package com.google.weatherapp.ui.map

import android.graphics.Bitmap
import com.google.weatherapp.data.map.*
import kotlinx.coroutines.CoroutineDispatcher
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
import kotlin.math.abs

data class WeatherMapUiState(
    val selectedLayer: WeatherMapLayer = WeatherMapLayer.WIND,
    val windMetricMode: WindMetricMode = WindMetricMode.SUSTAINED,
    val satelliteMode: SatelliteMode = SatelliteMode.GEOCOLOUR,
    val showWindStreamlines: Boolean = true,
    val showLightning: Boolean = true,
    val showStormEnergy: Boolean = false,
    val showTemperatureBadges: Boolean = true,
    val showIsotherms: Boolean = true,
    val hourOffset: Int = 0, // -2..47 on Storms/Satellite, 0..47 on Wind/Temp/Pressure
    val isPlaying: Boolean = false,
    val focusedPlaceName: String = "Current Location",
    val cameraCenterLat: Double = 0.0,
    val cameraCenterLon: Double = 0.0,
    val gridComposite: WeatherGridComposite? = null,
    val gridStatus: WeatherMapGridStatus = WeatherMapGridStatus.Loading,
    val smoothFieldBitmap: Bitmap? = null,
    val smoothFieldBounds: MapViewportBounds? = null,
    val isobars: List<IsobarContourLine> = emptyList(),
    val pressureCenters: List<PressureCenterMarker> = emptyList(),
    val temperatureSpots: List<TemperatureSpotMarker> = emptyList(),
    val isotherms: List<IsothermContourLine> = emptyList(),
    val rainViewerFrames: List<RainViewerRadarFrame> = emptyList(),
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
    val activeZoneId: ZoneId = ZoneId.systemDefault(),
    val referenceTimeUtc: Instant? = null
) {
    val grid: WeatherGridField? get() = gridComposite?.baseGrid
    val minHourOffset: Int get() = if (selectedLayer.supportsPastLoop) -2 else 0
    val maxHourOffset: Int get() = 47
    val gridHourIndex: Int get() = grid?.resolveHourIndex(hourOffset, referenceTimeUtc) ?: 0
    val inEumetsatCoverage: Boolean
        get() = WeatherTileSourceResolver.isInEumetsatDisk(cameraCenterLat, cameraCenterLon)
    val inUsCoverage: Boolean
        get() = WeatherTileSourceResolver.isInUsLightningCoverage(cameraCenterLat, cameraCenterLon)
}

/**
 * Lifecycle-scoped controller for WeatherMapScreen.
 * Handles canonical world-locked viewport grid fetching, hierarchical multi-zoom compositing,
 * background Web-Mercator-exact smooth color-field & isobar rendering, live satellite/radar
 * routing, timeline playback, and tap-anywhere inspection.
 */
