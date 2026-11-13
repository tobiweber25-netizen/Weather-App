package com.google.weatherapp.data.repository

import com.google.weatherapp.data.model.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import com.google.weatherapp.data.api.*

/** Pure mapping and calculation helpers that turn raw API responses into dashboard models. */
internal object WeatherMapper {
    internal fun mapEnsembleDashboardData(
        current: CurrentConditionsResponse,
        hourly: HourlyForecastResponse,
        daily: DailyForecastResponse,
        omForecast: OpenMeteoForecastResponse?,
        omAqi: OpenMeteoAirQualityResponse?,
        resolvedLocationName: String? = null,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ): WeatherDashboardData {
        val timezoneId = current.timeZone?.id ?: omForecast?.timezone
        val zoneId = resolveZoneId(timezoneId)
        val isDay = current.isDaytime ?: (omForecast?.current?.isDay == 1)
        val condType = current.weatherCondition?.type.orEmpty()
        val descText = current.weatherCondition?.description?.text
            ?: omForecast?.current?.weatherCode?.let { mapWmoCodeToCondition(it, isDay) }
            ?: "Clear"

        val isClearOrSunny = condType.contains("CLEAR", ignoreCase = true) ||
            condType.contains("SUN", ignoreCase = true) ||
            descText.contains("clear", ignoreCase = true) ||
            descText.contains("sun", ignoreCase = true)

        val skyTheme = when {
            condType.contains("THUNDER", ignoreCase = true) || (!isClearOrSunny && (current.thunderstormProbability ?: 0) >= 40) ->
                SkyThemeType.THUNDERSTORM
            condType.contains("SNOW", ignoreCase = true) || condType.contains("SLEET", ignoreCase = true) || condType.contains("BLIZZARD", ignoreCase = true) || condType.contains("ICE", ignoreCase = true) ||
                descText.contains("snow", ignoreCase = true) || descText.contains("sleet", ignoreCase = true) || descText.contains("blizzard", ignoreCase = true) ->
                SkyThemeType.SNOW
            condType.contains("RAIN", ignoreCase = true) || condType.contains("DRIZZLE", ignoreCase = true) ||
                descText.contains("rain", ignoreCase = true) || descText.contains("drizzle", ignoreCase = true) ->
                SkyThemeType.RAIN
            condType.contains("CLOUD", ignoreCase = true) || condType.contains("OVERCAST", ignoreCase = true) ||
                descText.contains("cloud", ignoreCase = true) || descText.contains("overcast", ignoreCase = true) ->
                SkyThemeType.OVERCAST
            !isDay -> SkyThemeType.CLEAR_NIGHT
            else -> SkyThemeType.CLEAR_DAY
        }

        // Blend current temperature between Google and Open-Meteo with equal ensemble weighting
        val gTemp = current.temperature?.degrees
        val omTemp = omForecast?.current?.temperature2m
        val blendedCurrentTemp = blendTemperatures(gTemp, omTemp)
            ?: gTemp?.roundToInt()
            ?: omTemp?.roundToInt()
            ?: 20

        val gFeels = current.feelsLikeTemperature?.degrees
        val omFeels = omForecast?.current?.apparentTemperature
        val blendedFeelsTemp = blendTemperatures(gFeels, omFeels) ?: blendedCurrentTemp

        val todayForecast = daily.forecastDays?.firstOrNull()
        val todayLocalDate = todayForecast?.displayDate?.let {
            try {
                LocalDate.of(it.year ?: LocalDate.now(zoneId).year, it.month ?: 1, it.day ?: 1)
            } catch (_: Exception) {
                null
            }
        }
        val todayOmIdx = todayLocalDate?.toString()?.let { iso ->
            omForecast?.daily?.time?.indexOf(iso)?.takeIf { it >= 0 }
        } ?: 0

        val gHigh = todayForecast?.maxTemperature?.degrees
        val omHigh = omForecast?.daily?.temperature2mMax?.getOrNull(todayOmIdx)
        val rawHighTemp = blendTemperatures(gHigh, omHigh) ?: blendedCurrentTemp

        val gLow = todayForecast?.minTemperature?.degrees
        val omLow = omForecast?.daily?.temperature2mMin?.getOrNull(todayOmIdx)
        val rawLowTemp = blendTemperatures(gLow, omLow) ?: blendedCurrentTemp

        val tempSpread = if (gTemp != null && omTemp != null) abs(gTemp - omTemp) else 0.0
        val gTodayPop = todayForecast?.daytimeForecast?.precipitation?.probability?.percent
        val omTodayPop = omForecast?.daily?.precipitationProbabilityMax?.getOrNull(todayOmIdx)
        val popSpread = if (gTodayPop != null && omTodayPop != null) abs(gTodayPop - omTodayPop) else 0

        val ensemble = computeEnsembleDispersion(blendedCurrentTemp, gTemp, omTemp, gHigh, omHigh, gTodayPop, omTodayPop)
        val heroConfidence = ensemble.confidencePercent
        val modelLabel = when {
            gTemp != null && omTemp != null -> "Google + Open-Meteo Ensemble"
            gTemp != null -> "Google Weather"
            omForecast != null -> "Open-Meteo High-Res"
            else -> "Cached Weather"
        }
        val nowcast = parsePrecipitationNowcast(omForecast?.minutely15, zoneId)

        val locationName = resolvedLocationName?.takeIf { it.isNotBlank() }
            ?: timezoneId?.substringAfterLast("/")?.replace("_", " ")
            ?: "Current Location"

        val humidity = current.relativeHumidity ?: omForecast?.current?.relativeHumidity2m ?: 50
        val windSpeed = current.wind?.speed?.value?.roundToInt()
            ?: omForecast?.current?.windSpeed10m?.roundToInt()
            ?: 0
        val feelsReason = buildFeelsLikeReason(blendedCurrentTemp, blendedFeelsTemp, humidity, windSpeed)

        // Build Open-Meteo 240-hour lookup table by "YYYY-MM-DDTHH:00"
        val omHourlyByDateHour = parseOpenMeteoHourlyMap(omForecast?.hourly)

        // Hourly forecast processing (24 hours, with Open-Meteo ensemble blending or fallback)
        val nowZoned = ZonedDateTime.now(zoneId)
        val currentLocalDateHour = nowZoned.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val currentLocalHour = nowZoned.hour
        val gHours = hourly.forecastHours.orEmpty()
        val futureGHours = gHours.filter { h ->
            val dt = h.displayDateTime
            if (dt?.year != null && dt.month != null && dt.day != null && dt.hours != null) {
                try {
                    !LocalDateTime.of(dt.year, dt.month, dt.day, dt.hours, 0).isBefore(currentLocalDateHour)
                } catch (_: Exception) {
                    true
                }
            } else {
                true
            }
        }

        val hourlyItems: List<HourlyItem> = if (futureGHours.isNotEmpty()) {
            futureGHours.take(24).mapIndexed { idx, item ->
                val dt = item.displayDateTime
                val key = if (dt?.year != null && dt.month != null && dt.day != null && dt.hours != null) {
                    String.format(Locale.US, "%04d-%02d-%02dT%02d:00", dt.year, dt.month, dt.day, dt.hours)
                } else null
                val omMatch = key?.let { omHourlyByDateHour[it] }

                val hourStr = if (idx == 0) {
                    "Now"
                } else {
                    dt?.hours?.let { String.format(Locale.getDefault(), "%02d:00", it) } ?: "—"
                }

                val hTemp = if (idx == 0) {
                    blendedCurrentTemp
                } else {
                    blendTemperatures(item.temperature?.degrees, omMatch?.temp) ?: blendedCurrentTemp
                }

                val gPop = item.precipitation?.probability?.percent
                val blendedHourPop = if (gPop != null && omMatch?.pop != null) {
                    maxOf(gPop, omMatch.pop)
                } else {
                    gPop ?: omMatch?.pop ?: 0
                }
                val hPop = if (idx == 0) {
                    current.precipitation?.probability?.percent ?: blendedHourPop
                } else {
                    blendedHourPop
                }

                val gPrecip = item.precipitation?.qpf?.quantity ?: 0.0
                val omPrecip = omMatch?.precipMm ?: 0.0
                val blendedHourPrecip = maxOf(gPrecip, omPrecip)
                val hPrecipMm = if (idx == 0) {
                    current.precipitation?.qpf?.quantity ?: blendedHourPrecip
                } else {
                    blendedHourPrecip
                }

                val hourConditionText = if (idx == 0) {
                    descText
                } else {
                    item.weatherCondition?.description?.text
                        ?: item.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
                        ?: omMatch?.conditionText
                        ?: descText
                }

                val hourIsDay = if (idx == 0) isDay else (item.isDaytime ?: omMatch?.isDay ?: true)
                val hourIcon = if (idx == 0) current.weatherCondition?.iconBaseUri else item.weatherCondition?.iconBaseUri
                val hWindSpeed = if (idx == 0) windSpeed else (item.wind?.speed?.value?.roundToInt() ?: omMatch?.windSpeed ?: 0)
                val hUv = if (idx == 0) (current.uvIndex ?: 0) else (item.uvIndex ?: omMatch?.uvIndex ?: 0)
                val hHumidity = if (idx == 0) humidity else (item.relativeHumidity ?: omMatch?.humidity ?: 0)
                val gStormProb = item.thunderstormProbability ?: (
                    if (item.weatherCondition?.type?.contains("THUNDER", ignoreCase = true) == true ||
                        item.weatherCondition?.description?.text?.contains("thunder", ignoreCase = true) == true
                    ) {
                        maxOf(item.precipitation?.probability?.percent ?: 0, 40)
                    } else {
                        0
                    }
                )
                val omStormProb = omMatch?.stormProb ?: 0
                val hStormProb = if (idx == 0) {
                    maxOf(current.thunderstormProbability ?: 0, omStormProb)
                } else {
                    maxOf(gStormProb, omStormProb)
                }

                HourlyItem(
                    hourLabel = hourStr,
                    tempDegrees = hTemp,
                    rainProbability = hPop,
                    iconUri = hourIcon,
                    isDaytime = hourIsDay,
                    conditionText = hourConditionText,
                    windSpeedKmH = hWindSpeed,
                    precipitationMm = roundOneDecimal(hPrecipMm),
                    uvIndex = hUv,
                    humidityPercent = hHumidity,
                    thunderstormProbability = hStormProb
                )
            }
        } else {
            extractNext24HoursFromOpenMeteo(omForecast, zoneId).mapIndexed { idx, item ->
                if (idx == 0) {
                    item.copy(
                        tempDegrees = blendedCurrentTemp,
                        rainProbability = current.precipitation?.probability?.percent ?: item.rainProbability,
                        precipitationMm = roundOneDecimal(current.precipitation?.qpf?.quantity ?: item.precipitationMm),
                        conditionText = descText,
                        isDaytime = isDay,
                        iconUri = current.weatherCondition?.iconBaseUri,
                        windSpeedKmH = windSpeed,
                        uvIndex = current.uvIndex ?: item.uvIndex,
                        humidityPercent = humidity,
                        thunderstormProbability = maxOf(current.thunderstormProbability ?: 0, item.thunderstormProbability)
                    )
                } else {
                    item
                }
            }
        }

        // Severe weather & thunderstorm risk pill calculation (24-hour storm scanner)
        val next24Hours = futureGHours.take(24)
        val hourlyStormList = hourlyItems.map { it.thunderstormProbability }

        val currentStormProb = current.thunderstormProbability ?: 0
        // Do not let an expired daytime forecast (7 AM - 7 PM) bleed into evening/night storm risk
        val dayStormProb = if (currentLocalHour < 18) {
            todayForecast?.daytimeForecast?.thunderstormProbability
                ?: todayForecast?.nighttimeForecast?.thunderstormProbability
                ?: 0
        } else {
            todayForecast?.nighttimeForecast?.thunderstormProbability ?: 0
        }

        fun qualifyStormHourLabel(idx: Int, rawLabel: String?): String? {
            if (rawLabel.isNullOrBlank() || idx <= 0 || rawLabel.equals("Now", ignoreCase = true)) return rawLabel
            return if (currentLocalHour + idx >= 24 && !rawLabel.startsWith("Tomorrow", ignoreCase = true)) {
                "Tomorrow $rawLabel"
            } else {
                rawLabel
            }
        }

        val peakIdx = hourlyStormList.indices.maxByOrNull { hourlyStormList[it] } ?: -1
        val peakHourlyProb = if (peakIdx >= 0) hourlyStormList[peakIdx] else 0
        val peakHourTimeStr = if (peakIdx > 0 && peakHourlyProb > 0) {
            qualifyStormHourLabel(peakIdx, hourlyItems.getOrNull(peakIdx)?.hourLabel)
        } else if (peakIdx == 0 && peakHourlyProb > 0) {
            "Now"
        } else {
            null
        }

        val firstOnsetIdx = hourlyStormList.indexOfFirst { it >= 30 }
        val onsetTimeStr = if (firstOnsetIdx > 0) {
            qualifyStormHourLabel(firstOnsetIdx, hourlyItems.getOrNull(firstOnsetIdx)?.hourLabel)
        } else null

        val stormProb = maxOf(currentStormProb, peakHourlyProb, dayStormProb)
        val maxGustNext24 = next24Hours.maxOfOrNull { it.wind?.gust?.value?.roundToInt() ?: it.wind?.speed?.value?.roundToInt() ?: 0 }
            ?: (current.wind?.gust?.value?.roundToInt() ?: 0)
        val totalPrecipNext24 = hourlyItems.sumOf { it.precipitationMm }

        fun formatStormWindowTime(label: String): String {
            return if (label.startsWith("Tomorrow ", ignoreCase = true)) {
                "tomorrow around " + label.substringAfter(" ")
            } else {
                "around $label"
            }
        }

        val timingClause = when {
            onsetTimeStr != null && peakHourTimeStr != null && onsetTimeStr != peakHourTimeStr ->
                "Storm window opens ${formatStormWindowTime(onsetTimeStr)}, peaking ${formatStormWindowTime(peakHourTimeStr)}."
            peakHourTimeStr != null && peakHourTimeStr != "Now" ->
                "Peak activity expected ${formatStormWindowTime(peakHourTimeStr)}."
            peakHourTimeStr == "Now" ->
                "Active storm conditions in your current window."
            else ->
                "Scanned next 24h forecast window."
        }

        val telemetryClause = buildList {
            if (totalPrecipNext24 > 0.0) add(String.format(Locale.getDefault(), "%.1f mm rain", totalPrecipNext24))
            if (maxGustNext24 > 0) add("gusts up to $maxGustNext24 km/h")
        }.joinToString(" • ")

        val extraDetails = if (telemetryClause.isNotBlank()) " ($telemetryClause)." else ""

        val (riskLevel, alertTitle, alertDesc) = when {
            stormProb >= 60 || condType.contains("THUNDER", ignoreCase = true) || condType.contains("STORM", ignoreCase = true) -> Triple(
                SeverityRiskLevel.SEVERE,
                "SEVERE THUNDERSTORM WARNING",
                "High storm risk ($stormProb% peak probability). $timingClause$extraDetails"
            )
            stormProb >= 30 -> Triple(
                SeverityRiskLevel.HIGH,
                "THUNDERSTORM WATCH",
                "Elevated storm risk ($stormProb% peak probability). $timingClause$extraDetails"
            )
            stormProb >= 10 -> Triple(
                SeverityRiskLevel.MODERATE,
                "ISOLATED THUNDERSTORM RISK",
                "Localized storm potential ($stormProb% peak probability). $timingClause$extraDetails"
            )
            else -> Triple(
                SeverityRiskLevel.LOW,
                "THUNDERSTORM TRACKER: CLEAR",
                "No significant thunderstorm cells detected in the next 24 hours ($stormProb% peak probability).$extraDetails"
            )
        }

        val alert = SevereAlertInfo(
            riskLevel = riskLevel,
            thunderstormProbability = stormProb,
            title = alertTitle,
            subtitle = alertDesc,
            isVisible = true,
            peakHourLabel = peakHourTimeStr,
            hourlyStormProbabilities = hourlyStormList
        )

        // Daily forecast processing (up to 10 days with Open-Meteo ensemble blending & 24h hourly backfill)
        val omDailyTimes = omForecast?.daily?.time.orEmpty()
        val dailyItems = (daily.forecastDays ?: emptyList()).mapIndexed { idx, day ->
            val localDate = day.displayDate?.let {
                try {
                    LocalDate.of(it.year ?: LocalDate.now(zoneId).year, it.month ?: 1, it.day ?: 1)
                } catch (_: Exception) {
                    null
                }
            } ?: LocalDate.now(zoneId).plusDays(idx.toLong())
            val omIdx = localDate.toString().let { iso ->
                omDailyTimes.indexOf(iso).takeIf { it >= 0 }
            } ?: idx

            val dayLabel = when (idx) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> localDate?.dayOfWeek?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "Day ${idx + 1}"
            }

            val dateText = localDate?.let { "${it.monthValue}/${it.dayOfMonth}" } ?: ""
            val fullDateStr = localDate?.let {
                val dayName = it.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                val monthName = it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
                "$dayName, $monthName ${it.dayOfMonth}"
            } ?: "$dayLabel $dateText"

            val dayPeriod = day.daytimeForecast
            val nightPeriod = day.nighttimeForecast
            val dayWind = dayPeriod?.wind
            val nightWind = nightPeriod?.wind

            val morningWindSpeed = dayWind?.speed?.value?.roundToInt()
                ?: omForecast?.daily?.windSpeed10mMax?.getOrNull(omIdx)?.roundToInt()
                ?: 0
            val morningWindDir = parseCardinal(
                dayWind?.direction?.cardinal
                    ?: omForecast?.daily?.windDirection10mDominant?.getOrNull(omIdx)?.let { degreesToCardinal(it) }
            )
            val morningWindStr = if (morningWindSpeed > 0 && morningWindDir != "—") {
                "AM: $morningWindSpeed km/h $morningWindDir"
            } else if (morningWindSpeed == 0) {
                "AM: Calm"
            } else {
                "AM: —"
            }

            val dayYear = day.displayDate?.year
            val dayMonth = day.displayDate?.month
            val dayDay = day.displayDate?.day
            val afternoonForecastHour = gHours.firstOrNull { hItem ->
                val dt = hItem.displayDateTime
                val matchesDay = if (dayYear != null && dayMonth != null && dayDay != null) {
                    dt?.year == dayYear && dt?.month == dayMonth && dt?.day == dayDay
                } else {
                    idx == 0
                }
                matchesDay && (dt?.hours in 12..17) && hItem.wind != null
            }
            val afternoonWind = afternoonForecastHour?.wind ?: dayWind ?: nightWind
            val afternoonWindSpeed = afternoonWind?.speed?.value?.roundToInt() ?: morningWindSpeed
            val afternoonWindDir = parseCardinal(afternoonWind?.direction?.cardinal ?: morningWindDir)
            val afternoonWindStr = if (afternoonWindSpeed > 0 && afternoonWindDir != "—") {
                "PM: $afternoonWindSpeed km/h $afternoonWindDir"
            } else if (afternoonWindSpeed == 0) {
                "PM: Calm"
            } else {
                "PM: —"
            }

            val uvVal = dayPeriod?.uvIndex
                ?: omForecast?.daily?.uvIndexMax?.getOrNull(omIdx)?.roundToInt()
                ?: current.uvIndex
                ?: 0
            val uvCat = categorizeUv(uvVal)

            // Combine daytime + nighttime QPF with Open-Meteo daily sum (taking upper bound for reliable precipitation planning)
            val gDayQpf = (dayPeriod?.precipitation?.qpf?.quantity ?: 0.0) +
                          (nightPeriod?.precipitation?.qpf?.quantity ?: 0.0)
            val omDayQpf = omForecast?.daily?.precipitationSum?.getOrNull(omIdx)
            val precipAmount = roundOneDecimal(
                when {
                    gDayQpf > 0.0 && omDayQpf != null -> maxOf(gDayQpf, omDayQpf)
                    gDayQpf > 0.0 -> gDayQpf
                    omDayQpf != null -> omDayQpf
                    else -> 0.0
                }
            )

            val dayStormRisk = dayPeriod?.thunderstormProbability
                ?: nightPeriod?.thunderstormProbability
                ?: 0

            val dayHumidity = dayPeriod?.relativeHumidity
                ?: nightPeriod?.relativeHumidity
                ?: current.relativeHumidity
                ?: 0

            val cloudCover = dayPeriod?.cloudCover
                ?: nightPeriod?.cloudCover
                ?: 0

            val daySunrise = day.sunEvents?.sunriseTime
                ?: (if (idx == 0) current.sunEvents?.sunriseTime else null)
                ?: omForecast?.daily?.sunrise?.getOrNull(omIdx)
            val daySunset = day.sunEvents?.sunsetTime
                ?: (if (idx == 0) current.sunEvents?.sunsetTime else null)
                ?: omForecast?.daily?.sunset?.getOrNull(omIdx)
            val sunriseStr = formatIsoTime(
                daySunrise,
                zoneId,
                fallback = "06:15"
            )
            val sunsetStr = formatIsoTime(
                daySunset,
                zoneId,
                fallback = "18:15"
            )

            val dayCond = dayPeriod?.weatherCondition?.description?.text
                ?: dayPeriod?.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: omForecast?.daily?.weatherCode?.getOrNull(omIdx)?.let { mapWmoCodeToCondition(it, true) }
                ?: descText
            val nightCond = nightPeriod?.weatherCondition?.description?.text
                ?: nightPeriod?.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: omForecast?.daily?.weatherCode?.getOrNull(omIdx)?.let { mapWmoCodeToCondition(it, false) }
                ?: "Clear"

            val gMax = day.maxTemperature?.degrees
            val omMax = omForecast?.daily?.temperature2mMax?.getOrNull(omIdx)
            val gMin = day.minTemperature?.degrees
            val omMin = omForecast?.daily?.temperature2mMin?.getOrNull(omIdx)

            val dayHourly = extractHourlyForDayWithBackfill(
                day = day,
                localDate = localDate,
                dayIdx = idx,
                allGoogleHours = gHours,
                omHourlyByDateHour = omHourlyByDateHour,
                todayHoursFallback = hourlyItems
            )
            val hourlyMax = dayHourly.maxOfOrNull { it.tempDegrees }
            val hourlyMin = dayHourly.minOfOrNull { it.tempDegrees }

            val rawBlendedMax = blendTemperatures(gMax, omMax) ?: hourlyMax ?: rawHighTemp
            val rawBlendedMin = blendTemperatures(gMin, omMin) ?: hourlyMin ?: rawLowTemp

            // Ensure daily high/low accurately bound the day's hourly temperatures (and current temp for Today)
            val candidateMax = listOfNotNull(
                rawBlendedMax,
                hourlyMax,
                if (idx == 0) blendedCurrentTemp else null
            ).maxOrNull() ?: rawBlendedMax
            val candidateMin = listOfNotNull(
                rawBlendedMin,
                hourlyMin,
                if (idx == 0) blendedCurrentTemp else null
            ).minOrNull() ?: rawBlendedMin

            val blendedMax = maxOf(candidateMax, candidateMin)
            val blendedMin = minOf(candidateMax, candidateMin)
            val daySpread = if (gMax != null && omMax != null) roundOneDecimal(abs(gMax - omMax)) else 0.7

            val gDayPop = maxOf(
                dayPeriod?.precipitation?.probability?.percent ?: 0,
                nightPeriod?.precipitation?.probability?.percent ?: 0
            )
            val omDayPop = omForecast?.daily?.precipitationProbabilityMax?.getOrNull(omIdx)
            val blendedDayPop = if (omDayPop != null) {
                maxOf(gDayPop, omDayPop)
            } else {
                gDayPop
            }
            val maxHourlyPop = dayHourly.maxOfOrNull { it.rainProbability } ?: 0
            val sumHourlyPrecip = dayHourly.sumOf { it.precipitationMm }
            val boundedDayPop = maxOf(blendedDayPop, maxHourlyPop)
            val boundedPrecipAmount = roundOneDecimal(maxOf(precipAmount, sumHourlyPrecip))

            val dayPopSpread = if (omDayPop != null) abs(gDayPop - omDayPop) else 8
            val dayConfidence = computeEnsembleConfidence(daySpread + idx * 0.15, dayPopSpread)

            val rawFeelsMax = blendTemperatures(
                day.feelsLikeMaxTemperature?.degrees,
                omForecast?.daily?.apparentTemperatureMax?.getOrNull(omIdx)
            ) ?: blendedMax
            val rawFeelsMin = blendTemperatures(
                day.feelsLikeMinTemperature?.degrees,
                omForecast?.daily?.apparentTemperatureMin?.getOrNull(omIdx)
            ) ?: blendedMin

            DailyItem(
                dayLabel = dayLabel,
                dateText = dateText,
                minTemp = blendedMin,
                maxTemp = blendedMax,
                rainProbability = boundedDayPop,
                iconUri = dayPeriod?.weatherCondition?.iconBaseUri,
                conditionText = dayCond,
                morningWind = morningWindStr,
                afternoonWind = afternoonWindStr,
                fullDateString = fullDateStr,
                feelsLikeMaxTemp = maxOf(rawFeelsMax, rawFeelsMin),
                feelsLikeMinTemp = minOf(rawFeelsMax, rawFeelsMin),
                precipitationMm = boundedPrecipAmount,
                uvIndex = uvVal,
                uvCategory = uvCat,
                humidityPercent = dayHumidity,
                thunderstormProbability = dayStormRisk,
                cloudCoverPercent = cloudCover,
                sunriseTime = sunriseStr,
                sunsetTime = sunsetStr,
                dayConditionText = dayCond,
                dayIconUri = dayPeriod?.weatherCondition?.iconBaseUri,
                nightConditionText = nightCond,
                nightIconUri = nightPeriod?.weatherCondition?.iconBaseUri,
                dayWindSpeedKmH = morningWindSpeed,
                dayWindDirection = morningWindDir,
                nightWindSpeedKmH = nightWind?.speed?.value?.roundToInt() ?: 0,
                nightWindDirection = nightWind?.direction?.cardinal ?: "—",
                hourlyForecast = alignHourlyExtremes(dayHourly, blendedMax, blendedMin),
                confidencePercent = dayConfidence,
                modelSpreadDegrees = daySpread
            )
        }

