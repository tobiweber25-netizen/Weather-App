package com.google.weatherapp.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import kotlin.math.sin

/**
 * Full 6-condition parallax atmospheric canvas inspired by Apple Weather & Mercury Weather.
 * Renders depth-layered rain streaks with rising mist, bokeh snow with sine flutter,
 * twinkling starfields with shooting stars, solar corona rays, volumetric cloud mist,
 * and sheet-lightning thunderstorm flashes.
 */
@Composable
fun WeatherEffectOverlay(
    effectType: WeatherEffectType,
    modifier: Modifier = Modifier,
    intensity: Float = 1.0f
) {
    if (effectType == WeatherEffectType.OFF || effectType == WeatherEffectType.AUTO) return

    val infiniteTransition = rememberInfiniteTransition(label = "atmospheric_canvas_transition")
    val needsFastPhase = effectType == WeatherEffectType.RAIN ||
        effectType == WeatherEffectType.THUNDERSTORM ||
        effectType == WeatherEffectType.SNOW

    // Hold State objects and read .value strictly inside Canvas draw lambda -> ZERO recompositions/sec!
    val fastPhaseState = if (needsFastPhase) {
        infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1800, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "fast_weather_phase"
        )
    } else {
        null
    }

    val slowPhaseState = infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "slow_weather_phase"
    )

    val density = LocalDensity.current
    val strokeBgPx = with(density) { EffectTokens.RainStrokeBg.toPx() }
    val strokeMidPx = with(density) { EffectTokens.RainStrokeMid.toPx() }
    val strokeFgPx = with(density) { EffectTokens.RainStrokeFg.toPx() }
    val lenBgPx = with(density) { EffectTokens.RainLengthBgMax.toPx() }
    val lenMidPx = with(density) { EffectTokens.RainLengthMidMax.toPx() }
    val lenFgPx = with(density) { EffectTokens.RainLengthFgMax.toPx() }
    val rainHazeHeightPx = with(density) { EffectTokens.RainHazeHeight.toPx() }
    val snowMinPx = with(density) { EffectTokens.SnowflakeRadiusMin.toPx() }
    val snowMaxPx = with(density) { EffectTokens.SnowflakeRadiusMax.toPx() }
    val snowBokehPx = with(density) { EffectTokens.SnowflakeBokehRadius.toPx() }
    val starMinPx = with(density) { EffectTokens.StarRadiusMin.toPx() }
    val starMaxPx = with(density) { EffectTokens.StarRadiusMax.toPx() }
    val starSpikePx = with(density) { EffectTokens.StarSpikeLength.toPx() }
    val shootingTrailPx = with(density) { EffectTokens.ShootingStarTrailLength.toPx() }
    val sunCoronaPx = with(density) { EffectTokens.SunCoronaRadius.toPx() }
    val sunbeamPx = with(density) { EffectTokens.SunbeamRadius.toPx() }
    val moteMaxPx = with(density) { EffectTokens.SunMoteRadiusMax.toPx() }
    val cloudWidthPx = with(density) { EffectTokens.CloudBandWidth.toPx() }
    val cloudHeightPx = with(density) { EffectTokens.CloudBandHeight.toPx() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val slowPhase = slowPhaseState.value
        val fastPhase = fastPhaseState?.value ?: 0f
        when (effectType) {
            WeatherEffectType.CLEAR_DAY -> drawClearDayAtmosphere(
                slowPhase = slowPhase,
                sunCoronaPx = sunCoronaPx,
                sunbeamPx = sunbeamPx,
                moteMaxPx = moteMaxPx,
                intensity = intensity
            )

            WeatherEffectType.CLEAR_NIGHT -> drawClearNightAtmosphere(
                slowPhase = slowPhase,
                starMinPx = starMinPx,
                starMaxPx = starMaxPx,
                starSpikePx = starSpikePx,
                shootingTrailPx = shootingTrailPx,
                intensity = intensity
            )

            WeatherEffectType.CLOUDY -> drawCloudyMistAtmosphere(
                slowPhase = slowPhase,
                cloudWidthPx = cloudWidthPx,
                cloudHeightPx = cloudHeightPx,
                intensity = intensity
            )

            WeatherEffectType.RAIN -> drawParallaxRainAtmosphere(
                fastPhase = fastPhase,
                slowPhase = slowPhase,
                strokeBgPx = strokeBgPx,
                strokeMidPx = strokeMidPx,
                strokeFgPx = strokeFgPx,
                lenBgPx = lenBgPx,
                lenMidPx = lenMidPx,
                lenFgPx = lenFgPx,
                rainHazeHeightPx = rainHazeHeightPx,
                cloudWidthPx = cloudWidthPx,
                cloudHeightPx = cloudHeightPx,
                intensity = intensity
            )

            WeatherEffectType.THUNDERSTORM -> {
                drawParallaxRainAtmosphere(
                    fastPhase = fastPhase,
                    slowPhase = slowPhase,
                    strokeBgPx = strokeBgPx,
                    strokeMidPx = strokeMidPx,
                    strokeFgPx = strokeFgPx,
                    lenBgPx = lenBgPx * 1.15f,
                    lenMidPx = lenMidPx * 1.15f,
                    lenFgPx = lenFgPx * 1.15f,
                    rainHazeHeightPx = rainHazeHeightPx,
                    cloudWidthPx = cloudWidthPx,
                    cloudHeightPx = cloudHeightPx,
                    intensity = intensity * 1.15f
                )
                drawThunderstormLightning(
                    slowPhase = slowPhase,
                    intensity = intensity
                )
            }

            WeatherEffectType.SNOW -> drawBokehSnowAtmosphere(
                fastPhase = fastPhase,
                slowPhase = slowPhase,
                snowMinPx = snowMinPx,
                snowMaxPx = snowMaxPx,
                snowBokehPx = snowBokehPx,
                intensity = intensity
            )

            else -> {}
        }
    }
}

