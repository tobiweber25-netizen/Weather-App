package com.google.weatherapp.data.repository

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.weatherapp.data.api.OpenMeteoAirQualityCurrent
import com.google.weatherapp.data.model.EnsembleDispersion
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure meteorological consensus, physical validation, and probabilistic dispersion engine.
 * Combines Google Weather with up to 5–6 independent Open-Meteo models using lead-time-aware
 * skill weights, outlier dampening, and calibrated rain averaging (no double-counting).
 */
internal object WeatherConsensus {

    internal const val MODEL_BEST_MATCH = "best_match"
    internal const val MODEL_ECMWF_IFS = "ecmwf_ifs"
    internal const val MODEL_ECMWF_AIFS = "ecmwf_aifs025_single"
    internal const val MODEL_UKMO = "ukmo_seamless"
    internal const val MODEL_ICON = "icon_seamless"
    internal const val MODEL_GFS = "gfs_seamless"

    internal val ALL_OPEN_METEO_MODELS = listOf(
        MODEL_BEST_MATCH,
        MODEL_ECMWF_IFS,
        MODEL_ECMWF_AIFS,
        MODEL_UKMO,
        MODEL_ICON,
        MODEL_GFS
    )

    private val GLOBAL_BASE_MODELS = listOf(
        MODEL_ECMWF_IFS,
        MODEL_UKMO,
        MODEL_ICON,
        MODEL_GFS,
        MODEL_ECMWF_AIFS
    )

    internal data class MultiModelHourSlot(
        val tempsByModel: Map<String, Double>,
        val precipByModel: Map<String, Double>,
        val popByModel: Map<String, Int>
    )

    internal data class MultiModelConsensusData(
        val hourlyByTime: Map<String, MultiModelHourSlot>,
        val activeModels: List<String>,
        val hasDistinctRegionalModel: Boolean
    )

    internal data class AirQualitySummary(
        val value: Int,
        val category: String,
        val scaleLabel: String,
        val pm25: Double,
        val dominantPollutantLabel: String,
        val isAvailable: Boolean
    )

    internal fun parseMultiModelConsensus(raw: JsonObject?): MultiModelConsensusData? {
        val hourly = raw?.getAsJsonObject("hourly") ?: return null
        val times = hourly.getAsJsonArray("time")?.mapNotNull {
            if (it.isJsonNull) null else it.asString
        } ?: return null
        if (times.isEmpty()) return null

        val tempArrays = HashMap<String, List<Double?>>()
        val precipArrays = HashMap<String, List<Double?>>()
        val popArrays = HashMap<String, List<Int?>>()

        for (model in ALL_OPEN_METEO_MODELS) {
            extractDoubleList(hourly, "temperature_2m_$model")?.let { tempArrays[model] = it }
            extractDoubleList(hourly, "precipitation_$model")?.let { precipArrays[model] = it }
            extractIntList(hourly, "precipitation_probability_$model")?.let { popArrays[model] = it }
        }

        if (tempArrays.isEmpty() && precipArrays.isEmpty()) return null

        // Deduplicate best_match whenever it aliases one of the explicit global models
        // (e.g., in South Africa best_match == ecmwf_ifs; in UK best_match == ukmo_seamless).
        val bestTemps = tempArrays[MODEL_BEST_MATCH]
        val bestMatchIsDuplicate = bestTemps != null && GLOBAL_BASE_MODELS.any { globalModel ->
            val otherTemps = tempArrays[globalModel] ?: return@any false
            seriesMatches(bestTemps, otherTemps)
        }
        val activeModels = ALL_OPEN_METEO_MODELS.filter { model ->
            if (model == MODEL_BEST_MATCH && bestMatchIsDuplicate) {
                false
            } else {
                tempArrays[model]?.any { it != null } == true ||
                    precipArrays[model]?.any { it != null } == true
            }
        }
        if (activeModels.isEmpty()) return null

        val hourlyByTime = LinkedHashMap<String, MultiModelHourSlot>(times.size)
        for (i in times.indices) {
            val tKey = times[i]
            // Preserve the first occurrence on DST fall-back duplicate clock hours
            if (hourlyByTime.containsKey(tKey)) continue

            val temps = LinkedHashMap<String, Double>(activeModels.size)
            val precips = LinkedHashMap<String, Double>(activeModels.size)
            val pops = LinkedHashMap<String, Int>(activeModels.size)

            for (model in activeModels) {
                tempArrays[model]?.getOrNull(i)?.let { temps[model] = it }
                precipArrays[model]?.getOrNull(i)?.let { precips[model] = it }
                popArrays[model]?.getOrNull(i)?.let { pops[model] = it }
            }
            hourlyByTime[tKey] = MultiModelHourSlot(
                tempsByModel = temps,
                precipByModel = precips,
                popByModel = pops
            )
        }

        return MultiModelConsensusData(
            hourlyByTime = hourlyByTime,
            activeModels = activeModels,
            hasDistinctRegionalModel = activeModels.contains(MODEL_BEST_MATCH)
        )
    }

