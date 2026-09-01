package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.theme.LocalDylandosColors

/**
 * DYLANDOS IPTV — TV Focus Modifiers v3 (Theme-Aware)
 *
 * Firestick / 10-foot UI optimised focus modifiers.
 * Colors are now drawn from LocalDylandosColors so they respond to the selected app theme.
 *
 * Three variants:
 *   tvFocusable()      — channel rows, list items, nav buttons
 *   tvCardFocusable()  — poster/grid cards (larger scale)
 *   tvButtonFocusable()— action buttons (secondary accent color)
 *
 * Focus effect:
 *   • Solid colored background (theme focusBgStrong) — instantly visible
 *   • Bright border on focus (snappy 100 ms tween — replaces infinite glow pulse)
 *   • Modest scale pop (1.04× default) that does not clip adjacent TV cards
 */

/**
 * Theme-aware TV focus modifier via LocalDylandosColors.
 *
 * Prefer [com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable] for clickable rows/buttons
 * that need OK/Enter confirm. Use this when you need theme focus colors / scale and either
 * no click, or a parent-owned click path.
 *
 * Pass explicit [borderColor]/[bgFocused] to override; null (default) → theme palette.
 * Pass an external [interactionSource] so parent composables can observe child focus
 * (e.g. a Card border lighting up when a button inside it has focus).
 *
 * When [onClick] is provided, Firestick OK / Enter / BUTTON_A confirm keys invoke it
 * (same contract as [com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable]).
 */
fun Modifier.tvFocusable(
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource? = null,
    borderColor: Color? = null,
    bgFocused: Color? = null,
    scaleOnFocus: Float = 1.04f,
    borderWidth: Dp = 2.5.dp,
    cornerRadius: Dp = 8.dp,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val themeColors = LocalDylandosColors.current
    val resolvedBorder = borderColor ?: themeColors.accent
    val resolvedBg     = bgFocused    ?: themeColors.focusBgStrong

    val resolvedInteractionSource = interactionSource ?: remember { MutableInteractionSource() }
    val isFocused by resolvedInteractionSource.collectIsFocusedAsState()

    // PERF FIX: tween(100) replaces MediumBouncy spring + infinite glow pulse.
    // The infinite pulse kept Compose animation thread permanently busy on every focused element.
    // The bouncy spring caused 3+ animation frames per D-pad keypress across 25+ visible grid items.
    // tween(100) is imperceptible to the user but costs 4x fewer frames.
    val scale by animateFloatAsState(
        targetValue  = if (isFocused) scaleOnFocus else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "tvFocusScale"
    )

    this
        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        .focusable(interactionSource = resolvedInteractionSource)
        .then(
            if (onClick != null) {
                Modifier
                    .onKeyEvent { event ->
                        if (event.isRemoteConfirmKey()) {
                            onClick()
                            true
                        } else {
                            false
                        }
                    }
                    .clickable(
                        interactionSource = resolvedInteractionSource,
                        indication = null,
                        onClick = onClick
                    )
            } else Modifier
        )
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .then(
            if (isFocused) Modifier.background(resolvedBg, RoundedCornerShape(cornerRadius))
            else Modifier
        )
        .then(
            if (isFocused) Modifier.border(
                BorderStroke(borderWidth, resolvedBorder),
                RoundedCornerShape(cornerRadius)
            )
            else Modifier
        )
}

/**
 * Card focus modifier — for poster/grid cards.
 * Slightly larger scale pop to lift card off the grid.
 */
fun Modifier.tvCardFocusable(
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null
): Modifier = tvFocusable(
    focusRequester = focusRequester,
    borderColor    = null,   // → LocalDylandosColors.current.accent
    bgFocused      = null,   // → LocalDylandosColors.current.focusBgStrong
    scaleOnFocus   = 1.05f,
    borderWidth    = 2.5.dp,
    cornerRadius   = 10.dp,
    onClick        = onClick
)

/**
 * Button focus modifier — for primary action buttons.
 * Uses accentSecondary border color with theme-aware button focus bg.
 * Pass [interactionSource] to let a parent composable observe this button's focus state
 * (e.g. lighting a card border when a child button inside is focused).
 */
fun Modifier.tvButtonFocusable(
    focusRequester: FocusRequester? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: (() -> Unit)? = null
): Modifier = composed {
    val themeColors = LocalDylandosColors.current
    this.tvFocusable(
        focusRequester    = focusRequester,
        interactionSource = interactionSource,
        borderColor       = themeColors.accentSecondary,
        bgFocused         = themeColors.focusBgButton,
        scaleOnFocus      = 1.04f,
        borderWidth       = 2.5.dp,
        cornerRadius      = 8.dp,
        onClick           = onClick
    )
}

/**
 * DYLANDOS IPTV — TvFocusableCard
 *
 * A reusable card composable for Channel and VOD grids that delivers premium
 * Firestick D-pad focus feedback:
 *   • Scale spring pop to 1.05x when focused (snappy, medium-bouncy spring)
 *   • Animated primary-color border that fades in/out in 150 ms
 *   • Subtle focused background from the active theme palette
 *
 * Drop-in replacement for any Box/Card that needs TV focus behaviour.
 *
 * @param onClick     Called when the user presses D-pad center or taps on a touch screen.
 * @param modifier    Passed to the outer Box so callers control size/padding.
 * @param cornerRadius Card corner radius — matches the corner used in the border.
 * @param content     Slot composable rendered inside the card.
 */
@Composable
fun TvFocusableCard(
    onClick:      () -> Unit,
    modifier:     Modifier  = Modifier,
    cornerRadius: Dp        = 10.dp,
    content:      @Composable BoxScope.() -> Unit
) {
    val themeColors       = LocalDylandosColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused         by interactionSource.collectIsFocusedAsState()

    // PERF FIX: tween(100) replaces MediumBouncy spring — same fix as tvFocusable.
    val scale by animateFloatAsState(
        targetValue   = if (isFocused) 1.05f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "tvCardScale"
    )
    val borderColor by animateColorAsState(
        targetValue   = if (isFocused) themeColors.accent else Color.Transparent,
        animationSpec = tween(durationMillis = 150),
        label         = "tvCardBorderColor"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .border(
                BorderStroke(if (isFocused) 3.dp else 0.dp, borderColor),
                RoundedCornerShape(cornerRadius)
            )
            .then(
                if (isFocused)
                    Modifier.background(themeColors.focusBgStrong, RoundedCornerShape(cornerRadius))
                else Modifier
            )
            .clickable(
                interactionSource = interactionSource,
                indication        = null,
                onClick           = onClick
            )
            .focusable(interactionSource = interactionSource),
        content = content
    )
}
