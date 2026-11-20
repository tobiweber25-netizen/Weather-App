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
import androidx.compose.ui.graphics.drawscope.clipPath
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Identifies conditions where sun or moon peeks behind frosted clouds,
 * matching Almanac's luminous backlit glass icon aesthetic.
 */
fun isPartlyCloudyCondition(conditionText: String): Boolean {
    val c = conditionText.lowercase()
    if (c.contains("rain") || c.contains("shower") || c.contains("drizzle") ||
        c.contains("thunder") || c.contains("storm") || c.contains("snow") ||
        c.contains("sleet") || c.contains("fog") || c.contains("mist")
    ) {
        return false
    }
    return c.contains("partly") ||
        c.contains("mostly sun") ||
        c.contains("mostly clear") ||
        c.contains("scattered") ||
        c.contains("few cloud") ||
        c.contains("broken cloud") ||
        c.contains("sun and cloud") ||
        c.contains("intermittent")
}

/**
 * Multi-layer luminous frosted-glass weather iconography inspired by Almanac's app icon.
 * Retains the NothingWeatherIcon entry point for seamless drop-in compatibility.
 */
@Composable
fun NothingWeatherIcon(
    effectType: WeatherEffectType,
    modifier: Modifier = Modifier,
    isPartlyCloudy: Boolean = false,
    isDaytime: Boolean = true,
    animate: Boolean = true
) {
    if (effectType == WeatherEffectType.OFF || effectType == WeatherEffectType.AUTO) return

    val phase = if (animate) {
        val infiniteTransition = rememberInfiniteTransition(label = "luminous_icon_anim")
        val animated by infiniteTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 32000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "icon_phase"
        )
        animated
    } else {
        0.15f
    }

    val foregroundCloudPath = remember { Path() }
    val backgroundCloudPath = remember { Path() }
    val shadowCloudPath = remember { Path() }

    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val sz = minOf(width, height)
        val t = phase
        val breathe = if (animate) sin(t * Math.PI * 2).toFloat() else 0f

        when {
            isPartlyCloudy && (effectType == WeatherEffectType.CLOUDY || effectType == WeatherEffectType.CLEAR_DAY || effectType == WeatherEffectType.CLEAR_NIGHT) -> {
                drawPartlyCloudyGlassIcon(
                    width = width,
                    height = height,
                    sz = sz,
                    t = t,
                    breathe = breathe,
                    isDaytime = isDaytime && effectType != WeatherEffectType.CLEAR_NIGHT,
                    cloudPath = foregroundCloudPath,
                    shadowPath = shadowCloudPath
                )
            }

            effectType == WeatherEffectType.CLEAR_DAY -> {
                drawClearDaySunIcon(
                    cx = width * 0.5f,
                    cy = height * 0.5f,
                    sz = sz,
                    t = t,
                    breathe = breathe,
                    animate = animate
                )
            }

            effectType == WeatherEffectType.CLEAR_NIGHT -> {
                drawClearNightMoonIcon(
                    cx = width * 0.49f,
                    cy = height * 0.51f,
                    sz = sz,
                    t = t,
                    animate = animate
                )
            }

            effectType == WeatherEffectType.CLOUDY -> {
                val drift = breathe * sz * 0.022f
                // Rear cool-silver frosted cloud
                buildSculpturalCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.58f - drift * 0.6f,
                    cy = height * 0.44f,
                    sz = sz,
                    scale = 0.80f
                )
                drawPath(
                    path = backgroundCloudPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            CloudMidSilver.copy(alpha = 0.82f),
                            CloudShadowSlate.copy(alpha = 0.72f)
                        ),
                        startY = height * 0.20f,
                        endY = height * 0.62f
                    )
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = GlassCloudRimLight.copy(alpha = 0.30f),
                    style = Stroke(width = maxOf(sz * 0.015f, 1f), join = StrokeJoin.Round)
                )

                // Soft shadow under foreground cloud
                buildSculpturalCloudPath(
                    target = shadowCloudPath,
                    cx = width * 0.46f + drift,
                    cy = height * 0.58f,
                    sz = sz,
                    scale = 1.00f
                )
                drawPath(path = shadowCloudPath, color = GlassCloudDropShadow)

                // Foreground frosted glass cloud
                buildSculpturalCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.46f + drift,
                    cy = height * 0.55f,
                    sz = sz,
                    scale = 1.00f
                )
                drawLuminousGlassCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.28f,
                    bottomY = height * 0.72f,
                    sz = sz
                )
            }

            effectType == WeatherEffectType.RAIN -> {
                val drift = breathe * sz * 0.016f
                // Rear storm-tinted cloud
                buildSculpturalCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.57f - drift,
                    cy = height * 0.36f,
                    sz = sz,
                    scale = 0.76f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudShadowSlate.copy(alpha = 0.76f)
                )

                // Sleek tapered cyan-blue rain streaks
                val dropStroke = maxOf(sz * 0.042f, 2.0f)
                for (i in -1..1) {
                    val dropPhase = if (animate) (t * 4f + (i + 1) * 0.33f) % 1f else ((i + 1) * 0.28f)
                    val baseX = width * 0.50f + i * sz * 0.145f - dropPhase * sz * 0.055f
                    val startY = height * 0.53f + dropPhase * sz * 0.18f
                    val len = sz * (0.14f + if (i == 0) 0.03f else 0f)
                    val endX = baseX - sz * 0.045f
                    val endY = startY + len
                    val alpha = (1f - dropPhase * 0.65f).coerceIn(0.30f, 0.96f)

                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                RainDropCyan.copy(alpha = alpha * 0.15f),
                                RainDropCyan.copy(alpha = alpha * 0.85f),
                                RainDropDeepBlue.copy(alpha = alpha)
                            ),
                            start = Offset(baseX, startY),
                            end = Offset(endX, endY)
                        ),
                        start = Offset(baseX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = dropStroke,
                        cap = StrokeCap.Round
                    )
                    // Specular droplet head glint
                    drawCircle(
                        color = PureWhite.copy(alpha = alpha * 0.75f),
                        radius = dropStroke * 0.42f,
                        center = Offset(endX, endY)
                    )
                }

                // Foreground frosted rain cloud
                buildSculpturalCloudPath(
                    target = shadowCloudPath,
                    cx = width * 0.47f + drift,
                    cy = height * 0.46f,
                    sz = sz,
                    scale = 0.96f
                )
                drawPath(path = shadowCloudPath, color = GlassCloudDropShadow)

                buildSculpturalCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.47f + drift,
                    cy = height * 0.43f,
                    sz = sz,
                    scale = 0.96f
                )
                drawLuminousGlassCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.18f,
                    bottomY = height * 0.60f,
                    sz = sz,
                    isStorm = false,
                    coolRainTint = true
                )
            }

            effectType == WeatherEffectType.THUNDERSTORM -> {
                val drift = breathe * sz * 0.014f
                val pulseAlpha = if (animate) 0.78f + 0.22f * sin(t * Math.PI * 8).toFloat() else 0.92f

                // Rear dark storm anvil cloud
                buildSculpturalCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.57f - drift,
                    cy = height * 0.35f,
                    sz = sz,
                    scale = 0.78f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudStormDark.copy(alpha = 0.88f)
                )

                // Flanking rain streaks
                val dropStroke = maxOf(sz * 0.034f, 1.8f)
                for (side in listOf(-1, 1)) {
                    val dropPhase = if (animate) (t * 4.5f + if (side < 0) 0.15f else 0.65f) % 1f else 0.3f
                    val x = width * 0.50f + side * sz * 0.21f - dropPhase * sz * 0.04f
                    val y = height * 0.55f + dropPhase * sz * 0.17f
                    drawLine(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                RainDropCyan.copy(alpha = 0.15f),
                                RainDropCyan.copy(alpha = (1f - dropPhase * 0.55f) * 0.85f)
                            ),
                            start = Offset(x, y),
                            end = Offset(x - sz * 0.035f, y + sz * 0.12f)
                        ),
                        start = Offset(x, y),
                        end = Offset(x - sz * 0.035f, y + sz * 0.12f),
                        strokeWidth = dropStroke,
                        cap = StrokeCap.Round
                    )
                }

                // Glowing electric golden-white lightning bolt
                val lx = width * 0.50f
                val ly = height * 0.46f
                val boltPath = Path().apply {
                    moveTo(lx + sz * 0.035f, ly)
                    lineTo(lx - sz * 0.095f, ly + sz * 0.19f)
                    lineTo(lx + sz * 0.005f, ly + sz * 0.19f)
                    lineTo(lx - sz * 0.055f, ly + sz * 0.38f)
                    lineTo(lx + sz * 0.115f, ly + sz * 0.135f)
                    lineTo(lx + sz * 0.015f, ly + sz * 0.135f)
                    close()
                }

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            SunCoreGold.copy(alpha = 0.48f * pulseAlpha),
                            SunCoronaAmber.copy(alpha = 0.18f * pulseAlpha),
                            Transparent
                        ),
                        center = Offset(lx, ly + sz * 0.16f),
                        radius = sz * 0.30f
                    ),
                    radius = sz * 0.30f,
                    center = Offset(lx, ly + sz * 0.16f)
                )
                drawPath(
                    path = boltPath,
                    brush = Brush.verticalGradient(
                        colors = listOf(PureWhite, SunCoreGold, SunCoreDeepAmber),
                        startY = ly,
                        endY = ly + sz * 0.38f
                    )
                )

                // Foreground storm-frosted cloud with warm golden under-belly glow
                buildSculpturalCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.47f + drift,
                    cy = height * 0.41f,
                    sz = sz,
                    scale = 0.95f
                )
                drawLuminousGlassCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.17f,
                    bottomY = height * 0.58f,
                    sz = sz,
                    isStorm = true,
                    warmBacklightCenter = Offset(lx, height * 0.56f),
                    warmBacklightRadius = sz * 0.28f
                )
            }

            effectType == WeatherEffectType.SNOW -> {
                val drift = breathe * sz * 0.016f
                // Rear frosted cloud
                buildSculpturalCloudPath(
                    target = backgroundCloudPath,
                    cx = width * 0.57f - drift,
                    cy = height * 0.36f,
                    sz = sz,
                    scale = 0.76f
                )
                drawPath(
                    path = backgroundCloudPath,
                    color = CloudMidSilver.copy(alpha = 0.74f)
                )

                // Drifting 6-point crystalline snowflakes with soft bokeh halo
                val flakeRadius = sz * 0.052f
                val flakeStroke = maxOf(sz * 0.024f, 1.4f)
                for (i in -1..1) {
                    val fallProgress = if (animate) (t * 2.5f + (i + 1) * 0.33f) % 1f else ((i + 1) * 0.25f)
                    val cxFlake = width * 0.50f + i * sz * 0.155f + sin((fallProgress + i) * Math.PI * 2).toFloat() * sz * 0.022f
                    val cyFlake = height * 0.56f + fallProgress * sz * 0.22f
                    val alpha = (1f - fallProgress * 0.55f).coerceIn(0.35f, 1f)
                    val baseAngle = if (animate) t * Math.PI * 2 else 0.0

                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                PureWhite.copy(alpha = 0.30f * alpha),
                                Transparent
                            ),
                            center = Offset(cxFlake, cyFlake),
                            radius = flakeRadius * 1.9f
                        ),
                        radius = flakeRadius * 1.9f,
                        center = Offset(cxFlake, cyFlake)
                    )

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
                    drawCircle(
                        color = PureWhite.copy(alpha = alpha),
                        radius = flakeStroke * 0.65f,
                        center = Offset(cxFlake, cyFlake)
                    )
                }

                // Foreground frosted cloud
                buildSculpturalCloudPath(
                    target = foregroundCloudPath,
                    cx = width * 0.47f + drift,
                    cy = height * 0.43f,
                    sz = sz,
                    scale = 0.95f
                )
                drawLuminousGlassCloud(
                    cloudPath = foregroundCloudPath,
                    topY = height * 0.19f,
                    bottomY = height * 0.58f,
                    sz = sz
                )
            }

            else -> {}
        }
    }
}

