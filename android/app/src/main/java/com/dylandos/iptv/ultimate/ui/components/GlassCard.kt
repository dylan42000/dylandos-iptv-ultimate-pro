package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dylandos.iptv.ultimate.ui.theme.DylandosPalette

/**
 * GlassCard — Glassmorphism-style card for the DYLANDOS Cinematic UI.
 *
 * Creates a semi-transparent card with a subtle gradient background and
 * a border that animates to cyan when focused via D-pad.
 *
 * True BackdropFilter blur is not available in Compose on Android; the
 * effect is simulated with semi-transparent backgrounds and DylandosPalette
 * surface colours.
 *
 * @param modifier       Modifier chain
 * @param cornerRadius   Corner radius in dp (default 16 dp)
 * @param isFocused      Whether this card is currently D-pad focused
 * @param glassOpacity   Opacity of the glass-white background (0.0–1.0)
 * @param borderWidth    Border stroke width
 * @param focusBorderColor Border colour when focused (defaults to DylandosCyan)
 * @param elevation      Glass depth level — affects background opacity
 * @param content        Composable slot rendered inside the card
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    isFocused: Boolean = false,
    glassOpacity: Float = 0.1f,
    borderWidth: Dp = 1.dp,
    focusBorderColor: Color = DylandosPalette.Cyan,
    elevation: GlassElevation = GlassElevation.Medium,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)

    // Border colour: animates from dim glass border → full cyan on focus
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) focusBorderColor else DylandosPalette.GlassBorder,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "glassBorder",
    )

    // Glass background alpha: LOW = 50%, MEDIUM = 100%, HIGH = 150% of glassOpacity
    val glassAlpha = when (elevation) {
        GlassElevation.Low    -> glassOpacity * 0.5f
        GlassElevation.Medium -> glassOpacity
        GlassElevation.High   -> (glassOpacity * 1.5f).coerceAtMost(1f)
    }
    val glassBackground = Color.White.copy(alpha = glassAlpha)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        glassBackground,
                        glassBackground.copy(alpha = glassAlpha * 0.5f),
                    )
                ),
                shape = shape,
            )
            .border(
                width = if (isFocused) borderWidth * 2 else borderWidth,
                color = borderColor,
                shape = shape,
            ),
        content = content,
    )
}

/** Depth hint that adjusts how opaque the glass background appears. */
enum class GlassElevation {
    Low,
    Medium,
    High,
}

/**
 * FocusableGlassCard — GlassCard with self-contained TV focus tracking.
 *
 * Wraps [GlassCard] and automatically sets [isFocused] from the Compose
 * focus system. Use for any card that participates in D-pad navigation.
 *
 * @param onClick      Called on click/select
 * @param modifier     Modifier chain
 * @param cornerRadius Corner radius in dp
 * @param content      Composable slot
 */
@Composable
fun FocusableGlassCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    GlassCard(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource),
        isFocused = isFocused,
        cornerRadius = cornerRadius,
        content = content,
    )
}
