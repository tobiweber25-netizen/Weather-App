package com.google.weatherapp.notification

import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import java.util.Locale
import kotlin.math.abs

data class TomorrowWeatherSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val tempDiff: Int
)

data class TodayWeatherSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String
)

data class RainNowcastSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String
)

data class SevereStormSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String
)

data class HeatWarningSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val isTropicalNight: Boolean = false,
    val isExtremeHeat: Boolean = false
)

data class FreezeHazardSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val isBlackIce: Boolean = false,
    val isHardFreeze: Boolean = false
)

data class WindAlertSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val isSevereGale: Boolean = false
)

data class AqiAlertSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val isUnhealthy: Boolean = false
)

data class FogAlertSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String
)

data class RainCessationSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val cessationMinutes: Int
)

data class UvAlertSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val isExtreme: Boolean = false
)

data class TempDropSummary(
    val title: String,
    val conciseText: String,
    val expandedDetails: String,
    val dropDegrees: Int
)

object WeatherSummaryFormatter {

    fun generateTomorrowSummary(
        today: DailyItem,
        tomorrow: DailyItem,
        locationName: String? = null
    ): TomorrowWeatherSummary {
        val tempDiff = tomorrow.maxTemp - today.maxTemp

        val tempComparison = when {
            tempDiff >= 2 -> "About ${tempDiff}° warmer tomorrow"
            tempDiff == 1 -> "About 1° warmer tomorrow"
            tempDiff <= -2 -> "About ${abs(tempDiff)}° cooler tomorrow"
            tempDiff == -1 -> "About 1° cooler tomorrow"
            else -> "Similar temperatures tomorrow"
        }

        val rawDayCondition = tomorrow.dayConditionText.ifBlank { tomorrow.conditionText }
        val conditionPhrase = formatCondition(
            rawCondition = rawDayCondition,
            rainProbability = tomorrow.rainProbability,
            precipType = tomorrow.precipitationType,
            maxTempC = tomorrow.maxTemp
        )

        val conciseText = "$tempComparison with $conditionPhrase (${tomorrow.maxTemp}° / ${tomorrow.minTemp}°)."

        val title = if (!locationName.isNullOrBlank()) {
            "Tomorrow's weather in $locationName"
        } else {
            "Tomorrow's weather"
        }

        val expandedDetails = buildString {
            append(conciseText)
            append("\nHigh: ")
            append(tomorrow.maxTemp)
            append("° • Low: ")
            append(tomorrow.minTemp)
            append("°")
            if (tomorrow.rainProbability > 0) {
                val precipTerm = precipLabelFor(
                    tomorrow.precipitationType,
                    rawDayCondition,
                    tomorrow.maxTemp
                )
                append(" • $precipTerm: ")
                append(tomorrow.rainProbability)
                append("%")
            }
            if (tomorrow.dayWindSpeedKmH > 0) {
                append(" • Wind: ")
                append(tomorrow.dayWindSpeedKmH)
                append(" km/h")
            }
            if (tomorrow.uvIndex >= 6) {
                append(" • UV: ")
                append(tomorrow.uvIndex)
                append(" (")
                append(tomorrow.uvCategory)
                append(")")
            }
            if (tempDiff <= -7) {
                append("\nSharp cold front: Tomorrow's high drops by ${abs(tempDiff)}°C compared to today. Prepare warmer layers.")
            }
        }

        return TomorrowWeatherSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            tempDiff = tempDiff
        )
    }

    fun generateMorningSummary(
        today: DailyItem,
        upcomingHours: List<HourlyItem> = emptyList(),
        locationName: String? = null,
        minHourOfDay: Int? = null
    ): TodayWeatherSummary {
        val title = if (!locationName.isNullOrBlank()) {
            "Today's forecast • $locationName"
        } else {
            "Today's forecast"
        }

        val futureHours = if (minHourOfDay != null) {
            upcomingHours.filter { item ->
                val parsedHour = parseHourOfDay(item.hourLabel)
                parsedHour == null || parsedHour >= minHourOfDay
            }
        } else {
            upcomingHours
        }

        // Only call out hourly precip when probability or volume is meaningful (avoid false alarms on 0.2mm trace at 20% PoP)
        val firstRainIdx = futureHours.indexOfFirst { isMeaningfulRainHour(it) }
        val rawTodayCond = today.dayConditionText.ifBlank { today.conditionText }
        val conciseText = if (firstRainIdx >= 0) {
            val rainRun = futureHours.drop(firstRainIdx).takeWhile { isMeaningfulRainHour(it) }
            val firstRainHour = rainRun.first()
            val lastRainHour = rainRun.last()
            val runPrecipWord = resolveRunPrecipNoun(rainRun, today.precipitationType, rawTodayCond)
            when {
                firstRainHour.hourLabel.trim().equals("Now", ignoreCase = true) ->
                    "High of ${today.maxTemp}° with $runPrecipWord right now."
                rainRun.size >= 2 && !lastRainHour.hourLabel.trim().equals("Now", ignoreCase = true) -> {
                    val startText = formatClockLabel(firstRainHour.hourLabel)
                    val endText = formatClockLabel(lastRainHour.hourLabel)
                    "High of ${today.maxTemp}° with $runPrecipWord likely from $startText to $endText."
                }
                else -> {
                    val timingPhrase = formatHourLabelForProse(firstRainHour.hourLabel)
                    "High of ${today.maxTemp}° with $runPrecipWord likely $timingPhrase."
                }
            }
        } else {
            val cond = formatCondition(
                rawCondition = rawTodayCond,
                rainProbability = today.rainProbability,
                precipType = today.precipitationType,
                maxTempC = today.maxTemp
            )
            "High of ${today.maxTemp}° today with $cond."
        }

        val expandedDetails = buildString {
            append(conciseText)
            append("\nHigh: ")
            append(today.maxTemp)
            append("° • Low: ")
            append(today.minTemp)
            append("°")
            if (today.rainProbability > 0) {
                val precipTerm = precipLabelFor(today.precipitationType, rawTodayCond, today.maxTemp)
                append(" • $precipTerm: ")
                append(today.rainProbability)
                append("%")
            }
            if (today.dayWindSpeedKmH > 0) {
                append(" • Wind: ")
                append(today.dayWindSpeedKmH)
                append(" km/h")
            }
            if (today.uvIndex > 0) {
                append(" • UV: ")
                append(today.uvIndex)
                append(" (")
                append(today.uvCategory)
                append(")")
                if (today.uvIndex >= 8) {
                    append(" • Solar noon protection recommended (11 AM - 3 PM)")
                }
            }
        }

        return TodayWeatherSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails
        )
    }

    fun generateRainNowcastSummary(
        nowcast: PrecipitationNowcast,
        locationName: String? = null
    ): RainNowcastSummary {
        val kind = resolveNowcastPrecipKind(nowcast)
        val alertBaseTitle = when (kind) {
            PrecipKind.SNOW -> "Snow alert"
            PrecipKind.ICE -> "Ice alert"
            PrecipKind.RAIN -> "Rain alert"
        }
        val title = if (!locationName.isNullOrBlank()) {
            "$alertBaseTitle • $locationName"
        } else {
            alertBaseTitle
        }

        val effectiveRate = if (nowcast.nextPrecipitationOnsetMinutes != null && nowcast.onsetRateMmH > 0.0) {
            nowcast.onsetRateMmH
        } else {
            nowcast.maxRateMmH
        }
        val precipNoun = when (kind) {
            PrecipKind.SNOW -> "snow"
            PrecipKind.ICE -> "freezing rain"
            PrecipKind.RAIN -> "rain"
        }
        val intensityLabel = when {
            effectiveRate >= 4.0 -> "Heavy $precipNoun"
            effectiveRate >= 1.0 -> "Moderate $precipNoun"
            else -> "Light $precipNoun"
        }
        val rateClause = if (effectiveRate >= 0.1) " (~$effectiveRate mm/h)" else ""
        val actionAdvice = when (kind) {
            PrecipKind.SNOW -> "dress warmly and allow extra travel time."
            PrecipKind.ICE -> "watch for slippery roads."
            PrecipKind.RAIN -> "grab an umbrella."
        }
        val durationAdvice = when (kind) {
            PrecipKind.SNOW -> "plan for snowy roads."
            PrecipKind.ICE -> "use caution on icy surfaces."
            PrecipKind.RAIN -> "keep an umbrella handy."
        }

        val onset = nowcast.nextPrecipitationOnsetMinutes
        val conciseText = when {
            !nowcast.isHighRes15Min ->
                nowcast.summaryText.trim().removeSuffix(".") + " — $actionAdvice"
            onset != null && onset <= 5 ->
                "$intensityLabel starting right now$rateClause — $actionAdvice"
            onset != null ->
                "$intensityLabel starting in ~$onset min$rateClause — $actionAdvice"
            else ->
                nowcast.summaryText
        }

        val activeIntervals = nowcast.intervals.count { it.precipMm >= 0.1 }
        val durationGuidance = when {
            !nowcast.isHighRes15Min && activeIntervals > 0 ->
                "Expected across the next ~$activeIntervals hour${if (activeIntervals == 1) "" else "s"} — $durationAdvice"
            activeIntervals > 0 ->
                "Expected across the next ~${activeIntervals * 15} min — $durationAdvice"
            kind == PrecipKind.SNOW ->
                "Snowy conditions arriving shortly — $durationAdvice"
            kind == PrecipKind.ICE ->
                "Icy conditions arriving shortly — $durationAdvice"
            else ->
                "Wet conditions arriving shortly — $durationAdvice"
        }
        val leadSentence = when {
            !nowcast.isHighRes15Min ->
                nowcast.summaryText.trim().removeSuffix(".") + "."
            onset != null ->
                "$intensityLabel starting in ~$onset min$rateClause."
            else ->
                nowcast.summaryText.trim().removeSuffix(".") + "."
        }
        val expandedDetails = "$leadSentence\n$durationGuidance"

        return RainNowcastSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails
        )
    }

    fun generateSevereAlertSummary(
        alert: SevereAlertInfo,
        locationName: String? = null
    ): SevereStormSummary {
        val title = if (!locationName.isNullOrBlank()) {
            "Severe weather • $locationName"
        } else {
            "Severe weather alert"
        }

        val cleanHeadline = normalizeAlertHeadline(alert.title)
        val cleanSubtitle = alert.subtitle.trim().removeSuffix(".")
        val isOfficialHeadline = alert.officialAlerts.isNotEmpty() &&
            !alert.sourceLabel.isNullOrBlank() &&
            alert.sourceLabel != alert.liveSatellite?.sourceLabel
        val isThunderstormHeadline = cleanHeadline.contains("thunder", ignoreCase = true)

        if (isOfficialHeadline && !isThunderstormHeadline) {
            val topOfficial = alert.officialAlerts.first()
            val detailBody = cleanSubtitle.ifBlank {
                listOfNotNull(
                    topOfficial.areaName.takeIf { it.isNotBlank() }?.let { "Area: $it" },
                    topOfficial.expiresLabel.takeIf { it.isNotBlank() }?.let { "Active until $it" }
                ).joinToString(". ").ifBlank { "Issued by ${alert.sourceLabel}" }
            }
            val conciseText = "$cleanHeadline: $detailBody."
            val expandedDetails = buildString {
                append("$cleanHeadline: $detailBody.")
                append("\nSource: ${alert.sourceLabel}")
                if (topOfficial.instruction.isNotBlank()) {
                    append("\n${topOfficial.instruction.trim()}")
                }
            }
            return SevereStormSummary(
                title = title,
                conciseText = conciseText,
                expandedDetails = expandedDetails
            )
        }

        val hasTypeSuffix = cleanHeadline.endsWith("Watch", ignoreCase = true) ||
            cleanHeadline.endsWith("Warning", ignoreCase = true) ||
            cleanHeadline.endsWith("Alert", ignoreCase = true) ||
            cleanHeadline.endsWith("Risk", ignoreCase = true)
        val prefix = if (hasTypeSuffix) cleanHeadline else "$cleanHeadline watch"
        val peakLabel = alert.peakHourLabel?.trim()
        val liveSatSummary = alert.liveSatellite
            ?.takeIf { it.hasActiveStormOrLightning }
            ?.formatCompactSummary()

        val topOfficial = alert.officialAlerts.firstOrNull()
        val fallbackOfficialBody = cleanSubtitle.ifBlank {
            listOfNotNull(
                topOfficial?.areaName?.takeIf { it.isNotBlank() }?.let { "Area: $it" },
                topOfficial?.expiresLabel?.takeIf { it.isNotBlank() }?.let { "Active until $it" }
            ).joinToString(". ").ifBlank {
                alert.sourceLabel?.takeIf { it.isNotBlank() }?.let { "Issued by $it" }.orEmpty()
            }
        }
        val conciseText = when {
            !liveSatSummary.isNullOrBlank() ->
                "$cleanHeadline: $liveSatSummary (${alert.liveSatellite.sourceLabel})."
            isOfficialHeadline ->
                if (fallbackOfficialBody.isNotBlank()) "$cleanHeadline: $fallbackOfficialBody." else "$cleanHeadline."
            peakLabel.isNullOrBlank() ->
                if (cleanSubtitle.isNotBlank()) "$cleanHeadline: $cleanSubtitle." else "$cleanHeadline."
            peakLabel.equals("Now", ignoreCase = true) ->
                "$cleanHeadline active right now (${alert.thunderstormProbability}% risk)."
            peakLabel.startsWith("Tomorrow", ignoreCase = true) -> {
                val timePart = peakLabel.substringAfter(" ").trim()
                "$prefix peaking tomorrow around $timePart (${alert.thunderstormProbability}% risk)."
            }
            else ->
                "$prefix peaking around $peakLabel (${alert.thunderstormProbability}% risk)."
        }

        val expandedDetails = buildString {
            val leadBody = if (isOfficialHeadline) fallbackOfficialBody else cleanSubtitle
            append(if (leadBody.isNotBlank()) "$cleanHeadline: $leadBody." else "$cleanHeadline.")
            if (!liveSatSummary.isNullOrBlank()) {
                append("\nLive Satellite: $liveSatSummary (${alert.liveSatellite?.sourceLabel ?: "Meteosat Live"})")
            }
            if (isOfficialHeadline) {
                append("\nSource: ${alert.sourceLabel}")
                topOfficial?.instruction?.takeIf { it.isNotBlank() }?.let {
                    append("\n${it.trim()}")
                }
            } else if (!alert.subtitle.contains("${alert.thunderstormProbability}%")) {
                append("\nThunderstorm probability: ${alert.thunderstormProbability}%")
            }
        }

        return SevereStormSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails
        )
    }

    fun generateHeatWarningSummary(
        today: DailyItem,
        hero: HeroWeather? = null,
        locationName: String? = null
    ): HeatWarningSummary {
        val effectiveMax = maxOf(today.maxTemp, hero?.tempDegrees ?: today.maxTemp)
        val effectiveFeelsLike = maxOf(today.feelsLikeMaxTemp ?: today.maxTemp, hero?.feelsLikeDegrees ?: today.maxTemp)
        val isExtremeHeat = effectiveMax >= 35 || effectiveFeelsLike >= 40
        val isTropicalNight = today.minTemp >= 20

        val baseTitle = when {
            isExtremeHeat -> "Extreme Heat"
            else -> "Tropical Night"
        }
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = when {
            isExtremeHeat && isTropicalNight ->
                "Dangerous high of ${effectiveMax}°C (feels like ${effectiveFeelsLike}°C) with low staying at ${today.minTemp}°C."
            isExtremeHeat ->
                "Dangerous high of ${effectiveMax}°C (feels like ${effectiveFeelsLike}°C). Stay hydrated."
            else ->
                "Tropical night ahead: Temperature will stay at or above ${today.minTemp}°C tonight."
        }

        val expandedDetails = buildString {
            append("High: ${effectiveMax}°C • Feels like: ${effectiveFeelsLike}°C • Low: ${today.minTemp}°C.\n")
            if (isExtremeHeat) {
                append("Extreme heat stress risk. Drink plenty of water and avoid strenuous outdoor exercise during afternoon hours.")
            } else {
                append("Overnight heat retention. Keep indoor spaces ventilated or cooled.")
            }
        }

        return HeatWarningSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            isTropicalNight = isTropicalNight,
            isExtremeHeat = isExtremeHeat
        )
    }

    fun generateFreezeHazardSummary(
        today: DailyItem,
        telemetry: BentoTelemetry? = null,
        locationName: String? = null
    ): FreezeHazardSummary {
        val isHardFreeze = today.minTemp <= -4
        val isWetSurface = today.precipitationMm > 0.0 || today.rainProbability >= 30 || (telemetry != null && telemetry.precipitationMm > 0.0)
        val isBlackIce = isWetSurface && today.minTemp <= 2

        val baseTitle = when {
            isBlackIce -> "Black Ice Risk"
            isHardFreeze -> "Hard Freeze"
            else -> "Frost & Freeze"
        }
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = when {
            isBlackIce && today.minTemp <= 0 -> "Wet roads and freezing temperatures (${today.minTemp}°C) create severe black ice hazard."
            isBlackIce -> "Wet roads and freezing surface conditions create severe black ice hazard."
            isHardFreeze -> "Hard freeze expected with low plunging to ${today.minTemp}°C."
            else -> "Frost warning: Overnight low dropping to ${today.minTemp}°C."
        }

        val expandedDetails = when {
            isBlackIce ->
                "Invisible black ice risk on highways, bridges, and shaded paths.\nLow: ${today.minTemp}°C. Reduce driving speed and increase braking distance."
            isHardFreeze ->
                "Hard freeze warning (Low: ${today.minTemp}°C).\nProtect vulnerable plumbing, bring outdoor pets inside, and shield sensitive plants."
            else ->
                "Sub-zero temperatures expected overnight (Low: ${today.minTemp}°C).\nCover frost-sensitive vegetation and check vehicle antifreeze."
        }

        return FreezeHazardSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            isBlackIce = isBlackIce,
            isHardFreeze = isHardFreeze
        )
    }

    fun generateWindAlertSummary(
        telemetry: BentoTelemetry,
        locationName: String? = null
    ): WindAlertSummary {
        val speed = telemetry.windSpeedKmH
        val gust = maxOf(telemetry.windGustKmH, speed)
        val isSevereGale = gust >= 85 || speed >= 75

        val baseTitle = if (isSevereGale) "Severe Wind Alert" else "High Wind Alert"
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = "High winds: Sustained ${speed} km/h with gusts up to ${gust} km/h."
        val expandedDetails = "Wind: ${speed} km/h (${telemetry.windDirection}) • Gusts: ${gust} km/h.\nSecure loose outdoor objects, patio furniture, and take extra caution with high-profile vehicles."

        return WindAlertSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            isSevereGale = isSevereGale
        )
    }

    fun generateAqiAlertSummary(
        telemetry: BentoTelemetry,
        locationName: String? = null
    ): AqiAlertSummary {
        val aqi = telemetry.aqiValue
        val pm25 = telemetry.pm25
        val isUnhealthy = aqi > 150 || pm25 > 55.4

        val baseTitle = if (isUnhealthy) "Poor Air Quality" else "Air Quality Alert"
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = "Air quality index is $aqi (${telemetry.aqiCategory}) with PM2.5 at ${pm25} µg/m³."
        val expandedDetails = "AQI: $aqi (${telemetry.aqiCategory}) • PM2.5: ${pm25} µg/m³.\nSensitive individuals and outdoor runners should reduce strenuous outdoor exertion and keep windows closed."

        return AqiAlertSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            isUnhealthy = isUnhealthy
        )
    }

    fun generateFogAlertSummary(
        telemetry: BentoTelemetry,
        locationName: String? = null
    ): FogAlertSummary {
        val vis = telemetry.visibilityKm
        val hum = telemetry.humidityPercent
        val baseTitle = "Dense Fog"
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = "Visibility down to ${vis} km in dense fog (humidity ${hum}%)."
        val expandedDetails = "Visibility: ${vis} km • Humidity: ${hum}%.\nDense fog significantly impedes roadway visibility. Use low-beam headlights and slow down."

        return FogAlertSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails
        )
    }

    fun generateRainCessationSummary(
        nowcast: PrecipitationNowcast,
        locationName: String? = null
    ): RainCessationSummary {
        val minutes = nowcast.rainCessationMinutes ?: 15
        val kind = resolveNowcastPrecipKind(nowcast)
        val baseTitle = when (kind) {
            PrecipKind.SNOW -> "Snow Clearing Soon"
            PrecipKind.ICE -> "Ice Clearing Soon"
            PrecipKind.RAIN -> "Rain Clearing Soon"
        }
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = when (kind) {
            PrecipKind.SNOW -> "Snow stopping in ~$minutes min — skies clearing soon."
            PrecipKind.ICE -> "Freezing rain stopping in ~$minutes min — conditions easing soon."
            PrecipKind.RAIN -> "Rain stopping in ~$minutes min — skies drying out soon."
        }
        val expandedDetails = when (kind) {
            PrecipKind.SNOW -> "Precipitation is clearing up. Expect snow to stop in approximately $minutes minutes with calmer conditions ahead."
            PrecipKind.ICE -> "Precipitation is clearing up. Expect freezing rain to stop in approximately $minutes minutes; watch for lingering ice."
            PrecipKind.RAIN -> "Precipitation is clearing up. Expect rain to stop in approximately $minutes minutes with drier conditions ahead."
        }

        return RainCessationSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            cessationMinutes = minutes
        )
    }

    fun generateUvAlertSummary(
        today: DailyItem,
        telemetry: BentoTelemetry? = null,
        locationName: String? = null
    ): UvAlertSummary {
        val uv = maxOf(today.uvIndex, telemetry?.uvIndex ?: 0, telemetry?.maxUvIndex ?: 0)
        val isExtreme = uv >= 11
        val baseTitle = if (isExtreme) "Extreme UV Alert" else "High UV Alert"
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = "Peak UV index reaches $uv today. Solar protection recommended."
        val expandedDetails = "UV Index: $uv (${today.uvCategory}).\nIntense solar radiation between 11:00 AM and 3:00 PM. Seek shade, apply SPF 30+ sunscreen, wear UV-protective eyewear."

        return UvAlertSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            isExtreme = isExtreme
        )
    }

    fun generateTempDropSummary(
        today: DailyItem,
        tomorrow: DailyItem,
        locationName: String? = null
    ): TempDropSummary {
        val diff = tomorrow.maxTemp - today.maxTemp
        val drop = abs(diff)
        val baseTitle = "Cold Front Tomorrow"
        val title = if (!locationName.isNullOrBlank()) "$baseTitle • $locationName" else baseTitle

        val conciseText = "Tomorrow drops by ${drop}°C to a high of ${tomorrow.maxTemp}°C (vs ${today.maxTemp}°C today)."
        val expandedDetails = "A sharp cold front will lower temperatures by ${drop}°C tomorrow.\nToday's High: ${today.maxTemp}°C -> Tomorrow's High: ${tomorrow.maxTemp}°C (Low: ${tomorrow.minTemp}°C). Prepare warmer layers."

        return TempDropSummary(
            title = title,
            conciseText = conciseText,
            expandedDetails = expandedDetails,
            dropDegrees = drop
        )
    }

    internal fun isMeaningfulRainHour(item: HourlyItem): Boolean {
        return item.rainProbability >= 50 ||
            (item.rainProbability >= 40 && item.precipitationMm >= 0.5) ||
            item.precipitationMm >= 1.0
    }

    internal fun parseHourOfDay(hourLabel: String): Int? {
        val clean = hourLabel.trim()
        if (clean.isEmpty() || clean.equals("Now", ignoreCase = true)) return null
        if (clean.startsWith("Tomorrow", ignoreCase = true)) {
            val sub = clean.substringAfter(" ").trim()
            return parseHourOfDay(sub)?.plus(24) ?: 24
        }
        // Match 24-hour format e.g. "14:00" or "03:00"
        val colonParts = clean.split(":")
        if (colonParts.size == 2 && !clean.contains("am", ignoreCase = true) && !clean.contains("pm", ignoreCase = true)) {
            return colonParts[0].toIntOrNull()?.takeIf { it in 0..23 }
        }
        // Match 12-hour format e.g. "3 PM", "10 AM", "4:30 PM"
        val upper = clean.uppercase(Locale.US)
        val isPm = upper.endsWith("PM")
        val isAm = upper.endsWith("AM")
        if (isAm || isPm) {
            val numberPart = upper.removeSuffix("AM").removeSuffix("PM").trim().substringBefore(":")
            val hour12 = numberPart.toIntOrNull() ?: return null
            if (hour12 !in 1..12) return null
            return when {
                isAm && hour12 == 12 -> 0
                isAm -> hour12
                isPm && hour12 == 12 -> 12
                else -> hour12 + 12
            }
        }
        return null
    }

    internal fun formatClockLabel(rawLabel: String): String {
        val clean = rawLabel.trim()
        if (clean.startsWith("Tomorrow", ignoreCase = true)) {
            return "tomorrow at " + clean.substringAfter(" ").trim().lowercase(Locale.getDefault())
        }
        return clean.lowercase(Locale.getDefault())
    }

    internal fun formatHourLabelForProse(rawLabel: String): String {
        val clean = rawLabel.trim()
        return when {
            clean.equals("Now", ignoreCase = true) -> "right now"
            clean.startsWith("Tomorrow", ignoreCase = true) ->
                "tomorrow around " + clean.substringAfter(" ").trim().lowercase(Locale.getDefault())
            else -> "around " + clean.lowercase(Locale.getDefault())
        }
    }

    internal fun normalizeAlertHeadline(rawTitle: String): String {
        val trimmed = rawTitle.trim()
        if (trimmed.isEmpty()) return "Severe Weather Alert"
        // Convert screaming ALL-CAPS mapper titles ("THUNDERSTORM WATCH") to natural Title Case
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isNotEmpty() && letters.all { it.isUpperCase() }) {
            return trimmed.lowercase(Locale.getDefault())
                .split(Regex("\\s+"))
                .joinToString(" ") { word ->
                    word.replaceFirstChar { ch -> ch.titlecase(Locale.getDefault()) }
                }
        }
        return trimmed
    }

    private enum class PrecipKind { RAIN, SNOW, ICE }

    private fun resolveNowcastPrecipKind(nowcast: PrecipitationNowcast): PrecipKind {
        val label = nowcast.precipitationLabel?.lowercase(Locale.US).orEmpty()
        val headline = nowcast.headlineLabel.lowercase(Locale.US)
        val summary = nowcast.summaryText.lowercase(Locale.US)
        return when {
            label.contains("snow") || headline.contains("snow") || summary.startsWith("snow") -> PrecipKind.SNOW
            label.contains("freezing") || label.contains("ice") || headline.contains("ice") || summary.startsWith("freezing") -> PrecipKind.ICE
            else -> PrecipKind.RAIN
        }
    }

    private fun precipLabelFor(precipType: String?, conditionText: String, maxTempC: Int): String {
        val upperType = precipType?.trim()?.uppercase(Locale.US).orEmpty()
        val lowerCond = conditionText.lowercase(Locale.US)
        return when {
            upperType == "SNOW" || lowerCond.contains("snow") || lowerCond.contains("blizzard") || lowerCond.contains("flurr") -> "Snow"
            upperType == "ICE" || lowerCond.contains("freezing") || lowerCond.contains("sleet") || lowerCond.contains("ice") -> "Ice"
            maxTempC <= 0 && lowerCond.contains("precip") -> "Snow"
            else -> "Rain"
        }
    }

    private fun resolveRunPrecipNoun(
        run: List<HourlyItem>,
        dayPrecipType: String?,
        dayConditionText: String
    ): String {
        val anySnow = run.any { h ->
            val c = h.conditionText.lowercase(Locale.US)
            c.contains("snow") || c.contains("blizzard") || c.contains("flurr") || c.contains("sleet") || h.tempDegrees <= 0
        } || dayPrecipType.equals("SNOW", ignoreCase = true) ||
            dayConditionText.contains("snow", ignoreCase = true)
        if (anySnow) return "snow"

        val anyIce = run.any { h ->
            val c = h.conditionText.lowercase(Locale.US)
            c.contains("freezing") || c.contains("ice")
        } || dayPrecipType.equals("ICE", ignoreCase = true) ||
            dayConditionText.contains("freezing", ignoreCase = true)
        if (anyIce) return "freezing rain"

        return "rain"
    }

    private fun formatCondition(
        rawCondition: String,
        rainProbability: Int,
        precipType: String? = null,
        maxTempC: Int? = null
    ): String {
        val lower = rawCondition.lowercase(Locale.getDefault()).trim()
        return when {
            lower.contains("thunder") || lower.contains("storm") -> "thunderstorms"
            lower.contains("freezing") || lower.contains("sleet") || lower.contains("ice") -> "freezing rain"
            lower.contains("snow") || lower.contains("flurr") || lower.contains("blizzard") -> "snow"
            lower.contains("drizzle") -> "light drizzle"
            lower.contains("shower") -> "showers"
            lower.contains("rain") -> "rain"
            rainProbability >= 60 -> when {
                precipType.equals("SNOW", ignoreCase = true) || (maxTempC != null && maxTempC <= 0) -> "snow likely"
                precipType.equals("ICE", ignoreCase = true) -> "freezing rain likely"
                else -> "rain likely"
            }
            lower.contains("overcast") -> "overcast skies"
            lower.contains("part") || lower.contains("scatter") -> "cloud and sun"
            lower.contains("cloud") -> "cloud"
            lower.contains("clear") || lower.contains("sun") -> "clear skies"
            lower.contains("fog") || lower.contains("mist") || lower.contains("haze") -> "mist"
            lower.contains("wind") -> "breezy conditions"
            lower.isNotBlank() -> lower
            else -> "clear skies"
        }
    }
}
