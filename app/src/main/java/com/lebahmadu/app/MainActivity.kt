package com.lebahmadu.app

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.VpnService
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.lebahmadu.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                DnsVpnService.start(this)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_LONG).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Safety net: never show content without a session.
        if (!SessionManager.isLoggedIn(this)) {
            goToLogin()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyInsets()
        setupWebView()
        setupControls()
        setupBackHandling()
        observeDnsState()
        validateSession()

        if (savedInstanceState != null) {
            binding.webView.restoreState(savedInstanceState)
        } else {
            binding.webView.loadUrl(AppConfig.WEB_URL)
        }
    }

    // ---------------------------------------------------------------- UI

    private fun applyInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupControls() {
        binding.btnDns.setOnClickListener { toggleDns() }
        binding.btnRefresh.setOnClickListener { refreshPage() }
        binding.btnLogout.setOnClickListener { logout() }
        binding.btnRetry.setOnClickListener {
            binding.errorGroup.visibility = View.GONE
            binding.webView.loadUrl(binding.webView.url ?: AppConfig.WEB_URL)
        }
    }

    /** Memuat ulang halaman yang sedang tampil (mis. halaman video setelah pengalihan). */
    private fun refreshPage() {
        binding.errorGroup.visibility = View.GONE
        val current = binding.webView.url
        if (current.isNullOrEmpty() || current == "about:blank") {
            binding.webView.loadUrl(AppConfig.WEB_URL)
        } else {
            binding.webView.reload()
        }
    }

    private fun observeDnsState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DnsVpnService.running.collect { renderDnsState(it) }
            }
        }
    }

    private fun renderDnsState(on: Boolean) {
        binding.btnDns.text = getString(if (on) R.string.dns_on else R.string.dns_off)
        val color = ContextCompat.getColor(this, if (on) R.color.red_500 else R.color.zinc_700)
        binding.btnDns.strokeColor = ColorStateList.valueOf(color)
        binding.btnDns.setTextColor(
            ContextCompat.getColor(this, if (on) R.color.red_500 else R.color.zinc_100)
        )
    }

    // ----------------------------------------------------------- Private DNS

    private fun toggleDns() {
        if (DnsVpnService.running.value) {
            DnsVpnService.stop(this)
            return
        }
        // Shows the system "Connection request" dialog the first time.
        val consentIntent = VpnService.prepare(this)
        if (consentIntent != null) vpnPermissionLauncher.launch(consentIntent)
        else DnsVpnService.start(this)
    }

    // ---------------------------------------------------------------- Logout

    /** Jika administrator menghapus/mengubah kode di config.json, sesi lama otomatis berakhir. */
    private fun validateSession() {
        val savedCode = SessionManager.getCode(this) ?: return
        lifecycleScope.launch {
            val result = ConfigRepository.fetch()
            // Offline / gagal fetch -> sesi tetap dipertahankan.
            if (result is ConfigResult.Success && savedCode !in result.config.codes) {
                logout(getString(R.string.session_expired))
            }
        }
    }

    private fun logout(message: String? = null) {
        DnsVpnService.stop(this)
        SessionManager.clear(this)
        binding.webView.stopLoading()
        if (message != null) Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        goToLogin()
    }

    private fun goToLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val wv = binding.webView
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // Pemutar video pihak ketiga (mis. jwplyr) sering membutuhkan cookie pihak ketiga.
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        wv.setBackgroundColor(ContextCompat.getColor(this, R.color.zinc_950))

        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportMultipleWindows(false) // target=_blank / window.open tetap di WebView yang sama
        }

        // Tanpa bilah alamat, tanpa menu tekan-lama pada link (agar URL tidak bisa disalin/dilihat).
        WebView.setWebContentsDebuggingEnabled(false)
        wv.isLongClickable = false
        wv.isHapticFeedbackEnabled = false
        wv.setOnLongClickListener { true }

        wv.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // http/https/about dimuat DI DALAM WebView (termasuk redirect & pemutar video).
                // Skema lain (intent:, market:, tel:, mailto:, dll.) DIBLOKIR: tidak ada
                // browser atau aplikasi lain yang pernah dibuka.
                val scheme = request.url.scheme?.lowercase()
                return !(scheme == "http" || scheme == "https" || scheme == "about")
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                binding.errorGroup.visibility = View.GONE
                binding.pageProgress.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String?) {
                binding.pageProgress.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    binding.pageProgress.visibility = View.GONE
                    binding.errorGroup.visibility = View.VISIBLE
                }
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pageProgress.setProgressCompat(newProgress, true)
            }

            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                showCustomView(view, callback)
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            // Prevents a null-poster crash on some devices when a <video> starts.
            override fun getDefaultVideoPoster(): Bitmap =
                Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customViewCallback = callback
        binding.contentGroup.visibility = View.GONE
        binding.fullscreenContainer.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        binding.fullscreenContainer.visibility = View.VISIBLE
        WindowCompat.getInsetsController(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun hideCustomView() {
        val view = customView ?: return
        binding.fullscreenContainer.removeView(view)
        binding.fullscreenContainer.visibility = View.GONE
        binding.contentGroup.visibility = View.VISIBLE
        customViewCallback?.onCustomViewHidden()
        customView = null
        customViewCallback = null
        WindowCompat.getInsetsController(window, binding.root)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    customView != null -> hideCustomView()
                    binding.webView.canGoBack() -> binding.webView.goBack()
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            }
        })
    }

    // -------------------------------------------------------------- Lifecycle

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::binding.isInitialized) binding.webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized) binding.webView.onResume()
    }

    override fun onPause() {
        if (::binding.isInitialized) binding.webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            hideCustomView()
            binding.webView.apply {
                stopLoading()
                (parent as? ViewGroup)?.removeView(this)
                destroy()
            }
        }
        super.onDestroy()
    }
}
