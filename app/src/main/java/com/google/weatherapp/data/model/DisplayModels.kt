package com.google.weatherapp.data.model

enum class SkyThemeType {
    CLEAR_DAY,
    CLEAR_NIGHT,
    OVERCAST,
    RAIN,
    SNOW,
    THUNDERSTORM
}

enum class SeverityRiskLevel {
    LOW,
    MODERATE,
    HIGH,
    SEVERE
}

enum class WeatherEffectType {
    AUTO,
    RAIN,
    THUNDERSTORM,
    SNOW,
    CLEAR_DAY,
    CLEAR_NIGHT,
    CLOUDY,
    OFF
}

data class SavedLocation(
    val name: String,
    val adminArea: String = "",
    val country: String = "",
    val latitude: Double,
    val longitude: Double,
    val isCurrentGps: Boolean = false,
    val timezone: String? = null,
    val countryCode: String? = null
) {
    val displaySubtitle: String
        get() = listOf(adminArea, country)
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals(name.trim(), ignoreCase = true) }
            .distinctBy { it.lowercase() }
            .joinToString(", ")
}

data class HeroWeather(
    val tempDegrees: Int,
    val feelsLikeDegrees: Int,
    val highDegrees: Int,
    val lowDegrees: Int,
    val conditionText: String,
    val iconUri: String?,
    val isDaytime: Boolean,
    val locationName: String,
    val skyTheme: SkyThemeType,
    val weatherStory: String? = null,
    val confidencePercent: Int = 92,
    val modelSourcesLabel: String = "Google + ECMWF",
    val lastUpdatedEpochMs: Long = 0L,
    val feelsLikeReason: String = "",
    val tempP10: Int? = null,
    val tempP90: Int? = null,
    val dispersionSummary: String? = null,
    val modelCount: Int = 1
)

data class OfficialWeatherAlert(
    val title: String,
    val severity: SeverityRiskLevel,
    val eventType: String = "",
    val source: String = "Official Alert",
    val areaName: String = "",
    val description: String = "",
    val instruction: String = "",
    val expiresLabel: String = "",
    val severityLabel: String = "",
    val expiresEpochMs: Long? = null
)

