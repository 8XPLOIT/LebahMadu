package com.lebahmadu.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.lebahmadu.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        // Already logged in -> skip the login screen.
        if (SessionManager.isLoggedIn(this)) {
            openMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime()
            )
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.etCode.doAfterTextChanged { binding.tilCode.error = null }
        binding.etCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                attemptLogin()
                true
            } else false
        }
        binding.btnSubmit.setOnClickListener { attemptLogin() }
        setupBuyLicense()
    }

    /** "Belum punya kode login? Beli Lisensi DISINI." - bagian link membuka halaman pembelian. */
    private fun setupBuyLicense() {
        val prefix = getString(R.string.login_buy_prefix)
        val link = getString(R.string.login_buy_link)
        val text = SpannableString(prefix + link)
        val span = object : ClickableSpan() {
            override fun onClick(widget: View) {
                try {
                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.login_buy_url)))
                    )
                } catch (e: ActivityNotFoundException) {
                    toast(getString(R.string.err_network))
                }
            }

            override fun updateDrawState(ds: TextPaint) {
                ds.color = ContextCompat.getColor(this@LoginActivity, R.color.honey_400)
                ds.isUnderlineText = true
                ds.isFakeBoldText = true
            }
        }
        text.setSpan(span, prefix.length, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        binding.tvBuyLicense.text = text
        binding.tvBuyLicense.movementMethod = LinkMovementMethod.getInstance()
        binding.tvBuyLicense.highlightColor = Color.TRANSPARENT
    }

    private fun attemptLogin() {
        val code = binding.etCode.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            binding.tilCode.error = getString(R.string.login_empty)
            return
        }

        hideKeyboard()
        setLoading(true)

        lifecycleScope.launch {
            val result = ConfigRepository.fetch()
            setLoading(false)
            handleResult(code, result)
        }
    }

    private fun handleResult(code: String, result: ConfigResult) {
        when (result) {
            is ConfigResult.Failure -> toast(errorMessage(result))
            is ConfigResult.Success -> {
                val config = result.config
                if (!config.loginEnabled) {
                    toast(getString(R.string.login_disabled))
                } else if (code in config.codes) {
                    SessionManager.saveSession(this, code)
                    openMain()
                } else {
                    binding.tilCode.error = getString(R.string.login_invalid)
                }
            }
        }
    }

    private fun errorMessage(f: ConfigResult.Failure): String = when (f.error) {
        ConfigError.NO_INTERNET -> getString(R.string.err_no_internet)
        ConfigError.TIMEOUT -> getString(R.string.err_timeout)
        ConfigError.NETWORK -> getString(R.string.err_network)
        ConfigError.SERVER -> getString(R.string.err_server, f.httpCode)
        ConfigError.BAD_CONFIG -> getString(R.string.err_config)
    }

    private fun setLoading(loading: Boolean) {
        binding.progress.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnSubmit.isEnabled = !loading
        binding.etCode.isEnabled = !loading
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.etCode.windowToken, 0)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
