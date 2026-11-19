package com.google.weatherapp.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.theme.*

private val sharedAiService = AiWeatherStoryService()

@Composable
fun HeroWeatherSection(
    hero: HeroWeather,
    hourlyList: List<HourlyItem> = emptyList(),
    nowcast: PrecipitationNowcast? = null,
    onLocationClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SpacingTokens.Base,
                vertical = SpacingTokens.Medium
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Clickable Editorial Location Badge (opens City Switcher)
            val pillModifier = Modifier
                .clip(RoundedCornerShape(CornerTokens.Pill))
                .background(GlassCardBackgroundLight)
                .border(
                    width = BorderTokens.Hairline,
                    color = GlassCardBorderLight,
                    shape = RoundedCornerShape(CornerTokens.Pill)
                )
                .then(if (onLocationClick != null) Modifier.clickable(onClick = onLocationClick) else Modifier)
                .padding(
                    horizontal = SpacingTokens.Medium,
                    vertical = SpacingTokens.ExtraSmall
                )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                modifier = pillModifier
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Current Location",
                    tint = SkyTextPrimary,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = hero.locationName.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextPrimary,
                    maxLines = 1
                )
                if (onLocationClick != null) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Switch City",
                        tint = SkyTextSecondary,
                        modifier = Modifier.size(IconSizeTokens.Micro)
                    )
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.Medium))

            // 2. Dimensional Multi-Layer Hero Icon & Thin 96sp Temperature
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
            ) {
                NothingWeatherIcon(
                    effectType = com.google.weatherapp.ui.resolveActiveEffect(hero),
                    modifier = Modifier.size(IconSizeTokens.HeroDisplay)
                )

                AnimatedContent(
                    targetState = hero.tempDegrees,
                    transitionSpec = {
                        if (targetState > initialState) {
                            (slideInVertically { height -> height } + fadeIn())
                                .togetherWith(slideOutVertically { height -> -height } + fadeOut())
                        } else {
                            (slideInVertically { height -> -height } + fadeIn())
                                .togetherWith(slideOutVertically { height -> height } + fadeOut())
                        }
                    },
                    label = "temperature_slot_machine"
                ) { targetTemp ->
                    Text(
                        text = "$targetTemp°",
                        style = HeroTemperatureTextStyle,
                        color = SkyTextPrimary
                    )
                }
            }

            // 3. Editorial Condition Title
            Text(
                text = hero.conditionText,
                style = MaterialTheme.typography.headlineMedium,
                color = SkyTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))

            // 4. High / Low & Feels Like Summary Row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
            ) {
                Text(
                    text = "H:${hero.highDegrees}°  L:${hero.lowDegrees}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextPrimary
                )
                Text(
                    text = "•",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyTextMuted
                )
                Text(
                    text = "Feels like ${hero.feelsLikeDegrees}°",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))

            // 5. Verification & Source Metadata Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                Text(
                    text = hero.modelSourcesLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
                if (hero.lastUpdatedEpochMs > 0L) {
                    val minutesAgo = ((System.currentTimeMillis() - hero.lastUpdatedEpochMs) / 60000L).coerceAtLeast(0)
                    val updateLabel = when {
                        minutesAgo < 1 -> "Just now"
                        minutesAgo < 60 -> "${minutesAgo}m ago"
                        else -> "${minutesAgo / 60}h ago"
                    }
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyTextMuted
                    )
                    Text(
                        text = "Updated $updateLabel",
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyTextMuted
                    )
                }
            }

            // 6. Generative Editorial "Weather Story" Frosted Glass Card
            val storyKey = remember(hero.locationName, hero.tempDegrees, hero.conditionText, hero.isDaytime) {
                "${hero.locationName}_${hero.tempDegrees}_${hero.conditionText}_${hero.isDaytime}"
            }
            var weatherStory by remember(storyKey) {
                mutableStateOf<String?>(hero.weatherStory)
            }

            LaunchedEffect(storyKey) {
                if (hero.weatherStory != null) {
                    weatherStory = hero.weatherStory
                } else {
                    weatherStory = sharedAiService.generateStory(hero)
                }
            }

            val currentStory = weatherStory
            if (!currentStory.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(SpacingTokens.Base))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(CornerTokens.Medium))
                        .background(GlassCardBackgroundLight)
                        .border(
                            width = BorderTokens.Hairline,
                            color = GlassCardBorderLight,
                            shape = RoundedCornerShape(CornerTokens.Medium)
                        )
                        .padding(
                            horizontal = SpacingTokens.Base,
                            vertical = SpacingTokens.Medium
                        )
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = "AI Editorial Note",
                        tint = SunCoreGold,
                        modifier = Modifier.size(IconSizeTokens.Small)
                    )
                    Text(
                        text = currentStory,
                        style = EditorialStoryTextStyle,
                        color = SkyTextPrimary,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 6b. 15-Minute Sub-Hourly Nowcasting Banner (when rain is detected or imminent)
            if (nowcast != null && (nowcast.isPrecipitationImminent || nowcast.intervals.any { it.precipMm > 0.0 })) {
                Spacer(modifier = Modifier.height(SpacingTokens.Medium))
                PrecipitationNowcastBanner(nowcast = nowcast)
            }

            // 7. Apple Weather / Dark Sky Style Upcoming Precipitation Bar Chart (when rain is active/expected)
            val upcoming12h = remember(hourlyList) { hourlyList.take(12) }
            val hasUpcomingRain = remember(upcoming12h) {
                upcoming12h.any { it.rainProbability >= 25 || it.precipitationMm > 0.0 }
            }
            if (hasUpcomingRain && upcoming12h.isNotEmpty()) {
                Spacer(modifier = Modifier.height(SpacingTokens.Medium))
                PrecipitationNowcastCard(upcomingHours = upcoming12h)
            }
        }
    }
}

