package com.google.weatherapp.data.repository

import com.google.weatherapp.data.api.*
import com.google.weatherapp.data.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure mapping and calculation helpers that turn raw API responses into dashboard models. */
internal object WeatherMapper {

    private val HOUR_KEY_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00", Locale.US)

    internal fun mapEnsembleDashboardData(
        current: CurrentConditionsResponse,
        hourly: HourlyForecastResponse,
        daily: DailyForecastResponse,
        omForecast: OpenMeteoForecastResponse?,
        omAqi: OpenMeteoAirQualityResponse?,
        resolvedLocationName: String? = null,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        omConsensus: WeatherConsensus.MultiModelConsensusData? = null,
        googleAlerts: PublicAlertsResponse? = null,
        sawsAlerts: List<OfficialWeatherAlert> = emptyList()
    ): WeatherDashboardData {
        val timezoneId = current.timeZone?.id ?: omForecast?.timezone
        val zoneId = resolveZoneId(timezoneId)
        val nowZoned = ZonedDateTime.now(zoneId)
        val currentLocalDateHour = nowZoned.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val currentLocalHour = nowZoned.hour
        val nowHourKey = currentLocalDateHour.format(HOUR_KEY_FORMATTER)

        val omHourlyByDateHour = parseOpenMeteoHourlyMap(omForecast?.hourly)
        val currentConsensusSlot = omConsensus?.hourlyByTime?.get(nowHourKey)

        val isDay = current.isDaytime
            ?: omForecast?.current?.isDay?.let { it == 1 }
            ?: omHourlyByDateHour[nowHourKey]?.isDay
            ?: (currentLocalHour in 6..18)
        val condType = current.weatherCondition?.type.orEmpty()
        val descText = current.weatherCondition?.description?.text
            ?: omForecast?.current?.weatherCode?.let { mapWmoCodeToCondition(it, isDay) }
            ?: "Clear"

        val skyTheme = resolveSkyTheme(
            condType = condType,
            descText = descText,
            thunderstormProb = current.thunderstormProbability ?: 0,
            isDay = isDay
        )

        // Current temperature (lead = 0h): anchor strongly to live station-blended Google Current (78%)
        // while gently cross-checking against multi-model Open-Meteo consensus (22%).
        val gTemp = current.temperature?.degrees
        val omTemp = omForecast?.current?.temperature2m
        val currentOmModels: Map<String, Double> = buildCurrentModelMap(
            omCurrentTemp = omTemp,
            consensusSlot = currentConsensusSlot
        )
        val blendedCurrentTempDouble = WeatherConsensus.blendConsensusTemperature(
            googleTemp = gTemp,
            modelTemps = currentOmModels,
            leadHours = 0
        ) ?: gTemp ?: omTemp ?: 20.0
        val blendedCurrentTemp = blendedCurrentTempDouble.roundToInt()

        val humidity = current.relativeHumidity ?: omForecast?.current?.relativeHumidity2m ?: 50
        val windSpeed = current.wind?.speed?.value?.roundToInt()
            ?: omForecast?.current?.windSpeed10m?.roundToInt()
            ?: 0

        val gFeels = current.feelsLikeTemperature?.degrees
        val omFeels = omForecast?.current?.apparentTemperature
        val blendedFeelsTemp = WeatherConsensus.computeSanitizedFeelsLike(
            actualTempC = blendedCurrentTempDouble,
            googleFeelsC = gFeels,
            omFeelsC = omFeels,
            humidityPercent = humidity,
            windKmH = windSpeed.toDouble(),
            isDaytime = isDay
        )
        val feelsReason = buildFeelsLikeReason(blendedCurrentTemp, blendedFeelsTemp, humidity, windSpeed, isDay)

        val todayForecast = daily.forecastDays?.firstOrNull()
        val todayLocalDate = todayForecast?.displayDate?.let { parseDisplayDate(it, zoneId) }
        val todayOmIdx = todayLocalDate?.toString()?.let { iso ->
            omForecast?.daily?.time?.indexOf(iso)?.takeIf { it >= 0 }
        } ?: 0

        val gHigh = todayForecast?.maxTemperature?.degrees
        val omHigh = omForecast?.daily?.temperature2mMax?.getOrNull(todayOmIdx)
        val rawHighTemp = blendTemperatures(gHigh, omHigh) ?: blendedCurrentTemp

        val gLow = todayForecast?.minTemperature?.degrees
        val omLow = omForecast?.daily?.temperature2mMin?.getOrNull(todayOmIdx)
        val rawLowTemp = blendTemperatures(gLow, omLow) ?: blendedCurrentTemp

        val activeOmModelCount = when {
            omConsensus != null && omConsensus.activeModels.isNotEmpty() -> omConsensus.activeModels.size
            omForecast != null -> 1
            else -> 0
        }
        val totalModelCount = (if (gTemp != null) 1 else 0) + activeOmModelCount

        val currentTempSamples = buildList {
            gTemp?.let { add(it) }
            addAll(currentOmModels.values)
        }
        val todayHighSamples = buildList {
            gHigh?.let { add(it) }
            if (todayLocalDate != null) {
                addAll(computeWindowModelHighs(todayLocalDate, omConsensus, omHourlyByDateHour).values)
            }
            if (size <= 1 && omHigh != null) add(omHigh)
        }
        val gTodayPop = maxOf(
            todayForecast?.daytimeForecast?.precipitation?.probability?.percent ?: 0,
            todayForecast?.nighttimeForecast?.precipitation?.probability?.percent ?: 0
        ).takeIf { todayForecast?.daytimeForecast != null || todayForecast?.nighttimeForecast != null }
        val omTodayPop = omForecast?.daily?.precipitationProbabilityMax?.getOrNull(todayOmIdx)
        val todayPopSamples = buildList {
            gTodayPop?.let { add(it) }
            omTodayPop?.let { add(it) }
        }

        val ensemble = WeatherConsensus.computeMultiModelDispersion(
            centerTemp = blendedCurrentTemp,
            tempSamples = currentTempSamples,
            highTempSamples = todayHighSamples,
            popSamples = todayPopSamples,
            dayIndex = 0
        )
        val heroConfidence = ensemble.confidencePercent
        val modelLabel = when {
            totalModelCount >= 3 -> "$totalModelCount-Model Consensus"
            gTemp != null && omTemp != null -> "Google + Open-Meteo Ensemble"
            gTemp != null -> "Google Weather"
            omForecast != null -> "Open-Meteo High-Res"
            else -> "Cached Weather"
        }

        val locationName = resolvedLocationName?.takeIf { it.isNotBlank() }
            ?: timezoneId?.substringAfterLast("/")?.replace("_", " ")
            ?: "Current Location"

        // Hourly forecast processing (24 hours, 1-hour aligned precipitation, calibrated consensus)
        val gHours = hourly.forecastHours.orEmpty()
        val futureGHours = gHours.filter { h ->
            val dt = parseForecastHourDateTime(h.displayDateTime)
            dt == null || !dt.isBefore(currentLocalDateHour)
        }

        val hourlyItems: List<HourlyItem> = if (futureGHours.isNotEmpty()) {
            val firstDt = parseForecastHourDateTime(futureGHours.first().displayDateTime)
            val needsPrependedNow = firstDt != null && firstDt.isAfter(currentLocalDateHour)
            val currentHourPrecipSlot = lookupAlignedPrecipSlot(currentLocalDateHour, omHourlyByDateHour)
            val currentHourConsensusPrecip = lookupAlignedConsensusPrecip(currentLocalDateHour, omConsensus)

            val built = mutableListOf<HourlyItem>()
            if (needsPrependedNow) {
                val nowPop = current.precipitation?.probability?.percent
                    ?: blendHourlyPop(null, currentHourPrecipSlot?.pop, currentHourConsensusPrecip?.popByModel, leadHours = 0)
                val nowPrecipMm = current.precipitation?.qpf?.quantity
                    ?: blendHourlyPrecipMm(null, currentHourPrecipSlot?.precipMm, currentHourConsensusPrecip?.precipByModel, leadHours = 0)
                val nowOmSlot = omHourlyByDateHour[nowHourKey]
                val nowStormProb = maxOf(
                    current.thunderstormProbability ?: 0,
                    nowOmSlot?.stormProb ?: 0
                )
                built.add(
                    HourlyItem(
                        hourLabel = "Now",
                        tempDegrees = blendedCurrentTemp,
                        rainProbability = nowPop,
                        iconUri = current.weatherCondition?.iconBaseUri,
                        isDaytime = isDay,
                        conditionText = descText,
                        windSpeedKmH = windSpeed,
                        precipitationMm = roundOneDecimal(nowPrecipMm),
                        uvIndex = current.uvIndex ?: nowOmSlot?.uvIndex ?: 0,
                        humidityPercent = humidity,
                        thunderstormProbability = nowStormProb
                    )
                )
            }

            val takeCount = if (needsPrependedNow) 23 else 24
            futureGHours.take(takeCount).forEachIndexed { idx, item ->
                val isNowAnchor = !needsPrependedNow && idx == 0
                val dt = parseForecastHourDateTime(item.displayDateTime)
                val leadHours = if (dt != null) {
                    ChronoUnit.HOURS.between(currentLocalDateHour, dt).toInt().coerceAtLeast(0)
                } else {
                    idx + if (needsPrependedNow) 1 else 0
                }
                val label = if (isNowAnchor) {
                    "Now"
                } else {
                    item.displayDateTime?.hours?.let { String.format(Locale.getDefault(), "%02d:00", it) } ?: "—"
                }

                if (isNowAnchor) {
                    val blendedItem = buildBlendedHourlyItem(
                        label = "Now",
                        slotDateTime = dt ?: currentLocalDateHour,
                        gItem = item,
                        omHourlyByDateHour = omHourlyByDateHour,
                        omConsensus = omConsensus,
                        leadHours = 0,
                        fallbackTemp = blendedCurrentTemp,
                        fallbackCondition = descText
                    )
                    built.add(
                        blendedItem.copy(
                            tempDegrees = blendedCurrentTemp,
                            rainProbability = current.precipitation?.probability?.percent ?: blendedItem.rainProbability,
                            precipitationMm = roundOneDecimal(current.precipitation?.qpf?.quantity ?: blendedItem.precipitationMm),
                            conditionText = descText,
                            isDaytime = isDay,
                            iconUri = current.weatherCondition?.iconBaseUri,
                            windSpeedKmH = windSpeed,
                            uvIndex = current.uvIndex ?: blendedItem.uvIndex,
                            humidityPercent = humidity,
                            thunderstormProbability = maxOf(
                                current.thunderstormProbability ?: 0,
                                blendedItem.thunderstormProbability
                            )
                        )
                    )
                } else {
                    built.add(
                        buildBlendedHourlyItem(
                            label = label,
                            slotDateTime = dt,
                            gItem = item,
                            omHourlyByDateHour = omHourlyByDateHour,
                            omConsensus = omConsensus,
                            leadHours = leadHours,
                            fallbackTemp = blendedCurrentTemp,
                            fallbackCondition = descText
                        )
                    )
                }
            }
            built
        } else {
            extractNext24HoursFromOpenMeteo(omForecast, zoneId, omConsensus).mapIndexed { idx, item ->
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

        // Build Short-Term Rain Forecast (or native 15-min nowcast in Central Europe / North America)
        val useNative15Min = WeatherConsensus.hasNative15MinNowcast(latitude, longitude)
        val nowcast = if (useNative15Min && omForecast?.minutely15 != null) {
            parsePrecipitationNowcast(omForecast.minutely15, zoneId)
                ?: buildShortTermRainForecast(hourlyItems)
        } else {
            buildShortTermRainForecast(hourlyItems)
        }

        // Merge official government alerts (Google Public Alerts + SAWS CAP)
        val parsedGoogleAlerts = parseGooglePublicAlerts(googleAlerts, zoneId)
        val officialAlerts = (parsedGoogleAlerts + sawsAlerts)
            .distinctBy { "${it.title.lowercase(Locale.US)}|${it.areaName.lowercase(Locale.US)}" }
            .sortedByDescending { it.severity.ordinal }

        // Severe weather & thunderstorm risk pill calculation (24-hour storm scanner + official alerts)
        val next24Hours = futureGHours.take(24)
        val hourlyStormList = hourlyItems.map { it.thunderstormProbability }
        val currentStormProb = current.thunderstormProbability ?: 0

        // Only fall back to day/night period storm probability when hourly storm data is absent,
        // preventing an expired morning storm from triggering an afternoon alert.
        val periodFallbackStormProb = if (hourlyStormList.isEmpty()) {
            if (currentLocalHour < 18) {
                todayForecast?.daytimeForecast?.thunderstormProbability
                    ?: todayForecast?.nighttimeForecast?.thunderstormProbability
                    ?: 0
            } else {
                todayForecast?.nighttimeForecast?.thunderstormProbability ?: 0
            }
        } else {
            0
        }

        val maxOmGustNext24 = (0 until 24).mapNotNull { offset ->
            val key = currentLocalDateHour.plusHours(offset.toLong()).format(HOUR_KEY_FORMATTER)
            omHourlyByDateHour[key]?.windGusts
        }.maxOrNull() ?: 0
        val maxGoogleGustNext24 = next24Hours.maxOfOrNull {
            it.wind?.gust?.value?.roundToInt() ?: it.wind?.speed?.value?.roundToInt() ?: 0
        } ?: 0
        val maxGustNext24 = maxOf(
            maxGoogleGustNext24,
            maxOmGustNext24,
            current.wind?.gust?.value?.roundToInt() ?: 0,
            omForecast?.current?.windGusts10m?.roundToInt() ?: 0
        )

        val alert = buildSevereAlertInfo(
            hourlyItems = hourlyItems,
            hourlyStormList = hourlyStormList,
            currentStormProb = currentStormProb,
            periodFallbackStormProb = periodFallbackStormProb,
            condType = condType,
            currentLocalHour = currentLocalHour,
            maxGustNext24 = maxGustNext24,
            officialAlerts = officialAlerts
        )

        // Daily forecast processing (up to 10 days, aligned to Google's 07:00–07:00 meteorological day
        // so Tonight's overnight low matches mainstream apps and blends the exact same night!)
        val omDailyTimes = omForecast?.daily?.time.orEmpty()
        val observedTodayMax = current.currentConditionsHistory?.maxTemperature?.degrees
        val dailyItems = (daily.forecastDays ?: emptyList()).mapIndexed { idx, day ->
            val localDate = day.displayDate?.let { parseDisplayDate(it, zoneId) }
                ?: LocalDate.now(zoneId).plusDays(idx.toLong())
            val omIdx = localDate.toString().let { iso ->
                omDailyTimes.indexOf(iso).takeIf { it >= 0 }
            } ?: idx

            val dayLabel = when (idx) {
                0 -> "Today"
                1 -> "Tomorrow"
                else -> localDate.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
            }
            val dateText = "${localDate.monthValue}/${localDate.dayOfMonth}"
            val fullDateStr = "${localDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, " +
                "${localDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${localDate.dayOfMonth}"

            val dayPeriod = day.daytimeForecast
            val nightPeriod = day.nighttimeForecast
            val dayWind = dayPeriod?.wind
            val nightWind = nightPeriod?.wind

            val dayHourly = extractHourlyForDayWithBackfill(
                day = day,
                localDate = localDate,
                dayIdx = idx,
                allGoogleHours = gHours,
                omHourlyByDateHour = omHourlyByDateHour,
                omConsensus = omConsensus,
                todayHoursFallback = hourlyItems,
                currentLocalDateHour = currentLocalDateHour,
                zoneId = zoneId
            )

            // Real AM (06-11), PM (12-17), and Night (18-23 + next 00-05) winds across all 10 days
            val amWindData = extractPeriodWind(
                localDate = localDate,
                hoursRange = 6..11,
                gHours = gHours,
                omHourly = omHourlyByDateHour,
                fallbackSpeed = dayWind?.speed?.value?.roundToInt()
                    ?: omForecast?.daily?.windSpeed10mMax?.getOrNull(omIdx)?.roundToInt() ?: 0,
                fallbackDir = parseCardinal(
                    dayWind?.direction?.cardinal
                        ?: omForecast?.daily?.windDirection10mDominant?.getOrNull(omIdx)?.let { degreesToCardinal(it) }
                )
            )
            val pmWindData = extractPeriodWind(
                localDate = localDate,
                hoursRange = 12..17,
                gHours = gHours,
                omHourly = omHourlyByDateHour,
                fallbackSpeed = dayWind?.speed?.value?.roundToInt() ?: amWindData.first,
                fallbackDir = parseCardinal(dayWind?.direction?.cardinal ?: amWindData.second)
            )
            val nightWindData = extractPeriodWind(
                localDate = localDate,
                hoursRange = 18..23,
                gHours = gHours,
                omHourly = omHourlyByDateHour,
                fallbackSpeed = nightWind?.speed?.value?.roundToInt() ?: pmWindData.first,
                fallbackDir = parseCardinal(nightWind?.direction?.cardinal ?: pmWindData.second)
            )

            val morningWindStr = formatPeriodWind("AM", amWindData.first, amWindData.second)
            val afternoonWindStr = formatPeriodWind("PM", pmWindData.first, pmWindData.second)
            val nightWindStr = formatPeriodWind("Night", nightWindData.first, nightWindData.second)

            val uvVal = dayPeriod?.uvIndex
                ?: omForecast?.daily?.uvIndexMax?.getOrNull(omIdx)?.roundToInt()
                ?: (if (idx == 0) current.uvIndex else null)
                ?: 0
            val uvCat = categorizeUv(uvVal)

            val leadHoursMidday = (idx * 24 + 12).coerceAtLeast(1)

            // Aligned 07:00–07:00 window statistics from Open-Meteo / multi-model consensus
            val windowHighsByModel = computeWindowModelHighs(localDate, omConsensus, omHourlyByDateHour)
            val windowLowsByModel = computeOvernightWindowModelLows(localDate, omConsensus, omHourlyByDateHour)
            val hasAlignedOvernightWindow = windowLowsByModel.isNotEmpty()

            val gMax = day.maxTemperature?.degrees
            val gMin = day.minTemperature?.degrees
            val omDailyMax = omForecast?.daily?.temperature2mMax?.getOrNull(omIdx)
            val omDailyMin = omForecast?.daily?.temperature2mMin?.getOrNull(omIdx)

            val maxModelMap = windowHighsByModel.ifEmpty {
                omDailyMax?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap()
            }
            val minModelMap = windowLowsByModel.ifEmpty {
                // Only fall back to OM midnight-to-midnight daily min if Google daily min is null
                // OR we don't have enough hourly points (e.g. sparse unit test fixture)
                if (omHourlyByDateHour.isEmpty() || gMin == null) {
                    omDailyMin?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap()
                } else {
                    emptyMap()
                }
            }

            val hourlyMax = dayHourly.maxOfOrNull { it.tempDegrees }
            val hourlyMin = dayHourly.minOfOrNull { it.tempDegrees }

            val rawBlendedMax = WeatherConsensus.blendConsensusTemperature(gMax, maxModelMap, leadHoursMidday)?.roundToInt()
                ?: hourlyMax ?: rawHighTemp
            val rawBlendedMin = WeatherConsensus.blendConsensusTemperature(gMin, minModelMap, leadHoursMidday + 12)?.roundToInt()
                ?: hourlyMin ?: rawLowTemp

            // Bound daily high accurately (including observed earlier-today high from Google history!)
            val candidateMax = listOfNotNull(
                rawBlendedMax,
                hourlyMax,
                if (idx == 0) blendedCurrentTemp else null,
                if (idx == 0) observedTodayMax?.roundToInt() else null
            ).maxOrNull() ?: rawBlendedMax

            // When aligned to Tonight's 19:00–07:00 overnight low, do NOT let this morning's expired
            // pre-dawn hours pull tonight's low down!
            val candidateMin = if (hasAlignedOvernightWindow) {
                val tonightHoursMin = computeOvernightHourlyMin(localDate, dayHourly, currentLocalHour, idx == 0)
                listOfNotNull(
                    rawBlendedMin,
                    tonightHoursMin,
                    if (idx == 0 && (currentLocalHour >= 19 || blendedCurrentTemp < rawBlendedMin)) blendedCurrentTemp else null
                ).minOrNull() ?: rawBlendedMin
            } else {
                listOfNotNull(
                    rawBlendedMin,
                    hourlyMin,
                    if (idx == 0) blendedCurrentTemp else null
                ).minOrNull() ?: rawBlendedMin
            }

            val blendedMax = maxOf(candidateMax, candidateMin)
            val blendedMin = minOf(candidateMax, candidateMin)

            // Calibrated Day vs. Night Rain Probabilities (no maxOf inflation!)
            val gDayPop = dayPeriod?.precipitation?.probability?.percent
            val gNightPop = nightPeriod?.precipitation?.probability?.percent
            val omDayPopsByModel = computeWindowMaxPopByModel(localDate, 7..18, omConsensus, omHourlyByDateHour)
            val omNightPopsByModel = computeOvernightMaxPopByModel(localDate, omConsensus, omHourlyByDateHour)
            val omDailyPopFallback = omForecast?.daily?.precipitationProbabilityMax?.getOrNull(omIdx)

            val dayPopModelMap = omDayPopsByModel.ifEmpty {
                if (omHourlyByDateHour.isEmpty()) omDailyPopFallback?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap() else emptyMap()
            }
            val nightPopModelMap = omNightPopsByModel.ifEmpty {
                if (omHourlyByDateHour.isEmpty()) omDailyPopFallback?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap() else emptyMap()
            }

            val blendedDayPop = WeatherConsensus.blendConsensusRainProbability(gDayPop, dayPopModelMap, leadHoursMidday) ?: 0
            val blendedNightPop = WeatherConsensus.blendConsensusRainProbability(gNightPop, nightPopModelMap, leadHoursMidday + 12) ?: 0

            val relevantHourlyPop = if (idx == 0 && currentLocalHour >= 19) {
                // Past daytime: focus Today's badge on remaining evening/tonight hours
                dayHourly.filter { (parseHourInt(it.hourLabel) ?: 0) >= currentLocalHour }
                    .maxOfOrNull { it.rainProbability } ?: blendedNightPop
            } else {
                dayHourly.maxOfOrNull { it.rainProbability } ?: 0
            }
            val combinedDayPop = if (idx == 0 && currentLocalHour >= 19) {
                maxOf(blendedNightPop, relevantHourlyPop)
            } else {
                maxOf(blendedDayPop, blendedNightPop, relevantHourlyPop)
            }

            // Calibrated 24-hour rainfall total (07:00–07:00 aligned, weighted mean across models)
            val hasGoogleQpf = dayPeriod?.precipitation?.qpf?.quantity != null || nightPeriod?.precipitation?.qpf?.quantity != null
            val gDayQpf = if (hasGoogleQpf) {
                (dayPeriod?.precipitation?.qpf?.quantity ?: 0.0) + (nightPeriod?.precipitation?.qpf?.quantity ?: 0.0)
            } else null
            val omWindowPrecipByModel = compute24hWindowPrecipByModel(localDate, omConsensus, omHourlyByDateHour)
            val omDailyPrecipFallback = omForecast?.daily?.precipitationSum?.getOrNull(omIdx)
            val precipModelMap = omWindowPrecipByModel.ifEmpty {
                omDailyPrecipFallback?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap()
            }
            val blendedPrecipAmount = WeatherConsensus.blendConsensusPrecipMm(gDayQpf, precipModelMap, leadHoursMidday) ?: 0.0
            val sumHourlyPrecip = dayHourly.sumOf { it.precipitationMm }
            // When single-source (no Open-Meteo), still honor hourly QPF sum if Google daily under-reported it
            val finalPrecipAmount = roundOneDecimal(
                if (precipModelMap.isEmpty()) maxOf(blendedPrecipAmount, sumHourlyPrecip) else blendedPrecipAmount
            )

            // True multi-model dispersion for this day (never fake 0.7° when single source!)
            val dayHighSamples = buildList {
                gMax?.let { add(it) }
                addAll(maxModelMap.values)
            }
            val dayPopSamples = buildList {
                if (gDayPop != null || gNightPop != null) add(maxOf(gDayPop ?: 0, gNightPop ?: 0))
                omDayPopsByModel.values.forEach { add(it) }
                if (omDayPopsByModel.isEmpty() && omDailyPopFallback != null) add(omDailyPopFallback)
            }
            val dayDispersion = WeatherConsensus.computeMultiModelDispersion(
                centerTemp = blendedMax,
                tempSamples = dayHighSamples,
                popSamples = dayPopSamples,
                dayIndex = idx
            )

            // Fix nighttime storm blindness: take max of day, night, and hourly storm risks
            val dayStormRisk = maxOf(
                dayPeriod?.thunderstormProbability ?: 0,
                nightPeriod?.thunderstormProbability ?: 0,
                dayHourly.maxOfOrNull { it.thunderstormProbability } ?: 0
            )

            // Average day + night humidity and cloud cover (not daytime-only!)
            val dayHumidity = listOfNotNull(dayPeriod?.relativeHumidity, nightPeriod?.relativeHumidity)
                .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: dayHourly.takeIf { it.isNotEmpty() }?.map { it.humidityPercent }?.average()?.roundToInt()
                ?: current.relativeHumidity
                ?: 50

            val cloudCover = listOfNotNull(dayPeriod?.cloudCover, nightPeriod?.cloudCover)
                .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: (0..23).mapNotNull { h -> omHourlyByDateHour[String.format(Locale.US, "%sT%02d:00", localDate, h)]?.cloudCover }
                    .takeIf { it.isNotEmpty() }?.average()?.roundToInt()
                ?: 0

            val daySunrise = day.sunEvents?.sunriseTime
                ?: (if (idx == 0) current.sunEvents?.sunriseTime else null)
                ?: omForecast?.daily?.sunrise?.getOrNull(omIdx)
            val daySunset = day.sunEvents?.sunsetTime
                ?: (if (idx == 0) current.sunEvents?.sunsetTime else null)
                ?: omForecast?.daily?.sunset?.getOrNull(omIdx)
            val sunriseStr = formatIsoTime(daySunrise, zoneId, fallback = "06:15")
            val sunsetStr = formatIsoTime(daySunset, zoneId, fallback = "18:15")

            val dayCond = dayPeriod?.weatherCondition?.description?.text
                ?: dayPeriod?.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: omForecast?.daily?.weatherCode?.getOrNull(omIdx)?.let { mapWmoCodeToCondition(it, true) }
                ?: descText
            val nightCond = nightPeriod?.weatherCondition?.description?.text
                ?: nightPeriod?.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
                ?: omForecast?.daily?.weatherCode?.getOrNull(omIdx)?.let { mapWmoCodeToCondition(it, false) }
                ?: "Clear"

            val rawFeelsMax = blendTemperatures(
                day.feelsLikeMaxTemperature?.degrees,
                omForecast?.daily?.apparentTemperatureMax?.getOrNull(omIdx)
            ) ?: blendedMax
            val rawFeelsMin = blendTemperatures(
                day.feelsLikeMinTemperature?.degrees,
                omForecast?.daily?.apparentTemperatureMin?.getOrNull(omIdx)
            ) ?: blendedMin

            // Avoid mutating hourly temperatures away from reality when aligned overnight low differs from morning min
            val hourlyForDetail = if (hasAlignedOvernightWindow) {
                dayHourly
            } else {
                alignHourlyExtremes(dayHourly, blendedMax, blendedMin)
            }

            DailyItem(
                dayLabel = dayLabel,
                dateText = dateText,
                minTemp = blendedMin,
                maxTemp = blendedMax,
                rainProbability = combinedDayPop,
                iconUri = dayPeriod?.weatherCondition?.iconBaseUri,
                conditionText = dayCond,
                morningWind = morningWindStr,
                afternoonWind = afternoonWindStr,
                fullDateString = fullDateStr,
                feelsLikeMaxTemp = maxOf(rawFeelsMax, rawFeelsMin),
                feelsLikeMinTemp = minOf(rawFeelsMax, rawFeelsMin),
                precipitationMm = finalPrecipAmount,
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
                dayWindSpeedKmH = maxOf(amWindData.first, pmWindData.first),
                dayWindDirection = amWindData.second,
                nightWindSpeedKmH = nightWindData.first,
                nightWindDirection = nightWindData.second,
                hourlyForecast = hourlyForDetail,
                confidencePercent = dayDispersion.confidencePercent,
                modelSpreadDegrees = dayDispersion.modelSpreadDegrees,
                dayRainProbability = blendedDayPop,
                nightRainProbability = blendedNightPop,
                nightWind = nightWindStr
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
            dispersionSummary = ensemble.modelAgreementSummary,
            modelCount = totalModelCount.coerceAtLeast(1)
        )

        // Bento Telemetry Grid
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

        // Consistent today 24h precipitation (never mix tomorrow morning's rolling sum into Today's card)
        val todayTotalPrecipMm = roundOneDecimal(
            maxOf(
                dailyItems.firstOrNull()?.precipitationMm ?: 0.0,
                current.precipitation?.qpf?.quantity ?: 0.0
            )
        )

        val currentOmHourSlot = omHourlyByDateHour[nowHourKey]
        val uvVal = current.uvIndex
            ?: currentOmHourSlot?.uvIndex
            ?: (if (isDay) hourlyItems.firstOrNull()?.uvIndex else 0)
            ?: 0
        val maxUv = todayForecast?.daytimeForecast?.uvIndex
            ?: omForecast?.daily?.uvIndexMax?.getOrNull(todayOmIdx)?.roundToInt()
            ?: uvVal
        val uvCat = categorizeUv(uvVal)

        val dewPoint = current.dewPoint?.degrees?.roundToInt()
            ?: omForecast?.current?.dewPoint2m?.roundToInt()
            ?: WeatherConsensus.computeDewPoint(blendedCurrentTempDouble, humidity)

        val (pressure, pressureLabel) = resolvePressureHpa(
            googleMsl = current.airPressure?.meanSeaLevelMillibars,
            omMsl = omForecast?.current?.pressureMsl,
            omSurface = omForecast?.current?.surfacePressure,
            elevationMeters = omForecast?.elevation,
            tempC = blendedCurrentTempDouble
        )

        val sunEvents = todayForecast?.sunEvents ?: current.sunEvents
        val sunriseStr = formatIsoTime(
            sunEvents?.sunriseTime ?: omForecast?.daily?.sunrise?.getOrNull(todayOmIdx),
            zoneId,
            fallback = "06:15"
        )
        val sunsetStr = formatIsoTime(
            sunEvents?.sunsetTime ?: omForecast?.daily?.sunset?.getOrNull(todayOmIdx),
            zoneId,
            fallback = "18:15"
        )
        val sunProgress = computeSolarProgress(sunriseStr, sunsetStr, zoneId)

        val aqiSummary = WeatherConsensus.resolveAirQuality(omAqi?.current)
        val visibilityKm = resolveVisibilityKm(current.visibility, currentOmHourSlot?.visibilityMeters)

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
            aqiValue = aqiSummary.value,
            aqiCategory = aqiSummary.category,
            pm25 = aqiSummary.pm25,
            visibilityKm = visibilityKm,
            sunProgress = sunProgress,
            feelsLikeDegrees = blendedFeelsTemp,
            feelsLikeReason = feelsReason,
            dominantPollutantLabel = aqiSummary.dominantPollutantLabel,
            aqiScaleLabel = aqiSummary.scaleLabel,
            pressureLabel = pressureLabel
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
     * Still leverages multi-model Open-Meteo consensus + official alerts when available.
     */
    internal fun mapOpenMeteoFallbackDashboardData(
        omForecast: OpenMeteoForecastResponse,
        omAqi: OpenMeteoAirQualityResponse?,
        resolvedLocationName: String?,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        omConsensus: WeatherConsensus.MultiModelConsensusData? = null,
        googleAlerts: PublicAlertsResponse? = null,
        sawsAlerts: List<OfficialWeatherAlert> = emptyList()
    ): WeatherDashboardData {
        val zoneId = resolveZoneId(omForecast.timezone)
        val nowZoned = ZonedDateTime.now(zoneId)
        val currentLocalDateHour = nowZoned.toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val currentLocalHour = nowZoned.hour
        val nowHourKey = currentLocalDateHour.format(HOUR_KEY_FORMATTER)

        val omCurrent = omForecast.current
        val isDay = (omCurrent?.isDay ?: 1) == 1
        val wmoCode = omCurrent?.weatherCode ?: 0
        val descText = mapWmoCodeToCondition(wmoCode, isDay)

        val skyTheme = when {
            wmoCode in 95..99 -> SkyThemeType.THUNDERSTORM
            wmoCode in 71..77 || wmoCode in 85..86 || wmoCode in listOf(56, 57, 66, 67) -> SkyThemeType.SNOW
            wmoCode in 51..65 || wmoCode in 80..82 -> SkyThemeType.RAIN
            wmoCode in 2..3 || wmoCode in 45..48 -> SkyThemeType.OVERCAST
            !isDay -> SkyThemeType.CLEAR_NIGHT
            else -> SkyThemeType.CLEAR_DAY
        }

        val omHourlyByDateHour = parseOpenMeteoHourlyMap(omForecast.hourly)
        val currentConsensusSlot = omConsensus?.hourlyByTime?.get(nowHourKey)
        val currentModelMap = buildCurrentModelMap(omCurrent?.temperature2m, currentConsensusSlot)

        val currentTempDouble = WeatherConsensus.blendConsensusTemperature(
            googleTemp = null,
            modelTemps = currentModelMap,
            leadHours = 0
        ) ?: omCurrent?.temperature2m ?: 22.0
        val currentTemp = currentTempDouble.roundToInt()

        val humidity = omCurrent?.relativeHumidity2m ?: 50
        val windSpeed = omCurrent?.windSpeed10m?.roundToInt() ?: 0
        val feelsLike = WeatherConsensus.computeSanitizedFeelsLike(
            actualTempC = currentTempDouble,
            googleFeelsC = null,
            omFeelsC = omCurrent?.apparentTemperature,
            humidityPercent = humidity,
            windKmH = windSpeed.toDouble(),
            isDaytime = isDay
        )
        val feelsReason = buildFeelsLikeReason(currentTemp, feelsLike, humidity, windSpeed, isDay)

        val ensemble = WeatherConsensus.computeMultiModelDispersion(
            centerTemp = currentTemp,
            tempSamples = currentModelMap.values.toList(),
            popSamples = omForecast.daily?.precipitationProbabilityMax?.firstOrNull()?.let { listOf(it) } ?: emptyList(),
            dayIndex = 0
        )

        val rawHighTemp = omForecast.daily?.temperature2mMax?.firstOrNull()?.roundToInt() ?: currentTemp
        val rawLowTemp = omForecast.daily?.temperature2mMin?.firstOrNull()?.roundToInt() ?: currentTemp
        val locationName = resolvedLocationName?.takeIf { it.isNotBlank() }
            ?: omForecast.timezone?.substringAfterLast("/")?.replace("_", " ")
            ?: "Current Location"

        val hourlyItems = extractNext24HoursFromOpenMeteo(omForecast, zoneId, omConsensus).mapIndexed { idx, item ->
            if (idx == 0) item.copy(tempDegrees = currentTemp) else item
        }

        val useNative15Min = WeatherConsensus.hasNative15MinNowcast(latitude, longitude)
        val nowcast = if (useNative15Min && omForecast.minutely15 != null) {
            parsePrecipitationNowcast(omForecast.minutely15, zoneId)
                ?: buildShortTermRainForecast(hourlyItems)
        } else {
            buildShortTermRainForecast(hourlyItems)
        }

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
            val fullDateStr = "${localDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())}, " +
                "${localDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${localDate.dayOfMonth}"

            val dCode = omForecast.daily?.weatherCode?.getOrNull(idx) ?: 0
            val dCond = mapWmoCodeToCondition(dCode, true)
            val fallbackWindSpeed = omForecast.daily?.windSpeed10mMax?.getOrNull(idx)?.roundToInt() ?: 0
            val fallbackWindDir = omForecast.daily?.windDirection10mDominant?.getOrNull(idx)?.let { degreesToCardinal(it) } ?: "—"
            val uvVal = omForecast.daily?.uvIndexMax?.getOrNull(idx)?.roundToInt() ?: 0
            val sunriseStr = formatIsoTime(omForecast.daily?.sunrise?.getOrNull(idx), zoneId, "06:15")
            val sunsetStr = formatIsoTime(omForecast.daily?.sunset?.getOrNull(idx), zoneId, "18:15")

            val dayHours = (0..23).mapNotNull { h ->
                val slotLdt = localDate.atTime(h, 0)
                val key = slotLdt.format(HOUR_KEY_FORMATTER)
                val slot = omHourlyByDateHour[key] ?: return@mapNotNull null
                val lead = ChronoUnit.HOURS.between(currentLocalDateHour, slotLdt).toInt().coerceAtLeast(0)
                val cTemp = omConsensus?.hourlyByTime?.get(key)?.tempsByModel
                val cPrecip = lookupAlignedConsensusPrecip(slotLdt, omConsensus)
                val alignedPrecipSlot = lookupAlignedPrecipSlot(slotLdt, omHourlyByDateHour)
                val temp = if (!cTemp.isNullOrEmpty()) {
                    WeatherConsensus.blendConsensusTemperature(null, cTemp, lead)?.roundToInt() ?: slot.temp.roundToInt()
                } else {
                    slot.temp.roundToInt()
                }
                val pop = blendHourlyPop(null, alignedPrecipSlot?.pop ?: slot.pop, cPrecip?.popByModel, lead)
                val mm = blendHourlyPrecipMm(null, alignedPrecipSlot?.precipMm ?: slot.precipMm, cPrecip?.precipByModel, lead)
                slot.toHourlyItem(String.format(Locale.getDefault(), "%02d:00", h)).copy(
                    tempDegrees = temp,
                    rainProbability = pop,
                    precipitationMm = roundOneDecimal(mm)
                )
            }

            val amWind = extractPeriodWind(localDate, 6..11, emptyList(), omHourlyByDateHour, fallbackWindSpeed, fallbackWindDir)
            val pmWind = extractPeriodWind(localDate, 12..17, emptyList(), omHourlyByDateHour, fallbackWindSpeed, fallbackWindDir)
            val nightWind = extractPeriodWind(localDate, 18..23, emptyList(), omHourlyByDateHour, fallbackWindSpeed, fallbackWindDir)

            val windowHighs = computeWindowModelHighs(localDate, omConsensus, omHourlyByDateHour)
            val windowLows = computeOvernightWindowModelLows(localDate, omConsensus, omHourlyByDateHour)
            val hasAlignedOvernight = windowLows.isNotEmpty()
            val leadMidday = (idx * 24 + 12).coerceAtLeast(1)

            val hourlyMax = dayHours.maxOfOrNull { it.tempDegrees }
            val hourlyMin = dayHours.minOfOrNull { it.tempDegrees }
            val rawDayMax = WeatherConsensus.blendConsensusTemperature(null, windowHighs, leadMidday)?.roundToInt()
                ?: omForecast.daily?.temperature2mMax?.getOrNull(idx)?.roundToInt()
                ?: hourlyMax ?: rawHighTemp
            val rawDayMin = WeatherConsensus.blendConsensusTemperature(null, windowLows, leadMidday + 12)?.roundToInt()
                ?: omForecast.daily?.temperature2mMin?.getOrNull(idx)?.roundToInt()
                ?: hourlyMin ?: rawLowTemp

            val candidateMax = listOfNotNull(rawDayMax, hourlyMax, if (idx == 0) currentTemp else null).maxOrNull() ?: rawDayMax
            val candidateMin = if (hasAlignedOvernight) {
                val tonightMin = computeOvernightHourlyMin(localDate, dayHours, currentLocalHour, idx == 0)
                listOfNotNull(rawDayMin, tonightMin, if (idx == 0 && (currentLocalHour >= 19 || currentTemp < rawDayMin)) currentTemp else null).minOrNull() ?: rawDayMin
            } else {
                listOfNotNull(rawDayMin, hourlyMin, if (idx == 0) currentTemp else null).minOrNull() ?: rawDayMin
            }
            val dayMax = maxOf(candidateMax, candidateMin)
            val dayMin = minOf(candidateMax, candidateMin)

            val dayPops = computeWindowMaxPopByModel(localDate, 7..18, omConsensus, omHourlyByDateHour)
            val nightPops = computeOvernightMaxPopByModel(localDate, omConsensus, omHourlyByDateHour)
            val rawDayPop = omForecast.daily?.precipitationProbabilityMax?.getOrNull(idx) ?: 0
            val dayRainPop = WeatherConsensus.blendConsensusRainProbability(null, dayPops, leadMidday) ?: rawDayPop
            val nightRainPop = WeatherConsensus.blendConsensusRainProbability(null, nightPops, leadMidday + 12) ?: rawDayPop
            val maxHourlyPop = dayHours.maxOfOrNull { it.rainProbability } ?: 0
            val boundedDayPop = maxOf(dayRainPop, nightRainPop, maxHourlyPop)

            val windowPrecip = compute24hWindowPrecipByModel(localDate, omConsensus, omHourlyByDateHour)
            val blendedPrecip = WeatherConsensus.blendConsensusPrecipMm(null, windowPrecip, leadMidday)
                ?: omForecast.daily?.precipitationSum?.getOrNull(idx)
                ?: dayHours.sumOf { it.precipitationMm }

            val avgHumidity = if (dayHours.isNotEmpty()) {
                dayHours.map { it.humidityPercent }.average().roundToInt()
            } else {
                humidity
            }
            val matchingSlots = (0..23).mapNotNull { h ->
                omHourlyByDateHour[String.format(Locale.US, "%sT%02d:00", dateIso, h)]
            }
            val avgCloudCover = if (matchingSlots.isNotEmpty()) {
                matchingSlots.map { it.cloudCover }.average().roundToInt()
            } else {
                omCurrent?.cloudCover ?: 20
            }

            val dayDispersion = WeatherConsensus.computeMultiModelDispersion(
                centerTemp = dayMax,
                tempSamples = windowHighs.values.toList(),
                popSamples = dayPops.values.toList(),
                dayIndex = idx
            )

            DailyItem(
                dayLabel = dayLabel,
                dateText = dateText,
                minTemp = dayMin,
                maxTemp = dayMax,
                rainProbability = boundedDayPop,
                iconUri = null,
                conditionText = dCond,
                morningWind = formatPeriodWind("AM", amWind.first, amWind.second),
                afternoonWind = formatPeriodWind("PM", pmWind.first, pmWind.second),
                fullDateString = fullDateStr,
                feelsLikeMaxTemp = omForecast.daily?.apparentTemperatureMax?.getOrNull(idx)?.roundToInt() ?: dayMax,
                feelsLikeMinTemp = omForecast.daily?.apparentTemperatureMin?.getOrNull(idx)?.roundToInt() ?: dayMin,
                precipitationMm = roundOneDecimal(blendedPrecip),
                uvIndex = uvVal,
                uvCategory = categorizeUv(uvVal),
                humidityPercent = avgHumidity,
                thunderstormProbability = maxOf(if (dCode in 95..99) 70 else 0, dayHours.maxOfOrNull { it.thunderstormProbability } ?: 0),
                cloudCoverPercent = avgCloudCover,
                sunriseTime = sunriseStr,
                sunsetTime = sunsetStr,
                dayConditionText = dCond,
                nightConditionText = mapWmoCodeToCondition(dCode, false),
                dayWindSpeedKmH = maxOf(amWind.first, pmWind.first),
                dayWindDirection = amWind.second,
                nightWindSpeedKmH = nightWind.first,
                nightWindDirection = nightWind.second,
                hourlyForecast = if (hasAlignedOvernight) dayHours else alignHourlyExtremes(dayHours, dayMax, dayMin),
                confidencePercent = dayDispersion.confidencePercent,
                modelSpreadDegrees = dayDispersion.modelSpreadDegrees,
                dayRainProbability = dayRainPop,
                nightRainProbability = nightRainPop,
                nightWind = formatPeriodWind("Night", nightWind.first, nightWind.second)
            )
        }

        val highTemp = dailyItems.firstOrNull()?.maxTemp ?: maxOf(rawHighTemp, rawLowTemp, currentTemp)
        val lowTemp = dailyItems.firstOrNull()?.minTemp ?: minOf(rawHighTemp, rawLowTemp, currentTemp)

        val todayHourly = dailyItems.firstOrNull()?.hourlyForecast.orEmpty()
        val calibratedHourlyItems = calibrateRollingHourlyPeak(
            hourlyList = hourlyItems,
            todayHourly = todayHourly,
            todayHigh = highTemp,
            currentHour = currentLocalHour
        )

        val fallbackModelCount = omConsensus?.activeModels?.size?.coerceAtLeast(1) ?: 1
        val heroModelLabel = if (fallbackModelCount >= 3) {
            "$fallbackModelCount-Model Open-Meteo Consensus"
        } else {
            "Open-Meteo High-Res"
        }

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
            modelSourcesLabel = heroModelLabel,
            lastUpdatedEpochMs = System.currentTimeMillis(),
            feelsLikeReason = feelsReason,
            tempP10 = ensemble.p10Temp,
            tempP90 = ensemble.p90Temp,
            dispersionSummary = ensemble.modelAgreementSummary,
            modelCount = fallbackModelCount
        )

        val sunriseToday = dailyItems.firstOrNull()?.sunriseTime ?: "06:15"
        val sunsetToday = dailyItems.firstOrNull()?.sunsetTime ?: "18:15"
        val aqiSummary = WeatherConsensus.resolveAirQuality(omAqi?.current)
        val omWindDeg = omCurrent?.windDirection10m ?: 0
        val omWindDir = degreesToCardinal(omWindDeg)
        val omMaxUv = omForecast.daily?.uvIndexMax?.firstOrNull()?.roundToInt() ?: (dailyItems.firstOrNull()?.uvIndex ?: 0)

        val currentOmHourSlot = omHourlyByDateHour[nowHourKey]
        // Never fall back to daily peak UV at night
        val currentUv = if (!isDay) {
            0
        } else {
            currentOmHourSlot?.uvIndex ?: dailyItems.firstOrNull()?.uvIndex ?: 0
        }
        val currentVisibilityKm = roundOneDecimal(
            (currentOmHourSlot?.visibilityMeters ?: 10000.0) / 1000.0
        )
        val (pressure, pressureLabel) = resolvePressureHpa(
            googleMsl = null,
            omMsl = omCurrent?.pressureMsl,
            omSurface = omCurrent?.surfacePressure,
            elevationMeters = omForecast.elevation,
            tempC = currentTempDouble
        )
        val dewPoint = omCurrent?.dewPoint2m?.roundToInt()
            ?: WeatherConsensus.computeDewPoint(currentTempDouble, humidity)

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
            dewPointDegrees = dewPoint,
            pressureHpa = pressure,
            sunriseTime = sunriseToday,
            sunsetTime = sunsetToday,
            aqiValue = aqiSummary.value,
            aqiCategory = aqiSummary.category,
            pm25 = aqiSummary.pm25,
            visibilityKm = currentVisibilityKm,
            sunProgress = computeSolarProgress(sunriseToday, sunsetToday, zoneId),
            feelsLikeDegrees = feelsLike,
            feelsLikeReason = feelsReason,
            dominantPollutantLabel = aqiSummary.dominantPollutantLabel,
            aqiScaleLabel = aqiSummary.scaleLabel,
            pressureLabel = pressureLabel
        )

        val parsedGoogleAlerts = parseGooglePublicAlerts(googleAlerts, zoneId)
        val officialAlerts = (parsedGoogleAlerts + sawsAlerts)
            .distinctBy { "${it.title.lowercase(Locale.US)}|${it.areaName.lowercase(Locale.US)}" }
            .sortedByDescending { it.severity.ordinal }

        val hourlyStormList = hourlyItems.map { it.thunderstormProbability }
        val currentStormProb = if (wmoCode in 95..99) 70 else 0
        val maxOmGustNext24 = (0 until 24).mapNotNull { offset ->
            omHourlyByDateHour[currentLocalDateHour.plusHours(offset.toLong()).format(HOUR_KEY_FORMATTER)]?.windGusts
        }.maxOrNull() ?: (omCurrent?.windGusts10m?.roundToInt() ?: 0)

        val alert = buildSevereAlertInfo(
            hourlyItems = hourlyItems,
            hourlyStormList = hourlyStormList,
            currentStormProb = currentStormProb,
            periodFallbackStormProb = 0,
            condType = if (wmoCode in 95..99) "THUNDERSTORM" else "",
            currentLocalHour = currentLocalHour,
            maxGustNext24 = maxOmGustNext24,
            officialAlerts = officialAlerts
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

    private fun resolveSkyTheme(
        condType: String,
        descText: String,
        thunderstormProb: Int,
        isDay: Boolean
    ): SkyThemeType {
        val isClearOrSunny = condType.contains("CLEAR", ignoreCase = true) ||
            condType.contains("SUN", ignoreCase = true) ||
            descText.contains("clear", ignoreCase = true) ||
            descText.contains("sun", ignoreCase = true)

        return when {
            condType.contains("THUNDER", ignoreCase = true) ||
                condType.contains("HAIL", ignoreCase = true) ||
                descText.contains("thunder", ignoreCase = true) ||
                descText.contains("hail", ignoreCase = true) ||
                (!isClearOrSunny && thunderstormProb >= 40) ->
                SkyThemeType.THUNDERSTORM
            condType.contains("SNOW", ignoreCase = true) ||
                condType.contains("SLEET", ignoreCase = true) ||
                condType.contains("BLIZZARD", ignoreCase = true) ||
                condType.contains("ICE", ignoreCase = true) ||
                condType.contains("FREEZING", ignoreCase = true) ||
                descText.contains("snow", ignoreCase = true) ||
                descText.contains("sleet", ignoreCase = true) ||
                descText.contains("blizzard", ignoreCase = true) ||
                descText.contains("freezing", ignoreCase = true) ->
                SkyThemeType.SNOW
            condType.contains("RAIN", ignoreCase = true) ||
                condType.contains("DRIZZLE", ignoreCase = true) ||
                condType.contains("SHOWER", ignoreCase = true) ||
                descText.contains("rain", ignoreCase = true) ||
                descText.contains("drizzle", ignoreCase = true) ||
                descText.contains("shower", ignoreCase = true) ->
                SkyThemeType.RAIN
            condType.contains("CLOUD", ignoreCase = true) ||
                condType.contains("OVERCAST", ignoreCase = true) ||
                condType.contains("FOG", ignoreCase = true) ||
                condType.contains("MIST", ignoreCase = true) ||
                condType.contains("HAZE", ignoreCase = true) ||
                descText.contains("cloud", ignoreCase = true) ||
                descText.contains("overcast", ignoreCase = true) ||
                descText.contains("fog", ignoreCase = true) ||
                descText.contains("mist", ignoreCase = true) ->
                SkyThemeType.OVERCAST
            !isDay -> SkyThemeType.CLEAR_NIGHT
            else -> SkyThemeType.CLEAR_DAY
        }
    }

    private fun buildSevereAlertInfo(
        hourlyItems: List<HourlyItem>,
        hourlyStormList: List<Int>,
        currentStormProb: Int,
        periodFallbackStormProb: Int,
        condType: String,
        currentLocalHour: Int,
        maxGustNext24: Int,
        officialAlerts: List<OfficialWeatherAlert>
    ): SevereAlertInfo {
        fun qualifyStormHourLabel(idx: Int, rawLabel: String?): String? {
            if (rawLabel.isNullOrBlank() || idx <= 0 || rawLabel.equals("Now", ignoreCase = true)) return rawLabel
            val parsedHour = parseHourInt(rawLabel)
            val crossesMidnight = if (parsedHour != null) {
                parsedHour < currentLocalHour || (parsedHour == currentLocalHour && idx >= 12)
            } else {
                currentLocalHour + idx >= 24
            }
            return if (crossesMidnight && !rawLabel.startsWith("Tomorrow", ignoreCase = true)) {
                "Tomorrow $rawLabel"
            } else {
                rawLabel
            }
        }

        val peakIdx = hourlyStormList.indices.maxByOrNull { hourlyStormList[it] } ?: -1
        val peakHourlyProb = if (peakIdx >= 0) hourlyStormList[peakIdx] else 0
        val peakHourTimeStr = when {
            peakIdx > 0 && peakHourlyProb > 0 -> qualifyStormHourLabel(peakIdx, hourlyItems.getOrNull(peakIdx)?.hourLabel)
            (peakIdx == 0 && peakHourlyProb > 0) || currentStormProb > 0 -> "Now"
            else -> null
        }

        val firstOnsetIdx = hourlyStormList.indexOfFirst { it >= 30 }
        val onsetTimeStr = if (firstOnsetIdx > 0) {
            qualifyStormHourLabel(firstOnsetIdx, hourlyItems.getOrNull(firstOnsetIdx)?.hourLabel)
        } else null

        val stormProb = maxOf(currentStormProb, peakHourlyProb, periodFallbackStormProb)
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

        val (stormRiskLevel, stormTitle, stormDesc) = when {
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

        val topOfficial = officialAlerts.firstOrNull()
        return if (topOfficial != null && topOfficial.severity.ordinal >= stormRiskLevel.ordinal) {
            val officialPrefix = if (topOfficial.severityLabel.isNotBlank()) {
                "${topOfficial.title} (${topOfficial.severityLabel})"
            } else {
                topOfficial.title
            }
            val officialDetails = buildString {
                if (topOfficial.areaName.isNotBlank()) append("Area: ${topOfficial.areaName}. ")
                if (topOfficial.description.isNotBlank()) append(topOfficial.description.trim().removeSuffix(".") + ". ")
                if (topOfficial.expiresLabel.isNotBlank()) append("Active until ${topOfficial.expiresLabel}.")
            }.trim().ifBlank { stormDesc }

            SevereAlertInfo(
                riskLevel = maxOf(stormRiskLevel, topOfficial.severity),
                thunderstormProbability = stormProb,
                title = officialPrefix,
                subtitle = officialDetails,
                isVisible = true,
                peakHourLabel = peakHourTimeStr,
                hourlyStormProbabilities = hourlyStormList,
                officialAlerts = officialAlerts,
                sourceLabel = topOfficial.source
            )
        } else {
            SevereAlertInfo(
                riskLevel = stormRiskLevel,
                thunderstormProbability = stormProb,
                title = stormTitle,
                subtitle = stormDesc,
                isVisible = true,
                peakHourLabel = peakHourTimeStr,
                hourlyStormProbabilities = hourlyStormList,
                officialAlerts = officialAlerts,
                sourceLabel = topOfficial?.source
            )
        }
    }

    internal fun parseGooglePublicAlerts(
        response: PublicAlertsResponse?,
        zoneId: ZoneId,
        now: Instant = Instant.now()
    ): List<OfficialWeatherAlert> {
        val rawAlerts = response?.weatherAlerts.orEmpty()
        if (rawAlerts.isEmpty()) return emptyList()

        return rawAlerts.mapNotNull { item ->
            val expInstant = item.expirationTime?.let {
                try { OffsetDateTime.parse(it).toInstant() } catch (_: Exception) { null }
            }
            if (expInstant != null && expInstant.isBefore(now)) return@mapNotNull null

            val title = item.alertTitle?.text?.trim().orEmpty()
                .ifBlank { item.eventType?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty() }
                .ifBlank { "Weather Alert" }

            val severity = when (item.severity?.trim()?.uppercase(Locale.US)) {
                "EXTREME", "SEVERE" -> SeverityRiskLevel.SEVERE
                "MODERATE" -> SeverityRiskLevel.HIGH
                "MINOR" -> SeverityRiskLevel.MODERATE
                else -> SeverityRiskLevel.LOW
            }
            val expiresLabel = expInstant?.let {
                DateTimeFormatter.ofPattern("EEE HH:mm", Locale.getDefault())
                    .withZone(zoneId)
                    .format(it)
            }.orEmpty()

            OfficialWeatherAlert(
                title = title,
                severity = severity,
                eventType = item.eventType.orEmpty(),
                source = "Official Alert",
                areaName = item.areaName.orEmpty(),
                description = item.description.orEmpty().trim(),
                instruction = item.instruction.orEmpty().filter { it.isNotBlank() }.joinToString(" "),
                expiresLabel = expiresLabel,
                severityLabel = item.severity?.lowercase()?.replaceFirstChar { it.uppercase() }.orEmpty()
            )
        }
    }

    private data class OmHourSlot(
        val temp: Double,
        val pop: Int,
        val precipMm: Double,
        val windSpeed: Int,
        val windDirection: String,
        val windGusts: Int,
        val uvIndex: Int,
        val humidity: Int,
        val isDay: Boolean,
        val conditionText: String,
        val stormProb: Int,
        val visibilityMeters: Double? = null,
        val cloudCover: Int = 20,
        val dewPoint: Double? = null,
        val cape: Double? = null
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
        val result = LinkedHashMap<String, OmHourSlot>(times.size)
        for (i in times.indices) {
            val tStr = times[i]
            // Preserve the first occurrence on DST fall-back duplicate clock hours
            if (result.containsKey(tStr)) continue

            val isDay = (hourly.isDay?.getOrNull(i) ?: 1) == 1
            val code = hourly.weatherCode?.getOrNull(i) ?: 0
            val pop = hourly.precipitationProbability?.getOrNull(i) ?: 0
            val precip = hourly.precipitation?.getOrNull(i) ?: 0.0
            val windSpd = hourly.windSpeed10m?.getOrNull(i)?.roundToInt() ?: 0
            val windDir = hourly.windDirection10m?.getOrNull(i)?.let { degreesToCardinal(it) } ?: "—"
            val gusts = hourly.windGusts10m?.getOrNull(i)?.roundToInt() ?: windSpd
            val cape = hourly.cape?.getOrNull(i)
            val cloud = hourly.cloudCover?.getOrNull(i) ?: when (code) {
                0 -> 0
                1 -> 20
                2 -> 50
                3 -> 95
                45, 48 -> 90
                in 51..99 -> 85
                else -> 20
            }
            val stormProb = when {
                code in 95..99 -> maxOf(pop, 60)
                // High convective energy + active showers/rain -> moderate localized storm potential
                cape != null && cape >= 1200.0 && (code in 80..82 || precip >= 1.0) && pop >= 40 ->
                    minOf(pop, 45)
                else -> 0
            }
            result[tStr] = OmHourSlot(
                temp = hourly.temperature2m?.getOrNull(i) ?: 20.0,
                pop = pop,
                precipMm = precip,
                windSpeed = windSpd,
                windDirection = windDir,
                windGusts = gusts,
                uvIndex = hourly.uvIndex?.getOrNull(i)?.roundToInt() ?: 0,
                humidity = hourly.relativeHumidity2m?.getOrNull(i) ?: 50,
                isDay = isDay,
                conditionText = mapWmoCodeToCondition(code, isDay),
                stormProb = stormProb,
                visibilityMeters = hourly.visibility?.getOrNull(i),
                cloudCover = cloud,
                dewPoint = hourly.dewPoint2m?.getOrNull(i),
                cape = cape
            )
        }
        return result
    }

    /**
     * Aligns Open-Meteo's preceding-hour precipitation label (T+1h = rain from T to T+1h)
     * with Google's start-hour interval (T = rain from T to T+1h).
     * Falls back to T at the very end of the series or in single-slot unit tests.
     */
    private fun lookupAlignedPrecipSlot(
        slotDateTime: LocalDateTime?,
        omHourlyByDateHour: Map<String, OmHourSlot>
    ): OmHourSlot? {
        if (slotDateTime == null) return null
        val shiftedKey = slotDateTime.plusHours(1).format(HOUR_KEY_FORMATTER)
        return omHourlyByDateHour[shiftedKey] ?: omHourlyByDateHour[slotDateTime.format(HOUR_KEY_FORMATTER)]
    }

    private fun lookupAlignedConsensusPrecip(
        slotDateTime: LocalDateTime?,
        omConsensus: WeatherConsensus.MultiModelConsensusData?
    ): WeatherConsensus.MultiModelHourSlot? {
        if (slotDateTime == null || omConsensus == null) return null
        val shiftedKey = slotDateTime.plusHours(1).format(HOUR_KEY_FORMATTER)
        return omConsensus.hourlyByTime[shiftedKey]
            ?: omConsensus.hourlyByTime[slotDateTime.format(HOUR_KEY_FORMATTER)]
    }

    private fun buildBlendedHourlyItem(
        label: String,
        slotDateTime: LocalDateTime?,
        gItem: ForecastHour,
        omHourlyByDateHour: Map<String, OmHourSlot>,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        leadHours: Int,
        fallbackTemp: Int,
        fallbackCondition: String
    ): HourlyItem {
        val exactKey = slotDateTime?.format(HOUR_KEY_FORMATTER)
        val omExact = exactKey?.let { omHourlyByDateHour[it] }
        val omPrecipSlot = lookupAlignedPrecipSlot(slotDateTime, omHourlyByDateHour)
        val consensusExact = exactKey?.let { omConsensus?.hourlyByTime?.get(it) }
        val consensusPrecip = lookupAlignedConsensusPrecip(slotDateTime, omConsensus)

        val tempModels = consensusExact?.tempsByModel?.takeIf { it.isNotEmpty() }
            ?: omExact?.temp?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) }
            ?: emptyMap()
        val hTemp = WeatherConsensus.blendConsensusTemperature(
            googleTemp = gItem.temperature?.degrees,
            modelTemps = tempModels,
            leadHours = leadHours
        )?.roundToInt() ?: fallbackTemp

        val gPop = gItem.precipitation?.probability?.percent
        val hPop = blendHourlyPop(
            googlePop = gPop,
            omPop = omPrecipSlot?.pop,
            consensusPops = consensusPrecip?.popByModel,
            leadHours = leadHours
        )

        val gPrecip = gItem.precipitation?.qpf?.quantity
        val hPrecipMm = blendHourlyPrecipMm(
            googlePrecip = gPrecip,
            omPrecip = omPrecipSlot?.precipMm,
            consensusPrecips = consensusPrecip?.precipByModel,
            leadHours = leadHours
        )

        val hourConditionText = gItem.weatherCondition?.description?.text
            ?: gItem.weatherCondition?.type?.replace("_", " ")?.lowercase()?.replaceFirstChar { it.uppercase() }
            ?: omExact?.conditionText
            ?: fallbackCondition

        val hourIsDay = gItem.isDaytime ?: omExact?.isDay ?: ((slotDateTime?.hour ?: 12) in 6..18)
        val hWindSpeed = gItem.wind?.speed?.value?.roundToInt() ?: omExact?.windSpeed ?: 0
        val hUv = gItem.uvIndex ?: omExact?.uvIndex ?: 0
        val hHumidity = gItem.relativeHumidity ?: omExact?.humidity ?: 0
        val gStormProb = gItem.thunderstormProbability ?: (
            if (gItem.weatherCondition?.type?.contains("THUNDER", ignoreCase = true) == true ||
                gItem.weatherCondition?.description?.text?.contains("thunder", ignoreCase = true) == true
            ) {
                maxOf(gPop ?: 0, 40)
            } else {
                0
            }
        )
        val omStormProb = omExact?.stormProb ?: 0
        val hStormProb = maxOf(gStormProb, omStormProb)

        return HourlyItem(
            hourLabel = label,
            tempDegrees = hTemp,
            rainProbability = hPop,
            iconUri = gItem.weatherCondition?.iconBaseUri,
            isDaytime = hourIsDay,
            conditionText = hourConditionText,
            windSpeedKmH = hWindSpeed,
            precipitationMm = roundOneDecimal(hPrecipMm),
            uvIndex = hUv,
            humidityPercent = hHumidity,
            thunderstormProbability = hStormProb
        )
    }

    private fun blendHourlyPop(
        googlePop: Int?,
        omPop: Int?,
        consensusPops: Map<String, Int>?,
        leadHours: Int
    ): Int {
        val modelMap = consensusPops?.takeIf { it.isNotEmpty() }
            ?: omPop?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) }
            ?: emptyMap()
        return WeatherConsensus.blendConsensusRainProbability(googlePop, modelMap, leadHours) ?: 0
    }

