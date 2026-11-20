package com.google.weatherapp.data.api

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val GOOGLE_BASE_URL = "https://weather.googleapis.com/v1/"
    private const val OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/"
    private const val OPEN_METEO_AQI_URL = "https://air-quality-api.open-meteo.com/"
    private const val OPEN_METEO_GEO_URL = "https://geocoding-api.open-meteo.com/"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    internal val okHttpClient = OkHttpClient.Builder().apply {
        if (com.google.weatherapp.BuildConfig.DEBUG) {
            addInterceptor(loggingInterceptor)
        }
    }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val weatherService: GoogleWeatherApiService by lazy {
        Retrofit.Builder()
            .baseUrl(GOOGLE_BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoogleWeatherApiService::class.java)
    }

    val openMeteoForecastService: OpenMeteoForecastService by lazy {
        Retrofit.Builder()
            .baseUrl(OPEN_METEO_FORECAST_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoForecastService::class.java)
    }

    val openMeteoAirQualityService: OpenMeteoAirQualityService by lazy {
        Retrofit.Builder()
            .baseUrl(OPEN_METEO_AQI_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoAirQualityService::class.java)
    }

    val openMeteoGeocodingService: OpenMeteoGeocodingService by lazy {
        Retrofit.Builder()
            .baseUrl(OPEN_METEO_GEO_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenMeteoGeocodingService::class.java)
    }

}
