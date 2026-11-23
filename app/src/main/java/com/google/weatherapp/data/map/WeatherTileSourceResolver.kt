package com.google.weatherapp.data.map

import com.google.gson.JsonParser
import com.google.weatherapp.data.api.ApiClient
import com.google.weatherapp.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ExternalRasterTileLayerSpec(
    val id: String,
    val urlTemplate: String,
    val maxZoom: Float,
    val tileSize: Int = 512,
    val opacity: Float = 0.84f,
    val bounds: FloatArray? = null // [west, south, east, north]
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ExternalRasterTileLayerSpec) return false
        return id == other.id &&
            urlTemplate == other.urlTemplate &&
            maxZoom == other.maxZoom &&
            tileSize == other.tileSize &&
            opacity == other.opacity &&
            ((bounds == null && other.bounds == null) ||
                (bounds != null && other.bounds != null && bounds.contentEquals(other.bounds)))
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + urlTemplate.hashCode()
        result = 31 * result + maxZoom.hashCode()
        result = 31 * result + tileSize
        result = 31 * result + opacity.hashCode()
        result = 31 * result + (bounds?.contentHashCode() ?: 0)
        return result
    }
}

data class ResolvedMapTilePlan(
    val rasterLayers: List<ExternalRasterTileLayerSpec>,
    val useSmoothOpenMeteoField: Boolean,
    val openMeteoOverlayOpacity: Float,
    val sourceBadge: String,
    val attributionLabel: String,
    val stormFieldMode: StormFieldMode = StormFieldMode.CAPE_ONLY,
    val legendStops: List<MapLegendStop>? = null
)

/**
 * Routes each weather layer, geographic region, and timeline offset (-2h..+48h) to
 * verified, free, keyless real weather feeds (EUMETSAT Meteosat H60B & MTG Lightning,
 * RainViewer smooth Doppler radar, NOAA GOES-East IR, and smooth Open-Meteo model fields),
 * returning honest source labels and matching legends every time.
 */
object WeatherTileSourceResolver {

    const val OPENFREEMAP_FIORD_STYLE_URL = "https://tiles.openfreemap.org/styles/fiord"
    const val FIORD_LABEL_ANCHOR_LAYER_ID = "boundary_state"

    private const val EUMETSAT_WMS_BASE = "https://view.eumetsat.int/geoserver/wms"
    private const val RAINVIEWER_MAPS_API_URL = "https://api.rainviewer.com/public/weather-maps.json"
    private const val RAINVIEWER_TILE_HOST = "https://tilecache.rainviewer.com"
    private const val VISUAL_CROSSING_TILE_BASE =
        "https://maps.visualcrossing.com/VisualCrossingWebServices/rest/api/v1/map/tile"
    private const val IEM_GOES_EAST_IR_URL =
        "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/goes-east-ir-4km-900913/{z}/{x}/{y}.png"

    private val EUMETSAT_BOUNDS = floatArrayOf(-70f, -70f, 70f, 70f)
    private val GOES_EAST_BOUNDS = floatArrayOf(-140f, -55f, -35f, 65f)
    private val US_LIGHTNING_BOUNDS = floatArrayOf(-130f, 20f, -62f, 54f)

    private val ISO_UTC_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00:00'Z'").withZone(ZoneOffset.UTC)

    private val EUMETSAT_H60B_LEGEND_NO_BOLT: List<MapLegendStop> = listOf(
        MapLegendStop(MapH60bLight, "0.5"),
        MapLegendStop(MapH60bModerate, "2"),
        MapLegendStop(MapH60bHeavy, "10"),
        MapLegendStop(MapH60bIntense, "20"),
        MapLegendStop(MapH60bExtreme, "50+")
    )

    private val EUMETSAT_H60B_LEGEND_WITH_BOLT: List<MapLegendStop> =
        EUMETSAT_H60B_LEGEND_NO_BOLT + MapLegendStop(MapHudLightningAccent, "Bolt")

    private val MODEL_PRECIP_LEGEND_NO_BOLT: List<MapLegendStop> = listOf(
        MapLegendStop(MapRainLight, "0.5"),
        MapLegendStop(MapRainModerate, "2"),
        MapLegendStop(MapRainHeavy, "8"),
        MapLegendStop(MapRainIntense, "20"),
        MapLegendStop(MapRainExtreme, "40+")
    )

    private val MODEL_PRECIP_LEGEND_WITH_BOLT: List<MapLegendStop> =
        MODEL_PRECIP_LEGEND_NO_BOLT + MapLegendStop(MapHudLightningAccent, "Bolt")

