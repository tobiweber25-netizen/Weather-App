package com.google.weatherapp.data.model

import com.google.gson.annotations.SerializedName

data class CurrentConditionsResponse(
    @SerializedName("currentTime") val currentTime: String? = null,
    @SerializedName("timeZone") val timeZone: TimeZoneInfo? = null,
    @SerializedName("weatherCondition") val weatherCondition: WeatherCondition? = null,
    @SerializedName("temperature") val temperature: Temperature? = null,
    @SerializedName("feelsLikeTemperature") val feelsLikeTemperature: Temperature? = null,
    @SerializedName("dewPoint") val dewPoint: Temperature? = null,
    @SerializedName("heatIndex") val heatIndex: Temperature? = null,
    @SerializedName("windChill") val windChill: Temperature? = null,
    @SerializedName("relativeHumidity") val relativeHumidity: Int? = null,
    @SerializedName("uvIndex") val uvIndex: Int? = null,
    @SerializedName("precipitation") val precipitation: Precipitation? = null,
    @SerializedName("wind") val wind: Wind? = null,
    @SerializedName("visibility") val visibility: Visibility? = null,
    @SerializedName("airPressure") val airPressure: AirPressure? = null,
    @SerializedName("thunderstormProbability") val thunderstormProbability: Int? = null,
    @SerializedName("isDaytime") val isDaytime: Boolean? = null,
    @SerializedName("sunEvents") val sunEvents: SunEvents? = null,
    @SerializedName("cloudCover") val cloudCover: Int? = null,
    @SerializedName("currentConditionsHistory") val currentConditionsHistory: CurrentConditionsHistory? = null
)

data class CurrentConditionsHistory(
    @SerializedName("temperatureChange") val temperatureChange: Temperature? = null,
    @SerializedName("maxTemperature") val maxTemperature: Temperature? = null,
    @SerializedName("minTemperature") val minTemperature: Temperature? = null,
    @SerializedName("qpf") val qpf: PrecipQuantity? = null,
    @SerializedName("snowQpf") val snowQpf: PrecipQuantity? = null
)

data class PublicAlertsResponse(
    @SerializedName("weatherAlerts") val weatherAlerts: List<GooglePublicAlert>? = null,
    @SerializedName("regionCode") val regionCode: String? = null
)

data class GooglePublicAlert(
    @SerializedName("alertId") val alertId: String? = null,
    @SerializedName("alertTitle") val alertTitle: ConditionDescription? = null,
    @SerializedName("eventType") val eventType: String? = null,
    @SerializedName("areaName") val areaName: String? = null,
    @SerializedName("severity") val severity: String? = null,
    @SerializedName("urgency") val urgency: String? = null,
    @SerializedName("certainty") val certainty: String? = null,
    @SerializedName("startTime") val startTime: String? = null,
    @SerializedName("expirationTime") val expirationTime: String? = null,
    @SerializedName("description") val description: String? = null,
    @SerializedName("instruction") val instruction: List<String>? = null,
    @SerializedName("safetyRecommendations") val safetyRecommendations: List<SafetyRecommendation>? = null,
    @SerializedName("dataSource") val dataSource: AlertDataSource? = null
)

data class AlertDataSource(
    @SerializedName("publisher") val publisher: String? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("authorityUri") val authorityUri: String? = null
)

data class SafetyRecommendation(
    @SerializedName("directive") val directive: String? = null,
    @SerializedName("subtext") val subtext: String? = null
)

data class HourlyForecastResponse(
    @SerializedName("forecastHours") val forecastHours: List<ForecastHour>? = null
)

data class DailyForecastResponse(
    @SerializedName("forecastDays") val forecastDays: List<ForecastDay>? = null
)

data class TimeZoneInfo(
    @SerializedName("id") val id: String? = null
)

data class WeatherCondition(
    @SerializedName("iconBaseUri") val iconBaseUri: String? = null,
    @SerializedName("description") val description: ConditionDescription? = null,
    @SerializedName("type") val type: String? = null
)

data class ConditionDescription(
    @SerializedName("text") val text: String? = null,
    @SerializedName("languageCode") val languageCode: String? = null
)

data class Temperature(
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("degrees") val degrees: Double? = null
)

data class Wind(
    @SerializedName("direction") val direction: WindDirection? = null,
    @SerializedName("speed") val speed: WindSpeed? = null,
    @SerializedName("gust") val gust: WindSpeed? = null
)

