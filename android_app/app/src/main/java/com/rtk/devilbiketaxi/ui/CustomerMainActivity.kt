package com.rtk.devilbiketaxi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rtk.devilbiketaxi.R
import com.rtk.devilbiketaxi.data.model.*
import com.rtk.devilbiketaxi.data.network.RetrofitClient
import kotlinx.coroutines.*

class CustomerMainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var token: String
    private lateinit var pickup: EditText
    private lateinit var drop: EditText
    private lateinit var result: TextView
    private lateinit var categorySpinner: Spinner
    private lateinit var bookButton: Button
    private lateinit var estimateButton: Button
    private lateinit var categories: List<VehicleCategory>
    private var activeRideId: Int = 0
    private var trackingJob: Job? = null

    private val mapPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { response ->
        if (response.resultCode == RESULT_OK) {
            val lat = response.data?.getDoubleExtra("lat", 0.0) ?: 0.0
            val lng = response.data?.getDoubleExtra("lng", 0.0) ?: 0.0
            val mode = response.data?.getStringExtra("mode")
            val value = "$lat, $lng"
            if (mode == "pickup") pickup.setText(value) else drop.setText(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_customer_main)

        token = getSharedPreferences("rtk_session", MODE_PRIVATE)
            .getString("access_token", null).orEmpty()
        if (token.isBlank()) {
            openLogin()
            return
        }
        token = "Bearer $token"

        pickup = findViewById(R.id.etPickup)
        drop = findViewById(R.id.etDrop)
        result = findViewById(R.id.tvResult)
        categorySpinner = findViewById(R.id.spinnerCategory)
        bookButton = findViewById(R.id.btnBookRide)
        estimateButton = findViewById(R.id.btnEstimate)

        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }

        findViewById<Button>(R.id.btnPickupMap).setOnClickListener {
            openMap("pickup", pickup.text.toString())
        }
        findViewById<Button>(R.id.btnDropMap).setOnClickListener {
            openMap("drop", drop.text.toString())
        }
        findViewById<Button>(R.id.btnMyLocation).setOnClickListener {
            useCurrentLocation()
        }
        findViewById<Button>(R.id.btnCancelRide).setOnClickListener {
            cancelRide()
        }

        bookButton.isEnabled = false

        scope.launch {
            try {
                val me = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getMe(token)
                }
                if (!me.isSuccessful || me.body()?.role != "customer") {
                    result.text = "Customer session invalid. Login again."
                    logout()
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getCategories()
                }
                if (!response.isSuccessful || response.body().isNullOrEmpty()) {
                    result.text = "Vehicle categories load nahi hui."
                    return@launch
                }

                categories = response.body()!!
                categorySpinner.adapter = ArrayAdapter(
                    this@CustomerMainActivity,
                    android.R.layout.simple_spinner_dropdown_item,
                    categories.map {
                        "${it.name}  |  Base ₹${it.base_fare} + ₹${it.per_km_rate}/km"
                    }
                )
                bookButton.isEnabled = true
                result.text = "Pickup aur Drop map par select karein. Phir fare estimate karein."
            } catch (e: Exception) {
                result.text = "Backend connection error: ${e.message}"
            }
        }

        estimateButton.setOnClickListener { estimateFare() }
        bookButton.setOnClickListener { bookRide() }

        activeRideId = getSharedPreferences("rtk_session", MODE_PRIVATE)
            .getInt("active_customer_ride", 0)
        if (activeRideId > 0) startTracking()
    }

    private fun openMap(mode: String, raw: String) {
        val parts = raw.split(",")
        val lat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 25.1460
        val lng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 82.5700
        val intent = Intent(this, MapPickerActivity::class.java).apply {
            putExtra("mode", mode)
            putExtra("lat", lat)
            putExtra("lng", lng)
        }
        mapPicker.launch(intent)
    }

    private fun useCurrentLocation() {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!fine && !coarse) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                801
            )
            return
        }

        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            val location = manager.getProviders(true).mapNotNull {
                try { manager.getLastKnownLocation(it) }
                catch (_: SecurityException) { null }
            }.maxByOrNull { it.time }

            if (location == null) {
                result.text = "GPS fix abhi nahi mila. Location ON karke open area mein retry karein."
            } else {
                pickup.setText("${location.latitude}, ${location.longitude}")
                result.text = "Current GPS pickup set ho gaya. Map se exact point adjust kar sakte hain."
            }
        } catch (e: Exception) {
            result.text = "GPS error: ${e.message}"
        }
    }

    private fun coordinates(): FareEstimateRequest? {
        val p = pickup.text.toString().split(",")
        val d = drop.text.toString().split(",")
        if (p.size != 2 || d.size != 2) return null

        val values = listOf(
            p[0].trim().toDoubleOrNull(),
            p[1].trim().toDoubleOrNull(),
            d[0].trim().toDoubleOrNull(),
            d[1].trim().toDoubleOrNull()
        )
        if (values.any { it == null }) return null

        val v = values.filterNotNull()
        if (v[0] !in -90.0..90.0 || v[2] !in -90.0..90.0 ||
            v[1] !in -180.0..180.0 || v[3] !in -180.0..180.0
        ) return null

        return FareEstimateRequest(v[0], v[1], v[2], v[3])
    }

    private fun estimateFare() {
        val request = coordinates()
        if (request == null) {
            result.text = "Pickup aur Drop dono map par select karein."
            return
        }

        estimateButton.isEnabled = false
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.estimateFare(request)
                }
                if (response.isSuccessful && response.body() != null) {
                    val data = response.body()!!
                    result.text = buildString {
                        append("Estimated straight-line distance: ${data.distance_km} km\n")
                        append("Vehicle estimates:\n\n")
                        data.estimates.forEach {
                            append("${it.category_name}: ₹${it.estimated_fare}\n")
                        }
                        append("\nFinal fare booking ke waqt backend calculate karega.")
                    }
                } else {
                    result.text = "Fare estimate failed: HTTP ${response.code()}"
                }
            } catch (e: Exception) {
                result.text = "Network error: ${e.message}"
            } finally {
                estimateButton.isEnabled = true
            }
        }
    }

    private fun bookRide() {
        val coords = coordinates()
        if (coords == null || !::categories.isInitialized || categories.isEmpty()) {
            result.text = "Valid pickup, drop aur vehicle select karein."
            return
        }

        val category = categories.getOrNull(categorySpinner.selectedItemPosition)
        if (category == null) return

        bookButton.isEnabled = false
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.requestRide(
                        token,
                        RideRequestPayload(
                            category.id,
                            coords.pickup_lat,
                            coords.pickup_lng,
                            coords.drop_lat,
                            coords.drop_lng
                        )
                    )
                }
                val ride = response.body()
                if (response.isSuccessful && ride != null) {
                    activeRideId = ride.ride_id
                    getSharedPreferences("rtk_session", MODE_PRIVATE).edit()
                        .putInt("active_customer_ride", activeRideId).apply()
                    result.text = "Ride requested!\nRide ID: ${ride.ride_id}\nVehicle: ${category.name}\nFare: ₹${ride.total_fare}\nStatus: ${ride.status}\n\nNearby rider search chal rahi hai."
                    startTracking()
                } else {
                    result.text = "Booking failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                }
            } catch (e: Exception) {
                result.text = "Booking error: ${e.message}"
            } finally {
                bookButton.isEnabled = true
            }
        }
    }

    private fun startTracking() {
        trackingJob?.cancel()
        trackingJob = scope.launch {
            while (isActive && activeRideId > 0) {
                try {
                    val response = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.getRide(token, activeRideId)
                    }
                    val ride = response.body()
                    if (response.isSuccessful && ride != null) {
                        result.text = "Ride ID: ${ride.ride_id}\nStatus: ${ride.status}\nFare: ₹${ride.total_fare}\nDistance: ${ride.distance_km} km\n\n" +
                            when (ride.status) {
                                "searching" -> "Nearby rider search mein hai..."
                                "accepted" -> "Rider ne ride accept kar li hai."
                                "arrived" -> "Rider pickup location par pahunch gaya."
                                "started" -> "Your ride is in progress."
                                "completed" -> "Ride completed."
                                "cancelled" -> "Ride cancelled."
                                else -> "Status refresh ho raha hai."
                            }

                        if (ride.status in listOf("completed", "cancelled")) {
                            activeRideId = 0
                            getSharedPreferences("rtk_session", MODE_PRIVATE).edit()
                                .remove("active_customer_ride").apply()
                            break
                        }
                    } else if (response.code() == 404) {
                        activeRideId = 0
                        break
                    }
                } catch (_: Exception) {
                    result.text = "Ride tracking: connection retry..."
                }
                delay(8000)
            }
        }
    }

    private fun cancelRide() {
        if (activeRideId <= 0) {
            result.text = "Abhi koi active ride nahi hai."
            return
        }
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.updateRideStatus(
                        token, activeRideId, RideStatusRequest("cancel")
                    )
                }
                if (response.isSuccessful) {
                    result.text = "Ride cancelled."
                    trackingJob?.cancel()
                    activeRideId = 0
                    getSharedPreferences("rtk_session", MODE_PRIVATE).edit()
                        .remove("active_customer_ride").apply()
                } else {
                    result.text = "Cancel failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                }
            } catch (e: Exception) {
                result.text = "Cancel error: ${e.message}"
            }
        }
    }

    private fun logout() {
        trackingJob?.cancel()
        getSharedPreferences("rtk_session", MODE_PRIVATE).edit().clear().apply()
        openLogin()
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
