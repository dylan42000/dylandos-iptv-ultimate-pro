package com.dylandos.iptv.ultimate.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * DYLANDOS IPTV ULTIMATE - Color Palette
 *
 * NEON BLAST theme — 3-color background + 2 neon accent colors:
 *  Backgrounds : Neon Jet Black  | Neon Smoke Grey | Neon Baby Blue (tint)
 *  Text/Outline: Neon Hot Pink   | Neon Lamborghini Orange
 */

// ── Background Colors ─────────────────────────────────── Neon Jet Black base
val BgBase     = Color(0xFF080808)   // Near-pure jet black
val BgSurface  = Color(0xFF111115)   // Jet black with micro smoke
val BgSurface2 = Color(0xFF1A1A20)   // Neon Smoke Grey (cool dark grey)
val BgSurface3 = Color(0xFF212128)   // Smoke Grey lighter
val BgElevated = Color(0xFF28282F)   // Elevated Smoke Grey (cards)

// ── Baby Blue background tint (glass/info surfaces) ──────────────────────────
val BgBabyBlue     = Color(0xFF0A1522)   // Very dark Neon Baby Blue tint
val BgBabyBlueCard = Color(0xFF0D1D2E)   // Baby Blue tinted card surface

// ── Border Colors ─────────────────────────────────────────────────────────────
val BorderSubtle  = Color(0x1AFF0076)   // Hot Pink 10%
val BorderDefault = Color(0x33FF0076)   // Hot Pink 20%
val BorderAccent  = Color(0x66FF0076)   // Hot Pink 40%

// ── PRIMARY ACCENT: Neon Hot Pink ────────────────────────────────────────────
val Accent             = Color(0xFFFF0076)  // Core Neon Hot Pink
val AccentBright       = Color(0xFFFF3399)  // Lighter / hover Hot Pink
val AccentGlow         = Color(0x40FF0076)  // Hot Pink glow (25% opacity)
val AccentSurface      = Color(0x14FF0076)  // Hot Pink tint surface (8%)
val AccentSurfaceHover = Color(0x24FF0076)  // Hot Pink tint hover (14%)

// ── SECONDARY ACCENT: Neon Lamborghini Orange ─────────────────────────────────
val AccentSecondary       = Color(0xFFFF5500)  // Lamborghini Orange
val AccentSecondaryBright = Color(0xFFFF7722)  // Bright orange hover
val AccentSecondaryGlow   = Color(0x40FF5500)  // Orange glow

// ── TERTIARY ACCENT: Neon Baby Blue ───────────────────────────────────────────
val AccentBlue     = Color(0xFF00C8FF)  // Neon Baby Blue
val AccentBlueDim  = Color(0xFF0099CC)  // Dimmer baby blue
val AccentBlueGlow = Color(0x3300C8FF)  // Baby blue glow

// ── Text Colors ───────────────────────────────────────────────────────────────
val TextPrimary   = Color(0xFFF5F5F5)          // Near-white
val TextSecondary = Color(0x99F5F5F5)          // 60% opacity
val TextTertiary  = Color(0x4DF5F5F5)          // 30% opacity
val TextAccent    = Color(0xFFFF3399)           // Hot Pink text accent

// ── Status Colors ─────────────────────────────────────────────────────────────
val StatusSuccess = Color(0xFF00FFAA)  // Neon Green for success
val StatusWarning = Color(0xFFFF5500)  // Lamborghini Orange for warnings
val StatusError   = Color(0xFFFF0076)  // Hot Pink for errors / live indicator
val StatusInfo    = Color(0xFF00C8FF)  // Baby Blue for info

// ── DVR / Status aliases (semantic convenience names) ─────────────────────────
val DylandosRed  = Color(0xFFFF0076)   // Same as Accent / StatusError — recording live indicator
val DylandosGold = Color(0xFFFFD600)   // Gold for OTG "recommended" badge in storage picker

// ── Focused Item Surfaces — much more visible for 10-foot Firestick UI ─────────
val FocusBg       = Color(0x66FF0076)  // Hot Pink 40% — very clear focused background
val FocusBgStrong = Color(0xFF3A0020)  // Deep pink solid bg for focused rows/items

