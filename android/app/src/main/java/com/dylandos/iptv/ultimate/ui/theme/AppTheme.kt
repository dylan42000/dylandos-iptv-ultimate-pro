package com.dylandos.iptv.ultimate.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * DYLANDOS IPTV ULTIMATE — Dynamic Theme System
 *
 * Four complete visual themes:
 *
 *  CLASSIC_BLUE         — Original premium streaming look: electric blue on near-black navy
 *  NEON_PARADISE        — Default hot-pink + Lamborghini orange + baby blue (the original)
 *  NEON_PARADISE_ULTRA  — Hyper-amped version: blinding magenta, violet BGs, acid green status
 *  CYBER_VOID           — Wild dark-matter: Matrix acid green + void purple + magenta chaos
 *
 * Usage:
 *   val c = LocalDylandosColors.current
 *   Modifier.background(c.bgBase)
 *   Text(color = c.accent)
 */

enum class AppTheme(val displayName: String, val emoji: String) {
    CLASSIC_BLUE          ("Classic Blue",            "🔵"),
    NEON_PARADISE         ("Neon Paradise",           "🌸"),
    NEON_HOT_PINK         ("Neon Hot Pink",           "💗"),
    NEON_LAMBORGHINI      ("Neon Lamborghini Orange", "🟠"),
    NEON_SMOKE_GREY       ("Neon Smoke Grey",         "🩶"),
    NEON_SMOKE_WHITE      ("Neon Smoke White",        "🤍"),
    NEON_CHARCOAL_BLACK   ("Neon Charcoal Black",     "🖤"),
    NEON_BABY_BLUE        ("Neon Baby Blue",          "🩵"),
    NEON_PARADISE_ULTRA   ("Neon Paradise ULTRA",     "⚡"),
    CYBER_VOID            ("Cyber Void",              "💀")
}

/** All dynamic color tokens used by DYLANDOS screens — provided via CompositionLocal. */
data class DylandosColors(
    val accent: Color,
    val accentBright: Color,
    val accentGlow: Color,
    val accentSurface: Color,
    val accentSecondary: Color,
    val accentSecondaryBright: Color,
    val accentBlue: Color,
    val bgBase: Color,
    val bgSurface: Color,
    val bgSurface2: Color,
    val bgSurface3: Color,
    val bgElevated: Color,
    val borderSubtle: Color,
    val borderDefault: Color,
    val borderAccent: Color,
    val focusBg: Color,
    val focusBgStrong: Color,
    val focusBgButton: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val textAccent: Color,
    val statusSuccess: Color,
    val statusWarning: Color,
    val statusError: Color,
    val statusInfo: Color
)

val LocalDylandosColors = compositionLocalOf<DylandosColors> { NeonParadiseColors }

// ── Theme 1: Classic Blue ─────────────────────────────────────────────────────
val ClassicBlueColors = DylandosColors(
    accent                = Color(0xFF0096FF),
    accentBright          = Color(0xFF33AAFF),
    accentGlow            = Color(0x440096FF),
    accentSurface         = Color(0x180096FF),
    accentSecondary       = Color(0xFF00D4FF),
    accentSecondaryBright = Color(0xFF44DDFF),
    accentBlue            = Color(0xFF0044CC),
    bgBase                = Color(0xFF060A10),
    bgSurface             = Color(0xFF0B1420),
    bgSurface2            = Color(0xFF111E30),
    bgSurface3            = Color(0xFF162440),
    bgElevated            = Color(0xFF1A2C4A),
    borderSubtle          = Color(0x1A0096FF),
    borderDefault         = Color(0x350096FF),
    borderAccent          = Color(0x660096FF),
    focusBg               = Color(0x440096FF),
    focusBgStrong         = Color(0xFF001A3A),
    focusBgButton         = Color(0xFF001C4A),
    textPrimary           = Color(0xFFF5F5F5),
    textSecondary         = Color(0x99F5F5F5),
    textTertiary          = Color(0x4DF5F5F5),
    textAccent            = Color(0xFF33AAFF),
    statusSuccess         = Color(0xFF00D97F),
    statusWarning         = Color(0xFFFF9500),
    statusError           = Color(0xFFFF3B30),
    statusInfo            = Color(0xFF0096FF)
)

