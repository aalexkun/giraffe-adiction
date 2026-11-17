package com.example.giraffeaddiction.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The clay profile, light and dark. Dynamic colour is off on purpose: the palette is tied to the
 * mascot and is not negotiable per wallpaper.
 *
 *  - `primary` is [Clay] with light text on it; on the dark ground it lifts to [NightClay] and
 *    takes dark text instead, which is the only way a red stays readable both ways.
 *  - `tertiary` is [Ember], reached for by name where a small warm accent is wanted.
 *  - `surfaceVariant` and `secondaryContainer` are [Sand], the tint cards and discs sit on.
 */
private val LightScheme = lightColorScheme(
    primary = Clay,
    onPrimary = OnClay,
    primaryContainer = Clay,
    onPrimaryContainer = OnClay,
    secondary = Rust,
    onSecondary = Blush,
    secondaryContainer = Sand,
    onSecondaryContainer = Ink,
    tertiary = Ember,
    onTertiary = OnClay,
    tertiaryContainer = Sand,
    onTertiaryContainer = Ink,
    background = Blush,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Sand,
    onSurfaceVariant = InkMuted,
    surfaceContainer = Paper,
    surfaceContainerLow = Blush,
    surfaceContainerHigh = Sand,
    outline = Rule,
    outlineVariant = Rule,
    error = Danger,
    onError = Blush,
    errorContainer = DangerContainer,
    onErrorContainer = Ink,
    scrim = Ink,
)

private val DarkScheme = darkColorScheme(
    primary = NightClay,
    onPrimary = NightBlush,
    primaryContainer = NightClay,
    onPrimaryContainer = NightBlush,
    secondary = NightTerracotta,
    onSecondary = NightBlush,
    secondaryContainer = NightSand,
    onSecondaryContainer = NightInk,
    tertiary = Ember,
    onTertiary = NightBlush,
    tertiaryContainer = NightSand,
    onTertiaryContainer = NightInk,
    background = NightBlush,
    onBackground = NightInk,
    surface = NightPaper,
    onSurface = NightInk,
    surfaceVariant = NightSand,
    onSurfaceVariant = NightInkMuted,
    surfaceContainer = NightPaper,
    surfaceContainerLow = NightBlush,
    surfaceContainerHigh = NightSand,
    outline = NightRule,
    outlineVariant = NightRule,
    error = Color(0xFFE08A76),
    onError = NightBlush,
    errorContainer = NightDangerContainer,
    onErrorContainer = NightInk,
    scrim = Color.Black,
)

@Composable
fun GiraffeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
}

/** Shades that are not part of the Material scheme but every screen reaches for. */
object Tones {
    val primary: Color @Composable get() = MaterialTheme.colorScheme.primary
    val muted: Color @Composable get() = if (isSystemInDarkTheme()) NightInkMuted else InkMuted
    val faint: Color @Composable get() = if (isSystemInDarkTheme()) NightInkFaint else InkFaint
    val rule: Color @Composable get() = if (isSystemInDarkTheme()) NightRule else Rule
    val sand: Color @Composable get() = if (isSystemInDarkTheme()) NightSand else Sand
    val terracotta: Color @Composable get() = if (isSystemInDarkTheme()) NightTerracotta else Terracotta
}