    private val RAINVIEWER_RADAR_LEGEND_NO_BOLT: List<MapLegendStop> = listOf(
        MapLegendStop(MapRadarLight, "0.5"),
        MapLegendStop(MapRadarModerate, "2"),
        MapLegendStop(MapRainHeavy, "8"),
        MapLegendStop(MapRainIntense, "20"),
        MapLegendStop(MapRainExtreme, "40+")
    )

    private val RAINVIEWER_RADAR_LEGEND_WITH_BOLT: List<MapLegendStop> =
        RAINVIEWER_RADAR_LEGEND_NO_BOLT + MapLegendStop(MapHudLightningAccent, "Bolt")

    fun isInEumetsatDisk(latitude: Double, longitude: Double): Boolean =
        latitude in -65.0..65.0 && longitude in -65.0..65.0

    fun isInAmericasGoesDisk(latitude: Double, longitude: Double): Boolean =
        latitude in -50.0..62.0 && longitude in -138.0..-40.0

    fun isInUsLightningCoverage(latitude: Double, longitude: Double): Boolean =
        latitude in 22.0..52.0 && longitude in -128.0..-65.0

    fun resolve(
        layer: WeatherMapLayer,
        centerLat: Double,
        centerLon: Double,
        hourOffset: Int,
        satelliteMode: SatelliteMode = SatelliteMode.GEOCOLOUR,
        showLightning: Boolean = true,
        showStormEnergy: Boolean = false,
        rainViewerFrames: List<RainViewerRadarFrame> = emptyList(),
        nowUtc: Instant = Instant.now()
    ): ResolvedMapTilePlan {
        val inEumetsat = isInEumetsatDisk(centerLat, centerLon)
        val inAmericas = isInAmericasGoesDisk(centerLat, centerLon)
        val inUs = isInUsLightningCoverage(centerLat, centerLon)

        return when (layer) {
            WeatherMapLayer.WIND -> {
                val badge = if (hourOffset <= 0) "Open-Meteo · Live Wind" else "Forecast · +${hourOffset}h"
                ResolvedMapTilePlan(
                    rasterLayers = emptyList(),
                    useSmoothOpenMeteoField = true,
                    openMeteoOverlayOpacity = 0.64f,
                    sourceBadge = badge,
                    attributionLabel = "OpenFreeMap · Open-Meteo"
                )
            }

            WeatherMapLayer.STORMS -> resolveStormsPlan(
                inEumetsat = inEumetsat,
                inUs = inUs,
                hourOffset = hourOffset,
                showLightning = showLightning,
                showStormEnergy = showStormEnergy,
                rainViewerFrames = rainViewerFrames,
                nowUtc = nowUtc
            )

            WeatherMapLayer.SATELLITE -> resolveSatellitePlan(
                inEumetsat = inEumetsat,
                inAmericas = inAmericas,
                hourOffset = hourOffset,
                satelliteMode = satelliteMode,
                nowUtc = nowUtc
            )

            WeatherMapLayer.TEMPERATURE -> {
                val badge = if (hourOffset <= 0) "Open-Meteo · Live Temp" else "Forecast · +${hourOffset}h"
                ResolvedMapTilePlan(
                    rasterLayers = emptyList(),
                    useSmoothOpenMeteoField = true,
                    openMeteoOverlayOpacity = 0.58f,
                    sourceBadge = badge,
                    attributionLabel = "OpenFreeMap · Open-Meteo"
                )
            }

            WeatherMapLayer.PRESSURE -> {
                val badge = if (hourOffset <= 0) "Open-Meteo · Isobars" else "Forecast · +${hourOffset}h"
                ResolvedMapTilePlan(
                    rasterLayers = emptyList(),
                    useSmoothOpenMeteoField = true,
                    openMeteoOverlayOpacity = 0.44f,
                    sourceBadge = badge,
                    attributionLabel = "OpenFreeMap · Open-Meteo"
                )
            }
        }
    }