private fun DrawScope.drawClearDaySunIcon(
    cx: Float,
    cy: Float,
    sz: Float,
    t: Float,
    breathe: Float,
    animate: Boolean
) {
    val sunRadius = sz * 0.245f

    // 1. Broad luminous amber-gold solar corona
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                SunCoreGold.copy(alpha = 0.48f + 0.08f * breathe),
                SunCoronaAmber.copy(alpha = 0.22f),
                Transparent
            ),
            center = Offset(cx, cy),
            radius = sz * 0.49f
        ),
        radius = sz * 0.49f,
        center = Offset(cx, cy)
    )

    // 2. Delicate alternating tapered solar rays with soft gradient fade
    val rayCount = 12
    val spinOffset = if (animate) t * Math.PI * 2.0 else 0.0
    val rayStroke = maxOf(sz * 0.034f, 1.8f)
    for (i in 0 until rayCount) {
        val isMajor = i % 2 == 0
        val angle = (i * Math.PI * 2.0 / rayCount) + spinOffset
        val startR = sunRadius + sz * 0.055f
        val endR = sunRadius + sz * (if (isMajor) 0.175f else 0.125f) + (if (isMajor) sz * 0.018f * breathe else 0f)
        val cosA = cos(angle).toFloat()
        val sinA = sin(angle).toFloat()
        val startPt = Offset(cx + cosA * startR, cy + sinA * startR)
        val endPt = Offset(cx + cosA * endR, cy + sinA * endR)

        drawLine(
            brush = Brush.linearGradient(
                colors = listOf(
                    SunRayWarmGold.copy(alpha = if (isMajor) 0.90f else 0.55f),
                    SunRayWarmGold.copy(alpha = 0.08f)
                ),
                start = startPt,
                end = endPt
            ),
            start = startPt,
            end = endPt,
            strokeWidth = if (isMajor) rayStroke else rayStroke * 0.78f,
            cap = StrokeCap.Round
        )
    }

    // 3. Sculpted 3D backlit golden solar sphere
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                PureWhite,
                SunCoreGold,
                SunCoreDeepAmber
            ),
            center = Offset(cx - sunRadius * 0.28f, cy - sunRadius * 0.28f),
            radius = sunRadius * 1.45f
        ),
        radius = sunRadius,
        center = Offset(cx, cy)
    )
    // Specular rim highlight on top-left curve
    drawCircle(
        brush = Brush.linearGradient(
            colors = listOf(
                PureWhite.copy(alpha = 0.65f),
                Transparent
            ),
            start = Offset(cx - sunRadius, cy - sunRadius),
            end = Offset(cx + sunRadius * 0.3f, cy + sunRadius * 0.3f)
        ),
        radius = sunRadius - maxOf(sz * 0.01f, 0.8f),
        center = Offset(cx, cy),
        style = Stroke(width = maxOf(sz * 0.016f, 1f))
    )
}

