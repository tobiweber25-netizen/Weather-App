package com.google.weatherapp.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.google.weatherapp.data.model.BentoTelemetry
import com.google.weatherapp.data.model.DailyItem
import com.google.weatherapp.data.model.HeroWeather
import com.google.weatherapp.data.model.HourlyItem
import com.google.weatherapp.data.model.SevereAlertInfo
import com.google.weatherapp.data.model.SeverityRiskLevel
import com.google.weatherapp.data.model.SkyThemeType
import com.google.weatherapp.data.model.WeatherDashboardData
import com.google.weatherapp.data.model.WeatherEffectType
import com.google.weatherapp.theme.BorderTokens
import com.google.weatherapp.theme.CornerTokens
import com.google.weatherapp.theme.GlassCardBackgroundDark
import com.google.weatherapp.theme.GlassCardBackgroundLight
import com.google.weatherapp.theme.GlassCardBorderDark
import com.google.weatherapp.theme.GlassCardBorderLight
import com.google.weatherapp.theme.GlassCardDividerColor
import com.google.weatherapp.theme.GlassPillHighlight
import com.google.weatherapp.theme.PureWhite
import com.google.weatherapp.theme.RainDropCyan
import com.google.weatherapp.theme.SkyContrastScrimBottomDark
import com.google.weatherapp.theme.SkyDayClearEnd
import com.google.weatherapp.theme.SkyDayClearMid
import com.google.weatherapp.theme.SkyDayClearStart
import com.google.weatherapp.theme.SkyNightClearEnd
import com.google.weatherapp.theme.SkyNightClearMid
import com.google.weatherapp.theme.SkyNightClearStart
import com.google.weatherapp.theme.SkyNightOvercastEnd
import com.google.weatherapp.theme.SkyNightOvercastMid
import com.google.weatherapp.theme.SkyNightOvercastStart
import com.google.weatherapp.theme.SkyNightRainEnd
import com.google.weatherapp.theme.SkyNightRainMid
import com.google.weatherapp.theme.SkyNightRainStart
import com.google.weatherapp.theme.SkyNightSnowEnd
import com.google.weatherapp.theme.SkyNightSnowMid
import com.google.weatherapp.theme.SkyNightSnowStart
import com.google.weatherapp.theme.SkyNightStormEnd
import com.google.weatherapp.theme.SkyNightStormMid
import com.google.weatherapp.theme.SkyNightStormStart
import com.google.weatherapp.theme.SkyOvercastEnd
import com.google.weatherapp.theme.SkyOvercastMid
import com.google.weatherapp.theme.SkyOvercastStart
import com.google.weatherapp.theme.SkyRainEnd
import com.google.weatherapp.theme.SkyRainMid
import com.google.weatherapp.theme.SkyRainStart
import com.google.weatherapp.theme.SkySnowEnd
import com.google.weatherapp.theme.SkySnowMid
import com.google.weatherapp.theme.SkySnowStart
import com.google.weatherapp.theme.SkyStormEnd
import com.google.weatherapp.theme.SkyStormMid
import com.google.weatherapp.theme.SkyStormStart
import com.google.weatherapp.theme.SkyTextMuted
import com.google.weatherapp.theme.SkyTextPrimary
import com.google.weatherapp.theme.SkyTextSecondary
import com.google.weatherapp.theme.SpacingTokens
import com.google.weatherapp.theme.SunCoreGold
import com.google.weatherapp.theme.TempBarCoolColor
import com.google.weatherapp.theme.TempBarHotColor
import com.google.weatherapp.theme.TempBarMildColor
import com.google.weatherapp.theme.TempBarTrackColor
import com.google.weatherapp.theme.TempBarWarmColor
import com.google.weatherapp.ui.resolveActiveEffect
import com.google.weatherapp.ui.resolveActiveEffectForDay
import com.google.weatherapp.ui.resolveActiveEffectForHourly

object WeatherWidgetRenderer {

