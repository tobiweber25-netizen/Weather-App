package com.google.weatherapp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Eco
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.theme.*
import kotlin.math.cos
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.Base)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        // Row 1: Air Quality (AQI) & Visibility / Comfort
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val isAqiAvailable = telemetry.aqiCategory != "Unavailable" && telemetry.aqiCategory != "N/A"
            val aqiDisplay = if (isAqiAvailable) "${telemetry.aqiValue} • ${telemetry.aqiCategory}" else "Unavailable"
            val aqiSubtitle = if (isAqiAvailable && telemetry.pm25 > 0.0) "PM2.5: ${telemetry.pm25} µg/m³" else "Sensor feed unavailable"

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

            BentoGlassTile(
                title = "VISIBILITY & COMFORT",
                value = "${telemetry.visibilityKm} km",
                subtitle = telemetry.feelsLikeReason,
                icon = Icons.Default.Visibility,
                onClick = null,
                gaugeContent = {
                    LinearFillMicroGauge(
                        progress = (telemetry.visibilityKm.toFloat() / 16f).coerceIn(0.15f, 1f),
                        activeColor = TempBarMildColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Row 2: Wind & Precipitation (Expandable 24h Sparklines)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val windSubtitle = if (telemetry.windGustKmH > telemetry.windSpeedKmH) {
                "${telemetry.windDirection} • Gusts ${telemetry.windGustKmH} km/h"
            } else {
                "Direction: ${telemetry.windDirection}"
            }

            BentoGlassTile(
                title = if (isSevereWind) "HIGH WIND" else "WIND",
                value = "${telemetry.windSpeedKmH} km/h",
                subtitle = windSubtitle,
                icon = Icons.Default.Air,
                isAlert = isSevereWind,
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
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            BentoGlassTile(
                title = "PRECIPITATION",
                value = "${telemetry.precipitationMm} mm",
                subtitle = if (telemetry.precipitationMm > 0.0) "24h total accumulation" else "Dry conditions",
                icon = Icons.Default.WaterDrop,
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.PRECIPITATION) null else TelemetryMetricType.PRECIPITATION
                    }
                },
                gaugeContent = {
                    LinearFillMicroGauge(
                        progress = (telemetry.precipitationMm.toFloat() / 20f).coerceIn(0f, 1f),
                        activeColor = RainDropCyan,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Expandable Sparkline Drawer for Wind or Precipitation
        AnimatedVisibility(
            visible = expandedMetric == TelemetryMetricType.WIND || expandedMetric == TelemetryMetricType.PRECIPITATION,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            val isWind = expandedMetric == TelemetryMetricType.WIND
            val chartData = remember(hourlyList, isWind) {
                if (isWind) hourlyList.map { it.windSpeedKmH.toFloat() }
                else hourlyList.map { it.precipitationMm.toFloat() }
            }
            ExpandedSparklineGlassCard(
                title = if (isWind) "24-HOUR WIND TREND (KM/H)" else "24-HOUR PRECIPITATION TREND (MM)",
                data = chartData,
                accentColor = if (isWind) SunCoreGold else RainDropCyan
            )
        }

        // Row 3: UV Index & Humidity
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val uvSubtitle = if (telemetry.maxUvIndex > telemetry.uvIndex) {
                "${telemetry.uvCategory} now • Peak ${telemetry.maxUvIndex} today"
            } else {
                "${telemetry.uvCategory} for the day"
            }

            BentoGlassTile(
                title = "UV INDEX",
                value = "${telemetry.uvIndex}",
                subtitle = uvSubtitle,
                icon = Icons.Default.WbSunny,
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.UV) null else TelemetryMetricType.UV
                    }
                },
                gaugeContent = {
                    UvSpectrumMicroGauge(
                        uvIndex = telemetry.uvIndex,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            BentoGlassTile(
                title = "HUMIDITY",
                value = "${telemetry.humidityPercent}%",
                subtitle = "Dew point is ${telemetry.dewPointDegrees}°",
                icon = Icons.Default.Opacity,
                onClick = {
                    if (hourlyList.isNotEmpty()) {
                        expandedMetric = if (expandedMetric == TelemetryMetricType.HUMIDITY) null else TelemetryMetricType.HUMIDITY
                    }
                },
                gaugeContent = {
                    LinearFillMicroGauge(
                        progress = (telemetry.humidityPercent / 100f).coerceIn(0f, 1f),
                        activeColor = TempBarCoolColor,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Expandable Sparkline Drawer for UV or Humidity
        AnimatedVisibility(
            visible = expandedMetric == TelemetryMetricType.UV || expandedMetric == TelemetryMetricType.HUMIDITY,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            val isUv = expandedMetric == TelemetryMetricType.UV
            val chartData = remember(hourlyList, isUv) {
                if (isUv) hourlyList.map { it.uvIndex.toFloat() }
                else hourlyList.map { it.humidityPercent.toFloat() }
            }
            ExpandedSparklineGlassCard(
                title = if (isUv) "24-HOUR UV EXPOSURE CURVE" else "24-HOUR RELATIVE HUMIDITY (%)",
                data = chartData,
                accentColor = if (isUv) SunCoreGold else TempBarCoolColor
            )
        }

        // Row 4: Real-Time Solar Horizon Arc & Atmospheric Pressure
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
        ) {
            val sunTitle = if (isDaytime) "SUNSET" else "SUNRISE"
            val sunValue = if (isDaytime) telemetry.sunsetTime else telemetry.sunriseTime
            val sunSubtitle = if (isDaytime) "Sunrise was ${telemetry.sunriseTime}" else "Sunset was ${telemetry.sunsetTime}"
            val sunIcon = if (isDaytime) Icons.Default.WbSunny else Icons.Default.WbTwilight

            BentoGlassTile(
                title = sunTitle,
                value = sunValue,
                subtitle = sunSubtitle,
                icon = sunIcon,
                onClick = null,
                gaugeContent = {
                    SolarHorizonArcGauge(
                        isDaytime = isDaytime,
                        sunProgress = telemetry.sunProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )

            BentoGlassTile(
                title = "PRESSURE",
                value = "${telemetry.pressureHpa}",
                subtitle = "hPa • Sea level",
                icon = Icons.Default.Compress,
                onClick = null,
                gaugeContent = {
                    BarometerArcGauge(
                        pressureHpa = telemetry.pressureHpa,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(ComponentTokens.BentoGaugeHeight)
                    )
                },
                modifier = Modifier.weight(1f)
            )
        }
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
    isAlert: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = when {
        isAlert -> AlertSevereContainer
        isDark -> GlassCardBackgroundDark
        else -> GlassCardBackgroundLight
    }
    val borderColor = when {
        isAlert -> AlertSevereBorder
        isDark -> GlassCardBorderDark
        else -> GlassCardBorderLight
    }

    val tileModifier = modifier
        .heightIn(min = ComponentTokens.BentoCardMinHeight)
        .clip(RoundedCornerShape(CornerTokens.Large))
        .background(cardBg)
        .border(
            width = BorderTokens.Hairline,
            color = borderColor,
            shape = RoundedCornerShape(CornerTokens.Large)
        )
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(SpacingTokens.Base)

    Column(
        modifier = tileModifier,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.ExtraSmall)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = SkyTextSecondary,
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
    data: List<Float>,
    accentColor: Color
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) GlassCardElevatedDark else GlassCardElevatedLight
    val borderColor = if (isDark) GlassCardBorderDark else GlassCardBorderLight

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CornerTokens.Large))
            .background(cardBg)
            .border(
                width = BorderTokens.Hairline,
                color = borderColor,
                shape = RoundedCornerShape(CornerTokens.Large)
            )
            .padding(SpacingTokens.Base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.Small)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = SkyTextSecondary
        )
        DotMatrixGraph(
            data = data,
            color = accentColor,
            modifier = Modifier
                .fillMaxWidth()
                .height(ComponentTokens.BentoSparklineHeight)
        )
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
        val cy = size.height * 0.5f
        if (!isAvailable) {
            drawRoundRect(
                color = SkyTextMuted.copy(alpha = 0.2f),
                topLeft = Offset(0f, cy - barHeightPx * 0.5f),
                size = Size(size.width, barHeightPx),
                cornerRadius = CornerRadius(barHeightPx, barHeightPx)
            )
            return@Canvas
        }

        val ratio = (aqiValue / 200f).coerceIn(0f, 1f)

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    UvLowGreen,
                    UvModerateYellow,
                    UvHighOrange,
                    UvVeryHighRed,
                    UvExtremeViolet
                )
            ),
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        val thumbX = (size.width * ratio).coerceIn(barHeightPx, size.width - barHeightPx)
        drawCircle(
            color = Black.copy(alpha = 0.45f),
            radius = barHeightPx * 1.05f,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            color = PureWhite,
            radius = barHeightPx * 0.78f,
            center = Offset(thumbX, cy)
        )
    }
}

