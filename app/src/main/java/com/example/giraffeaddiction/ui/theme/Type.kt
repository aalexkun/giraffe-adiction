package com.example.giraffeaddiction.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.example.giraffeaddiction.R

/**
 * Three faces, bundled rather than downloaded so the first frame is the right one.
 *
 * [Display] is Instrument Serif: a tall, light, slightly old-fashioned serif that gives the big
 * status lines their calm. [Body] is Public Sans, the same plain face the other giraffe apps use.
 * [Utility] is JetBrains Mono, for the places where digits have to line up: timestamps, hours,
 * the event log.
 */
val Display = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Body = FontFamily(
    Font(R.font.public_sans_regular, FontWeight.Normal),
    Font(R.font.public_sans_semibold, FontWeight.SemiBold),
)

val Utility = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
)

private val Trim = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun body(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = Body,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    lineHeightStyle = Trim,
)

private fun display(size: Int, line: Int, tracking: Float = -0.01f) = TextStyle(
    fontFamily = Display,
    fontWeight = FontWeight.Normal,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = (size * tracking).sp,
    lineHeightStyle = Trim,
)

val AppTypography = Typography(
    displayLarge = display(48, 52, -0.015f),
    displayMedium = display(40, 44, -0.015f),
    displaySmall = display(32, 36),
    headlineLarge = display(28, 32),
    headlineMedium = display(24, 28),
    headlineSmall = display(21, 26),
    titleLarge = display(19, 24, 0f),

    titleMedium = body(16, 22, FontWeight.SemiBold),
    titleSmall = body(14, 19, FontWeight.SemiBold),
    bodyLarge = body(16, 24),
    bodyMedium = body(15, 22),
    bodySmall = body(13, 19),
    labelLarge = body(14, 18, FontWeight.SemiBold),
    labelMedium = body(12, 16, FontWeight.SemiBold),
    labelSmall = body(11, 15),
)

/** The letter-spaced caps that sit above every section and screen. */
val EyebrowStyle = TextStyle(
    fontFamily = Utility,
    fontSize = 11.sp,
    letterSpacing = 2.sp,
    lineHeight = 16.sp,
)

/** Anywhere digits line up. */
val MonoStyle = TextStyle(
    fontFamily = Utility,
    fontSize = 12.sp,
    lineHeight = 16.sp,
)
