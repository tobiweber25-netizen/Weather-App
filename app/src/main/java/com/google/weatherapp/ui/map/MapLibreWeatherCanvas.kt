package com.google.weatherapp.ui.map

import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.weatherapp.data.api.ApiClient
import com.google.weatherapp.data.map.*
import com.google.weatherapp.theme.MapCanvasFallbackBg
import com.google.weatherapp.theme.MapIsobarStroke
import com.google.weatherapp.theme.MapTokens
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.ImageSource
import org.maplibre.android.style.sources.RasterSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

data class ProjectedScreenPoint(
    val xPx: Float,
    val yPx: Float,
    val visible: Boolean
)

data class MapCameraCommand(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val token: Long
)

/**
 * Native MapLibre OpenGL ES dark-vector map (OpenFreeMap Fiord with inline dark vector fallback) fused with:
 * - Smooth GPU-filtered Open-Meteo ImageSource overlays inserted BELOW borders & city names
 * - Live EUMETSAT Meteosat / NOAA GOES / Visual Crossing RasterSource layers BELOW city names
 * - Native GPU anti-aliased GeoJSON LineLayer for real 4 hPa sea-level pressure isobars
 * - Transparent 60fps hardware-accelerated WindParticleOverlayView above the map
 *
 * Initializes MapView before DisposableEffect so Android lifecycle events (onCreate -> onStart -> onResume)
 * always run deterministically before style loading, with TextureView mode for zero Compose flicker.
 */
