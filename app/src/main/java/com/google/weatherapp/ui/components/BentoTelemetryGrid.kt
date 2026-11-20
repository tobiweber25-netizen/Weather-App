package com.google.weatherapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WbTwilight
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.theme.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private enum class TelemetryMetricType {
    WIND, PRECIPITATION, UV, HUMIDITY
}

@Composable
fun BentoTelemetryGrid(
    telemetry: BentoTelemetry,
    hourlyList: List<HourlyItem> = emptyList(),
    isDaytime: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isSevereWind = telemetry.windGustKmH > 40
    var expandedMetric by remember { mutableStateOf<TelemetryMetricType?>(null) }

    val timeAxisLabels = remember(hourlyList) {
        if (hourlyList.size < 4) emptyList()
        else listOf(
            hourlyList.first().hourLabel,
            hourlyList[hourlyList.size / 3].hourLabel,
            hourlyList[(hourlyList.size * 2) / 3].hourLabel,
            hourlyList.last().hourLabel
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        // Row 1 (Wide Feature): Full-Width Solar Horizon Arc Instrument
        SolarHorizonFeatureCard(
            telemetry = telemetry,
            isDaytime = isDaytime
        )

        // Row 2: Wind Compass Rose & Curved Barometer Dial
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val windSubtitle = if (telemetry.windGustKmH > telemetry.windSpeedKmH) {
                "${telemetry.windDirection} · Gusts ${telemetry.windGustKmH} km/h"
            } else {
                "From ${telemetry.windDirection}"
            }

            BentoGlassTile(
                title = if (isSevereWind) "HIGH WIND" else "WIND",
                value = "${telemetry.windSpeedKmH} km/h",
                subtitle = windSubtitle,
                icon = Icons.Default.Air,
                isAlert = isSevereWind,
                isExpanded = expandedMetric == TelemetryMetricType.WIND,
                isExpandable = hourlyList.isNotEmpty(),
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.WIND) null else TelemetryMetricType.WIND
                    }
                },
                gaugeContent = {
                    WindCompassGauge(
                        directionText = telemetry.windDirection,
                        speedKmH = telemetry.windSpeedKmH,
                        degrees = telemetry.windDegrees,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoCompassHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            val pressureMood = when {
                telemetry.pressureHpa < 1005 -> "Low pressure"
                telemetry.pressureHpa > 1022 -> "High pressure"
                else -> "Steady · Sea level"
            }

            BentoGlassTile(
                title = "PRESSURE",
                value = "${telemetry.pressureHpa} hPa",
                subtitle = pressureMood,
                icon = Icons.Default.Compress,
                onClick = null,
                gaugeContent = {
                    BarometerArcGauge(
                        pressureHpa = telemetry.pressureHpa,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoBarometerHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Expandable 24h Sparkline Drawer for Wind
        AnimatedVisibility(
            visible = expandedMetric == TelemetryMetricType.WIND,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val windData = remember(hourlyList) { hourlyList.map { it.windSpeedKmH.toFloat() } }
            ExpandedSparklineGlassCard(
                title = "24-HOUR WIND SPEED",
                unitSuffix = " km/h",
                data = windData,
                timeLabels = timeAxisLabels,
                accentColor = SunCoreGold
            )
        }

        // Row 3: UV Index & Precipitation (Both Expandable)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val uvSubtitle = if (telemetry.maxUvIndex > telemetry.uvIndex) {
                "${telemetry.uvCategory} · Peak ${telemetry.maxUvIndex} today"
            } else {
                "${telemetry.uvCategory} exposure"
            }

            BentoGlassTile(
                title = "UV INDEX",
                value = "${telemetry.uvIndex}",
                subtitle = uvSubtitle,
                icon = Icons.Default.WbSunny,
                isExpanded = expandedMetric == TelemetryMetricType.UV,
                isExpandable = hourlyList.isNotEmpty(),
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.UV) null else TelemetryMetricType.UV
                    }
                },
                gaugeContent = {
                    UvSpectrumMicroGauge(
                        uvIndex = telemetry.uvIndex,
                        maxUvIndex = telemetry.maxUvIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            BentoGlassTile(
                title = "PRECIPITATION",
                value = "${telemetry.precipitationMm} mm",
                subtitle = if (telemetry.precipitationMm > 0.0) "24h accumulation" else "Dry conditions",
                icon = Icons.Default.WaterDrop,
                isExpanded = expandedMetric == TelemetryMetricType.PRECIPITATION,
                isExpandable = hourlyList.isNotEmpty(),
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.PRECIPITATION) null else TelemetryMetricType.PRECIPITATION
                    }
                },
                gaugeContent = {
                    PrecipitationMiniHistogram(
                        hourlyList = hourlyList,
                        totalMm = telemetry.precipitationMm,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Expandable 24h Sparkline Drawer for UV or Precipitation
        AnimatedVisibility(
            visible = expandedMetric == TelemetryMetricType.UV || expandedMetric == TelemetryMetricType.PRECIPITATION,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val isUv = expandedMetric == TelemetryMetricType.UV
            val chartData = remember(hourlyList, isUv) {
                if (isUv) hourlyList.map { it.uvIndex.toFloat() }
                else hourlyList.map { it.precipitationMm.toFloat() }
            }
            ExpandedSparklineGlassCard(
                title = if (isUv) "24-HOUR UV EXPOSURE" else "24-HOUR PRECIPITATION",
                unitSuffix = if (isUv) "" else " mm",
                data = chartData,
                timeLabels = timeAxisLabels,
                accentColor = if (isUv) UvHighOrange else RainDropCyan
            )
        }

        // Row 4: Humidity & Air Quality (AQI)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            BentoGlassTile(
                title = "HUMIDITY",
                value = "${telemetry.humidityPercent}%",
                subtitle = "Dew point ${telemetry.dewPointDegrees}°",
                icon = Icons.Default.Opacity,
                isExpanded = expandedMetric == TelemetryMetricType.HUMIDITY,
                isExpandable = hourlyList.isNotEmpty(),
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.HUMIDITY) null else TelemetryMetricType.HUMIDITY
                    }
                },
                gaugeContent = {
                    HumidityGraduatedGauge(
                        humidityPercent = telemetry.humidityPercent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            val isAqiAvailable = telemetry.aqiCategory != "Unavailable" && telemetry.aqiCategory != "N/A"
            val aqiDisplay = if (isAqiAvailable) "${telemetry.aqiValue}" else "—"
            val aqiSubtitle = when {
                isAqiAvailable && telemetry.pm25 > 0.0 -> "${telemetry.aqiCategory} · PM2.5 ${telemetry.pm25}"
                isAqiAvailable -> telemetry.aqiCategory
                else -> "Sensor unavailable"
            }

            BentoGlassTile(
                title = "AIR QUALITY",
                value = aqiDisplay,
                subtitle = aqiSubtitle,
                icon = Icons.Default.Eco,
                onClick = null,
                gaugeContent = {
                    AqiSpectrumMicroGauge(
                        aqiValue = telemetry.aqiValue,
                        isAvailable = isAqiAvailable,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Expandable 24h Sparkline Drawer for Humidity
        AnimatedVisibility(
            visible = expandedMetric == TelemetryMetricType.HUMIDITY,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            val humidityData = remember(hourlyList) { hourlyList.map { it.humidityPercent.toFloat() } }
            ExpandedSparklineGlassCard(
                title = "24-HOUR RELATIVE HUMIDITY",
                unitSuffix = "%",
                data = humidityData,
                timeLabels = timeAxisLabels,
                accentColor = TempBarCoolColor
            )
        }

        // Row 5 (Wide Footer Strip): Visibility & Comfort Instrument
        VisibilityComfortFeatureStrip(telemetry = telemetry)
    }
}

@Composable
private fun SolarHorizonFeatureCard(
    telemetry: BentoTelemetry,
    isDaytime: Boolean
) {
    val primaryTitle = if (isDaytime) "DAYLIGHT ARC" else "NIGHT CYCLE"
    val nextEventLabel = if (isDaytime) "Sunset ${telemetry.sunsetTime}" else "Sunrise ${telemetry.sunriseTime}"
    val sunIcon = if (isDaytime) Icons.Default.WbSunny else Icons.Default.WbTwilight
    val iconTint = if (isDaytime) SunCoreGold else MoonPearlCore

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ComponentTokens.BentoSolarCardHeight)
            .luminousGlass(cornerRadius = CornerTokens.Large)
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
                    imageVector = sunIcon,
                    contentDescription = primaryTitle,
                    tint = iconTint,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = primaryTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextSecondary
                )
            }

            Text(
                text = nextEventLabel,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = SkyTextPrimary
            )
        }

        SolarHorizonArcGauge(
            isDaytime = isDaytime,
            sunProgress = telemetry.sunProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.BentoSolarArcHeight)
                .padding(vertical = SpacingTokens.ExtraSmall)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Text(
                    text = "RISE",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
                Text(
                    text = telemetry.sunriseTime,
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextPrimary
                )
            }

            val daylightStatus = when {
                !isDaytime -> "Below horizon"
                telemetry.sunProgress < 0.18f -> "Morning golden hour"
                telemetry.sunProgress > 0.82f -> "Evening golden hour"
                telemetry.sunProgress in 0.42f..0.58f -> "Near solar noon"
                else -> "${(telemetry.sunProgress * 100).roundToInt()}% of daylight"
            }
            Text(
                text = daylightStatus,
                style = MaterialTheme.typography.labelSmall,
                color = if (isDaytime) SunCoreGold.copy(alpha = 0.88f) else SkyTextMuted
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Text(
                    text = "SET",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextMuted
                )
                Text(
                    text = telemetry.sunsetTime,
                    style = MaterialTheme.typography.labelLarge,
                    color = SkyTextPrimary
                )
            }
        }
    }
}

