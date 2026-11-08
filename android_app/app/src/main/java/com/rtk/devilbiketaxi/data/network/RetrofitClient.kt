package com.rtk.devilbiketaxi.data.network

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    // Flask server isi Android phone ke PRoot Ubuntu mein chal raha hai.
    // VPS par deploy karne ke baad is URL ko VPS HTTPS URL se replace karein.
    const val BASE_URL = "http://127.0.0.1:5000/api/v1/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        // Password aur JWT ko logs mein expose na karein.
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    val apiService: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