data class LiveSatelliteStormObservation(
    val lightningNearby: Boolean = false,
    val nearestLightningKm: Int? = null,
    val lightningDirection: String? = null,
    val activeStormPhase: String? = null,
    val stormRainRateMmH: Double? = null,
    val stormMoveSpeedKmH: Int? = null,
    val stormMoveDirection: String? = null,
    val observedRainRateMmH: Double? = null,
    val observationTimeIso: String? = null,
    val sourceLabel: String = "Meteosat Live"
) {
    val hasActiveStormOrLightning: Boolean
        get() = lightningNearby || !activeStormPhase.isNullOrBlank()

    val hasObservedRain: Boolean
        get() = (observedRainRateMmH ?: 0.0) >= 0.3 || (stormRainRateMmH ?: 0.0) >= 0.5

    val effectiveRainRateMmH: Double
        get() = maxOf(observedRainRateMmH ?: 0.0, stormRainRateMmH ?: 0.0)

    fun formatCompactSummary(): String? {
        val parts = mutableListOf<String>()
        if (lightningNearby) {
            val locText = when {
                nearestLightningKm == null || nearestLightningKm <= 4 -> "overhead"
                !lightningDirection.isNullOrBlank() -> "~${nearestLightningKm} km $lightningDirection"
                else -> "~${nearestLightningKm} km away"
            }
            parts.add("Live lightning $locText")
        }
        if (!activeStormPhase.isNullOrBlank()) {
            val movePart = if (stormMoveSpeedKmH != null && !stormMoveDirection.isNullOrBlank()) {
                " moving $stormMoveDirection at $stormMoveSpeedKmH km/h"
            } else {
                ""
            }
            parts.add("${activeStormPhase} storm cell$movePart")
        }
        if (parts.isEmpty() && (observedRainRateMmH ?: 0.0) >= 0.3) {
            parts.add(String.format(java.util.Locale.US, "Satellite rain %.1f mm/h", observedRainRateMmH))
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }
}

data class SevereAlertInfo(
    val riskLevel: SeverityRiskLevel,
    val thunderstormProbability: Int,
    val title: String,
    val subtitle: String,
    val isVisible: Boolean,
    val peakHourLabel: String? = null,
    val hourlyStormProbabilities: List<Int> = emptyList(),
    val officialAlerts: List<OfficialWeatherAlert> = emptyList(),
    val sourceLabel: String? = null,
    val liveSatellite: LiveSatelliteStormObservation? = null
)

data class HourlyItem(
    val hourLabel: String,
    val tempDegrees: Int,
    val rainProbability: Int,
    val iconUri: String?,
    val isDaytime: Boolean,
    val conditionText: String = "Clear",
    val windSpeedKmH: Int = 0,
    val precipitationMm: Double = 0.0,
    val uvIndex: Int = 0,
    val humidityPercent: Int = 0,
    val pressureHpa: Int = 0,
    val thunderstormProbability: Int = 0
)

data class DailyItem(
    val dayLabel: String,
    val dateText: String,
    val minTemp: Int,
    val maxTemp: Int,
    val rainProbability: Int,
    val iconUri: String?,
    val conditionText: String,
    val morningWind: String,
    val afternoonWind: String,
    val fullDateString: String = "",
    val feelsLikeMaxTemp: Int? = null,
    val feelsLikeMinTemp: Int? = null,
    val precipitationMm: Double = 0.0,
    val uvIndex: Int = 0,
    val uvCategory: String = "Low",
    val humidityPercent: Int = 0,
    val thunderstormProbability: Int = 0,
    val cloudCoverPercent: Int = 0,
    val sunriseTime: String? = null,
    val sunsetTime: String? = null,
    val dayConditionText: String = conditionText,
    val dayIconUri: String? = iconUri,
    val nightConditionText: String = conditionText,
    val nightIconUri: String? = iconUri,
    val dayWindSpeedKmH: Int = 0,
    val dayWindDirection: String = "—",
    val nightWindSpeedKmH: Int = 0,
    val nightWindDirection: String = "—",
    val hourlyForecast: List<HourlyItem>? = emptyList(),
    val confidencePercent: Int = 90,
    val modelSpreadDegrees: Double = 0.8,
    val dayRainProbability: Int = rainProbability,
    val nightRainProbability: Int = rainProbability,
    val nightWind: String = "",
    val isHumidityUnavailable: Boolean = false,
    val isCloudCoverUnavailable: Boolean = false,
    val precipitationType: String? = null
) {
    val hourlyList: List<HourlyItem> get() = hourlyForecast ?: emptyList()
}

data class BentoTelemetry(
    val windSpeedKmH: Int,
    val windDirection: String,
    val windGustKmH: Int,
    val precipitationMm: Double,
    val uvIndex: Int,
    val uvCategory: String,
    val humidityPercent: Int,
    val dewPointDegrees: Int,
    val pressureHpa: Int,
    val sunriseTime: String,
    val sunsetTime: String,
    val windDegrees: Int = 0,
    val maxUvIndex: Int = 0,
    val aqiValue: Int = 0,
    val aqiCategory: String = "Unavailable",
    val pm25: Double = 0.0,
    val visibilityKm: Double = -1.0,
    val sunProgress: Float = 0.5f,
    val feelsLikeDegrees: Int = 0,
    val feelsLikeReason: String = "",
    val dominantPollutantLabel: String = "",
    val aqiScaleLabel: String = "AQI",
    val pressureLabel: String = "hPa • Sea level",
    val isDewPointUnavailable: Boolean = false,
    val daylightStatusOverride: String? = null
)

data class MinutelyPrecipInterval(
    val minuteOffset: Int,
    val timeLabel: String,
    val precipMm: Double,
    val probabilityPercent: Int,
    val weatherCode: Int
)

data class PrecipitationNowcast(
    val summaryText: String,
    val isPrecipitationImminent: Boolean,
    val nextPrecipitationOnsetMinutes: Int? = null,
    val maxRateMmH: Double = 0.0,
    val intervals: List<MinutelyPrecipInterval> = emptyList(),
    val rainCessationMinutes: Int? = null,
    val isHighRes15Min: Boolean = true,
    val headlineLabel: String = "SHORT-TERM RAIN FORECAST",
    val onsetRateMmH: Double = maxRateMmH,
    val precipitationLabel: String? = null
)

data class EnsembleDispersion(
    val modelSpreadDegrees: Double,
    val p10Temp: Int,
    val p90Temp: Int,
    val confidencePercent: Int,
    val confidenceTier: String,
    val modelAgreementSummary: String
)

data class WeatherDashboardData(
    val hero: HeroWeather,
    val alert: SevereAlertInfo,
    val hourlyList: List<HourlyItem>,
    val dailyList: List<DailyItem>,
    val telemetry: BentoTelemetry,
    val nowcast: PrecipitationNowcast? = null,
    val ensemble: EnsembleDispersion? = null,
    val timezone: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val liveSatellite: LiveSatelliteStormObservation? = null
)
