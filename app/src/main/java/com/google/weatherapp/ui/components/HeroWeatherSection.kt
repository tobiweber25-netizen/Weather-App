package com.google.weatherapp.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.PrecipitationNowcast
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.theme.*
import kotlinx.coroutines.delay

private val sharedAiService = AiWeatherStoryService()

@Composable
fun HeroWeatherSection(
    hero: HeroWeather,
    hourlyList: List<HourlyItem> = emptyList(),
    nowcast: PrecipitationNowcast? = null,
    telemetry: BentoTelemetry? = null,
    alert: SevereAlertInfo? = null,
    onLocationClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val activeEffect = remember(hero) { com.google.weatherapp.ui.resolveActiveEffect(hero) }
    val partlyCloudy = remember(hero.conditionText) { isPartlyCloudyCondition(hero.conditionText) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = SpacingTokens.Base,
                vertical = SpacingTokens.Small
            )
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 1. Clickable Luminous Frosted Location Pill
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                modifier = Modifier
                    .luminousGlass(cornerRadius = CornerTokens.Pill)
                    .then(if (onLocationClick != null) Modifier.clickable(onClick = onLocationClick) else Modifier)
                    .padding(
                        horizontal = SpacingTokens.Medium,
                        vertical = SpacingTokens.Small
                    )
            ) {
                Icon(
                    imageVector = Icons.Default.LocationOn,
                    contentDescription = "Current Location",
                    tint = SunCoreGold,
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

            Spacer(modifier = Modifier.height(SpacingTokens.Base))

            // 2. Sculptural Backlit Hero Icon & Optically Centered 96sp Temperature
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                NothingWeatherIcon(
                    effectType = activeEffect,
                    isPartlyCloudy = partlyCloudy,
                    isDaytime = hero.isDaytime,
                    animate = true,
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
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = "$targetTemp",
                            style = HeroTemperatureTextStyle,
                            color = SkyTextPrimary,
                            maxLines = 1,
                            softWrap = false
                        )
                        Text(
                            text = "°",
                            style = HeroDegreeTextStyle,
                            color = SkyTextSecondary,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(top = SpacingTokens.Small)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))

            // 3. Editorial Newsreader Condition Title
            Text(
                text = hero.conditionText,
                style = MaterialTheme.typography.headlineMedium,
                color = SkyTextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(SpacingTokens.Small))

            // 4. High / Low & Feels Like Summary Row (Tabular Numerals)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium)
            ) {
                Text(
                    text = "H ${hero.highDegrees}°  ·  L ${hero.lowDegrees}°",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextPrimary
                )
                Box(
                    modifier = Modifier
                        .width(BorderTokens.Hairline)
                        .height(SpacingTokens.Medium)
                        .background(GlassCardBorderLight)
                )
                Text(
                    text = "Feels ${hero.feelsLikeDegrees}°",
                    style = MaterialTheme.typography.titleMedium,
                    color = SkyTextSecondary
                )
            }

            Spacer(modifier = Modifier.height(SpacingTokens.Small))

            // 5. Subtle Ensemble Source & Freshness Whisper
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                Box(
                    modifier = Modifier
                        .size(IconSizeTokens.StatusDot)
                        .clip(CircleShape)
                        .background(TempBarMildColor.copy(alpha = 0.85f))
                )
                Text(
                    text = hero.modelSourcesLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
                if (hero.lastUpdatedEpochMs > 0L) {
                    var currentTimeMs by remember(hero.lastUpdatedEpochMs) {
                        mutableLongStateOf(System.currentTimeMillis())
                    }
                    LaunchedEffect(hero.lastUpdatedEpochMs) {
                        while (true) {
                            delay(60_000L)
                            currentTimeMs = System.currentTimeMillis()
                        }
                    }
                    val minutesAgo = ((currentTimeMs - hero.lastUpdatedEpochMs) / 60000L).coerceAtLeast(0)
                    val updateLabel = when {
                        minutesAgo < 1 -> "Just now"
                        minutesAgo < 60 -> "${minutesAgo}m ago"
                        else -> "${minutesAgo / 60}h ago"
                    }
                    Text(
                        text = "·",
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

            // 6. Editorial Pull-Quote "Weather Story" Luminous Glass Card
            val storyKey = remember(hero.locationName, hero.tempDegrees, hero.conditionText, hero.isDaytime, alert?.title, nowcast?.summaryText) {
                "${hero.locationName}_${hero.tempDegrees}_${hero.conditionText}_${hero.isDaytime}_${alert?.title.orEmpty()}_${nowcast?.summaryText.orEmpty()}"
            }
            var weatherStory by remember(storyKey) {
                mutableStateOf<String?>(hero.weatherStory)
            }

            LaunchedEffect(storyKey) {
                if (hero.weatherStory != null) {
                    weatherStory = hero.weatherStory
                } else {
                    weatherStory = sharedAiService.generateStory(
                        hero = hero,
                        telemetry = telemetry,
                        alert = alert,
                        nowcast = nowcast
                    )
                }
            }

            val currentStory = weatherStory
            if (!currentStory.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(SpacingTokens.Large))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Medium),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min)
                        .luminousGlass(cornerRadius = CornerTokens.Large)
                        .padding(
                            horizontal = SpacingTokens.Base,
                            vertical = SpacingTokens.Medium
                        )
                ) {
                    // Warm golden editorial pull-quote accent rule spanning exact story height
                    Box(
                        modifier = Modifier
                            .width(BorderTokens.AccentRule)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(CornerTokens.Pill))
                            .background(
                                Brush.verticalGradient(
                                    colors = listOf(SunCoreGold, SunCoreDeepAmber.copy(alpha = 0.55f))
                                )
                            )
                    )
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Editorial Note",
                                tint = SunCoreGold,
                                modifier = Modifier.size(IconSizeTokens.Micro)
                            )
                            Text(
                                text = "ALMANAC NOTE",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = SunCoreGold.copy(alpha = 0.90f)
                            )
                        }
                        Text(
                            text = currentStory,
                            style = EditorialStoryTextStyle,
                            color = SkyTextPrimary
                        )
                    }
                }
            }

            // 7. Unified Precipitation Nowcast & Upcoming Rain Card (eliminates stacked duplicate banners)
            val upcoming12h = remember(hourlyList) { hourlyList.take(12) }
            val hasImminentNowcast = nowcast != null && (nowcast.isPrecipitationImminent || nowcast.intervals.any { it.precipMm > 0.0 })
            val hasUpcomingRain = remember(upcoming12h) {
                upcoming12h.any { it.rainProbability >= 25 || it.precipitationMm > 0.0 }
            }
            if ((hasImminentNowcast || hasUpcomingRain) && (upcoming12h.isNotEmpty() || nowcast != null)) {
                Spacer(modifier = Modifier.height(SpacingTokens.Medium))
                UnifiedPrecipitationNowcastCard(
                    nowcast = if (hasImminentNowcast) nowcast else null,
                    upcomingHours = upcoming12h
                )
            }
        }
    }
}