    private fun blendHourlyPrecipMm(
        googlePrecip: Double?,
        omPrecip: Double?,
        consensusPrecips: Map<String, Double>?,
        leadHours: Int
    ): Double {
        val modelMap = consensusPrecips?.takeIf { it.isNotEmpty() }
            ?: omPrecip?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) }
            ?: emptyMap()
        return WeatherConsensus.blendConsensusPrecipMm(googlePrecip, modelMap, leadHours) ?: 0.0
    }

    private fun buildCurrentModelMap(
        omCurrentTemp: Double?,
        consensusSlot: WeatherConsensus.MultiModelHourSlot?
    ): Map<String, Double> {
        val consensusTemps = consensusSlot?.tempsByModel
        if (!consensusTemps.isNullOrEmpty()) return consensusTemps
        return omCurrentTemp?.let { mapOf(WeatherConsensus.MODEL_BEST_MATCH to it) } ?: emptyMap()
    }

    private fun computeWindowModelHighs(
        date: LocalDate,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        omHourly: Map<String, OmHourSlot>
    ): Map<String, Double> {
        val hours = (7..19).map { date.atTime(it, 0).format(HOUR_KEY_FORMATTER) }
        if (omConsensus != null && omConsensus.activeModels.isNotEmpty()) {
            val perModel = LinkedHashMap<String, Double>()
            for (model in omConsensus.activeModels) {
                val vals = hours.mapNotNull { omConsensus.hourlyByTime[it]?.tempsByModel?.get(model) }
                if (vals.size >= 4) perModel[model] = vals.max()
            }
            if (perModel.isNotEmpty()) return perModel
        }
        val singleVals = hours.mapNotNull { omHourly[it]?.temp }
        return if (singleVals.size >= 4) {
            mapOf(WeatherConsensus.MODEL_BEST_MATCH to singleVals.max())
        } else {
            emptyMap()
        }
    }

    /**
     * Computes each model's overnight low over Google's exact nighttime window:
     * 19:00 on [date] through 07:00 on [date + 1 day].
     */
    private fun computeOvernightWindowModelLows(
        date: LocalDate,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        omHourly: Map<String, OmHourSlot>
    ): Map<String, Double> {
        val nextDate = date.plusDays(1)
        val hours = (19..23).map { date.atTime(it, 0).format(HOUR_KEY_FORMATTER) } +
            (0..7).map { nextDate.atTime(it, 0).format(HOUR_KEY_FORMATTER) }

        if (omConsensus != null && omConsensus.activeModels.isNotEmpty()) {
            val perModel = LinkedHashMap<String, Double>()
            for (model in omConsensus.activeModels) {
                val vals = hours.mapNotNull { omConsensus.hourlyByTime[it]?.tempsByModel?.get(model) }
                if (vals.size >= 4) perModel[model] = vals.min()
            }
            if (perModel.isNotEmpty()) return perModel
        }
        val singleVals = hours.mapNotNull { omHourly[it]?.temp }
        return if (singleVals.size >= 4) {
            mapOf(WeatherConsensus.MODEL_BEST_MATCH to singleVals.min())
        } else {
            emptyMap()
        }
    }

    private fun computeOvernightHourlyMin(
        date: LocalDate,
        dayHourly: List<HourlyItem>,
        currentLocalHour: Int,
        isToday: Boolean
    ): Int? {
        val eveningHours = dayHourly.filter { item ->
            val h = parseHourInt(item.hourLabel) ?: return@filter false
            h >= 19 && (!isToday || h >= currentLocalHour)
        }
        return eveningHours.minOfOrNull { it.tempDegrees }
    }

    private fun computeWindowMaxPopByModel(
        date: LocalDate,
        hoursRange: IntRange,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        omHourly: Map<String, OmHourSlot>
    ): Map<String, Int> {
        // Shift +1h so Open-Meteo preceding-hour labels match [h, h+1)
        val keys = hoursRange.map { date.atTime(it, 0).plusHours(1).format(HOUR_KEY_FORMATTER) }
        if (omConsensus != null && omConsensus.activeModels.isNotEmpty()) {
            val perModel = LinkedHashMap<String, Int>()
            for (model in omConsensus.activeModels) {
                val vals = keys.mapNotNull { omConsensus.hourlyByTime[it]?.popByModel?.get(model) }
                if (vals.size >= 4) perModel[model] = vals.max()
            }
            if (perModel.isNotEmpty()) return perModel
        }
        val singleVals = keys.mapNotNull { omHourly[it]?.pop }
        return if (singleVals.size >= 4) {
            mapOf(WeatherConsensus.MODEL_BEST_MATCH to singleVals.max())
        } else {
            emptyMap()
        }
    }

    private fun computeOvernightMaxPopByModel(
        date: LocalDate,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        omHourly: Map<String, OmHourSlot>
    ): Map<String, Int> {
        val nextDate = date.plusDays(1)
        val keys = (19..23).map { date.atTime(it, 0).plusHours(1).format(HOUR_KEY_FORMATTER) } +
            (0..6).map { nextDate.atTime(it, 0).plusHours(1).format(HOUR_KEY_FORMATTER) }
        if (omConsensus != null && omConsensus.activeModels.isNotEmpty()) {
            val perModel = LinkedHashMap<String, Int>()
            for (model in omConsensus.activeModels) {
                val vals = keys.mapNotNull { omConsensus.hourlyByTime[it]?.popByModel?.get(model) }
                if (vals.size >= 4) perModel[model] = vals.max()
            }
            if (perModel.isNotEmpty()) return perModel
        }
        val singleVals = keys.mapNotNull { omHourly[it]?.pop }
        return if (singleVals.size >= 4) {
            mapOf(WeatherConsensus.MODEL_BEST_MATCH to singleVals.max())
        } else {
            emptyMap()
        }
    }

    /** Sums 07:00..07:00 24h precipitation per model (shifted +1h for preceding-hour OM convention). */
    private fun compute24hWindowPrecipByModel(
        date: LocalDate,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        omHourly: Map<String, OmHourSlot>
    ): Map<String, Double> {
        val start = date.atTime(7, 0)
        val keys = (1..24).map { start.plusHours(it.toLong()).format(HOUR_KEY_FORMATTER) }
        if (omConsensus != null && omConsensus.activeModels.isNotEmpty()) {
            val perModel = LinkedHashMap<String, Double>()
            for (model in omConsensus.activeModels) {
                val vals = keys.mapNotNull { omConsensus.hourlyByTime[it]?.precipByModel?.get(model) }
                if (vals.size >= 12) perModel[model] = vals.sum()
            }
            if (perModel.isNotEmpty()) return perModel
        }
        val singleVals = keys.mapNotNull { omHourly[it]?.precipMm }
        return if (singleVals.size >= 12) {
            mapOf(WeatherConsensus.MODEL_BEST_MATCH to singleVals.sum())
        } else {
            emptyMap()
        }
    }

    private fun extractPeriodWind(
        localDate: LocalDate,
        hoursRange: IntRange,
        gHours: List<ForecastHour>,
        omHourly: Map<String, OmHourSlot>,
        fallbackSpeed: Int,
        fallbackDir: String
    ): Pair<Int, String> {
        val gPeriod = gHours.filter { h ->
            val dt = h.displayDateTime
            dt?.year == localDate.year &&
                dt.month == localDate.monthValue &&
                dt.day == localDate.dayOfMonth &&
                (dt.hours in hoursRange) &&
                h.wind?.speed?.value != null
        }
        if (gPeriod.isNotEmpty()) {
            val maxHour = gPeriod.maxBy { it.wind?.speed?.value ?: 0.0 }
            val spd = maxHour.wind?.speed?.value?.roundToInt() ?: fallbackSpeed
            val dir = parseCardinal(maxHour.wind?.direction?.cardinal).takeIf { it != "—" } ?: fallbackDir
            return spd to dir
        }

        val omPeriod = hoursRange.mapNotNull { h ->
            omHourly[localDate.atTime(h, 0).format(HOUR_KEY_FORMATTER)]
        }
        if (omPeriod.isNotEmpty()) {
            val maxSlot = omPeriod.maxBy { it.windSpeed }
            val dir = maxSlot.windDirection.takeIf { it != "—" } ?: fallbackDir
            return maxSlot.windSpeed to dir
        }
        return fallbackSpeed to fallbackDir
    }

    private fun formatPeriodWind(prefix: String, speedKmH: Int, dir: String): String = when {
        speedKmH > 0 && dir != "—" -> "$prefix: $speedKmH km/h $dir"
        speedKmH == 0 -> "$prefix: Calm"
        else -> "$prefix: $speedKmH km/h"
    }

    private fun resolvePressureHpa(
        googleMsl: Double?,
        omMsl: Double?,
        omSurface: Double?,
        elevationMeters: Double?,
        tempC: Double
    ): Pair<Int, String> {
        if (googleMsl != null) return googleMsl.roundToInt() to "hPa • Sea level"
        if (omMsl != null) return omMsl.roundToInt() to "hPa • Sea level"
        if (omSurface != null) {
            return if (elevationMeters != null) {
                WeatherConsensus.convertSurfacePressureToSeaLevel(omSurface, elevationMeters, tempC) to "hPa • Sea level"
            } else {
                omSurface.roundToInt() to "hPa • Station"
            }
        }
        return 1013 to "hPa • Sea level"
    }

    private fun resolveVisibilityKm(googleVis: Visibility?, omVisMeters: Double?): Double {
        if (googleVis?.distance != null) {
            val km = when (googleVis.unit?.uppercase(Locale.US)) {
                "MILES" -> googleVis.distance * 1.60934
                "METERS" -> googleVis.distance / 1000.0
                else -> googleVis.distance
            }
            return roundOneDecimal(km)
        }
        if (omVisMeters != null) return roundOneDecimal(omVisMeters / 1000.0)
        return 10.0
    }

    private fun extractNext24HoursFromOpenMeteo(
        omForecast: OpenMeteoForecastResponse?,
        zoneId: ZoneId,
        omConsensus: WeatherConsensus.MultiModelConsensusData? = null
    ): List<HourlyItem> {
        val hourly = omForecast?.hourly ?: return emptyList()
        val times = hourly.time ?: return emptyList()
        val nowLdt = ZonedDateTime.now(zoneId).toLocalDateTime().truncatedTo(ChronoUnit.HOURS)
        val nowHourPrefix = nowLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH", Locale.US))
        val matchedIndex = times.indexOfFirst { it.startsWith(nowHourPrefix) }
        val startIndex = if (matchedIndex >= 0) matchedIndex else {
            val todayPrefix = LocalDate.now(zoneId).toString()
            times.indexOfFirst { it.startsWith(todayPrefix) }.takeIf { it >= 0 } ?: return emptyList()
        }
        val map = parseOpenMeteoHourlyMap(hourly)

        return (0 until 24).mapNotNull { offset ->
            val tStr = times.getOrNull(startIndex + offset) ?: return@mapNotNull null
            val slot = map[tStr] ?: return@mapNotNull null
            val slotLdt = try { LocalDateTime.parse(tStr) } catch (_: Exception) { null }
            val label = if (offset == 0) "Now" else tStr.substringAfter("T", "00:00").take(5)
            val cExact = omConsensus?.hourlyByTime?.get(tStr)
            val cPrecip = lookupAlignedConsensusPrecip(slotLdt, omConsensus)
            val alignedPrecip = lookupAlignedPrecipSlot(slotLdt, map)
            val temp = if (cExact != null && cExact.tempsByModel.isNotEmpty()) {
                WeatherConsensus.blendConsensusTemperature(null, cExact.tempsByModel, offset)?.roundToInt()
                    ?: slot.temp.roundToInt()
            } else {
                slot.temp.roundToInt()
            }
            val pop = blendHourlyPop(null, alignedPrecip?.pop ?: slot.pop, cPrecip?.popByModel, offset)
            val mm = blendHourlyPrecipMm(null, alignedPrecip?.precipMm ?: slot.precipMm, cPrecip?.precipByModel, offset)
            slot.toHourlyItem(label).copy(
                tempDegrees = temp,
                rainProbability = pop,
                precipitationMm = roundOneDecimal(mm)
            )
        }
    }

    private fun extractHourlyForDayWithBackfill(
        day: ForecastDay,
        localDate: LocalDate?,
        dayIdx: Int,
        allGoogleHours: List<ForecastHour>,
        omHourlyByDateHour: Map<String, OmHourSlot>,
        omConsensus: WeatherConsensus.MultiModelConsensusData?,
        todayHoursFallback: List<HourlyItem>,
        currentLocalDateHour: LocalDateTime,
        zoneId: ZoneId
    ): List<HourlyItem> {
        val locationToday = LocalDate.now(zoneId)
        val targetDate = localDate ?: day.displayDate?.let { parseDisplayDate(it, zoneId) }
            ?: locationToday.plusDays(dayIdx.toLong())

        val dayYear = targetDate.year
        val dayMonth = targetDate.monthValue
        val dayDay = targetDate.dayOfMonth

        val matchedGoogle = allGoogleHours.filter {
            it.displayDateTime?.year == dayYear &&
                it.displayDateTime.month == dayMonth &&
                it.displayDateTime.day == dayDay
        }

        val fallbackTemp = day.maxTemperature?.degrees?.roundToInt() ?: 0
        val dayHours = (0..23).mapNotNull { h ->
            val hStr = String.format(Locale.getDefault(), "%02d:00", h)
            val slotLdt = targetDate.atTime(h, 0)
            val key = slotLdt.format(HOUR_KEY_FORMATTER)
            val gItem = matchedGoogle.firstOrNull { it.displayDateTime?.hours == h }
            val omSlot = omHourlyByDateHour[key]
            val leadHours = ChronoUnit.HOURS.between(currentLocalDateHour, slotLdt).toInt().coerceAtLeast(0)

            if (gItem != null) {
                // Uses the exact same calibrated blending as the main Hourly Strip!
                buildBlendedHourlyItem(
                    label = hStr,
                    slotDateTime = slotLdt,
                    gItem = gItem,
                    omHourlyByDateHour = omHourlyByDateHour,
                    omConsensus = omConsensus,
                    leadHours = leadHours,
                    fallbackTemp = fallbackTemp,
                    fallbackCondition = if ((gItem.precipitation?.probability?.percent ?: 0) > 30) "Scattered Showers" else "Clear"
                )
            } else if (omSlot != null) {
                val cExact = omConsensus?.hourlyByTime?.get(key)
                val cPrecip = lookupAlignedConsensusPrecip(slotLdt, omConsensus)
                val alignedPrecip = lookupAlignedPrecipSlot(slotLdt, omHourlyByDateHour)
                val temp = if (cExact != null && cExact.tempsByModel.isNotEmpty()) {
                    WeatherConsensus.blendConsensusTemperature(null, cExact.tempsByModel, leadHours)?.roundToInt()
                        ?: omSlot.temp.roundToInt()
                } else {
                    omSlot.temp.roundToInt()
                }
                val pop = blendHourlyPop(null, alignedPrecip?.pop ?: omSlot.pop, cPrecip?.popByModel, leadHours)
                val mm = blendHourlyPrecipMm(null, alignedPrecip?.precipMm ?: omSlot.precipMm, cPrecip?.precipByModel, leadHours)
                omSlot.toHourlyItem(hStr).copy(
                    tempDegrees = temp,
                    rainProbability = pop,
                    precipitationMm = roundOneDecimal(mm)
                )
            } else {
                null
            }
        }

        if (dayHours.isNotEmpty()) return dayHours
        if (dayIdx == 0 && targetDate == locationToday && todayHoursFallback.isNotEmpty()) {
            return todayHoursFallback
        }
        return emptyList()
    }

    /**
     * Honest Short-Term Rain Forecast derived from aligned multi-model hourly consensus
     * for regions without native 15-minute weather models (e.g. South Africa, UK, Asia, Australia).
     */
    internal fun buildShortTermRainForecast(hourlyItems: List<HourlyItem>): PrecipitationNowcast? {
        if (hourlyItems.isEmpty()) return null
        val window = hourlyItems.take(4) // Now + next 3 hours
        val nowItem = window.first()
        val activeNow = nowItem.precipitationMm >= 0.2 || (nowItem.rainProbability >= 60 && nowItem.precipitationMm >= 0.1)
        val maxRate = window.maxOfOrNull { it.precipitationMm } ?: 0.0

        val intervals = window.mapIndexed { idx, item ->
            MinutelyPrecipInterval(
                minuteOffset = idx * 60,
                timeLabel = item.hourLabel,
                precipMm = item.precipitationMm,
                probabilityPercent = item.rainProbability,
                weatherCode = if (item.precipitationMm >= 0.1) 61 else 0
            )
        }

        if (activeNow) {
            val dryIdx = window.drop(1).indexOfFirst { it.precipitationMm < 0.1 && it.rainProbability < 30 }
                .let { if (it >= 0) it + 1 else -1 }
            val summary = if (dryIdx > 0) {
                val clearLabel = window[dryIdx].hourLabel
                "Rain easing around $clearLabel (~${roundOneDecimal(nowItem.precipitationMm)} mm/h now)"
            } else {
                "Rain continuing over the next few hours (peak ~${roundOneDecimal(maxRate)} mm/h)"
            }
            return PrecipitationNowcast(
                summaryText = summary,
                isPrecipitationImminent = true,
                nextPrecipitationOnsetMinutes = null,
                maxRateMmH = roundOneDecimal(maxRate),
                intervals = intervals,
                rainCessationMinutes = if (dryIdx == 1) 60 else null,
                isHighRes15Min = false,
                headlineLabel = "SHORT-TERM RAIN FORECAST",
                onsetRateMmH = roundOneDecimal(nowItem.precipitationMm)
            )
        }

        val firstWetIdx = window.drop(1).indexOfFirst {
            it.precipitationMm >= 0.2 || (it.rainProbability >= 55 && it.precipitationMm >= 0.1)
        }.let { if (it >= 0) it + 1 else -1 }

        if (firstWetIdx > 0) {
            val wetHour = window[firstWetIdx]
            val rate = roundOneDecimal(wetHour.precipitationMm)
            val rateSuffix = if (rate >= 0.1) " (~$rate mm/h)" else ""
            return PrecipitationNowcast(
                summaryText = "Rain likely around ${wetHour.hourLabel} (${wetHour.rainProbability}% chance$rateSuffix)",
                isPrecipitationImminent = firstWetIdx <= 2,
                nextPrecipitationOnsetMinutes = firstWetIdx * 60,
                maxRateMmH = roundOneDecimal(maxRate),
                intervals = intervals,
                rainCessationMinutes = null,
                isHighRes15Min = false,
                headlineLabel = "SHORT-TERM RAIN FORECAST",
                onsetRateMmH = rate
            )
        }

        return PrecipitationNowcast(
            summaryText = "No measurable rain expected in the next 3 hours",
            isPrecipitationImminent = false,
            nextPrecipitationOnsetMinutes = null,
            maxRateMmH = 0.0,
            intervals = intervals,
            rainCessationMinutes = null,
            isHighRes15Min = false,
            headlineLabel = "SHORT-TERM RAIN FORECAST",
            onsetRateMmH = 0.0
        )
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
        val peakHourOfDay = parseHourInt(todayHourly[peakIdx].hourLabel) ?: peakIdx

        if (peakHourOfDay <= currentHour) {
            return hourlyList
        }

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

    private fun parseDisplayDate(date: DisplayDate, zoneId: ZoneId): LocalDate? {
        return try {
            LocalDate.of(date.year ?: LocalDate.now(zoneId).year, date.month ?: 1, date.day ?: 1)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseForecastHourDateTime(dt: DisplayDateTime?): LocalDateTime? {
        if (dt?.year == null || dt.month == null || dt.day == null || dt.hours == null) return null
        return try {
            LocalDateTime.of(dt.year, dt.month, dt.day, dt.hours, 0)
        } catch (_: Exception) {
            null
        }
    }

    private fun parseHourInt(hourLabel: String): Int? {
        return hourLabel.substringBefore(":").trim().toIntOrNull()?.takeIf { it in 0..23 }
    }

    private fun hasIsoTimezoneOffset(isoString: String): Boolean {
        val timePart = isoString.substringAfter("T", "")
        return isoString.endsWith("Z", ignoreCase = true) ||
            timePart.contains("+") ||
            timePart.contains("-")
    }

    internal fun formatIsoTime(isoString: String?, zoneId: ZoneId, fallback: String): String {
        if (isoString.isNullOrBlank()) return fallback
        return try {
            if (hasIsoTimezoneOffset(isoString)) {
                val instant = OffsetDateTime.parse(isoString).toInstant()
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
                if (hasIsoTimezoneOffset(tStr)) {
                    OffsetDateTime.parse(tStr).atZoneSameInstant(zoneId).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
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

        val firstSlot = intervals.first()
        // Only treat rain as "active now" if the wet slot is genuinely happening right now (<= 5 min away)
        val activeNow = firstSlot.minuteOffset <= 5 && firstSlot.precipMm >= 0.1
        // Never trigger phantom "0.0 mm/h" rain alerts: require measurable rainfall (>= 0.1 mm)
        val firstRainIdx = intervals.indexOfFirst { it.precipMm >= 0.1 }
        val maxRate = intervals.maxOfOrNull { it.precipMm } ?: 0.0

        var rainCessationMinutes: Int? = null
        var onsetRateMmH = roundOneDecimal(maxRate * 4.0)
        val (summary, imminent, onsetMinutes) = when {
            activeNow -> {
                onsetRateMmH = roundOneDecimal(firstSlot.precipMm * 4.0)
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
                onsetRateMmH = rate
                Triple("Precipitation starting in ~$onset min (~$rate mm/h)", true, onset)
            }
            else -> {
                onsetRateMmH = 0.0
                Triple("No precipitation expected in next 2 hours", false, null)
            }
        }

        return PrecipitationNowcast(
            summaryText = summary,
            isPrecipitationImminent = imminent,
            nextPrecipitationOnsetMinutes = onsetMinutes,
            maxRateMmH = roundOneDecimal(maxRate * 4.0),
            intervals = intervals,
            rainCessationMinutes = rainCessationMinutes,
            isHighRes15Min = true,
            headlineLabel = "15-MIN RAIN NOWCAST",
            onsetRateMmH = onsetRateMmH
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
        val highPoints = listOfNotNull(gHigh, omHigh)
        val popPoints = listOfNotNull(gPop, omPop)
        val hasMultiModel = tempPoints.size >= 2
        val spread = if (hasMultiModel) abs(tempPoints[0] - tempPoints[1]) else 0.0
        val highSpread = if (highPoints.size >= 2) abs(highPoints[0] - highPoints[1]) else spread
        val popSpread = if (popPoints.size >= 2) abs(popPoints[0] - popPoints[1]) else 0

        val halfSpread = (spread / 2.0).coerceAtLeast(0.5)
        val p10 = kotlin.math.floor(currentTemp - halfSpread).roundToInt()
        val p90 = kotlin.math.ceil(currentTemp + halfSpread).roundToInt()

        val effectiveSpread = maxOf(spread, highSpread * 0.7)
        val confidenceVal = if (hasMultiModel) {
            (98 - (effectiveSpread * 6.5) - (popSpread * 0.3)).roundToInt().coerceIn(52, 98)
        } else {
            75
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
            51, 53, 55 -> "Light Drizzle"
            56, 57 -> "Freezing Drizzle"
            61, 63, 80, 81 -> "Scattered Showers"
            65, 82 -> "Heavy Rain"
            66, 67 -> "Freezing Rain"
            71, 73, 75, 85, 86 -> "Snow Showers"
            77 -> "Snow Grains"
            95 -> "Thunderstorms"
            96, 99 -> "Thunderstorm with Hail"
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

    internal fun categorizeAqi(usAqi: Int): String = WeatherConsensus.categorizeUsAqi(usAqi)

    internal fun buildFeelsLikeReason(
        actual: Int,
        feelsLike: Int,
        humidity: Int,
        windKmH: Int,
        isDaytime: Boolean = true
    ): String {
        val diff = feelsLike - actual
        return when {
            diff >= 2 && humidity >= 60 -> "Humidity is making it feel warmer"
            diff >= 2 && isDaytime -> "Solar warmth is making it feel warmer"
            diff >= 2 -> "Calm, humid air is making it feel slightly warmer"
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
