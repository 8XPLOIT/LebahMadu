package com.lebahmadu.app

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Local "DNS-only" VPN.
 *
 *  - Creates a TUN interface that advertises a virtual DNS server (10.111.222.2) and routes ONLY
 *    that /32 into the tunnel. All other traffic bypasses the VPN completely.
 *  - Every DNS query the system sends to the virtual server is read from the TUN, forwarded to
 *    all.dns.mullvad.net over DNS-over-HTTPS (encrypted), and the answer is written back.
 *  - The DoH connection to Mullvad is not routed through the TUN, so there is no loop.
 */
class DnsVpnService : VpnService() {

    companion object {
        private const val TAG = "DnsVpnService"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VIRTUAL_DNS = "10.111.222.2"
        private const val MTU = 1500
        private const val IP_HEADER = 20
        private const val UDP_HEADER = 8
        private const val MAX_DNS_PAYLOAD = MTU - IP_HEADER - UDP_HEADER
        private val VIRTUAL_DNS_BYTES: ByteArray = InetAddress.getByName(VIRTUAL_DNS).address
        private val DNS_MESSAGE = "application/dns-message".toMediaType()

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context) {
            context.startService(Intent(context, DnsVpnService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DnsVpnService::class.java))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    private var executor: ExecutorService? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dns(BootstrapDns)
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .callTimeout(6, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() {
        // User turned the VPN off from system settings, or another VPN took over.
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ------------------------------------------------------------ lifecycle

    @Synchronized
    private fun startVpn() {
        if (tun != null) return
        try {
            val pfd = Builder()
                .setSession("Lebah Madu DNS Pribadi")
                .setMtu(MTU)
                .addAddress(VPN_ADDRESS, 24)
                .addDnsServer(VIRTUAL_DNS)
                .addRoute(VIRTUAL_DNS, 32)
                .setBlocking(true)
                .establish()

            if (pfd == null) {
                Log.e(TAG, "establish() returned null (VPN permission missing or revoked)")
                stopSelf()
                return
            }
            tun = pfd
            executor = Executors.newFixedThreadPool(4)
            reader = thread(name = "dns-vpn-reader", isDaemon = true) { readLoop(pfd) }
            _running.value = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start VPN", e)
            stopVpn()
            stopSelf()
        }
    }

    @Synchronized
    private fun stopVpn() {
        _running.value = false
        reader?.interrupt()
        reader = null
        executor?.shutdownNow()
        executor = null
        try {
            tun?.close()
        } catch (e: IOException) {
        }
        tun = null
    }

    // ---------------------------------------------------------- packet loop

    private fun readLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val output = FileOutputStream(pfd.fileDescriptor)
        val buffer = ByteArray(32767)
        try {
            while (!Thread.currentThread().isInterrupted) {
                val length = input.read(buffer)
                if (length < 0) break
                if (length > 0) handlePacket(buffer, length, output)
            }
        } catch (e: IOException) {
            if (_running.value) Log.w(TAG, "TUN read ended: ${e.message}")
        } finally {
            if (_running.value) stopSelf() // ended unexpectedly
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, out: FileOutputStream) {
        if (len < IP_HEADER + UDP_HEADER) return
        if ((buf[0].toInt() shr 4) != 4) return                 // IPv4 only
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if (ihl < IP_HEADER || len < ihl + UDP_HEADER) return
        if (buf[9].toInt() != 17) return                        // UDP only
        for (i in 0..3) if (buf[16 + i] != VIRTUAL_DNS_BYTES[i]) return
        if (u16(buf, ihl + 2) != 53) return

        val udpLen = u16(buf, ihl + 4)
        val payloadLen = minOf(udpLen - UDP_HEADER, len - ihl - UDP_HEADER)
        if (payloadLen <= 0) return

        // Copy everything needed: `buf` is reused for the next packet.
        val query = buf.copyOfRange(ihl + UDP_HEADER, ihl + UDP_HEADER + payloadLen)
        val clientIp = buf.copyOfRange(12, 16)
        val clientPort = u16(buf, ihl)

        try {
            executor?.execute {
                val answer = resolveOverDoh(query) ?: buildServFail(query)
                val packet = buildUdpPacket(clientIp, clientPort, answer)
                synchronized(out) {
                    try {
                        out.write(packet)
                    } catch (e: IOException) {
                        Log.w(TAG, "TUN write failed: ${e.message}")
                    }
                }
            }
        } catch (e: RejectedExecutionException) {
            // Service is shutting down.
        }
    }

    // ----------------------------------------------------------- DNS logic

    private fun resolveOverDoh(query: ByteArray): ByteArray? = try {
        val request = Request.Builder()
            .url(AppConfig.DOH_URL)
            .header("Accept", "application/dns-message")
            .post(query.toRequestBody(DNS_MESSAGE))
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null
            else response.body?.bytes()?.takeIf { it.size in 12..MAX_DNS_PAYLOAD }
        }
    } catch (e: IOException) {
        Log.w(TAG, "DoH request failed: ${e.message}")
        null
    }

    /** Minimal SERVFAIL reply so apps fail fast instead of waiting for a timeout. */
    private fun buildServFail(query: ByteArray): ByteArray {
        val r = ByteArray(12)
        if (query.size >= 2) {
            r[0] = query[0]
            r[1] = query[1]
        }
        r[2] = 0x81.toByte() // QR=1, RD=1
        r[3] = 0x82.toByte() // RA=1, RCODE=2 (SERVFAIL)
        return r
    }

    /** Wraps a DNS payload in IPv4 + UDP, source = virtual DNS:53, destination = the client. */
    private fun buildUdpPacket(clientIp: ByteArray, clientPort: Int, payload: ByteArray): ByteArray {
        val total = IP_HEADER + UDP_HEADER + payload.size
        val p = ByteArray(total)
        p[0] = 0x45.toByte()              // IPv4, IHL=5
        putU16(p, 2, total)
        putU16(p, 6, 0x4000)              // Don't Fragment
        p[8] = 64                         // TTL
        p[9] = 17                         // UDP
        System.arraycopy(VIRTUAL_DNS_BYTES, 0, p, 12, 4)
        System.arraycopy(clientIp, 0, p, 16, 4)
        putU16(p, 10, ipChecksum(p, IP_HEADER))

        putU16(p, 20, 53)
        putU16(p, 22, clientPort)
        putU16(p, 24, UDP_HEADER + payload.size)
        putU16(p, 26, 0)                  // UDP checksum optional over IPv4
        System.arraycopy(payload, 0, p, 28, payload.size)
        return p
    }

    private fun u16(b: ByteArray, off: Int) =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte()
        b[off + 1] = v.toByte()
    }

    private fun ipChecksum(b: ByteArray, len: Int): Int {
        var sum = 0
        var i = 0
        while (i < len) {
            sum += u16(b, i)
            i += 2
        }
        while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    /** Resolves all.dns.mullvad.net from hardcoded IPs, avoiding a DNS-through-our-own-VPN loop. */
    private object BootstrapDns : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (hostname != AppConfig.DNS_HOST) throw UnknownHostException(hostname)
            // Literal IPs: no network lookup happens. Hostname is kept for SNI / cert validation.
            return AppConfig.DNS_BOOTSTRAP_IPS.map {
                InetAddress.getByAddress(hostname, InetAddress.getByName(it).address)
            }
        }
    }
}
