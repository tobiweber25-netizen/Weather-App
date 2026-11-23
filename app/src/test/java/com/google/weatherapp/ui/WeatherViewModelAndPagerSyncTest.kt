package com.google.weatherapp.ui

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.JsonObject
import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.*
import com.google.weatherapp.data.repository.WeatherRepository
import com.google.weatherapp.location.Coordinates
import com.google.weatherapp.location.LocationClient
import com.google.weatherapp.widget.WeatherWidgetUpdater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

class WeatherViewModelAndPagerSyncTest {

    private var activeScope: CoroutineScope? = null

    @After
    fun tearDown() {
        activeScope?.cancel()
        activeScope = null
    }

    private val london = SavedLocation("London", "England", "United Kingdom", 51.5074, -0.1278, timezone = "Europe/London", countryCode = "GB")
    private val tokyo = SavedLocation("Tokyo", "Tokyo", "Japan", 35.6762, 139.6503, timezone = "Asia/Tokyo", countryCode = "JP")
    private val paris = SavedLocation("Paris", "Île-de-France", "France", 48.8566, 2.3522, timezone = "Europe/Paris", countryCode = "FR")

    private fun makeDashboardData(name: String, tz: String, lat: Double, lon: Double, temp: Int): WeatherDashboardData {
        val today = LocalDate.now(ZoneId.of(tz))
        val todayStr = "${today.monthValue}/${today.dayOfMonth}"
        return WeatherDashboardData(
            hero = HeroWeather(temp, temp, temp + 4, temp - 4, "Clear", null, true, name, SkyThemeType.CLEAR_DAY, lastUpdatedEpochMs = System.currentTimeMillis()),
            alert = SevereAlertInfo(SeverityRiskLevel.LOW, 0, "", "", false),
            hourlyList = emptyList(),
            dailyList = listOf(DailyItem("Today", todayStr, temp - 4, temp + 4, 0, null, "Clear", "", "")),
            telemetry = BentoTelemetry(10, "W", 15, 0.0, 3, "Low", 50, 10, 1015, "06:00", "18:00"),
            timezone = tz,
            latitude = lat,
            longitude = lon
        )
    }

    private fun makeOmForecast(tz: String, temp: Double): OpenMeteoForecastResponse {
        val today = LocalDate.now(ZoneId.of(tz))
        return OpenMeteoForecastResponse(
            timezone = tz,
            current = OpenMeteoCurrent(temperature2m = temp, isDay = 1, weatherCode = 0),
            daily = OpenMeteoDaily(
                time = listOf(today.toString()),
                temperature2mMax = listOf(temp + 4.0),
                temperature2mMin = listOf(temp - 4.0)
            )
        )
    }

