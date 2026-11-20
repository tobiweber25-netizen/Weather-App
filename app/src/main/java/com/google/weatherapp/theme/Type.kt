package com.google.weatherapp.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.weatherapp.R

val GeometricSansFont = FontFamily(
    Font(R.font.plus_jakarta_sans_light, FontWeight.Light),
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold)
)

val EditorialSerifFont = FontFamily(
    Font(R.font.newsreader_regular, FontWeight.Normal, FontStyle.Normal),
    Font(R.font.newsreader_medium, FontWeight.Medium, FontStyle.Normal),
    Font(R.font.newsreader_medium, FontWeight.SemiBold, FontStyle.Normal),
    Font(R.font.newsreader_italic, FontWeight.Normal, FontStyle.Italic)
)

// Backwards-compatible alias
val PixelFont = GeometricSansFont

val AlmanacWordmarkTextStyle = TextStyle(
    fontFamily = EditorialSerifFont,
    fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Italic,
    fontSize = 23.sp,
    lineHeight = 28.sp,
    letterSpacing = 0.2.sp
)

val EditorialStoryTextStyle = TextStyle(
    fontFamily = EditorialSerifFont,
    fontWeight = FontWeight.Normal,
    fontStyle = FontStyle.Italic,
    fontSize = 15.5.sp,
    lineHeight = 23.sp,
    letterSpacing = 0.1.sp
)

val HeroTemperatureTextStyle = TextStyle(
    fontFamily = GeometricSansFont,
    fontWeight = FontWeight.Light,
    fontSize = 96.sp,
    lineHeight = 96.sp,
    letterSpacing = (-4.5).sp,
    fontFeatureSettings = "tnum"
)

val HeroDegreeTextStyle = TextStyle(
    fontFamily = GeometricSansFont,
    fontWeight = FontWeight.Light,
    fontSize = 54.sp,
    lineHeight = 56.sp,
    letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Light,
        fontSize = 56.sp,
        lineHeight = 64.sp,
        letterSpacing = (-1.8).sp,
        fontFeatureSettings = "tnum"
    ),
    displayMedium = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Light,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.8).sp,
        fontFeatureSettings = "tnum"
    ),
    displaySmall = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineLarge = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.4).sp,
        fontFeatureSettings = "tnum"
    ),
    headlineMedium = TextStyle(
        fontFamily = EditorialSerifFont,
        fontWeight = FontWeight.Normal,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.2).sp
    ),
    titleLarge = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        fontFeatureSettings = "tnum"
    ),
    titleMedium = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
        fontFeatureSettings = "tnum"
    ),
    titleSmall = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyLarge = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum"
    ),
    bodyMedium = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        letterSpacing = 0.1.sp,
        fontFeatureSettings = "tnum"
    ),
    bodySmall = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.15.sp,
        fontFeatureSettings = "tnum"
    ),
    labelLarge = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.2.sp,
        fontFeatureSettings = "tnum"
    ),
    labelMedium = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
        fontFeatureSettings = "tnum"
    ),
    labelSmall = TextStyle(
        fontFamily = GeometricSansFont,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.35.sp,
        fontFeatureSettings = "tnum"
    )
)
