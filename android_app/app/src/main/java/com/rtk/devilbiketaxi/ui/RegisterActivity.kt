package com.rtk.devilbiketaxi.ui

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.rtk.devilbiketaxi.data.model.CustomerRegisterRequest
import com.rtk.devilbiketaxi.data.model.RiderRegisterRequest
import com.rtk.devilbiketaxi.data.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RegisterActivity : AppCompatActivity() {

    private fun field(hintText: String, type: Int = 1): EditText {
        return EditText(this).apply {
            hint = hintText
            inputType = type
            textSize = 16f
            setPadding(24, 16, 24, 16)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 24, 28, 24)
        }

        val title = TextView(this).apply {
            text = "RTK Devil Bike Taxi\nCreate Account"
            textSize = 24f
            setPadding(0, 8, 0, 24)
        }
        layout.addView(title)

        val name = field("Full Name")
        val email = field("Email Address", 33)
        val phone = field("Mobile Number", 3)
        val password = field("Create Password", 129)

        layout.addView(name)
        layout.addView(email)
        layout.addView(phone)
        layout.addView(password)

        val roleLabel = TextView(this).apply {
            text = "Select Account Type"
            textSize = 16f
            setPadding(0, 16, 0, 4)
        }
        layout.addView(roleLabel)

        val role = Spinner(this)
        role.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("Customer", "Rider")
        )
        layout.addView(role)

        val vehicle = field("Vehicle Number")
        val license = field("Driving License Number")
        layout.addView(vehicle)
        layout.addView(license)

        fun updateRoleFields() {
            val rider = role.selectedItemPosition == 1
            vehicle.visibility = if (rider) View.VISIBLE else View.GONE
            license.visibility = if (rider) View.VISIBLE else View.GONE
        }
        role.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) = updateRoleFields()
        }
        updateRoleFields()

        val submit = Button(this).apply {
            text = "Create Account & Send Verification Email"
        }
        layout.addView(submit)

        val note = TextView(this).apply {
            text = "Registration ke baad apna email kholkar verification link par click karein."
            textSize = 14f
            setPadding(0, 16, 0, 12)
        }
        layout.addView(note)

        scroll.addView(layout)
        setContentView(scroll)

        submit.setOnClickListener {
            val fullName = name.text.toString().trim()
            val emailText = email.text.toString().trim()
            val phoneText = phone.text.toString().trim()
            val passwordText = password.text.toString()
            val isRider = role.selectedItemPosition == 1

            if (fullName.isBlank() || emailText.isBlank() ||
                phoneText.isBlank() || passwordText.isBlank()
            ) {
                Toast.makeText(this, "Sabhi required fields bhariye", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (passwordText.length < 8) {
                Toast.makeText(this, "Password kam se kam 8 characters ka ho", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (isRider && (vehicle.text.isBlank() || license.text.isBlank())) {
                Toast.makeText(this, "Vehicle aur Driving License details bhariye", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val vehicleText = vehicle.text.toString().trim()
            val licenseText = license.text.toString().trim()

            submit.isEnabled = false
            submit.text = "Registering..."

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = if (isRider) {
                        RetrofitClient.apiService.registerRider(
                            RiderRegisterRequest(
                                fullName, emailText, phoneText, passwordText,
                                vehicleText,
                                licenseText
                            )
                        )
                    } else {
                        RetrofitClient.apiService.registerCustomer(
                            CustomerRegisterRequest(
                                fullName, emailText, phoneText, passwordText
                            )
                        )
                    }

                    val body = response.body()
                    val error = response.errorBody()?.string()

                    withContext(Dispatchers.Main) {
                        submit.isEnabled = true
                        submit.text = "Create Account & Send Verification Email"

                        if (response.isSuccessful && body != null) {
                            val message = if (body.email_sent) {
                                "Account created! Email mein verification link check karein."
                            } else {
                                "Account created, lekin email send nahi hua. Server SMTP settings check karein."
                            }
                            Toast.makeText(this@RegisterActivity, message, Toast.LENGTH_LONG).show()
                            finish()
                        } else {
                            Toast.makeText(
                                this@RegisterActivity,
                                "Registration failed (${response.code()}): ${error ?: "Unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        submit.isEnabled = true
                        submit.text = "Create Account & Send Verification Email"
                        Toast.makeText(
                            this@RegisterActivity,
                            "Connection error: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }
}