    internal fun modelWeight(modelId: String, leadHours: Int): Double {
        return when {
            leadHours <= 0 -> when (modelId) {
                MODEL_BEST_MATCH -> 0.28
                MODEL_ECMWF_IFS -> 0.24
                MODEL_UKMO -> 0.20
                MODEL_ECMWF_AIFS -> 0.14
                MODEL_ICON -> 0.10
                MODEL_GFS -> 0.04
                else -> 0.15
            }
            leadHours <= 24 -> when (modelId) {
                MODEL_BEST_MATCH -> 0.24
                MODEL_ECMWF_IFS -> 0.20
                MODEL_UKMO -> 0.16
                MODEL_ECMWF_AIFS -> 0.14
                MODEL_ICON -> 0.11
                MODEL_GFS -> 0.07
                else -> 0.12
            }
            leadHours <= 48 -> when (modelId) {
                MODEL_ECMWF_IFS -> 0.24
                MODEL_ECMWF_AIFS -> 0.20
                MODEL_BEST_MATCH -> 0.16
                MODEL_UKMO -> 0.15
                MODEL_ICON -> 0.11
                MODEL_GFS -> 0.07
                else -> 0.12
            }
            else -> when (modelId) {
                MODEL_ECMWF_IFS -> 0.30
                MODEL_ECMWF_AIFS -> 0.26
                MODEL_UKMO -> 0.15
                MODEL_ICON -> 0.11
                MODEL_BEST_MATCH -> 0.11
                MODEL_GFS -> 0.07
                else -> 0.10
            }
        }
    }

    internal fun googleWeight(leadHours: Int): Double = when {
        // Current conditions: Google incorporates live weather stations + rapid nowcasting
        leadHours <= 0 -> 0.78
        leadHours <= 6 -> 0.45
        leadHours <= 24 -> 0.34
        leadHours <= 48 -> 0.25
        else -> 0.22
    }

    /**
     * Skill-weighted, outlier-resistant consensus temperature.
     * When >= 4 sources are present, any single source deviating > 3.5°C from the median
     * is dampened by 50% so one biased model cannot pull the consensus off target.
     */
    internal fun blendConsensusTemperature(
        googleTemp: Double?,
        modelTemps: Map<String, Double>,
        leadHours: Int
    ): Double? {
        val entries = mutableListOf<Pair<Double, Double>>() // (value, weight)

        if (leadHours <= 0 && googleTemp != null && modelTemps.isNotEmpty()) {
            // Normalize all NWP models to share the remaining (1 - googleWeight) cleanly
            val gW = googleWeight(0)
            val omWTotal = 1.0 - gW
            val omConsensus = weightedAverageWithOutlierGuard(
                modelTemps.entries.map { (id, v) -> v to modelWeight(id, 0) }
            )
            return if (omConsensus != null) {
                googleTemp * gW + omConsensus * omWTotal
            } else {
                googleTemp
            }
        }

        if (googleTemp != null) {
            entries.add(googleTemp to googleWeight(leadHours))
        }
        modelTemps.forEach { (id, temp) ->
            entries.add(temp to modelWeight(id, leadHours))
        }
        return weightedAverageWithOutlierGuard(entries)
    }

