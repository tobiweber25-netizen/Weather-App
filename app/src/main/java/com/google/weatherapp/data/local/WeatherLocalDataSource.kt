package com.google.weatherapp.data.local

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.data.repository.LocationSearchEngine
import com.google.weatherapp.data.repository.WeatherMapper
import java.time.ZonedDateTime
import java.util.Locale

class WeatherLocalDataSource(
    context: Context,
    private val gson: Gson = Gson()
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private data class CachedWeatherEntry(
        val timestampMs: Long,
        val data: WeatherDashboardData
    )

    fun getCachedWeather(
        maxAgeMs: Long = 30 * 60 * 1000L,
        latitude: Double? = null,
        longitude: Double? = null
    ): WeatherDashboardData? {
        val expectedKey = currentLocationKey(latitude, longitude)
        if (expectedKey != null) {
            getCachedWeatherForKey(expectedKey, maxAgeMs)?.let { return it }
        }

        // Legacy single-slot fallback (strictly verified for location match, TTL, and local date)
        val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
        val now = System.currentTimeMillis()
        if (timestamp == 0L || (now - timestamp) !in 0..maxAgeMs) {
            return null
        }
        val cachedLoc = prefs.getString(KEY_CACHE_LOCATION, null)
        if (expectedKey != null) {
            if (cachedLoc != expectedKey) return null
        } else {
            if (cachedLoc == null || (!cachedLoc.startsWith("gps_") && cachedLoc != "gps")) return null
        }
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            val data = gson.fromJson(json, WeatherDashboardData::class.java)
            validateFreshForLocalDate(data)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads a cached forecast for a specific city or GPS slot (used for instant horizontal swiping).
     * Never serves weather belonging to a different location or a different calendar day.
     */
    fun getCachedWeatherForLocation(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null,
        maxAgeMs: Long = 30 * 60 * 1000L
    ): WeatherDashboardData? {
        val key = locationKeyFor(location, gpsLatitude, gpsLongitude) ?: return null
        return getCachedWeatherForKey(key, maxAgeMs)
    }

    private fun getCachedWeatherForKey(key: String, maxAgeMs: Long): WeatherDashboardData? {
        val now = System.currentTimeMillis()
        val entry = synchronized(CACHE_MAP_LOCK) {
            loadLocationCacheMap()[key]
        } ?: return null
        if (entry.timestampMs == 0L || (now - entry.timestampMs) !in 0..maxAgeMs) {
            return null
        }
        return validateFreshForLocalDate(entry.data)
    }

    /**
     * Reads and validates all fresh location cache entries in a single JSON parse
     * (used by ViewModel startup hydration off the main thread).
     */
    fun getAllCachedWeatherEntries(maxAgeMs: Long = 30 * 60 * 1000L): Map<String, WeatherDashboardData> {
        val now = System.currentTimeMillis()
        val rawMap = synchronized(CACHE_MAP_LOCK) {
            loadLocationCacheMap()
        }
        if (rawMap.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, WeatherDashboardData>(rawMap.size)
        rawMap.forEach { (key, entry) ->
            if (entry.timestampMs != 0L && (now - entry.timestampMs) in 0..maxAgeMs) {
                val fresh = validateFreshForLocalDate(entry.data)
                if (fresh != null) {
                    result[key] = fresh
                }
            }
        }
        return result
    }

    internal fun validateFreshForLocalDate(
        data: WeatherDashboardData?,
        referenceNow: ZonedDateTime? = null
    ): WeatherDashboardData? {
        if (data == null || data.dailyList.isEmpty()) return null
        val firstDaily = data.dailyList.firstOrNull()
        if (firstDaily != null && firstDaily.dateText.isNotBlank()) {
            val zoneId = WeatherMapper.resolveZoneId(data.timezone)
            val nowZoned = referenceNow?.withZoneSameInstant(zoneId) ?: ZonedDateTime.now(zoneId)
            val nowLocalDate = nowZoned.toLocalDate()
            val todayStr = "${nowLocalDate.monthValue}/${nowLocalDate.dayOfMonth}"
            if (firstDaily.dateText == todayStr) {
                return data
            }
            // Between 00:00 and 06:59 local time, Google's 07:00–07:00 meteorological day may
            // start on yesterday with today immediately following; treat that as fresh until 07:00.
            val yesterday = nowLocalDate.minusDays(1)
            val yesterdayStr = "${yesterday.monthValue}/${yesterday.dayOfMonth}"
            val isValidEarlyMorningMetDay = nowZoned.hour < 7 &&
                firstDaily.dateText == yesterdayStr &&
                data.dailyList.any { it.dateText == todayStr }
            if (!isValidEarlyMorningMetDay) {
                return null
            }
        }
        return data
    }

    fun getLatestCachedWeatherIgnoringTtl(): WeatherDashboardData? {
        val json = prefs.getString(KEY_CACHED_WEATHER, null) ?: return null
        return try {
            gson.fromJson(json, WeatherDashboardData::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the latest cached forecast that actually belongs to the currently active location
     * (either the selected custom city or the device's GPS location), ignoring TTL for offline
     * widget rendering. Never substitutes a different city when the active slot lags behind.
     */
    fun getLatestCachedWeatherForActiveLocation(): WeatherDashboardData? {
        val activeCustom = getSelectedCustomLocation()
        val cachedLoc = prefs.getString(KEY_CACHE_LOCATION, null)
        return if (activeCustom != null && !activeCustom.isCurrentGps) {
            val expectedKey = customLocationKey(activeCustom.latitude, activeCustom.longitude)
            if (cachedLoc == expectedKey) {
                getLatestCachedWeatherIgnoringTtl()
            } else {
                synchronized(CACHE_MAP_LOCK) {
                    loadLocationCacheMap()[expectedKey]?.data
                } ?: if (cachedLoc == null) getLatestCachedWeatherIgnoringTtl() else null
            }
        } else {
            if (cachedLoc == "gps" || cachedLoc?.startsWith("gps_") == true) {
                getLatestCachedWeatherIgnoringTtl() ?: getDeviceWeather(maxAgeMs = Long.MAX_VALUE)
            } else {
                getDeviceWeather(maxAgeMs = Long.MAX_VALUE)
                    ?: if (cachedLoc == null) getLatestCachedWeatherIgnoringTtl() else null
            }
        }
    }

    /**
     * Syncs the active widget/legacy cache slot (KEY_CACHED_WEATHER) when switching to an
     * already-cached page without re-fetching, so widget redraws/resizes never revert to a prior city.
     * Evaluates [shouldUpdate] inside [CACHE_MAP_LOCK] to prevent rapid-swipe race clobbering.
     */
    fun updateActiveCacheSlot(
        data: WeatherDashboardData,
        targetLocation: SavedLocation? = getSelectedCustomLocation(),
        shouldUpdate: () -> Boolean = { true }
    ) {
        try {
            val isGps = targetLocation == null || targetLocation.isCurrentGps
            val cacheKey = if (!isGps) {
                customLocationKey(targetLocation!!.latitude, targetLocation.longitude)
            } else {
                val lat = data.latitude
                val lon = data.longitude
                if (lat != null && lon != null) gpsLocationKey(lat, lon) else "gps"
            }

            synchronized(CACHE_MAP_LOCK) {
                if (!shouldUpdate()) return
                val existingTimestamp = loadLocationCacheMap()[cacheKey]?.timestampMs
                    ?.takeIf { it > 0L }
                    ?: data.hero.lastUpdatedEpochMs.takeIf { it > 0L }
                    ?: System.currentTimeMillis()
                prefs.edit()
                    .putString(KEY_CACHED_WEATHER, gson.toJson(data))
                    .putLong(KEY_CACHE_TIMESTAMP, existingTimestamp)
                    .putString(KEY_CACHE_LOCATION, cacheKey)
                    .apply()
            }
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    fun saveWeather(
        data: WeatherDashboardData,
        targetLocation: SavedLocation? = getSelectedCustomLocation(),
        updateActiveSlot: Boolean = true,
        shouldUpdateActiveSlot: () -> Boolean = { updateActiveSlot }
    ) {
        try {
            val isGps = targetLocation == null || targetLocation.isCurrentGps
            val cacheKey = if (!isGps) {
                customLocationKey(targetLocation!!.latitude, targetLocation.longitude)
            } else {
                val lat = data.latitude
                val lon = data.longitude
                if (lat != null && lon != null) gpsLocationKey(lat, lon) else "gps"
            }

            val now = System.currentTimeMillis()
            synchronized(CACHE_MAP_LOCK) {
                val updatedMap = loadLocationCacheMap().toMutableMap()
                updatedMap[cacheKey] = CachedWeatherEntry(timestampMs = now, data = data)
                // Retain the 8 most recently viewed places worldwide for fast swiping
                while (updatedMap.size > MAX_CACHED_LOCATIONS) {
                    val oldestKey = updatedMap.entries
                        .filter { it.key != cacheKey }
                        .minByOrNull { it.value.timestampMs }?.key ?: break
                    updatedMap.remove(oldestKey)
                }

                val editor = prefs.edit()
                    .putString(KEY_LOCATION_CACHE_MAP, gson.toJson(updatedMap))

                if (shouldUpdateActiveSlot()) {
                    editor
                        .putString(KEY_CACHED_WEATHER, gson.toJson(data))
                        .putLong(KEY_CACHE_TIMESTAMP, now)
                        .putString(KEY_CACHE_LOCATION, cacheKey)
                }
                editor.apply()
            }

            // Dashboard is showing the device's own location: notifications can reuse this forecast.
            if (isGps) saveDeviceWeather(data)
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    /**
     * Forecast for where the device physically is. Kept separate from the dashboard cache so that
     * browsing or swiping to another city never changes what notifications and alerts are about.
     */
    fun saveDeviceWeather(data: WeatherDashboardData) {
        try {
            prefs.edit()
                .putString(KEY_DEVICE_WEATHER, gson.toJson(data))
                .putLong(KEY_DEVICE_WEATHER_TIMESTAMP, System.currentTimeMillis())
                .apply()
        } catch (_: Exception) {
            // Ignore cache write failure
        }
    }

    fun getDeviceWeather(
        maxAgeMs: Long = Long.MAX_VALUE,
        now: Long = System.currentTimeMillis()
    ): WeatherDashboardData? {
        val age = now - prefs.getLong(KEY_DEVICE_WEATHER_TIMESTAMP, 0L)
        if (age < 0L || age > maxAgeMs) return null
        val json = prefs.getString(KEY_DEVICE_WEATHER, null) ?: return null
        return try {
            gson.fromJson(json, WeatherDashboardData::class.java)?.takeIf { it.dailyList.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    fun getSavedLocations(): List<SavedLocation> {
        val json = prefs.getString(KEY_SAVED_LOCATIONS, null) ?: return defaultPopularCities()
        return try {
            val type = object : TypeToken<List<SavedLocation>>() {}.type
            val parsed = gson.fromJson<List<SavedLocation>>(json, type) ?: return defaultPopularCities()
            sanitizeSavedLocations(parsed)
        } catch (_: Exception) {
            defaultPopularCities()
        }
    }

    fun saveLocations(locations: List<SavedLocation>) {
        try {
            val sanitized = sanitizeSavedLocations(locations)
            val json = gson.toJson(sanitized)
            prefs.edit().putString(KEY_SAVED_LOCATIONS, json).apply()
        } catch (_: Exception) {
            // Ignore write failure
        }
    }

    private fun sanitizeSingleLocation(loc: SavedLocation?): SavedLocation? {
        if (loc == null || loc.isCurrentGps) return null
        @Suppress("SENSELESS_COMPARISON")
        val rawName = if (loc.name == null) "" else loc.name.trim()
        if (rawName.isBlank()) return null
        if (!loc.latitude.isFinite() || !loc.longitude.isFinite()) return null
        if (loc.latitude !in -90.0..90.0 || loc.longitude !in -180.0..180.0) return null
        @Suppress("SENSELESS_COMPARISON")
        val safeAdmin = if (loc.adminArea == null) "" else loc.adminArea
        @Suppress("SENSELESS_COMPARISON")
        val safeCountry = if (loc.country == null) "" else loc.country
        return if (rawName != loc.name || safeAdmin != loc.adminArea || safeCountry != loc.country) {
            loc.copy(name = rawName, adminArea = safeAdmin, country = safeCountry)
        } else {
            loc
        }
    }

    internal fun sanitizeSavedLocations(raw: List<SavedLocation?>): List<SavedLocation> {
        val sanitized = mutableListOf<SavedLocation>()
        val seenPagerKeys = HashSet<String>(raw.size)
        for (rawLoc in raw) {
            val loc = sanitizeSingleLocation(rawLoc) ?: continue
            val pagerKey = "%.4f,%.4f".format(Locale.US, loc.latitude, loc.longitude)
            if (pagerKey in seenPagerKeys) continue
            if (sanitized.any { LocationSearchEngine.isSamePlace(it, loc) }) continue
            seenPagerKeys.add(pagerKey)
            sanitized.add(loc)
        }
        return sanitized
    }

    fun getSelectedCustomLocation(): SavedLocation? {
        val json = prefs.getString(KEY_ACTIVE_CUSTOM_LOCATION, null) ?: return null
        return try {
            sanitizeSingleLocation(gson.fromJson(json, SavedLocation::class.java))
        } catch (_: Exception) {
            null
        }
    }

    fun setSelectedCustomLocation(location: SavedLocation?) {
        try {
            if (location == null || location.isCurrentGps) {
                prefs.edit().remove(KEY_ACTIVE_CUSTOM_LOCATION).apply()
            } else {
                prefs.edit().putString(KEY_ACTIVE_CUSTOM_LOCATION, gson.toJson(location)).apply()
            }
        } catch (_: Exception) {
            // Ignore write failure
        }
    }

    fun clearCache() {
        synchronized(CACHE_MAP_LOCK) {
            prefs.edit()
                .remove(KEY_CACHED_WEATHER)
                .remove(KEY_CACHE_TIMESTAMP)
                .remove(KEY_CACHE_LOCATION)
                .remove(KEY_LOCATION_CACHE_MAP)
                .apply()
        }
    }

    fun currentLocationKey(latitude: Double? = null, longitude: Double? = null): String? {
        val loc = getSelectedCustomLocation()
        return locationKeyFor(loc, latitude, longitude)
    }

    fun locationKeyFor(
        location: SavedLocation?,
        gpsLatitude: Double? = null,
        gpsLongitude: Double? = null
    ): String? {
        if (location != null && !location.isCurrentGps) {
            return customLocationKey(location.latitude, location.longitude)
        }
        if (gpsLatitude != null && gpsLongitude != null) {
            return gpsLocationKey(gpsLatitude, gpsLongitude)
        }
        return null
    }

    private fun customLocationKey(latitude: Double, longitude: Double): String =
        "%.3f,%.3f".format(Locale.US, latitude, longitude)

    private fun gpsLocationKey(latitude: Double, longitude: Double): String =
        "gps_%.2f,%.2f".format(Locale.US, latitude, longitude)

    private fun loadLocationCacheMap(): Map<String, CachedWeatherEntry> {
        val json = prefs.getString(KEY_LOCATION_CACHE_MAP, null) ?: return emptyMap()
        return try {
            val type = object : TypeToken<Map<String, CachedWeatherEntry>>() {}.type
            gson.fromJson<Map<String, CachedWeatherEntry>>(json, type) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun defaultPopularCities(): List<SavedLocation> = listOf(
        SavedLocation("Cape Town", "Western Cape", "South Africa", -33.9249, 18.4241, timezone = "Africa/Johannesburg", countryCode = "ZA"),
        SavedLocation("Johannesburg", "Gauteng", "South Africa", -26.2041, 28.0473, timezone = "Africa/Johannesburg", countryCode = "ZA"),
        SavedLocation("London", "England", "United Kingdom", 51.5074, -0.1278, timezone = "Europe/London", countryCode = "GB"),
        SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503, timezone = "Asia/Tokyo", countryCode = "JP")
    )

    companion object {
        private val CACHE_MAP_LOCK = Any()
        private const val PREFS_NAME = "weather_dashboard_cache"
        private const val KEY_CACHED_WEATHER = "cached_weather_json"
        private const val KEY_CACHE_TIMESTAMP = "cached_weather_timestamp"
        private const val KEY_CACHE_LOCATION = "cached_weather_location_key"
        private const val KEY_LOCATION_CACHE_MAP = "cached_weather_location_map_json"
        private const val KEY_DEVICE_WEATHER = "device_weather_json"
        private const val KEY_DEVICE_WEATHER_TIMESTAMP = "device_weather_timestamp"
        private const val KEY_SAVED_LOCATIONS = "saved_locations_json"
        private const val KEY_ACTIVE_CUSTOM_LOCATION = "active_custom_location_json"
        private const val MAX_CACHED_LOCATIONS = 8
    }
}
