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
import com.google.weatherapp.theme.CloudHighlightWhite
import com.google.weatherapp.theme.CloudMidSilver
import com.google.weatherapp.theme.CloudShadowSlate
import com.google.weatherapp.theme.CloudStormDark
import com.google.weatherapp.theme.LightningBoltGlowColor
import com.google.weatherapp.theme.MoonCraterShadow
import com.google.weatherapp.theme.MoonPearlCore
import com.google.weatherapp.theme.PureWhite
import com.google.weatherapp.theme.RainDropCyan
import com.google.weatherapp.theme.RainDropDeepBlue
import com.google.weatherapp.theme.SnowflakeCoreColor
import com.google.weatherapp.theme.StarTwinkleColor
import com.google.weatherapp.theme.SunCoreGold
import com.google.weatherapp.theme.SunCoronaAmber
import com.google.weatherapp.theme.SunRayWarmGold
import kotlin.math.cos
import kotlin.math.sin

/**
 * Native Android Canvas port of [com.google.weatherapp.ui.components.NothingWeatherIcon].
 * Draws multi-layer dimensional weather iconography inside any home screen widget bitmap.
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
        sizePx: Float
    ) {
        if (sizePx <= 4f) return
        val sz = sizePx
        val paints = DrawPaints()

        when (effectType) {
            WeatherEffectType.CLEAR_DAY -> drawClearDay(canvas, paints, cx, cy, sz)
            WeatherEffectType.CLEAR_NIGHT -> drawClearNight(canvas, paints, cx, cy, sz)
            WeatherEffectType.CLOUDY -> drawCloudy(canvas, paints, cx, cy, sz)
            WeatherEffectType.RAIN -> drawRain(canvas, paints, cx, cy, sz)
            WeatherEffectType.THUNDERSTORM -> drawThunderstorm(canvas, paints, cx, cy, sz)
            WeatherEffectType.SNOW -> drawSnow(canvas, paints, cx, cy, sz)
            else -> drawClearDay(canvas, paints, cx, cy, sz)
        }
    }

    private fun drawClearDay(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val sunRadius = sz * 0.24f

        // 1. Soft outer ambient solar corona glow
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            sz * 0.48f,
            intArrayOf(
                SunCoreGold.copy(alpha = 0.46f).toArgb(),
                SunCoronaAmber.copy(alpha = 0.14f).toArgb(),
                0x00000000
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sz * 0.48f, fillPaint)
        fillPaint.shader = null

        // 2. Tapered sun rays
        val rayCount = 8
        strokePaint.shader = null
        strokePaint.color = SunRayWarmGold.toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.048f, 2f)
        for (i in 0 until rayCount) {
            val angle = i * Math.PI * 2.0 / rayCount
            val startR = sunRadius + sz * 0.07f
            val endR = sunRadius + sz * 0.18f
            val cosA = cos(angle).toFloat()
            val sinA = sin(angle).toFloat()
            canvas.drawLine(
                cx + cosA * startR,
                cy + sinA * startR,
                cx + cosA * endR,
                cy + sinA * endR,
                strokePaint
            )
        }

        // 3. Dimensional 3D golden sun orb
        fillPaint.shader = RadialGradient(
            cx - sunRadius * 0.25f,
            cy - sunRadius * 0.25f,
            sunRadius * 1.35f,
            intArrayOf(
                PureWhite.toArgb(),
                SunCoreGold.toArgb(),
                SunCoronaAmber.toArgb()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sunRadius, fillPaint)
        fillPaint.shader = null
    }

    private fun drawClearNight(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val r = sz * 0.25f

        // 1. Ambient lunar halo
        fillPaint.shader = RadialGradient(
            cx,
            cy,
            sz * 0.45f,
            intArrayOf(MoonPearlCore.copy(alpha = 0.28f).toArgb(), 0x00000000),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, sz * 0.45f, fillPaint)
        fillPaint.shader = null

        // 2. 4-point star sparkles
        val stars = arrayOf(
            floatArrayOf(cx - sz * 0.28f, cy - sz * 0.22f, 0.055f),
            floatArrayOf(cx + sz * 0.28f, cy - sz * 0.26f, 0.045f),
            floatArrayOf(cx - sz * 0.22f, cy + sz * 0.24f, 0.038f)
        )
        strokePaint.shader = null
        strokePaint.color = StarTwinkleColor.copy(alpha = 0.88f).toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.028f, 1.5f)
        for (star in stars) {
            val sx = star[0]
            val sy = star[1]
            val arm = sz * star[2]
            canvas.drawLine(sx - arm, sy, sx + arm, sy, strokePaint)
            canvas.drawLine(sx, sy - arm, sx, sy + arm, strokePaint)
        }

        // 3. Dimensional pearl crescent moon via boolean Path difference
        val outerCircle = Path().apply {
            addOval(RectF(cx - r, cy - r, cx + r, cy + r), Path.Direction.CW)
        }
        val cutoutRadius = r * 0.82f
        val cutCx = cx - r * 0.42f
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
            cx,
            cy - r,
            cx + r,
            cy + r,
            intArrayOf(
                PureWhite.toArgb(),
                MoonPearlCore.toArgb(),
                MoonCraterShadow.toArgb()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(crescent, fillPaint)
        fillPaint.shader = null
    }

    private fun drawCloudy(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val bgPath = buildVolumetricCloudPath(cx + sz * 0.07f, cy - sz * 0.06f, sz, 0.82f)
        fillPaint.shader = LinearGradient(
            cx,
            cy - sz * 0.28f,
            cx,
            cy + sz * 0.12f,
            CloudMidSilver.copy(alpha = 0.78f).toArgb(),
            CloudShadowSlate.copy(alpha = 0.68f).toArgb(),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(bgPath, fillPaint)
        fillPaint.shader = null

        val fgPath = buildVolumetricCloudPath(cx - sz * 0.03f, cy + sz * 0.05f, sz, 1.02f)
        drawDimensionalForegroundCloud(
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
        val bgPath = buildVolumetricCloudPath(cx + sz * 0.06f, cy - sz * 0.14f, sz, 0.78f)
        fillPaint.shader = null
        fillPaint.color = CloudShadowSlate.copy(alpha = 0.72f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        // Angled cyan rain droplets
        strokePaint.strokeWidth = maxOf(sz * 0.044f, 2f)
        for (i in -1..1) {
            val baseX = cx + i * sz * 0.14f
            val startY = cy + sz * 0.11f + (if (i == 0) sz * 0.04f else 0f)
            val endY = startY + sz * 0.14f
            strokePaint.shader = LinearGradient(
                baseX,
                startY,
                baseX - sz * 0.035f,
                endY,
                RainDropCyan.copy(alpha = 0.65f).toArgb(),
                RainDropDeepBlue.toArgb(),
                Shader.TileMode.CLAMP
            )
            canvas.drawLine(baseX, startY, baseX - sz * 0.035f, endY, strokePaint)
        }
        strokePaint.shader = null

        val fgPath = buildVolumetricCloudPath(cx - sz * 0.02f, cy - sz * 0.07f, sz, 0.98f)
        drawDimensionalForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.30f,
            bottomY = cy + sz * 0.10f,
            sz = sz
        )
    }

    private fun drawThunderstorm(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val bgPath = buildVolumetricCloudPath(cx + sz * 0.06f, cy - sz * 0.15f, sz, 0.80f)
        fillPaint.shader = null
        fillPaint.color = CloudStormDark.copy(alpha = 0.85f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        // Flanking rain drops
        strokePaint.shader = null
        strokePaint.color = RainDropCyan.copy(alpha = 0.78f).toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.036f, 1.8f)
        for (side in intArrayOf(-1, 1)) {
            val rx = cx + side * sz * 0.20f
            val ry = cy + sz * 0.11f
            canvas.drawLine(rx, ry, rx - sz * 0.03f, ry + sz * 0.11f, strokePaint)
        }

        // Glowing amber-gold lightning bolt
        val ly = cy - sz * 0.02f
        val boltPath = Path().apply {
            moveTo(cx + sz * 0.03f, ly)
            lineTo(cx - sz * 0.09f, ly + sz * 0.18f)
            lineTo(cx + sz * 0.01f, ly + sz * 0.18f)
            lineTo(cx - sz * 0.05f, ly + sz * 0.36f)
            lineTo(cx + sz * 0.11f, ly + sz * 0.13f)
            lineTo(cx + sz * 0.01f, ly + sz * 0.13f)
            close()
        }
        fillPaint.shader = RadialGradient(
            cx,
            ly + sz * 0.18f,
            sz * 0.26f,
            intArrayOf(LightningBoltGlowColor.copy(alpha = 0.42f).toArgb(), 0x00000000),
            null,
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, ly + sz * 0.18f, sz * 0.26f, fillPaint)

        fillPaint.shader = LinearGradient(
            cx,
            ly,
            cx,
            ly + sz * 0.36f,
            intArrayOf(
                PureWhite.toArgb(),
                SunCoreGold.toArgb(),
                SunCoronaAmber.toArgb()
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(boltPath, fillPaint)
        fillPaint.shader = null

        val fgPath = buildVolumetricCloudPath(cx - sz * 0.02f, cy - sz * 0.09f, sz, 0.96f)
        drawDimensionalForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.32f,
            bottomY = cy + sz * 0.08f,
            sz = sz
        )
    }

    private fun drawSnow(canvas: Canvas, paints: DrawPaints, cx: Float, cy: Float, sz: Float) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        val bgPath = buildVolumetricCloudPath(cx + sz * 0.06f, cy - sz * 0.14f, sz, 0.78f)
        fillPaint.shader = null
        fillPaint.color = CloudMidSilver.copy(alpha = 0.72f).toArgb()
        canvas.drawPath(bgPath, fillPaint)

        val flakeRadius = sz * 0.048f
        strokePaint.shader = null
        strokePaint.color = SnowflakeCoreColor.toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.026f, 1.5f)
        for (i in -1..1) {
            val fx = cx + i * sz * 0.15f
            val fy = cy + sz * 0.18f + (if (i == 0) sz * 0.04f else 0f)
            for (axis in 0 until 3) {
                val angle = axis * (Math.PI / 3.0)
                val dx = cos(angle).toFloat() * flakeRadius
                val dy = sin(angle).toFloat() * flakeRadius
                canvas.drawLine(fx - dx, fy - dy, fx + dx, fy + dy, strokePaint)
            }
        }

        val fgPath = buildVolumetricCloudPath(cx - sz * 0.02f, cy - sz * 0.07f, sz, 0.96f)
        drawDimensionalForegroundCloud(
            canvas = canvas,
            paints = paints,
            cloudPath = fgPath,
            cx = cx,
            topY = cy - sz * 0.30f,
            bottomY = cy + sz * 0.08f,
            sz = sz
        )
    }

    private fun drawDimensionalForegroundCloud(
        canvas: Canvas,
        paints: DrawPaints,
        cloudPath: Path,
        cx: Float,
        topY: Float,
        bottomY: Float,
        sz: Float
    ) {
        val fillPaint = paints.fillPaint
        val strokePaint = paints.strokePaint
        fillPaint.shader = LinearGradient(
            cx,
            topY,
            cx,
            bottomY,
            intArrayOf(
                PureWhite.toArgb(),
                CloudHighlightWhite.toArgb(),
                CloudMidSilver.toArgb()
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(cloudPath, fillPaint)
        fillPaint.shader = null

        strokePaint.shader = null
        strokePaint.color = PureWhite.copy(alpha = 0.58f).toArgb()
        strokePaint.strokeWidth = maxOf(sz * 0.018f, 1f)
        canvas.drawPath(cloudPath, strokePaint)
    }

    private fun buildVolumetricCloudPath(
        cx: Float,
        cy: Float,
        sz: Float,
        scale: Float
    ): Path {
        val bottomY = cy + sz * 0.11f * scale
        val rMain = sz * 0.17f * scale
        val rLeft = sz * 0.125f * scale
        val rRight = sz * 0.135f * scale

        val mainLob = Path().apply {
            addOval(
                RectF(cx - rMain * 0.95f, bottomY - rMain * 2.05f, cx + rMain * 1.05f, bottomY),
                Path.Direction.CW
            )
        }
        val leftLob = Path().apply {
            addOval(
                RectF(
                    cx - rMain - rLeft * 0.95f,
                    bottomY - rLeft * 1.95f,
                    cx - rMain + rLeft * 1.05f,
                    bottomY
                ),
                Path.Direction.CW
            )
        }
        val rightLob = Path().apply {
            addOval(
                RectF(
                    cx + rMain - rRight * 0.95f,
                    bottomY - rRight * 1.95f,
                    cx + rMain + rRight * 1.05f,
                    bottomY
                ),
                Path.Direction.CW
            )
        }
        val baseFill = Path().apply {
            addRect(
                RectF(cx - rMain, bottomY - rLeft * 1.3f, cx + rMain, bottomY),
                Path.Direction.CW
            )
        }

        return Path().apply {
            op(mainLob, leftLob, Path.Op.UNION)
            op(this, rightLob, Path.Op.UNION)
            op(this, baseFill, Path.Op.UNION)
        }
    }
}
