package com.google.weatherapp.data.api

import com.google.weatherapp.data.model.OfficialWeatherAlert
import com.google.weatherapp.data.model.SeverityRiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import org.w3c.dom.Element

/**
 * Fetches official South African Weather Service (SAWS) CAP 1.2 severe weather warnings
 * and matches them against the user's exact coordinates using polygon ray-casting.
 */
class SawsAlertClient(
    baseClient: OkHttpClient = ApiClient.okHttpClient
) {
    private val httpClient: OkHttpClient = baseClient.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .callTimeout(3500, TimeUnit.MILLISECONDS)
        .build()

    private data class CachedCapAlert(
        val fetchedAtMs: Long,
        val xml: String
    )

    suspend fun fetchAlertsForLocation(
        latitude: Double,
        longitude: Double,
        now: Instant = Instant.now()
    ): List<OfficialWeatherAlert> = withContext(Dispatchers.IO) {
        if (!isWithinSouthAfrica(latitude, longitude)) return@withContext emptyList()

        val rssXml = fetchTextCachedRss(now.toEpochMilli()) ?: return@withContext emptyList()
        val recentLinks = parseSawsRssRecentLinks(rssXml, now)
        if (recentLinks.isEmpty()) return@withContext emptyList()

        val capXmlList = coroutineScope {
            recentLinks.take(MAX_RECENT_CAPS_TO_FETCH).map { link ->
                async { fetchTextCachedCap(link, now.toEpochMilli()) }
            }.awaitAll().filterNotNull()
        }

        val zone = ZoneId.of("Africa/Johannesburg")
        capXmlList.flatMap { xml ->
            parseCapAlertXml(xml, latitude, longitude, now, zone)
        }.distinctBy { "${it.title}|${it.areaName}|${it.expiresLabel}" }
            .sortedByDescending { it.severity.ordinal }
    }

    private fun fetchTextCachedRss(nowMs: Long): String? {
        val existing = cachedRssXml
        if (existing != null && (nowMs - cachedRssAtMs) in 0..CACHE_TTL_MS) {
            return existing
        }
        val fetched = fetchUrl(SAWS_RSS_URL) ?: return existing
        cachedRssXml = fetched
        cachedRssAtMs = nowMs
        return fetched
    }

    private fun fetchTextCachedCap(url: String, nowMs: Long): String? {
        synchronized(capXmlCache) {
            val cached = capXmlCache[url]
            if (cached != null && (nowMs - cached.fetchedAtMs) in 0..CACHE_TTL_MS) {
                return cached.xml
            }
        }
        val fetched = fetchUrl(url) ?: return null
        synchronized(capXmlCache) {
            if (capXmlCache.size > 32) capXmlCache.clear()
            capXmlCache[url] = CachedCapAlert(nowMs, fetched)
        }
        return fetched
    }

    private fun fetchUrl(url: String): String? = try {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AlmanacWeatherApp/1.0")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        private const val SAWS_RSS_URL = "https://caps.weathersa.co.za/Home/RssFeed"
        private const val CACHE_TTL_MS = 15 * 60 * 1000L
        private const val RECENT_RSS_WINDOW_HOURS = 36L
        private const val MAX_RECENT_CAPS_TO_FETCH = 8

        @Volatile private var cachedRssXml: String? = null
        @Volatile private var cachedRssAtMs: Long = 0L
        private val capXmlCache = mutableMapOf<String, CachedCapAlert>()

        internal fun isWithinSouthAfrica(latitude: Double, longitude: Double): Boolean {
            return latitude in -35.5..-21.5 && longitude in 16.0..33.5
        }

        internal fun parseSawsRssRecentLinks(rssXml: String, now: Instant = Instant.now()): List<String> {
            return try {
                val doc = parseXmlDocument(rssXml) ?: return emptyList()
                val items = doc.getElementsByTagName("item")
                val cutoff = now.minusSeconds(RECENT_RSS_WINDOW_HOURS * 3600L)
                val links = LinkedHashSet<String>()

                for (i in 0 until items.length) {
                    val elem = items.item(i) as? Element ?: continue
                    val pubDateRaw = childText(elem, "pubDate")
                    if (!pubDateRaw.isNullOrBlank()) {
                        val parsedPub = parseRssDate(pubDateRaw)
                        if (parsedPub != null && parsedPub.isBefore(cutoff)) {
                            continue
                        }
                    }
                    val rawLink = childText(elem, "link")?.trim() ?: continue
                    if (rawLink.isBlank()) continue
                    // SAWS RSS lists http:// links but port 80 is blocked; upgrade to https://
                    val httpsLink = if (rawLink.startsWith("http://", ignoreCase = true)) {
                        "https://" + rawLink.substring(7)
                    } else {
                        rawLink
                    }
                    links.add(httpsLink)
                    if (links.size >= MAX_RECENT_CAPS_TO_FETCH) break
                }
                links.toList()
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun parseCapAlertXml(
            capXml: String,
            latitude: Double,
            longitude: Double,
            now: Instant = Instant.now(),
            zoneId: ZoneId = ZoneId.of("Africa/Johannesburg")
        ): List<OfficialWeatherAlert> {
            return try {
                val doc = parseXmlDocument(capXml) ?: return emptyList()
                val status = firstTagText(doc.documentElement, "status")
                if (status != null && !status.equals("Actual", ignoreCase = true)) return emptyList()
                val msgType = firstTagText(doc.documentElement, "msgType")
                if (msgType.equals("Cancel", ignoreCase = true)) return emptyList()

                val infoNodes = doc.getElementsByTagName("info")
                val matched = mutableListOf<OfficialWeatherAlert>()

                for (i in 0 until infoNodes.length) {
                    val info = infoNodes.item(i) as? Element ?: continue
                    val expiresRaw = childText(info, "expires")
                    val expiresInstant = expiresRaw?.let { parseIsoInstant(it) }
                    if (expiresInstant != null && expiresInstant.isBefore(now)) {
                        continue
                    }

                    val matchedAreas = mutableListOf<String>()
                    val areaNodes = info.getElementsByTagName("area")
                    for (a in 0 until areaNodes.length) {
                        val area = areaNodes.item(a) as? Element ?: continue
                        val polygons = area.getElementsByTagName("polygon")
                        var hit = false
                        for (p in 0 until polygons.length) {
                            val polyText = polygons.item(p)?.textContent ?: continue
                            val points = parsePolygonPoints(polyText)
                            if (points.size >= 3 && isPointInPolygon(latitude, longitude, points)) {
                                hit = true
                                break
                            }
                        }
                        if (hit) {
                            val desc = childText(area, "areaDesc")?.trim().orEmpty()
                            if (desc.isNotBlank()) matchedAreas.add(desc) else matchedAreas.add("Your area")
                        }
                    }

                    if (matchedAreas.isEmpty()) continue

                    val event = childText(info, "event")?.trim().orEmpty()
                    val headline = childText(info, "headline")?.trim().orEmpty().ifBlank { event }.ifBlank { "Weather Warning" }
                    val description = cleanMultilineText(childText(info, "description"))
                    val instruction = cleanMultilineText(childText(info, "instruction"))
                    val severityRaw = childText(info, "severity")?.trim().orEmpty()
                    val alertTypeParam = extractParameter(info, "alertType")
                    val levelNum = Regex("Level\\s*(\\d+)", RegexOption.IGNORE_CASE)
                        .find(alertTypeParam.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val mappedSeverity = mapCapSeverity(severityRaw, levelNum)
                    val expiresLabel = expiresInstant?.let {
                        DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                            .withZone(zoneId)
                            .format(it)
                    }.orEmpty()

                    val severityLabel = alertTypeParam?.takeIf { it.isNotBlank() }
                        ?: severityRaw.takeIf { it.isNotBlank() }
                        ?: mappedSeverity.name.lowercase().replaceFirstChar { it.uppercase() }

                    matched.add(
                        OfficialWeatherAlert(
                            title = headline,
                            severity = mappedSeverity,
                            eventType = event,
                            source = "SAWS Official",
                            areaName = matchedAreas.distinct().take(2).joinToString(", "),
                            description = description,
                            instruction = instruction,
                            expiresLabel = expiresLabel,
                            severityLabel = severityLabel,
                            expiresEpochMs = expiresInstant?.toEpochMilli()
                        )
                    )
                }
                matched
            } catch (_: Exception) {
                emptyList()
            }
        }

        internal fun mapCapSeverity(capSeverity: String, sawsWarningLevel: Int? = null): SeverityRiskLevel {
            if (sawsWarningLevel != null) {
                return when {
                    sawsWarningLevel >= 6 -> SeverityRiskLevel.SEVERE
                    sawsWarningLevel >= 4 -> SeverityRiskLevel.HIGH
                    sawsWarningLevel >= 2 -> SeverityRiskLevel.MODERATE
                    else -> SeverityRiskLevel.LOW
                }
            }
            return when (capSeverity.trim().uppercase(Locale.US)) {
                "EXTREME", "SEVERE" -> SeverityRiskLevel.SEVERE
                "MODERATE" -> SeverityRiskLevel.HIGH
                "MINOR" -> SeverityRiskLevel.MODERATE
                else -> SeverityRiskLevel.LOW
            }
        }

        internal fun parsePolygonPoints(polygonText: String): List<Pair<Double, Double>> {
            return polygonText.trim()
                .split(Regex("\\s+"))
                .mapNotNull { token ->
                    val parts = token.split(",")
                    if (parts.size != 2) return@mapNotNull null
                    val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                    val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
                    lat to lon
                }
        }

        /** Standard ray-casting point-in-polygon check on (lat, lon) pairs. */
        internal fun isPointInPolygon(
            lat: Double,
            lon: Double,
            polygon: List<Pair<Double, Double>>
        ): Boolean {
            if (polygon.size < 3) return false
            var inside = false
            var j = polygon.lastIndex
            for (i in polygon.indices) {
                val yi = polygon[i].first
                val xi = polygon[i].second
                val yj = polygon[j].first
                val xj = polygon[j].second
                val intersects = ((yi > lat) != (yj > lat)) &&
                    (lon < (xj - xi) * (lat - yi) / ((yj - yi).takeIf { it != 0.0 } ?: 1e-12) + xi)
                if (intersects) inside = !inside
                j = i
            }
            return inside
        }

        private fun parseXmlDocument(xml: String): org.w3c.dom.Document? {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = false
                try {
                    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                } catch (_: Exception) {
                    // Android XML parser may not support every Xerces feature flag
                }
            }
            return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }

        private fun childText(parent: Element, tagName: String): String? {
            val nodes = parent.getElementsByTagName(tagName)
            if (nodes.length == 0) return null
            return nodes.item(0)?.textContent
        }

        private fun firstTagText(parent: Element, tagName: String): String? = childText(parent, tagName)?.trim()

        private fun extractParameter(info: Element, targetName: String): String? {
            val params = info.getElementsByTagName("parameter")
            for (i in 0 until params.length) {
                val p = params.item(i) as? Element ?: continue
                val name = childText(p, "valueName")?.trim()
                if (name.equals(targetName, ignoreCase = true)) {
                    return childText(p, "value")?.trim()
                }
            }
            return null
        }

        private val RSS_FALLBACK_DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss Z", Locale.US)

        private fun parseRssDate(raw: String): Instant? {
            val trimmed = raw.trim()
            return try {
                ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: Exception) {
                try {
                    val withoutWeekday = trimmed.substringAfter(",", trimmed).trim()
                    ZonedDateTime.parse(withoutWeekday, RSS_FALLBACK_DATE_FORMAT).toInstant()
                } catch (_: Exception) {
                    null
                }
            }
        }

        private fun parseIsoInstant(raw: String): Instant? = try {
            OffsetDateTime.parse(raw.trim()).toInstant()
        } catch (_: Exception) {
            null
        }

        private fun cleanMultilineText(raw: String?): String {
            if (raw.isNullOrBlank()) return ""
            return raw.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.contains("WeatherSmart APP", ignoreCase = true) }
                .joinToString(" ")
        }
    }
}