@Composable
fun MapLibreWeatherCanvas(
    initialLatitude: Double,
    initialLongitude: Double,
    uiState: WeatherMapUiState,
    cameraCommand: MapCameraCommand?,
    styleReloadToken: Long,
    onViewportIdle: (MapViewportBounds) -> Unit,
    onMapTapped: (latitude: Double, longitude: Double) -> Unit,
    onProjectionChanged: ((LatLng) -> ProjectedScreenPoint?) -> Unit,
    onBasemapReadyChanged: (Boolean) -> Unit,
    onBasemapErrorChanged: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Ensure MapLibre native library and shared OkHttp client are initialized once before MapView creation
    remember(context) {
        MapLibre.getInstance(context.applicationContext)
        HttpRequestUtil.setOkHttpClient(ApiClient.okHttpClient)
        true
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    var resolvedStyleJson by remember { mutableStateOf<String?>(null) }
    var usedFallbackStyle by remember { mutableStateOf(false) }
    val activeExternalLayerIds = remember { mutableStateListOf<String>() }

    val latestOnViewportIdle by rememberUpdatedState(onViewportIdle)
    val latestOnMapTapped by rememberUpdatedState(onMapTapped)
    val latestOnProjectionChanged by rememberUpdatedState(onProjectionChanged)
    val latestOnBasemapReadyChanged by rememberUpdatedState(onBasemapReadyChanged)
    val latestOnBasemapErrorChanged by rememberUpdatedState(onBasemapErrorChanged)

    fun publishProjection(map: MapLibreMap, mv: MapView) {
        if (mv.width <= 0 || mv.height <= 0) return
        latestOnProjectionChanged { latLng ->
            val pt = map.projection.toScreenLocation(latLng)
            val vis = pt.x >= -80f && pt.x <= mv.width + 80f && pt.y >= -80f && pt.y <= mv.height + 80f
            ProjectedScreenPoint(pt.x, pt.y, vis)
        }
    }

    fun calibrateWindView(map: MapLibreMap, windView: WindParticleOverlayView) {
        if (windView.width <= 0 || windView.height <= 0) return
        val cam = map.cameraPosition
        val centerLat = cam.target?.latitude ?: 0.0
        windView.updateProjectionCalibration(
            bearingDegrees = cam.bearing,
            zoom = cam.zoom,
            centerLatitude = centerLat
        ) { sx, sy ->
            val ll = map.projection.fromScreenLocation(PointF(sx, sy))
            if (ll.latitude.isNaN() || ll.longitude.isNaN()) null else ll.latitude to ll.longitude
        }
    }

    fun emitCurrentBounds(map: MapLibreMap) {
        val region = map.projection.visibleRegion.latLngBounds
        val cam = map.cameraPosition
        val bounds = WeatherMapGridClient.normalizeVisibleViewport(
            south = region.latitudeSouth,
            west = region.longitudeWest,
            north = region.latitudeNorth,
            east = region.longitudeEast,
            centerLon = cam.target?.longitude ?: 0.0,
            zoom = cam.zoom
        )
        if (bounds != null) {
            latestOnViewportIdle(bounds)
        }
    }

    // Create MapView and WindParticleOverlayView deterministically BEFORE DisposableEffect
    val mapView = remember(context) {
        val options = MapLibreMapOptions.createFromAttributes(context, null)
            .textureMode(true)
            .foregroundLoadColor(MapCanvasFallbackBg.toArgb())
            .compassEnabled(false)
            .logoEnabled(false)
            .attributionEnabled(false)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(initialLatitude, initialLongitude))
                    .zoom(INITIAL_REGIONAL_ZOOM)
                    .build()
            )
        MapView(context, options).apply {
            onCreate(null)
        }
    }

    val windOverlayView = remember(context) {
        WindParticleOverlayView(context)
    }

    // Bind Android Lifecycle strictly to MapView on the VERY FIRST composition
    DisposableEffect(lifecycleOwner, mapView) {
        var isStarted = false
        var isResumed = false
        var isDestroyed = false

        val failListener = MapView.OnDidFailLoadingMapListener { errorMessage ->
            Log.w(TAG, "MapLibre failed loading map: $errorMessage")
            if (!usedFallbackStyle) {
                usedFallbackStyle = true
                resolvedStyleJson = WeatherTileSourceResolver.FALLBACK_DARK_VECTOR_STYLE_JSON
            } else {
                latestOnBasemapReadyChanged(false)
                latestOnBasemapErrorChanged("Map tiles couldn't load. Check your connection and retry.")
            }
        }
        mapView.addOnDidFailLoadingMapListener(failListener)

        val observer = LifecycleEventObserver { _, event ->
            if (isDestroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> if (!isStarted) {
                    mapView.onStart()
                    isStarted = true
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!isStarted) {
                        mapView.onStart()
                        isStarted = true
                    }
                    if (!isResumed) {
                        mapView.onResume()
                        isResumed = true
                    }
                }
                Lifecycle.Event.ON_PAUSE -> if (isResumed) {
                    mapView.onPause()
                    isResumed = false
                }
                Lifecycle.Event.ON_STOP -> if (isStarted) {
                    mapView.onStop()
                    isStarted = false
                }
                Lifecycle.Event.ON_DESTROY -> {
                    if (isResumed) {
                        mapView.onPause()
                        isResumed = false
                    }
                    if (isStarted) {
                        mapView.onStop()
                        isStarted = false
                    }
                    mapView.onDestroy()
                    isDestroyed = true
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        // Wire MapLibre callbacks once MapView is alive and lifecycle-bound
        mapView.getMapAsync { map ->
            mapLibreMap = map
            map.setMinZoomPreference(2.0)
            map.setMaxZoomPreference(13.5)
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isRotateGesturesEnabled = true

            map.addOnCameraMoveStartedListener {
                windOverlayView.setCameraMoving(true)
            }

            map.addOnCameraMoveListener {
                publishProjection(map, mapView)
            }

            map.addOnCameraIdleListener {
                publishProjection(map, mapView)
                calibrateWindView(map, windOverlayView)
                windOverlayView.setCameraMoving(false)
                emitCurrentBounds(map)
            }

            map.addOnMapClickListener { latLng ->
                latestOnMapTapped(latLng.latitude, latLng.longitude)
                true
            }
        }

        onDispose {
            mapView.removeOnDidFailLoadingMapListener(failListener)
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!isDestroyed) {
                if (isResumed) mapView.onPause()
                if (isStarted) mapView.onStop()
                mapView.onDestroy()
                isDestroyed = true
            }
        }
    }

    // Fetch/read cached Fiord style JSON over shared OkHttp in parallel with GPU surface creation
    LaunchedEffect(styleReloadToken) {
        latestOnBasemapErrorChanged(null)
        val (styleJson, isFiord) = WeatherTileSourceResolver.loadFiordOrFallbackStyleJson(
            forceRefresh = styleReloadToken > 0L
        )
        usedFallbackStyle = !isFiord
        resolvedStyleJson = styleJson
    }

    // Apply style JSON cleanly once BOTH MapLibreMap and style JSON are ready
    LaunchedEffect(mapLibreMap, resolvedStyleJson) {
        val map = mapLibreMap ?: return@LaunchedEffect
        val json = resolvedStyleJson ?: return@LaunchedEffect
        loadedStyle = null
        activeExternalLayerIds.clear()
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            // Guarantee foreground cover clears immediately once style is ready
            mapView.foreground = null
            loadedStyle = style
            latestOnBasemapReadyChanged(true)
            latestOnBasemapErrorChanged(null)
            publishProjection(map, mapView)
            calibrateWindView(map, windOverlayView)
            emitCurrentBounds(map)
        }
    }

    // Smooth programmatic camera jumps (re-center or saved-city quick jump)
    LaunchedEffect(cameraCommand?.token, mapLibreMap) {
        val cmd = cameraCommand ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val target = CameraPosition.Builder()
            .target(LatLng(cmd.latitude, cmd.longitude))
            .zoom(cmd.zoom)
            .bearing(0.0)
            .tilt(0.0)
            .build()
        map.animateCamera(CameraUpdateFactory.newCameraPosition(target), 600)
    }

    // Sync smooth Open-Meteo color-field ImageSource whenever bitmap or layer opacity updates
    LaunchedEffect(
        loadedStyle,
        uiState.smoothFieldBitmap,
        uiState.smoothFieldBounds,
        uiState.tilePlan.openMeteoOverlayOpacity,
        uiState.tilePlan.useSmoothOpenMeteoField
    ) {
        val style = loadedStyle ?: return@LaunchedEffect
        syncSmoothImageLayer(
            style = style,
            bitmap = if (uiState.tilePlan.useSmoothOpenMeteoField) uiState.smoothFieldBitmap else null,
            bounds = uiState.smoothFieldBounds,
            opacity = uiState.tilePlan.openMeteoOverlayOpacity,
            activeExternalLayerIds = activeExternalLayerIds
        )
    }

    // Sync external live satellite / radar / lightning RasterSources (diffed by ID to avoid flashing!)
    LaunchedEffect(loadedStyle, uiState.tilePlan.rasterLayers) {
        val style = loadedStyle ?: return@LaunchedEffect
        syncExternalRasterLayers(
            style = style,
            desired = uiState.tilePlan.rasterLayers,
            activeIds = activeExternalLayerIds
        )
    }

    // Sync real sea-level pressure isobars via native MapLibre GeoJSON LineLayer
    LaunchedEffect(loadedStyle, uiState.isobars, uiState.selectedLayer) {
        val style = loadedStyle ?: return@LaunchedEffect
        syncIsobarLayer(
            style = style,
            isobars = if (uiState.selectedLayer == WeatherMapLayer.PRESSURE) uiState.isobars else emptyList()
        )
    }

    // Sync frame-rate-independent WindParticleOverlayView state
    LaunchedEffect(
        windOverlayView,
        uiState.gridComposite,
        uiState.gridHourIndex,
        uiState.windMetricMode,
        uiState.showWindStreamlines,
        uiState.selectedLayer
    ) {
        val subtle = uiState.selectedLayer != WeatherMapLayer.WIND
        windOverlayView.updateWeatherState(
            composite = uiState.gridComposite,
            hourIndex = uiState.gridHourIndex,
            windMetricMode = uiState.windMetricMode,
            enabled = uiState.showWindStreamlines,
            subtleMode = subtle
        )
        val map = mapLibreMap
        if (map != null && !windOverlayView.isCalibrated && windOverlayView.width > 0) {
            calibrateWindView(map, windOverlayView)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapCanvasFallbackBg)
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val container = FrameLayout(ctx)
                windOverlayView.addOnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
                    if (v.width > 0 && v.height > 0) {
                        val resized = (right - left) != (oldRight - oldLeft) ||
                            (bottom - top) != (oldBottom - oldTop)
                        val map = mapLibreMap
                        if (map != null && (resized || !windOverlayView.isCalibrated)) {
                            publishProjection(map, mapView)
                            calibrateWindView(map, windOverlayView)
                            if (resized) {
                                emitCurrentBounds(map)
                            }
                        }
                    }
                }
                (mapView.parent as? android.view.ViewGroup)?.removeView(mapView)
                (windOverlayView.parent as? android.view.ViewGroup)?.removeView(windOverlayView)
                container.addView(
                    mapView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                container.addView(
                    windOverlayView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
                container
            }
        )
    }
}

