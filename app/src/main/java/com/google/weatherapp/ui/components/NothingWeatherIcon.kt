package com.google.weatherapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Multi-layer dimensional weather iconography inspired by Apple SF Symbols.
 * Retains the NothingWeatherIcon entry point for seamless drop-in compatibility.
 */
@Composable
fun NothingWeatherIcon(
    effectType: WeatherEffectType,
    modifier: Modifier = Modifier
) {
    if (effectType == WeatherEffectType.OFF || effectType == WeatherEffectType.AUTO) return

    val infiniteTransition = rememberInfiniteTransition(label = "dimensional_icon_anim")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 36000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "icon_phase"
    )

    val foregroundCloudPath = remember { Path() }
    val backgroundCloudPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val sz = minOf(width, height)
        val t = phase
        val breathe = sin(t * Math.PI * 2).toFloat()

        when (effectType) {
            WeatherEffectType.CLEAR_DAY -> {
                val cx = width * 0.5f
                val cy = height * 0.5f
                val sunRadius = sz * 0.24f

                // 1. Soft outer ambient solar corona glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SunCoreGold.copy(alpha = 0.42f + 0.08f * breathe),
                            SunCoronaAmber.copy(alpha = 0.14f),
                            Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = sz * 0.48f
                    ),
                    radius = sz * 0.48f,
                    center = Offset(cx, cy)
                )

                // 2. Smooth rotating tapered sun rays
                val rayCount = 8
                val spinOffset = t * Math.PI * 2.0
                val rayStroke = maxOf(sz * 0.048f, 2.5f)
                for (i in 0 until rayCount) {
                    val angle = (i * Math.PI * 2.0 / rayCount) + spinOffset
                    val startR = sunRadius + sz * 0.07f
                    val endR = sunRadius + sz * 0.17f + (if (i % 2 == 0) sz * 0.025f * breathe else 0f)
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    drawLine(
                        color = SunRayWarmGold,
                        start = Offset(cx + cosA * startR, cy + sinA * startR),
                        end = Offset(cx + cosA * endR, cy + sinA * endR),
                        strokeWidth = rayStroke,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Dimensional 3D golden sun orb
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            PureWhite,
                            SunCoreGold,
                            SunCoronaAmber
                        ),
                        center = Offset(cx - sunRadius * 0.25f, cy - sunRadius * 0.25f),
                        radius = sunRadius * 1.35f
                    ),
                    radius = sunRadius,
                    center = Offset(cx, cy)
                )
            }

            WeatherEffectType.CLEAR_NIGHT -> {
                val cx = width * 0.48f
                val cy = height * 0.52f
                val r = sz * 0.25f

                // 1. Ambient lunar halo
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            MoonPearlCore.copy(alpha = 0.28f),
                            Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = sz * 0.45f
                    ),
                    radius = sz * 0.45f,
                    center = Offset(cx, cy)
                )

                // 2. Twinkling 4-point star sparkles
                val starOffsets = listOf(
                    Offset(cx - sz * 0.28f, cy - sz * 0.22f) to 0.055f,
                    Offset(cx + sz * 0.28f, cy - sz * 0.26f) to 0.045f,
                    Offset(cx - sz * 0.22f, cy + sz * 0.24f) to 0.038f
                )
                starOffsets.forEachIndexed { idx, (pos, scaleFactor) ->
                    val twinkle = (0.5f + 0.5f * sin(t * Math.PI * 4 + idx * 1.7)).toFloat()
                    val arm = sz * scaleFactor * (0.75f + 0.25f * twinkle)
                    val strokeW = maxOf(sz * 0.028f, 1.5f)
                    drawLine(
                        color = StarTwinkleColor.copy(alpha = 0.45f + 0.55f * twinkle),
                        start = Offset(pos.x - arm, pos.y),
                        end = Offset(pos.x + arm, pos.y),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = StarTwinkleColor.copy(alpha = 0.45f + 0.55f * twinkle),
                        start = Offset(pos.x, pos.y - arm),
                        end = Offset(pos.x, pos.y + arm),
                        strokeWidth = strokeW,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Dimensional pearl crescent moon via boolean Path difference
                val outerCircle = Path().apply {
                    addOval(Rect(cx - r, cy - r, cx + r, cy + r))
                }
                val cutoutRadius = r * 0.82f
                val cutoutCenter = Offset(cx - r * 0.42f, cy - r * 0.28f)
                val innerCutout = Path().apply {
                    addOval(
                        Rect(
                            cutoutCenter.x - cutoutRadius,
                            cutoutCenter.y - cutoutRadius,
                            cutoutCenter.x + cutoutRadius,
                            cutoutCenter.y + cutoutRadius
                        )
                    )
                }
                val crescentPath = Path().apply {
                    op(outerCircle, innerCutout, PathOperation.Difference)
                }

                drawPath(
                    path = crescentPath,
                    brush = Brush.linearGradient(
                        colors = listOf(PureWhite, MoonPearlCore, MoonCraterShadow),
                        start = Offset(cx, cy - r),
                        end = Offset(cx + r, cy + r)
                    )
                )
            }

            WeatherEffectType.CLOUDY -> {
                val drift = breathe * sz * 0.025f
                // 1. Tucked warm sun peek or rear volumetric shadow cloud
                buildVolumetricCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.57f - drift * 0.6f,
                    cy = height * 0.44f,
                    sz = sz,
                    scale = 0.82f
                )
                drawPath(
                    path = backgroundCloudPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            CloudMidSilver.copy(alpha = 0.78f),
                            CloudShadowSlate.copy(alpha = 0.68f)
                        ),
                        startY = height * 0.22f,
                        endY = height * 0.62f
                    )
                )

                // 2. Foreground frosted pearl cloud
                buildVolumetricCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.47f + drift,
                    cy = height * 0.55f,
                    sz = sz,
                    scale = 1.02f
                )
                drawDimensionalForegroundCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.30f,
                    bottomY = height * 0.72f
                )
            }

            WeatherEffectType.RAIN -> {
                val drift = breathe * sz * 0.018f
                // 1. Rear storm-tinted cloud layer
                buildVolumetricCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.56f - drift,
                    cy = height * 0.36f,
                    sz = sz,
                    scale = 0.78f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudShadowSlate.copy(alpha = 0.72f)
                )

                // 2. Angled cyan rain droplets falling behind the foreground cloud rim
                val dropStroke = maxOf(sz * 0.044f, 2.2f)
                for (i in -1..1) {
                    val dropPhase = (t * 3f + (i + 1) * 0.33f) % 1f
                    val baseX = width * 0.5f + i * sz * 0.14f - dropPhase * sz * 0.07f
                    val startY = height * 0.54f + dropPhase * sz * 0.22f
                    val endY = startY + sz * 0.13f
                    val alpha = (1f - dropPhase).coerceIn(0.2f, 0.95f)

                    drawLine(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                RainDropCyan.copy(alpha = alpha * 0.4f),
                                RainDropDeepBlue.copy(alpha = alpha)
                            ),
                            startY = startY,
                            endY = endY
                        ),
                        start = Offset(baseX, startY),
                        end = Offset(baseX - sz * 0.035f, endY),
                        strokeWidth = dropStroke,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Foreground frosted cloud
                buildVolumetricCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.48f + drift,
                    cy = height * 0.43f,
                    sz = sz,
                    scale = 0.98f
                )
                drawDimensionalForegroundCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.20f,
                    bottomY = height * 0.60f
                )
            }

            WeatherEffectType.THUNDERSTORM -> {
                val drift = breathe * sz * 0.015f
                // 1. Rear dark storm anvil cloud
                buildVolumetricCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.56f - drift,
                    cy = height * 0.35f,
                    sz = sz,
                    scale = 0.80f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudStormDark.copy(alpha = 0.85f)
                )

                // 2. Flanking rain droplets
                val dropStroke = maxOf(sz * 0.036f, 1.8f)
                for (side in listOf(-1, 1)) {
                    val dropPhase = (t * 4f + (if (side < 0) 0.2f else 0.7f)) % 1f
                    val x = width * 0.5f + side * sz * 0.20f - dropPhase * sz * 0.05f
                    val y = height * 0.55f + dropPhase * sz * 0.22f
                    drawLine(
                        color = RainDropCyan.copy(alpha = (1f - dropPhase) * 0.75f),
                        start = Offset(x, y),
                        end = Offset(x - sz * 0.03f, y + sz * 0.11f),
                        strokeWidth = dropStroke,
                        cap = StrokeCap.Round
                    )
                }

                // 3. Glowing amber-gold lightning bolt emerging from cloud base
                val lx = width * 0.50f
                val ly = height * 0.48f
                val boltPath = Path().apply {
                    moveTo(lx + sz * 0.03f, ly)
                    lineTo(lx - sz * 0.09f, ly + sz * 0.18f)
                    lineTo(lx + sz * 0.01f, ly + sz * 0.18f)
                    lineTo(lx - sz * 0.05f, ly + sz * 0.37f)
                    lineTo(lx + sz * 0.11f, ly + sz * 0.13f)
                    lineTo(lx + sz * 0.01f, ly + sz * 0.13f)
                    close()
                }

                val pulseAlpha = 0.78f + 0.22f * sin(t * Math.PI * 6).toFloat()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            LightningBoltGlowColor.copy(alpha = 0.45f * pulseAlpha),
                            Transparent
                        ),
                        center = Offset(lx, ly + sz * 0.18f),
                        radius = sz * 0.26f
                    ),
                    radius = sz * 0.26f,
                    center = Offset(lx, ly + sz * 0.18f)
                )
                drawPath(
                    path = boltPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(PureWhite, SunCoreGold, SunCoronaAmber),
                        startY = ly,
                        endY = ly + sz * 0.37f
                    )
                )

                // 4. Foreground slate-tinted cloud
                buildVolumetricCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.48f + drift,
                    cy = height * 0.41f,
                    sz = sz,
                    scale = 0.96f
                )
                drawPath(
                    path = foregroundCloudPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            CloudHighlightWhite,
                            CloudMidSilver,
                            CloudShadowSlate
                        ),
                        startY = height * 0.18f,
                        endY = height * 0.58f
                    )
                )
            }

            WeatherEffectType.SNOW -> {
                val drift = breathe * sz * 0.018f
                // 1. Rear frosted cloud
                buildVolumetricCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.56f - drift,
                    cy = height * 0.36f,
                    sz = sz,
                    scale = 0.78f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudMidSilver.copy(alpha = 0.72f)
                )

                // 2. Drifting 6-point crystalline snowflakes
                val flakeRadius = sz * 0.048f
                val flakeStroke = maxOf(sz * 0.026f, 1.5f)
                for (i in -1..1) {
                    val fallProgress = (t * 2f + (i + 1) * 0.33f) % 1f
                    val cxFlake = width * 0.5f + i * sz * 0.15f + sin((fallProgress + i) * Math.PI * 2).toFloat() * sz * 0.025f
                    val cyFlake = height * 0.56f + fallProgress * sz * 0.24f
                    val alpha = (1f - fallProgress * 0.7f).coerceIn(0.25f, 1f)
                    val baseAngle = t * Math.PI * 2

                    for (axis in 0 until 3) {
                        val angle = baseAngle + axis * (Math.PI / 3.0)
                        val dx = cos(angle).toFloat() * flakeRadius
                        val dy = sin(angle).toFloat() * flakeRadius
                        drawLine(
                            color = SnowflakeCoreColor.copy(alpha = alpha),
                            start = Offset(cxFlake - dx, cyFlake - dy),
                            end = Offset(cxFlake + dx, cyFlake + dy),
                            strokeWidth = flakeStroke,
                            cap = StrokeCap.Round
                        )
                    }
                }

                // 3. Foreground frosted cloud
                buildVolumetricCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.48f + drift,
                    cy = height * 0.43f,
                    sz = sz,
                    scale = 0.96f
                )
                drawDimensionalForegroundCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.20f,
                    bottomY = height * 0.58f
                )
            }

            else -> {}
        }
    }
}

