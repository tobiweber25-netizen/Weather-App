package com.google.weatherapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import com.google.weatherapp.ui.resolveActiveEffectForDay
import com.google.weatherapp.ui.resolveActiveEffectForNight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailModalSheet(
    day: DailyItem,
    allDays: List<DailyItem>,
    onSelectDay: (DailyItem) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    effectChoice: WeatherEffectType = WeatherEffectType.AUTO
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val isDark = isSystemInDarkTheme()
    val sheetContainerColor = if (isDark) DayDetailSheetSurfaceDark else DayDetailSheetSurfaceLight

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = sheetContainerColor,
        shape = RoundedCornerShape(topStart = CornerTokens.ExtraLarge, topEnd = CornerTokens.ExtraLarge),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = SpacingTokens.Base)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.Base)
            ) {
                // Sheet Header with Close Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = day.dayLabel,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = day.fullDateString.ifBlank { day.dateText },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close details",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // Top Horizontal Day Selector
                if (allDays.size > 1) {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
                        contentPadding = PaddingValues(vertical = SpacingTokens.ExtraSmall)
                    ) {
                        items(allDays) { item ->
                            val isSelected = item.dateText == day.dateText && item.dayLabel == day.dayLabel
                            DaySelectorChip(
                                item = item,
                                isSelected = isSelected,
                                onClick = { onSelectDay(item) }
                            )
                        }
                    }
                }

                // Hero Summary Card for Selected Day (with subtle ensemble agreement line)
                DayDetailHeroCard(day = day, effectChoice = effectChoice)

                // 24-Hour Hourly Forecast for Selected Day (now populated across all 10 days!)
                val hourly = day.hourlyList
                if (hourly.isNotEmpty()) {
                    DayDetailHourlyStrip(dayLabel = day.dayLabel, hourlyItems = hourly)
                }

                // Day vs. Night Period Comparison
                DayNightComparisonSection(day = day)

                // Granular Telemetry Grid for the Day
                DayDetailTelemetryGrid(day = day)

                Spacer(modifier = Modifier.height(SpacingTokens.Huge))
            }
        }
    }
}

@Composable
private fun DayDetailHourlyStrip(
    dayLabel: String,
    hourlyItems: List<HourlyItem>
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBackground)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
            .padding(vertical = SpacingTokens.Medium),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Text(
            text = "HOURLY PROGRESSION (${dayLabel.uppercase()})",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.Base)
        )

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
            contentPadding = PaddingValues(horizontal = SpacingTokens.Base)
        ) {
            items(hourlyItems) { item ->
                DayDetailHourlyItem(item = item)
            }
        }
    }
}