@Composable
private fun VisibilityComfortFeatureStrip(
    telemetry: BentoTelemetry
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .luminousGlass(cornerRadius = CornerTokens.Large)
            .padding(SpacingTokens.Base),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = SpacingTokens.Medium),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
            ) {
                Icon(
                    imageVector = Icons.Default.Visibility,
                    contentDescription = "Visibility",
                    tint = TempBarMildColor,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = "VISIBILITY & COMFORT",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextSecondary
                )
            }
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
            ) {
                Text(
                    text = "${telemetry.visibilityKm} km",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextPrimary
                )
                val clarityText = when {
                    telemetry.visibilityKm >= 10.0 -> "Crystal clear"
                    telemetry.visibilityKm >= 5.0 -> "Good clarity"
                    telemetry.visibilityKm >= 2.0 -> "Light haze"
                    else -> "Low visibility"
                }
                Text(
                    text = clarityText,
                    style = MaterialTheme.typography.labelMedium,
                    color = TempBarMildColor,
                    modifier = Modifier.padding(bottom = SpacingTokens.ExtraSmall)
                )
            }
            Text(
                text = telemetry.feelsLikeReason,
                style = MaterialTheme.typography.bodySmall,
                color = SkyTextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        VisibilityClarityHorizonGraphic(
            visibilityKm = telemetry.visibilityKm,
            modifier = Modifier.size(width = ComponentTokens.TempBarWidth, height = ComponentTokens.BentoGaugeHeight)
        )
    }
}

