package com.google.weatherapp.data.repository

import com.google.weatherapp.data.api.OpenMeteoGeoResult
import com.google.weatherapp.data.api.PhotonFeature
import com.google.weatherapp.data.api.PhotonProperties
import com.google.weatherapp.data.model.SavedLocation
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure worldwide place search, ranking, coordinate parsing, and reverse-geocoding formatter.
 * Ensures any city, suburb, neighborhood, postal code, landmark, or coordinate pair resolves
 * cleanly with zero fabricated names.
 */
internal object LocationSearchEngine {

    private val COORD_REGEX = Regex(
        """^\s*([+-]?\d{1,2}(?:\.\d+)?)\s*°?\s*([NSns])?(?:\s*,\s*|\s+)([+-]?\d{1,3}(?:\.\d+)?)\s*°?\s*([EWew])?\s*$"""
    )

    private val PREFERRED_PHOTON_TYPES = setOf(
        "city", "town", "village", "suburb", "district", "locality",
        "county", "state", "country", "borough", "quarter", "neighbourhood",
        "hamlet", "island", "postcode"
    )

    private val RESIDENTIAL_PLACE_TYPES = setOf(
        "city", "town", "village", "suburb", "district", "borough",
        "quarter", "neighbourhood", "hamlet"
    )

    private val ALLOWED_BOUNDARY_VALUES = setOf(
        "administrative", "national_park", "protected_area", "postal_code"
    )

    private val ALLOWED_NATURAL_VALUES = setOf(
        "peak", "mountain_range", "volcano", "beach", "bay", "valley",
        "glacier", "peninsula", "cape", "island"
    )

    private val ALLOWED_TOURISM_VALUES = setOf(
        "resort", "ski_resort"
    )

    private val ALLOWED_AMENITY_VALUES = setOf(
        "university", "ferry_terminal"
    )

    private val ALLOWED_OSM_VALUES = setOf(
        "city", "town", "village", "suburb", "quarter", "neighbourhood", "borough",
        "hamlet", "isolated_dwelling", "locality", "island", "islet", "archipelago",
        "municipality", "county", "state", "region", "province", "administrative",
        "aerodrome", "national_park", "nature_reserve",
        "peak", "mountain_range", "volcano", "beach", "bay", "valley", "glacier",
        "ski_resort", "resort", "postcode"
    )

    private val REGION_ABBREVIATIONS = mapOf(
        // United States
        "al" to "alabama", "ak" to "alaska", "az" to "arizona", "ar" to "arkansas",
        "ca" to "california", "co" to "colorado", "ct" to "connecticut", "de" to "delaware",
        "fl" to "florida", "ga" to "georgia", "hi" to "hawaii", "id" to "idaho",
        "il" to "illinois", "in" to "indiana", "ia" to "iowa", "ks" to "kansas",
        "ky" to "kentucky", "la" to "louisiana", "me" to "maine", "md" to "maryland",
        "ma" to "massachusetts", "mi" to "michigan", "mn" to "minnesota", "ms" to "mississippi",
        "mo" to "missouri", "mt" to "montana", "ne" to "nebraska", "nv" to "nevada",
        "nh" to "new hampshire", "nj" to "new jersey", "nm" to "new mexico", "ny" to "new york",
        "nc" to "north carolina", "nd" to "north dakota", "oh" to "ohio", "ok" to "oklahoma",
        "or" to "oregon", "pa" to "pennsylvania", "ri" to "rhode island", "sc" to "south carolina",
        "sd" to "south dakota", "tn" to "tennessee", "tx" to "texas", "ut" to "utah",
        "vt" to "vermont", "va" to "virginia", "wa" to "washington", "wv" to "west virginia",
        "wi" to "wisconsin", "wy" to "wyoming", "dc" to "district of columbia",
        // Canada
        "ab" to "alberta", "bc" to "british columbia", "mb" to "manitoba", "nb" to "new brunswick",
        "nl" to "newfoundland", "ns" to "nova scotia", "nt" to "northwest territories",
        "nu" to "nunavut", "on" to "ontario", "pe" to "prince edward island", "qc" to "quebec",
        "sk" to "saskatchewan", "yt" to "yukon",
        // Australia
        "nsw" to "new south wales", "qld" to "queensland", "sa" to "south australia",
        "tas" to "tasmania", "vic" to "victoria", "act" to "australian capital territory"
    )

