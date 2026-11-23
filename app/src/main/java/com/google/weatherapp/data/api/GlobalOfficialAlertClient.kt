package com.google.weatherapp.data.api

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.weatherapp.data.model.OfficialWeatherAlert
import com.google.weatherapp.data.model.SeverityRiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Direct free official government weather alert backups for regions with open real-time APIs:
 * - United States & territories: NOAA National Weather Service (api.weather.gov)
 * - Canada: Environment and Climate Change Canada (api.weather.gc.ca)
 * Supplements Google Public Alerts and SAWS so severe warnings stay reliable worldwide.
 */
class GlobalOfficialAlertClient(
    private val httpClient: OkHttpClient = ApiClient.okHttpClient
) {

    suspend fun fetchAlertsForLocation(
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId? = null,
        now: Instant = Instant.now()
    ): List<OfficialWeatherAlert> = withContext(Dispatchers.IO) {
        val alerts = mutableListOf<OfficialWeatherAlert>()
        if (isWithinUsJurisdiction(latitude, longitude)) {
            fetchUsNwsAlerts(latitude, longitude, zoneId, now)?.let { alerts.addAll(it) }
        }
        if (isWithinCanada(latitude, longitude)) {
            fetchCanadaEcccAlerts(latitude, longitude, zoneId, now)?.let { alerts.addAll(it) }
        }
        alerts
            .distinctBy { "${it.title.lowercase(Locale.US)}|${it.areaName.lowercase(Locale.US)}" }
            .sortedByDescending { it.severity.ordinal }
    }

    private fun fetchUsNwsAlerts(
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId?,
        now: Instant
    ): List<OfficialWeatherAlert>? {
        val url = String.format(
            Locale.US,
            "https://api.weather.gov/alerts/active?point=%.4f,%.4f",
            latitude,
            longitude
        )
        val body = fetchJson(url, accept = "application/geo+json") ?: return null
        return parseNwsGeoJson(body, zoneId ?: ZoneId.systemDefault(), now)
    }

    private fun fetchCanadaEcccAlerts(
        latitude: Double,
        longitude: Double,
        zoneId: ZoneId?,
        now: Instant
    ): List<OfficialWeatherAlert>? {
        val delta = 0.25
        val minLon = (longitude - delta).coerceAtLeast(-180.0)
        val minLat = (latitude - delta).coerceAtLeast(-90.0)
        val maxLon = (longitude + delta).coerceAtMost(180.0)
        val maxLat = (latitude + delta).coerceAtMost(90.0)
        val url = String.format(
            Locale.US,
            "https://api.weather.gc.ca/collections/weather-alerts/items?f=json&bbox=%.4f,%.4f,%.4f,%.4f&limit=10",
            minLon, minLat, maxLon, maxLat
        )
        val body = fetchJson(url, accept = "application/json") ?: return null
        return parseEcccGeoJson(body, latitude, longitude, zoneId ?: ZoneId.systemDefault(), now)
    }

    private fun fetchJson(url: String, accept: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", "AlmanacWeatherApp/1.0 (Android)")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        internal fun isWithinUsJurisdiction(latitude: Double, longitude: Double): Boolean {
            val conus = latitude in 24.3..49.5 && longitude in -125.0..-66.8
            val alaska = latitude in 51.0..71.6 && longitude in -179.9..-129.9
            val hawaii = latitude in 18.8..22.4 && longitude in -160.5..-154.7
            val puertoRicoUsvi = latitude in 17.6..18.6 && longitude in -67.5..-64.5
            val guamCnmi = latitude in 13.1..20.6 && longitude in 144.5..146.2
            return conus || alaska || hawaii || puertoRicoUsvi || guamCnmi
        }

        internal fun isWithinCanada(latitude: Double, longitude: Double): Boolean {
            return latitude in 41.6..83.2 && longitude in -141.0..-52.5
        }

        internal fun parseNwsGeoJson(
            rawJson: String,
            zoneId: ZoneId,
            now: Instant = Instant.now()
        ): List<OfficialWeatherAlert> {
            return try {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val features = root.getAsJsonArray("features") ?: return emptyList()
                val alerts = mutableListOf<OfficialWeatherAlert>()

                for (elem in features) {
                    val props = elem.asJsonObject.getAsJsonObject("properties") ?: continue
                    val status = props.stringOrNull("status")
                    if (status != null && !status.equals("Actual", ignoreCase = true)) continue
                    val msgType = props.stringOrNull("messageType")
                    if (msgType.equals("Cancel", ignoreCase = true)) continue

                    val expiresRaw = props.stringOrNull("expires") ?: props.stringOrNull("ends")
                    val expiresInstant = expiresRaw?.let { parseIsoInstant(it) }
                    if (expiresInstant != null && expiresInstant.isBefore(now)) continue

                    val event = props.stringOrNull("event").orEmpty()
                    val title = event.ifBlank { props.stringOrNull("headline").orEmpty() }.ifBlank { "Weather Alert" }
                    val severityRaw = props.stringOrNull("severity").orEmpty()
                    val mappedSeverity = mapSeverity(severityRaw, event)
                    val sender = props.stringOrNull("senderName")?.takeIf { it.isNotBlank() }
                        ?: "NOAA National Weather Service"
                    val areaDesc = props.stringOrNull("areaDesc")
                        ?.split(";")
                        ?.map { it.trim() }
                        ?.filter { it.isNotBlank() }
                        ?.take(2)
                        ?.joinToString(", ")
                        .orEmpty()
                    val description = cleanText(props.stringOrNull("description"))
                    val instruction = cleanText(props.stringOrNull("instruction"))
                    val expiresLabel = expiresInstant?.let {
                        DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                            .withZone(zoneId)
                            .format(it)
                    }.orEmpty()

                    alerts.add(
                        OfficialWeatherAlert(
                            title = title,
                            severity = mappedSeverity,
                            eventType = event,
                            source = sender,
                            areaName = areaDesc,
                            description = description,
                            instruction = instruction,
                            expiresLabel = expiresLabel,
                            severityLabel = severityRaw.ifBlank {
                                mappedSeverity.name.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                            }
                        )
                    )
                }
                alerts
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun parseEcccGeoJson(
            rawJson: String,
            latitude: Double,
            longitude: Double,
            zoneId: ZoneId,
            now: Instant = Instant.now()
        ): List<OfficialWeatherAlert> {
            return try {
                val root = JsonParser.parseString(rawJson).asJsonObject
                val features = root.getAsJsonArray("features") ?: return emptyList()
                val alerts = mutableListOf<OfficialWeatherAlert>()

                for (elem in features) {
                    val obj = elem.asJsonObject
                    val geom = obj.getAsJsonObject("geometry")
                    if (geom != null && !isPointInGeoJsonGeometry(latitude, longitude, geom)) {
                        continue
                    }
                    val props = obj.getAsJsonObject("properties") ?: continue
                    val expiresRaw = props.stringOrNull("expiry_datetime") ?: props.stringOrNull("expires")
                    val expiresInstant = expiresRaw?.let { parseIsoInstant(it) }
                    if (expiresInstant != null && expiresInstant.isBefore(now)) continue

                    val alertType = props.stringOrNull("alert_type").orEmpty()
                    val alertName = props.stringOrNull("alert_name_en")
                        ?: props.stringOrNull("event_en")
                        ?: props.stringOrNull("headline_en")
                        ?: continue
                    if (alertName.isBlank()) continue

                    val title = alertName.trim().replaceFirstChar { it.uppercase() }
                    val mappedSeverity = when (alertType.trim().lowercase(Locale.US)) {
                        "warning" -> SeverityRiskLevel.SEVERE
                        "watch" -> SeverityRiskLevel.HIGH
                        "advisory" -> SeverityRiskLevel.MODERATE
                        else -> mapSeverity(props.stringOrNull("severity").orEmpty(), title)
                    }
                    val areaName = props.stringOrNull("area_name_en")
                        ?: props.stringOrNull("zone_name_en")
                        ?: ""
                    val description = cleanText(props.stringOrNull("alert_text_en") ?: props.stringOrNull("description_en"))
                    val expiresLabel = expiresInstant?.let {
                        DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                            .withZone(zoneId)
                            .format(it)
                    }.orEmpty()

                    alerts.add(
                        OfficialWeatherAlert(
                            title = title,
                            severity = mappedSeverity,
                            eventType = alertType.ifBlank { title },
                            source = "Environment Canada",
                            areaName = areaName.trim(),
                            description = description,
                            instruction = "",
                            expiresLabel = expiresLabel,
                            severityLabel = alertType.trim().replaceFirstChar { it.uppercase() }
                        )
                    )
                }
                alerts
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun isPointInGeoJsonGeometry(latitude: Double, longitude: Double, geometry: JsonObject): Boolean {
            val type = geometry.stringOrNull("type") ?: return false
            val coords = geometry.getAsJsonArray("coordinates") ?: return false
            return when (type) {
                "Polygon" -> isPointInGeoJsonPolygon(latitude, longitude, coords)
                "MultiPolygon" -> (0 until coords.size()).any { idx ->
                    coords[idx]?.takeIf { it.isJsonArray }?.asJsonArray?.let { poly ->
                        isPointInGeoJsonPolygon(latitude, longitude, poly)
                    } == true
                }
                else -> false
            }
        }

        private fun isPointInGeoJsonPolygon(latitude: Double, longitude: Double, rings: JsonArray): Boolean {
            if (rings.size() == 0) return false
            val outerRing = rings[0]?.takeIf { it.isJsonArray }?.asJsonArray ?: return false
            val outerPoints = parseGeoJsonRing(outerRing)
            if (!SawsAlertClient.isPointInPolygon(latitude, longitude, outerPoints)) return false
            // Exclude inner holes
            for (i in 1 until rings.size()) {
                val holeRing = rings[i]?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (SawsAlertClient.isPointInPolygon(latitude, longitude, parseGeoJsonRing(holeRing))) {
                    return false
                }
            }
            return true
        }

        private fun parseGeoJsonRing(ring: JsonArray): List<Pair<Double, Double>> {
            val pts = ArrayList<Pair<Double, Double>>(ring.size())
            for (elem in ring) {
                val pair = elem?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
                if (pair.size() < 2) continue
                val lon = try { pair[0].asDouble } catch (_: Exception) { continue }
                val lat = try { pair[1].asDouble } catch (_: Exception) { continue }
                pts.add(lat to lon)
            }
            return pts
        }

        private fun mapSeverity(rawSeverity: String, event: String): SeverityRiskLevel {
            return when (rawSeverity.trim().uppercase(Locale.US)) {
                "EXTREME", "SEVERE" -> SeverityRiskLevel.SEVERE
                "MODERATE" -> SeverityRiskLevel.HIGH
                "MINOR" -> SeverityRiskLevel.MODERATE
                else -> when {
                    event.contains("Warning", ignoreCase = true) -> SeverityRiskLevel.SEVERE
                    event.contains("Watch", ignoreCase = true) -> SeverityRiskLevel.HIGH
                    event.contains("Advisory", ignoreCase = true) -> SeverityRiskLevel.MODERATE
                    else -> SeverityRiskLevel.LOW
                }
            }
        }

        private fun parseIsoInstant(raw: String): Instant? = try {
            OffsetDateTime.parse(raw.trim()).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(raw.trim())
            } catch (_: Exception) {
                null
            }
        }

        private fun cleanText(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.replace(Regex("\\s+"), " ").trim().take(500)
        }

        private fun JsonObject.stringOrNull(key: String): String? {
            val elem = get(key) ?: return null
            if (elem.isJsonNull) return null
            return try { elem.asString } catch (_: Exception) { null }
        }
    }
}
