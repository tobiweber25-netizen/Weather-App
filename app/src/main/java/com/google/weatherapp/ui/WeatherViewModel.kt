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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class WeatherViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: WeatherRepository = WeatherRepository(
        localDataSource = WeatherLocalDataSource(application)
    ),
    private val locationClient: LocationClient = LocationClient(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _pageStates = MutableStateFlow<Map<String, WeatherUiState>>(emptyMap())
    val pageStates: StateFlow<Map<String, WeatherUiState>> = _pageStates.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _savedLocations = MutableStateFlow<List<SavedLocation>>(repository.getSavedLocations())
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    private val _selectedCustomLocation = MutableStateFlow<SavedLocation?>(repository.getSelectedCustomLocation())
    val selectedCustomLocation: StateFlow<SavedLocation?> = _selectedCustomLocation.asStateFlow()

    private val _searchState = MutableStateFlow<LocationSearchState>(LocationSearchState.Idle)
    val searchState: StateFlow<LocationSearchState> = _searchState.asStateFlow()

    private val pageJobs = mutableMapOf<String, Job>()
    private val pageFetchTimestamps = mutableMapOf<String, Long>()
    private var searchJob: Job? = null

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
        hydrateCachedPages()
        val activeLoc = _selectedCustomLocation.value
        val activeKey = pageKeyFor(activeLoc)
        val initialCached = _pageStates.value[activeKey]
        if (initialCached is WeatherUiState.Success) {
            _uiState.value = initialCached
            fetchPageWeather(location = activeLoc, showInitialLoading = false)
        } else {
            _uiState.value = WeatherUiState.Loading
            fetchPageWeather(location = activeLoc, showInitialLoading = true)
        }
        startPolling()
    }

    private fun hydrateCachedPages() {
        val initialMap = mutableMapOf<String, WeatherUiState>()
        val lastCoords = locationClient.getCachedCoordinates()
        val gpsCached = repository.getCachedWeatherForLocation(
            location = null,
            gpsLatitude = lastCoords?.latitude,
            gpsLongitude = lastCoords?.longitude
        )
        if (gpsCached != null) {
            initialMap[PAGE_KEY_GPS] = WeatherUiState.Success(gpsCached)
        }
        _savedLocations.value.forEach { loc ->
            val cached = repository.getCachedWeatherForLocation(location = loc)
            if (cached != null) {
                initialMap[pageKeyFor(loc)] = WeatherUiState.Success(cached)
            }
        }
        if (initialMap.isNotEmpty()) {
            _pageStates.value = initialMap
        }
    }

    private fun startPolling() {
        viewModelScope.launch {
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
        val activeLoc = _selectedCustomLocation.value
        val key = pageKeyFor(activeLoc)
        pageJobs[key]?.cancel()
        fetchPageWeather(
            location = activeLoc,
            showInitialLoading = false,
            forceFreshLocation = activeLoc == null
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
        searchJob = viewModelScope.launch {
            delay(250L)
            _searchState.value = repository.searchPlaces(trimmed)
        }
    }

    /**
     * Called when the user swipes horizontally to a page index (0 = GPS, 1..N = saved places).
     */
    fun onSwipeToPage(pageIndex: Int) {
        val target = locationForPage(pageIndex)
        val current = _selectedCustomLocation.value
        val alreadyActive = if (target == null) {
            current == null
        } else {
            current != null && LocationSearchEngine.isSamePlace(current, target)
        }
        if (alreadyActive) return

        _selectedCustomLocation.value = target
        repository.setSelectedCustomLocation(target)
        activatePageStateAndFetchIfNeeded(target, forceFreshGps = false)
    }

    fun selectLocation(location: SavedLocation?) {
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
        activatePageStateAndFetchIfNeeded(effectiveLocation, forceFreshGps = effectiveLocation == null)
    }

    fun removeSavedLocation(location: SavedLocation) {
        val removedKey = pageKeyFor(location)
        pageJobs.remove(removedKey)?.cancel()
        pageFetchTimestamps.remove(removedKey)
        _pageStates.update { it - removedKey }

        val updated = _savedLocations.value.filterNot { LocationSearchEngine.isSamePlace(it, location) }
        _savedLocations.value = updated
        repository.saveLocations(updated)

        val activeCustom = _selectedCustomLocation.value
        if (activeCustom != null && LocationSearchEngine.isSamePlace(activeCustom, location)) {
            selectLocation(null)
        }
    }

    private fun activatePageStateAndFetchIfNeeded(
        target: SavedLocation?,
        forceFreshGps: Boolean
    ) {
        val key = pageKeyFor(target)
        val existingState = _pageStates.value[key] ?: run {
            val lastCoords = if (target == null) locationClient.getCachedCoordinates() else null
            val diskCached = repository.getCachedWeatherForLocation(
                location = target,
                gpsLatitude = lastCoords?.latitude,
                gpsLongitude = lastCoords?.longitude
            )
            diskCached?.let { WeatherUiState.Success(it) }?.also { state ->
                _pageStates.update { it + (key to state) }
            }
        }

        if (existingState is WeatherUiState.Success) {
            _uiState.value = existingState
            // Refresh silently if older than 5 minutes so swiping back and forth doesn't hammer APIs
            val lastFetch = pageFetchTimestamps[key] ?: 0L
            val ageMs = System.currentTimeMillis() - lastFetch
            if (forceFreshGps || lastFetch == 0L || ageMs > SWIPE_REFRESH_THROTTLE_MS) {
                fetchPageWeather(
                    location = target,
                    showInitialLoading = false,
                    forceFreshLocation = forceFreshGps
                )
            } else {
                // Keep home widgets synced to the newly selected active city
                com.google.weatherapp.widget.WeatherWidgetUpdater.updateAllWidgets(
                    context = getApplication(),
                    preloadedData = existingState.data
                )
            }
        } else {
            _uiState.value = WeatherUiState.Loading
            _pageStates.update { it + (key to WeatherUiState.Loading) }
            fetchPageWeather(
                location = target,
                showInitialLoading = true,
                forceFreshLocation = forceFreshGps
            )
        }
    }

    private fun fetchPageWeather(
        location: SavedLocation?,
        showInitialLoading: Boolean = false,
        forceFreshLocation: Boolean = false
    ) {
        val key = pageKeyFor(location)
        val isActivePage = { pageKeyFor(_selectedCustomLocation.value) == key }

        pageJobs[key]?.cancel()
        pageJobs[key] = viewModelScope.launch {
            val existing = _pageStates.value[key]
            if (showInitialLoading || existing !is WeatherUiState.Success) {
                _pageStates.update { it + (key to WeatherUiState.Loading) }
                if (isActivePage()) {
                    _uiState.value = WeatherUiState.Loading
                    _isRefreshing.value = false
                }
            } else if (isActivePage()) {
                _isRefreshing.value = true
            }

            try {
                val data = if (location != null && !location.isCurrentGps) {
                    repository.getWeatherData(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        resolvedLocationName = location.name,
                        fallbackTimezone = location.timezone,
                        targetLocation = location,
                        updateActiveCacheSlot = isActivePage()
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
                        updateActiveCacheSlot = isActivePage()
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
                    com.google.weatherapp.widget.WeatherWidgetUpdater.updateAllWidgets(
                        context = getApplication(),
                        preloadedData = data
                    )
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
                if (current !is WeatherUiState.Success) {
                    val lastCoords = if (location == null) locationClient.getCachedCoordinates() else null
                    val fallback = repository.getCachedWeatherForLocation(
                        location = location,
                        gpsLatitude = lastCoords?.latitude,
                        gpsLongitude = lastCoords?.longitude
                    )
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
                if (isActivePage()) {
                    _isRefreshing.value = false
                }
            }
        }
    }

    companion object {
        const val PAGE_KEY_GPS = "gps"
        private const val SWIPE_REFRESH_THROTTLE_MS = 5 * 60 * 1000L

        fun pageKeyFor(location: SavedLocation?): String {
            if (location == null || location.isCurrentGps) return PAGE_KEY_GPS
            return "%.4f,%.4f".format(Locale.US, location.latitude, location.longitude)
        }
    }
}
