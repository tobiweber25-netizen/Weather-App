package com.google.weatherapp.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.weatherapp.data.local.WeatherLocalDataSource
import com.google.weatherapp.data.model.SavedLocation
import com.google.weatherapp.data.repository.WeatherRepository
import com.google.weatherapp.location.LocationClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class WeatherViewModel @JvmOverloads constructor(
    application: Application,
    private val repository: WeatherRepository = WeatherRepository(
        localDataSource = WeatherLocalDataSource(application)
    ),
    private val locationClient: LocationClient = LocationClient(application)
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow<WeatherUiState>(WeatherUiState.Loading)
    val uiState: StateFlow<WeatherUiState> = _uiState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _savedLocations = MutableStateFlow<List<SavedLocation>>(repository.getSavedLocations())
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    private val _selectedCustomLocation = MutableStateFlow<SavedLocation?>(repository.getSelectedCustomLocation())
    val selectedCustomLocation: StateFlow<SavedLocation?> = _selectedCustomLocation.asStateFlow()

    private val _searchResults = MutableStateFlow<List<SavedLocation>>(emptyList())
    val searchResults: StateFlow<List<SavedLocation>> = _searchResults.asStateFlow()

    private var activeJob: Job? = null
    private var searchJob: Job? = null
    private var lastFetchTimestampMs: Long = 0L
    private var latestRequestId: Long = 0L

    fun hasLocationPermission(): Boolean = locationClient.hasLocationPermission()

    init {
        val lastCoords = if (_selectedCustomLocation.value == null) locationClient.getCachedCoordinates() else null
        val cached = repository.getCachedWeatherData(
            latitude = lastCoords?.latitude,
            longitude = lastCoords?.longitude
        )
        if (cached != null) {
            _uiState.value = WeatherUiState.Success(cached)
            fetchAndSetWeatherData(showInitialLoading = false)
        } else {
            _uiState.value = WeatherUiState.Loading
            fetchAndSetWeatherData(showInitialLoading = true)
        }
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (true) {
                delay(15 * 60 * 1000L)
                if (activeJob?.isActive != true) {
                    fetchAndSetWeatherData(showInitialLoading = false)
                }
            }
        }
    }

    fun loadWeatherData() {
        if (activeJob?.isActive == true) return
        fetchAndSetWeatherData(showInitialLoading = true)
    }

    fun refresh() {
        activeJob?.cancel()
        fetchAndSetWeatherData(showInitialLoading = false, forceFreshLocation = true)
    }

    fun onLocationPermissionGranted() {
        activeJob?.cancel()
        fetchAndSetWeatherData(showInitialLoading = _uiState.value !is WeatherUiState.Success, forceFreshLocation = true)
    }

    fun searchCities(query: String) {
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _searchResults.value = emptyList()
            return
        }
        searchJob = viewModelScope.launch {
            delay(250L)
            _searchResults.value = repository.searchCities(query)
        }
    }

    fun selectLocation(location: SavedLocation?) {
        val normalized = location?.takeUnless { it.isCurrentGps }
        _selectedCustomLocation.value = normalized
        repository.setSelectedCustomLocation(normalized)
        if (normalized != null) {
            val currentList = _savedLocations.value.toMutableList()
            val exists = currentList.any { isSameLocation(it, normalized) }
            if (!exists) {
                currentList.add(0, normalized)
                _savedLocations.value = currentList
                repository.saveLocations(currentList)
            }
        }
        _searchResults.value = emptyList()
        activeJob?.cancel()
        val cachedForNewLocation = repository.getCachedWeatherData()
        if (cachedForNewLocation != null) {
            _uiState.value = WeatherUiState.Success(cachedForNewLocation)
            fetchAndSetWeatherData(showInitialLoading = false, forceFreshLocation = normalized == null)
        } else {
            _uiState.value = WeatherUiState.Loading
            fetchAndSetWeatherData(showInitialLoading = true, forceFreshLocation = normalized == null)
        }
    }

    fun removeSavedLocation(location: SavedLocation) {
        val updated = _savedLocations.value.filterNot { isSameLocation(it, location) }
        _savedLocations.value = updated
        repository.saveLocations(updated)
        val activeCustom = _selectedCustomLocation.value
        if (activeCustom != null && isSameLocation(activeCustom, location)) {
            selectLocation(null)
        }
    }

    private fun isSameLocation(a: SavedLocation, b: SavedLocation): Boolean {
        return a.name.equals(b.name, ignoreCase = true) &&
            a.country.equals(b.country, ignoreCase = true) &&
            a.adminArea.equals(b.adminArea, ignoreCase = true)
    }

    private fun fetchAndSetWeatherData(showInitialLoading: Boolean = false, forceFreshLocation: Boolean = false) {
        val requestId = ++latestRequestId
        activeJob = viewModelScope.launch {
            if (showInitialLoading || _uiState.value !is WeatherUiState.Success) {
                _uiState.value = WeatherUiState.Loading
                _isRefreshing.value = false
            } else {
                _isRefreshing.value = true
            }

            try {
                val customLoc = _selectedCustomLocation.value
                val data = if (customLoc != null && !customLoc.isCurrentGps) {
                    repository.getWeatherData(
                        latitude = customLoc.latitude,
                        longitude = customLoc.longitude,
                        resolvedLocationName = customLoc.name
                    )
                } else {
                    val coords = locationClient.fetchLocation(forceFresh = forceFreshLocation)
                        ?: throw Exception("Location unavailable. Please enable location services or pick a city.")
                    repository.getWeatherData(
                        latitude = coords.latitude,
                        longitude = coords.longitude,
                        resolvedLocationName = coords.locationName,
                        altitude = coords.altitude
                    )
                }
                if (requestId != latestRequestId) return@launch
                lastFetchTimestampMs = System.currentTimeMillis()
                _uiState.value = WeatherUiState.Success(data)
                com.google.weatherapp.widget.WeatherWidgetUpdater.updateAllWidgets(
                    context = getApplication(),
                    preloadedData = data
                )
                if (customLoc == null || customLoc.isCurrentGps) {
                    com.google.weatherapp.notification.WeatherNotificationScheduler.checkAndDispatchEventAlerts(
                        context = getApplication(),
                        data = data
                    )
                }
                Log.d("WeatherViewModel", "Weather data successfully loaded and applied to UI")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (requestId != latestRequestId) return@launch
                Log.e("WeatherViewModel", "Error loading weather data", e)
                if (_uiState.value !is WeatherUiState.Success) {
                    val fallback = repository.getCachedWeatherData()
                    if (fallback != null) {
                        _uiState.value = WeatherUiState.Success(fallback)
                    } else {
                        _uiState.value = WeatherUiState.Error(e.localizedMessage ?: "Failed to load weather data")
                    }
                }
            } finally {
                if (requestId == latestRequestId) {
                    _isRefreshing.value = false
                }
            }
        }
    }
}
