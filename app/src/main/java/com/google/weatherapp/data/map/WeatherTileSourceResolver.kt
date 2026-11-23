package com.google.weatherapp.data.map

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

data class ExternalRasterTileLayerSpec(
    val id: String,
    val urlTemplate: String,
    val maxZoom: Float,
    val tileSize: Int = 256,
    val opacity: Float = 0.82f,
    val bounds: FloatArray? = null // [west, south, east, north]
)

data class ResolvedMapTilePlan(
    val rasterLayers: List<ExternalRasterTileLayerSpec>,
    val useSmoothOpenMeteoField: Boolean,
    val openMeteoOverlayOpacity: Float,
    val sourceBadge: String,
    val attributionLabel: String
)

/**
 * Routes each weather layer, geographic region, and timeline offset (-2h..+48h) to
 * verified, free, keyless real weather tile feeds (EUMETSAT MTG, NOAA GOES-East, Visual Crossing)
 * or smooth client-side Open-Meteo model fields, returning honest source labels every time.
 */
object WeatherTileSourceResolver {

    const val OPENFREEMAP_FIORD_STYLE_URL = "https://tiles.openfreemap.org/styles/fiord"
    const val FIORD_LABEL_ANCHOR_LAYER_ID = "boundary_state"

    private const val EUMETSAT_WMS_BASE = "https://view.eumetsat.int/geoserver/wms"
    private const val VISUAL_CROSSING_TILE_BASE =
        "https://maps.visualcrossing.com/VisualCrossingWebServices/rest/api/v1/map/tile"
    private const val IEM_GOES_EAST_IR_URL =
        "https://mesonet.agron.iastate.edu/cache/tile.py/1.0.0/goes-east-ir-4km-900913/{z}/{x}/{y}.png"

    private val EUMETSAT_BOUNDS = floatArrayOf(-70f, -70f, 70f, 70f)
    private val GOES_EAST_BOUNDS = floatArrayOf(-140f, -55f, -35f, 65f)
    private val US_LIGHTNING_BOUNDS = floatArrayOf(-130f, 20f, -62f, 54f)