@Composable
private fun BentoGlassTile(
    title: String,
    value: String,
    subtitle: String,
    icon: ImageVector,
    onClick: (() -> Unit)?,
    gaugeContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    isAlert: Boolean = false,
    isExpanded: Boolean = false,
    isExpandable: Boolean = false
) {
    val accentBorder = when {
        isAlert -> AlertSevereBorder
        isExpanded -> GlassActiveBorderGold
        else -> null
    }
    val overrideTop = if (isAlert) AlertSevereContainer else null
    val overrideBottom = if (isAlert) AlertSevereContainer.copy(alpha = 0.65f) else null

    Column(
        modifier = modifier
            .heightIn(min = ComponentTokens.BentoCardMinHeight)
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                elevated = isExpanded,
                accentBorderColor = accentBorder,
                overrideTopColor = overrideTop,
                overrideBottomColor = overrideBottom,
                borderWidth = if (isExpanded || isAlert) BorderTokens.Subtle else BorderTokens.Hairline
            )
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isExpanded) SunCoreGold else SkyTextSecondary,
                    modifier = Modifier.size(IconSizeTokens.Micro)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (isExpandable) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(SpacingTokens.Large)
                        .clip(RoundedCornerShape(CornerTokens.Pill))
                        .background(if (isExpanded) GlassActiveGlowGold else GlassSegmentedTrackBg)
                        .border(
                            width = BorderTokens.Hairline,
                            color = if (isExpanded) GlassActiveBorderGold else GlassCardDividerColor,
                            shape = RoundedCornerShape(CornerTokens.Pill)
                        )
                ) {
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "Collapse 24h trend" else "Expand 24h trend",
                        tint = if (isExpanded) SunCoreGold else SkyTextMuted,
                        modifier = Modifier.size(IconSizeTokens.Micro)
                    )
                }
            }
        }

        Text(
            text = value,
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            color = SkyTextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        gaugeContent()

        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = SkyTextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ExpandedSparklineGlassCard(
    title: String,
    unitSuffix: String,
    data: List<Float>,
    timeLabels: List<String>,
    accentColor: Color
) {
    val maxVal = data.maxOrNull() ?: 0f
    val minVal = data.minOrNull() ?: 0f
    val formatNum: (Float) -> String = { v ->
        if (v % 1f == 0f || v >= 10f) "${v.roundToInt()}$unitSuffix"
        else String.format(java.util.Locale.US, "%.1f%s", v, unitSuffix)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .luminousGlass(
                cornerRadius = CornerTokens.Large,
                elevated = true,
                accentBorderColor = accentColor.copy(alpha = 0.50f)
            )
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )
            if (data.isNotEmpty()) {
                Text(
                    text = "Peak ${formatNum(maxVal)}  ·  Low ${formatNum(minVal)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SkyTextSecondary
                )
            }
        }

        DotMatrixGraph(
            data = data,
            color = accentColor,
            unitSuffix = unitSuffix,
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.BentoSparklineHeight)
        )

        if (timeLabels.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                timeLabels.forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = SkyTextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun AqiSpectrumMicroGauge(
    aqiValue: Int,
    isAvailable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.55f
        val segments = listOf(UvLowGreen, UvModerateYellow, UvHighOrange, UvVeryHighRed, UvExtremeViolet)
        val gap = 4f
        val segW = (size.width - gap * (segments.size - 1)) / segments.size

        if (!isAvailable) {
            segments.forEachIndexed { idx, _ ->
                drawRoundRect(
                    color = SkyTextMuted.copy(alpha = 0.18f),
                    topLeft = Offset(idx * (segW + gap), cy - barHeightPx * 0.5f),
                    size = Size(segW, barHeightPx),
                    cornerRadius = CornerRadius(barHeightPx, barHeightPx)
                )
            }
            return@Canvas
        }

        val ratio = (aqiValue / 200f).coerceIn(0f, 1f)
        segments.forEachIndexed { idx, col ->
            drawRoundRect(
                color = col.copy(alpha = 0.85f),
                topLeft = Offset(idx * (segW + gap), cy - barHeightPx * 0.5f),
                size = Size(segW, barHeightPx),
                cornerRadius = CornerRadius(barHeightPx, barHeightPx)
            )
        }

        val thumbX = (size.width * ratio).coerceIn(barHeightPx * 1.1f, size.width - barHeightPx * 1.1f)
        drawCircle(
            color = Black.copy(alpha = 0.55f),
            radius = barHeightPx * 1.2f,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            color = PureWhite,
            radius = barHeightPx * 0.85f,
            center = Offset(thumbX, cy)
        )
    }
}