    private fun resolveStormsPlan(
        inEumetsat: Boolean,
        inUs: Boolean,
        hourOffset: Int,
        showLightning: Boolean,
        showStormEnergy: Boolean,
        rainViewerFrames: List<RainViewerRadarFrame>,
        nowUtc: Instant
    ): ResolvedMapTilePlan {
        // 1. Forecast (+1h..+48h worldwide): smooth GPU Open-Meteo real precipitation + storm energy
        // Avoids slow, blocky external forecast raster tiles and enables instant 0ms timeline scrubbing.
        if (hourOffset > 0) {
            return ResolvedMapTilePlan(
                rasterLayers = emptyList(),
                useSmoothOpenMeteoField = true,
                openMeteoOverlayOpacity = 0.84f,
                stormFieldMode = StormFieldMode.PRECIPITATION_AND_CAPE,
                legendStops = MODEL_PRECIP_LEGEND_NO_BOLT,
                sourceBadge = "Forecast · +${hourOffset}h Rain & CAPE",
                attributionLabel = "OpenFreeMap · Open-Meteo"
            )
        }

        // 2. Live & Past (-2h..0h) inside EUMETSAT disk (Africa, Europe, Middle East):
        // Smooth 512px H-SAF H60B blended satellite/microwave rain + MTG-I Space Lightning Imager.
        // Capped at maxZoom 5 (native ~2.4 km/px) so only 2-4 tiles load in <1s and never reload on zoom-in.
        if (inEumetsat) {
            val timeParam = buildEumetsatPastTimeParam(hourOffset, nowUtc)
            val idSuffix = timeParam ?: "live"
            val layers = mutableListOf<ExternalRasterTileLayerSpec>()
            layers.add(
                ExternalRasterTileLayerSpec(
                    id = "eumetsat-h60b-$idSuffix",
                    urlTemplate = buildEumetsatWmsUrl(
                        wmsLayer = "msg_fes:h60b",
                        format = "image/png",
                        transparent = true,
                        tileSize = 512,
                        timeParam = timeParam
                    ),
                    maxZoom = 5f,
                    tileSize = 512,
                    opacity = 0.86f,
                    bounds = EUMETSAT_BOUNDS
                )
            )
            if (showLightning) {
                layers.add(
                    ExternalRasterTileLayerSpec(
                        id = "eumetsat-lightning-$idSuffix",
                        urlTemplate = buildEumetsatWmsUrl(
                            wmsLayer = "mtg_fd:li_afa",
                            format = "image/png",
                            transparent = true,
                            tileSize = 512,
                            timeParam = timeParam
                        ),
                        maxZoom = 5f,
                        tileSize = 512,
                        opacity = 0.96f,
                        bounds = EUMETSAT_BOUNDS
                    )
                )
            }
            val timePrefix = if (hourOffset == 0) "Meteosat Live" else "${hourOffset}h · Meteosat"
            val detail = if (showLightning) "Lightning & Rain" else "Satellite Rain"
            return ResolvedMapTilePlan(
                rasterLayers = layers,
                useSmoothOpenMeteoField = showStormEnergy,
                openMeteoOverlayOpacity = if (showStormEnergy) 0.34f else 0f,
                stormFieldMode = StormFieldMode.CAPE_ONLY,
                legendStops = if (showLightning) EUMETSAT_H60B_LEGEND_WITH_BOLT else EUMETSAT_H60B_LEGEND_NO_BOLT,
                sourceBadge = "$timePrefix · $detail",
                attributionLabel = if (showStormEnergy) {
                    "OpenFreeMap · EUMETSAT · Open-Meteo"
                } else {
                    "OpenFreeMap · EUMETSAT"
                }
            )
        }

        // 3. Live & Past (-2h..0h) outside EUMETSAT (Americas, Australia, Asia-Pacific):
        // Fast 512px smooth RainViewer Doppler radar CDN + smooth Open-Meteo rain coverage
        // (+ live US lightning when inside US coverage).
        val layers = mutableListOf<ExternalRasterTileLayerSpec>()
        val selectedRadarFrame = selectRainViewerFrame(rainViewerFrames, hourOffset, nowUtc)
        if (selectedRadarFrame != null) {
            layers.add(
                ExternalRasterTileLayerSpec(
                    id = "rainviewer-radar-${selectedRadarFrame.timestampEpochSec}",
                    urlTemplate = "$RAINVIEWER_TILE_HOST${selectedRadarFrame.path}/512/{z}/{x}/{y}/2/1_1.png",
                    maxZoom = 7f,
                    tileSize = 512,
                    opacity = 0.84f,
                    bounds = null
                )
            )
        }
        val hasUsLightning = hourOffset == 0 && inUs && showLightning
        if (hasUsLightning) {
            layers.add(
                ExternalRasterTileLayerSpec(
                    id = "vc-lightning-us",
                    urlTemplate = "$VISUAL_CROSSING_TILE_BASE/lightning/{z}/{x}/{y}.webp?time=latest&unitGroup=metric",
                    maxZoom = 8f,
                    tileSize = 256,
                    opacity = 0.95f,
                    bounds = US_LIGHTNING_BOUNDS
                )
            )
        }
        val hasRadar = selectedRadarFrame != null
        val badge = when {
            hasRadar && hasUsLightning -> "Live Radar & US Lightning"
            hasRadar && hourOffset < 0 -> "${hourOffset}h · Doppler Radar"
            hasRadar -> "Live Doppler Radar"
            hasUsLightning -> "Open-Meteo Rain & US Lightning"
            hourOffset < 0 -> "${hourOffset}h · Open-Meteo Rain"
            else -> "Open-Meteo · Live Rain"
        }
        val attribution = when {
            hasRadar && hasUsLightning -> "OpenFreeMap · RainViewer · Visual Crossing"
            hasRadar -> "OpenFreeMap · RainViewer · Open-Meteo"
            hasUsLightning -> "OpenFreeMap · Open-Meteo · Visual Crossing"
            else -> "OpenFreeMap · Open-Meteo"
        }
        val stormsLegend = when {
            hasRadar && hasUsLightning -> RAINVIEWER_RADAR_LEGEND_WITH_BOLT
            hasRadar -> RAINVIEWER_RADAR_LEGEND_NO_BOLT
            hasUsLightning -> MODEL_PRECIP_LEGEND_WITH_BOLT
            else -> MODEL_PRECIP_LEGEND_NO_BOLT
        }
        return ResolvedMapTilePlan(
            rasterLayers = layers,
            useSmoothOpenMeteoField = !hasRadar || showStormEnergy,
            openMeteoOverlayOpacity = when {
                !hasRadar -> 0.82f
                showStormEnergy -> 0.34f
                else -> 0f
            },
            stormFieldMode = if (hasRadar) StormFieldMode.CAPE_ONLY else StormFieldMode.PRECIPITATION_AND_CAPE,
            legendStops = stormsLegend,
            sourceBadge = badge,
            attributionLabel = attribution
        )
    }