// ── Theme 2: Neon Paradise ────────────────────────────────────────────────────
val NeonParadiseColors = DylandosColors(
    accent                = Color(0xFFFF0076),
    accentBright          = Color(0xFFFF3399),
    accentGlow            = Color(0x40FF0076),
    accentSurface         = Color(0x14FF0076),
    accentSecondary       = Color(0xFFFF5500),
    accentSecondaryBright = Color(0xFFFF7722),
    accentBlue            = Color(0xFF00C8FF),
    bgBase                = Color(0xFF080808),
    bgSurface             = Color(0xFF111115),
    bgSurface2            = Color(0xFF1A1A20),
    bgSurface3            = Color(0xFF212128),
    bgElevated            = Color(0xFF28282F),
    borderSubtle          = Color(0x1AFF0076),
    borderDefault         = Color(0x33FF0076),
    borderAccent          = Color(0x66FF0076),
    focusBg               = Color(0x66FF0076),
    focusBgStrong         = Color(0xFF3A0020),
    focusBgButton         = Color(0xFF2A1000),
    textPrimary           = Color(0xFFF5F5F5),
    textSecondary         = Color(0x99F5F5F5),
    textTertiary          = Color(0x4DF5F5F5),
    textAccent            = Color(0xFFFF3399),
    statusSuccess         = Color(0xFF00FFAA),
    statusWarning         = Color(0xFFFF5500),
    statusError           = Color(0xFFFF0076),
    statusInfo            = Color(0xFF00C8FF)
)

// ── Theme 3: Neon Hot Pink ────────────────────────────────────────────────────
// Pure ultra-saturated hot pink — more intense than Neon Paradise.
// Every glow, border, and focus ring is maximum-brightness pink.
val NeonHotPinkColors = DylandosColors(
    accent                = Color(0xFFFF0066),  // Pure neon hot pink — zero red bleed
    accentBright          = Color(0xFFFF44AA),  // Bright hover pink
    accentGlow            = Color(0x60FF0066),  // 38% glow — very vivid
    accentSurface         = Color(0x20FF0066),  // 12.5% tint surface
    accentSecondary       = Color(0xFFFF00CC),  // Hot magenta secondary
    accentSecondaryBright = Color(0xFFFF55DD),
    accentBlue            = Color(0xFF00EEFF),  // Cyan-blue contrast
    bgBase                = Color(0xFF020007),  // Near-black with deep purple tint
    bgSurface             = Color(0xFF0A000E),
    bgSurface2            = Color(0xFF130018),
    bgSurface3            = Color(0xFF1C0024),
    bgElevated            = Color(0xFF250030),
    borderSubtle          = Color(0x30FF0066),
    borderDefault         = Color(0x55FF0066),
    borderAccent          = Color(0x99FF0066),
    focusBg               = Color(0x70FF0066),  // Very visible focus ring
    focusBgStrong         = Color(0xFF400025),
    focusBgButton         = Color(0xFF2A0015),
    textPrimary           = Color(0xFFFFF0F8),  // Warm white with pink tint
    textSecondary         = Color(0xAAFFF0F8),
    textTertiary          = Color(0x55FFF0F8),
    textAccent            = Color(0xFFFF44AA),
    statusSuccess         = Color(0xFF00FFAA),
    statusWarning         = Color(0xFFFF6600),
    statusError           = Color(0xFFFF0066),
    statusInfo            = Color(0xFF00EEFF)
)