private fun DrawScope.drawDimensionalForegroundCloud(
    cloudPath: Path,
    topY: Float,
    bottomY: Float
) {
    drawPath(
        path = cloudPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                PureWhite,
                CloudHighlightWhite,
                CloudMidSilver
            ),
            startY = topY,
            endY = bottomY
        )
    )
    drawPath(
        path = cloudPath,
        color = PureWhite.copy(alpha = 0.55f),
        style = Stroke(width = maxOf(size.minDimension * 0.018f, 1f), join = StrokeJoin.Round)
    )
}

private fun buildVolumetricCloudPath(
    target: Path,
    cx: Float,
    cy: Float,
    sz: Float,
    scale: Float
) {
    val bottomY = cy + sz * 0.11f * scale
    val rMain = sz * 0.17f * scale
    val rLeft = sz * 0.125f * scale
    val rRight = sz * 0.135f * scale

    val mainLob = Path().apply {
        addOval(Rect(cx - rMain * 0.95f, bottomY - rMain * 2.05f, cx + rMain * 1.05f, bottomY))
    }
    val leftLob = Path().apply {
        addOval(Rect(cx - rMain - rLeft * 0.95f, bottomY - rLeft * 1.95f, cx - rMain + rLeft * 1.05f, bottomY))
    }
    val rightLob = Path().apply {
        addOval(Rect(cx + rMain - rRight * 0.95f, bottomY - rRight * 1.95f, cx + rMain + rRight * 1.05f, bottomY))
    }
    val baseFill = Path().apply {
        addRect(Rect(cx - rMain, bottomY - rLeft * 1.3f, cx + rMain, bottomY))
    }

    target.reset()
    target.op(mainLob, leftLob, PathOperation.Union)
    target.op(target, rightLob, PathOperation.Union)
    target.op(target, baseFill, PathOperation.Union)
}