private fun DrawScope.drawClearDayAtmosphere(
    slowPhase: Float,
    sunCoronaPx: Float,
    sunbeamPx: Float,
    moteMaxPx: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height
    val sunCenter = Offset(width * 0.82f, height * 0.06f)
    val pulse = (0.92f + 0.08f * sin(slowPhase * Math.PI * 2)).toFloat()

    // 1. Broad atmospheric ambient solar wash across upper sky (diffuse, non-blinding)
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                SunbeamColor.copy(alpha = 0.16f * pulse * intensity),
                Transparent
            ),
            center = sunCenter,
            radius = width * 0.95f
        ),
        radius = width * 0.95f,
        center = sunCenter
    )

    // 2. Soft-focus honey-amber solar core aura
    val auraRadius = sunCoronaPx * 1.5f * pulse
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                SunCoronaCore.copy(alpha = 0.32f * pulse * intensity),
                SunbeamColor.copy(alpha = 0.14f * pulse * intensity),
                Transparent
            ),
            center = sunCenter,
            radius = auraRadius
        ),
        radius = auraRadius,
        center = sunCenter
    )

    // 3. Floating golden light motes with gentle organic drift
    for (i in 0 until 14) {
        val progress = (slowPhase + i * 0.071f) % 1f
        val x = width * ((i * 0.19f + 0.07f) % 1f) + sin(progress * Math.PI * 2 + i).toFloat() * 16f
        val y = height * 0.48f * (1f - progress)
        val alpha = sin(progress * Math.PI).toFloat() * 0.28f * intensity
        val radius = moteMaxPx * (0.45f + (i % 3) * 0.22f)
        drawCircle(
            color = SunParticleColor.copy(alpha = alpha.coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(x, y)
        )
    }
}

