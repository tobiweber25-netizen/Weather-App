package com.google.weatherapp.ui.components

import com.google.ai.client.generativeai.GenerativeModel
import com.google.weatherapp.BuildConfig
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiWeatherStoryService {
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash-lite",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val storyCache = mutableMapOf<String, String>()

    suspend fun generateStory(
        hero: HeroWeather,
        persona: String = "witty but helpful",
        telemetry: BentoTelemetry? = null,
        alert: SevereAlertInfo? = null,
        nowcast: PrecipitationNowcast? = null
    ): String? {
        val alertKey = alert?.takeIf { it.riskLevel != SeverityRiskLevel.LOW }?.title.orEmpty()
        val satKey = alert?.liveSatellite?.formatCompactSummary().orEmpty()
        val precipKey = nowcast?.takeIf { it.isPrecipitationImminent }?.summaryText.orEmpty()
        val cacheKey = "${hero.locationName}_${hero.tempDegrees}_${hero.conditionText}_${hero.isDaytime}_${alertKey}_${satKey}_${precipKey}"
        synchronized(storyCache) {
            storyCache[cacheKey]?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            val story = try {
                if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY == "AIzaSyDummyKeyForGeminiApi") {
                    null
                } else {
                    val contextLines = buildString {
                        appendLine("Location: ${hero.locationName}")
                        appendLine("Condition: ${hero.conditionText}")
                        appendLine("Temperature: ${hero.tempDegrees}°C (Feels Like: ${hero.feelsLikeDegrees}°C)")
                        appendLine("High/Low: ${hero.highDegrees}°C / ${hero.lowDegrees}°C")
                        appendLine("Is Daytime: ${hero.isDaytime}")
                        telemetry?.let { t ->
                            val humPart = if (t.humidityPercent >= 0) ", Humidity: ${t.humidityPercent}%" else ""
                            appendLine("Wind: ${t.windSpeedKmH} km/h (gusts ${t.windGustKmH} km/h)$humPart, UV: ${t.uvIndex} (${t.uvCategory})")
                            if (t.precipitationMm > 0.0) appendLine("Expected 24h Precipitation: ${t.precipitationMm} mm")
                            if (t.aqiCategory != "Unavailable") appendLine("Air Quality: ${t.aqiValue} ${t.aqiScaleLabel} (${t.aqiCategory})")
                        }
                        nowcast?.takeIf { it.isPrecipitationImminent }?.let { n ->
                            appendLine("Short-Term Precipitation Forecast: ${n.summaryText}")
                        }
                        alert?.takeIf { it.riskLevel != SeverityRiskLevel.LOW }?.let { a ->
                            appendLine("Active Alert: ${a.title} - ${a.subtitle}")
                        }
                        alert?.liveSatellite?.takeIf { it.hasActiveStormOrLightning || it.hasObservedRain }?.formatCompactSummary()?.let { sat ->
                            appendLine("Live Space Satellite Observation (Meteosat): $sat")
                        }
                    }.trim()

                    val prompt = """
                        You are a $persona weather app assistant. Given the exact verified weather conditions below,
                        generate a concise, accurate, maximum 2-sentence weather note that never contradicts any rain, snow, ice, wind, heat, UV, or storm alert.
                        Always use metric units only (°C, km/h, mm, km, hPa) and never use Fahrenheit or mph.

                        $contextLines
                    """.trimIndent()

                    val response = generativeModel.generateContent(prompt)
                    response.text?.trim()?.replace("\"", "")
                }
            } catch (e: Exception) {
                null
            }

            if (!story.isNullOrBlank()) {
                synchronized(storyCache) {
                    storyCache[cacheKey] = story
                }
            }
            story
        }
    }
}