data class WindDirection(
    @SerializedName("cardinal") val cardinal: String? = null,
    @SerializedName("degrees") val degrees: Int? = null
)

data class WindSpeed(
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("value") val value: Double? = null
)

data class Precipitation(
    @SerializedName("probability") val probability: PrecipProbability? = null,
    @SerializedName("qpf") val qpf: PrecipQuantity? = null,
    @SerializedName("snowQpf") val snowQpf: PrecipQuantity? = null
)

data class PrecipProbability(
    @SerializedName("type") val type: String? = null,
    @SerializedName("percent") val percent: Int? = null
)

data class PrecipQuantity(
    @SerializedName("unit") val unit: String? = null,
    @SerializedName("quantity") val quantity: Double? = null
)

data class AirPressure(
    @SerializedName("meanSeaLevelMillibars") val meanSeaLevelMillibars: Double? = null
)

data class Visibility(
    @SerializedName("distance") val distance: Double? = null,
    @SerializedName("unit") val unit: String? = null
)

data class SunEvents(
    @SerializedName("sunriseTime") val sunriseTime: String? = null,
    @SerializedName("sunsetTime") val sunsetTime: String? = null
)

data class TimeInterval(
    @SerializedName("startTime") val startTime: String? = null,
    @SerializedName("endTime") val endTime: String? = null
)

data class DisplayDateTime(
    @SerializedName("year") val year: Int? = null,
    @SerializedName("month") val month: Int? = null,
    @SerializedName("day") val day: Int? = null,
    @SerializedName("hours") val hours: Int? = null,
    @SerializedName("minutes") val minutes: Int? = null,
    @SerializedName("seconds") val seconds: Int? = null,
    @SerializedName("utcOffset") val utcOffset: String? = null
)

data class DisplayDate(
    @SerializedName("year") val year: Int? = null,
    @SerializedName("month") val month: Int? = null,
    @SerializedName("day") val day: Int? = null
)

data class ForecastHour(
    @SerializedName("interval") val interval: TimeInterval? = null,
    @SerializedName("displayDateTime") val displayDateTime: DisplayDateTime? = null,
    @SerializedName("weatherCondition") val weatherCondition: WeatherCondition? = null,
    @SerializedName("temperature") val temperature: Temperature? = null,
    @SerializedName("feelsLikeTemperature") val feelsLikeTemperature: Temperature? = null,
    @SerializedName("precipitation") val precipitation: Precipitation? = null,
    @SerializedName("wind") val wind: Wind? = null,
    @SerializedName("isDaytime") val isDaytime: Boolean? = null,
    @SerializedName("thunderstormProbability") val thunderstormProbability: Int? = null,
    @SerializedName("relativeHumidity") val relativeHumidity: Int? = null,
    @SerializedName("uvIndex") val uvIndex: Int? = null
)

data class ForecastDay(
    @SerializedName("interval") val interval: TimeInterval? = null,
    @SerializedName("displayDate") val displayDate: DisplayDate? = null,
    @SerializedName("daytimeForecast") val daytimeForecast: DayPeriodForecast? = null,
    @SerializedName("nighttimeForecast") val nighttimeForecast: DayPeriodForecast? = null,
    @SerializedName("maxTemperature") val maxTemperature: Temperature? = null,
    @SerializedName("minTemperature") val minTemperature: Temperature? = null,
    @SerializedName("feelsLikeMaxTemperature") val feelsLikeMaxTemperature: Temperature? = null,
    @SerializedName("feelsLikeMinTemperature") val feelsLikeMinTemperature: Temperature? = null,
    @SerializedName("sunEvents") val sunEvents: SunEvents? = null
)

data class DayPeriodForecast(
    @SerializedName("interval") val interval: TimeInterval? = null,
    @SerializedName("weatherCondition") val weatherCondition: WeatherCondition? = null,
    @SerializedName("precipitation") val precipitation: Precipitation? = null,
    @SerializedName("wind") val wind: Wind? = null,
    @SerializedName("thunderstormProbability") val thunderstormProbability: Int? = null,
    @SerializedName("relativeHumidity") val relativeHumidity: Int? = null,
    @SerializedName("uvIndex") val uvIndex: Int? = null,
    @SerializedName("cloudCover") val cloudCover: Int? = null
)
