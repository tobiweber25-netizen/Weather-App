package com.google.weatherapp.data.api

import com.google.weatherapp.data.model.CurrentConditionsResponse
import com.google.weatherapp.data.model.DailyForecastResponse
import com.google.weatherapp.data.model.HourlyForecastResponse
import retrofit2.http.GET
import retrofit2.http.Query

interface GoogleWeatherApiService {

    @GET("./currentConditions:lookup")
    suspend fun getCurrentConditions(
        @Query("key") apiKey: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("unitsSystem") unitsSystem: String = "METRIC",
        @Query("languageCode") languageCode: String = "en"
    ): CurrentConditionsResponse

    @GET("forecast/hours:lookup")
    suspend fun getHourlyForecast(
        @Query("key") apiKey: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("unitsSystem") unitsSystem: String = "METRIC",
        @Query("languageCode") languageCode: String = "en",
        @Query("hours") hours: Int = 48,
        @Query("pageSize") pageSize: Int = 48
    ): HourlyForecastResponse

    @GET("forecast/days:lookup")
    suspend fun getDailyForecast(
        @Query("key") apiKey: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("unitsSystem") unitsSystem: String = "METRIC",
        @Query("languageCode") languageCode: String = "en",
        @Query("days") days: Int = 10,
        @Query("pageSize") pageSize: Int = 10
    ): DailyForecastResponse

    // Aliases to support lookupHours and lookupDays naming conventions
    @GET("forecast/hours:lookup")
    suspend fun lookupHours(
        @Query("key") apiKey: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("unitsSystem") unitsSystem: String = "METRIC",
        @Query("languageCode") languageCode: String = "en",
        @Query("hours") hours: Int = 48,
        @Query("pageSize") pageSize: Int = 48
    ): HourlyForecastResponse = getHourlyForecast(apiKey, latitude, longitude, unitsSystem, languageCode, hours, pageSize)

    @GET("forecast/days:lookup")
    suspend fun lookupDays(
        @Query("key") apiKey: String,
        @Query("location.latitude") latitude: Double,
        @Query("location.longitude") longitude: Double,
        @Query("unitsSystem") unitsSystem: String = "METRIC",
        @Query("languageCode") languageCode: String = "en",
        @Query("days") days: Int = 10,
        @Query("pageSize") pageSize: Int = 10
    ): DailyForecastResponse = getDailyForecast(apiKey, latitude, longitude, unitsSystem, languageCode, days, pageSize)
}