    /**
     * Calibrated precipitation probability blending (weighted mean across non-null sources).
     * Never uses maxOf, preventing systematic wet bias and false alarms.
     */
    internal fun blendConsensusRainProbability(
        googlePop: Int?,
        modelPops: Map<String, Int>,
        leadHours: Int
    ): Int? {
        val entries = mutableListOf<Pair<Double, Double>>()
        if (googlePop != null) {
            entries.add(googlePop.coerceIn(0, 100).toDouble() to googleWeight(leadHours))
        }
        modelPops.forEach { (id, pop) ->
            entries.add(pop.coerceIn(0, 100).toDouble() to modelWeight(id, leadHours))
        }
        if (entries.isEmpty()) return null
        val totalW = entries.sumOf { it.second }.takeIf { it > 0.0 } ?: return null
        return (entries.sumOf { it.first * it.second } / totalW).roundToInt().coerceIn(0, 100)
    }

    /**
     * Calibrated rainfall accumulation blending (weighted mean with dry-majority noise floor).
     * Prevents doubling rainfall when two models disagree on shower timing by 1–2 hours.
     */
    internal fun blendConsensusPrecipMm(
        googlePrecipMm: Double?,
        modelPrecipsMm: Map<String, Double>,
        leadHours: Int
    ): Double? {
        val entries = mutableListOf<Pair<Double, Double>>()
        if (googlePrecipMm != null) {
            entries.add(googlePrecipMm.coerceAtLeast(0.0) to googleWeight(leadHours))
        }
        modelPrecipsMm.forEach { (id, mm) ->
            entries.add(mm.coerceAtLeast(0.0) to modelWeight(id, leadHours))
        }
        if (entries.isEmpty()) return null
        val totalW = entries.sumOf { it.second }.takeIf { it > 0.0 } ?: return null
        val weightedMean = entries.sumOf { it.first * it.second } / totalW

        // Dry-consensus guard: if >= 3 models exist, majority are dry (<= 0.05 mm), and weighted mean < 0.1 mm -> 0.0
        if (entries.size >= 3) {
            val dryCount = entries.count { it.first <= 0.05 }
            if (dryCount * 2 > entries.size && weightedMean < 0.1) {
                return 0.0
            }
        }
        return roundOneDecimal(weightedMean)
    }

