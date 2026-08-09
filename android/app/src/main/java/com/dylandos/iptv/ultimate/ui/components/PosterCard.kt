package com.dylandos.iptv.ultimate.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.painter.ColorPainter
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.dylandos.iptv.ultimate.ui.theme.DylandosPalette

/**
 * PosterCard — Premium VOD / Series poster card for the DYLANDOS UI.
 *
 * Features:
 * - 2:3 aspect-ratio poster image with bottom-scrim overlay
 * - Spring-based scale animation: 1.0 → 1.08 on D-pad focus
 * - Glowing cyan border on focus
 * - Star rating badge (top-right)
 * - Watched checkmark badge (top-left)
 * - Year label (bottom-left)
 * - Resume progress bar (bottom edge)
 * - Title below poster — turns cyan when focused
 *
 * @param title          VOD / series title
 * @param posterUrl      URL of the poster image
 * @param rating         Formatted rating string, e.g. "8.4" (optional)
 * @param year           Release year string, e.g. "2024" (optional)
 * @param resumeProgress Watch-resume progress 0.0–1.0 (hidden if 0)
 * @param isWatched      Show watched checkmark badge
 * @param onClick        Called on click/DPAD_CENTER
 * @param onLongClick    Called on long-press (add to list, etc.)
 * @param modifier       Modifier chain
 */
@Composable
fun PosterCard(
    title: String,
    posterUrl: String? = null,
    rating: String? = null,
    year: String? = null,
    resumeProgress: Float = 0f,
    isWatched: Boolean = false,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    val posterCacheKey = remember(title, posterUrl) {
        "poster_card_${title.hashCode()}_${posterUrl.orEmpty().hashCode()}"
    }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1.0f,
        animationSpec = tween(150),
        label = "posterScale"
    )

    Column(
        modifier = modifier
            .width(160.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
            .onFocusChanged { isFocused = it.isFocused },
    ) {
        // ── Poster image with overlaid badges ────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            // Poster image — error/fallback shows a dark surface box instead of invisible gap
            val context = LocalContext.current
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(posterUrl)
                    // Decode at thumbnail size — prevents GC thrashing from full-res bitmaps
                    // in scroll lists. 150×225 px is sufficient for a 2:3 poster at list density.
                    .size(150, 225)
                    .diskCacheKey(posterCacheKey)
                    .memoryCacheKey(posterCacheKey)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .networkCachePolicy(CachePolicy.ENABLED)
                    .crossfade(false)
                    .build(),
                contentDescription = title,
                modifier           = Modifier.fillMaxSize(),
                contentScale       = ContentScale.Crop,
                placeholder        = ColorPainter(DylandosPalette.Surface2),
                error              = ColorPainter(DylandosPalette.Surface2),
                fallback           = ColorPainter(DylandosPalette.Surface2),
            )

            // Cyan focus border overlay
            if (isFocused) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(
                            width = 3.dp,
                            color = DylandosPalette.Cyan,
                            shape = RoundedCornerShape(12.dp),
                        )
                )
            }

            // ── Top-right: Rating badge (solid red, world-class "IMDb" style) ─────
            if (!rating.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .background(
                            brush = Brush.verticalGradient(
                                listOf(Color(0xFFFF3B3B), Color(0xFFD11A1A))
                            ),
                            shape = RoundedCornerShape(7.dp),
                        )
                        .padding(horizontal = 7.dp, vertical = 3.dp),
                ) {
                    Text(
                        text       = rating,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = Color.White,
                        fontSize   = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    )
                }
            }

            // ── Top-left: Watched badge ───────────────────────────────────────
            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(24.dp)
                        .background(DylandosPalette.Success, CircleShape)
                        .padding(4.dp),
                ) {
                    Icon(
                        imageVector        = Icons.Default.Check,
                        contentDescription = "Watched",
                        tint               = Color.White,
                        modifier           = Modifier.fillMaxSize(),
                    )
                }
            }

            // ── Bottom gradient scrim ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xCC000000))
                        )
                    )
            )

            // ── Bottom-left: Year label ───────────────────────────────────────
            if (year != null) {
                Text(
                    text     = year,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = DylandosPalette.TextSecondary,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp),
                )
            }

            // ── Bottom edge: Resume progress bar ─────────────────────────────
            if (resumeProgress > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.BottomCenter),
                ) {
                    // Track
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DylandosPalette.Surface3)
                    )
                    // Fill
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = resumeProgress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(DylandosPalette.GradientPrimary)
                            )
                    )
                }
            }
        }

        // ── Title below poster ────────────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text     = title,
            style    = MaterialTheme.typography.bodySmall,
            color    = if (isFocused) DylandosPalette.Cyan else DylandosPalette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