@Composable
private fun UnifiedPrecipitationNowcastCard(
    nowcast: PrecipitationNowcast?,
    upcomingHours: List<HourlyItem>
) {
    val peakIndex = remember(upcomingHours) {
        if (upcomingHours.isEmpty()) -1
        else upcomingHours.indices.maxByOrNull { idx ->
            val h = upcomingHours[idx]
            maxOf(h.rainProbability, (h.precipitationMm * 25).toInt())
        } ?: -1
    }
    val peakHour = upcomingHours.getOrNull(peakIndex)

    val badgeLabel = when {
        nowcast != null && nowcast.headlineLabel.isNotBlank() -> nowcast.headlineLabel
        nowcast != null && nowcast.isHighRes15Min -> "15-MIN RAIN NOWCAST"
        else -> "SHORT-TERM RAIN FORECAST"
    }
    val summaryLabel = when {
        nowcast != null && nowcast.summaryText.isNotBlank() -> nowcast.summaryText
        upcomingHours.firstOrNull()?.let { it.rainProbability >= 45 || it.precipitationMm > 0.2 } == true ->
            "Precipitation active now · Continuing over next hours"
        peakHour != null ->
            "Peak rain expected around ${peakHour.hourLabel} (${peakHour.rainProbability}% chance)"
        else ->
            "Light precipitation possible over the next 12 hours"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                accentBorderColor = RainDropCyan.copy(alpha = 0.45f)
            )
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.WaterDrop,
                    contentDescription = "Precipitation Nowcast",
                    tint = RainDropCyan,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = badgeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = RainDropCyan
                )
            }
            if (peakHour != null && peakHour.rainProbability > 0) {
                Text(
                    text = "Peak ${peakHour.rainProbability}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextSecondary
                )
            }
        }

        Text(
            text = summaryLabel,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = SkyTextPrimary
        )

        if (upcomingHours.isNotEmpty()) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ComponentTokens.PrecipNowcastBarHeight)
            ) {
                // Subtle horizontal intensity guides (light / moderate)
                val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                listOf(0.35f, 0.70f).forEach { frac ->
                    val gy = size.height * frac
                    drawLine(
                        color = GlassCardDividerColor,
                        start = Offset(0f, gy),
                        end = Offset(size.width, gy),
                        strokeWidth = 1f,
                        pathEffect = dashEffect
                    )
                }

                val count = upcomingHours.size.coerceAtLeast(1)
                val slotWidth = size.width / count
                val barWidth = (slotWidth * 0.54f).coerceAtMost(18f)

                upcomingHours.forEachIndexed { idx, h ->
                    val intensityRatio = maxOf(
                        h.rainProbability / 100f,
                        (h.precipitationMm.toFloat() / 6f).coerceIn(0f, 1f)
                    ).coerceIn(0.08f, 1f)

                    val barH = size.height * intensityRatio
                    val leftX = idx * slotWidth + (slotWidth - barWidth) * 0.5f
                    val isPeak = idx == peakIndex && intensityRatio > 0.25f

                    // Track background capsule
                    drawRoundRect(
                        color = TempBarTrackColor,
                        topLeft = Offset(leftX, 0f),
                        size = Size(barWidth, size.height),
                        cornerRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
                    )

                    // Active rain intensity gradient bar
                    drawRoundRect(
                        brush = Brush.verticalGradient(
                            colors = if (isPeak) {
                                listOf(PureWhite, RainDropCyan, RainDropDeepBlue)
                            } else {
                                listOf(RainDropCyan.copy(alpha = 0.90f), RainDropDeepBlue.copy(alpha = 0.80f))
                            },
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
                    text = upcomingHours.getOrNull(upcomingHours.size / 3)?.hourLabel ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
                Text(
                    text = upcomingHours.getOrNull((upcomingHours.size * 2) / 3)?.hourLabel ?: "",
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
}