private fun DrawScope.drawClearNightAtmosphere(
    slowPhase: Float,
    starMinPx: Float,
    starMaxPx: Float,
    starSpikePx: Float,
    shootingTrailPx: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    // 1. Subtle celestial nebula glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                CelestialNebulaGlow.copy(alpha = 0.24f * intensity),
                Transparent
            ),
            center = Offset(width * 0.28f, height * 0.18f),
            radius = width * 0.65f
        ),
        radius = width * 0.65f,
        center = Offset(width * 0.28f, height * 0.18f)
    )

    // 2. Multi-layer twinkling starfield across upper 65% of sky
    for (i in 0 until 42) {
        val seedX = ((i * 37.3f + 11.7f) % 100f) / 100f
        val seedY = ((i * 53.9f + 7.1f) % 100f) / 100f
        val x = width * seedX
        val y = height * 0.62f * seedY
        val twinkle = (0.35f + 0.65f * sin(slowPhase * Math.PI * 4 + i * 1.3)).toFloat()
        val starRadius = if (i % 7 == 0) starMaxPx else starMinPx + (i % 3) * 0.4f * (starMaxPx - starMinPx)

        drawCircle(
            color = StarTwinkleColor.copy(alpha = (twinkle * 0.78f * intensity).coerceIn(0.1f, 0.95f)),
            radius = starRadius,
            center = Offset(x, y)
        )

        // Subtle diffraction cross on bright anchor stars
        if (i % 9 == 0) {
            val spikeLen = starSpikePx * (0.6f + 0.4f * twinkle)
            drawLine(
                color = StarDiffractionSpike.copy(alpha = twinkle * 0.45f * intensity),
                start = Offset(x - spikeLen, y),
                end = Offset(x + spikeLen, y),
                strokeWidth = starMinPx * 0.8f,
                cap = StrokeCap.Round
            )
            drawLine(
                color = StarDiffractionSpike.copy(alpha = twinkle * 0.45f * intensity),
                start = Offset(x, y - spikeLen),
                end = Offset(x, y + spikeLen),
                strokeWidth = starMinPx * 0.8f,
                cap = StrokeCap.Round
            )
        }
    }

    // 3. Periodic shooting star across the upper night sky
    if (slowPhase in 0.62f..0.72f) {
        val localT = (slowPhase - 0.62f) / 0.10f
        val headX = width * (0.82f - localT * 0.55f)
        val headY = height * (0.08f + localT * 0.22f)
        val tailX = headX + shootingTrailPx * 0.85f
        val tailY = headY - shootingTrailPx * 0.35f
        val fade = sin(localT * Math.PI).toFloat()

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    ShootingStarCoreColor.copy(alpha = 0.85f * fade),
                    ShootingStarColor.copy(alpha = 0.35f * fade),
                    Transparent
                ),
                start = Offset(headX, headY),
                end = Offset(tailX, tailY)
            ),
            start = Offset(headX, headY),
            end = Offset(tailX, tailY),
            strokeWidth = starMaxPx * 1.1f,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawCloudyMistAtmosphere(
    slowPhase: Float,
    cloudWidthPx: Float,
    cloudHeightPx: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    for (band in 0 until 5) {
        val speed = if (band % 2 == 0) 0.18f else -0.14f
        val rawX = ((band * 0.24f) + slowPhase * speed + 1f) % 1f
        val cx = -cloudWidthPx * 0.4f + rawX * (width + cloudWidthPx * 0.8f)
        val cy = height * (0.06f + band * 0.10f)
        val bandW = cloudWidthPx * (1.2f + (band % 3) * 0.25f)
        val bandH = cloudHeightPx * (0.9f + (band % 2) * 0.35f)

        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    CloudMistColor.copy(alpha = 0.22f * intensity),
                    CloudMistDeck.copy(alpha = 0.08f * intensity),
                    Transparent
                ),
                center = Offset(cx + bandW * 0.5f, cy + bandH * 0.5f),
                radius = bandW * 0.55f
            ),
            topLeft = Offset(cx, cy),
            size = Size(bandW, bandH)
        )
    }
}

