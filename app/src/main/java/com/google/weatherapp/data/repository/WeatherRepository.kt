package com.google.weatherapp.data.repository

import com.google.weatherapp.BuildConfig
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.*
import com.google.weatherapp.ui.LocationSearchState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class WeatherRepository(
    private val apiService: GoogleWeatherApiService = ApiClient.weatherService,
    private val openMeteoService: OpenMeteoForecastService = ApiClient.openMeteoForecastService,
    private val airQualityService: OpenMeteoAirQualityService = ApiClient.openMeteoAirQualityService,
    private val geocodingService: OpenMeteoGeocodingService = ApiClient.openMeteoGeocodingService,
    private val photonGeocodingService: PhotonGeocodingService = ApiClient.photonGeocodingService,
    private val sawsAlertClient: SawsAlertClient = SawsAlertClient(),
    private val globalOfficialAlertClient: GlobalOfficialAlertClient = GlobalOfficialAlertClient(),
    private val eumetsatLiveStormClient: EumetsatLiveStormClient = EumetsatLiveStormClient(),
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

    fun getCachedWeatherForLocation(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null
    ): WeatherDashboardData? = localDataSource?.getCachedWeatherForLocation(
        location = location,
        gpsLatitude = gpsLatitude,
        gpsLongitude = gpsLongitude
    )

    fun getAllCachedWeatherEntries(): Map<String, WeatherDashboardData> =
        localDataSource?.getAllCachedWeatherEntries() ?: emptyMap()

    fun locationCacheKeyFor(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null
    ): String? = localDataSource?.locationKeyFor(
        location = location,
        gpsLatitude = gpsLatitude,
        gpsLongitude = gpsLongitude
    )

    fun syncActiveWidgetCache(
        data: WeatherDashboardData,
        targetLocation: SavedLocation?,
        shouldUpdate: () -> Boolean = { true }
    ) {
        localDataSource?.updateActiveCacheSlot(
            data = data,
            targetLocation = targetLocation,
            shouldUpdate = shouldUpdate
        )
    }

    fun getSavedLocations(): List<SavedLocation> = localDataSource?.getSavedLocations() ?: emptyList()

    fun saveLocations(locations: List<SavedLocation>) {
        localDataSource?.saveLocations(locations)
    }

    fun getSelectedCustomLocation(): SavedLocation? = localDataSource?.getSelectedCustomLocation()

    fun setSelectedCustomLocation(location: SavedLocation?) {
        localDataSource?.setSelectedCustomLocation(location)
    }

    suspend fun searchCities(query: String): List<SavedLocation> {
        return when (val state = searchPlaces(query)) {
            is LocationSearchState.Results -> state.locations
            else -> emptyList()
        }
    }

    /**
     * Global hybrid place search combining Open-Meteo Geocoding + OpenStreetMap Photon
     * plus direct latitude/longitude coordinate lookup. Never fabricates results and
     * distinguishes empty matches from network errors.
     */
    suspend fun searchPlaces(query: String): LocationSearchState = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext LocationSearchState.Idle

        // 1. Direct coordinate search (e.g. "-33.9249, 18.4241" or "35.68° N, 139.65° E")
        LocationSearchEngine.parseCoordinateQuery(trimmed)?.let { (lat, lon) ->
            val reverseProps = safeCall("Photon coordinate reverse") {
                photonGeocodingService.reverseGeocode(latitude = lat, longitude = lon)
                    .features?.firstOrNull()?.properties
            }
            val resolvedName = LocationSearchEngine.formatReverseGeocodeName(reverseProps)
                ?: LocationSearchEngine.formatCoordinates(lat, lon)
            val admin = listOfNotNull(
                reverseProps?.state?.trim()?.takeIf { it.isNotBlank() && !it.equals(resolvedName, ignoreCase = true) }
            ).firstOrNull().orEmpty()
            val country = reverseProps?.country?.trim().orEmpty()
            return@withContext LocationSearchState.Results(
                listOf(
                    SavedLocation(
                        name = resolvedName,
                        adminArea = admin,
                        country = country,
                        latitude = lat,
                        longitude = lon,
                        isCurrentGps = false,
                        countryCode = reverseProps?.countryCode?.trim()?.uppercase()
                    )
                )
            )
        }

        // 2. Parallel Open-Meteo + OpenStreetMap Photon global search
        coroutineScope {
            var omSucceeded = false
            var photonSucceeded = false

            val omDeferred = async {
                try {
                    val res = geocodingService.searchLocations(query = trimmed, count = 10)
                    omSucceeded = true
                    res.results.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Open-Meteo geocoding failed: ${e.message}")
                    emptyList()
                }
            }
            val photonDeferred = async {
                try {
                    val res = photonGeocodingService.searchLocations(query = trimmed, limit = 10)
                    photonSucceeded = true
                    res.features.orEmpty()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Photon geocoding failed: ${e.message}")
                    emptyList()
                }
            }

            val omResults = omDeferred.await()
            val photonFeatures = photonDeferred.await()

            if (!omSucceeded && !photonSucceeded) {
                return@coroutineScope LocationSearchState.Error("Check your internet connection and try again.")
            }

            val merged = LocationSearchEngine.mergeAndRankSearchResults(
                query = trimmed,
                omResults = omResults,
                photonFeatures = photonFeatures
            )
            if (merged.isEmpty()) {
                LocationSearchState.Empty(trimmed)
            } else {
                LocationSearchState.Results(merged)
            }
        }
    }

    internal fun resolveBestRegionalModels(latitude: Double, longitude: Double): String {
        // Open-Meteo's "best_match" dynamically resolves the best high-resolution regional
        // model (HRRR in North America, ICON-D2 in Central Europe, AROME in Western Europe,
        // UKV in UK, JMA MSM in Japan, MET Norway in Nordics, HRDPS in Canada, ECMWF globally)
        // while preserving standard non-suffixed JSON keys and full 10-day coverage.
        return "best_match"
    }

    suspend fun getWeatherData(
        latitude: Double,
        longitude: Double,
        resolvedLocationName: String? = null,
        altitude: Double? = null,
        fallbackTimezone: String? = null,
        targetLocation: SavedLocation? = localDataSource?.getSelectedCustomLocation(),
        updateActiveCacheSlot: Boolean = true,
        shouldUpdateActiveCacheSlot: () -> Boolean = { updateActiveCacheSlot },
        includeSecondaryFeeds: Boolean = true
    ): WeatherDashboardData = withContext(Dispatchers.IO) {
        try {
            coroutineScope {
                val hasGoogleKey = apiKey.isNotBlank()
                val googleCurrentDeferred = async {
                    if (!hasGoogleKey) return@async null
                    safeCall("Google currentConditions") {
                        apiService.getCurrentConditions(apiKey = apiKey, latitude = latitude, longitude = longitude)
                    }
                }
                val googleHourlyDeferred = async {
                    if (!hasGoogleKey) return@async null
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
                    if (!hasGoogleKey) return@async null
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
                    if (!hasGoogleKey) return@async null
                    safeCall("Google publicAlerts") {
                        apiService.getPublicAlerts(
                            apiKey = apiKey,
                            latitude = latitude,
                            longitude = longitude
                        )
                    }
                }
                val sawsAlertsDeferred = async {
                    if (!includeSecondaryFeeds) return@async emptyList()
                    withTimeoutOrNull(3500L) {
                        safeCall("SAWS CAP alerts") {
                            sawsAlertClient.fetchAlertsForLocation(
                                latitude = latitude,
                                longitude = longitude
                            )
                        }
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
                val preliminaryZone = WeatherMapper.parseZoneIdOrNull(fallbackTimezone)
                val globalOfficialAlertsDeferred = async {
                    if (!includeSecondaryFeeds) return@async emptyList()
                    withTimeoutOrNull(3500L) {
                        safeCall("Global official alerts") {
                            globalOfficialAlertClient.fetchAlertsForLocation(
                                latitude = latitude,
                                longitude = longitude,
                                zoneId = preliminaryZone
                            )
                        }
                    } ?: emptyList()
                }
                val liveSatelliteDeferred = async {
                    if (!includeSecondaryFeeds) return@async null
                    withTimeoutOrNull(2200L) {
                        safeCall("EUMETSAT live satellite storm check") {
                            eumetsatLiveStormClient.fetchLiveObservation(
                                latitude = latitude,
                                longitude = longitude
                            )
                        }
                    }
                }

                val gCurrent = googleCurrentDeferred.await()
                val gHourly = googleHourlyDeferred.await()
                val gDaily = googleDailyDeferred.await()
                val gAlerts = googleAlertsDeferred.await()
                val sawsAlerts = sawsAlertsDeferred.await()
                val globalOfficialAlerts = globalOfficialAlertsDeferred.await()
                val omForecast = openMeteoDeferred.await()
                val omConsensus = consensusDeferred.await()
                val omAqi = airQualityDeferred.await()
                val liveSatellite = liveSatelliteDeferred.await()

                val hasUsableGoogle = gCurrent?.temperature?.degrees != null &&
                    !gDaily?.forecastDays.isNullOrEmpty()
                val hasUsableOpenMeteo = omForecast != null &&
                    (omForecast.current?.temperature2m != null || omForecast.hourly?.temperature2m?.any { it != null } == true)

                val result = when {
                    hasUsableGoogle -> WeatherMapper.mapEnsembleDashboardData(
                        current = gCurrent!!,
                        hourly = gHourly ?: HourlyForecastResponse(emptyList()),
                        daily = gDaily!!,
                        omForecast = omForecast,
                        omAqi = omAqi,
                        resolvedLocationName = resolvedLocationName,
                        latitude = latitude,
                        longitude = longitude,
                        omConsensus = omConsensus,
                        googleAlerts = gAlerts,
                        sawsAlerts = sawsAlerts,
                        globalOfficialAlerts = globalOfficialAlerts,
                        fallbackTimezone = fallbackTimezone,
                        liveSatellite = liveSatellite
                    )
                    hasUsableOpenMeteo -> WeatherMapper.mapOpenMeteoFallbackDashboardData(
                        omForecast = omForecast!!,
                        omAqi = omAqi,
                        resolvedLocationName = resolvedLocationName,
                        latitude = latitude,
                        longitude = longitude,
                        omConsensus = omConsensus,
                        googleAlerts = gAlerts,
                        sawsAlerts = sawsAlerts,
                        globalOfficialAlerts = globalOfficialAlerts,
                        fallbackTimezone = fallbackTimezone,
                        liveSatellite = liveSatellite
                    )
                    else -> throw IllegalStateException("Weather sources unavailable for this location")
                }

                localDataSource?.saveWeather(
                    data = result,
                    targetLocation = targetLocation,
                    shouldUpdateActiveSlot = shouldUpdateActiveCacheSlot
                )
                result
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch weather telemetry", e)
            localDataSource?.getCachedWeatherForLocation(
                location = targetLocation,
                gpsLatitude = latitude,
                gpsLongitude = longitude
            ) ?: throw e
        }
    }

}

private const val TAG = "WeatherRepository"
