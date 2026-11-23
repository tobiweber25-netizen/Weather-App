package com.google.weatherapp.ui.map

import android.graphics.Bitmap
import com.google.weatherapp.data.map.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

data class WeatherMapUiState(
    val selectedLayer: WeatherMapLayer = WeatherMapLayer.WIND,
    val windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
    val satelliteMode: SatelliteMode = SatelliteMode.GEOCOLOUR,
    val showWindStreamlines: Boolean = true,
    val showLightning: Boolean = true,
    val showStormCells: Boolean = true,
    val hourOffset: Int = 0, // -2..48 on Storms/Satellite, 0..48 on Wind/Temp/Pressure
    val isPlaying: Boolean = false,
    val focusedPlaceName: String = "Current Location",
    val cameraCenterLat: Double = 0.0,
    val cameraCenterLon: Double = 0.0,
    val grid: WeatherGridField? = null,
    val gridStatus: WeatherMapGridStatus = WeatherMapGridStatus.Loading,
    val smoothFieldBitmap: Bitmap? = null,
    val smoothFieldBounds: MapViewportBounds? = null,
    val isobars: List<IsobarContourLine> = emptyList(),
    val pressureCenters: List<PressureCenterMarker> = emptyList(),
    val tilePlan: ResolvedMapTilePlan = WeatherTileSourceResolver.resolve(
        layer = WeatherMapLayer.WIND,
        centerLat = 0.0,
        centerLon = 0.0,
        hourOffset = 0
    ),
    val tappedLat: Double? = null,
    val tappedLon: Double? = null,
    val tappedReading: MapPointReading? = null,
    val timeLabel: String = "Live · Now",
    val activeZoneId: ZoneId = ZoneId.systemDefault()
) {
    val minHourOffset: Int get() = if (selectedLayer.supportsPastLoop) -2 else 0
    val maxHourOffset: Int get() = 47
    val gridHourIndex: Int get() = hourOffset.coerceIn(0, (grid?.hourCount ?: 1) - 1)
    val inEumetsatCoverage: Boolean
        get() = WeatherTileSourceResolver.isInEumetsatDisk(cameraCenterLat, cameraCenterLon)
    val inUsCoverage: Boolean
        get() = WeatherTileSourceResolver.isInUsLightningCoverage(cameraCenterLat, cameraCenterLon)
}

/**
 * Lifecycle-scoped controller for WeatherMapScreen.
 * Handles debounced viewport grid fetching, background smooth color-field & isobar rendering,
 * live satellite/forecast tile routing, timeline playback, and tap-anywhere inspection.
 */