    fun renderWidgetBitmap(
        context: Context,
        kind: WidgetKind,
        style: WidgetBackgroundStyle,
        data: WeatherDashboardData?,
        widthDp: Int = kind.defaultWidthDp,
        heightDp: Int = kind.defaultHeightDp
    ): Bitmap {
        val weather = data ?: samplePreviewDashboardData()
        val density = context.resources.displayMetrics.density.coerceIn(1.5f, 3.0f)

        val safeWidthDp = widthDp.coerceIn(110, 460)
        val safeHeightDp = heightDp.coerceIn(96, 460)
        val widthPx = (safeWidthDp * density).toInt().coerceAtLeast(200)
        val heightPx = (safeHeightDp * density).toInt().coerceAtLeast(180)

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val w = widthPx.toFloat()
        val h = heightPx.toFloat()
        val effect = resolveActiveEffect(weather.hero)

        drawCardBackground(
            canvas = canvas,
            w = w,
            h = h,
            density = density,
            effect = effect,
            style = style,
            isDaytime = weather.hero.isDaytime
        )

        val isTranslucent = style == WidgetBackgroundStyle.TRANSLUCENT
        when (kind) {
            WidgetKind.TODAY_COMPACT -> drawTodayCompact(canvas, w, h, density, weather, effect, isTranslucent)
            WidgetKind.TODAY_HYBRID -> drawTodayHybrid(canvas, w, h, density, weather, effect, isTranslucent)
            WidgetKind.HOURLY_FORECAST -> drawHourlyForecast(canvas, w, h, density, weather, effect, isTranslucent)
            WidgetKind.DAILY_FORECAST -> drawDailyForecast(canvas, w, h, density, weather, effect, isTranslucent)
        }

        return bitmap
    }

    private fun drawCardBackground(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        effect: WeatherEffectType,
        style: WidgetBackgroundStyle,
        isDaytime: Boolean = true
    ) {
        val cornerPx = CornerTokens.Large.value * density
        val inset = BorderTokens.Hairline.value * density * 0.5f
        val rect = RectF(inset, inset, w - inset, h - inset)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.FILL
        }

        if (style == WidgetBackgroundStyle.LIVING_SKY) {
            val (topC, midC, botC) = resolveSkyPalette(effect, isDaytime)
            bgPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h,
                intArrayOf(topC.toArgb(), midC.toArgb(), botC.toArgb()),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)

            // Subtle bottom atmospheric contrast scrim for crisp bottom typography
            bgPaint.shader = LinearGradient(
                0f,
                h * 0.45f,
                0f,
                h,
                0x00000000,
                SkyContrastScrimBottomDark.toArgb(),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
            bgPaint.shader = null
        } else {
            // Fully Translucent mode: ultra-sheer glass veil so wallpaper shines through
            bgPaint.shader = LinearGradient(
                0f,
                0f,
                0f,
                h,
                GlassCardBackgroundLight.copy(alpha = 0.14f).toArgb(),
                GlassCardBackgroundDark.copy(alpha = 0.22f).toArgb(),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)
            bgPaint.shader = null
        }