private fun DrawScope.drawClearNightMoonIcon(
    cx: Float,
    cy: Float,
    sz: Float,
    t: Float,
    animate: Boolean
) {
    val r = sz * 0.26f

    // 1. Soft pearlescent lunar glow
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                MoonPearlCore.copy(alpha = 0.32f),
                CelestialNebulaGlow.copy(alpha = 0.16f),
                Transparent
            ),
            center = Offset(cx, cy),
            radius = sz * 0.47f
        ),
        radius = sz * 0.47f,
        center = Offset(cx, cy)
    )

    // 2. Crisp 4-point concave diamond stars
    val starSpecs = listOf(
        Triple(Offset(cx - sz * 0.27f, cy - sz * 0.23f), sz * 0.072f, 0),
        Triple(Offset(cx + sz * 0.28f, cy - sz * 0.25f), sz * 0.056f, 1),
        Triple(Offset(cx - sz * 0.22f, cy + sz * 0.24f), sz * 0.044f, 2)
    )
    starSpecs.forEach { (pos, arm, idx) ->
        val twinkle = if (animate) (0.55f + 0.45f * sin(t * Math.PI * 6 + idx * 1.8)).toFloat() else 0.88f
        drawDiamondStar(
            center = pos,
            radius = arm * (0.82f + 0.18f * twinkle),
            color = StarTwinkleColor.copy(alpha = (0.45f + 0.55f * twinkle).coerceIn(0f, 1f))
        )
    }

    // 3. Sculpted pearlescent crescent moon
    drawSculptedCrescentMoon(cx = cx, cy = cy, radius = r)
}