        val highTemp = dailyItems.firstOrNull()?.maxTemp ?: maxOf(rawHighTemp, rawLowTemp, blendedCurrentTemp)
        val lowTemp = dailyItems.firstOrNull()?.minTemp ?: minOf(rawHighTemp, rawLowTemp, blendedCurrentTemp)

        val todayHourly = dailyItems.firstOrNull()?.hourlyForecast.orEmpty()
        val calibratedHourlyItems = calibrateRollingHourlyPeak(
            hourlyList = hourlyItems,
            todayHourly = todayHourly,
            todayHigh = highTemp,
            currentHour = currentLocalHour
        )

        val hero = HeroWeather(
            tempDegrees = blendedCurrentTemp,
            feelsLikeDegrees = blendedFeelsTemp,
            highDegrees = highTemp,
            lowDegrees = lowTemp,
            conditionText = descText,
            iconUri = current.weatherCondition?.iconBaseUri,
            isDaytime = isDay,
            locationName = locationName,
            skyTheme = skyTheme,
            confidencePercent = heroConfidence,
            modelSourcesLabel = modelLabel,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            feelsLikeReason = feelsReason,
            tempP10 = ensemble.p10Temp,
            tempP90 = ensemble.p90Temp,
            dispersionSummary = ensemble.modelAgreementSummary
        )