private const val TAG = "MapLibreWeatherCanvas"
private const val INITIAL_REGIONAL_ZOOM = 6.2
private const val SMOOTH_FIELD_SOURCE_ID = "almanac-smooth-field-source"
private const val SMOOTH_FIELD_LAYER_ID = "almanac-smooth-field-layer"
private const val ISOBAR_SOURCE_ID = "almanac-isobar-source"
private const val ISOBAR_LAYER_ID = "almanac-isobar-layer"

private fun resolveAnchorLayerId(style: Style): String? {
    val preferred = WeatherTileSourceResolver.FIORD_LABEL_ANCHOR_LAYER_ID
    if (style.getLayer(preferred) != null) return preferred
    return style.layers.firstOrNull { layer ->
        layer.id.contains("boundary", ignoreCase = true) ||
            layer.id.contains("place_", ignoreCase = true)
    }?.id
}

private fun syncSmoothImageLayer(
    style: Style,
    bitmap: Bitmap?,
    bounds: MapViewportBounds?,
    opacity: Float,
    activeExternalLayerIds: List<String>
) {
    if (!style.isFullyLoaded) return
    val existingLayer = style.getLayerAs<RasterLayer>(SMOOTH_FIELD_LAYER_ID)
    if (bitmap == null || bitmap.isRecycled || bounds == null || opacity <= 0.01f) {
        existingLayer?.setProperties(PropertyFactory.visibility(Property.NONE))
        return
    }
    val quad = LatLngQuad(
        LatLng(bounds.north, bounds.west), // top-left
        LatLng(bounds.north, bounds.east), // top-right
        LatLng(bounds.south, bounds.east), // bottom-right
        LatLng(bounds.south, bounds.west)  // bottom-left
    )
    val existingSource = style.getSourceAs<ImageSource>(SMOOTH_FIELD_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setImage(bitmap)
        existingSource.setCoordinates(quad)
    } else {
        style.addSource(ImageSource(SMOOTH_FIELD_SOURCE_ID, quad, bitmap))
    }
    if (existingLayer != null) {
        existingLayer.setProperties(
            PropertyFactory.visibility(Property.VISIBLE),
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterFadeDuration(120f)
        )
    } else {
        val layer = RasterLayer(SMOOTH_FIELD_LAYER_ID, SMOOTH_FIELD_SOURCE_ID).withProperties(
            PropertyFactory.rasterOpacity(opacity),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            PropertyFactory.rasterFadeDuration(120f)
        )
        // Always stay underneath active satellite/radar raster layers AND underneath map borders/labels
        val firstExtLayerId = activeExternalLayerIds
            .map { "ext-layer-$it" }
            .firstOrNull { style.getLayer(it) != null }
        val anchor = firstExtLayerId ?: resolveAnchorLayerId(style)
        if (anchor != null) {
            style.addLayerBelow(layer, anchor)
        } else {
            style.addLayer(layer)
        }
    }
}