private fun DrawScope.drawPartlyCloudyGlassIcon(
    width: Float,
    height: Float,
    sz: Float,
    t: Float,
    breathe: Float,
    isDaytime: Boolean,
    cloudPath: Path,
    shadowPath: Path
) {
    val drift = breathe * sz * 0.018f
    val orbCx = width * 0.60f - drift * 0.45f
    val orbCy = height * 0.39f

    if (isDaytime) {
        val sunR = sz * 0.205f
        // Warm amber backlight corona glowing behind frosted cloud (matches app icon!)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    SunCoreGold.copy(alpha = 0.52f + 0.06f * breathe),
                    SunCoronaAmber.copy(alpha = 0.24f),
                    Transparent
                ),
                center = Offset(orbCx, orbCy),
                radius = sz * 0.36f
            ),
            radius = sz * 0.36f,
            center = Offset(orbCx, orbCy)
        )
        // Sculpted golden sun peeking from upper-right
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(PureWhite, SunCoreGold, SunCoreDeepAmber),
                center = Offset(orbCx - sunR * 0.24f, orbCy - sunR * 0.24f),
                radius = sunR * 1.42f
            ),
            radius = sunR,
            center = Offset(orbCx, orbCy)
        )
    } else {
        val moonR = sz * 0.195f
        // Soft lunar aura behind frosted night cloud
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    MoonPearlCore.copy(alpha = 0.32f),
                    Transparent
                ),
                center = Offset(orbCx, orbCy),
                radius = sz * 0.36f
            ),
            radius = sz * 0.36f,
            center = Offset(orbCx, orbCy)
        )
        drawDiamondStar(
            center = Offset(orbCx + sz * 0.20f, orbCy - sz * 0.16f),
            radius = sz * 0.048f,
            color = StarTwinkleColor.copy(alpha = 0.85f)
        )
        drawSculptedCrescentMoon(cx = orbCx, cy = orbCy, radius = moonR)
    }

    // Soft ambient depth shadow beneath frosted foreground cloud
    buildSculpturalCloudPath(
        target = shadowPath,
        cx = width * 0.44f + drift,
        cy = height * 0.60f,
        sz = sz,
        scale = 0.96f
    )
    drawPath(path = shadowPath, color = GlassCloudDropShadow)

    // Backlit refractive frosted glass foreground cloud (app-icon signature!)
    buildSculpturalCloudPath(
        target = cloudPath,
        cx = width * 0.44f + drift,
        cy = height * 0.57f,
        sz = sz,
        scale = 0.96f
    )
    drawLuminousGlassCloud(
        cloudPath = cloudPath,
        topY = height * 0.32f,
        bottomY = height * 0.74f,
        sz = sz,
        warmBacklightCenter = if (isDaytime) Offset(orbCx - sz * 0.04f, orbCy + sz * 0.08f) else null,
        warmBacklightRadius = if (isDaytime) sz * 0.34f else 0f
    )
}

