package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.theme.DylandosPalette

/**
 * ChannelCard — Premium live-TV channel card for the DYLANDOS grid.
 *
 * Features:
 * - Spring-based scale animation: 1.0 → 1.08 on D-pad focus
 * - Glowing cyan border on focus (via GlassCard)
 * - Pulsing live-dot indicator
 * - Programme name + progress bar
 * - Channel number badge
 *
 * @param channelName    Display name of the channel
 * @param channelNumber  Optional channel number string (e.g. "042")
 * @param logoUrl        URL for the channel logo image
 * @param nowPlaying     Currently airing programme title (optional)
 * @param progress       Programme elapsed ratio 0.0–1.0 (hidden if 0)
 * @param isLive         Show pulsing live indicator
 * @param onClick        Called on click/DPAD_CENTER
 * @param onLongClick    Called on long-press (add to favourites, etc.)
 * @param modifier       Modifier chain
 */
@Composable
fun ChannelCard(
    channelName: String,
    channelNumber: String? = null,
    logoUrl: String? = null,
    nowPlaying: String? = null,
    progress: Float = 0f,
    isLive: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(150),
        label = "channelScale"
    )

    // Infinite pulse animation for the live-dot
    val infiniteTransition = rememberInfiniteTransition(label = "livePulse")
    val liveDotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 0.3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotAlpha",
    )

    val interactionSource = remember { MutableInteractionSource() }

    GlassCard(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.isRemoteConfirmKey()) {
                    onClick()
                    true
                } else false
            }
            .width(200.dp)
            .height(140.dp),
        isFocused = isFocused,
        cornerRadius = 12.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ── Top row: Logo + Channel number badge ──────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                // Channel logo — error/fallback shows dark surface box instead of invisible gap
                AsyncImage(
                    model             = logoUrl,
                    contentDescription = channelName,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(DylandosPalette.Surface2),
                    contentScale = ContentScale.Fit,
                    placeholder  = ColorPainter(DylandosPalette.Surface2),
                    error        = ColorPainter(DylandosPalette.Surface2),
                    fallback     = ColorPainter(DylandosPalette.Surface2),
                )

                if (channelNumber != null) {
                    Box(
                        modifier = Modifier
                            .background(
                                DylandosPalette.CyanAlpha20,
                                RoundedCornerShape(6.dp),
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text  = channelNumber,
                            style = MaterialTheme.typography.labelSmall,
                            color = DylandosPalette.Cyan,
                        )
                    }
                }
            }

            // ── Channel name ─────────────────────────────────────────────────
            Text(
                text     = channelName,
                style    = MaterialTheme.typography.titleSmall,
                color    = DylandosPalette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            // ── Bottom: Now-playing info + progress bar ───────────────────────
            Column {
                if (nowPlaying != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // Pulsing live dot
                        if (isLive) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(
                                        DylandosPalette.LiveRed.copy(alpha = liveDotAlpha)
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }

                        Text(
                            text     = nowPlaying,
                            style    = MaterialTheme.typography.bodySmall,
                            color    = DylandosPalette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // Progress bar
                if (progress > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(DylandosPalette.Surface3)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction = progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    Brush.horizontalGradient(DylandosPalette.GradientPrimary)
                                )
                        )
                    }
                }
            }
        }
    }
}
