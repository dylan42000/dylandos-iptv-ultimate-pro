package com.dylandos.iptv.ultimate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/**
 * DYLANDOS IPTV ULTIMATE — Theme
 *
 * Accepts an [AppTheme] enum and builds a fully-themed Material3 + custom
 * [LocalDylandosColors] environment. All screens can access custom tokens via
 * LocalDylandosColors.current.
 *
 * Defaults to NEON_PARADISE (the original look) if no theme is provided.
 */
@Composable
fun DylandosTheme(
    appTheme: AppTheme = AppTheme.NEON_PARADISE,
    customAccentHex: String? = null,
    content: @Composable () -> Unit
) {
    val base = appTheme.colors()
    val c = if (!customAccentHex.isNullOrBlank()) {
        runCatching {
            val accent = Color(android.graphics.Color.parseColor(customAccentHex))
            base.copy(
                accent        = accent,
                accentBright  = accent,
                accentGlow    = accent.copy(alpha = 0.30f),
                accentSurface = accent.copy(alpha = 0.12f),
                borderAccent  = accent.copy(alpha = 0.55f),
                focusBg       = accent.copy(alpha = 0.35f),
                textAccent    = accent
            )
        }.getOrDefault(base)
    } else base

    val colorScheme = darkColorScheme(
        primary              = c.accent,
        onPrimary            = c.bgBase,
        primaryContainer     = c.accentSurface,
        onPrimaryContainer   = c.accentBright,
        secondary            = c.accentSecondary,
        onSecondary          = c.bgBase,
        secondaryContainer   = c.accentSurface,
        onSecondaryContainer = c.accentBright,
        tertiary             = c.accentBlue,
        onTertiary           = c.bgBase,
        background           = c.bgBase,
        onBackground         = c.textPrimary,
        surface              = c.bgSurface,
        onSurface            = c.textPrimary,
        surfaceVariant       = c.bgSurface2,
        onSurfaceVariant     = c.textSecondary,
        error                = c.statusError,
        onError              = c.textPrimary,
        errorContainer       = c.statusError,
        onErrorContainer     = c.textPrimary,
        outline              = c.borderDefault,
        outlineVariant       = c.borderSubtle
    )

    CompositionLocalProvider(LocalDylandosColors provides c) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = DylandosTypography,
            content     = content
        )
    }
}