private fun syncExternalRasterLayers(
    style: Style,
    desired: List<ExternalRasterTileLayerSpec>,
    activeIds: MutableList<String>
) {
    if (!style.isFullyLoaded) return
    val desiredIds = desired.map { it.id }.toSet()

    // Remove stale external layers no longer in the active plan
    val toRemove = activeIds.filter { it !in desiredIds }
    for (id in toRemove) {
        val layerId = "ext-layer-$id"
        val sourceId = "ext-src-$id"
        if (style.getLayer(layerId) != null) style.removeLayer(layerId)
        if (style.getSource(sourceId) != null) style.removeSource(sourceId)
        activeIds.remove(id)
    }

    val anchor = resolveAnchorLayerId(style)
    for ((idx, spec) in desired.withIndex()) {
        val layerId = "ext-layer-${spec.id}"
        val sourceId = "ext-src-${spec.id}"
        val existing = style.getLayerAs<RasterLayer>(layerId)
        if (existing != null) {
            existing.setProperties(
                PropertyFactory.visibility(Property.VISIBLE),
                PropertyFactory.rasterOpacity(spec.opacity)
            )
            continue
        }
        val tileSet = TileSet("2.2.0", spec.urlTemplate).apply {
            minZoom = 0f
            maxZoom = spec.maxZoom
            spec.bounds?.let { b ->
                if (b.size == 4) {
                    setBounds(b[0], b[1], b[2], b[3])
                }
            }
        }
        style.addSource(RasterSource(sourceId, tileSet, spec.tileSize))
        val rasterLayer = RasterLayer(layerId, sourceId).withProperties(
            PropertyFactory.rasterOpacity(spec.opacity),
            PropertyFactory.rasterResampling(Property.RASTER_RESAMPLING_LINEAR),
            PropertyFactory.rasterFadeDuration(180f)
        )
        // Preserve bottom-to-top ordering in [desired] even when a lower layer (e.g. radar) arrives after an upper layer (e.g. lightning)
        val nextExistingLayerId = ((idx + 1) until desired.size)
            .map { "ext-layer-${desired[it].id}" }
            .firstOrNull { style.getLayer(it) != null }
        val insertBelow = nextExistingLayerId ?: anchor
        if (insertBelow != null) {
            style.addLayerBelow(rasterLayer, insertBelow)
        } else {
            style.addLayer(rasterLayer)
        }
        if (spec.id !in activeIds) {
            activeIds.add(spec.id)
        }
    }
}