    /**
     * Parses direct latitude/longitude queries such as:
     * "-33.9249, 18.4241", "35.68 139.65", or "40.71° N, 74.01° W".
     */
    internal fun parseCoordinateQuery(query: String): Pair<Double, Double>? {
        val match = COORD_REGEX.matchEntire(query) ?: return null
        val rawLat = match.groupValues[1].toDoubleOrNull() ?: return null
        val ns = match.groupValues[2].uppercase(Locale.US)
        val rawLon = match.groupValues[3].toDoubleOrNull() ?: return null
        val ew = match.groupValues[4].uppercase(Locale.US)

        // Require either a decimal point, comma, or hemisphere letter so 5-digit postal codes like "90210" aren't misread
        val looksLikeCoords = query.contains(",") || query.contains(".") || ns.isNotEmpty() || ew.isNotEmpty()
        if (!looksLikeCoords) return null

        val lat = if (ns == "S") -abs(rawLat) else rawLat
        val lon = if (ew == "W") -abs(rawLon) else rawLon
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    internal fun formatCoordinates(latitude: Double, longitude: Double): String {
        val latHem = if (latitude >= 0.0) "N" else "S"
        val lonHem = if (longitude >= 0.0) "E" else "W"
        return String.format(Locale.US, "%.2f° %s, %.2f° %s", abs(latitude), latHem, abs(longitude), lonHem)
    }

    internal fun formatReverseGeocodeName(props: PhotonProperties?): String? {
        if (props == null) return null
        val district = props.district?.trim()?.takeIf { it.isNotBlank() }
            ?: props.locality?.trim()?.takeIf { it.isNotBlank() }
        val city = props.city?.trim()?.takeIf { it.isNotBlank() }
        val name = props.name?.trim()?.takeIf { it.isNotBlank() }
        val county = props.county?.trim()?.takeIf { it.isNotBlank() }
        val state = props.state?.trim()?.takeIf { it.isNotBlank() }

        val isUsefulPlaceName = name != null && isUsefulPhotonFeature(props)

        return when {
            district != null && city != null && !district.equals(city, ignoreCase = true) ->
                "$district, $city"
            isUsefulPlaceName && city != null && !name!!.equals(city, ignoreCase = true) && props.osmKey == "place" ->
                "$name, $city"
            city != null -> city
            district != null -> district
            isUsefulPlaceName -> name
            county != null -> county
            state != null -> state
            else -> null
        }
    }

    internal fun mergeAndRankSearchResults(
        query: String,
        omResults: List<OpenMeteoGeoResult>,
        photonFeatures: List<PhotonFeature>
    ): List<SavedLocation> {
        val rawParts = query.split(",").map { normalizeText(it) }.filter { it.isNotBlank() }
        val primaryQuery = rawParts.firstOrNull().orEmpty()
        val qualifierQuery = rawParts.drop(1).joinToString(" ")

        data class Candidate(
            val location: SavedLocation,
            val score: Double
        )

        val candidates = mutableListOf<Candidate>()

        // 1. Open-Meteo candidates (rich population + IANA timezone)
        omResults.forEach { item ->
            val name = item.name?.trim()?.takeIf { it.isNotBlank() } ?: return@forEach
            val lat = item.latitude ?: return@forEach
            val lon = item.longitude ?: return@forEach
            val admin = listOfNotNull(
                item.admin1?.trim()?.takeIf { it.isNotBlank() },
                item.admin2?.trim()?.takeIf { it.isNotBlank() && !it.equals(item.admin1, ignoreCase = true) && !it.equals(name, ignoreCase = true) }
            ).firstOrNull().orEmpty()
            val country = item.country?.trim().orEmpty()
            val loc = SavedLocation(
                name = name,
                adminArea = admin,
                country = country,
                latitude = lat,
                longitude = lon,
                isCurrentGps = false,
                timezone = item.timezone?.trim()?.takeIf { it.isNotBlank() },
                countryCode = item.countryCode?.trim()?.takeIf { it.isNotBlank() }
            )
            val popBonus = when {
                (item.population ?: 0L) >= 1_000_000L -> 30.0
                (item.population ?: 0L) >= 250_000L -> 22.0
                (item.population ?: 0L) >= 50_000L -> 14.0
                (item.population ?: 0L) >= 5_000L -> 8.0
                else -> 2.0
            }
            val score = computeMatchScore(loc, primaryQuery, qualifierQuery) + popBonus + 10.0
            candidates.add(Candidate(loc, score))
        }

        // 2. Photon (OpenStreetMap) candidates — suburbs, neighborhoods, landmarks, postal codes, "Area, City"
        // Never re-admit commercial POIs (restaurants, pubs, shops, offices, hairdressers) when filtered out.
        val filteredPhoton = photonFeatures.filter { isUsefulPhotonFeature(it.properties) }

        filteredPhoton.forEachIndexed { idx, feature ->
            val coords = feature.geometry?.coordinates
            if (coords == null || coords.size < 2) return@forEachIndexed
            val lon = coords[0]
            val lat = coords[1]
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEachIndexed

            val props = feature.properties ?: return@forEachIndexed
            val name = resolvePhotonDisplayName(props) ?: return@forEachIndexed
            val adminParts = listOfNotNull(
                props.city?.trim()?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) },
                props.state?.trim()?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }
            ).distinctBy { it.lowercase(Locale.US) }
            val adminArea = adminParts.joinToString(", ")
            val country = props.country?.trim().orEmpty()

