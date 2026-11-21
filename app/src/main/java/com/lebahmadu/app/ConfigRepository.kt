package com.lebahmadu.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Format config.json:
 * {
 *   "login_enabled": true,
 *   "codes": [
 *     { "code": "n3m0", "mulai": "21/11/2026", "bulan": 1 },   // aktif 30 hari x bulan sejak "mulai"
 *     { "code": "yon",  "expired": "31/12/2099" },             // aktif sampai akhir tanggal ini
 *     "hardi"                                                  // tanpa batas waktu
 *   ]
 * }
 * Semua tanggal format DD/MM/YYYY (contoh 31/12/2099), zona waktu WIB (Asia/Jakarta).
 * Format lama YYYY-MM-DD masih diterima supaya config lama tidak rusak.
 * Waktu "sekarang" diambil dari header Date server, jadi mundurkan jam HP tidak berguna.
 */
data class RemoteConfig(
    val loginEnabled: Boolean,
    /** kode -> waktu habis (epoch ms), null = tanpa batas. */
    val codes: Map<String, Long?>,
    val serverTimeMs: Long
) {
    /** null = kode tidak ada; false = expired; true = aktif. */
    fun isActive(code: String): Boolean? {
        if (!codes.containsKey(code)) return null
        val exp = codes[code] ?: return true
        return serverTimeMs < exp
    }
}

enum class ConfigError { NO_INTERNET, TIMEOUT, NETWORK, SERVER, BAD_CONFIG }

sealed interface ConfigResult {
    data class Success(val config: RemoteConfig) : ConfigResult
    data class Failure(val error: ConfigError, val httpCode: Int = 0) : ConfigResult
}

object ConfigRepository {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetch(): ConfigResult = withContext(Dispatchers.IO) {
        try {
            // Cache-buster: raw.githubusercontent.com caches per full URL for a few minutes.
            val url = AppConfig.CONFIG_URL.toHttpUrl().newBuilder()
                .addQueryParameter("t", System.currentTimeMillis().toString())
                .build()
            val request = Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    ConfigResult.Failure(ConfigError.SERVER, response.code)
                } else {
                    val body = response.body?.string().orEmpty()
                    val serverTime = response.headers.getDate("Date")?.time
                        ?: System.currentTimeMillis()
                    ConfigResult.Success(parse(body, serverTime))
                }
            }
        } catch (e: UnknownHostException) {
            ConfigResult.Failure(ConfigError.NO_INTERNET)
        } catch (e: SocketTimeoutException) {
            ConfigResult.Failure(ConfigError.TIMEOUT)
        } catch (e: IOException) {
            ConfigResult.Failure(ConfigError.NETWORK)
        } catch (e: JSONException) {
            ConfigResult.Failure(ConfigError.BAD_CONFIG)
        } catch (e: IllegalArgumentException) {
            ConfigResult.Failure(ConfigError.BAD_CONFIG)
        }
    }

    private val DAY_MS = 24L * 60 * 60 * 1000

    private fun parse(raw: String, serverTime: Long): RemoteConfig {
        val json = JSONObject(raw)
        val enabled = json.getBoolean("login_enabled")
        val array = json.optJSONArray("codes") ?: JSONArray()
        val codes = mutableMapOf<String, Long?>()
        for (i in 0 until array.length()) {
            val item = array.get(i)
            if (item is JSONObject) {
                val code = item.optString("code").trim()
                if (code.isEmpty()) continue
                val expiry: Long? = when {
                    item.has("expired") -> ExpiryDate.parse(item.getString("expired")) + DAY_MS
                    item.has("mulai") -> {
                        val bulan = item.optInt("bulan", 1).coerceAtLeast(0)
                        ExpiryDate.parse(item.getString("mulai")) + bulan * 30L * DAY_MS
                    }
                    else -> null
                }
                codes[code] = expiry
            } else {
                val code = item.toString().trim()
                if (code.isNotEmpty()) codes[code] = null
            }
        }
        return RemoteConfig(enabled, codes, serverTime)
    }
}
