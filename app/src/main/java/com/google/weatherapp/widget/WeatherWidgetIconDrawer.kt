package com.google.weatherapp.widget

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.*
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native Android Canvas port of [com.google.weatherapp.ui.components.NothingWeatherIcon].
 * Draws Almanac's backlit frosted-glass weather iconography inside any home screen widget bitmap.
 */
object WeatherWidgetIconDrawer {

    private class DrawPaints {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    }

    fun drawIcon(
        canvas: Canvas,
        effectType: WeatherEffectType,
        cx: Float,
        cy: Float,
        sizePx: Float,
        isPartlyCloudy: Boolean = false,
        isDaytime: Boolean = true
    ) {
        if (sizePx <= 4f) return
        val sz = sizePx
        val paints = DrawPaints()

        when {
            isPartlyCloudy && (effectType == WeatherEffectType.CLOUDY || effectType == WeatherEffectType.CLEAR_DAY || effectType == WeatherEffectType.CLEAR_NIGHT) -> {
                drawPartlyCloudy(
                    canvas = canvas,
                    paints = paints,
                    cx = cx,
                    cy = cy,
                    sz = sz,
                    isDaytime = isDaytime && effectType != WeatherEffectType.CLEAR_NIGHT
                )
            }
            effectType == WeatherEffectType.CLEAR_DAY -> drawClearDay(canvas, paints, cx, cy, sz)
            effectType == WeatherEffectType.CLEAR_NIGHT -> drawClearNight(canvas, paints, cx, cy, sz)
            effectType == WeatherEffectType.CLOUDY -> drawCloudy(canvas, paints, cx, cy, sz)
            effectType == WeatherEffectType.RAIN -> drawRain(canvas, paints, cx, cy, sz)
            effectType == WeatherEffectType.THUNDERSTORM -> drawThunderstorm(canvas, paints, cx, cy, sz)
            effectType == WeatherEffectType.SNOW -> drawSnow(canvas, paints, cx, cy, sz)
            else -> drawClearDay(canvas, paints, cx, cy, sz)
        }
    }