    private fun resolveSatellitePlan(
        inEumetsat: Boolean,
        inAmericas: Boolean,
        hourOffset: Int,
        satelliteMode: SatelliteMode,
        nowUtc: Instant
    ): ResolvedMapTilePlan {
        if (hourOffset <= 0 && inEumetsat) {
            val timeParam = buildEumetsatPastTimeParam(hourOffset, nowUtc)
            val idSuffix = timeParam ?: "live"
            val wmsLayer = if (satelliteMode == SatelliteMode.GEOCOLOUR) {
                "mtg_fd:rgb_geocolour"
            } else {
                "mtg_fd:ir105_hrfi"
            }
            val modeText = if (satelliteMode == SatelliteMode.GEOCOLOUR) "GeoColour" else "Infrared"
            val timePrefix = if (hourOffset == 0) "Live" else "${hourOffset}h"
            return ResolvedMapTilePlan(
                rasterLayers = listOf(
                    ExternalRasterTileLayerSpec(
                        id = "eumetsat-sat-${satelliteMode.name}-$idSuffix",
                        urlTemplate = buildEumetsatWmsUrl(
                            wmsLayer = wmsLayer,
                            format = "image/jpeg",
                            transparent = false,
                            tileSize = 512,
                            timeParam = timeParam
                        ),
                        maxZoom = 6f,
                        tileSize = 512,
                        opacity = if (satelliteMode == SatelliteMode.GEOCOLOUR) 0.88f else 0.78f,
                        bounds = EUMETSAT_BOUNDS
                    )
                ),
                useSmoothOpenMeteoField = false,
                openMeteoOverlayOpacity = 0f,
                sourceBadge = "Meteosat $modeText · $timePrefix",
                attributionLabel = "OpenFreeMap · EUMETSAT"
            )
        }

        if (hourOffset == 0 && inAmericas) {
            return ResolvedMapTilePlan(
                rasterLayers = listOf(
                    ExternalRasterTileLayerSpec(
                        id = "iem-goes-east-ir",
                        urlTemplate = IEM_GOES_EAST_IR_URL,
                        maxZoom = 7f,
                        tileSize = 256,
                        opacity = 0.78f,
                        bounds = GOES_EAST_BOUNDS
                    )
                ),
                useSmoothOpenMeteoField = false,
                openMeteoOverlayOpacity = 0f,
                sourceBadge = "NOAA GOES-East IR · Live",
                attributionLabel = "OpenFreeMap · NOAA / IEM"
            )
        }

        val badge = when {
            hourOffset > 0 -> "Forecast Clouds · +${hourOffset}h"
            hourOffset < 0 -> "${hourOffset}h · Cloud Model"
            else -> "Global Cloud Model"
        }
        return ResolvedMapTilePlan(
            rasterLayers = emptyList(),
            useSmoothOpenMeteoField = true,
            openMeteoOverlayOpacity = 0.74f,
            sourceBadge = badge,
            attributionLabel = "OpenFreeMap · Open-Meteo"
        )
    }

