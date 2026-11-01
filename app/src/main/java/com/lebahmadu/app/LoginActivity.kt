package com.lebahmadu.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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

            when (result) {
                is ConfigResult.Failure -> toast(errorMessage(result))
                is ConfigResult.Success -> {
                    val config = result.config
                    when {
                        !config.loginEnabled -> toast(getString(R.string.login_disabled))
                        code in config.codes -> {
                            SessionManager.saveSession(this@LoginActivity)
                            openMain()
                        }
                        else -> binding.tilCode.error = getString(R.string.login_invalid)
                    }
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
        binding.progress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
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
