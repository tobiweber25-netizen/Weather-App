package com.google.weatherapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.google.weatherapp.theme.*

/**
 * Sky-luminance context provided by WeatherScreen so frosted glass cards adapt
 * to the Living Sky behind them rather than OS dark/light mode.
 */
val LocalSkyIsBright = compositionLocalOf { false }

/**
 * Luminous frosted glass surface inspired by Almanac's backlit app icon:
 * - Sky-adaptive vertical luminance fill for guaranteed readability
 * - Directional top-down specular bevel rim border
 * - Delicate inner top catch-light sheen
 */
@Composable
fun Modifier.luminousGlass(
    cornerRadius: Dp = CornerTokens.Large,
    elevated: Boolean = false,
    accentBorderColor: Color? = null,
    overrideTopColor: Color? = null,
    overrideBottomColor: Color? = null,
    borderWidth: Dp = BorderTokens.Hairline
): Modifier {
    val isBrightSky = LocalSkyIsBright.current
    val shape: Shape = RoundedCornerShape(cornerRadius)

    val topFill = overrideTopColor ?: when {
        elevated -> GlassSurfaceElevatedTop
        isBrightSky -> GlassSurfaceBrightTop
        else -> GlassSurfaceDeepTop
    }
    val bottomFill = overrideBottomColor ?: when {
        elevated -> GlassSurfaceElevatedBottom
        isBrightSky -> GlassSurfaceBrightBottom
        else -> GlassSurfaceDeepBottom
    }

    val borderBrush = if (accentBorderColor != null) {
        Brush.verticalGradient(
            colors = listOf(
                accentBorderColor,
                accentBorderColor.copy(alpha = 0.45f),
                accentBorderColor.copy(alpha = 0.18f)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                GlassRimSpecularTop,
                GlassRimSpecularMid,
                GlassRimSpecularBottom
            )
        )
    }

    return this
        .clip(shape)
        .background(
            brush = Brush.verticalGradient(
                colors = listOf(topFill, bottomFill)
            )
        )
        .drawWithContent {
            drawContent()
            // Delicate top-down inner specular highlight wash (simulates glass refraction)
            val sheenHeight = (size.height * 0.24f).coerceAtMost(size.height)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        GlassInnerTopSheen,
                        Transparent
                    ),
                    startY = 0f,
                    endY = sheenHeight
                )
            )
            // Hairline inner top glint centered along top edge
            val insetX = size.width * 0.08f
            if (size.width > insetX * 2f) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Transparent,
                            PureWhite.copy(alpha = if (elevated) 0.28f else 0.20f),
                            Transparent
                        ),
                        startX = insetX,
                        endX = size.width - insetX
                    ),
                    start = Offset(insetX, 1f),
                    end = Offset(size.width - insetX, 1f),
                    strokeWidth = 1.2f
                )
            }
        }
        .border(
            width = borderWidth,
            brush = borderBrush,
            shape = shape
        )
}