        // Specular hairline rim border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.style = Paint.Style.STROKE
            strokeWidth = BorderTokens.Hairline.value * density
            color = if (style == WidgetBackgroundStyle.LIVING_SKY) {
                GlassCardBorderLight.toArgb()
            } else {
                GlassCardBorderDark.toArgb()
            }
        }
        canvas.drawRoundRect(rect, cornerPx, cornerPx, borderPaint)
    }

    /**
     * 1. Today Compact (2x2)
     */
    private fun drawTodayCompact(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        weather: WeatherDashboardData,
        effect: WeatherEffectType,
        isTranslucent: Boolean
    ) {
        val pad = SpacingTokens.Base.value * density
        val hero = weather.hero

        // Top-right dimensional icon
        val iconSize = minOf(w, h) * 0.36f
        val iconCx = w - pad - iconSize * 0.46f
        val iconCy = pad + iconSize * 0.46f
        WeatherWidgetIconDrawer.drawIcon(canvas, effect, iconCx, iconCy, iconSize)

        // Top-left location pill
        val maxLocWidth = (iconCx - iconSize * 0.52f - pad).coerceAtLeast(w * 0.35f)
        drawLocationPill(
            canvas = canvas,
            x = pad,
            y = pad,
            maxWidth = maxLocWidth,
            density = density,
            locationName = hero.locationName,
            isTranslucent = isTranslucent
        )

        // Footer High / Low
        val footerPaint = buildTextPaint(
            sp = 12.5f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val footerY = h - pad
        val hlText = "H:${hero.highDegrees}°  L:${hero.lowDegrees}°"
        canvas.drawText(hlText, pad, footerY, footerPaint)

        // Condition label above footer
        val condPaint = buildTextPaint(
            sp = 14f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val condY = footerY - (SpacingTokens.Medium.value + 6f) * density
        val condTruncated = TextUtils.ellipsize(
            hero.conditionText,
            condPaint,
            w - pad * 2,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(condTruncated, pad, condY, condPaint)

        // Giant thin editorial temperature above condition
        val tempSp = (minOf(w, h) / density * 0.31f).coerceIn(38f, 58f)
        val tempPaint = buildTextPaint(
            sp = tempSp,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-light", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val tempY = condY - SpacingTokens.Medium.value * density - 4f * density
        canvas.drawText("${hero.tempDegrees}°", pad, tempY, tempPaint)
    }

    /**
     * 2. Today + Next Hours (4x2 Hybrid)
     */
    private fun drawTodayHybrid(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        weather: WeatherDashboardData,
        effect: WeatherEffectType,
        isTranslucent: Boolean
    ) {
        val pad = SpacingTokens.Base.value * density
        val hero = weather.hero

        val splitX = w * 0.43f
        val leftMaxW = splitX - pad * 1.6f

        // Left side: Location pill at top
        drawLocationPill(
            canvas = canvas,
            x = pad,
            y = pad,
            maxWidth = leftMaxW,
            density = density,
            locationName = hero.locationName,
            isTranslucent = isTranslucent
        )

        // Left side: Dimensional icon tucked next to temperature
        val iconSize = (h * 0.32f).coerceIn(36f * density, 54f * density)
        val tempSp = (h / density * 0.27f).coerceIn(34f, 48f)
        val tempPaint = buildTextPaint(
            sp = tempSp,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-light", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val tempText = "${hero.tempDegrees}°"
        val tempWidth = tempPaint.measureText(tempText)

        val footerY = h - pad
        val footerPaint = buildTextPaint(
            sp = 11.5f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val footerText = TextUtils.ellipsize(
            "H:${hero.highDegrees}°  L:${hero.lowDegrees}° • Feels ${hero.feelsLikeDegrees}°",
            footerPaint,
            leftMaxW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(footerText, pad, footerY, footerPaint)

        val condPaint = buildTextPaint(
            sp = 13f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val condY = footerY - 17f * density
        val condText = TextUtils.ellipsize(
            hero.conditionText,
            condPaint,
            leftMaxW,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(condText, pad, condY, condPaint)

        val tempBaselineY = condY - 18f * density
        canvas.drawText(tempText, pad, tempBaselineY, tempPaint)

        val iconCx = (pad + tempWidth + iconSize * 0.55f).coerceAtMost(splitX - iconSize * 0.55f)
        val iconCy = tempBaselineY - tempSp * density * 0.36f
        WeatherWidgetIconDrawer.drawIcon(canvas, effect, iconCx, iconCy, iconSize)

        // Vertical hairline divider
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassCardDividerColor.toArgb()
            strokeWidth = BorderTokens.Hairline.value * density
        }
        canvas.drawLine(splitX, pad, splitX, h - pad, divPaint)

        // Right side: Upcoming 4 hours inside a frosted glass sub-panel
        val rightLeft = splitX + SpacingTokens.Medium.value * density
        val rightRight = w - pad
        val rightW = rightRight - rightLeft
        val hours = weather.hourlyList.take(4)
        if (hours.isEmpty()) return

        val colW = rightW / hours.size
        val hourLabelPaint = buildTextPaint(
            sp = 11f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )
        val hourTempPaint = buildTextPaint(
            sp = 14f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )
        val rainPaint = buildTextPaint(
            sp = 10f,
            density = density,
            color = RainDropCyan,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )
        val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassPillHighlight.copy(alpha = if (isTranslucent) 0.14f else 0.22f).toArgb()
            style = Paint.Style.FILL
        }

        hours.forEachIndexed { idx, item ->
            val cx = rightLeft + colW * (idx + 0.5f)
            val isNow = idx == 0 || item.hourLabel.equals("Now", ignoreCase = true)

            if (isNow) {
                val pillRect = RectF(
                    cx - colW * 0.44f,
                    pad,
                    cx + colW * 0.44f,
                    h - pad
                )
                val rPill = CornerTokens.Medium.value * density
                canvas.drawRoundRect(pillRect, rPill, rPill, pillPaint)
            }

            hourLabelPaint.color = (if (isNow) SkyTextPrimary else SkyTextSecondary).toArgb()
            canvas.drawText(
                if (isNow) "Now" else item.hourLabel,
                cx,
                pad + 18f * density,
                hourLabelPaint
            )

            val hEffect = resolveActiveEffectForHourly(item)
            val hIconSz = minOf(colW * 0.72f, h * 0.28f)
            WeatherWidgetIconDrawer.drawIcon(canvas, hEffect, cx, h * 0.50f, hIconSz)

            if (item.rainProbability >= 20) {
                canvas.drawText(
                    "${item.rainProbability}%",
                    cx,
                    h - pad - 20f * density,
                    rainPaint
                )
            }

            canvas.drawText(
                "${item.tempDegrees}°",
                cx,
                h - pad - 5f * density,
                hourTempPaint
            )
        }
    }

    /**
     * 3. Hourly Forecast (4x2 with Bezier Temperature Curve)
     */
    private fun drawHourlyForecast(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        weather: WeatherDashboardData,
        effect: WeatherEffectType,
        isTranslucent: Boolean
    ) {
        val pad = SpacingTokens.Base.value * density
        val hero = weather.hero

        // Compact Header Row
        val headerBottomY = pad + 26f * density
        drawLocationPill(
            canvas = canvas,
            x = pad,
            y = pad - 2f * density,
            maxWidth = w * 0.52f,
            density = density,
            locationName = hero.locationName,
            isTranslucent = isTranslucent
        )

        // Right-aligned current summary in header
        val headerTempPaint = buildTextPaint(
            sp = 18f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.RIGHT,
            isTranslucent = isTranslucent
        )
        val tempStr = "${hero.tempDegrees}°"
        canvas.drawText(tempStr, w - pad, pad + 16f * density, headerTempPaint)
        val tempW = headerTempPaint.measureText(tempStr)

        val headerIconSz = 24f * density
        val headerIconCx = w - pad - tempW - headerIconSz * 0.65f
        WeatherWidgetIconDrawer.drawIcon(canvas, effect, headerIconCx, pad + 10f * density, headerIconSz)

        val headerCondPaint = buildTextPaint(
            sp = 11.5f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            align = Paint.Align.RIGHT,
            isTranslucent = isTranslucent
        )
        val condStr = "${hero.conditionText} • H:${hero.highDegrees}° L:${hero.lowDegrees}°"
        val maxCondW = (headerIconCx - headerIconSz * 0.7f - (pad + w * 0.45f)).coerceAtLeast(60f * density)
        val fitCond = TextUtils.ellipsize(condStr, headerCondPaint, maxCondW, TextUtils.TruncateAt.END).toString()
        canvas.drawText(
            fitCond,
            headerIconCx - headerIconSz * 0.65f,
            pad + 14f * density,
            headerCondPaint
        )

        // Divider
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassCardDividerColor.toArgb()
            strokeWidth = BorderTokens.Hairline.value * density
        }
        canvas.drawLine(pad, headerBottomY, w - pad, headerBottomY, divPaint)

        // 6-hour strip
        val hours = weather.hourlyList.take(6)
        if (hours.isEmpty()) return

        val stripTop = headerBottomY + 8f * density
        val stripBottom = h - pad
        val stripH = (stripBottom - stripTop).coerceAtLeast(60f * density)
        val colW = (w - pad * 2) / hours.size

        val hourLabelPaint = buildTextPaint(
            sp = 11f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )
        val tempReadoutPaint = buildTextPaint(
            sp = 13.5f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )
        val rainPaint = buildTextPaint(
            sp = 9.5f,
            density = density,
            color = RainDropCyan,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.CENTER,
            isTranslucent = isTranslucent
        )

        val temps = hours.map { it.tempDegrees.toFloat() }
        val minT = temps.minOrNull() ?: 15f
        val maxT = temps.maxOrNull() ?: 25f
        val rangeT = (maxT - minT).takeIf { it > 0.5f } ?: 1f

        val curveTop = stripTop + stripH * 0.54f
        val curveBottom = stripTop + stripH * 0.72f
        fun tempToY(t: Float): Float {
            val ratio = ((t - minT) / rangeT).coerceIn(0f, 1f)
            return curveBottom - ratio * (curveBottom - curveTop)
        }

        // Continuous Bezier temperature curve across all 6 columns
        val curvePath = Path()
        hours.forEachIndexed { idx, item ->
            val cx = pad + colW * (idx + 0.5f)
            val cy = tempToY(item.tempDegrees.toFloat())
            if (idx == 0) {
                curvePath.moveTo(cx, cy)
            } else {
                val prevCx = pad + colW * (idx - 0.5f)
                val prevCy = tempToY(hours[idx - 1].tempDegrees.toFloat())
                val midX = (prevCx + cx) * 0.5f
                curvePath.cubicTo(midX, prevCy, midX, cy, cx, cy)
            }
        }
        val curvePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = BorderTokens.Thick.value * density
            strokeCap = Paint.Cap.ROUND
            color = SunCoreGold.copy(alpha = 0.85f).toArgb()
        }
        canvas.drawPath(curvePath, curvePaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = PureWhite.toArgb()
        }

        hours.forEachIndexed { idx, item ->
            val cx = pad + colW * (idx + 0.5f)
            val isNow = idx == 0 || item.hourLabel.equals("Now", ignoreCase = true)

            hourLabelPaint.color = (if (isNow) SkyTextPrimary else SkyTextSecondary).toArgb()
            canvas.drawText(
                if (isNow) "Now" else item.hourLabel,
                cx,
                stripTop + 12f * density,
                hourLabelPaint
            )

            val iconSz = minOf(colW * 0.58f, stripH * 0.30f)
            WeatherWidgetIconDrawer.drawIcon(
                canvas = canvas,
                effectType = resolveActiveEffectForHourly(item),
                cx = cx,
                cy = stripTop + stripH * 0.34f,
                sizePx = iconSz
            )

            val cy = tempToY(item.tempDegrees.toFloat())
            canvas.drawCircle(cx, cy, 3f * density, dotPaint)

            if (item.rainProbability >= 20) {
                canvas.drawText(
                    "${item.rainProbability}%",
                    cx,
                    stripBottom - 13f * density,
                    rainPaint
                )
            }
            canvas.drawText(
                "${item.tempDegrees}°",
                cx,
                stripBottom,
                tempReadoutPaint
            )
        }
    }

    /**
     * 4. 5-Day Forecast (4x3 with Multi-Stop Thermal Spectrum Range Bars)
     */
    private fun drawDailyForecast(
        canvas: Canvas,
        w: Float,
        h: Float,
        density: Float,
        weather: WeatherDashboardData,
        effect: WeatherEffectType,
        isTranslucent: Boolean
    ) {
        val pad = SpacingTokens.Base.value * density
        val hero = weather.hero

        // Header Section
        val headerHeight = 46f * density
        drawLocationPill(
            canvas = canvas,
            x = pad,
            y = pad - 2f * density,
            maxWidth = w * 0.55f,
            density = density,
            locationName = hero.locationName,
            isTranslucent = isTranslucent
        )

        val condSubPaint = buildTextPaint(
            sp = 12f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val condStr = TextUtils.ellipsize(
            "${hero.conditionText} • H:${hero.highDegrees}° L:${hero.lowDegrees}°",
            condSubPaint,
            w * 0.62f,
            TextUtils.TruncateAt.END
        ).toString()
        canvas.drawText(condStr, pad, pad + 36f * density, condSubPaint)

        // Header Right: Current Temp + Dimensional Icon
        val headerTempPaint = buildTextPaint(
            sp = 28f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-light", Typeface.NORMAL),
            align = Paint.Align.RIGHT,
            isTranslucent = isTranslucent
        )
        val tempStr = "${hero.tempDegrees}°"
        canvas.drawText(tempStr, w - pad, pad + 29f * density, headerTempPaint)
        val tempW = headerTempPaint.measureText(tempStr)

        val headerIconSz = 34f * density
        WeatherWidgetIconDrawer.drawIcon(
            canvas = canvas,
            effectType = effect,
            cx = w - pad - tempW - headerIconSz * 0.58f,
            cy = pad + 18f * density,
            sizePx = headerIconSz
        )

        val divY = pad + headerHeight
        val divPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = GlassCardDividerColor.toArgb()
            strokeWidth = BorderTokens.Hairline.value * density
        }
        canvas.drawLine(pad, divY, w - pad, divY, divPaint)

        // 5 Daily Rows
        val days = weather.dailyList.take(5)
        if (days.isEmpty()) return

        val listTop = divY + 6f * density
        val listBottom = h - pad * 0.75f
        val rowH = (listBottom - listTop) / days.size

        val globalMin = days.minOfOrNull { it.minTemp } ?: 10
        val globalMax = days.maxOfOrNull { it.maxTemp } ?: 28
        val totalRange = (globalMax - globalMin).coerceAtLeast(1).toFloat()

        val dayLabelPaint = buildTextPaint(
            sp = 13f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            isTranslucent = isTranslucent
        )
        val rainPaint = buildTextPaint(
            sp = 10.5f,
            density = density,
            color = RainDropCyan,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            isTranslucent = isTranslucent
        )
        val minTempPaint = buildTextPaint(
            sp = 13f,
            density = density,
            color = SkyTextSecondary,
            weight = Typeface.create("sans-serif-medium", Typeface.NORMAL),
            align = Paint.Align.RIGHT,
            isTranslucent = isTranslucent
        )
        val maxTempPaint = buildTextPaint(
            sp = 13f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            align = Paint.Align.RIGHT,
            isTranslucent = isTranslucent
        )

        val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = TempBarTrackColor.toArgb()
        }
        val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        val barRight = w - pad - 30f * density
        val barW = (w * 0.28f).coerceIn(64f * density, 110f * density)
        val barLeft = barRight - barW
        val barH = 5.5f * density

        barFillPaint.shader = LinearGradient(
            barLeft,
            0f,
            barRight,
            0f,
            intArrayOf(
                TempBarCoolColor.toArgb(),
                TempBarMildColor.toArgb(),
                TempBarWarmColor.toArgb(),
                TempBarHotColor.toArgb()
            ),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )

        days.forEachIndexed { idx, day ->
            val rowCenterY = listTop + rowH * (idx + 0.5f)
            val textBaselineY = rowCenterY + 4.5f * density
            val isToday = idx == 0 || day.dayLabel.equals("Today", ignoreCase = true)

            dayLabelPaint.typeface = Typeface.create(
                "sans-serif-medium",
                if (isToday) Typeface.BOLD else Typeface.NORMAL
            )
            val dayName = if (isToday) "Today" else day.dayLabel
            canvas.drawText(dayName, pad, textBaselineY, dayLabelPaint)

            // Weather icon + optional rain %
            val iconCx = pad + w * 0.30f
            val iconSz = minOf(rowH * 0.74f, 24f * density)
            WeatherWidgetIconDrawer.drawIcon(
                canvas = canvas,
                effectType = resolveActiveEffectForDay(day),
                cx = iconCx,
                cy = rowCenterY,
                sizePx = iconSz
            )

            if (day.rainProbability >= 20) {
                canvas.drawText(
                    "${day.rainProbability}%",
                    iconCx + iconSz * 0.62f,
                    textBaselineY - 1f * density,
                    rainPaint
                )
            }

            // Min temp
            canvas.drawText("${day.minTemp}°", barLeft - 8f * density, textBaselineY, minTempPaint)

            // Thermal range bar track
            val trackRect = RectF(
                barLeft,
                rowCenterY - barH * 0.5f,
                barRight,
                rowCenterY + barH * 0.5f
            )
            canvas.drawRoundRect(trackRect, barH * 0.5f, barH * 0.5f, barTrackPaint)

            // Active thermal spectrum segment
            val startRatio = ((day.minTemp - globalMin) / totalRange).coerceIn(0f, 1f)
            val endRatio = ((day.maxTemp - globalMin) / totalRange).coerceIn(0f, 1f)
            val segStart = barLeft + barW * startRatio
            val segEnd = (barLeft + barW * endRatio).coerceAtLeast(segStart + barH)
            val activeRect = RectF(
                segStart,
                rowCenterY - barH * 0.5f,
                segEnd,
                rowCenterY + barH * 0.5f
            )
            canvas.drawRoundRect(activeRect, barH * 0.5f, barH * 0.5f, barFillPaint)

            // Max temp
            canvas.drawText("${day.maxTemp}°", w - pad, textBaselineY, maxTempPaint)

            if (idx < days.lastIndex) {
                val rDivY = listTop + rowH * (idx + 1)
                canvas.drawLine(pad, rDivY, w - pad, rDivY, divPaint)
            }
        }
    }

    private fun drawLocationPill(
        canvas: Canvas,
        x: Float,
        y: Float,
        maxWidth: Float,
        density: Float,
        locationName: String,
        isTranslucent: Boolean
    ) {
        val textPaint = buildTextPaint(
            sp = 11f,
            density = density,
            color = SkyTextPrimary,
            weight = Typeface.create("sans-serif-medium", Typeface.BOLD),
            isTranslucent = isTranslucent
        ).apply {
            letterSpacing = 0.06f
        }

        val horizontalPad = 10f * density
        val pillH = 22f * density
        val label = TextUtils.ellipsize(
            locationName.uppercase(),
            textPaint,
            (maxWidth - horizontalPad * 2).coerceAtLeast(40f * density),
            TextUtils.TruncateAt.END
        ).toString()

        val textW = textPaint.measureText(label)
        val pillW = (textW + horizontalPad * 2).coerceAtMost(maxWidth)
        val rect = RectF(x, y, x + pillW, y + pillH)

        val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = GlassCardBackgroundLight.copy(alpha = if (isTranslucent) 0.22f else 0.18f).toArgb()
        }
        val pillBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = BorderTokens.Hairline.value * density
            color = GlassCardBorderLight.toArgb()
        }
        canvas.drawRoundRect(rect, pillH * 0.5f, pillH * 0.5f, pillFill)
        canvas.drawRoundRect(rect, pillH * 0.5f, pillH * 0.5f, pillBorder)

        val textY = y + pillH * 0.68f
        canvas.drawText(label, x + horizontalPad, textY, textPaint)
    }

    private fun buildTextPaint(
        sp: Float,
        density: Float,
        color: Color,
        weight: Typeface,
        align: Paint.Align = Paint.Align.LEFT,
        isTranslucent: Boolean = false
    ): TextPaint {
        return TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sp * density
            this.color = color.toArgb()
            typeface = weight
            textAlign = align
            if (isTranslucent) {
                setShadowLayer(4f * density, 0f, 1.5f * density, 0x88090F1E.toInt())
            } else {
                setShadowLayer(2f * density, 0f, 1f * density, 0x33090F1E)
            }
        }
    }

    internal fun resolveSkyPalette(effectType: WeatherEffectType, isDaytime: Boolean = true): Triple<Color, Color, Color> {
        if (effectType == WeatherEffectType.CLEAR_NIGHT || !isDaytime) {
            return when (effectType) {
                WeatherEffectType.CLOUDY -> Triple(SkyNightOvercastStart, SkyNightOvercastMid, SkyNightOvercastEnd)
                WeatherEffectType.RAIN -> Triple(SkyNightRainStart, SkyNightRainMid, SkyNightRainEnd)
                WeatherEffectType.SNOW -> Triple(SkyNightSnowStart, SkyNightSnowMid, SkyNightSnowEnd)
                WeatherEffectType.THUNDERSTORM -> Triple(SkyNightStormStart, SkyNightStormMid, SkyNightStormEnd)
                else -> Triple(SkyNightClearStart, SkyNightClearMid, SkyNightClearEnd)
            }
        }
        return when (effectType) {
            WeatherEffectType.CLEAR_DAY -> Triple(SkyDayClearStart, SkyDayClearMid, SkyDayClearEnd)
            WeatherEffectType.CLOUDY -> Triple(SkyOvercastStart, SkyOvercastMid, SkyOvercastEnd)
            WeatherEffectType.RAIN -> Triple(SkyRainStart, SkyRainMid, SkyRainEnd)
            WeatherEffectType.SNOW -> Triple(SkySnowStart, SkySnowMid, SkySnowEnd)
            WeatherEffectType.THUNDERSTORM -> Triple(SkyStormStart, SkyStormMid, SkyStormEnd)
            else -> Triple(SkyDayClearStart, SkyDayClearMid, SkyDayClearEnd)
        }
    }

    fun samplePreviewDashboardData(): WeatherDashboardData {
        return WeatherDashboardData(
            hero = HeroWeather(
                tempDegrees = 23,
                feelsLikeDegrees = 24,
                highDegrees = 25,
                lowDegrees = 16,
                conditionText = "Mostly Sunny",
                iconUri = null,
                isDaytime = true,
                locationName = "Cape Town",
                skyTheme = SkyThemeType.CLEAR_DAY
            ),
            alert = SevereAlertInfo(
                riskLevel = SeverityRiskLevel.LOW,
                thunderstormProbability = 0,
                title = "",
                subtitle = "",
                isVisible = false
            ),
            hourlyList = listOf(
                HourlyItem("Now", 23, 0, null, true, "Mostly Sunny"),
                HourlyItem("15:00", 24, 10, null, true, "Sunny"),
                HourlyItem("16:00", 25, 15, null, true, "Partly Cloudy"),
                HourlyItem("17:00", 23, 25, null, true, "Partly Cloudy"),
                HourlyItem("18:00", 20, 10, null, false, "Clear"),
                HourlyItem("19:00", 18, 0, null, false, "Clear")
            ),
            dailyList = listOf(
                DailyItem("Today", "10/19", 16, 25, 15, null, "Mostly Sunny", "", ""),
                DailyItem("Tue", "10/20", 15, 22, 45, null, "Light Rain", "", ""),
                DailyItem("Wed", "10/21", 14, 21, 20, null, "Partly Cloudy", "", ""),
                DailyItem("Thu", "10/22", 16, 26, 0, null, "Sunny", "", ""),
                DailyItem("Fri", "10/23", 17, 27, 10, null, "Sunny", "", "")
            ),
            telemetry = BentoTelemetry(
                windSpeedKmH = 14,
                windDirection = "NW",
                windGustKmH = 22,
                precipitationMm = 0.0,
                uvIndex = 6,
                uvCategory = "High",
                humidityPercent = 58,
                dewPointDegrees = 14,
                pressureHpa = 1016,
                sunriseTime = "06:04",
                sunsetTime = "19:02"
            )
        )
    }
}
