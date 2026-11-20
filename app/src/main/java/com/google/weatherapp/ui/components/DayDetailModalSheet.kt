package com.google.weatherapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    val closeBtnBg = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val closeBtnBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight

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
                // Sheet Header with Editorial Newsreader Title & Tactile Close Pill
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = day.dayLabel,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = day.fullDateString.ifBlank { day.dateText },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(ComponentTokens.TopBarActionButtonSize)
                            .clip(CircleShape)
                            .background(closeBtnBg)
                            .border(BorderTokens.Hairline, closeBtnBorder, CircleShape)
                            .clickable(onClick = onDismiss)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close details",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(IconSizeTokens.Small)
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

                // Hero Summary Card for Selected Day
                DayDetailHeroCard(day = day, effectChoice = effectChoice)

                // 24-Hour Continuous Curve Hourly Forecast for Selected Day
                val hourly = day.hourlyList
                if (hourly.isNotEmpty()) {
                    DayDetailHourlyStrip(dayLabel = day.dayLabel, hourlyItems = hourly)
                }

                // Visually Differentiated Day vs. Night Period Comparison (with full wind details)
                DayNightComparisonSection(day = day)

                // Granular Telemetry Grid with Visual Micro-Gauges
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
    val curveColor = if (isDark) SunCoreGold else PrimaryLight

    val temps = remember(hourlyItems) { hourlyItems.map { it.tempDegrees.toFloat() } }
    val minTemp = temps.minOrNull() ?: 0f
    val maxTemp = temps.maxOrNull() ?: 1f

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
            text = "HOURLY PROGRESSION · ${dayLabel.uppercase()}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = SpacingTokens.Base)
        )

        // Zero-gap LazyRow for seamless continuous temperature curve inside Day Detail
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.None),
            contentPadding = PaddingValues(horizontal = SpacingTokens.Medium)
        ) {
            itemsIndexed(hourlyItems) { index, item ->
                DayDetailHourlyItem(
                    item = item,
                    prevTemp = temps.getOrNull(index - 1),
                    currTemp = temps.getOrElse(index) { 0f },
                    nextTemp = temps.getOrNull(index + 1),
                    minTemp = minTemp,
                    maxTemp = maxTemp,
                    curveColor = curveColor,
                    isFirst = index == 0,
                    isLast = index == hourlyItems.lastIndex
                )
            }
        }
    }
}

