package com.google.weatherapp.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.repository.LocationSearchEngine
import com.google.weatherapp.data.repository.WeatherRepository
import com.google.weatherapp.location.LocationClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

data class PageNavigationEvent(
    val targetKey: String,
    val seq: Long = 0L,
    val snapImmediately: Boolean = false
)

class WeatherViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: WeatherRepository = WeatherRepository(
        localDataSource = WeatherLocalDataSource(application)
    ),
    private val locationClient: LocationClient = LocationClient(application),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val enablePolling: Boolean = true,
    private val scopeOverride: CoroutineScope? = null
) : AndroidViewModel(application) {

    private val vmScope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _pageStates = MutableStateFlow<Map<String, WeatherUiState>>(emptyMap())
    val pageStates: StateFlow<Map<String, WeatherUiState>> = _pageStates.asStateFlow()

    private val _refreshingPages = MutableStateFlow<Set<String>>(emptySet())
    val refreshingPages: StateFlow<Set<String>> = _refreshingPages.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _savedLocations = MutableStateFlow<List<SavedLocation>>(repository.getSavedLocations())
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    private val _selectedCustomLocation = MutableStateFlow<SavedLocation?>(repository.getSelectedCustomLocation())
    val selectedCustomLocation: StateFlow<SavedLocation?> = _selectedCustomLocation.asStateFlow()

    private val _searchState = MutableStateFlow<LocationSearchState>(LocationSearchState.Idle)
    val searchState: StateFlow<LocationSearchState> = _searchState.asStateFlow()

    private var navSequence = 0L
    private val _navigationEvent = MutableStateFlow(
        PageNavigationEvent(
            targetKey = pageKeyFor(_selectedCustomLocation.value),
            seq = 0L,
            snapImmediately = false
        )
    )
    val navigationEvent: StateFlow<PageNavigationEvent> = _navigationEvent.asStateFlow()

    private val pageJobs = mutableMapOf<String, Job>()
    private val pageFetchTimestamps = mutableMapOf<String, Long>()
    private var searchJob: Job? = null
    private var prefetchJob: Job? = null
    private var widgetUpdateJob: Job? = null
    @Volatile
    private var hasHydratedCache = false

    fun hasLocationPermission(): Boolean = locationClient.hasLocationPermission()

    /**
     * Ordered swipe pages: index 0 is always Current Location (null), followed by each saved place.
     */
    fun activePageIndex(
        selected: SavedLocation? = _selectedCustomLocation.value,
        saved: List<SavedLocation> = _savedLocations.value
    ): Int {
        if (selected == null || selected.isCurrentGps) return 0
        val idx = saved.indexOfFirst { LocationSearchEngine.isSamePlace(it, selected) }
        return if (idx >= 0) idx + 1 else 0
    }

    fun locationForPage(
        pageIndex: Int,
        saved: List<SavedLocation> = _savedLocations.value
    ): SavedLocation? {
        if (pageIndex <= 0) return null
        return saved.getOrNull(pageIndex - 1)
    }

    init {
        // Hydrate multi-city disk cache off the main thread in a single JSON parse
        vmScope.launch {
            val hydrated = withContext(ioDispatcher) {
                loadHydratedCachedPages()
            }
            if (hydrated.isNotEmpty()) {
                _pageStates.update { existing ->
                    val merged = existing.toMutableMap()
                    hydrated.forEach { (k, v) ->
                        if (merged[k] !is WeatherUiState.Success) {
                            merged[k] = v
                        }
                    }
                    merged
                }
            }
            hasHydratedCache = true
            val activeLoc = _selectedCustomLocation.value
            val activeKey = pageKeyFor(activeLoc)
            val initialCached = _pageStates.value[activeKey]
            if (initialCached is WeatherUiState.Success) {
                _uiState.value = initialCached
                if (pageJobs[activeKey]?.isActive != true) {
                    fetchPageWeather(location = activeLoc, showInitialLoading = false)
                }
            } else {
                if (pageJobs[activeKey]?.isActive != true) {
                    _uiState.value = WeatherUiState.Loading
                    fetchPageWeather(location = activeLoc, showInitialLoading = true)
                }
            }
            prefetchAdjacentPages(activePageIndex())
        }
        if (enablePolling) {
            startPolling()
        }
    }

    private fun loadHydratedCachedPages(): Map<String, WeatherUiState> {
        val allCached = repository.getAllCachedWeatherEntries()
        if (allCached.isEmpty()) return emptyMap()
        val initialMap = mutableMapOf<String, WeatherUiState>()
        val lastCoords = locationClient.getCachedCoordinates()
        val gpsCacheKey = repository.locationCacheKeyFor(
            location = null,
            gpsLatitude = lastCoords?.latitude,
            gpsLongitude = lastCoords?.longitude
        )
        if (gpsCacheKey != null) {
            allCached[gpsCacheKey]?.let { initialMap[PAGE_KEY_GPS] = WeatherUiState.Success(it) }
        }
        _savedLocations.value.forEach { loc ->
            val cacheKey = repository.locationCacheKeyFor(location = loc)
            if (cacheKey != null) {
                allCached[cacheKey]?.let { initialMap[pageKeyFor(loc)] = WeatherUiState.Success(it) }
            }
        }
        return initialMap
    }

    private fun startPolling() {
        vmScope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                val activeLoc = _selectedCustomLocation.value
                val key = pageKeyFor(activeLoc)
                if (pageJobs[key]?.isActive != true) {
                    fetchPageWeather(location = activeLoc, showInitialLoading = false)
                }
            }
        }
    }

    private fun emitNavigationEvent(target: SavedLocation?, snapImmediately: Boolean) {
        navSequence += 1L
        _navigationEvent.value = PageNavigationEvent(
            targetKey = pageKeyFor(target),
            seq = navSequence,
            snapImmediately = snapImmediately
        )
    }

    private fun syncActiveRefreshingState() {
        val activeKey = pageKeyFor(_selectedCustomLocation.value)
        _isRefreshing.value = activeKey in _refreshingPages.value
    }

    fun loadWeatherData() {
        val activeLoc = _selectedCustomLocation.value
        val key = pageKeyFor(activeLoc)
        if (pageJobs[key]?.isActive == true) return
        fetchPageWeather(location = activeLoc, showInitialLoading = true)
    }

    fun retryPage(location: SavedLocation?) {
        val key = pageKeyFor(location)
        pageJobs[key]?.cancel()
        fetchPageWeather(location = location, showInitialLoading = true, forceFreshLocation = location == null)
    }

    fun refresh() {
        refreshPage(_selectedCustomLocation.value)
    }

    fun refreshPage(location: SavedLocation?) {
        val target = location?.takeUnless { it.isCurrentGps }
        val key = pageKeyFor(target)
        pageJobs[key]?.cancel()
        fetchPageWeather(
            location = target,
            showInitialLoading = false,
            forceFreshLocation = target == null
        )
    }

    fun onLocationPermissionGranted() {
        pageJobs[PAGE_KEY_GPS]?.cancel()
        val isOnGps = _selectedCustomLocation.value == null
        val currentState = _pageStates.value[PAGE_KEY_GPS]
        fetchPageWeather(
            location = null,
            showInitialLoading = currentState !is WeatherUiState.Success,
            forceFreshLocation = true
        )
        if (!isOnGps) {
            // Also keep currently viewed custom location fresh
            val activeLoc = _selectedCustomLocation.value
            if (activeLoc != null && _pageStates.value[pageKeyFor(activeLoc)] !is WeatherUiState.Success) {
                fetchPageWeather(location = activeLoc, showInitialLoading = true)
            }
        }
    }

    fun searchCities(query: String) {
        searchJob?.cancel()
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchState.value = LocationSearchState.Idle
            return
        }
        _searchState.value = LocationSearchState.Searching
        searchJob = vmScope.launch {
            delay(250L)
            _searchState.value = repository.searchPlaces(trimmed)
        }
    }

    /**
     * Called ONLY when the user finishes a manual horizontal swipe to [pageIndex]
     * (0 = GPS, 1..N = saved places). Never emits a programmatic PageNavigationEvent,
     * preventing fast consecutive swipes from snapping backwards mid-drag.
     */
    fun onSwipeToPage(pageIndex: Int) {
        val target = locationForPage(pageIndex)
        val current = _selectedCustomLocation.value
        val alreadyActive = if (target == null) {
            current == null
        } else {
            current != null && LocationSearchEngine.isSamePlace(current, target)
        }
        if (alreadyActive) {
            syncActiveRefreshingState()
            prefetchAdjacentPages(pageIndex)
            return
        }

        _selectedCustomLocation.value = target
        repository.setSelectedCustomLocation(target)
        activatePageStateAndFetchIfNeeded(target, forceFreshGps = false)
        prefetchAdjacentPages(pageIndex)
    }

    /**
     * Called when the user explicitly taps a page indicator dot.
     */
    fun selectPageIndex(pageIndex: Int) {
        val target = locationForPage(pageIndex)
        val current = _selectedCustomLocation.value
        val alreadyActive = if (target == null) {
            current == null
        } else {
            current != null && LocationSearchEngine.isSamePlace(current, target)
        }
        if (!alreadyActive) {
            _selectedCustomLocation.value = target
            repository.setSelectedCustomLocation(target)
            activatePageStateAndFetchIfNeeded(target, forceFreshGps = false)
        } else {
            syncActiveRefreshingState()
        }
        emitNavigationEvent(target, snapImmediately = false)
        prefetchAdjacentPages(pageIndex)
    }

    fun selectLocation(
        location: SavedLocation?,
        snapImmediately: Boolean = false
    ) {
        val normalized = location?.takeUnless { it.isCurrentGps }
        var effectiveLocation = normalized
        if (normalized != null) {
            val currentList = _savedLocations.value.toMutableList()
            val existing = currentList.firstOrNull { LocationSearchEngine.isSamePlace(it, normalized) }
            if (existing != null) {
                effectiveLocation = existing
            } else {
                currentList.add(0, normalized)
                _savedLocations.value = currentList
                repository.saveLocations(currentList)
            }
        }
        _selectedCustomLocation.value = effectiveLocation
        repository.setSelectedCustomLocation(effectiveLocation)
        _searchState.value = LocationSearchState.Idle
        emitNavigationEvent(effectiveLocation, snapImmediately = snapImmediately)
        activatePageStateAndFetchIfNeeded(effectiveLocation, forceFreshGps = effectiveLocation == null)
        prefetchAdjacentPages(activePageIndex(effectiveLocation, _savedLocations.value))
    }

    fun removeSavedLocation(location: SavedLocation) {
        val currentSaved = _savedLocations.value
        val removedMatches = currentSaved.filter { LocationSearchEngine.isSamePlace(it, location) }
        val removedKeys = buildSet {
            add(pageKeyFor(location))
            removedMatches.forEach { add(pageKeyFor(it)) }
        }
        removedKeys.forEach { key ->
            pageJobs.remove(key)?.cancel()
            pageFetchTimestamps.remove(key)
        }
        _refreshingPages.update { it - removedKeys }
        _pageStates.update { it - removedKeys }

        val updated = currentSaved.filterNot { LocationSearchEngine.isSamePlace(it, location) }
        _savedLocations.value = updated
        repository.saveLocations(updated)

        val activeCustom = _selectedCustomLocation.value
        if (activeCustom != null && LocationSearchEngine.isSamePlace(activeCustom, location)) {
            selectLocation(null, snapImmediately = true)
        } else {
            syncActiveRefreshingState()
            prefetchAdjacentPages(activePageIndex())
        }
    }

    /**
     * Silently warms immediate neighbor pages (centerIndex ± 1) so horizontal swiping
     * lands on real weather data instead of skeleton placeholders, without triggering
     * pull-to-refresh spinners, blocking the active city load, or overwriting the active widget cache slot.
     */
    fun prefetchAdjacentPages(centerIndex: Int) {
        if (!hasHydratedCache) return
        prefetchJob?.cancel()
        val runPrefetch = {
            val saved = _savedLocations.value
            val maxPageIndex = saved.size
            listOf(centerIndex - 1, centerIndex + 1).forEach { neighborIdx ->
                if (neighborIdx !in 0..maxPageIndex) return@forEach
                val neighborLoc = locationForPage(neighborIdx, saved)
                val key = pageKeyFor(neighborLoc)
                if (_pageStates.value[key] is WeatherUiState.Success) return@forEach
                if (pageJobs[key]?.isActive == true) return@forEach
                if (neighborLoc == null && !hasLocationPermission() && locationClient.getCachedCoordinates() == null) {
                    return@forEach
                }
                fetchPageWeather(
                    location = neighborLoc,
                    showInitialLoading = false,
                    forceFreshLocation = false,
                    isPrefetch = true
                )
            }
        }
        if (!enablePolling) {
            runPrefetch()
        } else {
            prefetchJob = vmScope.launch {
                delay(PREFETCH_DEBOUNCE_MS)
                runPrefetch()
            }
        }
    }

    private fun scheduleWidgetUpdate(data: com.google.weatherapp.data.model.WeatherDashboardData) {
        widgetUpdateJob?.cancel()
        widgetUpdateJob = vmScope.launch(ioDispatcher) {
            try {
                com.google.weatherapp.widget.WeatherWidgetUpdater.updateAllWidgets(
                    context = getApplication(),
                    preloadedData = data
                )
            } catch (_: Exception) {
                // Ignore widget IPC exceptions in headless/test environments
            }
        }
    }

    private fun activatePageStateAndFetchIfNeeded(
        target: SavedLocation?,
        forceFreshGps: Boolean
    ) {
        val key = pageKeyFor(target)
        syncActiveRefreshingState()
        val existingState = _pageStates.value[key]

        if (existingState is WeatherUiState.Success) {
            _uiState.value = existingState
            // Always keep KEY_CACHED_WEATHER and home widgets synced to the newly active city,
            // even when within the 5-minute swipe refresh throttle window.
            vmScope.launch(ioDispatcher) {
                repository.syncActiveWidgetCache(
                    data = existingState.data,
                    targetLocation = target,
                    shouldUpdate = { pageKeyFor(_selectedCustomLocation.value) == key }
                )
            }
            scheduleWidgetUpdate(existingState.data)

            // Refresh silently if older than 5 minutes so swiping back and forth doesn't hammer APIs
            val lastFetch = pageFetchTimestamps[key] ?: 0L
            val ageMs = System.currentTimeMillis() - lastFetch
            if ((forceFreshGps || lastFetch == 0L || ageMs > SWIPE_REFRESH_THROTTLE_MS) &&
                (forceFreshGps || pageJobs[key]?.isActive != true)
            ) {
                fetchPageWeather(
                    location = target,
                    showInitialLoading = false,
                    forceFreshLocation = forceFreshGps
                )
            }
        } else {
            _uiState.value = WeatherUiState.Loading
            _pageStates.update { it + (key to WeatherUiState.Loading) }
            if (forceFreshGps || pageJobs[key]?.isActive != true) {
                fetchPageWeather(
                    location = target,
                    showInitialLoading = true,
                    forceFreshLocation = forceFreshGps,
                    checkDiskCacheFirst = true
                )
            }
        }
    }

    private fun fetchPageWeather(
        location: SavedLocation?,
        showInitialLoading: Boolean = false,
        forceFreshLocation: Boolean = false,
        isPrefetch: Boolean = false,
        checkDiskCacheFirst: Boolean = false
    ) {
        val key = pageKeyFor(location)
        val isActivePage = { pageKeyFor(_selectedCustomLocation.value) == key }

        pageJobs[key]?.cancel()
        lateinit var myJob: Job
        myJob = vmScope.launch(start = CoroutineStart.LAZY) {
            try {
                var loadedFromDisk = false
                if (isPrefetch || checkDiskCacheFirst) {
                    val diskCached = withContext(ioDispatcher) {
                        val lastCoords = if (location == null) locationClient.getCachedCoordinates() else null
                        repository.getCachedWeatherForLocation(
                            location = location,
                            gpsLatitude = lastCoords?.latitude,
                            gpsLongitude = lastCoords?.longitude
                        )
                    }
                    if (diskCached != null) {
                        loadedFromDisk = true
                        val cachedState = WeatherUiState.Success(diskCached)
                        _pageStates.update { it + (key to cachedState) }
                        if (isActivePage()) {
                            _uiState.value = cachedState
                            withContext(ioDispatcher) {
                                repository.syncActiveWidgetCache(
                                    data = diskCached,
                                    targetLocation = location,
                                    shouldUpdate = isActivePage
                                )
                            }
                            scheduleWidgetUpdate(diskCached)
                        }
                        if (isPrefetch) return@launch
                    }
                }

                val existing = _pageStates.value[key]
                if (!isPrefetch) {
                    if ((showInitialLoading && !loadedFromDisk) || existing !is WeatherUiState.Success) {
                        _pageStates.update { it + (key to WeatherUiState.Loading) }
                        _refreshingPages.update { it - key }
                        if (isActivePage()) {
                            _uiState.value = WeatherUiState.Loading
                        }
                        syncActiveRefreshingState()
                    } else {
                        _refreshingPages.update { it + key }
                        syncActiveRefreshingState()
                    }
                }

                val data = if (location != null && !location.isCurrentGps) {
                    repository.getWeatherData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        resolvedLocationName = location.name,
                        fallbackTimezone = location.timezone,
                        targetLocation = location,
                        shouldUpdateActiveCacheSlot = isActivePage,
                        includeSecondaryFeeds = !isPrefetch
                    )
                } else {
                    val coords = locationClient.fetchLocation(forceFresh = forceFreshLocation)
                        ?: throw Exception("Location unavailable. Please enable location services or pick a place.")
                    repository.getWeatherData(
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        resolvedLocationName = coords.locationName,
                        altitude = coords.altitude,
                        targetLocation = null,
                        shouldUpdateActiveCacheSlot = isActivePage,
                        includeSecondaryFeeds = !isPrefetch
                    )
                }

                // Backfill resolved IANA timezone onto the saved location if it came from Photon without one
                if (location != null && location.timezone.isNullOrBlank() && !data.timezone.isNullOrBlank()) {
                    val updatedList = _savedLocations.value.map { saved ->
                        if (LocationSearchEngine.isSamePlace(saved, location)) {
                            saved.copy(timezone = data.timezone)
                        } else {
                            saved
                        }
                    }
                    _savedLocations.value = updatedList
                    repository.saveLocations(updatedList)
                    if (_selectedCustomLocation.value?.let { LocationSearchEngine.isSamePlace(it, location) } == true) {
                        val updatedSelected = _selectedCustomLocation.value?.copy(timezone = data.timezone)
                        _selectedCustomLocation.value = updatedSelected
                        repository.setSelectedCustomLocation(updatedSelected)
                    }
                }

                val successState = WeatherUiState.Success(data)
                pageFetchTimestamps[key] = System.currentTimeMillis()
                _pageStates.update { it + (key to successState) }

                if (isActivePage()) {
                    _uiState.value = successState
                    scheduleWidgetUpdate(data)
                }
                if (location == null || location.isCurrentGps) {
                    com.google.weatherapp.notification.WeatherNotificationScheduler.checkAndDispatchEventAlerts(
                        context = getApplication(),
                        data = data
                    )
                }
                Log.d("WeatherViewModel", "Weather data loaded for $key (${data.hero.locationName})")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("WeatherViewModel", "Error loading weather data for $key", e)
                val current = _pageStates.value[key]
                if (current !is WeatherUiState.Success && (!isPrefetch || isActivePage())) {
                    val fallback = withContext(ioDispatcher) {
                        val lastCoords = if (location == null) locationClient.getCachedCoordinates() else null
                        repository.getCachedWeatherForLocation(
                            location = location,
                            gpsLatitude = lastCoords?.latitude,
                            gpsLongitude = lastCoords?.longitude
                        )
                    }
                    val resolvedState = if (fallback != null) {
                        WeatherUiState.Success(fallback)
                    } else {
                        WeatherUiState.Error(e.localizedMessage ?: "Failed to load weather data")
                    }
                    _pageStates.update { it + (key to resolvedState) }
                    if (isActivePage()) {
                        _uiState.value = resolvedState
                    }
                }
            } finally {
                if (pageJobs[key] === myJob) {
                    _refreshingPages.update { it - key }
                    syncActiveRefreshingState()
                }
            }
        }
        pageJobs[key] = myJob
        myJob.start()
    }

    companion object {
        const val PAGE_KEY_GPS = "gps"
        private const val SWIPE_REFRESH_THROTTLE_MS = 5 * 60 * 1000L
        private const val PREFETCH_DEBOUNCE_MS = 850L

        fun pageKeyFor(location: SavedLocation?): String {
            if (location == null || location.isCurrentGps) return PAGE_KEY_GPS
            return "%.4f,%.4f".format(Locale.US, location.latitude, location.longitude)
        }
    }
}
