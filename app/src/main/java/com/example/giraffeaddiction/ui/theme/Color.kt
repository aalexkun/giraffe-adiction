package com.example.giraffeaddiction.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The clay profile: the savanna palette shared with the other giraffe apps, turned toward red.
 *
 * Three values are sampled from the mascot and never change: [Giraffe] the coat, [Rust] the
 * patches, [EarPink] the inner ear. In this profile the coat yellow is worn by the mascot alone;
 * the app's own colour is [Clay], a brick red pulled from the patches and kept muted so a screen
 * full of it still reads as calm. The ground is a faintly rosy cream rather than a bone yellow,
 * and the ink leans warm to match.
 */

// --- sampled from the mascot -----------------------------------------------------------------

/** The coat. Only the mascot wears it now. */
val Giraffe = Color(0xFFE2DC06)

/** The patches. Headings and the secondary colour. */
val Rust = Color(0xFF874623)

/** The inner ear. Reserved. */
val EarPink = Color(0xFFE4899B)

// --- the clay profile --------------------------------------------------------------------------

/** The primary: a muted brick red, a shade lighter and warmer than the patches. */
val Clay = Color(0xFFB5483B)
val ClayPressed = Color(0xFFA23D31)

/** Light text on [Clay]. */
val OnClay = Color(0xFFFFF6F1)

/** [Clay] lifted for the dark ground, where dark text sits on it instead. */
val NightClay = Color(0xFFDB7D6A)

/** The live dot, the active tab, the slider thumb: warmer than [Clay] and used in small doses. */
val Ember = Color(0xFFD9634B)

/** Warnings, so that red can stay the brand and not become a verdict. */
val Amber = Color(0xFFD9962E)

/** [Rust] lightened for links and secondary buttons. */
val Terracotta = Color(0xFFC2703F)

// --- light ground ------------------------------------------------------------------------------

/** App background: a rose-biased cream, deliberately not pink. */
val Blush = Color(0xFFFBF5F1)
val Paper = Color(0xFFFFFFFF)

/** Card tints and the disc behind the mascot. */
val Sand = Color(0xFFF5E8E1)
val Rule = Color(0xFFEAD7CE)

/** Warm near-black with a red bias; never pure black. */
val Ink = Color(0xFF2A1D19)
val InkMuted = Color(0xFF6F5B54)
val InkFaint = Color(0xFF9B867E)

// --- dark ground -------------------------------------------------------------------------------

val NightBlush = Color(0xFF17110F)
val NightPaper = Color(0xFF211816)
val NightSand = Color(0xFF2E2220)
val NightRule = Color(0xFF42322E)
val NightInk = Color(0xFFF7EFEA)
val NightInkMuted = Color(0xFFB9A59D)
val NightInkFaint = Color(0xFF8A7771)
val NightTerracotta = Color(0xFFD98A57)

// --- semantic ----------------------------------------------------------------------------------

/** A calm green for "this is fine", so the red stays the brand rather than an alarm. */
val Moss = Color(0xFF5B8C3E)

/** The one destructive action: a deeper crimson than [Clay], so it still reads as different. */
val Danger = Color(0xFF8E1F1F)
val DangerContainer = Color(0xFFF7DCD9)
val NightDangerContainer = Color(0xFF3F1C19)
