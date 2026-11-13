package com.google.weatherapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.theme.*

private enum class HourlyDimensionMode(val label: String) {
    TEMP("Temp"),
    PRECIP("Rain"),
    WIND("Wind"),
    UV("UV")
}

@Composable
fun HourlyForecastStrip(
    hourlyItems: List<HourlyItem>,
    modifier: Modifier = Modifier,
    onVisibleHourChanged: ((HourlyItem) -> Unit)? = null
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) GlassCardBackgroundDark else GlassCardBackgroundLight
    val cardBorder = if (isDark) GlassCardBorderDark else GlassCardBorderLight

    var selectedMode by remember { mutableStateOf(HourlyDimensionMode.TEMP) }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (index in hourlyItems.indices) {
                    onVisibleHourChanged?.invoke(hourlyItems[index])
                }
            }
    }

    val metricValues = remember(hourlyItems, selectedMode) {
        hourlyItems.map { item ->
            when (selectedMode) {
                HourlyDimensionMode.TEMP -> item.tempDegrees.toFloat()
                HourlyDimensionMode.PRECIP -> item.rainProbability.toFloat()
                HourlyDimensionMode.WIND -> item.windSpeedKmH.toFloat()
                HourlyDimensionMode.UV -> item.uvIndex.toFloat()
            }
        }
    }
    val minMetric = metricValues.minOrNull() ?: 0f
    val maxMetric = metricValues.maxOrNull() ?: 1f

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBg)
            .border(
                width = BorderTokens.Hairline,
                color = cardBorder,
                shape = RoundedCornerShape(CornerTokens.Large)
            )
            .padding(vertical = SpacingTokens.Medium),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        // Section Header + Mercury-Style Multi-Dimension Mode Selector Pills
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.Base)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = SkyTextSecondary,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = "HOURLY",
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextSecondary
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HourlyDimensionMode.entries.forEach { mode ->
                    val isSelected = mode == selectedMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(CornerTokens.Pill))
                            .background(if (isSelected) GlassPillHighlight else Transparent)
                            .border(
                                width = BorderTokens.Hairline,
                                color = if (isSelected) GlassCardBorderLight else Transparent,
                                shape = RoundedCornerShape(CornerTokens.Pill)
                            )
                            .clickable { selectedMode = mode }
                            .padding(
                                horizontal = SpacingTokens.Small,
                                vertical = SpacingTokens.Micro
                            )
                    ) {
                        Text(
                            text = mode.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) SkyTextPrimary else SkyTextMuted
                        )
                    }
                }
            }
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = SpacingTokens.Base),
            thickness = BorderTokens.Hairline,
            color = GlassCardDividerColor
        )

        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small),
            contentPadding = PaddingValues(horizontal = SpacingTokens.Base)
        ) {
            itemsIndexed(hourlyItems) { index, item ->
                val prevVal = metricValues.getOrNull(index - 1)
                val currVal = metricValues.getOrElse(index) { 0f }
                val nextVal = metricValues.getOrNull(index + 1)

                HourlyItemCard(
                    item = item,
                    mode = selectedMode,
                    prevVal = prevVal,
                    currVal = currVal,
                    nextVal = nextVal,
                    minVal = minMetric,
                    maxVal = maxMetric
                )
            }
        }
    }
}

@Composable
internal fun HourlyItemCard(
    item: HourlyItem,
    modifier: Modifier = Modifier
) {
    HourlyItemCard(
        item = item,
        mode = HourlyDimensionMode.TEMP,
        prevVal = null,
        currVal = item.tempDegrees.toFloat(),
        nextVal = null,
        minVal = item.tempDegrees.toFloat() - 5f,
        maxVal = item.tempDegrees.toFloat() + 5f,
        modifier = modifier
    )
}