@Composable
private fun UvSpectrumMicroGauge(
    uvIndex: Int,
    maxUvIndex: Int = uvIndex,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.52f
        val ratio = (uvIndex / 11f).coerceIn(0f, 1f)
        val peakRatio = (maxUvIndex / 11f).coerceIn(0f, 1f)

        // Subtle scale ticks below spectrum (0, 3, 6, 8, 11)
        listOf(0f, 0.27f, 0.55f, 0.73f, 1f).forEach { tickFrac ->
            val tx = size.width * tickFrac
            drawLine(
                color = SkyTextMuted.copy(alpha = 0.35f),
                start = Offset(tx, cy + barHeightPx * 1.1f),
                end = Offset(tx, cy + barHeightPx * 1.9f),
                strokeWidth = 1.2f,
                cap = StrokeCap.Round
            )
        }

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(UvLowGreen, UvModerateYellow, UvHighOrange, UvVeryHighRed, UvExtremeViolet)
            ),
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        // Ghost marker for today's peak UV if higher than current
        if (maxUvIndex > uvIndex) {
            val peakX = (size.width * peakRatio).coerceIn(barHeightPx, size.width - barHeightPx)
            drawCircle(
                color = PureWhite.copy(alpha = 0.45f),
                radius = barHeightPx * 0.78f,
                center = Offset(peakX, cy),
                style = Stroke(width = 2f)
            )
        }

        val thumbX = (size.width * ratio).coerceIn(barHeightPx * 1.1f, size.width - barHeightPx * 1.1f)
        drawCircle(
            color = Black.copy(alpha = 0.55f),
            radius = barHeightPx * 1.2f,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            color = PureWhite,
            radius = barHeightPx * 0.85f,
            center = Offset(thumbX, cy)
        )
    }
}