    /**
     * True statistical dispersion across all participating models.
     * Reports genuine ±half-spread (never doubled), P10/P90 percentiles, and never fabricates
     * fake multi-model spread when only a single source is available.
     */
    internal fun computeMultiModelDispersion(
        centerTemp: Int,
        tempSamples: List<Double>,
        highTempSamples: List<Double> = emptyList(),
        popSamples: List<Int> = emptyList(),
        dayIndex: Int = 0
    ): EnsembleDispersion {
        val validTemps = tempSamples.sorted()
        val hasMultiModel = validTemps.size >= 2

        if (!hasMultiModel) {
            val singleConf = (75 - dayIndex * 2).coerceIn(55, 76)
            return EnsembleDispersion(
                modelSpreadDegrees = 0.0,
                p10Temp = centerTemp,
                p90Temp = centerTemp,
                confidencePercent = singleConf,
                confidenceTier = "Single Source",
                modelAgreementSummary = "Single source feed (no cross-model verification)"
            )
        }

        val fullSpread = validTemps.last() - validTemps.first()
        val halfSpread = fullSpread / 2.0
        val highHalfSpread = if (highTempSamples.size >= 2) {
            (highTempSamples.max() - highTempSamples.min()) / 2.0
        } else {
            halfSpread
        }
        val effectiveHalfSpread = maxOf(halfSpread, highHalfSpread * 0.6)
        val popSpread = if (popSamples.size >= 2) {
            popSamples.max() - popSamples.min()
        } else {
            0
        }

        val p10 = if (validTemps.size >= 3) {
            percentile(validTemps, 0.10).roundToInt()
        } else {
            floor(centerTemp - halfSpread.coerceAtLeast(0.5)).roundToInt()
        }
        val p90 = if (validTemps.size >= 3) {
            percentile(validTemps, 0.90).roundToInt()
        } else {
            ceil(centerTemp + halfSpread.coerceAtLeast(0.5)).roundToInt()
        }

        // Standard deviation gives a cleaner signal when 5–6 models are active
        val mean = validTemps.average()
        val stdDev = sqrt(validTemps.map { (it - mean) * (it - mean) }.average())
        val spreadMetric = if (validTemps.size >= 4) {
            (stdDev * 1.4 + effectiveHalfSpread * 0.6)
        } else {
            fullSpread
        }

        val leadPenalty = dayIndex * 1.1
        val confidenceVal = (98.0 - (spreadMetric * 6.5) - (popSpread * 0.28) - leadPenalty)
            .roundToInt()
            .coerceIn(52, 98)

        val tier = when {
            confidenceVal >= 88 -> "High Agreement"
            confidenceVal >= 72 -> "Moderate Spread"
            else -> "High Uncertainty"
        }

        val roundedHalf = roundOneDecimal(halfSpread)
        val modelWord = "${validTemps.size}-model"
        val summary = when {
            halfSpread <= 0.6 && popSpread <= 12 -> "High $modelWord consensus (±${roundedHalf}°C)"
            halfSpread <= 1.4 -> "Moderate $modelWord variance (±${roundedHalf}°C)"
            else -> "Model divergence (±${roundedHalf}°C across ${validTemps.size} models)"
        }

        return EnsembleDispersion(
            modelSpreadDegrees = roundedHalf,
            p10Temp = minOf(p10, p90),
            p90Temp = maxOf(p10, p90),
            confidencePercent = confidenceVal,
            confidenceTier = tier,
            modelAgreementSummary = summary
        )
    }

    /**
     * Exact Alduchov-Eskridge (1996) Magnus formula for dew point (°C).
     * Accurate to < 0.4°C across 1%–100% RH (eliminates 5–15°C dry-air errors).
     */
    internal fun computeDewPoint(tempC: Double, relativeHumidityPercent: Int): Int {
        val rh = relativeHumidityPercent.coerceIn(1, 100).toDouble()
        val a = 17.625
        val b = 243.04
        val alpha = ln(rh / 100.0) + (a * tempC) / (b + tempC)
        val dewPoint = (b * alpha) / (a - alpha)
        return dewPoint.coerceAtMost(tempC).roundToInt()
    }

    /**
     * Physical feels-like calculation & upstream sanity guard.
     * Catches anomalies like Google Weather returning Heat Index 24.4°C at 19.9°C air temp.
     */
    internal fun computeSanitizedFeelsLike(
        actualTempC: Double,
        googleFeelsC: Double?,
        omFeelsC: Double?,
        humidityPercent: Int,
        windKmH: Double,
        isDaytime: Boolean
    ): Int {
        val physicalRef = computePhysicalFeelsLike(actualTempC, humidityPercent, windKmH)

        fun isPlausible(candidate: Double?): Boolean {
            if (candidate == null) return false
            val delta = candidate - actualTempC
            // Without extreme heat (>= 26°C), air cannot feel > 2.2°C warmer at night or > 3.0°C in sun
            val maxWarmBonus = when {
                actualTempC >= 26.0 -> 15.0
                actualTempC >= 23.0 && humidityPercent >= 80 -> 3.0
                isDaytime -> 2.2
                else -> 1.5
            }
            // Without cold/high wind, apparent temperature cannot plunge wildly below actual
            val maxCoolDrop = when {
                actualTempC <= 10.0 -> 18.0
                windKmH >= 30.0 -> 5.5
                else -> 3.5
            }
            return delta in -maxCoolDrop..maxWarmBonus
        }

        val validGoogle = googleFeelsC?.takeIf { isPlausible(it) }
        val validOm = omFeelsC?.takeIf { isPlausible(it) }

        val result = when {
            validGoogle != null && validOm != null -> validGoogle * 0.72 + validOm * 0.28
            validGoogle != null -> validGoogle
            validOm != null -> validOm * 0.65 + physicalRef * 0.35
            else -> physicalRef
        }
        return result.roundToInt()
    }