class WeatherMapStateHolder(
    private val scope: CoroutineScope,
    initialLayer: WeatherMapLayer,
    initialLatitude: Double,
    initialLongitude: Double,
    initialPlaceName: String,
    initialZoneId: ZoneId = ZoneId.systemDefault(),
    initialPriorityPlaces: List<Pair<String, GeoPoint>> = emptyList(),
    private val gridClient: WeatherMapGridClient = WeatherMapGridClient.sharedInstance,
    private val clock: () -> Instant = { Instant.now() },
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default
) {
    private val initialNow: Instant = clock()
    private var focusedAnchorLat: Double = initialLatitude
    private var focusedAnchorLon: Double = initialLongitude
    private var priorityPlaces: List<Pair<String, GeoPoint>> = buildPriorityPlacesList(
        focusName = initialPlaceName,
        focusLat = focusedAnchorLat,
        focusLon = focusedAnchorLon,
        places = initialPriorityPlaces
    )
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
                hourOffset = 0,
                nowUtc = initialNow
            ),
            timeLabel = formatTimeLabel(0, initialZoneId, initialNow),
            activeZoneId = initialZoneId,
            referenceTimeUtc = initialNow
        )
    )
    val uiState: StateFlow<WeatherMapUiState> = _uiState.asStateFlow()

    private var fetchJob: Job? = null
    private var renderJob: Job? = null
    private var playbackJob: Job? = null
    private var rainViewerJob: Job? = null
    private var lastViewport: MapViewportBounds? = null
    private var inFlightCanonicalBounds: MapViewportBounds? = null

    init {
        // Kick off weather grid loading at millisecond 0 in parallel with MapLibre init
        val initialBounds = WeatherMapGridClient.estimateInitialViewport(initialLatitude, initialLongitude)
        lastViewport = initialBounds
        val cached = gridClient.findCachedComposite(initialBounds)
        if (cached != null) {
            applyLoadedComposite(cached)
        } else {
            val canonicalInitial = WeatherMapGridClient.computePaddedFetchBounds(initialBounds)
            inFlightCanonicalBounds = canonicalInitial
            fetchJob = scope.launch {
                performGridFetch(initialBounds, canonicalInitial, forceRefresh = false)
            }
        }
        maybeFetchRainViewerFrames()
    }

    fun onViewportIdle(bounds: MapViewportBounds) {
        lastViewport = bounds
        val prevPlan = _uiState.value.tilePlan
        val prevHourIndex = _uiState.value.gridHourIndex
        val now = clock()
        _uiState.update { state ->
            val withTime = state.copy(
                cameraCenterLat = bounds.centerLat,
                cameraCenterLon = bounds.centerLon,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(state.hourOffset, state.activeZoneId, now)
            )
            val updatedPlan = resolvePlanFor(withTime, now)
            withTime.copy(
                tilePlan = updatedPlan,
                tappedReading = refreshTappedReading(withTime)
            )
        }
        maybeFetchRainViewerFrames()
        val latest = _uiState.value
        val newPlan = latest.tilePlan
        val planFieldChanged = prevPlan.useSmoothOpenMeteoField != newPlan.useSmoothOpenMeteoField ||
            prevPlan.stormFieldMode != newPlan.stormFieldMode ||
            prevHourIndex != latest.gridHourIndex

        // Fast cache hit: zooming IN or gentle pans stay 100% inside cached canonical grid
        val cached = gridClient.findCachedComposite(bounds)
        if (cached != null) {
            if (!compositesEquivalent(_uiState.value.gridComposite, cached)) {
                applyLoadedComposite(cached)
            } else {
                if (_uiState.value.gridStatus !is WeatherMapGridStatus.Ready) {
                    _uiState.update { it.copy(gridStatus = WeatherMapGridStatus.Ready) }
                }
                if (planFieldChanged || latest.selectedLayer == WeatherMapLayer.TEMPERATURE) {
                    rebuildVisualProducts()
                }
            }
            return
        }

        val desiredCanonical = WeatherMapGridClient.computePaddedFetchBounds(bounds)
        val desiredStep = (desiredCanonical.north - desiredCanonical.south) /
            (WeatherMapGridClient.GRID_ROWS - 1).coerceAtLeast(1)
        val inFlight = inFlightCanonicalBounds
        // Keep an in-flight fetch ONLY if it already covers the settled viewport at equal-or-finer step
        if (fetchJob?.isActive == true && inFlight != null) {
            val inFlightStep = (inFlight.north - inFlight.south) /
                (WeatherMapGridClient.GRID_ROWS - 1).coerceAtLeast(1)
            val clamped = WeatherMapGridClient.clampViewportToCanonicalDomain(bounds, inFlightStep)
            if (inFlightStep <= desiredStep + 0.05 && inFlight.coversViewport(clamped)) {
                return
            }
        }

        fetchJob?.cancel()
        inFlightCanonicalBounds = desiredCanonical
        fetchJob = scope.launch {
            if (_uiState.value.gridComposite != null) {
                delay(CAMERA_IDLE_DEBOUNCE_MS)
            }
            performGridFetch(bounds, desiredCanonical, forceRefresh = false)
        }
    }

    fun retryFetch() {
        val bounds = lastViewport ?: return
        val desiredCanonical = WeatherMapGridClient.computePaddedFetchBounds(bounds)
        fetchJob?.cancel()
        inFlightCanonicalBounds = desiredCanonical
        fetchJob = scope.launch {
            performGridFetch(bounds, desiredCanonical, forceRefresh = true)
        }
        maybeFetchRainViewerFrames(forceRefresh = true)
    }

    fun selectLayer(layer: WeatherMapLayer) {
        val now = clock()
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
                timeLabel = formatTimeLabel(clampedHour, current.activeZoneId, now),
                referenceTimeUtc = now
            )
            next.copy(
                tilePlan = resolvePlanFor(next, now),
                tappedReading = refreshTappedReading(next)
            )
        }
        maybeFetchRainViewerFrames()
        rebuildVisualProducts()
    }

    fun setWindMetricMode(mode: WindMetricMode) {
        if (_uiState.value.windMetricMode == mode) return
        _uiState.update { it.copy(windMetricMode = mode) }
        rebuildVisualProducts()
    }

    fun setSatelliteMode(mode: SatelliteMode) {
        if (_uiState.value.satelliteMode == mode) return
        val prevHourIndex = _uiState.value.gridHourIndex
        val now = clock()
        _uiState.update { current ->
            val next = current.copy(
                satelliteMode = mode,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(current.hourOffset, current.activeZoneId, now)
            )
            next.copy(
                tilePlan = resolvePlanFor(next, now),
                tappedReading = refreshTappedReading(next)
            )
        }
        if (prevHourIndex != _uiState.value.gridHourIndex) {
            rebuildVisualProducts()
        }
    }

    fun toggleWindStreamlines() {
        _uiState.update { it.copy(showWindStreamlines = !it.showWindStreamlines) }
    }

    fun toggleLightning() {
        val prevHourIndex = _uiState.value.gridHourIndex
        val now = clock()
        _uiState.update { current ->
            val next = current.copy(
                showLightning = !current.showLightning,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(current.hourOffset, current.activeZoneId, now)
            )
            next.copy(
                tilePlan = resolvePlanFor(next, now),
                tappedReading = refreshTappedReading(next)
            )
        }
        if (prevHourIndex != _uiState.value.gridHourIndex) {
            rebuildVisualProducts()
        }
    }

    fun toggleStormEnergy() {
        val now = clock()
        _uiState.update { current ->
            val next = current.copy(
                showStormEnergy = !current.showStormEnergy,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(current.hourOffset, current.activeZoneId, now)
            )
            next.copy(
                tilePlan = resolvePlanFor(next, now),
                tappedReading = refreshTappedReading(next)
            )
        }
        rebuildVisualProducts()
    }

    fun toggleTemperatureBadges() {
        _uiState.update { it.copy(showTemperatureBadges = !it.showTemperatureBadges) }
        if (_uiState.value.showTemperatureBadges && _uiState.value.temperatureSpots.isEmpty()) {
            rebuildVisualProducts()
        }
    }

    fun toggleIsotherms() {
        _uiState.update { it.copy(showIsotherms = !it.showIsotherms) }
        if (_uiState.value.showIsotherms && _uiState.value.isotherms.isEmpty()) {
            rebuildVisualProducts()
        }
    }

    fun updatePriorityPlaces(places: List<Pair<String, GeoPoint>>) {
        val state = _uiState.value
        val updated = buildPriorityPlacesList(
            focusName = state.focusedPlaceName,
            focusLat = focusedAnchorLat,
            focusLon = focusedAnchorLon,
            places = places
        )
        if (updated == priorityPlaces) return
        priorityPlaces = updated
        if (state.selectedLayer == WeatherMapLayer.TEMPERATURE && state.gridComposite != null) {
            rebuildVisualProducts()
        }
    }

    fun setHourOffset(offset: Int, fromUserScrub: Boolean = true) {
        if (fromUserScrub && _uiState.value.isPlaying) {
            stopPlayback()
        }
        val state = _uiState.value
        val clamped = offset.coerceIn(state.minHourOffset, state.maxHourOffset)
        if (clamped == state.hourOffset) return
        val now = clock()
        _uiState.update { current ->
            val label = formatTimeLabel(clamped, current.activeZoneId, now)
            val next = current.copy(
                hourOffset = clamped,
                timeLabel = label,
                referenceTimeUtc = now
            )
            val refreshedReading = refreshTappedReading(next)
            next.copy(
                tilePlan = resolvePlanFor(next, now),
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
        val prevHourIndex = _uiState.value.gridHourIndex
        val now = clock()
        _uiState.update { current ->
            val next = current.copy(
                tappedLat = latitude,
                tappedLon = longitude,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(current.hourOffset, current.activeZoneId, now)
            )
            next.copy(
                tilePlan = if (prevHourIndex != next.gridHourIndex) resolvePlanFor(next, now) else current.tilePlan,
                tappedReading = refreshTappedReading(next)
            )
        }
        if (prevHourIndex != _uiState.value.gridHourIndex) {
            rebuildVisualProducts()
        }
    }

    fun dismissTappedReading() {
        _uiState.update { it.copy(tappedLat = null, tappedLon = null, tappedReading = null) }
    }

    fun updateFocusLocation(name: String, latitude: Double, longitude: Double, zoneId: ZoneId?) {
        val now = clock()
        focusedAnchorLat = latitude
        focusedAnchorLon = longitude
        priorityPlaces = buildPriorityPlacesList(
            focusName = name,
            focusLat = focusedAnchorLat,
            focusLon = focusedAnchorLon,
            places = priorityPlaces
        )
        _uiState.update { current ->
            val resolvedZone = zoneId ?: current.activeZoneId
            val next = current.copy(
                focusedPlaceName = name,
                cameraCenterLat = latitude,
                cameraCenterLon = longitude,
                activeZoneId = resolvedZone,
                timeLabel = formatTimeLabel(current.hourOffset, resolvedZone, now),
                referenceTimeUtc = now,
                tappedLat = null,
                tappedLon = null,
                tappedReading = null
            )
            next.copy(tilePlan = resolvePlanFor(next, now))
        }
        maybeFetchRainViewerFrames()
        if (_uiState.value.selectedLayer == WeatherMapLayer.TEMPERATURE) {
            rebuildVisualProducts()
        }
    }

    fun dispose() {
        stopPlayback()
        fetchJob?.cancel()
        renderJob?.cancel()
        rainViewerJob?.cancel()
    }

    private fun maybeFetchRainViewerFrames(forceRefresh: Boolean = false) {
        val state = _uiState.value
        if (state.selectedLayer != WeatherMapLayer.STORMS || state.inEumetsatCoverage) return
        if (!forceRefresh && (state.rainViewerFrames.isNotEmpty() || rainViewerJob?.isActive == true)) return
        rainViewerJob?.cancel()
        rainViewerJob = scope.launch {
            val frames = WeatherTileSourceResolver.fetchRainViewerRadarFrames(forceRefresh = forceRefresh)
            if (frames.isNotEmpty()) {
                val now = clock()
                _uiState.update { current ->
                    val next = current.copy(rainViewerFrames = frames, referenceTimeUtc = now)
                    next.copy(tilePlan = resolvePlanFor(next, now))
                }
                rebuildVisualProducts()
            }
        }
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

    private suspend fun performGridFetch(
        bounds: MapViewportBounds,
        canonicalBounds: MapViewportBounds,
        forceRefresh: Boolean
    ) {
        _uiState.update { it.copy(gridStatus = WeatherMapGridStatus.Loading) }
        try {
            when (val result = gridClient.fetchGridForViewport(bounds, forceRefresh = forceRefresh)) {
                is WeatherGridFetchResult.Success -> applyLoadedComposite(result.composite)
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
        } finally {
            if (inFlightCanonicalBounds == canonicalBounds) {
                inFlightCanonicalBounds = null
            }
        }
    }

    private fun applyLoadedComposite(composite: WeatherGridComposite) {
        val now = clock()
        _uiState.update { current ->
            val next = current.copy(
                gridComposite = composite,
                gridStatus = WeatherMapGridStatus.Ready,
                referenceTimeUtc = now,
                timeLabel = formatTimeLabel(current.hourOffset, current.activeZoneId, now)
            )
            next.copy(
                tilePlan = resolvePlanFor(next, now),
                tappedReading = refreshTappedReading(next)
            )
        }
        rebuildVisualProducts()
    }

    private data class RenderedMapProducts(
        val bitmap: Bitmap?,
        val isobars: List<IsobarContourLine>,
        val pressureCenters: List<PressureCenterMarker>,
        val temperatureSpots: List<TemperatureSpotMarker>,
        val isotherms: List<IsothermContourLine>
    )

    private fun rebuildVisualProducts() {
        val snapshot = _uiState.value
        val composite = snapshot.gridComposite ?: return
        val plan = snapshot.tilePlan
        val hourIndex = snapshot.gridHourIndex
        val layer = snapshot.selectedLayer
        val windMode = snapshot.windMetricMode
        val viewport = lastViewport
        val placesSnapshot = priorityPlaces

        renderJob?.cancel()
        renderJob = scope.launch {
            val rendered = withContext(computeDispatcher) {
                val bmp = if (plan.useSmoothOpenMeteoField) {
                    try {
                        WeatherFieldMath.renderSmoothFieldBitmap(
                            composite = composite,
                            layer = layer,
                            hourIndex = hourIndex,
                            windMetricMode = windMode,
                            width = FIELD_BITMAP_SIZE,
                            height = FIELD_BITMAP_SIZE,
                            stormFieldMode = plan.stormFieldMode
                        )
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }
                val isobars = if (layer == WeatherMapLayer.PRESSURE) {
                    WeatherFieldMath.extractIsobarContours(composite, hourIndex)
                } else {
                    emptyList()
                }
                val centers = if (layer == WeatherMapLayer.PRESSURE) {
                    WeatherFieldMath.findPressureCenters(composite, hourIndex)
                } else {
                    emptyList()
                }
                val tempSpots = if (layer == WeatherMapLayer.TEMPERATURE) {
                    WeatherFieldMath.extractTemperatureSpots(
                        composite = composite,
                        hourIndex = hourIndex,
                        viewport = viewport,
                        priorityPlaces = placesSnapshot
                    )
                } else {
                    emptyList()
                }
                val isotherms = if (layer == WeatherMapLayer.TEMPERATURE) {
                    WeatherFieldMath.extractIsothermContours(
                        composite = composite,
                        hourIndex = hourIndex
                    )
                } else {
                    emptyList()
                }
                RenderedMapProducts(
                    bitmap = bmp,
                    isobars = isobars,
                    pressureCenters = centers,
                    temperatureSpots = tempSpots,
                    isotherms = isotherms
                )
            }

            _uiState.update { current ->
                current.copy(
                    smoothFieldBitmap = rendered.bitmap,
                    smoothFieldBounds = if (rendered.bitmap != null) composite.toBounds() else null,
                    isobars = rendered.isobars,
                    pressureCenters = rendered.pressureCenters,
                    temperatureSpots = rendered.temperatureSpots,
                    isotherms = rendered.isotherms
                )
            }
        }
    }

    private fun refreshTappedReading(state: WeatherMapUiState): MapPointReading? {
        val lat = state.tappedLat ?: return null
        val lon = state.tappedLon ?: return null
        val composite = state.gridComposite ?: return null
        val matchedLabel = priorityPlaces.firstOrNull { (_, pt) ->
            abs(pt.latitude - lat) < 0.03 && abs(pt.longitude - lon) < 0.03
        }?.first
        return WeatherFieldMath.samplePointReading(
            composite = composite,
            hourIndex = state.gridHourIndex,
            latitude = lat,
            longitude = lon,
            timeLabel = state.timeLabel,
            placeLabel = matchedLabel
        )
    }

    private fun buildPriorityPlacesList(
        focusName: String,
        focusLat: Double,
        focusLon: Double,
        places: List<Pair<String, GeoPoint>>
    ): List<Pair<String, GeoPoint>> {
        val result = mutableListOf<Pair<String, GeoPoint>>()
        if (focusName.isNotBlank()) {
            result.add(focusName to GeoPoint(focusLat, focusLon))
        }
        for ((name, pt) in places) {
            if (name.isBlank()) continue
            val exists = result.any { (_, existingPt) ->
                abs(existingPt.latitude - pt.latitude) < 0.02 &&
                    abs(existingPt.longitude - pt.longitude) < 0.02
            }
            if (!exists) {
                result.add(name to pt)
            }
        }
        return result
    }

    private fun resolvePlanFor(
        state: WeatherMapUiState,
        nowUtc: Instant = state.referenceTimeUtc ?: clock()
    ): ResolvedMapTilePlan {
        return WeatherTileSourceResolver.resolve(
            layer = state.selectedLayer,
            centerLat = state.cameraCenterLat,
            centerLon = state.cameraCenterLon,
            hourOffset = state.hourOffset,
            satelliteMode = state.satelliteMode,
            showLightning = state.showLightning,
            showStormEnergy = state.showStormEnergy,
            rainViewerFrames = state.rainViewerFrames,
            nowUtc = nowUtc
        )
    }

    private fun compositesEquivalent(a: WeatherGridComposite?, b: WeatherGridComposite?): Boolean {
        if (a === b) return true
        if (a == null || b == null) return false
        if (a.baseGrid !== b.baseGrid) return false
        if (a.detailGrids.size != b.detailGrids.size) return false
        for (i in a.detailGrids.indices) {
            if (a.detailGrids[i] !== b.detailGrids[i]) return false
        }
        return true
    }

    companion object {
        private const val CAMERA_IDLE_DEBOUNCE_MS = 350L
        private const val FIELD_BITMAP_SIZE = 256

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