@Composable
private fun PrecipitationMiniHistogram(
    hourlyList: List<HourlyItem>,
    totalMm: Double,
    modifier: Modifier = Modifier
) {
    val upcoming = remember(hourlyList) { hourlyList.take(12) }

    Canvas(modifier = modifier) {
        val barCount = if (upcoming.isNotEmpty()) upcoming.size else 10
        val slotW = size.width / barCount
        val barW = (slotW * 0.55f).coerceIn(3f, 10f)
        val baseY = size.height * 0.88f
        val maxBarH = size.height * 0.72f

        // Hairline baseline
        drawLine(
            color = GlassCardDividerColor,
            start = Offset(0f, baseY),
            end = Offset(size.width, baseY),
            strokeWidth = 1f
        )

        for (i in 0 until barCount) {
            val item = upcoming.getOrNull(i)
            val ratio = if (item != null) {
                maxOf(item.rainProbability / 100f, (item.precipitationMm.toFloat() / 5f).coerceIn(0f, 1f))
            } else {
                (totalMm.toFloat() / 20f).coerceIn(0f, 1f)
            }
            val clampedH = (maxBarH * ratio).coerceAtLeast(3.5f)
            val leftX = i * slotW + (slotW - barW) * 0.5f

            drawRoundRect(
                color = TempBarTrackColor,
                topLeft = Offset(leftX, baseY - maxBarH),
                size = Size(barW, maxBarH),
                cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
            )

            if (ratio > 0.03f) {
                drawRoundRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(RainDropCyan, RainDropDeepBlue),
                        startY = baseY - clampedH,
                        endY = baseY
                    ),
                    topLeft = Offset(leftX, baseY - clampedH),
                    size = Size(barW, clampedH),
                    cornerRadius = CornerRadius(barW * 0.5f, barW * 0.5f)
                )
            }
        }
    }
}

@Composable
private fun HumidityGraduatedGauge(
    humidityPercent: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.52f
        val clamped = (humidityPercent / 100f).coerceIn(0.03f, 1f)

        // Subtle quartile graduation ticks
        listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { frac ->
            val tx = size.width * frac
            drawLine(
                color = SkyTextMuted.copy(alpha = 0.35f),
                start = Offset(tx, cy + barHeightPx * 1.1f),
                end = Offset(tx, cy + barHeightPx * 1.9f),
                strokeWidth = 1.2f,
                cap = StrokeCap.Round
            )
        }

        drawRoundRect(
            color = TempBarTrackColor,
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(TempBarCoolColor.copy(alpha = 0.55f), RainDropCyan)
            ),
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width * clamped, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        val thumbX = (size.width * clamped).coerceIn(barHeightPx, size.width - barHeightPx)
        drawCircle(
            color = PureWhite,
            radius = barHeightPx * 0.78f,
            center = Offset(thumbX, cy)
        )
    }
}