private fun DrawScope.drawParallaxRainAtmosphere(
    fastPhase: Float,
    slowPhase: Float,
    strokeBgPx: Float,
    strokeMidPx: Float,
    strokeFgPx: Float,
    lenBgPx: Float,
    lenMidPx: Float,
    lenFgPx: Float,
    rainHazeHeightPx: Float,
    cloudWidthPx: Float,
    cloudHeightPx: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height
    val slantRatio = 0.12f

    // Layer 1: Volumetric drifting mist layers across sky and midground
    for (deck in 0 until 3) {
        val speed = if (deck % 2 == 0) 0.14f else -0.11f
        val rawX = ((deck * 0.33f) + slowPhase * speed + 1f) % 1f
        val cx = -cloudWidthPx * 0.4f + rawX * (width + cloudWidthPx * 0.8f)
        val cy = height * (0.12f + deck * 0.20f)
        val deckW = width * (1.15f + deck * 0.15f)
        val deckH = cloudHeightPx * (1.1f + (deck % 2) * 0.3f)

        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(
                    CloudMistColor.copy(alpha = (0.13f + deck * 0.03f) * intensity),
                    CloudMistDeck.copy(alpha = 0.05f * intensity),
                    Transparent
                ),
                center = Offset(cx + deckW * 0.5f, cy + deckH * 0.5f),
                radius = deckW * 0.55f
            ),
            topLeft = Offset(cx, cy),
            size = Size(deckW, deckH)
        )
    }

    // Layer 2: Background delicate micro-mist streaks (soft transparent edges)
    for (i in 0 until 18) {
        val progress = (fastPhase + (i * 0.173f)) % 1f
        val xStart = width * ((i * 0.137f) % 1.15f) - progress * width * slantRatio
        val yStart = -lenBgPx + progress * (height + lenBgPx * 2f)
        val xEnd = xStart - lenBgPx * slantRatio
        val yEnd = yStart + lenBgPx

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Transparent,
                    RainStreakBackground.copy(alpha = (0.16f * intensity).coerceIn(0f, 1f)),
                    Transparent
                ),
                start = Offset(xStart, yStart),
                end = Offset(xEnd, yEnd)
            ),
            start = Offset(xStart, yStart),
            end = Offset(xEnd, yEnd),
            strokeWidth = strokeBgPx,
            cap = StrokeCap.Round
        )
    }

    // Layer 3: Midground rain streaks with gentle gradient tail
    for (i in 0 until 14) {
        val progress = (fastPhase * 2f + (i * 0.219f)) % 1f
        val xStart = width * ((i * 0.193f) % 1.15f) - progress * width * slantRatio
        val yStart = -lenMidPx + progress * (height + lenMidPx * 2f)
        val xEnd = xStart - lenMidPx * slantRatio
        val yEnd = yStart + lenMidPx

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Transparent,
                    RainStreakMidground.copy(alpha = (0.30f * intensity).coerceIn(0f, 1f)),
                    Transparent
                ),
                start = Offset(xStart, yStart),
                end = Offset(xEnd, yEnd)
            ),
            start = Offset(xStart, yStart),
            end = Offset(xEnd, yEnd),
            strokeWidth = strokeMidPx,
            cap = StrokeCap.Round
        )
    }

    // Layer 4: Sparse foreground soft droplets
    for (i in 0 until 6) {
        val progress = (fastPhase * 3f + (i * 0.311f)) % 1f
        val xStart = width * ((i * 0.271f) % 1.15f) - progress * width * slantRatio
        val yStart = -lenFgPx + progress * (height + lenFgPx * 2f)
        val xEnd = xStart - lenFgPx * slantRatio
        val yEnd = yStart + lenFgPx

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    Transparent,
                    RainStreakForeground.copy(alpha = (0.42f * intensity).coerceIn(0f, 1f))
                ),
                start = Offset(xStart, yStart),
                end = Offset(xEnd, yEnd)
            ),
            start = Offset(xStart, yStart),
            end = Offset(xEnd, yEnd),
            strokeWidth = strokeFgPx,
            cap = StrokeCap.Round
        )
    }

    // Layer 5: Rising atmospheric mist haze along bottom edge
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                Transparent,
                RainHazeBottom.copy(alpha = (0.24f * intensity).coerceIn(0f, 1f))
            ),
            startY = height - rainHazeHeightPx,
            endY = height
        ),
        topLeft = Offset(0f, height - rainHazeHeightPx),
        size = Size(width, rainHazeHeightPx)
    )
}