    private val ISO_UTC_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00:00'Z'").withZone(ZoneOffset.UTC)

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
        showStormCells: Boolean = true,
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
                showStormCells = showStormCells,
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
        showStormCells: Boolean,
        nowUtc: Instant
    ): ResolvedMapTilePlan {
        if (hourOffset <= 0 && inEumetsat) {
            val timeParam = buildEumetsatPastTimeParam(hourOffset, nowUtc)
            val layers = mutableListOf<ExternalRasterTileLayerSpec>()
            layers.add(
                ExternalRasterTileLayerSpec(
                    id = "eumetsat-rain-$hourOffset",
                    urlTemplate = buildEumetsatWmsUrl(
                        wmsLayer = "mtg_fd:h40b",
                        format = "image/png",
                        transparent = true,
                        timeParam = timeParam
                    ),
                    maxZoom = 7f,
                    opacity = 0.84f,
                    bounds = EUMETSAT_BOUNDS
                )
            )
            if (showStormCells) {
                layers.add(
                    ExternalRasterTileLayerSpec(
                        id = "eumetsat-rdt-$hourOffset",
                        urlTemplate = buildEumetsatWmsUrl(
                            wmsLayer = "msg_fes:rdt",
                            format = "image/png",
                            transparent = true,
                            timeParam = timeParam
                        ),
                        maxZoom = 7f,
                        opacity = 0.88f,
                        bounds = EUMETSAT_BOUNDS
                    )
                )
            }
            if (showLightning) {
                layers.add(
                    ExternalRasterTileLayerSpec(
                        id = "eumetsat-lightning-$hourOffset",
                        urlTemplate = buildEumetsatWmsUrl(
                            wmsLayer = "mtg_fd:li_afa",
                            format = "image/png",
                            transparent = true,
                            timeParam = timeParam
                        ),
                        maxZoom = 7f,
                        opacity = 0.96f,
                        bounds = EUMETSAT_BOUNDS
                    )
                )
            }
            val timePrefix = if (hourOffset == 0) "Meteosat Live" else "${hourOffset}h · Meteosat"
            val detail = if (showLightning) "Lightning & Rain" else "Satellite Rain"
            return ResolvedMapTilePlan(
                rasterLayers = layers,
                useSmoothOpenMeteoField = true, // subtle CAPE convective instability glow beneath live rain
                openMeteoOverlayOpacity = 0.32f,
                sourceBadge = "$timePrefix · $detail",
                attributionLabel = "OpenFreeMap · EUMETSAT · Open-Meteo"
            )
        }

        // Global Live (outside EUMETSAT) OR Forecast (+1h..+48h worldwide)
        val vcTime = if (hourOffset == 0) {
            "latest"
        } else {
            val target = nowUtc.truncatedTo(ChronoUnit.HOURS).plus(hourOffset.toLong(), ChronoUnit.HOURS)
            ISO_UTC_FORMATTER.format(target)
        }
        val layers = mutableListOf(
            ExternalRasterTileLayerSpec(
                id = "vc-precip-$hourOffset",
                urlTemplate = "$VISUAL_CROSSING_TILE_BASE/precipcomposite/{z}/{x}/{y}.webp?time=$vcTime&unitGroup=metric",
                maxZoom = 8f,
                opacity = 0.82f,
                bounds = null
            )
        )
        val hasUsLightning = hourOffset == 0 && inUs && showLightning
        if (hasUsLightning) {
            layers.add(
                ExternalRasterTileLayerSpec(
                    id = "vc-lightning-us",
                    urlTemplate = "$VISUAL_CROSSING_TILE_BASE/lightning/{z}/{x}/{y}.webp?time=latest&unitGroup=metric",
                    maxZoom = 8f,
                    opacity = 0.95f,
                    bounds = US_LIGHTNING_BOUNDS
                )
            )
        }
        val badge = when {
            hourOffset > 0 -> "Forecast · +${hourOffset}h Rain & CAPE"
            hasUsLightning -> "Live Radar & US Lightning"
            hourOffset < 0 -> "${hourOffset}h · Radar & Composite"
            else -> "Live Radar & Composite"
        }
        return ResolvedMapTilePlan(
            rasterLayers = layers,
            useSmoothOpenMeteoField = true,
            openMeteoOverlayOpacity = 0.36f,
            sourceBadge = badge,
            attributionLabel = "OpenFreeMap · Visual Crossing · Open-Meteo"
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
                        id = "eumetsat-sat-${satelliteMode.name}-$hourOffset",
                        urlTemplate = buildEumetsatWmsUrl(
                            wmsLayer = wmsLayer,
                            format = "image/jpeg",
                            transparent = false,
                            timeParam = timeParam
                        ),
                        maxZoom = 7f,
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

        val badge = if (hourOffset > 0) {
            "Forecast Clouds · +${hourOffset}h"
        } else {
            "Global Cloud Model"
        }
        return ResolvedMapTilePlan(
            rasterLayers = emptyList(),
            useSmoothOpenMeteoField = true,
            openMeteoOverlayOpacity = 0.74f,
            sourceBadge = badge,
            attributionLabel = "OpenFreeMap · Open-Meteo"
        )
    }

    internal fun buildEumetsatWmsUrl(
        wmsLayer: String,
        format: String,
        transparent: Boolean,
        timeParam: String? = null
    ): String {
        val base = "$EUMETSAT_WMS_BASE?service=WMS&version=1.1.1&request=GetMap" +
            "&layers=$wmsLayer&styles=&bbox={bbox-epsg-3857}" +
            "&width=256&height=256&srs=EPSG:3857&format=$format" +
            if (transparent) "&transparent=true" else ""
        return if (!timeParam.isNullOrBlank()) "$base&time=$timeParam" else base
    }

    internal fun buildEumetsatPastTimeParam(hourOffset: Int, nowUtc: Instant): String? {
        if (hourOffset >= 0) return null
        // Snap safely to whole hour in the past (guaranteed valid on PT5M/PT10M/PT15M EUMETSAT layers)
        val pastInstant = nowUtc.truncatedTo(ChronoUnit.HOURS).plus(hourOffset.toLong(), ChronoUnit.HOURS)
        return ISO_UTC_FORMATTER.format(pastInstant)
    }
}
