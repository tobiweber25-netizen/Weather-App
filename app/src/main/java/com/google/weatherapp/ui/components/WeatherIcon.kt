package com.google.weatherapp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thunderstorm
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import coil.compose.SubcomposeAsyncImage
import com.google.weatherapp.theme.*

/**
 * Robust WeatherIcon composable with seamless fallback to Material weather vectors
 * when network images are loading, missing, or offline.
 */
@Composable
fun WeatherIcon(
    iconUri: String?,
    conditionText: String,
    isDaytime: Boolean = true,
    size: Dp = IconSizeTokens.Large,
    modifier: Modifier = Modifier
) {
    val fallbackVector = resolveWeatherVector(conditionText, isDaytime)
    val fallbackTint = resolveWeatherVectorTint(conditionText, isDaytime)

    val validModelUrl = normalizeIconUrl(iconUri, conditionText, isDaytime)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        if (!validModelUrl.isNullOrBlank()) {
            SubcomposeAsyncImage(
                model = validModelUrl,
                contentDescription = conditionText,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
                loading = {
                    Icon(
                        imageVector = fallbackVector,
                        contentDescription = conditionText,
                        tint = fallbackTint,
                        modifier = Modifier.fillMaxSize()
                    )
                },
                error = {
                    Icon(
                        imageVector = fallbackVector,
                        contentDescription = conditionText,
                        tint = fallbackTint,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            )
        } else {
            Icon(
                imageVector = fallbackVector,
                contentDescription = conditionText,
                tint = fallbackTint,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private fun normalizeIconUrl(iconUri: String?, conditionText: String, isDaytime: Boolean): String? {
    if (iconUri.isNullOrBlank()) {
        val baseName = when {
            conditionText.contains("thunder", ignoreCase = true) || conditionText.contains("storm", ignoreCase = true) ->
                "isolated_tstorms"
            conditionText.contains("shower", ignoreCase = true) ->
                "scattered_showers"
            conditionText.contains("rain", ignoreCase = true) || conditionText.contains("drizzle", ignoreCase = true) ->
                "showers"
            conditionText.contains("partly", ignoreCase = true) ->
                "partly_cloudy"
            conditionText.contains("cloud", ignoreCase = true) || conditionText.contains("overcast", ignoreCase = true) ->
                "cloudy"
            conditionText.contains("mostly sun", ignoreCase = true) ->
                "mostly_sunny"
            !isDaytime ->
                "mostly_clear_night"
            else ->
                "sunny"
        }
        return "https://maps.gstatic.com/weather/v1/$baseName.png"
    }

    val url = if (iconUri.endsWith(".png") || iconUri.endsWith(".svg")) iconUri else "$iconUri.png"

    // Fix known mismatched Google Weather names
    return url.replace("scattered_showers_day", "scattered_showers")
        .replace("/thunderstorm.png", "/isolated_tstorms.png")
        .replace("/rain.png", "/showers.png")
        .replace("/heavy_rain.png", "/strong_tstorms.png")
}

private fun resolveWeatherVector(condition: String, isDaytime: Boolean): ImageVector {
    val cond = condition.lowercase()
    return when {
        cond.contains("thunder") || cond.contains("storm") || cond.contains("lightning") ->
            Icons.Default.Thunderstorm
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") ->
            Icons.Default.WaterDrop
        cond.contains("partly") ->
            Icons.Default.WbCloudy
        cond.contains("cloud") || cond.contains("overcast") ->
            Icons.Default.Cloud
        cond.contains("wind") ->
            Icons.Default.Air
        !isDaytime ->
            Icons.Default.NightsStay
        else ->
            Icons.Default.WbSunny
    }
}

private fun resolveWeatherVectorTint(condition: String, isDaytime: Boolean): Color {
    val cond = condition.lowercase()
    return when {
        cond.contains("thunder") || cond.contains("storm") ->
            WeatherIconTintAmber
        cond.contains("rain") || cond.contains("drizzle") || cond.contains("shower") ->
            WeatherIconTintRain
        cond.contains("partly") ->
            WeatherIconTintPartlyCloud
        cond.contains("cloud") || cond.contains("overcast") ->
            WeatherIconTintOvercast
        !isDaytime ->
            WeatherIconTintMoon
        else ->
            WeatherIconTintAmber
    }
}