    private fun drawClearDay(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val sunRadius = sz * 0.245f

        // 1. Soft outer ambient solar corona glow
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            sz * 0.49f,
            intArrayOf(
                SunCoreGold.copy(alpha = 0.48f).toArgb(),
                SunCoronaAmber.copy(alpha = 0.20f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sz * 0.49f, fillPaint)
        fillPaint.shader = null

        // 2. Alternating tapered sun rays with gentle gradient fade
        val rayCount = 12
        val baseStroke = maxOf(sz * 0.034f, 1.8f)
        for (i in 0 until rayCount) {
            val isMajor = i % 2 == 0
            val angle = i * Math.PI * 2.0 / rayCount
            val startR = sunRadius + sz * 0.055f
            val endR = sunRadius + sz * (if (isMajor) 0.175f else 0.125f)
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            val x1 = cx + cosA * startR
            val y1 = cy + sinA * startR
            val x2 = cx + cosA * endR
            val y2 = cy + sinA * endR

            strokePaint.strokeWidth = if (isMajor) baseStroke else baseStroke * 0.78f
            strokePaint.shader = LinearGradient(
                x1,
                y1,
                x2,
                y2,
                SunRayWarmGold.copy(alpha = if (isMajor) 0.88f else 0.52f).toArgb(),
                SunRayWarmGold.copy(alpha = 0.08f).toArgb(),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
        strokePaint.shader = null

        // 3. Sculpted 3D golden sun orb
        fillPaint.shader = RadialGradient(
            cx - sunRadius * 0.28f,
            cy - sunRadius * 0.28f,
            sunRadius * 1.45f,
            intArrayOf(
                PureWhite.toArgb(),
                SunCoreGold.toArgb(),
                SunCoreDeepAmber.toArgb()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sunRadius, fillPaint)
        fillPaint.shader = null
    }

    private fun drawClearNight(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val r = sz * 0.26f

        // 1. Ambient lunar halo
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            sz * 0.47f,
            intArrayOf(
                MoonPearlCore.copy(alpha = 0.30f).toArgb(),
                CelestialNebulaGlow.copy(alpha = 0.14f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sz * 0.47f, fillPaint)
        fillPaint.shader = null

        // 2. 4-point concave diamond stars
        drawDiamondStar(canvas, fillPaint, cx - sz * 0.27f, cy - sz * 0.23f, sz * 0.070f)
        drawDiamondStar(canvas, fillPaint, cx + sz * 0.28f, cy - sz * 0.25f, sz * 0.054f)
        drawDiamondStar(canvas, fillPaint, cx - sz * 0.22f, cy + sz * 0.24f, sz * 0.042f)

        // 3. Sculpted pearlescent crescent moon
        drawSculptedCrescent(canvas, paints, cx, cy, r)
    }

    private fun drawPartlyCloudy(
        canvas: Canvas,
        paints: DrawPaints,
        cx: Float,
        cy: Float,
        sz: Float,
        isDaytime: Boolean
    ) {
        val fillPaint = paints.fillPaint
        val orbCx = cx + sz * 0.10f
        val orbCy = cy - sz * 0.11f

        if (isDaytime) {
            val sunR = sz * 0.205f
            fillPaint.shader = RadialGradient(
                orbCx,
                orbCy,
                sz * 0.36f,
                intArrayOf(
                    SunCoreGold.copy(alpha = 0.50f).toArgb(),
                    SunCoronaAmber.copy(alpha = 0.22f).toArgb(),
                    0x00000000
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orbCx, orbCy, sz * 0.36f, fillPaint)

            fillPaint.shader = RadialGradient(
                orbCx - sunR * 0.24f,
                orbCy - sunR * 0.24f,
                sunR * 1.42f,
                intArrayOf(PureWhite.toArgb(), SunCoreGold.toArgb(), SunCoreDeepAmber.toArgb()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orbCx, orbCy, sunR, fillPaint)
            fillPaint.shader = null
        } else {
            val moonR = sz * 0.195f
            fillPaint.shader = RadialGradient(
                orbCx,
                orbCy,
                sz * 0.35f,
                intArrayOf(MoonPearlCore.copy(alpha = 0.30f).toArgb(), 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(orbCx, orbCy, sz * 0.35f, fillPaint)
            fillPaint.shader = null
            drawDiamondStar(canvas, fillPaint, orbCx + sz * 0.19f, orbCy - sz * 0.15f, sz * 0.048f)
            drawSculptedCrescent(canvas, paints, orbCx, orbCy, moonR)
        }

        val shadowPath = buildSculpturalCloudPath(cx - sz * 0.06f, cy + sz * 0.10f, sz, 0.96f)
        fillPaint.shader = null
        fillPaint.color = GlassCloudDropShadow.toArgb()
        canvas.drawPath(shadowPath, fillPaint)

        val fgPath = buildSculpturalCloudPath(cx - sz * 0.06f, cy + sz * 0.07f, sz, 0.96f)
        drawLuminousForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.18f,
            bottomY = cy + sz * 0.24f,
            sz = sz,
            warmGlowX = if (isDaytime) orbCx - sz * 0.04f else null,
            warmGlowY = if (isDaytime) orbCy + sz * 0.08f else null,
            warmGlowR = if (isDaytime) sz * 0.34f else 0f
        )
    }

    private fun drawCloudy(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val bgPath = buildSculpturalCloudPath(cx + sz * 0.08f, cy - sz * 0.06f, sz, 0.80f)
        fillPaint.shader = LinearGradient(
            cx,
            cy - sz * 0.28f,
            cx,
            cy + sz * 0.12f,
            CloudMidSilver.copy(alpha = 0.82f).toArgb(),
            CloudShadowSlate.copy(alpha = 0.72f).toArgb(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bgPath, fillPaint)
        fillPaint.shader = null

        val shadowPath = buildSculpturalCloudPath(cx - sz * 0.04f, cy + sz * 0.08f, sz, 1.00f)
        fillPaint.color = GlassCloudDropShadow.toArgb()
        canvas.drawPath(shadowPath, fillPaint)

        val fgPath = buildSculpturalCloudPath(cx - sz * 0.04f, cy + sz * 0.05f, sz, 1.00f)
        drawLuminousForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.20f,
            bottomY = cy + sz * 0.22f,
            sz = sz
        )
    }

    private fun drawRain(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val bgPath = buildSculpturalCloudPath(cx + sz * 0.07f, cy - sz * 0.14f, sz, 0.76f)
        fillPaint.shader = null
        fillPaint.color = CloudShadowSlate.copy(alpha = 0.76f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        // Angled cyan rain streaks with specular tips
        val dropStroke = maxOf(sz * 0.042f, 2f)
        strokePaint.strokeWidth = dropStroke
        for (i in -1..1) {
            val baseX = cx + i * sz * 0.145f
            val startY = cy + sz * 0.10f + (if (i == 0) sz * 0.04f else 0f)
            val endX = baseX - sz * 0.042f
            val endY = startY + sz * 0.15f
            strokePaint.shader = LinearGradient(
                baseX,
                startY,
                endX,
                endY,
                intArrayOf(
                    RainDropCyan.copy(alpha = 0.20f).toArgb(),
                    RainDropCyan.toArgb(),
                    RainDropDeepBlue.toArgb()
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(baseX, startY, endX, endY, strokePaint)
            fillPaint.shader = null
            fillPaint.color = PureWhite.copy(alpha = 0.72f).toArgb()
            canvas.drawCircle(endX, endY, dropStroke * 0.40f, fillPaint)
        }
        strokePaint.shader = null

        val fgPath = buildSculpturalCloudPath(cx - sz * 0.03f, cy - sz * 0.07f, sz, 0.96f)
        drawLuminousForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.30f,
            bottomY = cy + sz * 0.10f,
            sz = sz,
            coolRainTint = true
        )
    }

    private fun drawThunderstorm(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val bgPath = buildSculpturalCloudPath(cx + sz * 0.07f, cy - sz * 0.15f, sz, 0.78f)
        fillPaint.shader = null
        fillPaint.color = CloudStormDark.copy(alpha = 0.88f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        // Flanking rain drops
        strokePaint.shader = null
        strokePaint.color = RainDropCyan.copy(alpha = 0.80f).toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.034f, 1.8f)
        for (side in intArrayOf(-1, 1)) {
            val rx = cx + side * sz * 0.21f
            val ry = cy + sz * 0.11f
            canvas.drawLine(rx, ry, rx - sz * 0.035f, ry + sz * 0.12f, strokePaint)
        }

        // Glowing amber-gold lightning bolt
        val ly = cy - sz * 0.04f
        val boltPath = Path().apply {
            moveTo(cx + sz * 0.035f, ly)
            lineTo(cx - sz * 0.095f, ly + sz * 0.19f)
            lineTo(cx + sz * 0.005f, ly + sz * 0.19f)
            lineTo(cx - sz * 0.055f, ly + sz * 0.38f)
            lineTo(cx + sz * 0.115f, ly + sz * 0.135f)
            lineTo(cx + sz * 0.015f, ly + sz * 0.135f)
            close()
        }
        fillPaint.shader = RadialGradient(
            cx,
            ly + sz * 0.16f,
            sz * 0.30f,
            intArrayOf(
                SunCoreGold.copy(alpha = 0.45f).toArgb(),
                SunCoronaAmber.copy(alpha = 0.16f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, ly + sz * 0.16f, sz * 0.30f, fillPaint)

        fillPaint.shader = LinearGradient(
            cx,
            ly,
            cx,
            ly + sz * 0.38f,
            intArrayOf(PureWhite.toArgb(), SunCoreGold.toArgb(), SunCoreDeepAmber.toArgb()),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(boltPath, fillPaint)
        fillPaint.shader = null

        val fgPath = buildSculpturalCloudPath(cx - sz * 0.03f, cy - sz * 0.09f, sz, 0.95f)
        drawLuminousForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.32f,
            bottomY = cy + sz * 0.08f,
            sz = sz,
            isStorm = true,
            warmGlowX = cx,
            warmGlowY = cy + sz * 0.06f,
            warmGlowR = sz * 0.28f
        )
    }

    private fun drawSnow(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val bgPath = buildSculpturalCloudPath(cx + sz * 0.07f, cy - sz * 0.14f, sz, 0.76f)
        fillPaint.shader = null
        fillPaint.color = CloudMidSilver.copy(alpha = 0.74f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        val flakeRadius = sz * 0.050f
        strokePaint.shader = null
        strokePaint.color = SnowflakeCoreColor.toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.024f, 1.4f)
        for (i in -1..1) {
            val fx = cx + i * sz * 0.155f
            val fy = cy + sz * 0.18f + (if (i == 0) sz * 0.04f else 0f)
            fillPaint.shader = RadialGradient(
                fx,
                fy,
                flakeRadius * 1.8f,
                intArrayOf(PureWhite.copy(alpha = 0.28f).toArgb(), 0x00000000),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(fx, fy, flakeRadius * 1.8f, fillPaint)
            fillPaint.shader = null

            for (axis in 0 until 3) {
                val angle = axis * (Math.PI / 3.0)
                val dx = cos(angle).toFloat() * flakeRadius
                val dy = sin(angle).toFloat() * flakeRadius
                canvas.drawLine(fx - dx, fy - dy, fx + dx, fy + dy, strokePaint)
            }
        }

        val fgPath = buildSculpturalCloudPath(cx - sz * 0.03f, cy - sz * 0.07f, sz, 0.95f)
        drawLuminousForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.30f,
            bottomY = cy + sz * 0.08f,
            sz = sz
        )
    }

    private fun drawSculptedCrescent(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, r: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val outerCircle = Path().apply {
            addOval(RectF(cx - r, cy - r, cx + r, cy + r), Path.Direction.CW)
        }
        val cutoutRadius = r * 0.80f
        val cutCx = cx - r * 0.44f
        val cutCy = cy - r * 0.28f
        val innerCutout = Path().apply {
            addOval(
                RectF(
                    cutCx - cutoutRadius,
                    cutCy - cutoutRadius,
                    cutCx + cutoutRadius,
                    cutCy + cutoutRadius
                ),
                Path.Direction.CW
            )
        }
        val crescent = Path().apply {
            op(outerCircle, innerCutout, Path.Op.DIFFERENCE)
        }
        fillPaint.shader = LinearGradient(
            cx - r * 0.2f,
            cy - r,
            cx + r,
            cy + r,
            intArrayOf(PureWhite.toArgb(), MoonPearlCore.toArgb(), MoonCraterShadow.toArgb()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(crescent, fillPaint)
        fillPaint.shader = null

        strokePaint.shader = null
        strokePaint.color = PureWhite.copy(alpha = 0.45f).toArgb()
        strokePaint.strokeWidth = maxOf(r * 0.055f, 0.9f)
        canvas.drawPath(crescent, strokePaint)
    }

    private fun drawDiamondStar(canvas: Canvas, fillPaint: Paint, cx: Float, cy: Float, radius: Float) {
        val inner = radius * 0.24f
        val path = Path().apply {
            moveTo(cx, cy - radius)
            quadTo(cx + inner * 0.4f, cy - inner * 0.4f, cx + radius, cy)
            quadTo(cx + inner * 0.4f, cy + inner * 0.4f, cx, cy + radius)
            quadTo(cx - inner * 0.4f, cy + inner * 0.4f, cx - radius, cy)
            quadTo(cx - inner * 0.4f, cy - inner * 0.4f, cx, cy - radius)
            close()
        }
        fillPaint.shader = null
        fillPaint.color = StarTwinkleColor.copy(alpha = 0.90f).toArgb()
        canvas.drawPath(path, fillPaint)
    }

    private fun drawLuminousForegroundCloud(
        canvas: Canvas,
        paints: DrawPaints,
        cloudPath: Path,
        cx: Float,
        topY: Float,
        bottomY: Float,
        sz: Float,
        isStorm: Boolean = false,
        coolRainTint: Boolean = false,
        warmGlowX: Float? = null,
        warmGlowY: Float? = null,
        warmGlowR: Float = 0f
    ) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val colors = when {
            isStorm -> intArrayOf(GlassCloudStormTop.toArgb(), GlassCloudStormMid.toArgb(), GlassCloudStormBottom.toArgb())
            coolRainTint -> intArrayOf(GlassCloudBodyTop.toArgb(), CloudMidSilver.toArgb(), CloudShadowSlate.copy(alpha = 0.90f).toArgb())
            else -> intArrayOf(GlassCloudBodyTop.toArgb(), GlassCloudBodyMid.toArgb(), GlassCloudBodyBottom.toArgb())
        }

        fillPaint.shader = LinearGradient(
            cx,
            topY,
            cx,
            bottomY,
            colors,
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(cloudPath, fillPaint)

        // Subsurface warm golden backlight scattering inside cloud
        if (warmGlowX != null && warmGlowY != null && warmGlowR > 1f) {
            canvas.save()
            canvas.clipPath(cloudPath)
            fillPaint.shader = RadialGradient(
                warmGlowX,
                warmGlowY,
                warmGlowR,
                intArrayOf(
                    GlassCloudWarmBounce.toArgb(),
                    SunCoronaAmber.copy(alpha = 0.20f).toArgb(),
                    0x00000000
                ),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(warmGlowX, warmGlowY, warmGlowR, fillPaint)
            canvas.restore()
        }
        fillPaint.shader = null

        // Directional specular top-rim highlight
        strokePaint.shader = LinearGradient(
            cx,
            topY,
            cx,
            bottomY,
            intArrayOf(
                GlassCloudRimLight.toArgb(),
                PureWhite.copy(alpha = 0.35f).toArgb(),
                PureWhite.copy(alpha = 0.12f).toArgb()
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = maxOf(sz * 0.022f, 1.1f)
        canvas.drawPath(cloudPath, strokePaint)
        strokePaint.shader = null
    }

    private fun buildSculpturalCloudPath(
        cx: Float,
        cy: Float,
        sz: Float,
        scale: Float
    ): Path {
        val bottomY = cy + sz * 0.12f * scale
        val rMain = sz * 0.175f * scale
        val rLeft = sz * 0.125f * scale
        val rRight = sz * 0.135f * scale

        val leftCx = cx - rMain * 0.98f
        val rightCx = cx + rMain * 1.02f

        val mainLob = Path().apply {
            addOval(RectF(cx - rMain, bottomY - rMain * 2.02f, cx + rMain, bottomY - rMain * 0.02f), Path.Direction.CW)
        }
        val leftLob = Path().apply {
            addOval(RectF(leftCx - rLeft, bottomY - rLeft * 2.0f, leftCx + rLeft, bottomY), Path.Direction.CW)
        }
        val rightLob = Path().apply {
            addOval(RectF(rightCx - rRight, bottomY - rRight * 2.0f, rightCx + rRight, bottomY), Path.Direction.CW)
        }
        val bridgeH = minOf(rLeft, rRight) * 1.15f
        val capsuleBase = Path().apply {
            addRect(
                RectF(leftCx, bottomY - bridgeH, rightCx, bottomY),
                Path.Direction.CW
            )
        }

        return Path().apply {
            op(mainLob, leftLob, Path.Op.UNION)
            op(this, rightLob, Path.Op.UNION)
            op(this, capsuleBase, Path.Op.UNION)
        }
    }
}
