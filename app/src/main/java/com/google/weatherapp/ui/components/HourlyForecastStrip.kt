package com.google.weatherapp.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Brush
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

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
    var selectedMode by remember { mutableStateOf(HourlyDimensionMode.TEMP) }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    val currentItems by rememberUpdatedState(hourlyItems)
    val currentOnVisibleHourChanged by rememberUpdatedState(onVisibleHourChanged)

    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .drop(1)
            .collect { index ->
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                if (index in currentItems.indices) {
                    currentOnVisibleHourChanged?.invoke(currentItems[index])
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

    val curveAccentColor by animateColorAsState(
        targetValue = when (selectedMode) {
            HourlyDimensionMode.TEMP -> SunCoreGold
            HourlyDimensionMode.PRECIP -> RainDropCyan
            HourlyDimensionMode.WIND -> TempBarCoolColor
            HourlyDimensionMode.UV -> UvHighOrange
        },
        animationSpec = tween(durationMillis = 300),
        label = "hourly_curve_accent"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .luminousGlass(cornerRadius = CornerTokens.Large)
            .padding(vertical = SpacingTokens.Medium),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        // Header + Recessed Frosted Segmented Mode Selector
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

            // Segmented frosted capsule track
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Micro),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(CornerTokens.Pill))
                    .background(GlassSegmentedTrackBg)
                    .border(
                        width = BorderTokens.Hairline,
                        color = GlassCardDividerColor,
                        shape = RoundedCornerShape(CornerTokens.Pill)
                    )
                    .padding(SpacingTokens.Micro)
            ) {
                HourlyDimensionMode.entries.forEach { mode ->
                    val isSelected = mode == selectedMode
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(CornerTokens.Pill))
                            .background(if (isSelected) GlassPillHighlight else Transparent)
                            .border(
                                width = BorderTokens.Hairline,
                                color = if (isSelected) GlassRimSpecularTop else Transparent,
                                shape = RoundedCornerShape(CornerTokens.Pill)
                            )
                            .clickable { selectedMode = mode }
                            .padding(
                                horizontal = SpacingTokens.Small,
                                vertical = SpacingTokens.ExtraSmall
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

        // Zero-gap LazyRow so adjacent spline segments weld seamlessly edge-to-edge
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.None),
            contentPadding = PaddingValues(horizontal = SpacingTokens.Medium)
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
                    maxVal = maxMetric,
                    accentColor = curveAccentColor,
                    isFirst = index == 0,
                    isLast = index == hourlyItems.lastIndex
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
        accentColor = SunCoreGold,
        isFirst = true,
        isLast = true,
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
    accentColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
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

    Box(
        modifier = modifier
            .width(ComponentTokens.HourlyCardWidth)
            .height(ComponentTokens.HourlyCardHeight)
    ) {
        // Subtle vertical glass pill highlight behind "Now" column (inset horizontally so curve stays unbroken)
        if (isNow) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = SpacingTokens.ExtraSmall, vertical = SpacingTokens.Micro)
                    .clip(RoundedCornerShape(CornerTokens.Medium))
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                GlassPillHighlight,
                                GlassInnerTopSheen
                            )
                        )
                    )
                    .border(
                        width = BorderTokens.Hairline,
                        brush = Brush.verticalGradient(
                            colors = listOf(GlassRimSpecularTop, GlassRimSpecularBottom)
                        ),
                        shape = RoundedCornerShape(CornerTokens.Medium)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = SpacingTokens.Small),
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
                isPartlyCloudy = isPartlyCloudyCondition(item.conditionText),
                isDaytime = item.isDaytime,
                animate = false,
                modifier = Modifier.size(IconSizeTokens.Large)
            )

            // Seamless edge-to-edge continuous Bezier spline with soft gradient area fill
            HourlyCurveSegment(
                prevVal = prevVal,
                currVal = currVal,
                nextVal = nextVal,
                minVal = minVal,
                maxVal = maxVal,
                accentColor = accentColor,
                isNow = isNow,
                isFirst = isFirst,
                isLast = isLast,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(ComponentTokens.HourlyCurveCanvasHeight)
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = SpacingTokens.ExtraSmall)
            ) {
                Text(
                    text = primaryReadout,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = SkyTextPrimary,
                    maxLines = 1
                )
                Text(
                    text = subReadout.ifEmpty { " " },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (subReadout.isEmpty()) Transparent else if (mode == HourlyDimensionMode.TEMP) RainDropCyan else SkyTextMuted,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
internal fun HourlyCurveSegment(
    prevVal: Float?,
    currVal: Float,
    nextVal: Float?,
    minVal: Float,
    maxVal: Float,
    accentColor: Color,
    isNow: Boolean = false,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokePx = with(density) { BorderTokens.Thick.toPx() }
    val dotPx = with(density) { SpacingTokens.ExtraSmall.toPx() }

    Canvas(modifier = modifier) {
        val range = (maxVal - minVal).takeIf { it > 0.01f } ?: 1f
        fun normY(v: Float): Float {
            val ratio = ((v - minVal) / range).coerceIn(0f, 1f)
            return size.height * (0.76f - ratio * 0.56f)
        }

        val cy = normY(currVal)
        val cx = size.width * 0.5f
        val prevY = if (prevVal != null) normY(prevVal) else cy
        val nextY = if (nextVal != null) normY(nextVal) else cy
        val leftY = (prevY + cy) * 0.5f
        val rightY = (cy + nextY) * 0.5f

        // C1-continuous Hermite slopes match seamlessly across cell boundaries with zero staircasing
        val mLeft = (cy - prevY) / size.width
        val mRight = (nextY - cy) / size.width
        val mCurr = (nextY - prevY) / (2f * size.width)
        val dxThird = cx / 3f

        val leftStartX = if (isFirst) cx else 0f
        val rightEndX = if (isLast) cx else size.width

        val strokePath = Path().apply {
            if (!isFirst && !isLast) {
                moveTo(0f, leftY)
                cubicTo(
                    dxThird, leftY + mLeft * dxThird,
                    cx - dxThird, cy - mCurr * dxThird,
                    cx, cy
                )
                cubicTo(
                    cx + dxThird, cy + mCurr * dxThird,
                    size.width - dxThird, rightY - mRight * dxThird,
                    size.width, rightY
                )
            } else if (isFirst && !isLast) {
                moveTo(cx, cy)
                cubicTo(
                    cx + dxThird, cy + mCurr * dxThird,
                    size.width - dxThird, rightY - mRight * dxThird,
                    size.width, rightY
                )
            } else if (!isFirst && isLast) {
                moveTo(0f, leftY)
                cubicTo(
                    dxThird, leftY + mLeft * dxThird,
                    cx - dxThird, cy - mCurr * dxThird,
                    cx, cy
                )
            } else {
                moveTo(cx * 0.4f, cy)
                lineTo(cx * 1.6f, cy)
            }
        }

        // Soft vertical gradient fill beneath the curve
        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(rightEndX, size.height)
            lineTo(leftStartX, size.height)
            close()
        }
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    accentColor.copy(alpha = 0.24f),
                    Transparent
                ),
                startY = 0f,
                endY = size.height
            )
        )

        // Luminous spline stroke
        drawPath(
            path = strokePath,
            color = accentColor.copy(alpha = 0.90f),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Halo + anchor dot (accentuated on "Now")
        if (isNow) {
            drawCircle(
                color = accentColor.copy(alpha = 0.35f),
                radius = dotPx * 1.8f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = PureWhite,
                radius = dotPx * 0.95f,
                center = Offset(cx, cy)
            )
        } else {
            drawCircle(
                color = accentColor.copy(alpha = 0.28f),
                radius = dotPx * 1.15f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = PureWhite,
                radius = dotPx * 0.68f,
                center = Offset(cx, cy)
            )
        }
    }
}