    /** Standard NOAA Heat Index (>= 26.7°C, RH >= 40%) and Environment Canada Wind Chill (<= 10°C, wind >= 4.8 km/h). */
    internal fun computePhysicalFeelsLike(tempC: Double, humidityPercent: Int, windKmH: Double): Double {
        val rh = humidityPercent.coerceIn(0, 100).toDouble()
        if (tempC <= 10.0 && windKmH >= 4.8) {
            val vPow = windKmH.pow(0.16)
            return 13.12 + 0.6215 * tempC - 11.37 * vPow + 0.3965 * tempC * vPow
        }
        if (tempC >= 26.7 && rh >= 40.0) {
            val tF = tempC * 9.0 / 5.0 + 32.0
            val hiF = -42.379 +
                2.04901523 * tF +
                10.14333127 * rh -
                0.22475541 * tF * rh -
                0.00683783 * tF * tF -
                0.05481717 * rh * rh +
                0.00122874 * tF * tF * rh +
                0.00085282 * tF * rh * rh -
                0.00000199 * tF * tF * rh * rh
            return (hiF - 32.0) * 5.0 / 9.0
        }
        return tempC
    }

    /** Converts station surface pressure (hPa) to mean sea-level pressure using hypsometric formula. */
    internal fun convertSurfacePressureToSeaLevel(
        surfacePressureHpa: Double,
        elevationMeters: Double,
        tempC: Double
    ): Int {
        if (elevationMeters <= 1.0) return surfacePressureHpa.roundToInt()
        val factor = 1.0 - (0.0065 * elevationMeters) / (tempC + 0.0065 * elevationMeters + 273.15)
        if (factor <= 0.1) return surfacePressureHpa.roundToInt()
        return (surfacePressureHpa * factor.pow(-5.257)).roundToInt()
    }

    /**
     * Strictly separates US EPA AQI (0–500, including 2024 breakpoints & Very Unhealthy 201–300)
     * from European AQI (0–100+ 6-step scale) and identifies the true dominant pollutant.
     */
    internal fun resolveAirQuality(current: OpenMeteoAirQualityCurrent?): AirQualitySummary {
        if (current == null || (current.usAqi == null && current.europeanAqi == null)) {
            return AirQualitySummary(
                value = 0,
                category = "Unavailable",
                scaleLabel = "AQI",
                pm25 = 0.0,
                dominantPollutantLabel = "Sensor feed unavailable",
                isAvailable = false
            )
        }

        val useUsScale = current.usAqi != null
        val value = if (useUsScale) current.usAqi!! else current.europeanAqi!!
        val category = if (useUsScale) categorizeUsAqi(value) else categorizeEuropeanAqi(value)
        val scaleLabel = if (useUsScale) "US AQI" else "EU AQI"
        val pm25 = current.pm25?.let { roundOneDecimal(it) } ?: 0.0

        val dominantLabel = resolveDominantPollutantLabel(
            pm25 = current.pm25,
            pm10 = current.pm10,
            ozone = current.ozone,
            no2 = current.nitrogenDioxide
        )

        return AirQualitySummary(
            value = value,
            category = category,
            scaleLabel = scaleLabel,
            pm25 = pm25,
            dominantPollutantLabel = dominantLabel,
            isAvailable = true
        )
    }

    internal fun categorizeUsAqi(usAqi: Int): String = when {
        usAqi <= 50 -> "Good"
        usAqi <= 100 -> "Moderate"
        usAqi <= 150 -> "Sensitive"
        usAqi <= 200 -> "Unhealthy"
        usAqi <= 300 -> "Very Unhealthy"
        else -> "Hazardous"
    }