    internal fun selectRainViewerFrame(
        frames: List<RainViewerRadarFrame>,
        hourOffset: Int,
        @Suppress("UNUSED_PARAMETER") nowUtc: Instant = Instant.now()
    ): RainViewerRadarFrame? {
        if (frames.isEmpty() || hourOffset > 0) return null
        val latest = frames.maxByOrNull { it.timestampEpochSec } ?: return null
        if (hourOffset == 0) return latest
        // Anchor past offsets (-1h/-2h) to the pinned latest radar frame so zooming/panning never shifts frames
        val targetEpochSec = latest.timestampEpochSec + hourOffset * 3600L
        return frames.minByOrNull { abs(it.timestampEpochSec - targetEpochSec) }
    }

    internal fun parseRainViewerFrames(rawJson: String): List<RainViewerRadarFrame> {
        return try {
            val root = JsonParser.parseString(rawJson).asJsonObject
            val radar = root.getAsJsonObject("radar") ?: return emptyList()
            val past = radar.getAsJsonArray("past") ?: return emptyList()
            buildList(past.size()) {
                for (elem in past) {
                    val obj = elem.asJsonObject
                    val time = obj.get("time")?.takeIf { !it.isJsonNull }?.asLong ?: continue
                    val path = obj.get("path")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                    if (path.startsWith("/")) {
                        add(RainViewerRadarFrame(timestampEpochSec = time, path = path))
                    }
                }
            }.sortedBy { it.timestampEpochSec }
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal fun buildEumetsatWmsUrl(
        wmsLayer: String,
        format: String,
        transparent: Boolean,
        tileSize: Int = 512,
        timeParam: String? = null
    ): String {
        val base = "$EUMETSAT_WMS_BASE?service=WMS&version=1.1.1&request=GetMap" +
            "&layers=$wmsLayer&styles=&bbox={bbox-epsg-3857}" +
            "&width=$tileSize&height=$tileSize&srs=EPSG:3857&format=$format" +
            if (transparent) "&transparent=true" else ""
        return if (!timeParam.isNullOrBlank()) "$base&time=$timeParam" else base
    }

    internal fun buildEumetsatPastTimeParam(hourOffset: Int, nowUtc: Instant): String? {
        if (hourOffset >= 0) return null
        // Snap safely to whole hour in the past (guaranteed valid on PT10M/PT15M EUMETSAT layers)
        val pastInstant = nowUtc.truncatedTo(ChronoUnit.HOURS).plus(hourOffset.toLong(), ChronoUnit.HOURS)
        return ISO_UTC_FORMATTER.format(pastInstant)
    }

    @Volatile private var cachedFiordStyleJson: String? = null
    @Volatile private var cachedFiordFetchedAtMs: Long = 0L
    private const val STYLE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L

    @Volatile private var cachedRainViewerFrames: List<RainViewerRadarFrame> = emptyList()
    @Volatile private var cachedRainViewerFetchedAtMs: Long = 0L
    private const val RAINVIEWER_CACHE_TTL_MS = 5L * 60L * 1000L

    private val styleHttpClient: OkHttpClient by lazy {
        ApiClient.okHttpClient.newBuilder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .callTimeout(7, TimeUnit.SECONDS)
            .build()
    }

    suspend fun fetchRainViewerRadarFrames(forceRefresh: Boolean = false): List<RainViewerRadarFrame> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedRainViewerFrames
            if (!forceRefresh && cached.isNotEmpty() && (now - cachedRainViewerFetchedAtMs) in 0..RAINVIEWER_CACHE_TTL_MS) {
                return@withContext cached
            }
            val parsed = try {
                val req = Request.Builder().url(RAINVIEWER_MAPS_API_URL).build()
                styleHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string() ?: return@use emptyList()
                    parseRainViewerFrames(body)
                }
            } catch (_: Exception) {
                emptyList()
            }
            if (parsed.isNotEmpty()) {
                cachedRainViewerFrames = parsed
                cachedRainViewerFetchedAtMs = now
                parsed
            } else {
                cached
            }
        }