@Composable
private fun VisibilityClarityHorizonGraphic(
    visibilityKm: Double,
    modifier: Modifier = Modifier
) {
    val clarityRatio = (visibilityKm.toFloat() / 16f).coerceIn(0.15f, 1f)

    Canvas(modifier = modifier) {
        val bands = 5
        val gap = size.height / (bands + 1)
        for (i in 0 until bands) {
            val y = gap * (i + 1)
            val widthFrac = 0.45f + (i.toFloat() / (bands - 1)) * 0.55f
            val bandW = size.width * widthFrac
            val startX = (size.width - bandW) * 0.5f
            val activeThreshold = (bands - 1 - i).toFloat() / bands
            val isClearBand = clarityRatio >= activeThreshold
            val alpha = if (isClearBand) 0.28f + (i * 0.14f) else 0.12f

            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Transparent,
                        TempBarMildColor.copy(alpha = alpha.coerceIn(0.1f, 0.92f)),
                        Transparent
                    ),
                    startX = startX,
                    endX = startX + bandW
                ),
                start = Offset(startX, y),
                end = Offset(startX + bandW, y),
                strokeWidth = 3.2f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun SolarHorizonArcGauge(
    isDaytime: Boolean,
    sunProgress: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokePx = with(density) { BorderTokens.Thick.toPx() }
    val hairlinePx = with(density) { BorderTokens.Hairline.toPx() }

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val horizonY = h * 0.72f
        val padX = w * 0.08f
        val arcStartX = padX
        val arcEndX = w - padX
        val spanW = arcEndX - arcStartX
        val peakY = h * 0.12f

        // Dashed nocturnal trajectory dipping below horizon at both ends
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        val leftTail = Path().apply {
            moveTo(0f, h * 0.90f)
            quadraticTo(arcStartX * 0.5f, h * 0.85f, arcStartX, horizonY)
        }
        val rightTail = Path().apply {
            moveTo(arcEndX, horizonY)
            quadraticTo(arcEndX + padX * 0.5f, h * 0.85f, w, h * 0.90f)
        }
        drawPath(
            path = leftTail,
            color = SkyTextMuted.copy(alpha = 0.35f),
            style = Stroke(width = hairlinePx * 1.4f, pathEffect = dashEffect, cap = StrokeCap.Round)
        )
        drawPath(
            path = rightTail,
            color = SkyTextMuted.copy(alpha = 0.35f),
            style = Stroke(width = hairlinePx * 1.4f, pathEffect = dashEffect, cap = StrokeCap.Round)
        )

        // Daylight arc curve
        val arcPath = Path().apply {
            moveTo(arcStartX, horizonY)
            cubicTo(
                arcStartX + spanW * 0.25f, peakY,
                arcStartX + spanW * 0.75f, peakY,
                arcEndX, horizonY
            )
        }

        // Warm translucent sunlight fill inside the dome above the horizon
        val domeFill = Path().apply {
            addPath(arcPath)
            lineTo(arcEndX, horizonY)
            lineTo(arcStartX, horizonY)
            close()
        }
        val domeTint = if (isDaytime) SunCoreGold else MoonPearlCore
        drawPath(
            path = domeFill,
            brush = Brush.verticalGradient(
                colors = listOf(
                    domeTint.copy(alpha = if (isDaytime) 0.24f else 0.12f),
                    Transparent
                ),
                startY = peakY,
                endY = horizonY
            )
        )

        // Horizon baseline
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Transparent, GlassCardBorderLight, Transparent)
            ),
            start = Offset(0f, horizonY),
            end = Offset(w, horizonY),
            strokeWidth = hairlinePx * 1.2f
        )

        val arcColors = if (isDaytime) {
            listOf(SunCoreDeepAmber.copy(alpha = 0.55f), SunCoreGold, SunCoreDeepAmber.copy(alpha = 0.55f))
        } else {
            listOf(SkyTextMuted.copy(alpha = 0.45f), MoonPearlCore.copy(alpha = 0.75f), SkyTextMuted.copy(alpha = 0.45f))
        }
        drawPath(
            path = arcPath,
            brush = Brush.horizontalGradient(colors = arcColors, startX = arcStartX, endX = arcEndX),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Sunrise & sunset horizon anchor dots
        drawCircle(color = SunCoreGold.copy(alpha = 0.75f), radius = strokePx * 0.9f, center = Offset(arcStartX, horizonY))
        drawCircle(color = SunCoreDeepAmber.copy(alpha = 0.75f), radius = strokePx * 0.9f, center = Offset(arcEndX, horizonY))

        // Radiant sun / moon orb positioned along exact cubic Bezier curve
        val t = sunProgress.coerceIn(0.06f, 0.94f)
        val invT = 1f - t
        val orbX = arcStartX + spanW * t
        val orbY = (invT * invT * invT * horizonY) +
            (3f * invT * invT * t * peakY) +
            (3f * invT * t * t * peakY) +
            (t * t * t * horizonY)
        val orbColor = if (isDaytime) SunCoreGold else MoonPearlCore

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orbColor.copy(alpha = 0.55f), Transparent),
                center = Offset(orbX, orbY),
                radius = strokePx * 5.2f
            ),
            radius = strokePx * 5.2f,
            center = Offset(orbX, orbY)
        )
        drawCircle(
            color = orbColor,
            radius = strokePx * 1.9f,
            center = Offset(orbX, orbY)
        )
        drawCircle(
            color = PureWhite,
            radius = strokePx * 0.95f,
            center = Offset(orbX, orbY)
        )
    }
}