// ── Theme 4: Neon Lamborghini Orange ─────────────────────────────────────────
// Lamborghini Arancio Borealis orange as the blinding primary accent.
// Dark amber-tinted backgrounds for that supercar-showroom night feel.
val NeonLamborghiniColors = DylandosColors(
    accent                = Color(0xFFFF6600),  // Lamborghini Arancio Borealis — true orange
    accentBright          = Color(0xFFFF8822),  // Bright hover orange
    accentGlow            = Color(0x60FF6600),
    accentSurface         = Color(0x1AFF6600),
    accentSecondary       = Color(0xFFFF3300),  // Deeper red-orange secondary
    accentSecondaryBright = Color(0xFFFF5511),
    accentBlue            = Color(0xFF00DDFF),  // Cool cyan for contrast
    bgBase                = Color(0xFF050200),  // Near-black with dark amber tint
    bgSurface             = Color(0xFF100500),
    bgSurface2            = Color(0xFF1A0A00),
    bgSurface3            = Color(0xFF221000),
    bgElevated            = Color(0xFF2C1600),
    borderSubtle          = Color(0x2AFF6600),
    borderDefault         = Color(0x50FF6600),
    borderAccent          = Color(0x88FF6600),
    focusBg               = Color(0x66FF6600),
    focusBgStrong         = Color(0xFF3A1400),
    focusBgButton         = Color(0xFF2A0A00),
    textPrimary           = Color(0xFFFFF8F0),  // Warm white with orange tint
    textSecondary         = Color(0xAAFFF8F0),
    textTertiary          = Color(0x55FFF8F0),
    textAccent            = Color(0xFFFF8822),
    statusSuccess         = Color(0xFF00FFAA),
    statusWarning         = Color(0xFFFFCC00),
    statusError           = Color(0xFFFF0044),
    statusInfo            = Color(0xFF00DDFF)
)

// ── Theme 5: Neon Smoke Grey ─────────────────────────────────────────────────
// Neutral dark-smoke-grey backgrounds — the "understated luxe" palette.
// Hot pink neon accents pop against the cool grey surfaces.
val NeonSmokeGreyColors = DylandosColors(
    accent                = Color(0xFFFF0076),  // Hot pink on grey — high contrast
    accentBright          = Color(0xFFFF3399),
    accentGlow            = Color(0x50FF0076),
    accentSurface         = Color(0x18FF0076),
    accentSecondary       = Color(0xFF00C8FF),  // Baby blue secondary
    accentSecondaryBright = Color(0xFF44DDFF),
    accentBlue            = Color(0xFF9966FF),  // Purple tertiary
    bgBase                = Color(0xFF0F0F12),  // Dark smoke grey — neutral, no color cast
    bgSurface             = Color(0xFF181818),
    bgSurface2            = Color(0xFF222224),
    bgSurface3            = Color(0xFF2A2A2D),
    bgElevated            = Color(0xFF333336),
    borderSubtle          = Color(0x22FF0076),
    borderDefault         = Color(0x44FF0076),
    borderAccent          = Color(0x77FF0076),
    focusBg               = Color(0x55FF0076),
    focusBgStrong         = Color(0xFF3A1820),
    focusBgButton         = Color(0xFF2A0E16),
    textPrimary           = Color(0xFFF0F0F0),  // Pure near-white for smoke grey
    textSecondary         = Color(0x99F0F0F0),
    textTertiary          = Color(0x4DF0F0F0),
    textAccent            = Color(0xFFFF3399),
    statusSuccess         = Color(0xFF00FFAA),
    statusWarning         = Color(0xFFFF8800),
    statusError           = Color(0xFFFF0076),
    statusInfo            = Color(0xFF00C8FF)
)

// ── Theme 6: Neon Smoke White ─────────────────────────────────────────────────
// Silver-white neon on deep space-black — minimal, premium, ice-cold palette.
// Accent is a luminous silver-white; borders and glows are cool white-blue.
val NeonSmokeWhiteColors = DylandosColors(
    accent                = Color(0xFFE0E0FF),  // Luminous silver-white with ice-blue tint
    accentBright          = Color(0xFFFFFFFF),  // Pure white — blinding focus state
    accentGlow            = Color(0x55E0E0FF),
    accentSurface         = Color(0x16E0E0FF),
    accentSecondary       = Color(0xFF00CCFF),  // Baby blue secondary
    accentSecondaryBright = Color(0xFF55DDFF),
    accentBlue            = Color(0xFFAA88FF),  // Soft lavender tertiary
    bgBase                = Color(0xFF080812),  // Deep indigo-black
    bgSurface             = Color(0xFF10101C),
    bgSurface2            = Color(0xFF18182A),
    bgSurface3            = Color(0xFF202034),
    bgElevated            = Color(0xFF28283F),
    borderSubtle          = Color(0x28E0E0FF),
    borderDefault         = Color(0x50E0E0FF),
    borderAccent          = Color(0x88E0E0FF),
    focusBg               = Color(0x55E0E0FF),
    focusBgStrong         = Color(0xFF1E1E3A),
    focusBgButton         = Color(0xFF141430),
    textPrimary           = Color(0xFFFFFFFF),  // Pure white
    textSecondary         = Color(0xAAFFFFFF),
    textTertiary          = Color(0x55FFFFFF),
    textAccent            = Color(0xFFE0E0FF),
    statusSuccess         = Color(0xFF00FFCC),
    statusWarning         = Color(0xFFFFDD00),
    statusError           = Color(0xFFFF4488),
    statusInfo            = Color(0xFF00CCFF)
)