@Composable
private fun DayDetailHourlyItem(item: HourlyItem) {
    Column(
        modifier = Modifier
            .width(ComponentTokens.HourlyCardWidth)
            .padding(vertical = SpacingTokens.ExtraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Text(
            text = item.hourLabel,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
        NothingWeatherIcon(
            effectType = com.google.weatherapp.ui.resolveActiveEffectForHourly(item),
            modifier = Modifier.size(IconSizeTokens.Medium)
        )
        Text(
            text = "${item.tempDegrees}°",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun DaySelectorChip(
    item: DailyItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val borderColor = if (isSelected) {
        if (isDark) DayDetailChipSelectedBorderDark else DayDetailChipSelectedBorder
    } else {
        if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight
    }
    val bgColor = if (isSelected) {
        if (isDark) DayDetailChipSelectedBgDark else DayDetailChipSelectedBgLight
    } else {
        if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    }
    val labelColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val tempColor = MaterialTheme.colorScheme.onSurface

    Box(
        modifier = Modifier
            .width(DayDetailTokens.DayChipWidth)
            .height(DayDetailTokens.DayChipHeight)
            .clip(RoundedCornerShape(CornerTokens.Medium))
            .background(bgColor)
            .border(
                width = if (isSelected) BorderTokens.Subtle else BorderTokens.Hairline,
                color = borderColor,
                shape = RoundedCornerShape(CornerTokens.Medium)
            )
            .clickable(onClick = onClick)
            .padding(SpacingTokens.Small),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = item.dayLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = labelColor,
                maxLines = 1
            )

            NothingWeatherIcon(
                effectType = resolveActiveEffectForDay(item),
                modifier = Modifier.size(IconSizeTokens.Medium)
            )

            Text(
                text = "${item.maxTemp}° / ${item.minTemp}°",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                color = tempColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DayDetailHeroCard(
    day: DailyItem,
    effectChoice: WeatherEffectType
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    Card(
        shape = RoundedCornerShape(CornerTokens.Large),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(SpacingTokens.Base),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = day.conditionText,
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)) {
                        Text(
                            text = "High ${day.maxTemp}°C • Low ${day.minTemp}°C",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1
                        )
                        val feelsLikeMax = day.feelsLikeMaxTemp
                        val feelsLikeMin = day.feelsLikeMinTemp
                        if (feelsLikeMax != null && feelsLikeMin != null) {
                            Text(
                                text = "Feels like ${feelsLikeMax}°C / ${feelsLikeMin}°C",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }

                        // Subtle model agreement whisper
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                            modifier = Modifier.padding(top = SpacingTokens.Micro)
                        ) {
                            val confDotColor = when {
                                day.confidencePercent >= 85 -> UvLowGreen
                                day.confidencePercent >= 70 -> UvModerateYellow
                                else -> UvHighOrange
                            }
                            Box(
                                modifier = Modifier
                                    .size(IconSizeTokens.StatusDot)
                                    .clip(CircleShape)
                                    .background(confDotColor)
                            )
                            Text(
                                text = "${day.confidencePercent}% ensemble agreement • ±${day.modelSpreadDegrees}° model spread",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                NothingWeatherIcon(
                    effectType = resolveActiveEffectForDay(day, effectChoice),
                    modifier = Modifier.size(IconSizeTokens.Hero)
                )
            }

            if (day.rainProbability > 0 || day.precipitationMm > 0.0) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = SpacingTokens.ExtraSmall),
                    thickness = BorderTokens.Hairline,
                    color = cardBorder
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
                ) {
                    Icon(
                        imageVector = Icons.Default.WaterDrop,
                        contentDescription = "Precipitation",
                        tint = RainDropDeepBlue,
                        modifier = Modifier.size(IconSizeTokens.Small)
                    )
                    Text(
                        text = "${day.rainProbability}% chance of rain • ${day.precipitationMm} mm expected",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
private fun DayNightComparisonSection(day: DailyItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Text(
            text = "DAY & NIGHT BREAKDOWN",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.ExtraSmall)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            PeriodForecastCard(
                title = "DAY",
                iconVector = Icons.Default.WbSunny,
                tempLabel = "${day.maxTemp}°C",
                feelsLike = day.feelsLikeMaxTemp?.let { "Feels $it°" } ?: "",
                condition = day.dayConditionText,
                effectType = resolveActiveEffectForDay(day),
                rainProb = day.rainProbability,
                windText = day.morningWind,
                modifier = Modifier.weight(1f)
            )

            PeriodForecastCard(
                title = "NIGHT",
                iconVector = Icons.Default.NightsStay,
                tempLabel = "${day.minTemp}°C",
                feelsLike = day.feelsLikeMinTemp?.let { "Feels $it°" } ?: "",
                condition = day.nightConditionText,
                effectType = resolveActiveEffectForNight(day),
                rainProb = day.rainProbability,
                windText = day.afternoonWind,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PeriodForecastCard(
    title: String,
    iconVector: ImageVector,
    tempLabel: String,
    feelsLike: String,
    condition: String,
    effectType: WeatherEffectType,
    rainProb: Int,
    windText: String,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    Card(
        shape = RoundedCornerShape(CornerTokens.Large),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        modifier = modifier
            .heightIn(min = DayDetailTokens.BreakdownCardMinHeight)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SpacingTokens.Base),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
                ) {
                    Icon(
                        imageVector = iconVector,
                        contentDescription = title,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(IconSizeTokens.Small)
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                NothingWeatherIcon(
                    effectType = effectType,
                    modifier = Modifier.size(IconSizeTokens.Medium)
                )
            }

            Column(modifier = Modifier.padding(vertical = SpacingTokens.ExtraSmall)) {
                Text(
                    text = tempLabel,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (feelsLike.isNotBlank()) {
                    Text(
                        text = feelsLike,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = condition,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)) {
                if (rainProb > 0) {
                    Text(
                        text = "Rain: $rainProb%",
                        style = MaterialTheme.typography.labelSmall,
                        color = RainDropDeepBlue,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Text(
                    text = windText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DayDetailTelemetryGrid(day: DailyItem) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Text(
            text = "ATMOSPHERIC TELEMETRY",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.ExtraSmall)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            TelemetryDetailCard(
                title = "UV INDEX",
                value = "${day.uvIndex}",
                subtitle = "${day.uvCategory} risk level",
                icon = Icons.Default.WbSunny,
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "SUNRISE / SUNSET",
                value = day.sunriseTime?.takeIf { it.isNotBlank() } ?: "—",
                subtitle = "Sunset: ${day.sunsetTime?.takeIf { it.isNotBlank() } ?: "—"}",
                icon = Icons.Default.WbTwilight,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            TelemetryDetailCard(
                title = "STORM RISK",
                value = "${day.thunderstormProbability}%",
                subtitle = if (day.thunderstormProbability >= 30) "Storm watch advised" else "Low storm potential",
                icon = Icons.Default.ElectricBolt,
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "HUMIDITY & CLOUDS",
                value = "${day.humidityPercent}%",
                subtitle = "Cloud cover: ${day.cloudCoverPercent}%",
                icon = Icons.Default.Opacity,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            TelemetryDetailCard(
                title = "PRECIPITATION",
                value = "${day.precipitationMm} mm",
                subtitle = "${day.rainProbability}% probability",
                icon = Icons.Default.WaterDrop,
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "WIND SPEEDS",
                value = "${day.dayWindSpeedKmH} km/h",
                subtitle = "Day: ${day.dayWindDirection} • Night: ${day.nightWindSpeedKmH} km/h",
                icon = Icons.Default.Air,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun TelemetryDetailCard(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

    Card(
        shape = RoundedCornerShape(CornerTokens.Large),
        colors = CardDefaults.cardColors(containerColor = cardBackground),
        modifier = modifier
            .heightIn(min = ComponentTokens.BentoCardMinHeight)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(SpacingTokens.Base),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(SpacingTokens.ExtraSmall))
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(IconSizeTokens.Small)
                )
            }

            Column {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}