    /**
     * Fetches and caches OpenFreeMap's Fiord style JSON over our shared fast OkHttp pool
     * so opening or reopening the map never stalls on MapLibre's internal style URI fetch.
     * Falls back cleanly to our ultra-light inline midnight-blue OpenFreeMap vector style
     * if Fiord is unreachable or returns an unexpected response.
     */
    suspend fun loadFiordOrFallbackStyleJson(forceRefresh: Boolean = false): Pair<String, Boolean> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val cached = cachedFiordStyleJson
            if (!forceRefresh && cached != null && (now - cachedFiordFetchedAtMs) in 0..STYLE_CACHE_TTL_MS) {
                return@withContext cached to true
            }
            val fetched = try {
                val req = Request.Builder().url(OPENFREEMAP_FIORD_STYLE_URL).build()
                styleHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string()?.trim()
                    if (body != null && body.startsWith("{") && body.contains("\"layers\"")) body else null
                }
            } catch (_: Exception) {
                null
            }
            if (fetched != null) {
                cachedFiordStyleJson = fetched
                cachedFiordFetchedAtMs = now
                fetched to true
            } else if (cached != null) {
                cached to true
            } else {
                FALLBACK_DARK_VECTOR_STYLE_JSON to false
            }
        }

    /**
     * Minimal, zero-sprite midnight-blue OpenFreeMap vector style preserving the exact
     * `boundary_state` label anchor so weather overlays stay underneath borders and city names
     * even if the remote Fiord style endpoint or sprite sheet hiccups.
     */
    val FALLBACK_DARK_VECTOR_STYLE_JSON: String = """
        {
          "version": 8,
          "name": "Almanac Midnight Vector",
          "sources": {
            "openmaptiles": {
              "type": "vector",
              "url": "https://tiles.openfreemap.org/planet"
            }
          },
          "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#192638" }
            },
            {
              "id": "water",
              "type": "fill",
              "source": "openmaptiles",
              "source-layer": "water",
              "paint": { "fill-color": "#0F1E31" }
            },
            {
              "id": "waterway",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "waterway",
              "minzoom": 6,
              "paint": { "line-color": "#142B46", "line-width": 1 }
            },
            {
              "id": "road_major",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "transportation",
              "minzoom": 6,
              "filter": ["in", "class", "motorway", "trunk", "primary"],
              "paint": {
                "line-color": "#2A3E57",
                "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.5, 12, 2.0]
              }
            },
            {
              "id": "boundary_state",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "boundary",
              "minzoom": 3,
              "filter": ["all", ["==", "admin_level", 4], ["!=", "maritime", 1]],
              "paint": {
                "line-color": "#587394",
                "line-width": 0.9,
                "line-dasharray": [3, 2]
              }
            },
            {
              "id": "boundary_country",
              "type": "line",
              "source": "openmaptiles",
              "source-layer": "boundary",
              "filter": ["all", ["==", "admin_level", 2], ["!=", "maritime", 1]],
              "paint": {
                "line-color": "#82A3C7",
                "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.8, 8, 1.8]
              }
            },
            {
              "id": "place_town",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "place",
              "minzoom": 6,
              "filter": ["in", "class", "town", "village"],
              "layout": {
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 6, 10, 12, 13]
              },
              "paint": {
                "text-color": "#CBD5E1",
                "text-halo-color": "#0A1422",
                "text-halo-width": 1.3
              }
            },
            {
              "id": "place_city",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "place",
              "minzoom": 3,
              "filter": ["==", "class", "city"],
              "layout": {
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 3, 11, 10, 16]
              },
              "paint": {
                "text-color": "#F8FAFC",
                "text-halo-color": "#0A1422",
                "text-halo-width": 1.6
              }
            },
            {
              "id": "place_country",
              "type": "symbol",
              "source": "openmaptiles",
              "source-layer": "place",
              "maxzoom": 7,
              "filter": ["==", "class", "country"],
              "layout": {
                "text-field": ["coalesce", ["get", "name:en"], ["get", "name"]],
                "text-font": ["Noto Sans Regular"],
                "text-size": ["interpolate", ["linear"], ["zoom"], 2, 10, 6, 14]
              },
              "paint": {
                "text-color": "#A7C0DB",
                "text-halo-color": "#0A1422",
                "text-halo-width": 1.5
              }
            }
          ]
        }
    """.trimIndent()
}
