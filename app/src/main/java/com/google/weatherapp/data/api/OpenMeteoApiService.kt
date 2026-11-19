package com.google.weatherapp.data.api

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Query

interface OpenMeteoForecastService {
    @GET("v1/forecast")
    suspend fun getForecast(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,cloud_cover,pressure_msl,surface_pressure,wind_speed_10m,wind_direction_10m,wind_gusts_10m",
        @Query("hourly") hourly: String = "temperature_2m,apparent_temperature,precipitation_probability,precipitation,weather_code,wind_speed_10m,wind_direction_10m,wind_gusts_10m,uv_index,relative_humidity_2m,visibility,is_day,cloud_cover",
        @Query("daily") daily: String = "weather_code,temperature_2m_max,temperature_2m_min,apparent_temperature_max,apparent_temperature_min,sunrise,sunset,uv_index_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max,wind_gusts_10m_max,wind_direction_10m_dominant",
        @Query("minutely_15") minutely15: String? = "precipitation,precipitation_probability,weather_code",
        @Query("forecast_minutely_15") forecastMinutely15: Int? = 96,
        @Query("elevation") elevation: Double? = null,
        @Query("models") models: String? = null,
        @Query("timezone") timezone: String = "auto",
        @Query("forecast_days") forecastDays: Int = 10
    ): OpenMeteoForecastResponse
}

interface OpenMeteoAirQualityService {
    @GET("v1/air-quality")
    suspend fun getAirQuality(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "us_aqi,european_aqi,pm10,pm2_5",
        @Query("timezone") timezone: String = "auto"
    ): OpenMeteoAirQualityResponse
}

interface OpenMeteoGeocodingService {
    @GET("v1/search")
    suspend fun searchLocations(
        @Query("name") query: String,
        @Query("count") count: Int = 8,
        @Query("language") language: String = "en",
        @Query("format") format: String = "json"
    ): OpenMeteoGeocodingResponse
}

data class OpenMeteoForecastResponse(
    @SerializedName("timezone") val timezone: String? = null,
    @SerializedName("elevation") val elevation: Double? = null,
    @SerializedName("current") val current: OpenMeteoCurrent? = null,
    @SerializedName("hourly") val hourly: OpenMeteoHourly? = null,
    @SerializedName("daily") val daily: OpenMeteoDaily? = null,
    @SerializedName("minutely_15") val minutely15: OpenMeteoMinutely15? = null
)

data class OpenMeteoMinutely15(
    @SerializedName("time") val time: List<String>? = null,
    @SerializedName("precipitation") val precipitation: List<Double?>? = null,
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int?>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int?>? = null
)

data class OpenMeteoCurrent(
    @SerializedName("time") val time: String? = null,
    @SerializedName("temperature_2m") val temperature2m: Double? = null,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: Int? = null,
    @SerializedName("apparent_temperature") val apparentTemperature: Double? = null,
    @SerializedName("is_day") val isDay: Int? = null,
    @SerializedName("precipitation") val precipitation: Double? = null,
    @SerializedName("weather_code") val weatherCode: Int? = null,
    @SerializedName("cloud_cover") val cloudCover: Int? = null,
    @SerializedName("pressure_msl") val pressureMsl: Double? = null,
    @SerializedName("surface_pressure") val surfacePressure: Double? = null,
    @SerializedName("wind_speed_10m") val windSpeed10m: Double? = null,
    @SerializedName("wind_direction_10m") val windDirection10m: Int? = null,
    @SerializedName("wind_gusts_10m") val windGusts10m: Double? = null
)

data class OpenMeteoHourly(
    @SerializedName("time") val time: List<String>? = null,
    @SerializedName("temperature_2m") val temperature2m: List<Double?>? = null,
    @SerializedName("apparent_temperature") val apparentTemperature: List<Double?>? = null,
    @SerializedName("precipitation_probability") val precipitationProbability: List<Int?>? = null,
    @SerializedName("precipitation") val precipitation: List<Double?>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int?>? = null,
    @SerializedName("wind_speed_10m") val windSpeed10m: List<Double?>? = null,
    @SerializedName("wind_direction_10m") val windDirection10m: List<Int?>? = null,
    @SerializedName("wind_gusts_10m") val windGusts10m: List<Double?>? = null,
    @SerializedName("uv_index") val uvIndex: List<Double?>? = null,
    @SerializedName("relative_humidity_2m") val relativeHumidity2m: List<Int?>? = null,
    @SerializedName("visibility") val visibility: List<Double?>? = null,
    @SerializedName("is_day") val isDay: List<Int?>? = null,
    @SerializedName("cloud_cover") val cloudCover: List<Int?>? = null
)

data class OpenMeteoDaily(
    @SerializedName("time") val time: List<String>? = null,
    @SerializedName("weather_code") val weatherCode: List<Int?>? = null,
    @SerializedName("temperature_2m_max") val temperature2mMax: List<Double?>? = null,
    @SerializedName("temperature_2m_min") val temperature2mMin: List<Double?>? = null,
    @SerializedName("apparent_temperature_max") val apparentTemperatureMax: List<Double?>? = null,
    @SerializedName("apparent_temperature_min") val apparentTemperatureMin: List<Double?>? = null,
    @SerializedName("sunrise") val sunrise: List<String>? = null,
    @SerializedName("sunset") val sunset: List<String>? = null,
    @SerializedName("uv_index_max") val uvIndexMax: List<Double?>? = null,
    @SerializedName("precipitation_sum") val precipitationSum: List<Double?>? = null,
    @SerializedName("precipitation_probability_max") val precipitationProbabilityMax: List<Int?>? = null,
    @SerializedName("wind_speed_10m_max") val windSpeed10mMax: List<Double?>? = null,
    @SerializedName("wind_gusts_10m_max") val windGusts10mMax: List<Double?>? = null,
    @SerializedName("wind_direction_10m_dominant") val windDirection10mDominant: List<Int?>? = null
)

data class OpenMeteoAirQualityResponse(
    @SerializedName("current") val current: OpenMeteoAirQualityCurrent? = null
)

data class OpenMeteoAirQualityCurrent(
    @SerializedName("us_aqi") val usAqi: Int? = null,
    @SerializedName("european_aqi") val europeanAqi: Int? = null,
    @SerializedName("pm10") val pm10: Double? = null,
    @SerializedName("pm2_5") val pm25: Double? = null
)

data class OpenMeteoGeocodingResponse(
    @SerializedName("results") val results: List<OpenMeteoGeoResult>? = null
)

data class OpenMeteoGeoResult(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("country") val country: String? = null,
    @SerializedName("admin1") val admin1: String? = null
)