// ── Theme 7: Neon Charcoal Black ─────────────────────────────────────────────
// True charcoal (#1A1A1A) backgrounds — darker and warmer than Smoke Grey.
// Neon magenta-pink accents with maximum glow intensity on the charcoal canvas.
val NeonCharcoalBlackColors = DylandosColors(
    accent                = Color(0xFFFF00AA),  // Neon magenta-pink — extreme on charcoal
    accentBright          = Color(0xFFFF55CC),
    accentGlow            = Color(0x66FF00AA),  // Heavy 40% glow
    accentSurface         = Color(0x1EFF00AA),
    accentSecondary       = Color(0xFFFF6600),  // Lamborghini orange secondary
    accentSecondaryBright = Color(0xFFFF8833),
    accentBlue            = Color(0xFF00BBFF),
    bgBase                = Color(0xFF141414),  // Charcoal — the defining colour
    bgSurface             = Color(0xFF1A1A1A),  // Slightly lighter charcoal
    bgSurface2            = Color(0xFF222222),
    bgSurface3            = Color(0xFF2A2A2A),
    bgElevated            = Color(0xFF333333),
    borderSubtle          = Color(0x2AFF00AA),
    borderDefault         = Color(0x55FF00AA),
    borderAccent          = Color(0x88FF00AA),
    focusBg               = Color(0x66FF00AA),
    focusBgStrong         = Color(0xFF33002A),
    focusBgButton         = Color(0xFF22001A),
    textPrimary           = Color(0xFFF5F5F5),
    textSecondary         = Color(0x99F5F5F5),
    textTertiary          = Color(0x4DF5F5F5),
    textAccent            = Color(0xFFFF55CC),
    statusSuccess         = Color(0xFF00FF88),
    statusWarning         = Color(0xFFFF8800),
    statusError           = Color(0xFFFF0055),
    statusInfo            = Color(0xFF00BBFF)
)

// ── Theme 8: Neon Baby Blue ──────────────────────────────────────────────────
// Neon baby blue is the star — electric cyan-blue on deep space-navy black.
// Hot pink secondary adds the complementary pop that the blue needs.
val NeonBabyBlueColors = DylandosColors(
    accent                = Color(0xFF00C8FF),  // Neon baby blue
    accentBright          = Color(0xFF44DDFF),  // Ice-bright hover blue
    accentGlow            = Color(0x5500C8FF),
    accentSurface         = Color(0x1800C8FF),
    accentSecondary       = Color(0xFFFF0076),  // Hot pink complement
    accentSecondaryBright = Color(0xFFFF3399),
    accentBlue            = Color(0xFF0066FF),  // Royal blue tertiary
    bgBase                = Color(0xFF020810),  // Very deep navy-black
    bgSurface             = Color(0xFF060F1C),
    bgSurface2            = Color(0xFF0B1828),
    bgSurface3            = Color(0xFF102030),
    bgElevated            = Color(0xFF152840),
    borderSubtle          = Color(0x2200C8FF),
    borderDefault         = Color(0x4400C8FF),
    borderAccent          = Color(0x7700C8FF),
    focusBg               = Color(0x5500C8FF),
    focusBgStrong         = Color(0xFF002540),
    focusBgButton         = Color(0xFF001830),
    textPrimary           = Color(0xFFF0F8FF),  // Alice blue-white
    textSecondary         = Color(0xAAF0F8FF),
    textTertiary          = Color(0x55F0F8FF),
    textAccent            = Color(0xFF44DDFF),
    statusSuccess         = Color(0xFF00FFCC),
    statusWarning         = Color(0xFFFF8800),
    statusError           = Color(0xFFFF0076),
    statusInfo            = Color(0xFF00C8FF)
)