private fun DrawScope.drawSculptedCrescentMoon(
    cx: Float,
    cy: Float,
    radius: Float
) {
    val outerCircle = Path().apply {
        addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
    }
    val cutoutRadius = radius * 0.80f
    val cutoutCenter = Offset(cx - radius * 0.44f, cy - radius * 0.28f)
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
            start = Offset(cx - radius * 0.2f, cy - radius),
            end = Offset(cx + radius, cy + radius)
        )
    )
    drawPath(
        path = crescentPath,
        color = PureWhite.copy(alpha = 0.45f),
        style = Stroke(width = maxOf(radius * 0.055f, 0.9f), join = StrokeJoin.Round)
    )
}

private fun DrawScope.drawDiamondStar(
    center: Offset,
    radius: Float,
    color: Color
) {
    val inner = radius * 0.24f
    val path = Path().apply {
        moveTo(center.x, center.y - radius)
        quadraticTo(center.x + inner * 0.4f, center.y - inner * 0.4f, center.x + radius, center.y)
        quadraticTo(center.x + inner * 0.4f, center.y + inner * 0.4f, center.x, center.y + radius)
        quadraticTo(center.x - inner * 0.4f, center.y + inner * 0.4f, center.x - radius, center.y)
        quadraticTo(center.x - inner * 0.4f, center.y - inner * 0.4f, center.x, center.y - radius)
        close()
    }
    drawPath(path = path, color = color)
}

