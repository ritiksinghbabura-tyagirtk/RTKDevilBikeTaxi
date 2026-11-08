package com.rtk.devilbiketaxi.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.rtk.devilbiketaxi.R
import com.rtk.devilbiketaxi.data.model.LoginRequest
import com.rtk.devilbiketaxi.data.network.RetrofitClient
import kotlinx.coroutines.*
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        handleVerificationLink(intent)

        val email = findViewById<EditText>(R.id.etEmail)
        val password = findViewById<EditText>(R.id.etPassword)
        val login = findViewById<Button>(R.id.btnLogin)
        val register = findViewById<Button>(R.id.btnRegister)

        register.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }

        login.setOnClickListener {
            val emailText = email.text.toString().trim()
            val passwordText = password.text.toString()
            if (emailText.isBlank() || passwordText.isBlank()) {
                Toast.makeText(this, "Email aur password bhariye", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            login.isEnabled = false
            login.text = "Please wait..."

            scope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.login(LoginRequest(emailText, passwordText))
                    }
                    val auth = response.body()
                    if (response.isSuccessful && auth != null) {
                        getSharedPreferences("rtk_session", MODE_PRIVATE).edit()
                            .putString("access_token", auth.access_token)
                            .putString("user_role", auth.user.role)
                            .putString("user_email", auth.user.email)
                            .putInt("user_id", auth.user.id)
                            .apply()

                        Toast.makeText(this@LoginActivity, "Welcome ${auth.user.full_name}", Toast.LENGTH_SHORT).show()
                        val destination = if (auth.user.role == "rider") {
                            RiderMainActivity::class.java
                        } else {
                            CustomerMainActivity::class.java
                        }
                        startActivity(Intent(this@LoginActivity, destination))
                        finish()
                    } else {
                        val error = response.errorBody()?.string().orEmpty()
                        val message = try {
                            JSONObject(error).optString("message").ifBlank {
                                JSONObject(error).optString("error", "Login failed")
                            }
                        } catch (_: Exception) {
                            "Login failed (${response.code()})"
                        }
                        Toast.makeText(this@LoginActivity, message, Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this@LoginActivity, "Connection error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    login.isEnabled = true
                    login.text = "Login to Account"
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVerificationLink(intent)
    }

    private fun handleVerificationLink(intent: Intent?) {
        val uri: Uri? = intent?.data
        if (uri?.scheme == "rtkdevilbiketaxi" && uri.host == "verify") {
            val status = uri.getQueryParameter("status")
            val message = if (status == "success") {
                "Email verified! Ab login karein."
            } else {
                "Email verification link invalid ya expire ho gaya."
            }
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