    internal fun categorizeEuropeanAqi(eaqi: Int): String = when {
        eaqi <= 20 -> "Good"
        eaqi <= 40 -> "Fair"
        eaqi <= 60 -> "Moderate"
        eaqi <= 80 -> "Poor"
        eaqi <= 100 -> "Very Poor"
        else -> "Extremely Poor"
    }

    internal fun resolveDominantPollutantLabel(
        pm25: Double?,
        pm10: Double?,
        ozone: Double?,
        no2: Double?
    ): String {
        // Normalize each pollutant against its 2024 EPA/WHO "Good -> Moderate" threshold
        val candidates = buildList {
            pm25?.takeIf { it > 0.0 }?.let { add(Triple("PM2.5", it, it / 9.0)) }
            ozone?.takeIf { it > 0.0 }?.let { add(Triple("Ozone", it, it / 100.0)) }
            pm10?.takeIf { it > 0.0 }?.let { add(Triple("PM10", it, it / 54.0)) }
            no2?.takeIf { it > 0.0 }?.let { add(Triple("NO₂", it, it / 100.0)) }
        }
        val top = candidates.maxByOrNull { it.third }
        return if (top != null) {
            "${top.first}: ${roundOneDecimal(top.second)} µg/m³"
        } else {
            "Air sensor active"
        }
    }

    /**
     * Native 15-minute high-resolution NWP models on Open-Meteo cover Central Europe (ICON-D2 / AROME)
     * and North America (HRRR). Elsewhere, minutely_15 is temporally interpolated from hourly global grids.
     */
    internal fun hasNative15MinNowcast(latitude: Double, longitude: Double): Boolean {
        if (latitude == 0.0 && longitude == 0.0) return false
        val centralEurope = latitude in 43.0..58.0 && longitude in 0.0..20.0
        val northAmerica = latitude in 22.0..54.0 && longitude in -135.0..-60.0
        return centralEurope || northAmerica
    }

    private fun weightedAverageWithOutlierGuard(entries: List<Pair<Double, Double>>): Double? {
        if (entries.isEmpty()) return null
        if (entries.size == 1) return entries.first().first

        val adjusted = if (entries.size >= 4) {
            val sorted = entries.map { it.first }.sorted()
            val median = percentile(sorted, 0.5)
            entries.map { (v, w) ->
                if (abs(v - median) > 3.5) v to (w * 0.5) else v to w
            }
        } else {
            entries
        }
        val totalWeight = adjusted.sumOf { it.second }.takeIf { it > 0.0 } ?: return null
        return adjusted.sumOf { it.first * it.second } / totalWeight
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val rank = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(rank).toInt()
        val hi = ceil(rank).toInt()
        if (lo == hi) return sorted[lo]
        val frac = rank - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    private fun seriesMatches(a: List<Double?>, b: List<Double?>): Boolean {
        var compared = 0
        val limit = minOf(a.size, b.size, 24)
        for (i in 0 until limit) {
            val va = a[i] ?: continue
            val vb = b[i] ?: continue
            if (abs(va - vb) > 0.05) return false
            compared++
            if (compared >= 12) break
        }
        return compared >= 4
    }

    private fun extractDoubleList(obj: JsonObject, key: String): List<Double?>? {
        val arr: JsonArray = obj.getAsJsonArray(key) ?: return null
        return arr.map { elem ->
            if (elem == null || elem.isJsonNull) null else try { elem.asDouble } catch (_: Exception) { null }
        }
    }

    private fun extractIntList(obj: JsonObject, key: String): List<Int?>? {
        val arr: JsonArray = obj.getAsJsonArray(key) ?: return null
        return arr.map { elem ->
            if (elem == null || elem.isJsonNull) null else try { elem.asDouble.roundToInt() } catch (_: Exception) { null }
        }
    }

    private fun roundOneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0
}
