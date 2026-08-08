package ir.factory.entryexit.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ir.factory.entryexit.R
import ir.factory.entryexit.data.AuthRepository
import ir.factory.entryexit.data.Session
import ir.factory.entryexit.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch

/**
 * The app's true entry point (see AndroidManifest — this holds the LAUNCHER intent-filter now,
 * not MainActivity). Signs a guard/admin in, or creates a new account, then loads their role
 * from Firestore before handing off to MainActivity.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepository = AuthRepository()
    private var isSignUpMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnPrimary.setOnClickListener { if (isSignUpMode) attemptSignUp() else attemptSignIn() }
        binding.btnToggleMode.setOnClickListener { toggleMode() }

        // If Firebase already remembers a signed-in user (app reopened), skip straight past login.
        if (authRepository.isSignedIn()) {
            setLoading(true)
            lifecycleScope.launch {
                val result = authRepository.restoreSessionIfSignedIn()
                setLoading(false)
                if (result != null && result.isSuccess) {
                    Session.set(result.getOrNull())
                    goToMain()
                }
                // If it failed (e.g. profile doc missing), just stay on the login screen normally.
            }
        }
    }

    private fun toggleMode() {
        isSignUpMode = !isSignUpMode
        binding.tilName.visibility = if (isSignUpMode) View.VISIBLE else View.GONE
        binding.btnPrimary.text = getString(if (isSignUpMode) R.string.login_btn_signup else R.string.login_btn_signin)
        binding.btnToggleMode.text = getString(if (isSignUpMode) R.string.login_toggle_to_signin else R.string.login_toggle_to_signup)
    }

    private fun attemptSignIn() {
        val email = binding.etEmail.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()
        if (!validateEmailPassword(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            val result = authRepository.signIn(email, password)
            setLoading(false)
            result.onSuccess { profile ->
                Session.set(profile)
                goToMain()
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun attemptSignUp() {
        val name = binding.etName.text?.toString().orEmpty()
        val email = binding.etEmail.text?.toString().orEmpty()
        val password = binding.etPassword.text?.toString().orEmpty()

        if (name.isBlank()) {
            binding.tilName.error = getString(R.string.error_name_empty)
            return
        }
        binding.tilName.error = null
        if (!validateEmailPassword(email, password)) return

        setLoading(true)
        lifecycleScope.launch {
            val result = authRepository.signUp(email, password, name)
            setLoading(false)
            result.onSuccess { profile ->
                Session.set(profile)
                toast(getString(R.string.login_signup_success, profile.role.displayName))
                goToMain()
            }.onFailure { error ->
                toast(error.message ?: getString(R.string.error_generic))
            }
        }
    }

    private fun validateEmailPassword(email: String, password: String): Boolean {
        var ok = true
        if (email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            binding.tilEmail.error = getString(R.string.login_error_email)
            ok = false
        } else {
            binding.tilEmail.error = null
        }
        if (password.length < 6) {
            binding.tilPassword.error = getString(R.string.login_error_password)
            ok = false
        } else {
            binding.tilPassword.error = null
        }
        return ok
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnPrimary.isEnabled = !loading
        binding.btnToggleMode.isEnabled = !loading
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
    }
}
