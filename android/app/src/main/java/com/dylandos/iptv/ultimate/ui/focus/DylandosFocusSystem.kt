package com.dylandos.iptv.ultimate.ui.focus

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dylandos signature cyan used for all focus highlights. */
val DylandosCyan = Color(0xFF00D4FF)

/**
 * **Primary** TV focus modifier for interactive elements that need click + optional long-press.
 *
 * Handles: animated focus border, scale, Firestick OK/Enter confirm, combinedClickable.
 *
 * Prefer this over hand-rolled `focusable`/`clickable` pairs so confirm-key behaviour stays
 * consistent. For theme-aware visual-only focus (parent owns click), use
 * [com.dylandos.iptv.ultimate.ui.components.tvFocusable] instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.dylandosFocusable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    focusBorderColor: Color = DylandosCyan,
    focusBorderWidth: Dp = 3.dp,
    focusScale: Float = 1.08f,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    enabled: Boolean = true
): Modifier {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) focusScale else 1.0f,
        animationSpec = tween(durationMillis = 100),
        label = "dylandos_focus_scale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) focusBorderColor else Color.Transparent,
        animationSpec = tween(150),
        label = "dylandos_focus_border"
    )

    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .border(focusBorderWidth, borderColor, shape)
        .focusRequester(focusRequester)
        .onFocusChanged { fs -> isFocused = fs.isFocused || fs.hasFocus }
        .focusable(enabled = enabled)
        .onKeyEvent { keyEvent ->
            if (enabled && keyEvent.isRemoteConfirmKey()) {
                onClick()
                true
            } else {
                false
            }
        }
        .combinedClickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
            onLongClick = onLongClick
        )
}

/**
 * Focus-group boundary for Firestick D-pad rails / panes.
 *
 * Uses [focusRestorer] so re-entering the section returns to the last focused child,
 * and optionally cancels exit moves so focus does not jump to unrelated parents
 * (TopAppBar / sibling sections).
 *
 * Note: Compose `Modifier.focusGroup()` is not available on this project's Compose
 * resolution path, so behaviour is implemented with focusRestorer + focusProperties.
 *
 * Use around category rails, settings panes, search result lists, storage pickers.
 * Do NOT wrap an entire screen that must freely hand focus to the sidebar — pass
 * `trapExit = false` (or omit the group) in those cases.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dylandosFocusGroup(trapExit: Boolean = true): Modifier {
    val base = this.focusRestorer()
    return if (trapExit) {
        base.focusProperties {
            exit = { FocusRequester.Cancel }
            enter = { FocusRequester.Default }
        }
    } else {
        base.focusProperties {
            enter = { FocusRequester.Default }
        }
    }
}

/**
 * Trap exit except for specific directions (e.g. allow LEFT into the sidebar).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun Modifier.dylandosFocusGroup(
    trapExit: Boolean = true,
    allowExitDirections: Set<FocusDirection>
): Modifier {
    if (allowExitDirections.isEmpty()) {
        return dylandosFocusGroup(trapExit = trapExit)
    }
    return this
        .focusRestorer()
        .focusProperties {
            enter = { FocusRequester.Default }
            if (trapExit) {
                exit = { direction ->
                    if (direction in allowExitDirections) FocusRequester.Default
                    else FocusRequester.Cancel
                }
            }
        }
}

/** Remembers a LazyListState and scrolls to [lastFocusedIndex] on first composition. */
@Composable
fun rememberFocusRestoringListState(lastFocusedIndex: Int = 0): LazyListState {
    val listState = rememberLazyListState()
    LaunchedEffect(lastFocusedIndex) {
        if (lastFocusedIndex > 0) {
            listState.animateScrollToItem(lastFocusedIndex)
        }
    }
    return listState
}