private fun DrawScope.drawLuminousGlassCloud(
    cloudPath: Path,
    topY: Float,
    bottomY: Float,
    sz: Float,
    isStorm: Boolean = false,
    coolRainTint: Boolean = false,
    warmBacklightCenter: Offset? = null,
    warmBacklightRadius: Float = 0f
) {
    val bodyColors = when {
        isStorm -> listOf(GlassCloudStormTop, GlassCloudStormMid, GlassCloudStormBottom)
        coolRainTint -> listOf(GlassCloudBodyTop, CloudMidSilver, CloudShadowSlate.copy(alpha = 0.90f))
        else -> listOf(GlassCloudBodyTop, GlassCloudBodyMid, GlassCloudBodyBottom)
    }

    // 1. Frosted translucent glass body fill
    drawPath(
        path = cloudPath,
        brush = Brush.verticalGradient(
            colors = bodyColors,
            startY = topY,
            endY = bottomY
        )
    )

    // 2. Subsurface warm golden backlight scattering inside frosted glass (app icon signature)
    if (warmBacklightCenter != null && warmBacklightRadius > 1f) {
        clipPath(cloudPath) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        GlassCloudWarmBounce,
                        SunCoronaAmber.copy(alpha = 0.22f),
                        Transparent
                    ),
                    center = warmBacklightCenter,
                    radius = warmBacklightRadius
                ),
                radius = warmBacklightRadius,
                center = warmBacklightCenter
            )
        }
    }

    // 3. Directional specular glass rim highlight (top-left bright edge fading around bottom)
    drawPath(
        path = cloudPath,
        brush = Brush.verticalGradient(
            colors = listOf(
                GlassCloudRimLight,
                PureWhite.copy(alpha = 0.35f),
                PureWhite.copy(alpha = 0.14f)
            ),
            startY = topY,
            endY = bottomY
        ),
        style = Stroke(
            width = maxOf(sz * 0.022f, 1.1f),
            join = StrokeJoin.Round
        )
    )
}

/**
 * Smooth, organic multi-lobe cumulus cloud silhouette with rounded capsule base
 * (zero hard 90-degree corners).
 */
private fun buildSculpturalCloudPath(
    target: Path,
    cx: Float,
    cy: Float,
    sz: Float,
    scale: Float
) {
    val bottomY = cy + sz * 0.12f * scale
    val rMain = sz * 0.175f * scale
    val rLeft = sz * 0.125f * scale
    val rRight = sz * 0.135f * scale

    val leftCx = cx - rMain * 0.98f
    val rightCx = cx + rMain * 1.02f

    val mainLob = Path().apply {
        addOval(Rect(cx - rMain, bottomY - rMain * 2.02f, cx + rMain, bottomY - rMain * 0.02f))
    }
    val leftLob = Path().apply {
        addOval(Rect(leftCx - rLeft, bottomY - rLeft * 2.0f, leftCx + rLeft, bottomY))
    }
    val rightLob = Path().apply {
        addOval(Rect(rightCx - rRight, bottomY - rRight * 2.0f, rightCx + rRight, bottomY))
    }
    // Exact tangent bridge between left and right lobe bottom points (C1 continuous, zero corners)
    val bridgeHeight = minOf(rLeft, rRight) * 1.15f
    val capsuleBase = Path().apply {
        addRect(
            Rect(
                left = leftCx,
                top = bottomY - bridgeHeight,
                right = rightCx,
                bottom = bottomY
            )
        )
    }

    target.reset()
    target.op(mainLob, leftLob, PathOperation.Union)
    target.op(target, rightLob, PathOperation.Union)
    target.op(target, capsuleBase, PathOperation.Union)
}