@Composable
private fun PrecipitationNowcastBanner(nowcast: PrecipitationNowcast) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Medium))
            .background(GlassCardBackgroundLight)
            .border(
                width = BorderTokens.Hairline,
                color = GlassCardBorderLight,
                shape = RoundedCornerShape(CornerTokens.Medium)
            )
            .padding(
                horizontal = SpacingTokens.Base,
                vertical = SpacingTokens.Small
            )
    ) {
        Icon(
            imageVector = Icons.Default.WaterDrop,
            contentDescription = "Precipitation Nowcast",
            tint = RainDropDeepBlue,
            modifier = Modifier.size(IconSizeTokens.Small)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "15-MIN NOWCAST",
                style = MaterialTheme.typography.labelSmall,
                color = RainDropDeepBlue,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = nowcast.summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = SkyTextPrimary
            )
        }
    }
}

@Composable
private fun PrecipitationNowcastCard(
    upcomingHours: List<HourlyItem>
) {
    val peakHour = upcomingHours.maxByOrNull { maxOf(it.rainProbability, (it.precipitationMm * 25).toInt()) }
    val summaryLabel = when {
        upcomingHours.first().rainProbability >= 45 || upcomingHours.first().precipitationMm > 0.2 ->
            "Precipitation active now • Continuing over next hours"
        peakHour != null ->
            "Rain expected around ${peakHour.hourLabel} (${peakHour.rainProbability}% chance)"
        else ->
            "Light precipitation possible in the next 12 hours"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(GlassCardBackgroundLight)
            .border(
                width = BorderTokens.Hairline,
                color = GlassCardBorderLight,
                shape = RoundedCornerShape(CornerTokens.Large)
            )
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
        ) {
            Icon(
                imageVector = Icons.Default.WaterDrop,
                contentDescription = null,
                tint = RainDropCyan,
                modifier = Modifier.size(IconSizeTokens.Micro)
            )
            Text(
                text = summaryLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = SkyTextPrimary
            )
        }

        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.PrecipNowcastBarHeight)
        ) {
            val count = upcomingHours.size.coerceAtLeast(1)
            val slotWidth = size.width / count
            val barWidth = slotWidth * 0.56f

            upcomingHours.forEachIndexed { idx, h ->
                val intensityRatio = maxOf(
                    h.rainProbability / 100f,
                    (h.precipitationMm.toFloat() / 6f).coerceIn(0f, 1f)
                ).coerceIn(0.08f, 1f)

                val barH = size.height * intensityRatio
                val leftX = idx * slotWidth + (slotWidth - barWidth) * 0.5f

                // Track background pill
                drawRoundRect(
                    color = TempBarTrackColor,
                    topLeft = Offset(leftX, 0f),
                    size = Size(barWidth, size.height),
                    cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
                )

                // Active cyan intensity bar
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(RainDropCyan, RainDropDeepBlue),
                        startY = size.height - barH,
                        endY = size.height
                    ),
                    topLeft = Offset(leftX, size.height - barH),
                    size = Size(barWidth, barH),
                    cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = upcomingHours.firstOrNull()?.hourLabel ?: "Now",
                style = MaterialTheme.typography.labelSmall,
                color = SkyTextMuted
            )
            Text(
                text = upcomingHours.getOrNull(upcomingHours.size / 2)?.hourLabel ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = SkyTextMuted
            )
            Text(
                text = upcomingHours.lastOrNull()?.hourLabel ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = SkyTextMuted
            )
        }
    }
}
