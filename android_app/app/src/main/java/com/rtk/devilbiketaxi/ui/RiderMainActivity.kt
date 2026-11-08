package com.rtk.devilbiketaxi.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import kotlin.coroutines.resume
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import android.os.Looper
import android.location.LocationListener
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rtk.devilbiketaxi.R
import com.rtk.devilbiketaxi.data.model.*
import com.rtk.devilbiketaxi.data.network.RetrofitClient
import kotlinx.coroutines.*

class RiderMainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var token: String
    private lateinit var onlineSwitch: Switch
    private lateinit var statusText: TextView
    private lateinit var rideList: LinearLayout
    private var approved = false
    private var activeRideId = 0
    private var pollingJob: Job? = null
    private var currentStatus = ""
    private var suppressOnlineListener = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_rider_main)

        token = getSharedPreferences("rtk_session", MODE_PRIVATE)
            .getString("access_token", null).orEmpty()
        if (token.isBlank()) {
            openLogin()
            return
        }
        token = "Bearer $token"

        onlineSwitch = findViewById(R.id.switchOnline)
        statusText = findViewById(R.id.tvRiderStatus)
        rideList = findViewById(R.id.layoutNearbyRides)

        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            setOnline(false)
            getSharedPreferences("rtk_session", MODE_PRIVATE).edit().clear().apply()
            openLogin()
        }

        findViewById<Button>(R.id.btnRefreshRides).setOnClickListener {
            scope.launch { refreshNearbyRides() }
        }

        onlineSwitch.isEnabled = false
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.getRiderMe(token)
                }
                val rider = response.body()
                if (!response.isSuccessful || rider == null) {
                    statusText.text = "Rider account load failed (${response.code()})"
                    return@launch
                }

                approved = rider.verification_status == "approved"
                if (!approved) {
                    onlineSwitch.isChecked = false
                    onlineSwitch.isEnabled = false
                    statusText.text = "Verification: ${rider.verification_status}\nAdmin approval ke baad online ho sakenge."
                } else {
                    onlineSwitch.isEnabled = true
                    suppressOnlineListener = true
                    onlineSwitch.isChecked = rider.is_online
                    suppressOnlineListener = false
                    statusText.text = "Approved rider: ${rider.full_name}\nVehicle: ${rider.vehicle_number}\nLocation permission: ${if (hasLocationPermission()) "Granted" else "Not granted"}"
                    if (rider.is_online) startPolling()
                }
            } catch (e: Exception) {
                statusText.text = "Backend connection error: ${e.message}"
            }
        }

        onlineSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressOnlineListener) return@setOnCheckedChangeListener

            if (!approved) {
                suppressOnlineListener = true
                onlineSwitch.isChecked = false
                suppressOnlineListener = false
                statusText.text = "Rider approval required."
                return@setOnCheckedChangeListener
            }

            if (checked && !hasLocationPermission()) {
                suppressOnlineListener = true
                onlineSwitch.isChecked = false
                suppressOnlineListener = false

                statusText.text = "Location permission required. Please allow Approximate or Precise Location."

                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ),
                    701
                )
                return@setOnCheckedChangeListener
            }

            setOnline(checked)
        }

    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    private suspend fun freshLocation(): Location? {
        if (!hasLocationPermission()) return null

        return withTimeoutOrNull(25000L) {
            suspendCancellableCoroutine<Location?> { continuation ->
                val manager = getSystemService(LOCATION_SERVICE) as LocationManager

                val providers = try {
                    manager.getProviders(true).filter {
                        it == LocationManager.GPS_PROVIDER ||
                        it == LocationManager.NETWORK_PROVIDER
                    }
                } catch (_: Exception) {
                    emptyList()
                }

                if (providers.isEmpty()) {
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (continuation.isActive) {
                            continuation.resume(location)
                        }
                        try {
                            manager.removeUpdates(this)
                        } catch (_: Exception) {
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    try {
                        manager.removeUpdates(listener)
                    } catch (_: Exception) {
                    }
                }

                var requested = false

                // Try providers independently. A GPS permission failure
                // must not prevent a Network provider request.
                providers.forEach { provider ->
                    try {
                        manager.requestLocationUpdates(
                            provider,
                            1000L,
                            0f,
                            listener,
                            Looper.getMainLooper()
                        )
                        requested = true
                    } catch (_: SecurityException) {
                        // Continue with the next provider.
                    } catch (_: Exception) {
                        // Continue with the next provider.
                    }
                }

                if (!requested && continuation.isActive) {
                    continuation.resume(null)
                }
            }
        }
    }

    private fun setOnline(online: Boolean) {
        if (!online) {
            pollingJob?.cancel()
            scope.launch {
                try {
                    // Backend now permits offline without requiring a GPS fix.
                    val response = withContext(Dispatchers.IO) {
                        RetrofitClient.apiService.updateRiderLocation(
                            token,
                            RiderLocationRequest(0.0, 0.0, false)
                        )
                    }
                    statusText.text = if (response.isSuccessful) {
                        "You are OFFLINE."
                    } else {
                        "Offline update failed (${response.code()})"
                    }
                } catch (e: Exception) {
                    statusText.text = "Offline update error: ${e.message}"
                }
            }
            return
        }

        if (!hasLocationPermission()) {
            onlineSwitch.isChecked = false
            statusText.text = "Location permission required."
            return
        }

        scope.launch {
            statusText.text = "Getting fresh GPS location... Please wait."
            try {
                val location = freshLocation()
                if (location == null) {
                    onlineSwitch.isChecked = false
                    statusText.text =
                        "GPS location nahi mili. Phone Location ON karein, app permission allow karein aur open area mein retry karein."
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.updateRiderLocation(
                        token,
                        RiderLocationRequest(
                            location.latitude,
                            location.longitude,
                            true
                        )
                    )
                }

                if (response.isSuccessful) {
                    onlineSwitch.isChecked = true
                    statusText.text =
                        "You are ONLINE. GPS saved. Accuracy: ${location.accuracy.toInt()} m"
                    startPolling()
                } else {
                    onlineSwitch.isChecked = false
                    statusText.text =
                        "Online failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                }
            } catch (e: Exception) {
                onlineSwitch.isChecked = false
                statusText.text = "Location update error: ${e.message}"
            }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && onlineSwitch.isChecked) {
                val location = freshLocation()

                if (location == null) {
                    statusText.text = "GPS signal unavailable. Waiting for location..."
                } else {
                    try {
                        val response = withContext(Dispatchers.IO) {
                            RetrofitClient.apiService.updateRiderLocation(
                                token,
                                RiderLocationRequest(
                                    location.latitude,
                                    location.longitude,
                                    true
                                )
                            )
                        }

                        if (response.isSuccessful) {
                            statusText.text =
                                "ONLINE • GPS accuracy ${location.accuracy.toInt()} m"
                            refreshNearbyRides()
                        } else {
                            statusText.text =
                                "Location save failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                        }
                    } catch (e: Exception) {
                        statusText.text = "GPS/API error: ${e.message}"
                    }
                }

                delay(12000L)
            }
        }
    }

    private suspend fun refreshNearbyRides() {
        try {
            val response = withContext(Dispatchers.IO) {
                RetrofitClient.apiService.getNearbyRides(token)
            }
            if (!response.isSuccessful) {
                statusText.text = "Nearby rides: HTTP ${response.code()}"
                return
            }
            val rides = response.body().orEmpty()
            rideList.removeAllViews()

            if (rides.isEmpty()) {
                addMessage("Abhi nearby koi new ride nahi hai.")
                return
            }

            rides.forEach { ride ->
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(18, 14, 18, 14)
                    setBackgroundColor(0xFFF1F5F9.toInt())
                }
                val info = TextView(this).apply {
                    text = "Ride #${ride.ride_id}\n" +
                        "Pickup: ${ride.pickup_lat}, ${ride.pickup_lng}\n" +
                        "Drop: ${ride.drop_lat}, ${ride.drop_lng}\n" +
                        "Distance: ${ride.distance_km} km\n" +
                        "Fare: ₹${ride.total_fare}\nStatus: ${ride.status}"
                    textSize = 14f
                }
                val accept = Button(this).apply {
                    text = "Accept Ride #${ride.ride_id}"
                    setOnClickListener { acceptRide(ride.ride_id) }
                }
                card.addView(info)
                card.addView(accept)
                rideList.addView(card)
                rideList.addView(View(this).apply {
                    setBackgroundColor(0xFFE2E8F0.toInt())
                }, LinearLayout.LayoutParams(-1, 2))
            }
        } catch (e: Exception) {
            statusText.text = "Nearby ride refresh error: ${e.message}"
        }
    }

    private fun addMessage(message: String) {
        rideList.removeAllViews()
        rideList.addView(TextView(this).apply {
            text = message
            textSize = 15f
            setPadding(12, 20, 12, 20)
        })
    }

    private fun acceptRide(id: Int) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.respondRide(
                        token, id, RideActionRequest("accept")
                    )
                }
                if (response.isSuccessful) {
                    activeRideId = id
                    currentStatus = "accepted"
                    statusText.text = "Ride #$id accepted. Customer pickup ke liye ready ho jaiye."
                    showRideControls()
                    refreshNearbyRides()
                } else {
                    statusText.text = "Accept failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                    refreshNearbyRides()
                }
            } catch (e: Exception) {
                statusText.text = "Accept error: ${e.message}"
            }
        }
    }

    private fun showRideControls() {
        val container = findViewById<LinearLayout>(R.id.layoutActiveRide)
        container.removeAllViews()
        val title = TextView(this).apply {
            text = "Active Ride #$activeRideId"
            textSize = 18f
            setPadding(0, 8, 0, 8)
        }
        container.addView(title)

        val actions = when (currentStatus) {
            "accepted" -> listOf("arrived" to "Reached Pickup")
            "arrived" -> listOf("started" to "Start Ride")
            "started" -> listOf("completed" to "Complete Ride")
            else -> emptyList()
        }

        actions.forEach { (next, label) ->
            container.addView(Button(this).apply {
                text = label
                setOnClickListener { updateStatus(next) }
            })
        }
    }

    private fun updateStatus(next: String) {
        scope.launch {
            try {
                val response = withContext(Dispatchers.IO) {
                    RetrofitClient.apiService.updateRideStatus(
                        token, activeRideId, RideStatusRequest(next)
                    )
                }
                if (response.isSuccessful) {
                    currentStatus = next
                    statusText.text = "Ride #$activeRideId status: $next"
                    if (next == "completed") {
                        activeRideId = 0
                        findViewById<LinearLayout>(R.id.layoutActiveRide).removeAllViews()
                    } else {
                        showRideControls()
                    }
                    refreshNearbyRides()
                } else {
                    statusText.text = "Status update failed (${response.code()}): ${response.errorBody()?.string().orEmpty()}"
                }
            } catch (e: Exception) {
                statusText.text = "Status error: ${e.message}"
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 701) {
            val granted = hasLocationPermission()

            if (granted && approved) {
                statusText.text = "Location permission granted. Getting GPS fix..."
                setOnline(true)
            } else {
                suppressOnlineListener = true
                onlineSwitch.isChecked = false
                suppressOnlineListener = false
                statusText.text = "Location permission denied. Allow Approximate or Precise Location in App Settings."
            }
        }
    }

    private fun openLogin() {
        startActivity(Intent(this, LoginActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
}
