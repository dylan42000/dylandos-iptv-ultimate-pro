package com.dylandos.iptv.ultimate.ui.theme

import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * DYLANDOS IPTV ULTIMATE — shared premium screen background.
 *
 * Deep navy vertical base with a soft purple/blue radial glow, matching the
 * world-class dashboard look. Applied to the root container of the major
 * screens (Home, Movies, Series, Live TV, Guide) so the whole app shares one
 * cohesive atmosphere instead of flat black.
 */
private val DylandosBgBase = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF0B1220),   // top - blue black
        Color(0xFF090A14),   // mid
        Color(0xFF050609),   // bottom - near jet black
    )
)

private val DylandosBgGlow = Brush.radialGradient(
    colors = listOf(
        Color(0x2E8B3FD6),   // violet haze
        Color(0x1E00D4FF),   // cyan edge glow
        Color(0x12FF3B5C),   // subtle red spark
        Color(0x00000000),   // transparent
    ),
    radius = 1700f,
)

/** Apply the cohesive premium navy + purple-glow background to a screen root. */
fun Modifier.dylandosScreenBackground(): Modifier = this
    .background(DylandosBgBase)
    .background(DylandosBgGlow)
