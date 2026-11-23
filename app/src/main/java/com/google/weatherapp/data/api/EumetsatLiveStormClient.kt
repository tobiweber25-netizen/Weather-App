package com.google.weatherapp.data.api

import android.graphics.BitmapFactory
import com.google.gson.JsonParser
import com.google.weatherapp.data.model.LiveSatelliteStormObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Queries EUMETSAT's free public Meteosat Third Generation (MTG) & MSG geostationary
 * space-satellite WMS feeds for real-time lightning, rapidly developing thunderstorm cells,
 * and instantaneous satellite + microwave precipitation across Africa, Europe, and the Middle East.
 *
 * Returns null outside the valid geostationary disk (-65..+65 deg) or when unreachable;
 * never fabricates fallback data.
 */
class EumetsatLiveStormClient(
    baseClient: OkHttpClient = ApiClient.okHttpClient
) {
    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    suspend fun fetchLiveObservation(
        latitude: Double,
        longitude: Double
    ): LiveSatelliteStormObservation? = withContext(Dispatchers.IO) {
        if (!isWithinEumetsatCoverage(latitude, longitude)) return@withContext null

        val cacheKey = String.format(Locale.US, "%.1f,%.1f", latitude, longitude)
        val nowMs = System.currentTimeMillis()
        synchronized(CACHE_LOCK) {
            val cached = spatialCache[cacheKey]
            if (cached != null && (nowMs - cached.fetchedAtMs) in 0..OBSERVATION_CACHE_TTL_MS) {
                return@withContext cached.observation
            }
        }

        val fresh = coroutineScope {
            val lightningDeferred = async { fetchLightningProximity(latitude, longitude) }
            val rdtDeferred = async { fetchDevelopingStormCell(latitude, longitude) }
            val rainDeferred = async { fetchObservedRainRate(latitude, longitude) }

            val lightning = lightningDeferred.await()
            val rdt = rdtDeferred.await()
            val rain = rainDeferred.await()

            if (lightning == null && rdt == null && rain == null) {
                return@coroutineScope null
            }

            val observedRain = rain?.rainRateMmH?.takeIf { it > 0.0 }
            LiveSatelliteStormObservation(
                lightningNearby = lightning?.detected == true,
                nearestLightningKm = lightning?.nearestKm,
                lightningDirection = lightning?.compassDirection,
                activeStormPhase = rdt?.phase,
                stormRainRateMmH = rdt?.rainRateMmH,
                stormMoveSpeedKmH = rdt?.moveSpeedKmH,
                stormMoveDirection = rdt?.moveDirection,
                observedRainRateMmH = observedRain,
                observationTimeIso = rdt?.timeIso ?: rain?.timeIso,
                sourceLabel = "Meteosat Live"
            )
        }

        if (fresh != null) {
            synchronized(CACHE_LOCK) {
                if (spatialCache.size > 24) spatialCache.clear()
                spatialCache[cacheKey] = CachedObservation(nowMs, fresh)
            }
        }
        fresh
    }

    private fun fetchLightningProximity(latitude: Double, longitude: Double): LightningProbeResult? {
        val bbox = buildRadiusBboxEpsg3857(latitude, longitude, LIGHTNING_SCAN_RADIUS_KM)
        val url = "$EUMETSAT_WMS_BASE?service=WMS&version=1.1.1&request=GetMap" +
            "&layers=mtg_fd:li_afa&styles=&bbox=$bbox" +
            "&width=$LIGHTNING_GRID_SIZE&height=$LIGHTNING_GRID_SIZE" +
            "&srs=EPSG:3857&format=image/png&transparent=true"
        val bytes = fetchBytes(url) ?: return null
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            analyzeLightningPixels(pixels, w, h, LIGHTNING_SCAN_RADIUS_KM)
        } finally {
            bitmap.recycle()
        }
    }

    private fun fetchDevelopingStormCell(latitude: Double, longitude: Double): RdtStormCell? {
        val bbox = buildRadiusBboxEpsg3857(latitude, longitude, RDT_QUERY_RADIUS_KM)
        val url = "$EUMETSAT_WMS_BASE?service=WMS&version=1.1.1&request=GetFeatureInfo" +
            "&layers=msg_fes:rdt&query_layers=msg_fes:rdt&styles=&bbox=$bbox" +
            "&width=128&height=128&srs=EPSG:3857&info_format=application/json" +
            "&x=64&y=64&feature_count=3&buffer=18"
        val body = fetchText(url) ?: return null
        return parseRdtFeatureInfo(body)
    }

    private fun fetchObservedRainRate(latitude: Double, longitude: Double): SatelliteRainReading? {
        val bbox = buildRadiusBboxEpsg3857(latitude, longitude, RAIN_QUERY_RADIUS_KM)
        val url = "$EUMETSAT_WMS_BASE?service=WMS&version=1.1.1&request=GetFeatureInfo" +
            "&layers=mtg_fd:h40b&query_layers=mtg_fd:h40b&styles=&bbox=$bbox" +
            "&width=64&height=64&srs=EPSG:3857&info_format=application/json" +
            "&x=32&y=32&feature_count=1"
        val body = fetchText(url) ?: return null
        return parseH40bRainFeatureInfo(body)
    }

    private fun fetchBytes(url: String): ByteArray? = try {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.bytes()
        }
    } catch (_: Exception) {
        null
    }

    private fun fetchText(url: String): String? = try {
        val req = Request.Builder().url(url).build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    internal data class LightningProbeResult(
        val detected: Boolean,
        val nearestKm: Int?,
        val compassDirection: String?
    )

    internal data class RdtStormCell(
        val phase: String,
        val rainRateMmH: Double?,
        val moveSpeedKmH: Int?,
        val moveDirection: String?,
        val timeIso: String?
    )

    internal data class SatelliteRainReading(
        val rainRateMmH: Double,
        val qualityIndex: Int?,
        val timeIso: String?
    )

    private data class CachedObservation(
        val fetchedAtMs: Long,
        val observation: LiveSatelliteStormObservation
    )

    companion object {
        const val EUMETSAT_WMS_BASE = "https://view.eumetsat.int/geoserver/wms"
        private const val EARTH_RADIUS_METERS = 6378137.0
        private const val LIGHTNING_GRID_SIZE = 32
        private const val LIGHTNING_SCAN_RADIUS_KM = 40.0
        private const val RDT_QUERY_RADIUS_KM = 60.0
        private const val RAIN_QUERY_RADIUS_KM = 15.0
        private const val OBSERVATION_CACHE_TTL_MS = 5L * 60L * 1000L
        private val CACHE_LOCK = Any()
        private val spatialCache = HashMap<String, CachedObservation>()

        /**
         * Valid reliable disk for EUMETSAT MTG-I (0 deg geostationary slot):
         * covers all of Africa (including South Africa), Europe, Middle East, and nearby oceans.
         */
        fun isWithinEumetsatCoverage(latitude: Double, longitude: Double): Boolean {
            if (latitude == 0.0 && longitude == 0.0) return false
            return latitude in -65.0..65.0 && longitude in -65.0..65.0
        }

        internal fun toWebMercator(latitude: Double, longitude: Double): Pair<Double, Double> {
            val clampedLat = latitude.coerceIn(-85.0, 85.0)
            val x = longitude * (PI / 180.0) * EARTH_RADIUS_METERS
            val latRad = clampedLat * (PI / 180.0)
            val y = ln(tan(PI / 4.0 + latRad / 2.0)) * EARTH_RADIUS_METERS
            return x to y
        }

        internal fun buildRadiusBboxEpsg3857(
            latitude: Double,
            longitude: Double,
            radiusKm: Double
        ): String {
            val (cx, cy) = toWebMercator(latitude, longitude)
            val cosLat = cos(latitude * PI / 180.0).coerceAtLeast(0.25)
            // Adjust Web Mercator meter span for latitude scale factor (1 / cos(lat))
            val halfSpanMeters = (radiusKm * 1000.0) / cosLat
            return String.format(
                Locale.US,
                "%.2f,%.2f,%.2f,%.2f",
                cx - halfSpanMeters,
                cy - halfSpanMeters,
                cx + halfSpanMeters,
                cy + halfSpanMeters
            )
        }

        /**
         * Scans a rendered transparent ARGB pixel grid of mtg_fd:li_afa centered on the location
         * and locates the nearest real lightning flash pixel (distance in km + compass bearing).
         */
        internal fun analyzeLightningPixels(
            pixels: IntArray,
            width: Int,
            height: Int,
            radiusKm: Double
        ): LightningProbeResult {
            if (width <= 0 || height <= 0 || pixels.size < width * height) {
                return LightningProbeResult(false, null, null)
            }
            val centerX = (width - 1) / 2.0
            val centerY = (height - 1) / 2.0
            val kmPerPixelX = (radiusKm * 2.0) / width.toDouble()
            val kmPerPixelY = (radiusKm * 2.0) / height.toDouble()

            var bestDistKm = Double.MAX_VALUE
            var bestDxKm = 0.0
            var bestDyKm = 0.0
            var found = false

            for (py in 0 until height) {
                val rowOffset = py * width
                for (px in 0 until width) {
                    val argb = pixels[rowOffset + px]
                    val alpha = (argb ushr 24) and 0xFF
                    if (alpha < 40) continue
                    val red = (argb ushr 16) and 0xFF
                    val green = (argb ushr 8) and 0xFF
                    // MTG li_afa renders flashes in pale yellow -> orange -> deep crimson (high R, warm hue)
                    if (red < 120 && green < 120) continue

                    val dxKm = (px - centerX) * kmPerPixelX
                    val dyKm = (centerY - py) * kmPerPixelY // north is positive
                    val distKm = hypot(dxKm, dyKm)
                    if (distKm <= radiusKm && distKm < bestDistKm) {
                        bestDistKm = distKm
                        bestDxKm = dxKm
                        bestDyKm = dyKm
                        found = true
                    }
                }
            }

            if (!found) {
                return LightningProbeResult(false, null, null)
            }

            val nearestKm = bestDistKm.roundToInt().coerceAtLeast(1)
            val compass = if (bestDistKm < 4.0) {
                null // directly overhead
            } else {
                val bearingDeg = (Math.toDegrees(atan2(bestDxKm, bestDyKm)) + 360.0) % 360.0
                degreesToCompass(bearingDeg)
            }
            return LightningProbeResult(
                detected = true,
                nearestKm = nearestKm,
                compassDirection = compass
            )
        }

        internal fun parseRdtFeatureInfo(rawJson: String): RdtStormCell? {
            return try {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val features = root.getAsJsonArray("features") ?: return null
                if (features.size() == 0) return null

                var bestCell: RdtStormCell? = null
                var bestRank = -1

                for (elem in features) {
                    val props = elem.asJsonObject.getAsJsonObject("properties") ?: continue
                    val rawPhase = props.get("PhaseLife")?.takeIf { !it.isJsonNull }?.asString?.trim().orEmpty()
                    val phase = rawPhase.ifBlank { "Active" }
                    val rank = when (phase.lowercase(Locale.US)) {
                        "mature", "severe", "overshooting" -> 3
                        "growing", "triggering", "developing" -> 2
                        else -> 1
                    }
                    val rainRate = props.get("CRainRate")?.takeIf { !it.isJsonNull }?.asDouble
                        ?.takeIf { it > 0.0 && it < 500.0 }
                    val moveSpeed = props.get("MvtSpeed")?.takeIf { !it.isJsonNull }?.asDouble
                        ?.takeIf { it in 1.0..250.0 }?.roundToInt()
                    val moveDirDeg = props.get("MvtDirecti")?.takeIf { !it.isJsonNull }?.asDouble
                        ?.takeIf { it in 0.0..360.0 }
                    val moveDir = moveDirDeg?.let { degreesToCompass(it) }
                    val timeIso = props.get("time")?.takeIf { !it.isJsonNull }?.asString?.trim()

                    if (rank > bestRank) {
                        bestRank = rank
                        bestCell = RdtStormCell(
                            phase = phase,
                            rainRateMmH = rainRate,
                            moveSpeedKmH = moveSpeed,
                            moveDirection = moveDir,
                            timeIso = timeIso
                        )
                    }
                }
                bestCell
            } catch (_: Exception) {
                null
            }
        }

        internal fun parseH40bRainFeatureInfo(rawJson: String): SatelliteRainReading? {
            return try {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val timeIso = root.get("timeStamp")?.takeIf { !it.isJsonNull }?.asString
                val features = root.getAsJsonArray("features") ?: return null
                if (features.size() == 0) return null
                val props = features[0].asJsonObject.getAsJsonObject("properties") ?: return null
                val rate = props.get("Instantaneous_rain_rate")?.takeIf { !it.isJsonNull }?.asDouble
                    ?: return null
                if (rate < 0.0 || rate > 400.0) return null
                val quality = props.get("Quality_index")?.takeIf { !it.isJsonNull }?.asInt
                SatelliteRainReading(
                    rainRateMmH = rate,
                    qualityIndex = quality,
                    timeIso = timeIso
                )
            } catch (_: Exception) {
                null
            }
        }

        internal fun degreesToCompass(degrees: Double): String {
            val normalized = ((degrees % 360.0) + 360.0) % 360.0
            val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
            val index = ((normalized + 22.5) / 45.0).toInt() % 8
            return dirs[index]
        }
    }
}
