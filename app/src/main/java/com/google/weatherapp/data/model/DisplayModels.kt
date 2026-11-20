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
    val isCurrentGps: Boolean = false
) {
    val displaySubtitle: String
        get() = listOf(adminArea, country).filter { it.isNotBlank() }.joinToString(", ")
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
    val severityLabel: String = ""
)

data class SevereAlertInfo(
    val riskLevel: SeverityRiskLevel,
    val thunderstormProbability: Int,
    val title: String,
    val subtitle: String,
    val isVisible: Boolean,
    val peakHourLabel: String? = null,
    val hourlyStormProbabilities: List<Int> = emptyList(),
    val officialAlerts: List<OfficialWeatherAlert> = emptyList(),
    val sourceLabel: String? = null
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
    val nightWind: String = ""
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
    val aqiValue: Int = 24,
    val aqiCategory: String = "Good",
    val pm25: Double = 5.2,
    val visibilityKm: Double = 10.0,
    val sunProgress: Float = 0.5f,
    val feelsLikeDegrees: Int = 22,
    val feelsLikeReason: String = "Similar to the actual temperature",
    val dominantPollutantLabel: String = "",
    val aqiScaleLabel: String = "US AQI",
    val pressureLabel: String = "hPa • Sea level"
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
    val onsetRateMmH: Double = maxRateMmH
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
    val longitude: Double? = null
)