private fun syncIsobarLayer(
    style: Style,
    isobars: List<IsobarContourLine>
) {
    if (!style.isFullyLoaded) return
    val features = isobars.mapNotNull { contour ->
        if (contour.points.size < 2) return@mapNotNull null
        val coords = contour.points.map { Point.fromLngLat(it.longitude, it.latitude) }
        Feature.fromGeometry(LineString.fromLngLats(coords)).apply {
            addNumberProperty("hpa", contour.pressureHpa)
        }
    }
    val collection = FeatureCollection.fromFeatures(features)
    val existingSource = style.getSourceAs<GeoJsonSource>(ISOBAR_SOURCE_ID)
    if (existingSource != null) {
        existingSource.setGeoJson(collection)
    } else {
        style.addSource(GeoJsonSource(ISOBAR_SOURCE_ID, collection))
    }
    val existingLayer = style.getLayerAs<LineLayer>(ISOBAR_LAYER_ID)
    if (existingLayer != null) {
        existingLayer.setProperties(
            PropertyFactory.visibility(if (features.isEmpty()) Property.NONE else Property.VISIBLE)
        )
    } else {
        val lineLayer = LineLayer(ISOBAR_LAYER_ID, ISOBAR_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(MapIsobarStroke.toArgb()),
            PropertyFactory.lineWidth(MapTokens.IsobarLineWidthPx),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineOpacity(0.86f),
            PropertyFactory.visibility(if (features.isEmpty()) Property.NONE else Property.VISIBLE)
        )
        val anchor = resolveAnchorLayerId(style)
        if (anchor != null) {
            style.addLayerBelow(lineLayer, anchor)
        } else {
            style.addLayer(lineLayer)
        }
    }
}