@Composable
private fun WindCompassGauge(
    directionText: String,
    speedKmH: Int,
    degrees: Int = 0,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokePx = with(density) { BorderTokens.Subtle.toPx() }
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    val angleDeg = remember(directionText, degrees) {
        if (degrees > 0 || directionText.equals("N", ignoreCase = true)) {
            degrees - 90f
        } else {
            when (directionText.uppercase().trim()) {
                "N" -> -90f
                "NNE" -> -67.5f
                "NE" -> -45f
                "ENE" -> -22.5f
                "E" -> 0f
                "ESE" -> 22.5f
                "SE" -> 45f
                "SSE" -> 67.5f
                "S" -> 90f
                "SSW" -> 112.5f
                "SW" -> 135f
                "WSW" -> 157.5f
                "W" -> 180f
                "WNW" -> -157.5f
                "NW" -> -135f
                "NNW" -> -112.5f
                else -> -90f
            }
        }
    }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.5f
        val dialRadius = (size.height * 0.44f).coerceAtMost(size.width * 0.26f)
        val dialCenter = Offset(dialRadius + strokePx * 1.5f, cy)

        // Subtle recessed dial face
        drawCircle(
            color = TempBarTrackColor,
            radius = dialRadius,
            center = dialCenter
        )
        drawCircle(
            color = GlassCardBorderLight,
            radius = dialRadius,
            center = dialCenter,
            style = Stroke(width = strokePx * 0.85f)
        )

        // 8 precision compass rose tick marks (North tick highlighted in gold)
        for (i in 0 until 8) {
            val tickRad = Math.toRadians(i * 45.0 - 90.0)
            val isCardinal = i % 2 == 0
            val isNorth = i == 0
            val innerR = dialRadius * (if (isCardinal) 0.72f else 0.82f)
            val outerR = dialRadius * 0.96f
            drawLine(
                color = when {
                    isNorth -> SunCoreGold
                    isCardinal -> SkyTextSecondary.copy(alpha = 0.75f)
                    else -> SkyTextMuted.copy(alpha = 0.40f)
                },
                start = Offset(
                    dialCenter.x + cos(tickRad).toFloat() * innerR,
                    dialCenter.y + sin(tickRad).toFloat() * innerR
                ),
                end = Offset(
                    dialCenter.x + cos(tickRad).toFloat() * outerR,
                    dialCenter.y + sin(tickRad).toFloat() * outerR
                ),
                strokeWidth = if (isNorth) strokePx * 1.25f else strokePx * 0.75f,
                cap = StrokeCap.Round
            )
        }

        // Directional pointer shaft + crisp arrowhead
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val tip = Offset(dialCenter.x + cosA * (dialRadius * 0.72f), dialCenter.y + sinA * (dialRadius * 0.72f))
        val tail = Offset(dialCenter.x - cosA * (dialRadius * 0.42f), dialCenter.y - sinA * (dialRadius * 0.42f))
        val needleColor = if (speedKmH > 40) Red else TempBarCoolColor

        drawLine(
            color = SkyTextPrimary.copy(alpha = 0.85f),
            start = tail,
            end = tip,
            strokeWidth = strokePx * 1.1f,
            cap = StrokeCap.Round
        )

        // Small triangular arrowhead at tip
        val perpX = -sinA
        val perpY = cosA
        val headBaseX = dialCenter.x + cosA * (dialRadius * 0.42f)
        val headBaseY = dialCenter.y + sinA * (dialRadius * 0.42f)
        val headSpread = dialRadius * 0.19f
        val arrowHead = Path().apply {
            moveTo(tip.x, tip.y)
            lineTo(headBaseX + perpX * headSpread, headBaseY + perpY * headSpread)
            lineTo(headBaseX - perpX * headSpread, headBaseY - perpY * headSpread)
            close()
        }
        drawPath(path = arrowHead, color = needleColor)
        drawCircle(color = PureWhite, radius = strokePx * 0.9f, center = dialCenter)

        // Speed intensity bar to the right of the compass
        val trackStartX = dialCenter.x + dialRadius + strokePx * 4.5f
        val trackWidth = (size.width - trackStartX).coerceAtLeast(0f)
        val ratio = (speedKmH / 60f).coerceIn(0.10f, 1f)

        drawRoundRect(
            color = TempBarTrackColor,
            topLeft = Offset(trackStartX, cy - barHeightPx * 0.5f),
            size = Size(trackWidth, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = if (speedKmH > 40) listOf(UvHighOrange, Red) else listOf(TempBarCoolColor.copy(alpha = 0.65f), RainDropCyan),
                startX = trackStartX,
                endX = trackStartX + trackWidth
            ),
            topLeft = Offset(trackStartX, cy - barHeightPx * 0.5f),
            size = Size(trackWidth * ratio, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )
    }
}

/**
 * True 220-degree curved aneroid barometer arc dial with fine graduation ticks and glowing pointer.
 */
@Composable
private fun BarometerArcGauge(
    pressureHpa: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val strokePx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val normalized = ((pressureHpa - 980f) / 60f).coerceIn(0.05f, 0.95f)
        val startAngle = 160f
        val sweepTotal = 220f

        val radius = minOf(size.width * 0.32f, size.height * 0.56f)
        val center = Offset(size.width * 0.5f, size.height * 0.78f)
        val arcTopLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2f, radius * 2f)

        // Outer fine graduation ticks around the dial
        val tickCount = 11
        for (i in 0 until tickCount) {
            val frac = i.toFloat() / (tickCount - 1)
            val deg = startAngle + frac * sweepTotal
            val rad = deg * (PI / 180.0)
            val isMajor = i == 0 || i == 5 || i == 10
            val innerR = radius + strokePx * 0.75f
            val outerR = radius + strokePx * (if (isMajor) 1.75f else 1.25f)
            drawLine(
                color = if (isMajor) SkyTextSecondary.copy(alpha = 0.75f) else SkyTextMuted.copy(alpha = 0.35f),
                start = Offset(center.x + cos(rad).toFloat() * innerR, center.y + sin(rad).toFloat() * innerR),
                end = Offset(center.x + cos(rad).toFloat() * outerR, center.y + sin(rad).toFloat() * outerR),
                strokeWidth = if (isMajor) 2.2f else 1.4f,
                cap = StrokeCap.Round
            )
        }

        // Recessed dial arc track
        drawArc(
            color = TempBarTrackColor,
            startAngle = startAngle,
            sweepAngle = sweepTotal,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Active luminous sweep arc
        drawArc(
            brush = Brush.horizontalGradient(
                colors = listOf(TempBarCoolColor, TempBarMildColor, SunCoreGold),
                startX = center.x - radius,
                endX = center.x + radius
            ),
            startAngle = startAngle,
            sweepAngle = sweepTotal * normalized,
            useCenter = false,
            topLeft = arcTopLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Precision needle & glowing tip orb on the arc
        val pointerDeg = startAngle + sweepTotal * normalized
        val pointerRad = pointerDeg * (PI / 180.0)
        val tipX = center.x + cos(pointerRad).toFloat() * radius
        val tipY = center.y + sin(pointerRad).toFloat() * radius
        val needleBaseX = center.x + cos(pointerRad).toFloat() * (radius * 0.38f)
        val needleBaseY = center.y + sin(pointerRad).toFloat() * (radius * 0.38f)

        drawLine(
            color = SkyTextPrimary.copy(alpha = 0.75f),
            start = Offset(needleBaseX, needleBaseY),
            end = Offset(tipX, tipY),
            strokeWidth = 2.4f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Black.copy(alpha = 0.50f),
            radius = strokePx * 1.15f,
            center = Offset(tipX, tipY)
        )
        drawCircle(
            color = PureWhite,
            radius = strokePx * 0.78f,
            center = Offset(tipX, tipY)
        )
    }
}