class WeatherMapStateHolder(
    private val scope: CoroutineScope,
    initialLayer: WeatherMapLayer,
    initialLatitude: Double,
    initialLongitude: Double,
    initialPlaceName: String,
    initialZoneId: ZoneId = ZoneId.systemDefault(),
    private val gridClient: WeatherMapGridClient = WeatherMapGridClient.sharedInstance
) {
    private val _uiState = MutableStateFlow(
        WeatherMapUiState(
            selectedLayer = initialLayer,
            showWindStreamlines = initialLayer == WeatherMapLayer.WIND,
            focusedPlaceName = initialPlaceName,
            cameraCenterLat = initialLatitude,
            cameraCenterLon = initialLongitude,
            tilePlan = WeatherTileSourceResolver.resolve(
                layer = initialLayer,
                centerLat = initialLatitude,
                centerLon = initialLongitude,
                hourOffset = 0
            ),
            activeZoneId = initialZoneId
        )
    )
    val uiState: StateFlow<WeatherMapUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var renderJob: Job? = null
    private var playbackJob: Job? = null
    private var lastViewport: MapViewportBounds? = null

    init {
        // Kick off weather grid loading at millisecond 0 in parallel with MapLibre init
        val initialBounds = WeatherMapGridClient.estimateInitialViewport(initialLatitude, initialLongitude)
        lastViewport = initialBounds
        val cached = gridClient.findCachedGrid(initialBounds)
        if (cached != null) {
            applyLoadedGrid(cached)
        } else {
            fetchJob = scope.launch {
                performGridFetch(initialBounds, forceRefresh = false)
            }
        }
    }

    fun onViewportIdle(bounds: MapViewportBounds) {
        lastViewport = bounds
        _uiState.update { state ->
            val updatedPlan = resolvePlanFor(
                state.copy(
                    cameraCenterLat = bounds.centerLat,
                    cameraCenterLon = bounds.centerLon
                )
            )
            state.copy(
                cameraCenterLat = bounds.centerLat,
                cameraCenterLon = bounds.centerLon,
                tilePlan = updatedPlan
            )
        }

        // Fast cache hit: avoid scheduling any network fetch when viewport is already covered
        val cached = gridClient.findCachedGrid(bounds)
        if (cached != null) {
            if (_uiState.value.grid !== cached) {
                applyLoadedGrid(cached)
            } else if (_uiState.value.gridStatus !is WeatherMapGridStatus.Ready) {
                _uiState.update { it.copy(gridStatus = WeatherMapGridStatus.Ready) }
            }
            return
        }

        // Avoid restarting an identical in-flight initial fetch when MapLibre settles on the starting box
        if (fetchJob?.isActive == true && _uiState.value.grid == null) {
            return
        }

        fetchJob?.cancel()
        fetchJob = scope.launch {
            if (_uiState.value.grid != null) {
                delay(CAMERA_IDLE_DEBOUNCE_MS)
            }
            performGridFetch(bounds, forceRefresh = false)
        }
    }

    fun retryFetch() {
        val bounds = lastViewport ?: return
        fetchJob?.cancel()
        fetchJob = scope.launch {
            performGridFetch(bounds, forceRefresh = true)
        }
    }

    fun selectLayer(layer: WeatherMapLayer) {
        _uiState.update { current ->
            val clampedHour = if (!layer.supportsPastLoop && current.hourOffset < 0) 0 else current.hourOffset
            // Wind streamlines turn on automatically on Wind; preserve user's toggle when switching between other layers,
            // or start off when arriving on a non-Wind layer from Wind unless toggled explicitly.
            val nextWindFlow = when {
                layer == WeatherMapLayer.WIND -> true
                current.selectedLayer == WeatherMapLayer.WIND -> false
                else -> current.showWindStreamlines
            }
            val next = current.copy(
                selectedLayer = layer,
                hourOffset = clampedHour,
                showWindStreamlines = nextWindFlow,
                timeLabel = formatTimeLabel(clampedHour, current.activeZoneId)
            )
            next.copy(tilePlan = resolvePlanFor(next))
        }
        rebuildVisualProducts()
    }

    fun setWindMetricMode(mode: WindMetricMode) {
        if (_uiState.value.windMetricMode == mode) return
        _uiState.update { it.copy(windMetricMode = mode) }
        rebuildVisualProducts()
    }

    fun setSatelliteMode(mode: SatelliteMode) {
        if (_uiState.value.satelliteMode == mode) return
        _uiState.update { current ->
            val next = current.copy(satelliteMode = mode)
            next.copy(tilePlan = resolvePlanFor(next))
        }
    }

    fun toggleWindStreamlines() {
        _uiState.update { it.copy(showWindStreamlines = !it.showWindStreamlines) }
    }

    fun toggleLightning() {
        _uiState.update { current ->
            val next = current.copy(showLightning = !current.showLightning)
            next.copy(tilePlan = resolvePlanFor(next))
        }
    }

    fun toggleStormCells() {
        _uiState.update { current ->
            val next = current.copy(showStormCells = !current.showStormCells)
            next.copy(tilePlan = resolvePlanFor(next))
        }
    }

    fun setHourOffset(offset: Int, fromUserScrub: Boolean = true) {
        if (fromUserScrub && _uiState.value.isPlaying) {
            stopPlayback()
        }
        val state = _uiState.value
        val clamped = offset.coerceIn(state.minHourOffset, state.maxHourOffset)
        if (clamped == state.hourOffset) return
        _uiState.update { current ->
            val label = formatTimeLabel(clamped, current.activeZoneId)
            val next = current.copy(
                hourOffset = clamped,
                timeLabel = label
            )
            val refreshedReading = refreshTappedReading(next)
            next.copy(
                tilePlan = resolvePlanFor(next),
                tappedReading = refreshedReading
            )
        }
        rebuildVisualProducts()
    }

    fun togglePlayback() {
        if (_uiState.value.isPlaying) {
            stopPlayback()
        } else {
            startPlayback()
        }
    }

    fun onMapTapped(latitude: Double, longitude: Double) {
        _uiState.update { current ->
            val next = current.copy(tappedLat = latitude, tappedLon = longitude)
            next.copy(tappedReading = refreshTappedReading(next))
        }
    }

    fun dismissTappedReading() {
        _uiState.update { it.copy(tappedLat = null, tappedLon = null, tappedReading = null) }
    }

    fun updateFocusLocation(name: String, latitude: Double, longitude: Double, zoneId: ZoneId?) {
        _uiState.update { current ->
            val resolvedZone = zoneId ?: current.activeZoneId
            val next = current.copy(
                focusedPlaceName = name,
                cameraCenterLat = latitude,
                cameraCenterLon = longitude,
                activeZoneId = resolvedZone,
                timeLabel = formatTimeLabel(current.hourOffset, resolvedZone),
                tappedLat = null,
                tappedLon = null,
                tappedReading = null
            )
            next.copy(tilePlan = resolvePlanFor(next))
        }
    }

    fun dispose() {
        stopPlayback()
        fetchJob?.cancel()
        renderJob?.cancel()
    }

    private fun startPlayback() {
        playbackJob?.cancel()
        _uiState.update { it.copy(isPlaying = true) }
        playbackJob = scope.launch {
            while (isActive) {
                val cur = _uiState.value
                val stepDelayMs = if (cur.tilePlan.rasterLayers.isNotEmpty()) 950L else 500L
                delay(stepDelayMs)
                val latest = _uiState.value
                if (!latest.isPlaying) break
                val nextHour = if (latest.hourOffset >= latest.maxHourOffset) {
                    latest.minHourOffset
                } else {
                    latest.hourOffset + 1
                }
                setHourOffset(nextHour, fromUserScrub = false)
            }
        }
    }

    private fun stopPlayback() {
        playbackJob?.cancel()
        playbackJob = null
        _uiState.update { it.copy(isPlaying = false) }
    }

    private suspend fun performGridFetch(bounds: MapViewportBounds, forceRefresh: Boolean) {
        _uiState.update { it.copy(gridStatus = WeatherMapGridStatus.Loading) }
        when (val result = gridClient.fetchGridForViewport(bounds, forceRefresh = forceRefresh)) {
            is WeatherGridFetchResult.Success -> applyLoadedGrid(result.grid)
            is WeatherGridFetchResult.RateLimited -> {
                _uiState.update {
                    it.copy(gridStatus = WeatherMapGridStatus.Error(result.message, isRateLimit = true))
                }
            }
            is WeatherGridFetchResult.Failure -> {
                _uiState.update {
                    it.copy(gridStatus = WeatherMapGridStatus.Error(result.message, isRateLimit = false))
                }
            }
        }
    }

    private fun applyLoadedGrid(grid: WeatherGridField) {
        _uiState.update { current ->
            val next = current.copy(
                grid = grid,
                gridStatus = WeatherMapGridStatus.Ready
            )
            next.copy(tappedReading = refreshTappedReading(next))
        }
        rebuildVisualProducts()
    }

    private fun rebuildVisualProducts() {
        val snapshot = _uiState.value
        val grid = snapshot.grid ?: return
        val plan = snapshot.tilePlan
        val hourIndex = snapshot.gridHourIndex
        val layer = snapshot.selectedLayer
        val windMode = snapshot.windMetricMode

        renderJob?.cancel()
        renderJob = scope.launch {
            val rendered = withContext(Dispatchers.Default) {
                val bmp = if (plan.useSmoothOpenMeteoField) {
                    WeatherFieldMath.renderSmoothFieldBitmap(
                        grid = grid,
                        layer = layer,
                        hourIndex = hourIndex,
                        windMetricMode = windMode,
                        width = FIELD_BITMAP_SIZE,
                        height = FIELD_BITMAP_SIZE
                    )
                } else {
                    null
                }
                val isobars = if (layer == WeatherMapLayer.PRESSURE) {
                    WeatherFieldMath.extractIsobarContours(grid, hourIndex)
                } else {
                    emptyList()
                }
                val centers = if (layer == WeatherMapLayer.PRESSURE) {
                    WeatherFieldMath.findPressureCenters(grid, hourIndex)
                } else {
                    emptyList()
                }
                Triple(bmp, isobars, centers)
            }

            _uiState.update { current ->
                current.copy(
                    smoothFieldBitmap = rendered.first,
                    smoothFieldBounds = if (rendered.first != null) grid.toBounds() else null,
                    isobars = rendered.second,
                    pressureCenters = rendered.third
                )
            }
        }
    }

    private fun refreshTappedReading(state: WeatherMapUiState): MapPointReading? {
        val lat = state.tappedLat ?: return null
        val lon = state.tappedLon ?: return null
        val grid = state.grid ?: return null
        return WeatherFieldMath.samplePointReading(
            grid = grid,
            hourIndex = state.gridHourIndex,
            latitude = lat,
            longitude = lon,
            timeLabel = state.timeLabel,
            placeLabel = null
        )
    }

    private fun resolvePlanFor(state: WeatherMapUiState): ResolvedMapTilePlan {
        return WeatherTileSourceResolver.resolve(
            layer = state.selectedLayer,
            centerLat = state.cameraCenterLat,
            centerLon = state.cameraCenterLon,
            hourOffset = state.hourOffset,
            satelliteMode = state.satelliteMode,
            showLightning = state.showLightning,
            showStormCells = state.showStormCells
        )
    }

    companion object {
        private const val CAMERA_IDLE_DEBOUNCE_MS = 350L
        private const val FIELD_BITMAP_SIZE = 112

        private val HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:00", Locale.US)
        private val DAY_HOUR_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE HH:00", Locale.US)

        fun formatTimeLabel(hourOffset: Int, zoneId: ZoneId, now: Instant = Instant.now()): String {
            if (hourOffset == 0) return "Live · Now"
            val zonedNow = now.atZone(zoneId)
            val target = zonedNow.truncatedTo(ChronoUnit.HOURS).plusHours(hourOffset.toLong())
            if (hourOffset < 0) {
                val hrsAgo = -hourOffset
                return "${hrsAgo}h ago · ${target.format(HOUR_FORMATTER)}"
            }
            return if (target.toLocalDate() == zonedNow.toLocalDate()) {
                "Today · ${target.format(HOUR_FORMATTER)}"
            } else if (target.toLocalDate() == zonedNow.toLocalDate().plusDays(1)) {
                "Tomorrow · ${target.format(HOUR_FORMATTER)}"
            } else {
                target.format(DAY_HOUR_FORMATTER)
            }
        }
    }
}