@Composable
private fun DayDetailHourlyItem(
    item: HourlyItem,
    prevTemp: Float?,
    currTemp: Float,
    nextTemp: Float?,
    minTemp: Float,
    maxTemp: Float,
    curveColor: Color,
    isFirst: Boolean,
    isLast: Boolean
) {
    Column(
        modifier = Modifier
            .width(DayDetailTokens.DetailHourlyCardWidth)
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
            isPartlyCloudy = isPartlyCloudyCondition(item.conditionText),
            isDaytime = item.isDaytime,
            animate = false,
            modifier = Modifier.size(IconSizeTokens.Medium)
        )
        HourlyCurveSegment(
            prevVal = prevTemp,
            currVal = currTemp,
            nextVal = nextTemp,
            minVal = minTemp,
            maxVal = maxTemp,
            accentColor = curveColor,
            isFirst = isFirst,
            isLast = isLast,
            modifier = Modifier
                .fillMaxWidth()
                .height(DayDetailTokens.DetailHourlyCurveHeight)
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "${item.tempDegrees}°",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = if (item.rainProbability >= 20) "${item.rainProbability}%" else " ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (item.rainProbability >= 20) RainDropDeepBlue else Transparent,
                maxLines = 1
            )
        }
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
                isPartlyCloudy = isPartlyCloudyCondition(item.conditionText.ifBlank { item.dayConditionText }),
                isDaytime = true,
                animate = false,
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBackground)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
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
                    maxLines = 2
                )
                Spacer(modifier = Modifier.height(SpacingTokens.ExtraSmall))
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.Micro)) {
                    Text(
                        text = "High ${day.maxTemp}°C · Low ${day.minTemp}°C",
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

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                        modifier = Modifier.padding(top = SpacingTokens.ExtraSmall)
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
                        val agreementText = if (day.modelSpreadDegrees > 0.0) {
                            "${day.confidencePercent}% consensus · ±${day.modelSpreadDegrees}° spread"
                        } else {
                            "${day.confidencePercent}% single-source forecast"
                        }
                        Text(
                            text = agreementText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            NothingWeatherIcon(
                effectType = resolveActiveEffectForDay(day, effectChoice),
                isPartlyCloudy = isPartlyCloudyCondition(day.conditionText.ifBlank { day.dayConditionText }),
                isDaytime = true,
                animate = true,
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
                    text = "${day.rainProbability}% chance of rain · ${day.precipitationMm} mm expected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun DayNightComparisonSection(day: DailyItem) {
    val dayWindLabel = when {
        day.morningWind.isNotBlank() -> day.morningWind
        day.dayWindSpeedKmH > 0 -> "Wind ${day.dayWindSpeedKmH} km/h ${day.dayWindDirection}"
        else -> "Calm breeze"
    }
    val nightWindLabel = when {
        day.nightWind.isNotBlank() -> day.nightWind
        day.nightWindSpeedKmH > 0 -> "Night: ${day.nightWindSpeedKmH} km/h ${day.nightWindDirection}"
        day.afternoonWind.isNotBlank() -> day.afternoonWind
        else -> "Light night air"
    }

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
                isDayCard = true,
                iconVector = Icons.Default.WbSunny,
                tempLabel = "${day.maxTemp}°C",
                feelsLike = day.feelsLikeMaxTemp?.let { "Feels $it°" } ?: "",
                condition = day.dayConditionText,
                effectType = resolveActiveEffectForDay(day),
                isPartlyCloudy = isPartlyCloudyCondition(day.dayConditionText),
                rainProb = day.dayRainProbability,
                windText = dayWindLabel,
                modifier = Modifier.weight(1f)
            )

            PeriodForecastCard(
                title = "NIGHT",
                isDayCard = false,
                iconVector = Icons.Default.NightsStay,
                tempLabel = "${day.minTemp}°C",
                feelsLike = day.feelsLikeMinTemp?.let { "Feels $it°" } ?: "",
                condition = day.nightConditionText,
                effectType = resolveActiveEffectForNight(day),
                isPartlyCloudy = isPartlyCloudyCondition(day.nightConditionText),
                rainProb = day.nightRainProbability,
                windText = nightWindLabel,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun PeriodForecastCard(
    title: String,
    isDayCard: Boolean,
    iconVector: ImageVector,
    tempLabel: String,
    feelsLike: String,
    condition: String,
    effectType: WeatherEffectType,
    isPartlyCloudy: Boolean,
    rainProb: Int,
    windText: String,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val baseCardBg = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val periodTint = when {
        isDayCard && isDark -> DayDetailPeriodDayBgDark
        isDayCard -> DayDetailPeriodDayBg
        isDark -> DayDetailPeriodNightBgDark
        else -> DayDetailPeriodNightBg
    }
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight
    val accentTint = if (isDayCard) {
        if (isDark) SunCoreGold else SunCoreDeepAmber
    } else {
        if (isDark) PrimaryDark else PrimaryLight
    }

    Column(
        modifier = modifier
            .heightIn(min = DayDetailTokens.BreakdownCardMinHeight)
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(baseCardBg)
            .background(
                Brush.verticalGradient(
                    colors = listOf(periodTint, Transparent)
                )
            )
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
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
                    tint = accentTint,
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
                isPartlyCloudy = isPartlyCloudy,
                isDaytime = isDayCard,
                animate = false,
                modifier = Modifier.size(IconSizeTokens.Medium)
            )
        }

        Column(modifier = Modifier.padding(vertical = SpacingTokens.ExtraSmall)) {
            Text(
                text = tempLabel,
                style = MaterialTheme.typography.headlineLarge,
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
                    text = "Rain $rainProb%",
                    style = MaterialTheme.typography.labelSmall,
                    color = RainDropDeepBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.Air,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = windText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
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
                barRatio = (day.uvIndex / 11f).coerceIn(0f, 1f),
                barColors = listOf(UvLowGreen, UvModerateYellow, UvHighOrange, UvVeryHighRed),
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "SUNRISE / SUNSET",
                value = day.sunriseTime?.takeIf { it.isNotBlank() } ?: "—",
                subtitle = "Sunset ${day.sunsetTime?.takeIf { it.isNotBlank() } ?: "—"}",
                icon = Icons.Default.WbTwilight,
                barRatio = 0.65f,
                barColors = listOf(SunCoreDeepAmber, SunCoreGold),
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
                barRatio = (day.thunderstormProbability / 100f).coerceIn(0f, 1f),
                barColors = listOf(SunCoreGold, UvHighOrange),
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "HUMIDITY & CLOUDS",
                value = "${day.humidityPercent}%",
                subtitle = "Cloud cover ${day.cloudCoverPercent}%",
                icon = Icons.Default.Opacity,
                barRatio = (day.humidityPercent / 100f).coerceIn(0f, 1f),
                barColors = listOf(TempBarCoolColor, RainDropDeepBlue),
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
                barRatio = (day.rainProbability / 100f).coerceIn(0f, 1f),
                barColors = listOf(RainDropCyan, RainDropDeepBlue),
                modifier = Modifier.weight(1f)
            )

            TelemetryDetailCard(
                title = "WIND SPEEDS",
                value = "${day.dayWindSpeedKmH} km/h",
                subtitle = "Day ${day.dayWindDirection} · Night ${day.nightWindSpeedKmH} km/h",
                icon = Icons.Default.Air,
                barRatio = (day.dayWindSpeedKmH / 60f).coerceIn(0.08f, 1f),
                barColors = listOf(TempBarMildColor, TempBarCoolColor),
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
    barRatio: Float,
    barColors: List<Color>,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val cardBackground = if (isDark) DayDetailCardSurfaceDark else DayDetailCardSurfaceLight
    val cardBorder = if (isDark) DayDetailCardBorderDark else DayDetailCardBorderLight
    val trackColor = if (isDark) TempBarTrackColor else SurfaceVariantLight

    Column(
        modifier = modifier
            .heightIn(min = ComponentTokens.BentoCardMinHeight)
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBackground)
            .border(BorderTokens.Hairline, cardBorder, RoundedCornerShape(CornerTokens.Large))
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

        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )

        // Micro visual gauge bar
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.DayDetailMicroBarHeight)
        ) {
            val r = size.height * 0.5f
            drawRoundRect(
                color = trackColor,
                size = size,
                cornerRadius = CornerRadius(r, r)
            )
            if (barRatio > 0.01f) {
                val fillW = (size.width * barRatio.coerceIn(0.06f, 1f))
                drawRoundRect(
                    brush = Brush.horizontalGradient(colors = barColors, startX = 0f, endX = size.width),
                    topLeft = Offset.Zero,
                    size = Size(fillW, size.height),
                    cornerRadius = CornerRadius(r, r)
                )
            }
        }

        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2
        )
    }
}