        // Bento Telemetry Grid (with fixed 24h precipitation sum, real AQI, visibility, and solar progress)
        val windObj = current.wind
        val windDirRaw = windObj?.direction?.cardinal
            ?: omForecast?.current?.windDirection10m?.let { degreesToCardinal(it) }
        val windDir = parseCardinal(windDirRaw)
        val windDegrees = windObj?.direction?.degrees
            ?: omForecast?.current?.windDirection10m
            ?: cardinalToDegrees(windDir)
        val windGust = windObj?.gust?.value?.roundToInt()
            ?: omForecast?.current?.windGusts10m?.roundToInt()
            ?: windSpeed

        val todayTotalPrecipMm = roundOneDecimal(
            maxOf(
                dailyItems.firstOrNull()?.precipitationMm ?: 0.0,
                totalPrecipNext24,
                current.precipitation?.qpf?.quantity ?: 0.0
            )
        )

        val nowHourKey = ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US))
        val currentOmHourSlot = omHourlyByDateHour[nowHourKey]

        val uvVal = current.uvIndex
            ?: currentOmHourSlot?.uvIndex
            ?: hourlyItems.firstOrNull()?.uvIndex
            ?: 0
        val maxUv = todayForecast?.daytimeForecast?.uvIndex
            ?: omForecast?.daily?.uvIndexMax?.firstOrNull()?.roundToInt()
            ?: uvVal
        val uvCat = categorizeUv(uvVal)

        val dewPoint = current.dewPoint?.degrees?.roundToInt() ?: (blendedCurrentTemp - ((100 - humidity) / 5))
        val pressure = current.airPressure?.meanSeaLevelMillibars?.roundToInt()
            ?: omForecast?.current?.surfacePressure?.roundToInt()
            ?: 1013

        val sunEvents = todayForecast?.sunEvents ?: current.sunEvents
        val sunriseStr = formatIsoTime(
            sunEvents?.sunriseTime ?: omForecast?.daily?.sunrise?.firstOrNull(),
            zoneId,
            fallback = "06:15"
        )
        val sunsetStr = formatIsoTime(
            sunEvents?.sunsetTime ?: omForecast?.daily?.sunset?.firstOrNull(),
            zoneId,
            fallback = "18:15"
        )
        val sunProgress = computeSolarProgress(sunriseStr, sunsetStr, zoneId)

        val rawAqi = omAqi?.current?.usAqi ?: omAqi?.current?.europeanAqi
        val usAqi = rawAqi ?: 0
        val aqiCategory = if (rawAqi != null) categorizeAqi(rawAqi) else "Unavailable"
        val pm25 = omAqi?.current?.pm25?.let { roundOneDecimal(it) } ?: 0.0

        val visibilityKm = roundOneDecimal(
            current.visibility?.distance
                ?: currentOmHourSlot?.visibilityMeters?.let { it / 1000.0 }
                ?: 10.0
        )

        val telemetry = BentoTelemetry(
            windSpeedKmH = windSpeed,
            windDirection = windDir,
            windDegrees = windDegrees,
            windGustKmH = windGust,
            precipitationMm = todayTotalPrecipMm,
            uvIndex = uvVal,
            maxUvIndex = maxUv,
            uvCategory = uvCat,
            humidityPercent = humidity,
            dewPointDegrees = dewPoint,
            pressureHpa = pressure,
            sunriseTime = sunriseStr,
            sunsetTime = sunsetStr,
            aqiValue = usAqi,
            aqiCategory = aqiCategory,
            pm25 = pm25,
            visibilityKm = visibilityKm,
            sunProgress = sunProgress,
            feelsLikeDegrees = blendedFeelsTemp,
            feelsLikeReason = feelsReason
        )

        return WeatherDashboardData(
            hero = hero,
            alert = alert,
            hourlyList = calibratedHourlyItems,
            dailyList = dailyItems,
            telemetry = telemetry,
            nowcast = nowcast,
            ensemble = ensemble,
            timezone = timezoneId,
            latitude = latitude,
            longitude = longitude
        )
    }

    /**
     * Complete zero-downtime fallback when Google Weather API is unreachable or rate-limited.
     */
    internal fun mapOpenMeteoFallbackDashboardData(
        omForecast: OpenMeteoForecastResponse,
        omAqi: OpenMeteoAirQualityResponse?,
        resolvedLocationName: String?,
        latitude: Double = 0.0,
        longitude: Double = 0.0
    ): WeatherDashboardData {
        val zoneId = resolveZoneId(omForecast.timezone)
        val omCurrent = omForecast.current
        val isDay = (omCurrent?.isDay ?: 1) == 1
        val wmoCode = omCurrent?.weatherCode ?: 0
        val descText = mapWmoCodeToCondition(wmoCode, isDay)

        val skyTheme = when {
            wmoCode in 95..99 -> SkyThemeType.THUNDERSTORM
            wmoCode in 71..77 || wmoCode in 85..86 -> SkyThemeType.SNOW
            wmoCode in 51..67 || wmoCode in 80..82 -> SkyThemeType.RAIN
            wmoCode in 2..3 || wmoCode in 45..48 -> SkyThemeType.OVERCAST
            !isDay -> SkyThemeType.CLEAR_NIGHT
            else -> SkyThemeType.CLEAR_DAY
        }

        val nowcast = parsePrecipitationNowcast(omForecast.minutely15, zoneId)
        val currentTemp = omCurrent?.temperature2m?.roundToInt() ?: 22
        val ensemble = computeEnsembleDispersion(
            currentTemp = currentTemp,
            gTemp = null,
            omTemp = omCurrent?.temperature2m,
            gHigh = null,
            omHigh = omForecast.daily?.temperature2mMax?.firstOrNull(),
            gPop = null,
            omPop = omForecast.daily?.precipitationProbabilityMax?.firstOrNull()
        )
        val feelsLike = omCurrent?.apparentTemperature?.roundToInt() ?: currentTemp
        val rawHighTemp = omForecast.daily?.temperature2mMax?.firstOrNull()?.roundToInt() ?: currentTemp
        val rawLowTemp = omForecast.daily?.temperature2mMin?.firstOrNull()?.roundToInt() ?: currentTemp
        val locationName = resolvedLocationName?.takeIf { it.isNotBlank() }
            ?: omForecast.timezone?.substringAfterLast("/")?.replace("_", " ")
            ?: "Current Location"

        val humidity = omCurrent?.relativeHumidity2m ?: 50
        val windSpeed = omCurrent?.windSpeed10m?.roundToInt() ?: 0
        val feelsReason = buildFeelsLikeReason(currentTemp, feelsLike, humidity, windSpeed)

        val hourlyItems = extractNext24HoursFromOpenMeteo(omForecast, zoneId).mapIndexed { idx, item ->
            if (idx == 0) item.copy(tempDegrees = currentTemp) else item
        }
        val omHourlyByDateHour = parseOpenMeteoHourlyMap(omForecast.hourly)

        val dailyTimes = omForecast.daily?.time.orEmpty()
        val dailyItems = dailyTimes.mapIndexed { idx, dateIso ->
            val localDate = try {
                LocalDate.parse(dateIso)
            } catch (_: Exception) {
                LocalDate.now(zoneId).plusDays(idx.toLong())
            }
            val dayLabel = when (idx) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> localDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            val dateText = "${localDate.monthValue}/${localDate.dayOfMonth}"
            val fullDateStr = "${localDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, ${localDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${localDate.dayOfMonth}"

            val dCode = omForecast.daily?.weatherCode?.getOrNull(idx) ?: 0
            val dCond = mapWmoCodeToCondition(dCode, true)
            val dWindSpeed = omForecast.daily?.windSpeed10mMax?.getOrNull(idx)?.roundToInt() ?: 0
            val dWindDir = omForecast.daily?.windDirection10mDominant?.getOrNull(idx)?.let { degreesToCardinal(it) } ?: "—"
            val uvVal = omForecast.daily?.uvIndexMax?.getOrNull(idx)?.roundToInt() ?: 0
            val sunriseStr = formatIsoTime(omForecast.daily?.sunrise?.getOrNull(idx), zoneId, "06:15")
            val sunsetStr = formatIsoTime(omForecast.daily?.sunset?.getOrNull(idx), zoneId, "18:15")

            val dayHours = (0..23).mapNotNull { h ->
                val key = String.format(Locale.US, "%sT%02d:00", dateIso, h)
                omHourlyByDateHour[key]?.toHourlyItem(String.format(Locale.getDefault(), "%02d:00", h))
            }
            val hourlyMax = dayHours.maxOfOrNull { it.tempDegrees }
            val hourlyMin = dayHours.minOfOrNull { it.tempDegrees }

            val rawDayMax = omForecast.daily?.temperature2mMax?.getOrNull(idx)?.roundToInt() ?: hourlyMax ?: rawHighTemp
            val rawDayMin = omForecast.daily?.temperature2mMin?.getOrNull(idx)?.roundToInt() ?: hourlyMin ?: rawLowTemp

            val candidateMax = listOfNotNull(
                rawDayMax,
                hourlyMax,
                if (idx == 0) currentTemp else null
            ).maxOrNull() ?: rawDayMax
            val candidateMin = listOfNotNull(
                rawDayMin,
                hourlyMin,
                if (idx == 0) currentTemp else null
            ).minOrNull() ?: rawDayMin

            val dayMax = maxOf(candidateMax, candidateMin)
            val dayMin = minOf(candidateMax, candidateMin)

            val maxHourlyPop = dayHours.maxOfOrNull { it.rainProbability } ?: 0
            val sumHourlyPrecip = dayHours.sumOf { it.precipitationMm }
            val rawDayPop = omForecast.daily?.precipitationProbabilityMax?.getOrNull(idx) ?: 0
            val boundedDayPop = maxOf(rawDayPop, maxHourlyPop)
            val rawPrecip = omForecast.daily?.precipitationSum?.getOrNull(idx) ?: 0.0
            val boundedPrecip = roundOneDecimal(maxOf(rawPrecip, sumHourlyPrecip))

            val avgHumidity = if (dayHours.isNotEmpty()) {
                dayHours.map { it.humidityPercent }.average().roundToInt()
            } else {
                humidity
            }
            val matchingSlots = (0..23).mapNotNull { h ->
                val key = String.format(Locale.US, "%sT%02d:00", dateIso, h)
                omHourlyByDateHour[key]
            }
            val avgCloudCover = if (matchingSlots.isNotEmpty()) {
                matchingSlots.map { it.cloudCover }.average().roundToInt()
            } else {
                omCurrent?.cloudCover ?: 20
            }

            DailyItem(
                dayLabel = dayLabel,
                dateText = dateText,
                minTemp = dayMin,
                maxTemp = dayMax,
                rainProbability = boundedDayPop,
                iconUri = null,
                conditionText = dCond,
                morningWind = "AM: $dWindSpeed km/h $dWindDir",
                afternoonWind = "PM: $dWindSpeed km/h $dWindDir",
                fullDateString = fullDateStr,
                feelsLikeMaxTemp = omForecast.daily?.apparentTemperatureMax?.getOrNull(idx)?.roundToInt() ?: dayMax,
                feelsLikeMinTemp = omForecast.daily?.apparentTemperatureMin?.getOrNull(idx)?.roundToInt() ?: dayMin,
                precipitationMm = boundedPrecip,
                uvIndex = uvVal,
                uvCategory = categorizeUv(uvVal),
                humidityPercent = avgHumidity,
                thunderstormProbability = if (dCode in 95..99) 70 else 0,
                cloudCoverPercent = avgCloudCover,
                sunriseTime = sunriseStr,
                sunsetTime = sunsetStr,
                dayConditionText = dCond,
                nightConditionText = mapWmoCodeToCondition(dCode, false),
                dayWindSpeedKmH = dWindSpeed,
                dayWindDirection = dWindDir,
                nightWindSpeedKmH = dWindSpeed,
                nightWindDirection = dWindDir,
                hourlyForecast = alignHourlyExtremes(dayHours, dayMax, dayMin),
                confidencePercent = (88 - idx * 2).coerceIn(65, 95),
                modelSpreadDegrees = 0.9
            )
        }

        val highTemp = dailyItems.firstOrNull()?.maxTemp ?: maxOf(rawHighTemp, rawLowTemp, currentTemp)
        val lowTemp = dailyItems.firstOrNull()?.minTemp ?: minOf(rawHighTemp, rawLowTemp, currentTemp)

        val currentLocalHour = ZonedDateTime.now(zoneId).hour
        val todayHourly = dailyItems.firstOrNull()?.hourlyForecast.orEmpty()
        val calibratedHourlyItems = calibrateRollingHourlyPeak(
            hourlyList = hourlyItems,
            todayHourly = todayHourly,
            todayHigh = highTemp,
            currentHour = currentLocalHour
        )

        val hero = HeroWeather(
            tempDegrees = currentTemp,
            feelsLikeDegrees = feelsLike,
            highDegrees = highTemp,
            lowDegrees = lowTemp,
            conditionText = descText,
            iconUri = null,
            isDaytime = isDay,
            locationName = locationName,
            skyTheme = skyTheme,
            confidencePercent = ensemble.confidencePercent,
            modelSourcesLabel = "Open-Meteo High-Res",
            lastUpdatedEpochMs = System.currentTimeMillis(),
            feelsLikeReason = feelsReason,
            tempP10 = ensemble.p10Temp,
            tempP90 = ensemble.p90Temp,
            dispersionSummary = ensemble.modelAgreementSummary
        )

        val sunriseToday = dailyItems.firstOrNull()?.sunriseTime ?: "06:15"
        val sunsetToday = dailyItems.firstOrNull()?.sunsetTime ?: "18:15"
        val rawAqi = omAqi?.current?.usAqi ?: omAqi?.current?.europeanAqi
        val usAqi = rawAqi ?: 0
        val aqiCategory = if (rawAqi != null) categorizeAqi(rawAqi) else "Unavailable"
        val pm25 = omAqi?.current?.pm25?.let { roundOneDecimal(it) } ?: 0.0
        val omWindDeg = omCurrent?.windDirection10m ?: 0
        val omWindDir = degreesToCardinal(omWindDeg)
        val omMaxUv = omForecast.daily?.uvIndexMax?.firstOrNull()?.roundToInt() ?: (dailyItems.firstOrNull()?.uvIndex ?: 0)

        val nowHourKey = ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US))
        val currentOmHourSlot = omHourlyByDateHour[nowHourKey]
        val currentUv = currentOmHourSlot?.uvIndex ?: dailyItems.firstOrNull()?.uvIndex ?: 0
        val currentVisibilityKm = roundOneDecimal(
            (currentOmHourSlot?.visibilityMeters ?: 10000.0) / 1000.0
        )

        val telemetry = BentoTelemetry(
            windSpeedKmH = windSpeed,
            windDirection = omWindDir,
            windDegrees = omWindDeg,
            windGustKmH = omCurrent?.windGusts10m?.roundToInt() ?: windSpeed,
            precipitationMm = dailyItems.firstOrNull()?.precipitationMm ?: 0.0,
            uvIndex = currentUv,
            maxUvIndex = omMaxUv,
            uvCategory = categorizeUv(currentUv),
            humidityPercent = humidity,
            dewPointDegrees = currentTemp - ((100 - humidity) / 5),
            pressureHpa = omCurrent?.surfacePressure?.roundToInt() ?: 1013,
            sunriseTime = sunriseToday,
            sunsetTime = sunsetToday,
            aqiValue = usAqi,
            aqiCategory = aqiCategory,
            pm25 = pm25,
            visibilityKm = currentVisibilityKm,
            sunProgress = computeSolarProgress(sunriseToday, sunsetToday, zoneId),
            feelsLikeDegrees = feelsLike,
            feelsLikeReason = feelsReason
        )

        val alert = SevereAlertInfo(
            riskLevel = if (wmoCode in 95..99) SeverityRiskLevel.HIGH else SeverityRiskLevel.LOW,
            thunderstormProbability = if (wmoCode in 95..99) 70 else 0,
            title = if (wmoCode in 95..99) "THUNDERSTORM WATCH" else "THUNDERSTORM TRACKER: CLEAR",
            subtitle = "ECMWF multi-model fallback active.",
            isVisible = true,
            peakHourLabel = null,
            hourlyStormProbabilities = hourlyItems.map { it.thunderstormProbability }
        )

        return WeatherDashboardData(
            hero = hero,
            alert = alert,
            hourlyList = calibratedHourlyItems,
            dailyList = dailyItems,
            telemetry = telemetry,
            nowcast = nowcast,
            ensemble = ensemble,
            timezone = omForecast.timezone,
            latitude = latitude,
            longitude = longitude
        )
    }

    private data class OmHourSlot(
        val temp: Double,
        val pop: Int,
        val precipMm: Double,
        val windSpeed: Int,
        val uvIndex: Int,
        val humidity: Int,
        val isDay: Boolean,
        val conditionText: String,
        val stormProb: Int,
        val visibilityMeters: Double? = null,
        val cloudCover: Int = 20
    ) {
        fun toHourlyItem(label: String): HourlyItem = HourlyItem(
            hourLabel = label,
            tempDegrees = temp.roundToInt(),
            rainProbability = pop,
            iconUri = null,
            isDaytime = isDay,
            conditionText = conditionText,
            windSpeedKmH = windSpeed,
            precipitationMm = roundOneDecimal(precipMm),
            uvIndex = uvIndex,
            humidityPercent = humidity,
            thunderstormProbability = stormProb
        )
    }

    private fun parseOpenMeteoHourlyMap(hourly: OpenMeteoHourly?): Map<String, OmHourSlot> {
        val times = hourly?.time ?: return emptyMap()
        val result = HashMap<String, OmHourSlot>(times.size)
        for (i in times.indices) {
            val tStr = times[i]
            val isDay = (hourly.isDay?.getOrNull(i) ?: 1) == 1
            val code = hourly.weatherCode?.getOrNull(i) ?: 0
            val pop = hourly.precipitationProbability?.getOrNull(i) ?: 0
            val cloud = hourly.cloudCover?.getOrNull(i) ?: when (code) {
                0 -> 0
                1 -> 20
                2 -> 50
                3 -> 95
                45, 48 -> 90
                in 51..99 -> 85
                else -> 20
            }
            result[tStr] = OmHourSlot(
                temp = hourly.temperature2m?.getOrNull(i) ?: 20.0,
                pop = pop,
                precipMm = hourly.precipitation?.getOrNull(i) ?: 0.0,
                windSpeed = hourly.windSpeed10m?.getOrNull(i)?.roundToInt() ?: 0,
                uvIndex = hourly.uvIndex?.getOrNull(i)?.roundToInt() ?: 0,
                humidity = hourly.relativeHumidity2m?.getOrNull(i) ?: 50,
                isDay = isDay,
                conditionText = mapWmoCodeToCondition(code, isDay),
                stormProb = if (code in 95..99) maxOf(pop, 60) else 0,
                visibilityMeters = hourly.visibility?.getOrNull(i),
                cloudCover = cloud
            )
        }
        return result
    }

    private fun extractNext24HoursFromOpenMeteo(
        omForecast: OpenMeteoForecastResponse?,
        zoneId: ZoneId
    ): List<HourlyItem> {
        val hourly = omForecast?.hourly ?: return emptyList()
        val times = hourly.time ?: return emptyList()
        val nowHourPrefix = ZonedDateTime.now(zoneId).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", Locale.US))
        val matchedIndex = times.indexOfFirst { it.startsWith(nowHourPrefix) }
        val startIndex = if (matchedIndex >= 0) matchedIndex else {
            val todayPrefix = LocalDate.now(zoneId).toString()
            times.indexOfFirst { it.startsWith(todayPrefix) }.takeIf { it >= 0 } ?: return emptyList()
        }
        val map = parseOpenMeteoHourlyMap(hourly)

        return (0 until 24).mapNotNull { offset ->
            val tStr = times.getOrNull(startIndex + offset) ?: return@mapNotNull null
            val slot = map[tStr] ?: return@mapNotNull null
            val label = if (offset == 0) "Now" else tStr.substringAfter("T", "00:00").take(5)
            slot.toHourlyItem(label)
        }
    }

    private fun extractHourlyForDayWithBackfill(
        day: ForecastDay,
        localDate: LocalDate?,
        dayIdx: Int,
        allGoogleHours: List<ForecastHour>,
        omHourlyByDateHour: Map<String, OmHourSlot>,
        todayHoursFallback: List<HourlyItem>
    ): List<HourlyItem> {
        val targetDate = localDate ?: day.displayDate?.let {
            try {
                LocalDate.of(it.year ?: LocalDate.now().year, it.month ?: 1, it.day ?: 1)
            } catch (_: Exception) {
                null
            }
        } ?: LocalDate.now().plusDays(dayIdx.toLong())

        val dayYear = targetDate.year
        val dayMonth = targetDate.monthValue
        val dayDay = targetDate.dayOfMonth

        val matchedGoogle = allGoogleHours.filter {
            it.displayDateTime?.year == dayYear &&
                it.displayDateTime.month == dayMonth &&
                it.displayDateTime.day == dayDay
        }

        val dayHours = (0..23).mapNotNull { h ->
            val hStr = String.format(Locale.getDefault(), "%02d:00", h)
            val key = String.format(Locale.US, "%04d-%02d-%02dT%02d:00", dayYear, dayMonth, dayDay, h)
            val gItem = matchedGoogle.firstOrNull { it.displayDateTime?.hours == h }
            val omSlot = omHourlyByDateHour[key]

            if (gItem != null) {
                val cond = gItem.weatherCondition?.description?.text
                    ?: omSlot?.conditionText
                    ?: if ((gItem.precipitation?.probability?.percent ?: 0) > 30) "Scattered Showers" else "Clear"
                HourlyItem(
                    hourLabel = hStr,
                    tempDegrees = blendTemperatures(gItem.temperature?.degrees, omSlot?.temp) ?: (day.maxTemperature?.degrees?.roundToInt() ?: 0),
                    rainProbability = gItem.precipitation?.probability?.percent ?: omSlot?.pop ?: 0,
                    iconUri = gItem.weatherCondition?.iconBaseUri,
                    isDaytime = gItem.isDaytime ?: omSlot?.isDay ?: (h in 6..18),
                    conditionText = cond,
                    windSpeedKmH = gItem.wind?.speed?.value?.roundToInt() ?: omSlot?.windSpeed ?: 0,
                    precipitationMm = roundOneDecimal(gItem.precipitation?.qpf?.quantity ?: omSlot?.precipMm ?: 0.0),
                    uvIndex = gItem.uvIndex ?: omSlot?.uvIndex ?: 0,
                    humidityPercent = gItem.relativeHumidity ?: omSlot?.humidity ?: 0,
                    thunderstormProbability = gItem.thunderstormProbability ?: omSlot?.stormProb ?: 0
                )
            } else if (omSlot != null) {
                omSlot.toHourlyItem(hStr)
            } else {
                null
            }
        }

        if (dayHours.isNotEmpty()) {
            return dayHours
        }

        if (dayIdx == 0 && targetDate == LocalDate.now() && todayHoursFallback.isNotEmpty()) {
            return todayHoursFallback
        }
        return emptyList()
    }

    internal fun alignHourlyExtremes(
        hourly: List<HourlyItem>,
        targetMax: Int,
        targetMin: Int
    ): List<HourlyItem> {
        if (hourly.isEmpty()) return hourly
        val currentMax = hourly.maxOf { it.tempDegrees }
        val currentMin = hourly.minOf { it.tempDegrees }

        var adjusted = hourly
        if (currentMax < targetMax) {
            val peakIdx = adjusted.indices.maxByOrNull { adjusted[it].tempDegrees }
            if (peakIdx != null) {
                adjusted = adjusted.mapIndexed { idx, item ->
                    if (idx == peakIdx) item.copy(tempDegrees = targetMax) else item
                }
            }
        }
        if (currentMin > targetMin) {
            val minIdx = adjusted.indices.minByOrNull { adjusted[it].tempDegrees }
            if (minIdx != null) {
                adjusted = adjusted.mapIndexed { idx, item ->
                    if (idx == minIdx) item.copy(tempDegrees = targetMin) else item
                }
            }
        }
        return adjusted
    }

    internal fun calibrateRollingHourlyPeak(
        hourlyList: List<HourlyItem>,
        todayHourly: List<HourlyItem>,
        todayHigh: Int,
        currentHour: Int
    ): List<HourlyItem> {
        if (hourlyList.size <= 1 || todayHourly.isEmpty()) return hourlyList
        val peakIdx = todayHourly.indices.maxByOrNull { todayHourly[it].tempDegrees } ?: return hourlyList
        val peakHourOfDay = todayHourly[peakIdx].hourLabel
            .substringBefore(":")
            .trim()
            .toIntOrNull()
            ?: peakIdx

        // If today's peak hour has already elapsed or is the current hour (index 0 = "Now"), do not overwrite "Now"
        if (peakHourOfDay <= currentHour) {
            return hourlyList
        }

        // Peak hour is ahead today: adjust the maximum future hour within today's remaining window (excluding index 0 "Now")
        val remainingTodayCount = (24 - currentHour).coerceIn(1, hourlyList.size)
        val todayWindow = hourlyList.take(remainingTodayCount)
        if (todayWindow.size <= 1) return hourlyList
        val peakInTodayWindow = (1 until todayWindow.size).maxByOrNull { todayWindow[it].tempDegrees } ?: return hourlyList

        if (todayWindow[peakInTodayWindow].tempDegrees < todayHigh && hourlyList[0].tempDegrees < todayHigh) {
            return hourlyList.mapIndexed { idx, item ->
                if (idx == peakInTodayWindow) item.copy(tempDegrees = todayHigh) else item
            }
        }
        return hourlyList
    }

    private fun formatIsoTime(isoString: String?, zoneId: ZoneId, fallback: String): String {
        if (isoString.isNullOrBlank()) return fallback
        return try {
            if (isoString.endsWith("Z") || isoString.contains("+")) {
                val instant = Instant.parse(isoString)
                DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                    .withZone(zoneId)
                    .format(instant)
            } else {
                val ldt = LocalDateTime.parse(isoString)
                ldt.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
            }
        } catch (_: Exception) {
            fallback
        }
    }

    private fun resolveZoneId(timezoneId: String?): ZoneId {
        if (timezoneId.isNullOrBlank()) return ZoneId.systemDefault()
        return try {
            ZoneId.of(timezoneId)
        } catch (_: Exception) {
            ZoneId.systemDefault()
        }
    }
    internal fun parsePrecipitationNowcast(
        minutely15: OpenMeteoMinutely15?,
        zoneId: ZoneId,
        referenceNow: LocalDateTime? = null
    ): PrecipitationNowcast? {
        val times = minutely15?.time ?: return null
        val precips = minutely15.precipitation ?: return null
        val pops = minutely15.precipitationProbability.orEmpty()
        val codes = minutely15.weatherCode.orEmpty()

        if (times.isEmpty() || precips.isEmpty()) return null

        val now = (referenceNow ?: LocalDateTime.now(zoneId)).truncatedTo(ChronoUnit.MINUTES)
        val intervals = mutableListOf<MinutelyPrecipInterval>()
        for (i in times.indices) {
            val tStr = times[i]
            val slotTime = try {
                if (tStr.endsWith("Z") || tStr.contains("+")) {
                    ZonedDateTime.parse(tStr).withZoneSameInstant(zoneId).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
                } else {
                    LocalDateTime.parse(tStr).truncatedTo(ChronoUnit.MINUTES)
                }
            } catch (_: Exception) {
                null
            } ?: continue

            if (slotTime.isBefore(now)) continue

            val precipMm = precips.getOrNull(i) ?: 0.0
            val pop = pops.getOrNull(i) ?: if (precipMm >= 0.1) 80 else 0
            val code = codes.getOrNull(i) ?: 0
            val minuteOffset = ChronoUnit.MINUTES.between(now, slotTime).toInt()
            val label = tStr.substringAfter("T", "").take(5)
            intervals.add(
                MinutelyPrecipInterval(
                    minuteOffset = minuteOffset,
                    timeLabel = label,
                    precipMm = roundOneDecimal(precipMm),
                    probabilityPercent = pop,
                    weatherCode = code
                )
            )
            if (intervals.size >= 8) break
        }

        if (intervals.isEmpty()) return null

        val activeNow = (intervals.firstOrNull()?.precipMm ?: 0.0) >= 0.1
        val firstRainIdx = intervals.indexOfFirst { it.precipMm >= 0.1 || it.probabilityPercent >= 50 }
        val maxRate = intervals.maxOfOrNull { it.precipMm } ?: 0.0

        var rainCessationMinutes: Int? = null
        val (summary, imminent, onsetMinutes) = when {
            activeNow -> {
                val dryIdx = intervals.indexOfFirst { it.precipMm < 0.1 && it.probabilityPercent < 30 }
                if (dryIdx > 0) {
                    val dryMin = intervals[dryIdx].minuteOffset
                    rainCessationMinutes = dryMin
                    Triple("Rain stopping in ~$dryMin min", true, null)
                } else {
                    Triple("Rain continuing for next ~2 hours (peak rate ${roundOneDecimal(maxRate * 4.0)} mm/h)", true, null)
                }
            }
            firstRainIdx >= 0 -> {
                val onset = intervals[firstRainIdx].minuteOffset
                val rate = roundOneDecimal(intervals[firstRainIdx].precipMm * 4.0)
                Triple("Precipitation starting in ~$onset min (~$rate mm/h)", true, onset)
            }
            else -> {
                Triple("No precipitation expected in next 2 hours", false, null)
            }
        }

        return PrecipitationNowcast(
            summaryText = summary,
            isPrecipitationImminent = imminent,
            nextPrecipitationOnsetMinutes = onsetMinutes,
            maxRateMmH = roundOneDecimal(maxRate * 4.0),
            intervals = intervals,
            rainCessationMinutes = rainCessationMinutes
        )
    }

    internal fun computeEnsembleDispersion(
        currentTemp: Int,
        gTemp: Double?,
        omTemp: Double?,
        gHigh: Double?,
        omHigh: Double?,
        gPop: Int?,
        omPop: Int?
    ): EnsembleDispersion {
        val tempPoints = listOfNotNull(gTemp, omTemp)
        val hasMultiModel = tempPoints.size >= 2
        val spread = if (hasMultiModel) {
            abs(tempPoints[0] - tempPoints[1])
        } else 0.0

        val popSpread = if (gPop != null && omPop != null) abs(gPop - omPop) else 0

        val halfSpread = (spread / 2.0).coerceAtLeast(0.5)
        val p10 = kotlin.math.floor(currentTemp - halfSpread).roundToInt()
        val p90 = kotlin.math.ceil(currentTemp + halfSpread).roundToInt()

        val confidenceVal = if (hasMultiModel) {
            (98 - (spread * 6.5) - (popSpread * 0.3)).roundToInt().coerceIn(52, 98)
        } else {
            75 // Baseline for single unverified source
        }
        val tier = when {
            !hasMultiModel -> "Single Source"
            confidenceVal >= 88 -> "High Agreement"
            confidenceVal >= 72 -> "Moderate Spread"
            else -> "High Uncertainty"
        }

        val summary = when {
            !hasMultiModel -> "Single source feed (no cross-model verification)"
            spread <= 0.8 && popSpread <= 10 -> "High multi-model consensus (spread ±${roundOneDecimal(spread / 2.0)}°C)"
            spread <= 1.8 -> "Moderate model variance (spread ±${roundOneDecimal(spread / 2.0)}°C)"
            else -> "Model divergence (${roundOneDecimal(spread)}°C spread between models)"
        }

        return EnsembleDispersion(
            modelSpreadDegrees = roundOneDecimal(spread),
            p10Temp = minOf(p10, p90),
            p90Temp = maxOf(p10, p90),
            confidencePercent = confidenceVal,
            confidenceTier = tier,
            modelAgreementSummary = summary
        )
    }

    internal fun blendTemperatures(googleTemp: Double?, openMeteoTemp: Double?): Int? {
        return when {
            googleTemp != null && openMeteoTemp != null -> ((googleTemp + openMeteoTemp) / 2.0).roundToInt()
            googleTemp != null -> googleTemp.roundToInt()
            openMeteoTemp != null -> openMeteoTemp.roundToInt()
            else -> null
        }
    }

    internal fun computeEnsembleConfidence(tempSpreadDegrees: Double, popSpreadPercent: Int): Int {
        val tempPenalty = (tempSpreadDegrees * 6.0).roundToInt()
        val popPenalty = (popSpreadPercent * 0.35).roundToInt()
        return (97 - tempPenalty - popPenalty).coerceIn(58, 98)
    }

    internal fun computeSolarProgress(
        sunriseHHmm: String,
        sunsetHHmm: String,
        zoneId: ZoneId = ZoneId.systemDefault(),
        referenceTime: LocalTime? = null
    ): Float {
        return try {
            val formatter = DateTimeFormatter.ofPattern("H:mm", Locale.US)
            val sunriseMinutes = LocalTime.parse(sunriseHHmm, formatter).toSecondOfDay() / 60f
            val sunsetMinutes = LocalTime.parse(sunsetHHmm, formatter).toSecondOfDay() / 60f
            val nowMinutes = (referenceTime ?: LocalTime.now(zoneId)).toSecondOfDay() / 60f
            if (sunsetMinutes <= sunriseMinutes) return 0.5f

            if (nowMinutes in sunriseMinutes..sunsetMinutes) {
                ((nowMinutes - sunriseMinutes) / (sunsetMinutes - sunriseMinutes)).coerceIn(0.05f, 0.95f)
            } else {
                val totalNightMinutes = (1440f - sunsetMinutes) + sunriseMinutes
                if (totalNightMinutes <= 0f) return 0.5f
                val elapsedNight = if (nowMinutes >= sunsetMinutes) {
                    nowMinutes - sunsetMinutes
                } else {
                    (1440f - sunsetMinutes) + nowMinutes
                }
                (elapsedNight / totalNightMinutes).coerceIn(0.05f, 0.95f)
            }
        } catch (_: Exception) {
            0.5f
        }
    }

    internal fun mapWmoCodeToCondition(code: Int, isDaytime: Boolean): String {
        return when (code) {
            0 -> if (isDaytime) "Clear and Sunny" else "Clear Night"
            1 -> if (isDaytime) "Mostly Sunny" else "Mostly Clear"
            2 -> "Partly Cloudy"
            3 -> "Overcast"
            45, 48 -> "Fog and Mist"
            51, 53, 55, 56, 57 -> "Light Drizzle"
            61, 63, 80, 81 -> "Scattered Showers"
            65, 66, 67, 82 -> "Heavy Rain"
            71, 73, 75, 77, 85, 86 -> "Snow Showers"
            95, 96, 99 -> "Thunderstorms"
            else -> if (isDaytime) "Partly Cloudy" else "Clear"
        }
    }

    internal fun categorizeUv(uvVal: Int): String = when {
        uvVal >= 11 -> "Extreme"
        uvVal >= 8 -> "Very High"
        uvVal >= 6 -> "High"
        uvVal >= 3 -> "Moderate"
        else -> "Low"
    }

    internal fun categorizeAqi(usAqi: Int): String = when {
        usAqi <= 50 -> "Good"
        usAqi <= 100 -> "Moderate"
        usAqi <= 150 -> "Sensitive"
        usAqi <= 200 -> "Unhealthy"
        else -> "Hazardous"
    }

    internal fun buildFeelsLikeReason(actual: Int, feelsLike: Int, humidity: Int, windKmH: Int): String {
        val diff = feelsLike - actual
        return when {
            diff >= 2 && humidity >= 60 -> "Humidity is making it feel warmer"
            diff >= 2 -> "Solar warmth is making it feel warmer"
            diff <= -2 && windKmH >= 15 -> "Wind is making it feel cooler"
            diff <= -2 -> "Breeze is making it feel cooler"
            else -> "Similar to the actual temperature"
        }
    }

    internal fun parseCardinal(rawCardinal: String?): String {
        if (rawCardinal.isNullOrBlank()) return "—"
        val clean = rawCardinal.trim().uppercase()
        return when (clean) {
            "N", "NORTH" -> "N"
            "NNE", "NORTH_NORTHEAST", "NORTH_NORTH_EAST" -> "NNE"
            "NE", "NORTHEAST", "NORTH_EAST" -> "NE"
            "ENE", "EAST_NORTHEAST", "EAST_NORTH_EAST" -> "ENE"
            "E", "EAST" -> "E"
            "ESE", "EAST_SOUTHEAST", "EAST_SOUTH_EAST" -> "ESE"
            "SE", "SOUTHEAST", "SOUTH_EAST" -> "SE"
            "SSE", "SOUTH_SOUTHEAST", "SOUTH_SOUTH_EAST" -> "SSE"
            "S", "SOUTH" -> "S"
            "SSW", "SOUTH_SOUTHWEST", "SOUTH_SOUTH_WEST" -> "SSW"
            "SW", "SOUTHWEST", "SOUTH_WEST" -> "SW"
            "WSW", "WEST_SOUTHWEST", "WEST_SOUTH_WEST" -> "WSW"
            "W", "WEST" -> "W"
            "WNW", "WEST_NORTHWEST", "WEST_NORTH_WEST" -> "WNW"
            "NW", "NORTHWEST", "NORTH_WEST" -> "NW"
            "NNW", "NORTH_NORTHWEST", "NORTH_NORTH_WEST" -> "NNW"
            else -> clean.take(3)
        }
    }

    internal fun cardinalToDegrees(cardinal: String?): Int = when (parseCardinal(cardinal)) {
        "N" -> 0
        "NNE" -> 23
        "NE" -> 45
        "ENE" -> 68
        "E" -> 90
        "ESE" -> 113
        "SE" -> 135
        "SSE" -> 158
        "S" -> 180
        "SSW" -> 203
        "SW" -> 225
        "WSW" -> 248
        "W" -> 270
        "WNW" -> 293
        "NW" -> 315
        "NNW" -> 338
        else -> 0
    }

    internal fun degreesToCardinal(degrees: Int): String {
        val dirs = arrayOf(
            "N", "NNE", "NE", "ENE",
            "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW",
            "W", "WNW", "NW", "NNW"
        )
        val norm = (degrees % 360 + 360) % 360
        val idx = ((norm + 11.25) / 22.5).toInt() and 15
        return dirs[idx]
    }

    private fun roundOneDecimal(value: Double): Double = (value * 10.0).roundToInt() / 10.0
}