@Composable
private fun HourlyItemCard(
    item: HourlyItem,
    mode: HourlyDimensionMode,
    prevVal: Float?,
    currVal: Float,
    nextVal: Float?,
    minVal: Float,
    maxVal: Float,
    modifier: Modifier = Modifier
) {
    val isNow = item.hourLabel.equals("Now", ignoreCase = true)

    val primaryReadout = when (mode) {
        HourlyDimensionMode.TEMP -> "${item.tempDegrees}°"
        HourlyDimensionMode.PRECIP -> "${item.rainProbability}%"
        HourlyDimensionMode.WIND -> "${item.windSpeedKmH}"
        HourlyDimensionMode.UV -> "UV ${item.uvIndex}"
    }

    val subReadout = when (mode) {
        HourlyDimensionMode.TEMP -> if (item.rainProbability >= 20) "${item.rainProbability}%" else ""
        HourlyDimensionMode.PRECIP -> if (item.precipitationMm > 0.0) "${item.precipitationMm}mm" else "Dry"
        HourlyDimensionMode.WIND -> "km/h"
        HourlyDimensionMode.UV -> "${item.tempDegrees}°"
    }

    val curveAccentColor = when (mode) {
        HourlyDimensionMode.TEMP -> SunCoreGold
        HourlyDimensionMode.PRECIP -> RainDropCyan
        HourlyDimensionMode.WIND -> TempBarCoolColor
        HourlyDimensionMode.UV -> UvHighOrange
    }

    Column(
        modifier = modifier
            .width(ComponentTokens.HourlyCardWidth)
            .height(ComponentTokens.HourlyCardHeight)
            .clip(RoundedCornerShape(CornerTokens.Medium))
            .background(if (isNow) GlassPillHighlight else Transparent)
            .border(
                width = BorderTokens.Hairline,
                color = if (isNow) GlassCardBorderLight else Transparent,
                shape = RoundedCornerShape(CornerTokens.Medium)
            )
            .padding(vertical = SpacingTokens.Small, horizontal = SpacingTokens.ExtraSmall),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = if (isNow) "Now" else item.hourLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isNow) FontWeight.Bold else FontWeight.Medium,
            color = if (isNow) SkyTextPrimary else SkyTextSecondary,
            maxLines = 1
        )

        NothingWeatherIcon(
            effectType = com.google.weatherapp.ui.resolveActiveEffectForHourly(item),
            modifier = Modifier.size(IconSizeTokens.Large)
        )

        // Continuous Bezier Curve Segment connecting adjacent hours
        HourlyCurveSegment(
            prevVal = prevVal,
            currVal = currVal,
            nextVal = nextVal,
            minVal = minVal,
            maxVal = maxVal,
            accentColor = curveAccentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.HourlyCurveCanvasHeight)
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = primaryReadout,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = SkyTextPrimary,
                maxLines = 1
            )
            if (subReadout.isNotEmpty()) {
                Text(
                    text = subReadout,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (mode == HourlyDimensionMode.TEMP) RainDropCyan else SkyTextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HourlyCurveSegment(
    prevVal: Float?,
    currVal: Float,
    nextVal: Float?,
    minVal: Float,
    maxVal: Float,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokePx = with(density) { BorderTokens.Thick.toPx() }
    val dotPx = with(density) { SpacingTokens.ExtraSmall.toPx() }

    Canvas(modifier = modifier) {
        val range = (maxVal - minVal).takeIf { it > 0.01f } ?: 1f
        fun normY(v: Float): Float {
            val ratio = ((v - minVal) / range).coerceIn(0f, 1f)
            return size.height * (0.82f - ratio * 0.64f)
        }

        val cy = normY(currVal)
        val cx = size.width * 0.5f
        val leftY = if (prevVal != null) normY((prevVal + currVal) * 0.5f) else cy
        val rightY = if (nextVal != null) normY((currVal + nextVal) * 0.5f) else cy

        val path = Path().apply {
            moveTo(0f, leftY)
            quadraticTo(cx * 0.5f, cy, cx, cy)
            quadraticTo(cx + cx * 0.5f, cy, size.width, rightY)
        }

        drawPath(
            path = path,
            color = accentColor.copy(alpha = 0.78f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        drawCircle(
            color = PureWhite,
            radius = dotPx * 0.85f,
            center = Offset(cx, cy)
        )
    }
}
