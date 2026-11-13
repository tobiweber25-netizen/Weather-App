package com.google.weatherapp.ui.components

import com.google.ai.client.generativeai.GenerativeModel
import com.google.weatherapp.BuildConfig
import com.google.weatherapp.data.model.HeroWeather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AiWeatherStoryService {
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash-lite",
        apiKey = BuildConfig.GEMINI_API_KEY
    )

    private val storyCache = mutableMapOf<String, String>()

    suspend fun generateStory(hero: HeroWeather, persona: String = "witty but helpful"): String? {
        val cacheKey = "${hero.locationName}_${hero.tempDegrees}_${hero.conditionText}_${hero.isDaytime}"
        synchronized(storyCache) {
            storyCache[cacheKey]?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            val story = try {
                if (BuildConfig.GEMINI_API_KEY.isBlank() || BuildConfig.GEMINI_API_KEY == "AIzaSyDummyKeyForGeminiApi") {
                    null
                } else {
                    val prompt = """
                        You are a $persona weather app assistant. Given the following current weather conditions, 
                        generate a creative, maximum 2-sentence weather story (e.g. "A crisp 15°C breeze dances through the city. Perfect weather for a light jacket and a stroll.").
                        
                        Location: ${hero.locationName}
                        Condition: ${hero.conditionText}
                        Temperature: ${hero.tempDegrees}°C
                        High/Low: ${hero.highDegrees}°C / ${hero.lowDegrees}°C
                        Feels Like: ${hero.feelsLikeDegrees}°C
                        Is Daytime: ${hero.isDaytime}
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
