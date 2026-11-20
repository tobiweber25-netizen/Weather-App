package com.google.weatherapp.data.repository

import com.google.weatherapp.BuildConfig
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class WeatherRepository(
    private val apiService: GoogleWeatherApiService = ApiClient.weatherService,
    private val openMeteoService: OpenMeteoForecastService = ApiClient.openMeteoForecastService,
    private val airQualityService: OpenMeteoAirQualityService = ApiClient.openMeteoAirQualityService,
    private val geocodingService: OpenMeteoGeocodingService = ApiClient.openMeteoGeocodingService,
    private val sawsAlertClient: SawsAlertClient = SawsAlertClient(),
    private val apiKey: String = BuildConfig.GOOGLE_WEATHER_API_KEY,
    private val localDataSource: WeatherLocalDataSource? = null
) {

    private suspend fun <T> safeCall(label: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w(TAG, "$label failed: ${e.message}")
        null
    }

    fun getCachedWeatherData(latitude: Double? = null, longitude: Double? = null): WeatherDashboardData? =
        localDataSource?.getCachedWeather(latitude = latitude, longitude = longitude)

    fun getSavedLocations(): List<SavedLocation> = localDataSource?.getSavedLocations() ?: emptyList()

    fun saveLocations(locations: List<SavedLocation>) {
        localDataSource?.saveLocations(locations)
    }

    fun getSelectedCustomLocation(): SavedLocation? = localDataSource?.getSelectedCustomLocation()

    fun setSelectedCustomLocation(location: SavedLocation?) {
        localDataSource?.setSelectedCustomLocation(location)
    }

    suspend fun searchCities(query: String): List<SavedLocation> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        try {
            val response = geocodingService.searchLocations(query = trimmed)
            response.results.orEmpty().mapNotNull { item ->
                val name = item.name ?: return@mapNotNull null
                val lat = item.latitude ?: return@mapNotNull null
                val lon = item.longitude ?: return@mapNotNull null
                SavedLocation(
                    name = name,
                    adminArea = item.admin1.orEmpty(),
                    country = item.country.orEmpty(),
                    latitude = lat,
                    longitude = lon,
                    isCurrentGps = false
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "City search failed", e)
            emptyList()
        }
    }

    internal fun resolveBestRegionalModels(latitude: Double, longitude: Double): String {
        // Open-Meteo's "best_match" dynamically resolves the best high-resolution regional
        // model (HRRR in North America, ICON-D2 in Central Europe, AROME in Western Europe, ECMWF globally)
        // while preserving standard non-suffixed JSON keys and full 10-day coverage.
        return "best_match"
    }

    suspend fun getWeatherData(
        latitude: Double,
        longitude: Double,
        resolvedLocationName: String? = null,
        altitude: Double? = null
    ): WeatherDashboardData = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val googleCurrentDeferred = async {
                    safeCall("Google currentConditions") {
                        apiService.getCurrentConditions(apiKey = apiKey, latitude = latitude, longitude = longitude)
                    }
                }
                val googleHourlyDeferred = async {
                    safeCall("Google hourly") {
                        apiService.getHourlyForecast(
                            apiKey = apiKey,
                            latitude = latitude,
                            longitude = longitude,
                            hours = 48,
                            pageSize = 48
                        )
                    }
                }
                val googleDailyDeferred = async {
                    safeCall("Google daily") {
                        apiService.getDailyForecast(
                            apiKey = apiKey,
                            latitude = latitude,
                            longitude = longitude,
                            days = 10,
                            pageSize = 10
                        )
                    }
                }
                val googleAlertsDeferred = async {
                    safeCall("Google publicAlerts") {
                        apiService.getPublicAlerts(
                            apiKey = apiKey,
                            latitude = latitude,
                            longitude = longitude
                        )
                    }
                }
                val sawsAlertsDeferred = async {
                    safeCall("SAWS CAP alerts") {
                        sawsAlertClient.fetchAlertsForLocation(
                            latitude = latitude,
                            longitude = longitude
                        )
                    } ?: emptyList()
                }
                val regionalModels = resolveBestRegionalModels(latitude, longitude)
                val useNativeMinutely = WeatherConsensus.hasNative15MinNowcast(latitude, longitude)
                val openMeteoDeferred = async {
                    // Never pass raw phone GPS altitude (WGS84 / noisy / often 0.0 on network fixes):
                    // let Open-Meteo use its built-in 90m Copernicus terrain DEM to match Google Weather.
                    safeCall("Open-Meteo regional") {
                        openMeteoService.getForecast(
                            latitude = latitude,
                            longitude = longitude,
                            minutely15 = if (useNativeMinutely) "precipitation,precipitation_probability,weather_code" else null,
                            forecastMinutely15 = if (useNativeMinutely) 96 else null,
                            elevation = null,
                            models = regionalModels
                        )
                    } ?: safeCall("Open-Meteo default") {
                        openMeteoService.getForecast(
                            latitude = latitude,
                            longitude = longitude,
                            minutely15 = if (useNativeMinutely) "precipitation,precipitation_probability,weather_code" else null,
                            forecastMinutely15 = if (useNativeMinutely) 96 else null,
                            elevation = null
                        )
                    }
                }
                val consensusDeferred = async {
                    safeCall("Open-Meteo multi-model consensus") {
                        WeatherConsensus.parseMultiModelConsensus(
                            openMeteoService.getMultiModelConsensus(
                                latitude = latitude,
                                longitude = longitude
                            )
                        )
                    }
                }
                val airQualityDeferred = async {
                    safeCall("Open-Meteo air quality") {
                        airQualityService.getAirQuality(latitude = latitude, longitude = longitude)
                    }
                }

                val gCurrent = googleCurrentDeferred.await()
                val gHourly = googleHourlyDeferred.await()
                val gDaily = googleDailyDeferred.await()
                val gAlerts = googleAlertsDeferred.await()
                val sawsAlerts = sawsAlertsDeferred.await()
                val omForecast = openMeteoDeferred.await()
                val omConsensus = consensusDeferred.await()
                val omAqi = airQualityDeferred.await()

                val result = when {
                    gCurrent != null && gDaily != null -> WeatherMapper.mapEnsembleDashboardData(
                        current = gCurrent,
                        hourly = gHourly ?: HourlyForecastResponse(emptyList()),
                        daily = gDaily,
                        omForecast = omForecast,
                        omAqi = omAqi,
                        resolvedLocationName = resolvedLocationName,
                        latitude = latitude,
                        longitude = longitude,
                        omConsensus = omConsensus,
                        googleAlerts = gAlerts,
                        sawsAlerts = sawsAlerts
                    )
                    omForecast != null -> WeatherMapper.mapOpenMeteoFallbackDashboardData(
                        omForecast = omForecast,
                        omAqi = omAqi,
                        resolvedLocationName = resolvedLocationName,
                        latitude = latitude,
                        longitude = longitude,
                        omConsensus = omConsensus,
                        googleAlerts = gAlerts,
                        sawsAlerts = sawsAlerts
                    )
                    else -> throw IllegalStateException("Both Google Weather and Open-Meteo forecast feeds failed")
                }

                localDataSource?.saveWeather(result)
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch weather telemetry", e)
            localDataSource?.getCachedWeather(latitude = latitude, longitude = longitude) ?: throw e
        }
    }

}

private const val TAG = "WeatherRepository"
