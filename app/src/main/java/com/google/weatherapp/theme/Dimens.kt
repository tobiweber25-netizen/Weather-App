package com.google.weatherapp.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mobile-first responsive design tokens.
 * Strict ban on layout magic numbers.
 */
object SpacingTokens {
    val None: Dp = 0.dp
    val Micro: Dp = 2.dp
    val ExtraSmall: Dp = 4.dp
    val Small: Dp = 8.dp
    val Compact: Dp = 10.dp
    val Medium: Dp = 12.dp
    val Base: Dp = 16.dp
    val Large: Dp = 20.dp
    val ExtraLarge: Dp = 24.dp
    val Huge: Dp = 32.dp
    val Jumbo: Dp = 48.dp
    val ContentPaddingBottom: Dp = 80.dp
}

object CornerTokens {
    val None: Dp = 0.dp
    val ExtraSmall: Dp = 8.dp
    val Small: Dp = 12.dp
    val Medium: Dp = 18.dp
    val Large: Dp = 24.dp
    val ExtraLarge: Dp = 32.dp
    val Pill: Dp = 999.dp
}

object IconSizeTokens {
    val StatusDot: Dp = 6.dp
    val Micro: Dp = 14.dp
    val Small: Dp = 18.dp
    val Medium: Dp = 26.dp
    val Large: Dp = 34.dp
    val ExtraLarge: Dp = 48.dp
    val Hero: Dp = 76.dp
    val HeroDisplay: Dp = 108.dp
}

object ElevationTokens {
    val Flat: Dp = 0.dp
    val Level1: Dp = 2.dp
    val Level2: Dp = 4.dp
    val Level3: Dp = 8.dp
}

object BorderTokens {
    val Hairline: Dp = 1.dp
    val Subtle: Dp = 1.5.dp
    val Thick: Dp = 2.dp
}

object ComponentTokens {
    val HourlyCardWidth: Dp = 68.dp
    val HourlyCardHeight: Dp = 148.dp
    val HourlyCurveCanvasHeight: Dp = 28.dp
    val PrecipNowcastBarHeight: Dp = 44.dp
    val TempBarHeight: Dp = 6.dp
    val TempBarWidth: Dp = 84.dp
    val DailyItemDayLabelWidth: Dp = 80.dp
    val DailyRainColumnWidth: Dp = 68.dp
    val DailyTempLabelWidth: Dp = 36.dp
    val BentoCardMinHeight: Dp = 152.dp
    val BentoGaugeHeight: Dp = 36.dp
    val BentoGaugeStroke: Dp = 5.dp
    val BentoSparklineHeight: Dp = 84.dp
    val AlertSparklineHeight: Dp = 64.dp
    val HeroCardMinHeight: Dp = 220.dp
    val SkeletonPillHeight: Dp = 44.dp
    val SkeletonTextHeight: Dp = 16.dp
    val SkeletonTitleHeight: Dp = 28.dp
    val SkeletonIconSize: Dp = 56.dp
    val LocationSearchMaxHeight: Dp = 420.dp
}

object EffectTokens {
    val RainStrokeWidth: Dp = 1.2.dp
    val RainLengthMin: Dp = 10.dp
    val RainLengthMax: Dp = 20.dp
    val RainSplashRadius: Dp = 2.dp
    val SnowflakeRadiusMin: Dp = 1.5.dp
    val SnowflakeRadiusMax: Dp = 3.5.dp
    val StarRadiusMin: Dp = 0.8.dp
    val StarRadiusMax: Dp = 2.0.dp
    val SunbeamRadius: Dp = 280.dp
    val CloudMistHeight: Dp = 160.dp
    val SunMoteRadiusMin: Dp = 1.2.dp
    val SunMoteRadiusMax: Dp = 2.8.dp
    val LightningBoltWidth: Dp = 2.dp
    val LightningGlowWidth: Dp = 6.dp
    val SunShaftWidth: Dp = 90.dp
    val CloudPuffRadius: Dp = 120.dp

    // Multi-Layer Rain Depth Tokens
    val RainStrokeBg: Dp = 0.6.dp
    val RainStrokeMid: Dp = 0.9.dp
    val RainStrokeFg: Dp = 1.4.dp
    val RainLengthBgMin: Dp = 8.dp
    val RainLengthBgMax: Dp = 14.dp
    val RainLengthMidMin: Dp = 14.dp
    val RainLengthMidMax: Dp = 22.dp
    val RainLengthFgMin: Dp = 22.dp
    val RainLengthFgMax: Dp = 34.dp
    val RainSplashSprayRadius: Dp = 0.8.dp
    val RainRippleRadiusMin: Dp = 2.dp
    val RainRippleRadiusMax: Dp = 8.dp
    val RainHazeHeight: Dp = 140.dp

    // Snow Bokeh & Flutter Tokens
    val SnowflakeBokehRadius: Dp = 6.dp
    val SnowflakeHaloRadius: Dp = 10.dp

    // Starfield & Solar Radiance Tokens
    val StarSpikeLength: Dp = 4.5.dp
    val ShootingStarTrailLength: Dp = 110.dp
    val SunCoronaRadius: Dp = 180.dp
    val SunShaftBaseWidth: Dp = 80.dp

    // Volumetric Cloud Deck Tokens
    val CloudBandWidth: Dp = 320.dp
    val CloudBandHeight: Dp = 140.dp
}

object DayDetailTokens {
    val DayChipWidth: Dp = 82.dp
    val DayChipHeight: Dp = 82.dp
    val BreakdownCardMinHeight: Dp = 148.dp
    val DetailHeroMinHeight: Dp = 130.dp
    val SheetHandleWidth: Dp = 36.dp
    val SheetHandleHeight: Dp = 4.dp
}