// ── Theme 9: Neon Paradise ULTRA ─────────────────────────────────────────────
val NeonParadiseUltraColors = DylandosColors(
    accent                = Color(0xFFFF00BB),
    accentBright          = Color(0xFFFF44DD),
    accentGlow            = Color(0x60FF00BB),
    accentSurface         = Color(0x22FF00BB),
    accentSecondary       = Color(0xFFFF2200),
    accentSecondaryBright = Color(0xFFFF5500),
    accentBlue            = Color(0xFF00FFFF),
    bgBase                = Color(0xFF050008),
    bgSurface             = Color(0xFF0E0014),
    bgSurface2            = Color(0xFF160022),
    bgSurface3            = Color(0xFF1E002E),
    bgElevated            = Color(0xFF27003B),
    borderSubtle          = Color(0x22FF00BB),
    borderDefault         = Color(0x44FF00BB),
    borderAccent          = Color(0x88FF00BB),
    focusBg               = Color(0x60FF00BB),
    focusBgStrong         = Color(0xFF400030),
    focusBgButton         = Color(0xFF350010),
    textPrimary           = Color(0xFFFFF0FF),
    textSecondary         = Color(0xAAFFF0FF),
    textTertiary          = Color(0x55FFF0FF),
    textAccent            = Color(0xFFFF44DD),
    statusSuccess         = Color(0xFF00FF88),
    statusWarning         = Color(0xFFFF5500),
    statusError           = Color(0xFFFF0077),
    statusInfo            = Color(0xFF00FFFF)
)

// ── Theme 4: Cyber Void ───────────────────────────────────────────────────────
val CyberVoidColors = DylandosColors(
    accent                = Color(0xFF00FF41),
    accentBright          = Color(0xFF44FF70),
    accentGlow            = Color(0x5000FF41),
    accentSurface         = Color(0x1600FF41),
    accentSecondary       = Color(0xFFFF00FF),
    accentSecondaryBright = Color(0xFFFF44FF),
    accentBlue            = Color(0xFF7B00FF),
    bgBase                = Color(0xFF03000C),
    bgSurface             = Color(0xFF080018),
    bgSurface2            = Color(0xFF0E0025),
    bgSurface3            = Color(0xFF140033),
    bgElevated            = Color(0xFF1A0040),
    borderSubtle          = Color(0x2200FF41),
    borderDefault         = Color(0x4400FF41),
    borderAccent          = Color(0x7700FF41),
    focusBg               = Color(0x5000FF41),
    focusBgStrong         = Color(0xFF001C0A),
    focusBgButton         = Color(0xFF100040),
    textPrimary           = Color(0xFFF0FFF4),
    textSecondary         = Color(0xAAF0FFF4),
    textTertiary          = Color(0x55F0FFF4),
    textAccent            = Color(0xFF44FF70),
    statusSuccess         = Color(0xFF00FF41),
    statusWarning         = Color(0xFFFFFF00),
    statusError           = Color(0xFFFF0055),
    statusInfo            = Color(0xFF7B00FF)
)

/** Map an AppTheme to its DylandosColors palette. */
fun AppTheme.colors(): DylandosColors = when (this) {
    AppTheme.CLASSIC_BLUE        -> ClassicBlueColors
    AppTheme.NEON_PARADISE       -> NeonParadiseColors
    AppTheme.NEON_HOT_PINK       -> NeonHotPinkColors
    AppTheme.NEON_LAMBORGHINI    -> NeonLamborghiniColors
    AppTheme.NEON_SMOKE_GREY     -> NeonSmokeGreyColors
    AppTheme.NEON_SMOKE_WHITE    -> NeonSmokeWhiteColors
    AppTheme.NEON_CHARCOAL_BLACK -> NeonCharcoalBlackColors
    AppTheme.NEON_BABY_BLUE      -> NeonBabyBlueColors
    AppTheme.NEON_PARADISE_ULTRA -> NeonParadiseUltraColors
    AppTheme.CYBER_VOID          -> CyberVoidColors
}

/** Safe valueOf that falls back to NEON_PARADISE on unknown strings. */
fun appThemeFromName(name: String?): AppTheme =
    runCatching { AppTheme.valueOf(name ?: "") }.getOrDefault(AppTheme.NEON_PARADISE)