    @Test
    fun testPagerNavigationEventsForAddDeleteAndManualSwipesPreventRevertHijackAndSnapBack() {
        val app = FakeApplication()
        val localSource = WeatherLocalDataSource(app)
        localSource.saveLocations(listOf(london, tokyo))
        localSource.setSelectedCustomLocation(london)
        localSource.saveWeather(makeDashboardData("London", "Europe/London", london.latitude, london.longitude, 14), london, updateActiveSlot = true)
        localSource.saveWeather(makeDashboardData("Tokyo", "Asia/Tokyo", tokyo.latitude, tokyo.longitude, 21), tokyo, updateActiveSlot = false)

        val stubOm = ControlledOpenMeteoService { lat, _ ->
            when {
                lat > 50.0 -> makeOmForecast("Europe/London", 15.0)
                lat > 45.0 -> makeOmForecast("Europe/Paris", 17.0)
                else -> makeOmForecast("Asia/Tokyo", 22.0)
            }
        }
        val repo = WeatherRepository(
            openMeteoService = stubOm,
            airQualityService = StubAqiService(),
            apiKey = "",
            localDataSource = localSource
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { activeScope = it }
        val vm = WeatherViewModel(
            application = app,
            repository = repo,
            locationClient = LocationClient(app),
            ioDispatcher = Dispatchers.Unconfined,
            enablePolling = false,
            scopeOverride = scope
        )

        // 1. Adding a new city (Paris) while on page 1 (London) emits a programmatic navigation event targeting Paris
        val seqBeforeAdd = vm.navigationEvent.value.seq
        vm.selectLocation(paris)
        assertEquals("Paris", vm.selectedCustomLocation.value?.name)
        assertEquals(listOf("Paris", "London", "Tokyo"), vm.savedLocations.value.map { it.name })
        assertEquals(1, vm.activePageIndex())
        assertEquals(seqBeforeAdd + 1, vm.navigationEvent.value.seq)
        assertEquals(WeatherViewModel.pageKeyFor(paris), vm.navigationEvent.value.targetKey)
        assertFalse(vm.navigationEvent.value.snapImmediately)

        // 2. Manual consecutive swipes (Paris -> London -> Tokyo) NEVER emit PageNavigationEvent (preventing snap-back mid-drag)
        val seqBeforeSwipes = vm.navigationEvent.value.seq
        vm.onSwipeToPage(2) // London
        assertEquals("London", vm.selectedCustomLocation.value?.name)
        assertEquals(seqBeforeSwipes, vm.navigationEvent.value.seq)

        vm.onSwipeToPage(3) // Tokyo
        assertEquals("Tokyo", vm.selectedCustomLocation.value?.name)
        assertEquals(seqBeforeSwipes, vm.navigationEvent.value.seq)

        // 3. Deleting the LAST active saved city (Tokyo, when >= 2 saved cities exist) snaps directly to GPS
        vm.removeSavedLocation(tokyo)
        assertNull("Deleting active last city must select GPS, not hijack to London", vm.selectedCustomLocation.value)
        assertEquals(0, vm.activePageIndex())
        assertEquals(seqBeforeSwipes + 1, vm.navigationEvent.value.seq)
        assertEquals(WeatherViewModel.PAGE_KEY_GPS, vm.navigationEvent.value.targetKey)
        assertTrue("Active deletion must snap immediately to prevent flashing shifted city", vm.navigationEvent.value.snapImmediately)

        // 4. Deleting a NON-active city (Paris) while viewing London leaves London active without firing navigation event
        vm.selectPageIndex(2) // Tap dot -> London
        assertEquals("London", vm.selectedCustomLocation.value?.name)
        val seqAfterDot = vm.navigationEvent.value.seq
        vm.removeSavedLocation(paris)
        assertEquals("London", vm.selectedCustomLocation.value?.name)
        assertEquals(1, vm.activePageIndex())
        assertEquals("Deleting non-active city must not emit navigation event", seqAfterDot, vm.navigationEvent.value.seq)
    }

    @Test
    fun testPullToRefreshSpinnerDoesNotStickOnSwipeAndWidgetCacheNeverClobberedByBackgroundFetch() = runBlocking {
        val app = FakeApplication()
        val localSource = WeatherLocalDataSource(app)
        localSource.saveLocations(listOf(london, tokyo))
        localSource.setSelectedCustomLocation(london)
        localSource.saveWeather(makeDashboardData("London", "Europe/London", london.latitude, london.longitude, 14), london, updateActiveSlot = true)
        localSource.saveWeather(makeDashboardData("Tokyo", "Asia/Tokyo", tokyo.latitude, tokyo.longitude, 21), tokyo, updateActiveSlot = false)

        val londonRefreshGate = CompletableDeferred<OpenMeteoForecastResponse>()
        var blockLondon = false
        val stubOm = ControlledOpenMeteoService { lat, _ ->
            if (lat > 50.0 && blockLondon) {
                londonRefreshGate.await()
            } else if (lat > 50.0) {
                makeOmForecast("Europe/London", 15.0)
            } else {
                makeOmForecast("Asia/Tokyo", 22.0)
            }
        }
        val repo = WeatherRepository(
            openMeteoService = stubOm,
            airQualityService = StubAqiService(),
            apiKey = "",
            localDataSource = localSource
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { activeScope = it }
        val vm = WeatherViewModel(
            application = app,
            repository = repo,
            locationClient = LocationClient(app),
            ioDispatcher = Dispatchers.Unconfined,
            enablePolling = false,
            scopeOverride = scope
        )

        val londonKey = WeatherViewModel.pageKeyFor(london)
        val tokyoKey = WeatherViewModel.pageKeyFor(tokyo)
        // Wait for initial London refresh to finish
        withTimeout(3_000L) { vm.refreshingPages.first { londonKey !in it } }

        // Warm Tokyo fetch timestamp so swiping to Tokyo stays inside SWIPE_REFRESH_THROTTLE_MS
        vm.onSwipeToPage(2)
        withTimeout(3_000L) { vm.refreshingPages.first { tokyoKey !in it } }
        assertEquals("Tokyo", localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName)
        vm.onSwipeToPage(1)
        assertEquals("London", localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName)

        // Now pause London's pull-to-refresh mid-flight
        blockLondon = true
        vm.refresh()
        assertTrue("London must be refreshing", vm.isRefreshing.value)
        assertTrue(londonKey in vm.refreshingPages.value)

        // Swipe to cached Tokyo (< 5 min ago): spinner MUST clear immediately AND active widget slot MUST sync to Tokyo!
        vm.onSwipeToPage(2)
        assertFalse("Pull-to-refresh spinner must NOT stay stuck on cached Tokyo", vm.isRefreshing.value)
        assertFalse(tokyoKey in vm.refreshingPages.value)
        assertEquals(
            "Active widget slot KEY_CACHED_WEATHER must immediately sync to Tokyo on cached swipe",
            "Tokyo",
            localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName
        )

        // Swiping back to London while still in flight shows spinner again; swiping back to Tokyo clears it again
        vm.onSwipeToPage(1)
        assertTrue("Returning to still-refreshing London should reflect active refresh", vm.isRefreshing.value)
        vm.onSwipeToPage(2)
        assertFalse("Returning to cached Tokyo should clear refresh indicator", vm.isRefreshing.value)

        // Complete London's background fetch while user is on Tokyo:
        // shouldUpdateActiveCacheSlot() evaluates AFTER await -> must NOT overwrite Tokyo in KEY_CACHED_WEATHER!
        londonRefreshGate.complete(makeOmForecast("Europe/London", 16.0))
        withTimeout(3_000L) { vm.refreshingPages.first { londonKey !in it } }
        assertFalse(londonKey in vm.refreshingPages.value)
        assertFalse(vm.isRefreshing.value)
        assertEquals(
            "Completing background London fetch must NOT clobber Tokyo in active widget slot",
            "Tokyo",
            localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName
        )
        // London's updated 16°C forecast IS still saved in London's own page state and multi-location cache!
        assertEquals(16, (vm.pageStates.value[londonKey] as WeatherUiState.Success).data.hero.tempDegrees)
    }

    @Test
    fun testPrefetchAdjacentPagesWarmsUncachedNeighborWithoutSpinnerOrClobberingWidgetSlot() = runBlocking {
        val app = FakeApplication()
        val localSource = WeatherLocalDataSource(app)
        localSource.saveLocations(listOf(london, tokyo))
        localSource.setSelectedCustomLocation(london)
        localSource.saveWeather(makeDashboardData("London", "Europe/London", london.latitude, london.longitude, 14), london, updateActiveSlot = true)
        // Tokyo is intentionally NOT cached on disk

        val stubOm = ControlledOpenMeteoService { lat, _ ->
            if (lat > 50.0) makeOmForecast("Europe/London", 15.0) else makeOmForecast("Asia/Tokyo", 23.0)
        }
        val repo = WeatherRepository(
            openMeteoService = stubOm,
            airQualityService = StubAqiService(),
            apiKey = "",
            localDataSource = localSource
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { activeScope = it }
        val vm = WeatherViewModel(
            application = app,
            repository = repo,
            locationClient = LocationClient(app),
            ioDispatcher = Dispatchers.Unconfined,
            enablePolling = false,
            scopeOverride = scope
        )

        val tokyoKey = WeatherViewModel.pageKeyFor(tokyo)
        val tokyoState = withTimeout(3_000L) {
            vm.pageStates.first { it[tokyoKey] is WeatherUiState.Success }[tokyoKey]
        }
        assertTrue("Adjacent uncached Tokyo page should be prefetched to Success", tokyoState is WeatherUiState.Success)
        assertEquals(23, (tokyoState as WeatherUiState.Success).data.hero.tempDegrees)
        withTimeout(3_000L) { vm.refreshingPages.first { it.isEmpty() } }
        assertTrue("Prefetch must never leave any refreshing indicator active", vm.refreshingPages.value.isEmpty())
        assertEquals(
            "Prefetching Tokyo must NOT overwrite London in active widget cache slot",
            "London",
            localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName
        )
    }

    @Test
    fun testWidgetLookupHitsGpsLocationCacheMapAndDeviceWeatherFallback() {
        val app = FakeApplication()
        val localSource = WeatherLocalDataSource(app)
        localSource.setSelectedCustomLocation(null) // Active selection is GPS

        val jhbCoords = Coordinates(-26.2041, 28.0473, "Johannesburg")
        val jhbData = makeDashboardData("Johannesburg", "Africa/Johannesburg", -26.2041, 28.0473, 25)
        // Save GPS weather into KEY_LOCATION_CACHE_MAP (and device weather), while active legacy slot holds London
        localSource.saveWeather(jhbData, targetLocation = null, updateActiveSlot = false)
        localSource.saveWeather(makeDashboardData("London", "Europe/London", london.latitude, london.longitude, 14), targetLocation = london, updateActiveSlot = true)

        // 1. With cached GPS coordinates, widget lookup hits KEY_LOCATION_CACHE_MAP for Johannesburg (NOT London!)
        val hitViaCoords = WeatherWidgetUpdater.lookupFreshCachedWeatherForWidget(localSource, jhbCoords)
        assertEquals("Johannesburg", hitViaCoords?.hero?.locationName)

        // 2. Even without cached coordinates, widget lookup falls back to fresh deviceWeather (Johannesburg), NOT London!
        val hitViaDevice = WeatherWidgetUpdater.lookupFreshCachedWeatherForWidget(localSource, cachedGpsCoords = null)
        assertEquals("Johannesburg", hitViaDevice?.hero?.locationName)

        // 3. Active-location offline widget lookup must NEVER return London when GPS is selected
        assertEquals("Johannesburg", localSource.getLatestCachedWeatherForActiveLocation()?.hero?.locationName)

        // 4. Atomic lock-guarded updateActiveCacheSlot skips writing when shouldUpdate returns false
        localSource.updateActiveCacheSlot(
            data = jhbData,
            targetLocation = null,
            shouldUpdate = { false }
        )
        assertEquals("London", localSource.getLatestCachedWeatherIgnoringTtl()?.hero?.locationName)
    }

    @Test
    fun testRemoveSavedLocationWithSlightlyOffsetCoordsCleansUpStoredPageState() {
        val app = FakeApplication()
        val localSource = WeatherLocalDataSource(app)
        localSource.saveLocations(listOf(london, tokyo))
        localSource.setSelectedCustomLocation(tokyo)
        localSource.saveWeather(makeDashboardData("London", "Europe/London", london.latitude, london.longitude, 14), london, updateActiveSlot = false)
        localSource.saveWeather(makeDashboardData("Tokyo", "Asia/Tokyo", tokyo.latitude, tokyo.longitude, 21), tokyo, updateActiveSlot = true)

        val stubOm = ControlledOpenMeteoService { lat, _ ->
            if (lat > 50.0) makeOmForecast("Europe/London", 15.0) else makeOmForecast("Asia/Tokyo", 22.0)
        }
        val repo = WeatherRepository(
            openMeteoService = stubOm,
            airQualityService = StubAqiService(),
            apiKey = "",
            localDataSource = localSource
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { activeScope = it }
        val vm = WeatherViewModel(
            application = app,
            repository = repo,
            locationClient = LocationClient(app),
            ioDispatcher = Dispatchers.Unconfined,
            enablePolling = false,
            scopeOverride = scope
        )

        val storedLondonKey = WeatherViewModel.pageKeyFor(london)
        assertTrue(vm.pageStates.value.containsKey(storedLondonKey))

        // Delete using a coordinate 300m away (same place per isSamePlace, different rounded pageKey)
        val offsetLondon = london.copy(latitude = london.latitude + 0.002, longitude = london.longitude + 0.002)
        vm.removeSavedLocation(offsetLondon)
        assertFalse("Stored London entry must be removed from savedLocations", vm.savedLocations.value.any { it.name == "London" })
        assertFalse("Stored London pageState must also be evicted", vm.pageStates.value.containsKey(storedLondonKey))
    }

    private class ControlledOpenMeteoService(
        private val responder: suspend (Double, Double) -> OpenMeteoForecastResponse
    ) : OpenMeteoForecastService {
        override suspend fun getForecast(
            latitude: Double,
            longitude: Double,
            current: String,
            hourly: String,
            daily: String,
            minutely15: String?,
            forecastMinutely15: Int?,
            elevation: Double?,
            models: String?,
            timezone: String,
            forecastDays: Int
        ): OpenMeteoForecastResponse = responder(latitude, longitude)

        override suspend fun getMultiModelConsensus(
            latitude: Double,
            longitude: Double,
            hourly: String,
            daily: String,
            models: String,
            timezone: String,
            forecastDays: Int
        ): JsonObject = throw UnsupportedOperationException("No multi-model in stub")
    }

    private class StubAqiService : OpenMeteoAirQualityService {
        override suspend fun getAirQuality(
            latitude: Double,
            longitude: Double,
            current: String,
            timezone: String
        ): OpenMeteoAirQualityResponse = OpenMeteoAirQualityResponse(null)
    }

    private class FakeSharedPreferences : SharedPreferences {
        private val map = ConcurrentHashMap<String, Any>()
        override fun getAll(): MutableMap<String, *> = HashMap(map)
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class FakeEditor(private val target: ConcurrentHashMap<String, Any>) : SharedPreferences.Editor {
            private val updates = LinkedHashMap<String, Any?>()
            private var clearAll = false
            override fun putString(key: String?, value: String?): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { if (key != null) updates[key] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { if (key != null) updates[key] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { if (key != null) updates[key] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                synchronized(target) {
                    if (clearAll) target.clear()
                    updates.forEach { (k, v) -> if (v == null) target.remove(k) else target[k] = v }
                }
            }
        }
    }

    private class FakeApplication : Application() {
        private val prefsByName = ConcurrentHashMap<String, SharedPreferences>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
            prefsByName.computeIfAbsent(name ?: "default") { FakeSharedPreferences() }
    }
}
