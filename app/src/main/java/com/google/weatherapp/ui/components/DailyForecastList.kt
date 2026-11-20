package com.google.weatherapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.theme.*

@Composable
fun DailyForecastList(
    dailyList: List<DailyItem>,
    modifier: Modifier = Modifier,
    currentTemp: Int? = null,
    onDayClick: (DailyItem) -> Unit = {}
) {
    val globalMin = dailyList.minOfOrNull { it.minTemp } ?: 10
    val globalMax = dailyList.maxOfOrNull { it.maxTemp } ?: 30

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .luminousGlass(cornerRadius = CornerTokens.Large)
            .padding(vertical = SpacingTokens.Medium)
    ) {
        // Section Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SpacingTokens.Base,
                    end = SpacingTokens.Base,
                    bottom = SpacingTokens.Small
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = SkyTextSecondary,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = "10-DAY OUTLOOK",
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextSecondary
                )
            }
            Text(
                text = "Tap a day for wind & hours",
                style = MaterialTheme.typography.labelSmall,
                color = SkyTextMuted
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = SpacingTokens.Base),
            thickness = BorderTokens.Hairline,
            color = GlassCardDividerColor
        )

        dailyList.forEachIndexed { index, item ->
            val isToday = index == 0 || item.dayLabel.equals("Today", ignoreCase = true)
            DailyForecastItemCard(
                item = item,
                globalMin = globalMin,
                globalMax = globalMax,
                isToday = isToday,
                currentTemp = if (isToday) currentTemp else null,
                showDivider = index < dailyList.lastIndex,
                onClick = { onDayClick(item) }
            )
        }
    }
}

@Composable
private fun DailyForecastItemCard(
    item: DailyItem,
    globalMin: Int,
    globalMax: Int,
    isToday: Boolean,
    currentTemp: Int?,
    showDivider: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        val rowModifier = if (isToday) {
            Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.Small, vertical = SpacingTokens.ExtraSmall)
                .clip(RoundedCornerShape(CornerTokens.Medium))
                .background(GlassInnerTopSheen)
                .padding(vertical = SpacingTokens.Medium, horizontal = SpacingTokens.Small)
        } else {
            Modifier
                .fillMaxWidth()
                .padding(vertical = SpacingTokens.Medium, horizontal = SpacingTokens.Base)
        }

        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Day + Date
            Column(modifier = Modifier.width(ComponentTokens.DailyItemDayLabelWidth)) {
                Text(
                    text = item.dayLabel,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                    color = SkyTextPrimary,
                    maxLines = 1
                )
                Text(
                    text = item.dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted,
                    maxLines = 1
                )
            }

            // Luminous Glass Icon & Optional Rain Probability
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                modifier = Modifier.width(ComponentTokens.DailyRainColumnWidth)
            ) {
                NothingWeatherIcon(
                    effectType = com.google.weatherapp.ui.resolveActiveEffectForDay(item),
                    isPartlyCloudy = isPartlyCloudyCondition(item.conditionText.ifBlank { item.dayConditionText }),
                    isDaytime = true,
                    animate = false,
                    modifier = Modifier.size(IconSizeTokens.Medium)
                )

                if (item.rainProbability >= 15) {
                    Text(
                        text = "${item.rainProbability}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = RainDropCyan
                    )
                }
            }

            // Horizontal Min/Max Temperature Thermal Spectrum Bar
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Text(
                    text = "${item.minTemp}°",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = SkyTextSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(ComponentTokens.DailyTempLabelWidth),
                    maxLines = 1,
                    softWrap = false
                )

                HorizontalTempRangeBar(
                    minTemp = item.minTemp,
                    maxTemp = item.maxTemp,
                    globalMin = globalMin,
                    globalMax = globalMax,
                    currentTemp = currentTemp,
                    modifier = Modifier.size(
                        width = ComponentTokens.TempBarWidth,
                        height = ComponentTokens.TempBarHeight
                    )
                )

                Text(
                    text = "${item.maxTemp}°",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = SkyTextPrimary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(ComponentTokens.DailyTempLabelWidth),
                    maxLines = 1,
                    softWrap = false
                )

                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View details for ${item.dayLabel}",
                    tint = SkyTextMuted,
                    modifier = Modifier.size(IconSizeTokens.Small)
                )
            }
        }

        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = SpacingTokens.Base),
                thickness = BorderTokens.Hairline,
                color = GlassCardDividerColor
            )
        }
    }
}

@Composable
private fun HorizontalTempRangeBar(
    minTemp: Int,
    maxTemp: Int,
    globalMin: Int,
    globalMax: Int,
    currentTemp: Int? = null,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val totalRange = (globalMax - globalMin).coerceAtLeast(1).toFloat()
        val startRatio = ((minTemp - globalMin) / totalRange).coerceIn(0f, 1f)
        val endRatio = ((maxTemp - globalMin) / totalRange).coerceIn(0f, 1f)

        // Recessed glass track background
        drawRoundRect(
            color = TempBarTrackColor,
            cornerRadius = CornerRadius(size.height / 2, size.height / 2),
            size = size
        )

        val startX = size.width * startRatio
        val endX = (size.width * endRatio).coerceAtLeast(startX + size.height)

        // Multi-stop thermal spectrum mapped across full bar width
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    TempBarCoolColor,
                    TempBarMildColor,
                    TempBarWarmColor,
                    TempBarHotColor
                ),
                startX = 0f,
                endX = size.width
            ),
            topLeft = Offset(startX, 0f),
            size = Size(endX - startX, size.height),
            cornerRadius = CornerRadius(size.height / 2, size.height / 2)
        )

        // Signature glowing current-temperature indicator dot on Today's bar
        if (currentTemp != null) {
            val currRatio = ((currentTemp - globalMin) / totalRange).coerceIn(startRatio, endRatio)
            val dotX = (size.width * currRatio).coerceIn(size.height * 0.6f, size.width - size.height * 0.6f)
            val cy = size.height * 0.5f

            drawCircle(
                color = Black.copy(alpha = 0.55f),
                radius = size.height * 0.92f,
                center = Offset(dotX, cy)
            )
            drawCircle(
                color = PureWhite,
                radius = size.height * 0.64f,
                center = Offset(dotX, cy)
            )
        }
    }
}