            val loc = SavedLocation(
                name = name,
                adminArea = adminArea,
                country = country,
                latitude = lat,
                longitude = lon,
                isCurrentGps = false,
                timezone = null,
                countryCode = props.countryCode?.trim()?.uppercase(Locale.US)?.takeIf { it.isNotBlank() }
            )
            val rankBonus = (10 - idx).coerceAtLeast(0) * 1.5
            val placeTypeBonus = when (props.osmValue?.lowercase(Locale.US)) {
                "city" -> 22.0
                "town", "suburb", "borough", "district" -> 16.0
                "village", "quarter", "neighbourhood", "locality" -> 12.0
                "aerodrome", "national_park", "island" -> 10.0
                else -> 4.0
            }
            val score = computeMatchScore(loc, primaryQuery, qualifierQuery) + placeTypeBonus + rankBonus
            candidates.add(Candidate(loc, score))
        }

        // 3. Deduplicate nearby matches (preserving Open-Meteo timezone when merged)
        val merged = mutableListOf<Candidate>()
        for (cand in candidates.sortedByDescending { it.score }) {
            val existingIdx = merged.indexOfFirst { isDuplicateCandidate(it.location, cand.location) }
            if (existingIdx < 0) {
                merged.add(cand)
            } else {
                val existing = merged[existingIdx]
                if (existing.location.timezone.isNullOrBlank() && !cand.location.timezone.isNullOrBlank()) {
                    merged[existingIdx] = existing.copy(
                        location = existing.location.copy(
                            timezone = cand.location.timezone,
                            countryCode = existing.location.countryCode ?: cand.location.countryCode
                        )
                    )
                }
            }
            if (merged.size >= 10) break
        }

        return merged.map { it.location }
    }

    internal fun isSamePlace(a: SavedLocation, b: SavedLocation): Boolean {
        if (a.isCurrentGps != b.isCurrentGps) return false
        val distKm = distanceKm(a.latitude, a.longitude, b.latitude, b.longitude)
        if (distKm <= 1.0) return true
        val normA = normalizeText(a.name)
        val normB = normalizeText(b.name)
        if (normA.isBlank() || normB.isBlank()) return false
        val sameName = normA == normB
        if (sameName && distKm <= 15.0) return true
        return sameName &&
            normalizeText(a.country) == normalizeText(b.country) &&
            normalizeText(a.adminArea) == normalizeText(b.adminArea) &&
            distKm <= 50.0
    }

    internal fun distanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2.0 * r * atan2(sqrt(a), sqrt((1.0 - a).coerceAtLeast(0.0)))
    }

    private fun isDuplicateCandidate(a: SavedLocation, b: SavedLocation): Boolean {
        val distKm = distanceKm(a.latitude, a.longitude, b.latitude, b.longitude)
        if (distKm <= 1.5) return true
        val normA = normalizeText(a.name)
        val normB = normalizeText(b.name)
        if (normA.isBlank() || normB.isBlank()) return false
        return (normA == normB || normA.startsWith(normB) || normB.startsWith(normA)) && distKm <= 12.0
    }

    internal fun isUsefulPhotonFeature(props: PhotonProperties?): Boolean {
        if (props == null) return false
        val key = props.osmKey?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val value = props.osmValue?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val type = props.type?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val isPostcodeEntry = !props.postcode.isNullOrBlank() &&
            (type == "postcode" || value == "postcode" || props.name.isNullOrBlank())

        return when (key) {
            "place" -> value != "plot"
            "boundary" -> value in ALLOWED_BOUNDARY_VALUES || (value.isBlank() && type in PREFERRED_PHOTON_TYPES)
            "aeroway" -> value == "aerodrome"
            "natural" -> value in ALLOWED_NATURAL_VALUES
            "leisure" -> value == "nature_reserve"
            "tourism" -> value in ALLOWED_TOURISM_VALUES
            "landuse" -> value == "residential" && type in RESIDENTIAL_PLACE_TYPES
            "amenity" -> value in ALLOWED_AMENITY_VALUES
            "" -> value in ALLOWED_OSM_VALUES || type in PREFERRED_PHOTON_TYPES || isPostcodeEntry
            else -> false
        }
    }

    private fun resolvePhotonDisplayName(props: PhotonProperties): String? {
        val rawName = props.name?.trim()?.takeIf { it.isNotBlank() }
        val postcode = props.postcode?.trim()?.takeIf { it.isNotBlank() }
        val city = props.city?.trim()?.takeIf { it.isNotBlank() }
        return when {
            rawName != null -> rawName
            postcode != null && city != null -> "$postcode, $city"
            city != null -> city
            else -> props.district?.trim()?.takeIf { it.isNotBlank() }
                ?: props.county?.trim()?.takeIf { it.isNotBlank() }
                ?: props.state?.trim()?.takeIf { it.isNotBlank() }
        }
    }

    private fun computeMatchScore(
        location: SavedLocation,
        primaryQuery: String,
        qualifierQuery: String
    ): Double {
        if (primaryQuery.isBlank()) return 0.0
        val normName = normalizeText(location.name)
        val normContext = normalizeText("${location.adminArea} ${location.country} ${location.countryCode.orEmpty()}")
        val contextTokens = normContext.split(" ").filter { it.isNotBlank() }

        fun matchesContextToken(tok: String): Boolean {
            val expandedRegion = REGION_ABBREVIATIONS[tok]
            if (expandedRegion != null && (
                    normContext == expandedRegion ||
                        normContext.startsWith("$expandedRegion ") ||
                        normContext.endsWith(" $expandedRegion") ||
                        normContext.contains(" $expandedRegion ")
                    )
            ) {
                return true
            }
            if (tok.length <= 2) {
                return contextTokens.any { it == tok }
            }
            return contextTokens.any { it == tok || it.startsWith(tok) } || normContext.contains(tok)
        }

        var bestScore = when {
            normName.isBlank() -> 0.0
            normName == primaryQuery -> 60.0
            normName.startsWith(primaryQuery) -> 42.0
            normName.contains(primaryQuery) -> 24.0
            else -> 8.0
        }
        if (qualifierQuery.isNotBlank()) {
            val tokens = qualifierQuery.split(" ").filter { it.length >= 2 }
            val matchedTokens = tokens.count { tok ->
                matchesContextToken(tok) || normName.contains(tok)
            }
            bestScore += if (matchedTokens == tokens.size && tokens.isNotEmpty()) {
                45.0
            } else {
                matchedTokens * 18.0
            }
        } else if (normName.isNotBlank()) {
            // Support comma-less qualified queries such as "Paris Texas", "London Ontario", "San Jose California"
            val queryTokens = primaryQuery.split(" ").filter { it.isNotBlank() }
            if (queryTokens.size >= 2) {
                for (splitIdx in 1 until queryTokens.size) {
                    val candPrimary = queryTokens.take(splitIdx).joinToString(" ")
                    val candQualifier = queryTokens.drop(splitIdx).joinToString(" ")
                    val qualTokens = candQualifier.split(" ").filter { it.length >= 2 }
                    if (qualTokens.isEmpty()) continue
                    val nameMatches = normName == candPrimary || normName.startsWith(candPrimary)
                    val allQualMatchContext = qualTokens.all { matchesContextToken(it) }
                    if (nameMatches && allQualMatchContext) {
                        val splitScore = (if (normName == candPrimary) 60.0 else 42.0) + 45.0
                        if (splitScore > bestScore) {
                            bestScore = splitScore
                        }
                    }
                }
            }
        }
        return bestScore
    }

    internal fun normalizeText(raw: String): String {
        val decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
        val withoutLatinDiacritics = decomposed.replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        val recomposed = Normalizer.normalize(withoutLatinDiacritics, Normalizer.Form.NFC)
        return recomposed
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{M}\\p{Nd}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