@Composable
private fun UvSpectrumMicroGauge(
    uvIndex: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.5f
        val ratio = (uvIndex / 11f).coerceIn(0f, 1f)

        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    UvLowGreen,
                    UvModerateYellow,
                    UvHighOrange,
                    UvVeryHighRed,
                    UvExtremeViolet
                )
            ),
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        val thumbX = (size.width * ratio).coerceIn(barHeightPx, size.width - barHeightPx)
        drawCircle(
            color = Black.copy(alpha = 0.45f),
            radius = barHeightPx * 1.05f,
            center = Offset(thumbX, cy)
        )
        drawCircle(
            color = PureWhite,
            radius = barHeightPx * 0.78f,
            center = Offset(thumbX, cy)
        )
    }
}

@Composable
private fun LinearFillMicroGauge(
    progress: Float,
    activeColor: Color,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.5f

        drawRoundRect(
            color = TempBarTrackColor,
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        if (progress > 0f) {
            val clamped = progress.coerceIn(0.02f, 1f)
            drawRoundRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(activeColor.copy(alpha = 0.65f), activeColor)
                ),
                topLeft = Offset(0f, cy - barHeightPx * 0.5f),
                size = Size(size.width * clamped, barHeightPx),
                cornerRadius = CornerRadius(barHeightPx, barHeightPx)
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
        val horizonY = h * 0.74f

        drawLine(
            color = GlassCardDividerColor,
            start = Offset(0f, horizonY),
            end = Offset(w, horizonY),
            strokeWidth = hairlinePx
        )

        val arcPath = Path().apply {
            moveTo(0f, horizonY)
            cubicTo(
                w * 0.25f, h * 0.05f,
                w * 0.75f, h * 0.05f,
                w, horizonY
            )
        }

        val arcColors = if (isDaytime) {
            listOf(
                SkyTextMuted,
                SunCoreGold,
                SkyTextMuted
            )
        } else {
            listOf(
                SkyTextMuted,
                MoonPearlCore.copy(alpha = 0.75f),
                SkyTextMuted
            )
        }

        drawPath(
            path = arcPath,
            brush = Brush.horizontalGradient(colors = arcColors),
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Exact Bezier interpolation using real-time sunProgress (0..1)
        val t = sunProgress.coerceIn(0.08f, 0.92f)
        val invT = 1f - t
        val sunX = w * t
        val sunY = (invT * invT * invT * horizonY) +
            (3f * invT * invT * t * (h * 0.05f)) +
            (3f * invT * t * t * (h * 0.05f)) +
            (t * t * t * horizonY)
        val orbColor = if (isDaytime) SunCoreGold else MoonPearlCore

        drawCircle(
            color = orbColor.copy(alpha = 0.35f),
            radius = strokePx * 3.2f,
            center = Offset(sunX, sunY)
        )
        drawCircle(
            color = orbColor,
            radius = strokePx * 1.6f,
            center = Offset(sunX, sunY)
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
        val dialRadius = size.height * 0.42f
        val dialCenter = Offset(dialRadius + strokePx, cy)

        drawCircle(
            color = GlassCardBorderLight,
            radius = dialRadius,
            center = dialCenter,
            style = Stroke(width = strokePx)
        )

        val rad = Math.toRadians(angleDeg.toDouble())
        val tipX = dialCenter.x + cos(rad).toFloat() * (dialRadius * 0.72f)
        val tipY = dialCenter.y + sin(rad).toFloat() * (dialRadius * 0.72f)
        drawLine(
            color = SkyTextPrimary,
            start = dialCenter,
            end = Offset(tipX, tipY),
            strokeWidth = strokePx * 1.2f,
            cap = StrokeCap.Round
        )

        val trackStartX = dialCenter.x + dialRadius + strokePx * 4f
        val trackWidth = (size.width - trackStartX).coerceAtLeast(0f)
        val ratio = (speedKmH / 60f).coerceIn(0.12f, 1f)

        drawRoundRect(
            color = TempBarTrackColor,
            topLeft = Offset(trackStartX, cy - barHeightPx * 0.5f),
            size = Size(trackWidth, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )
        drawRoundRect(
            color = if (speedKmH > 40) Red else TempBarCoolColor,
            topLeft = Offset(trackStartX, cy - barHeightPx * 0.5f),
            size = Size(trackWidth * ratio, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )
    }
}

@Composable
private fun BarometerArcGauge(
    pressureHpa: Int,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val barHeightPx = with(density) { ComponentTokens.BentoGaugeStroke.toPx() }

    Canvas(modifier = modifier) {
        val cy = size.height * 0.5f
        val normalized = ((pressureHpa - 980f) / 60f).coerceIn(0.1f, 0.9f)

        drawRoundRect(
            color = TempBarTrackColor,
            topLeft = Offset(0f, cy - barHeightPx * 0.5f),
            size = Size(size.width, barHeightPx),
            cornerRadius = CornerRadius(barHeightPx, barHeightPx)
        )

        val markerX = size.width * normalized
        drawCircle(
            color = SkyTextPrimary,
            radius = barHeightPx * 0.9f,
            center = Offset(markerX, cy)
        )
    }
}

@Composable
fun DotMatrixGraph(
    data: List<Float>,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (data.isEmpty()) return
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { BorderTokens.Thick.toPx() }
    val padPx = with(density) { SpacingTokens.ExtraSmall.toPx() }

    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val maxVal = data.maxOrNull() ?: 1f
        val minVal = data.minOrNull() ?: 0f
        val range = if (maxVal == minVal) 1f else (maxVal - minVal)

        val drawableWidth = (width - padPx * 2f).coerceAtLeast(1f)
        val drawableHeight = (height - padPx * 2f).coerceAtLeast(1f)
        val pointSpacing = if (data.size > 1) drawableWidth / (data.size - 1) else 0f

        val points = data.mapIndexed { index, value ->
            val x = padPx + index * pointSpacing
            val normalizedY = if (maxVal == minVal) {
                if (maxVal == 0f) 1f else 0.5f
            } else {
                1f - ((value - minVal) / range)
            }
            val y = padPx + normalizedY * drawableHeight
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
                        color.copy(alpha = 0.36f),
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
    }
}