private fun DrawScope.drawThunderstormLightning(
    slowPhase: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    // Highveld thunderstorm ambient sheet lightning: natural dual-pulse & distant glow
    val flashAlpha: Float = when {
        slowPhase in 0.18f..0.24f -> {
            val t = (slowPhase - 0.18f) / 0.06f
            if (t <= 0.40f) {
                // First softer pulse
                sin(t / 0.40f * Math.PI).toFloat() * 0.18f * intensity
            } else if (t in 0.45f..1.0f) {
                // Second brighter pulse with smooth decay
                sin((t - 0.45f) / 0.55f * Math.PI).toFloat() * 0.32f * intensity
            } else {
                0.04f * intensity
            }
        }
        slowPhase in 0.68f..0.72f -> {
            // Distant sheet lightning illumination
            val t = (slowPhase - 0.68f) / 0.04f
            sin(t * Math.PI).toFloat() * 0.12f * intensity
        }
        else -> 0f
    }

    if (flashAlpha > 0.005f) {
        // 1. Broad celestial sheet flash washing upper atmosphere
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    LightningFlashColor.copy(alpha = flashAlpha.coerceIn(0f, 0.45f)),
                    Transparent
                ),
                startY = 0f,
                endY = height * 0.75f
            ),
            size = Size(width, height * 0.75f)
        )

        // 2. Internal cloud deck bloom (illuminating clouds from within)
        val cloudGlowCenter = Offset(width * 0.58f, height * 0.18f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    LightningFlashColor.copy(alpha = (flashAlpha * 0.75f).coerceIn(0f, 0.35f)),
                    Transparent
                ),
                center = cloudGlowCenter,
                radius = width * 0.68f
            ),
            radius = width * 0.68f,
            center = cloudGlowCenter
        )
    }
}

private fun DrawScope.drawBokehSnowAtmosphere(
    fastPhase: Float,
    slowPhase: Float,
    snowMinPx: Float,
    snowMaxPx: Float,
    snowBokehPx: Float,
    intensity: Float
) {
    val width = size.width
    val height = size.height

    // 1. Background & midground crisp snowflakes with gentle sine drift
    for (i in 0 until 44) {
        val speedFactor = if (i % 2 == 0) 1f else 2f
        val progress = (slowPhase * speedFactor + (i * 0.137f)) % 1f
        val sway = sin((progress * 2.0 + i * 0.4) * Math.PI * 2).toFloat() * (14f + (i % 4) * 5f)
        val x = (width * ((i * 0.179f) % 1f) + sway + width) % width
        val y = -snowMaxPx + progress * (height + snowMaxPx * 2f)
        val radius = snowMinPx + (i % 4) * 0.3f * (snowMaxPx - snowMinPx)
        val alpha = (0.35f + (i % 3) * 0.22f) * intensity

        drawCircle(
            color = SnowflakeColor.copy(alpha = alpha.coerceIn(0.15f, 0.90f)),
            radius = radius,
            center = Offset(x, y)
        )
    }

    // 2. Foreground soft out-of-focus bokeh snow orbs
    for (i in 0 until 10) {
        val progress = (fastPhase + i * 0.29f) % 1f
        val sway = sin((progress + i * 0.5) * Math.PI * 2).toFloat() * 26f
        val x = (width * ((i * 0.23f + 0.11f) % 1f) + sway + width) % width
        val y = -snowBokehPx * 2f + progress * (height + snowBokehPx * 4f)
        val bokehR = snowBokehPx * (0.9f + (i % 3) * 0.35f)

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SnowflakeCoreColor.copy(alpha = 0.38f * intensity),
                    SnowflakeBokehHalo.copy(alpha = 0.14f * intensity),
                    Transparent
                ),
                center = Offset(x, y),
                radius = bokehR
            ),
            radius = bokehR,
            center = Offset(x, y)
        )
    }
}
