package com.rtk.devilbiketaxi.data.model

data class LoginRequest(val email: String, val password: String)

data class UserInfo(
    val id: Int,
    val full_name: String,
    val email: String,
    val role: String,
    val phone: String? = null,
    val rider_status: String? = null
)

data class AuthResponse(val access_token: String, val user: UserInfo)

data class CurrentUserResponse(
    val id: Int,
    val full_name: String,
    val email: String,
    val phone: String?,
    val role: String,
    val is_email_verified: Boolean,
    val rider_status: String?,
    val is_online: Boolean
)

data class RiderMeResponse(
    val rider_id: Int,
    val full_name: String,
    val phone: String?,
    val vehicle_number: String,
    val verification_status: String,
    val is_online: Boolean,
    val lat: Double?,
    val lng: Double?
)

data class VehicleCategory(
    val id: Int,
    val name: String,
    val base_fare: Double,
    val per_km_rate: Double,
    val icon_url: String? = null
)

data class FareEstimateRequest(
    val pickup_lat: Double,
    val pickup_lng: Double,
    val drop_lat: Double,
    val drop_lng: Double
)

data class CategoryEstimate(
    val category_id: Int,
    val category_name: String,
    val distance_km: Double,
    val estimated_fare: Double
)

data class FareEstimateResponse(
    val distance_km: Double,
    val estimates: List<CategoryEstimate>
)

data class RideRequestPayload(
    val vehicle_category_id: Int,
    val pickup_lat: Double,
    val pickup_lng: Double,
    val drop_lat: Double,
    val drop_lng: Double
)

data class RideResponse(
    val message: String,
    val ride_id: Int,
    val total_fare: Double,
    val status: String,
    val distance_km: Double = 0.0
)

data class RideSummary(
    val ride_id: Int,
    val customer_id: Int,
    val rider_id: Int?,
    val vehicle_category_id: Int,
    val pickup_lat: Double,
    val pickup_lng: Double,
    val drop_lat: Double,
    val drop_lng: Double,
    val distance_km: Double,
    val total_fare: Double,
    val status: String
)

data class SurgeRequest(val extra_amount: Double)

data class RiderLocationRequest(
    val lat: Double,
    val lng: Double,
    val is_online: Boolean
)

data class GeneralResponse(val message: String)

data class RideActionRequest(val action: String)
data class RideStatusRequest(val status: String)

data class CustomerRegisterRequest(
    val full_name: String,
    val email: String,
    val phone: String,
    val password: String
)

data class RiderRegisterRequest(
    val full_name: String,
    val email: String,
    val phone: String,
    val password: String,
    val vehicle_number: String,
    val driving_license: String
)

data class RegisterResponse(
    val message: String,
    val user_id: Int,
    val email_sent: Boolean
)
