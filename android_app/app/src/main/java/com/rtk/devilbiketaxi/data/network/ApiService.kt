package com.rtk.devilbiketaxi.data.network

import com.rtk.devilbiketaxi.data.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {
    @POST("auth/register/customer")
    suspend fun registerCustomer(@Body request: CustomerRegisterRequest): Response<RegisterResponse>

    @POST("auth/register/rider")
    suspend fun registerRider(@Body request: RiderRegisterRequest): Response<RegisterResponse>

    @POST("auth/login")
    suspend fun login(@Body request: LoginRequest): Response<AuthResponse>

    @GET("auth/me")
    suspend fun getMe(@Header("Authorization") token: String): Response<CurrentUserResponse>

    @GET("rides/categories")
    suspend fun getCategories(): Response<List<VehicleCategory>>

    @POST("rides/estimate")
    suspend fun estimateFare(@Body request: FareEstimateRequest): Response<FareEstimateResponse>

    @POST("rides/request")
    suspend fun requestRide(
        @Header("Authorization") token: String,
        @Body request: RideRequestPayload
    ): Response<RideResponse>

    @GET("rider/me")
    suspend fun getRiderMe(@Header("Authorization") token: String): Response<RiderMeResponse>

    @POST("rider/location")
    suspend fun updateRiderLocation(
        @Header("Authorization") token: String,
        @Body request: RiderLocationRequest
    ): Response<GeneralResponse>

    @GET("rider/rides/nearby")
    suspend fun getNearbyRides(
        @Header("Authorization") token: String
    ): Response<List<RideSummary>>

    @POST("rides/{ride_id}/respond")
    suspend fun respondRide(
        @Header("Authorization") token: String,
        @Path("ride_id") rideId: Int,
        @Body request: RideActionRequest
    ): Response<GeneralResponse>

    @POST("rides/{ride_id}/status")
    suspend fun updateRideStatus(
        @Header("Authorization") token: String,
        @Path("ride_id") rideId: Int,
        @Body request: RideStatusRequest
    ): Response<GeneralResponse>

    @GET("rides/history")
    suspend fun getRideHistory(
        @Header("Authorization") token: String
    ): Response<List<RideSummary>>

    @GET("rides/{ride_id}")
    suspend fun getRide(
        @Header("Authorization") token: String,
        @Path("ride_id") rideId: Int
    ): Response<RideSummary>
}