// ─────────────────────────────────────────────────────────────────────────────
// DylandosPalette — Static Cyan-based color tokens for new Glassmorphism UI.
//
// Used by GlassCard, ChannelCard, PosterCard, NavigationSidebar, and other
// new world-class components. Built around #00D4FF (DylandosCyan).
//
// Note: kept as a separate `object` so it co-exists with the dynamic
// DylandosColors data class (multi-theme system) in AppTheme.kt.
// ─────────────────────────────────────────────────────────────────────────────
object DylandosPalette {

    // === Brand Colors ===
    val Cyan        = Color(0xFF00D4FF)  // Primary brand
    val CyanLight   = Color(0xFF4DE5FF)  // Hover/focus state
    val CyanDark    = Color(0xFF0095B3)  // Pressed state
    val CyanAlpha50 = Color(0x8000D4FF)  // Semi-transparent for overlays
    val CyanAlpha20 = Color(0x3300D4FF)  // Subtle backgrounds
    val CyanAlpha10 = Color(0x1A00D4FF)  // Very subtle tint

    // === Surface Colors (Depth System) ===
    val SurfaceBase    = Color(0xFF0A0A0F)   // Deepest background
    val Surface0       = Color(0xFF0F0F18)   // Main background
    val Surface1       = Color(0xFF151520)   // Card background
    val Surface2       = Color(0xFF1A1A28)   // Elevated card
    val Surface3       = Color(0xFF1F1F30)   // Modal/dialog
    val Surface4       = Color(0xFF252538)   // Floating elements
    val SurfaceOverlay = Color(0xCC0A0A0F)   // 80% opacity overlay

    // === Glass Surfaces (Glassmorphism) ===
    val GlassLight  = Color(0x1AFFFFFF)  // 10% white
    val GlassMedium = Color(0x33FFFFFF)  // 20% white
    val GlassBorder = Color(0x33FFFFFF)  // Border for glass panels
    val GlassBlur   = Color(0x660A0A0F)  // Background for blur effect

    // === Text Colors ===
    val TextPrimary   = Color(0xFFFFFFFF)  // 100% white
    val TextSecondary = Color(0xB3FFFFFF)  // 70% white
    val TextTertiary  = Color(0x66FFFFFF)  // 40% white
    val TextDisabled  = Color(0x33FFFFFF)  // 20% white
    val TextOnCyan    = Color(0xFF0A0A0F)  // Dark text on cyan background

    // === Status Colors ===
    val Success       = Color(0xFF00E676)
    val SuccessAlpha20 = Color(0x3300E676)
    val Warning       = Color(0xFFFFAB00)
    val WarningAlpha20 = Color(0x33FFAB00)
    val Error         = Color(0xFFFF1744)
    val ErrorAlpha20  = Color(0x33FF1744)
    val Info          = Color(0xFF448AFF)
    val InfoAlpha20   = Color(0x33448AFF)

    // === Live Indicator ===
    val LiveRed      = Color(0xFFFF0000)
    val LiveRedPulse = Color(0xFFFF4444)

    // === Category Colors (EPG programme blocks) ===
    val CategorySports        = Color(0xFF4CAF50)
    val CategoryNews          = Color(0xFF2196F3)
    val CategoryMovies        = Color(0xFFE91E63)
    val CategoryKids          = Color(0xFFFF9800)
    val CategoryMusic         = Color(0xFF9C27B0)
    val CategoryDocumentary   = Color(0xFF795548)
    val CategoryEntertainment = Color(0xFFFFEB3B)
    val CategoryGeneral       = Color(0xFF607D8B)

    // === Gradient Definitions ===
    val GradientPrimary    = listOf(Cyan, CyanDark)
    val GradientBackground = listOf(SurfaceBase, Surface0)
    val GradientCardShimmer = listOf(Surface1, Surface2, Surface1)
    val GradientPlayerOverlay = listOf(
        Color(0x00000000),
        Color(0x80000000),
        Color(0xCC000000),
    )
    val GradientHeroOverlay = listOf(
        Color(0x00000000),
        Color(0x66000000),
        Color(0xE60A0A0F),
    )
}