@Composable
fun DotMatrixGraph(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    unitSuffix: String = ""
) {
    if (data.isEmpty()) return
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { BorderTokens.Thick.toPx() }
    val padPx = with(density) { SpacingTokens.Small.toPx() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val maxVal = data.maxOrNull() ?: 1f
        val minVal = data.minOrNull() ?: 0f
        val range = if (maxVal == minVal) 1f else (maxVal - minVal)

        // Subtle dashed horizontal gridlines for visual scale reference
        val dashEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
        listOf(0.20f, 0.55f, 0.90f).forEach { frac ->
            val gy = height * frac
            drawLine(
                color = GlassCardDividerColor,
                start = Offset(padPx, gy),
                end = Offset(width - padPx, gy),
                strokeWidth = 1f,
                pathEffect = dashEffect
            )
        }

        val drawableWidth = (width - padPx * 2f).coerceAtLeast(1f)
        val drawableHeight = (height - padPx * 2.4f).coerceAtLeast(1f)
        val pointSpacing = if (data.size > 1) drawableWidth / (data.size - 1) else 0f

        val points = data.mapIndexed { index, value ->
            val x = padPx + index * pointSpacing
            val normalizedY = if (maxVal == minVal) {
                if (maxVal == 0f) 0.88f else 0.5f
            } else {
                1f - ((value - minVal) / range)
            }
            val y = padPx * 1.2f + normalizedY * drawableHeight
            Offset(x, y)
        }

        val strokePath = Path()
        val fillPath = Path()

        points.forEachIndexed { index, pt ->
            if (index == 0) {
                strokePath.moveTo(pt.x, pt.y)
                fillPath.moveTo(pt.x, height)
                fillPath.lineTo(pt.x, pt.y)
            } else {
                val prev = points[index - 1]
                val ctrlX = (prev.x + pt.x) * 0.5f
                strokePath.cubicTo(ctrlX, prev.y, ctrlX, pt.y, pt.x, pt.y)
                fillPath.cubicTo(ctrlX, prev.y, ctrlX, pt.y, pt.x, pt.y)
            }
        }

        fillPath.lineTo(points.last().x, height)
        fillPath.close()

        if (maxVal > 0f) {
            drawPath(
                path = fillPath,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        color.copy(alpha = 0.34f),
                        Transparent
                    ),
                    startY = 0f,
                    endY = height
                )
            )
        }

        drawPath(
            path = strokePath,
            color = color,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round)
        )

        // Glowing peak anchor dot + vertical drop hairline
        val peakIdx = data.indices.maxByOrNull { data[it] } ?: 0
        val peakPt = points.getOrNull(peakIdx)
        if (peakPt != null && maxVal > 0f) {
            drawLine(
                color = color.copy(alpha = 0.35f),
                start = Offset(peakPt.x, peakPt.y),
                end = Offset(peakPt.x, height),
                strokeWidth = 1.2f,
                pathEffect = dashEffect
            )
            drawCircle(
                color = color.copy(alpha = 0.35f),
                radius = strokeWidthPx * 3.0f,
                center = peakPt
            )
            drawCircle(
                color = PureWhite,
                radius = strokeWidthPx * 1.35f,
                center = peakPt
            )
        }
    }
}
