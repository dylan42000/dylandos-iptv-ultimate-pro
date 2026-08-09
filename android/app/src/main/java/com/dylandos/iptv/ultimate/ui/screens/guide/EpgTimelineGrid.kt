package com.dylandos.iptv.ultimate.ui.screens.guide

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dylandos.iptv.ultimate.ui.theme.DylandosPalette
import kotlinx.coroutines.launch
import java.util.Calendar

// ─────────────────────────────────────────────────────────────────────────────
// Data models for the Canvas EPG grid
// ─────────────────────────────────────────────────────────────────────────────

/**
 * One row in the EPG grid — one channel plus its programme list.
 *
 * Build these from [com.dylandos.iptv.ultimate.data.model.XtreamChannel] +
 * [com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram] in the ViewModel
 * before passing to [EpgTimelineGrid].
 */
data class EpgChannelRow(
    val channelId: Int,
    val channelName: String,
    /** Optional human-readable channel number shown in the sidebar badge. */
    val channelNumber: String? = null,
    val programs: List<EpgProgramCell> = emptyList(),
)

/** A single programme cell to be drawn in the grid. */
data class EpgProgramCell(
    val id: String,
    val title: String,
    val description: String = "",
    /** Start time in Unix milliseconds. */
    val startMs: Long,
    /** End time in Unix milliseconds. */
    val endMs: Long,
    /** EPG category string — used for colour-coding (case-insensitive). */
    val category: String = "",
    /** True if this programme is currently airing (drives progress bar). */
    val isNowPlaying: Boolean = false,
    val hasArchive: Boolean = false,
)

/** Uniquely identifies a focused (or selected) cell in the grid. */
data class EpgCellIndex(
    val channelIndex: Int,
    val programIndex: Int,
)

// ─────────────────────────────────────────────────────────────────────────────
// Main composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * EpgTimelineGrid — High-performance EPG grid rendered entirely on Canvas.
 *
 * PERFORMANCE: Uses direct Canvas drawing instead of Compose LazyColumn/LazyRow
 * because a full guide can have 500+ channels × 200+ programmes.  LazyColumn
 * re-composition at that scale causes noticeable jank on Firestick 4K.
 *
 * Only cells that intersect the current viewport rectangle are drawn — O(visible).
 *
 * Layout:
 * ```
 * ┌──────────────┬──────────────────────────────────────────┐
 * │  "CHANNELS"  │  Time ruler (fixed top, scrolls X only)  │
 * ├──────────────┼──────────────────────────────────────────┤
 * │  Channel     │  Programme cells                         │
 * │  sidebar     │  (scrolls both X and Y)                  │
 * │  (fixed      │                                          │
 * │   left,      │  ← current-time red line                 │
 * │   scrolls Y) │                                          │
 * └──────────────┴──────────────────────────────────────────┘
 * ```
 *
 * @param channels          Ordered list of channel rows (sidebar + programme blocks)
 * @param focusedCellIndex  D-pad focused cell — drawn with a cyan outline
 * @param currentTimeMs     System clock in ms — drives the "NOW" red indicator
 * @param timeWindowStartMs Start of the total scrollable time window (ms)
 * @param timeWindowEndMs   End of the total scrollable time window (ms)
 * @param onCellSelected    Fired on DPAD_CENTER / click
 * @param onCellFocused     Fired when focus moves to a cell (for D-pad navigation)
 * @param modifier          Modifier chain
 */
@Composable
fun EpgTimelineGrid(
    channels: List<EpgChannelRow>,
    focusedCellIndex: EpgCellIndex?,
    currentTimeMs: Long,
    timeWindowStartMs: Long,
    timeWindowEndMs: Long,
    onCellSelected: (EpgCellIndex) -> Unit,
    onCellFocused: (EpgCellIndex) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density       = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val textMeasurer  = rememberTextMeasurer()

    // Convert layout constants dp → px once (stable across recompositions)
    val sidebarWidthPx    = with(density) { 180.dp.toPx() }
    val headerHeightPx    = with(density) { 48.dp.toPx()  }
    val rowHeightPx       = with(density) { 72.dp.toPx()  }
    val minCellWidthPx    = with(density) { 80.dp.toPx()  }
    val pixelsPerMinute   = with(density) { 3.dp.toPx()   }  // 3 dp/min → 180 dp/hr

    // Total content dimensions
    val totalTimeMinutes   = ((timeWindowEndMs - timeWindowStartMs) / 60_000L).toFloat()
    val totalContentWidth  = totalTimeMinutes * pixelsPerMinute
    val totalContentHeight = channels.size * rowHeightPx

    // Initial X scroll: place "now" slightly to the right of the visible left edge
    val initialScrollX = remember(timeWindowStartMs, currentTimeMs, pixelsPerMinute) {
        ((currentTimeMs - timeWindowStartMs) / 60_000f * pixelsPerMinute - 300f)
            .coerceAtLeast(0f)
    }

    // Animatable scroll state — used for both drag and fling
    val scrollXAnim = remember { Animatable(initialScrollX) }
    val scrollYAnim = remember { Animatable(0f) }
    val scrollX = scrollXAnim.value
    val scrollY = scrollYAnim.value

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(totalContentWidth, totalContentHeight) {
                val velocityTracker = VelocityTracker()
                detectDragGestures(
                    onDragEnd = {
                        // Fling with exponential decay
                        val velocity = velocityTracker.calculateVelocity()
                        velocityTracker.resetTracking()
                        val maxX = (totalContentWidth  - size.width ).coerceAtLeast(0f)
                        val maxY = (totalContentHeight - size.height).coerceAtLeast(0f)
                        coroutineScope.launch {
                            scrollXAnim.updateBounds(0f, maxX)
                            scrollYAnim.updateBounds(0f, maxY)
                            val decay = exponentialDecay<Float>(frictionMultiplier = 2.5f)
                            launch { scrollXAnim.animateDecay(-velocity.x / density.density, decay) }
                            launch { scrollYAnim.animateDecay(-velocity.y / density.density, decay) }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        val maxX = (totalContentWidth  - size.width ).coerceAtLeast(0f)
                        val maxY = (totalContentHeight - size.height).coerceAtLeast(0f)
                        coroutineScope.launch {
                            scrollXAnim.snapTo((scrollXAnim.value - dragAmount.x).coerceIn(0f, maxX))
                            scrollYAnim.snapTo((scrollYAnim.value - dragAmount.y).coerceIn(0f, maxY))
                        }
                    },
                )
            },
    ) {
        val vpWidth  = size.width
        val vpHeight = size.height

        // 1 ── Deepest background fill ──────────────────────────────────────
        drawRect(DylandosPalette.SurfaceBase)

        // 2 ── Programme grid (scrolls both axes, clipped to content area) ──
        clipRect(
            left   = sidebarWidthPx,
            top    = headerHeightPx,
            right  = vpWidth,
            bottom = vpHeight,
        ) {
            drawProgrammeGrid(
                textMeasurer      = textMeasurer,
                channels          = channels,
                scrollX           = scrollX,
                scrollY           = scrollY,
                timeWindowStartMs = timeWindowStartMs,
                pixelsPerMinute   = pixelsPerMinute,
                sidebarWidth      = sidebarWidthPx,
                headerHeight      = headerHeightPx,
                rowHeight         = rowHeightPx,
                minCellWidth      = minCellWidthPx,
                focusedCell       = focusedCellIndex,
                viewportWidth     = vpWidth,
                viewportHeight    = vpHeight,
            )
        }

        // 3 ── Time header (fixed top, scrolls X only) ─────────────────────
        clipRect(
            left   = sidebarWidthPx,
            top    = 0f,
            right  = vpWidth,
            bottom = headerHeightPx,
        ) {
            drawTimeHeader(
                textMeasurer      = textMeasurer,
                scrollX           = scrollX,
                timeWindowStartMs = timeWindowStartMs,
                pixelsPerMinute   = pixelsPerMinute,
                headerHeight      = headerHeightPx,
                sidebarWidth      = sidebarWidthPx,
                viewportWidth     = vpWidth,
                currentTimeMs     = currentTimeMs,
            )
        }

        // 4 ── Channel sidebar (fixed left, scrolls Y only) ────────────────
        clipRect(
            left   = 0f,
            top    = headerHeightPx,
            right  = sidebarWidthPx,
            bottom = vpHeight,
        ) {
            drawChannelSidebar(
                textMeasurer   = textMeasurer,
                channels       = channels,
                scrollY        = scrollY,
                sidebarWidth   = sidebarWidthPx,
                rowHeight      = rowHeightPx,
                headerHeight   = headerHeightPx,
                viewportHeight = vpHeight,
            )
        }

        // 5 ── Corner tile (top-left, over both header and sidebar) ─────────
        drawRect(
            color   = DylandosPalette.Surface1,
            topLeft = Offset.Zero,
            size    = Size(sidebarWidthPx, headerHeightPx),
        )
        drawText(
            textMeasurer = textMeasurer,
            text         = "CHANNELS",
            topLeft      = Offset(12f, headerHeightPx / 2f - 7f),
            style        = TextStyle(
                fontSize      = 10.sp,
                fontWeight    = FontWeight.Bold,
                color         = DylandosPalette.TextSecondary,
                letterSpacing = 1.5.sp,
            ),
        )

        // 6 ── Current-time indicator (vertical red line + top triangle) ────
        val nowOffsetMinutes = (currentTimeMs - timeWindowStartMs) / 60_000f
        val nowX = sidebarWidthPx + nowOffsetMinutes * pixelsPerMinute - scrollX
        if (nowX in sidebarWidthPx..vpWidth) {
            drawLine(
                color       = DylandosPalette.LiveRed,
                start       = Offset(nowX, headerHeightPx),
                end         = Offset(nowX, vpHeight),
                strokeWidth = 2f,
            )
            // Small downward-pointing triangle at the top edge of the line
            drawPath(
                path  = Path().apply {
                    moveTo(nowX, headerHeightPx)
                    lineTo(nowX - 6f, headerHeightPx - 10f)
                    lineTo(nowX + 6f, headerHeightPx - 10f)
                    close()
                },
                color = DylandosPalette.LiveRed,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private Canvas drawing helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Draws the horizontal time-of-day ruler pinned to the top of the grid.
 *
 * Draws:
 *  - Solid Surface1 background
 *  - Hour tick + "HH:00" label for each visible hour
 *  - Lighter half-hour tick at +30 min
 *  - Bottom separator line
 */
private fun DrawScope.drawTimeHeader(
    textMeasurer: TextMeasurer,
    scrollX: Float,
    timeWindowStartMs: Long,
    pixelsPerMinute: Float,
    headerHeight: Float,
    sidebarWidth: Float,
    viewportWidth: Float,
    currentTimeMs: Long,
) {
    // Background
    drawRect(
        color   = DylandosPalette.Surface1,
        topLeft = Offset(0f, 0f),
        size    = Size(viewportWidth, headerHeight),
    )
    // Bottom border
    drawLine(
        color       = DylandosPalette.GlassBorder,
        start       = Offset(0f, headerHeight - 1f),
        end         = Offset(viewportWidth, headerHeight - 1f),
        strokeWidth = 1f,
    )

    // Compute which hours are visible
    val firstVisibleMinute = (scrollX / pixelsPerMinute).toLong()
    val firstHour          = firstVisibleMinute / 60L
    val hoursToShow        = ((viewportWidth - sidebarWidth) / (pixelsPerMinute * 60f)).toInt() + 3

    val cal = Calendar.getInstance().apply {
        timeInMillis = timeWindowStartMs
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        set(Calendar.MINUTE, 0)
        add(Calendar.HOUR_OF_DAY, firstHour.toInt())
    }

    repeat(hoursToShow) {
        val hourMs           = cal.timeInMillis
        val minutesFromStart = (hourMs - timeWindowStartMs) / 60_000f
        val xPos             = sidebarWidth + minutesFromStart * pixelsPerMinute - scrollX

        // Skip if this tick is left of the sidebar
        if (xPos < sidebarWidth - 1f) {
            cal.add(Calendar.HOUR_OF_DAY, 1)
            return@repeat
        }
        // Stop if past the right edge
        if (xPos > viewportWidth + 1f) return@repeat

        // Hour tick line
        drawLine(
            color       = DylandosPalette.GlassBorder,
            start       = Offset(xPos, 0f),
            end         = Offset(xPos, headerHeight),
            strokeWidth = 1.5f,
        )

        // Hour label (cyan for the current hour)
        val isCurrent = cal.get(Calendar.HOUR_OF_DAY) ==
                Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        drawText(
            textMeasurer = textMeasurer,
            text         = "%02d:00".format(cal.get(Calendar.HOUR_OF_DAY)),
            topLeft      = Offset(xPos + 4f, headerHeight / 2f - 8f),
            style        = TextStyle(
                fontSize   = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color      = if (isCurrent) DylandosPalette.Cyan else DylandosPalette.TextPrimary,
            ),
        )

        // Half-hour tick (shorter, dimmer)
        val halfX = xPos + pixelsPerMinute * 30f
        if (halfX < viewportWidth) {
            drawLine(
                color       = DylandosPalette.GlassBorder.copy(alpha = 0.5f),
                start       = Offset(halfX, headerHeight * 0.55f),
                end         = Offset(halfX, headerHeight),
                strokeWidth = 1f,
            )
        }

        cal.add(Calendar.HOUR_OF_DAY, 1)
    }
}

/**
 * Draws the vertical channel list pinned to the left of the grid.
 *
 * Draws:
 *  - Surface0 background + right border
 *  - Channel number badge (top-left of each row)
 *  - Channel name (centred vertically in the row)
 *  - Row separator lines
 */
private fun DrawScope.drawChannelSidebar(
    textMeasurer: TextMeasurer,
    channels: List<EpgChannelRow>,
    scrollY: Float,
    sidebarWidth: Float,
    rowHeight: Float,
    headerHeight: Float,
    viewportHeight: Float,
) {
    // Background
    drawRect(
        color   = DylandosPalette.Surface0,
        topLeft = Offset(0f, 0f),
        size    = Size(sidebarWidth, viewportHeight),
    )
    // Right border
    drawLine(
        color       = DylandosPalette.GlassBorder,
        start       = Offset(sidebarWidth - 1f, 0f),
        end         = Offset(sidebarWidth - 1f, viewportHeight),
        strokeWidth = 1f,
    )

    val firstRow = (scrollY / rowHeight).toInt().coerceAtLeast(0)
    val lastRow  = ((scrollY + viewportHeight - headerHeight) / rowHeight)
        .toInt().coerceAtMost(channels.size - 1)

    for (rowIdx in firstRow..lastRow) {
        if (rowIdx >= channels.size) break
        val channel = channels[rowIdx]
        val rowTop  = headerHeight + rowIdx * rowHeight - scrollY
        if (rowTop > viewportHeight) break

        // Row separator
        drawLine(
            color       = DylandosPalette.GlassBorder.copy(alpha = 0.4f),
            start       = Offset(0f, rowTop + rowHeight - 1f),
            end         = Offset(sidebarWidth, rowTop + rowHeight - 1f),
            strokeWidth = 0.5f,
        )

        // Channel number badge
        val numberText = channel.channelNumber ?: (rowIdx + 1).toString()
        drawText(
            textMeasurer = textMeasurer,
            text         = numberText,
            topLeft      = Offset(8f, rowTop + 8f),
            style        = TextStyle(
                fontSize   = 10.sp,
                fontWeight = FontWeight.Bold,
                color      = DylandosPalette.Cyan,
            ),
        )

        // Channel name (centred in row, ellipsised)
        val nameMaxWidth = (sidebarWidth - 16f).toInt().coerceAtLeast(1)
        val nameMaxHeight = (rowHeight * 0.65f).toInt().coerceAtLeast(1)
        drawText(
            textMeasurer = textMeasurer,
            text         = channel.channelName,
            topLeft      = Offset(8f, rowTop + rowHeight / 2f - 12f),
            style        = TextStyle(
                fontSize   = 13.sp,
                fontWeight = FontWeight.Medium,
                color      = DylandosPalette.TextPrimary,
            ),
            maxLines     = 2,
            overflow     = TextOverflow.Ellipsis,
            size         = Size(nameMaxWidth.toFloat(), nameMaxHeight.toFloat()),
        )
    }
}

/**
 * Draws the main programme block grid (both axes scroll).
 *
 * Performance strategy — only draws cells that satisfy:
 *   1. Their channel row is within the vertical viewport
 *   2. Their time range overlaps the horizontal viewport
 *
 * Cell appearance:
 *  - Background: category-coded colour (alpha 0.25)
 *  - NOW PLAYING: Surface3 bg + bottom progress strip
 *  - FOCUSED: CyanAlpha20 bg + full cyan outline
 *  - Title text clipped to cell bounds, ellipsised at 2 lines
 */
private fun DrawScope.drawProgrammeGrid(
    textMeasurer: TextMeasurer,
    channels: List<EpgChannelRow>,
    scrollX: Float,
    scrollY: Float,
    timeWindowStartMs: Long,
    pixelsPerMinute: Float,
    sidebarWidth: Float,
    headerHeight: Float,
    rowHeight: Float,
    minCellWidth: Float,
    focusedCell: EpgCellIndex?,
    viewportWidth: Float,
    viewportHeight: Float,
) {
    // Visible time range in ms (for horizontal culling)
    val visibleStartMs = timeWindowStartMs +
            ((scrollX / pixelsPerMinute) * 60_000f).toLong()
    val visibleEndMs   = timeWindowStartMs +
            (((scrollX + viewportWidth - sidebarWidth) / pixelsPerMinute) * 60_000f).toLong()

    val firstRow = (scrollY / rowHeight).toInt().coerceAtLeast(0)
    val lastRow  = ((scrollY + viewportHeight - headerHeight) / rowHeight)
        .toInt().coerceAtMost(channels.size - 1)

    for (rowIdx in firstRow..lastRow) {
        if (rowIdx >= channels.size) break
        val channel = channels[rowIdx]
        val rowTop  = headerHeight + rowIdx * rowHeight - scrollY
        if (rowTop > viewportHeight) break

        // Horizontal row separator
        drawLine(
            color       = DylandosPalette.GlassBorder.copy(alpha = 0.3f),
            start       = Offset(sidebarWidth, rowTop + rowHeight - 1f),
            end         = Offset(viewportWidth, rowTop + rowHeight - 1f),
            strokeWidth = 0.5f,
        )

        for ((progIdx, program) in channel.programs.withIndex()) {
            // Horizontal culling
            if (program.endMs   <= visibleStartMs) continue
            if (program.startMs >= visibleEndMs)   break

            val minutesFromStart = (program.startMs - timeWindowStartMs) / 60_000f
            val durationMinutes  = ((program.endMs  - program.startMs)   / 60_000f)
                .coerceAtLeast(minCellWidth / pixelsPerMinute)

            val cellLeft  = sidebarWidth + minutesFromStart * pixelsPerMinute - scrollX
            val cellWidth = (durationMinutes * pixelsPerMinute).coerceAtLeast(minCellWidth)
            val cellRight = cellLeft + cellWidth

            // Cell entirely off-screen?
            if (cellRight < sidebarWidth) continue
            if (cellLeft  > viewportWidth) break

            val isFocused = focusedCell?.channelIndex == rowIdx &&
                            focusedCell.programIndex  == progIdx

            // ── Cell insets (4 dp padding all around) ──
            val pad      = 4f
            val innerL   = cellLeft + pad
            val innerT   = rowTop   + pad
            val innerW   = (cellWidth  - pad * 2f).coerceAtLeast(0f)
            val innerH   = (rowHeight  - pad * 2f).coerceAtLeast(0f)
            val corner   = CornerRadius(6f, 6f)

            // ── Background ──
            val bgColor = when {
                isFocused            -> DylandosPalette.CyanAlpha20
                program.isNowPlaying -> DylandosPalette.Surface3
                else                 -> categoryColor(program.category)
            }
            drawRoundRect(
                color        = bgColor,
                topLeft      = Offset(innerL, innerT),
                size         = Size(innerW, innerH),
                cornerRadius = corner,
            )

            // ── Subtle glass border ──
            drawRoundRect(
                color        = DylandosPalette.GlassBorder.copy(
                    alpha = if (isFocused) 1f else 0.3f
                ),
                topLeft      = Offset(innerL, innerT),
                size         = Size(innerW, innerH),
                cornerRadius = corner,
                style        = Stroke(width = if (isFocused) 2f else 0.5f),
            )

            // ── Cyan focus ring ──
            if (isFocused) {
                drawRoundRect(
                    color        = DylandosPalette.Cyan,
                    topLeft      = Offset(innerL, innerT),
                    size         = Size(innerW, innerH),
                    cornerRadius = corner,
                    style        = Stroke(width = 2f),
                )
            }

            // ── Now-playing progress strip (bottom 4px of cell) ──
            if (program.isNowPlaying && program.endMs > program.startMs) {
                val nowMs    = System.currentTimeMillis()
                val progress = ((nowMs - program.startMs).toFloat() /
                                (program.endMs - program.startMs).toFloat())
                    .coerceIn(0f, 1f)
                drawRoundRect(
                    color        = DylandosPalette.Cyan.copy(alpha = 0.5f),
                    topLeft      = Offset(innerL, innerT + innerH - 4f),
                    size         = Size(innerW * progress, 4f),
                    cornerRadius = CornerRadius(2f, 2f),
                )
            }

            // ── Programme title text (clipped, 2-line max) ──
            if (innerW > 20f && innerH > 14f) {
                drawText(
                    textMeasurer = textMeasurer,
                    text         = program.title,
                    topLeft      = Offset(innerL + 6f, innerT + 6f),
                    style        = TextStyle(
                        fontSize   = 12.sp,
                        fontWeight = if (program.isNowPlaying) FontWeight.SemiBold
                                     else FontWeight.Normal,
                        color      = if (isFocused) DylandosPalette.Cyan
                                     else DylandosPalette.TextPrimary,
                    ),
                    maxLines     = 2,
                    overflow     = TextOverflow.Ellipsis,
                    size         = Size(
                        width  = (innerW - 12f).coerceAtLeast(1f),
                        height = (innerH - 12f).coerceAtLeast(1f),
                    ),
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: category → colour
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Maps an EPG category string to a subtle tinted cell background.
 * Colours are 25 % opacity versions of the [DylandosPalette] category tokens.
 */
private fun categoryColor(category: String): Color {
    val lc = category.lowercase()
    return when {
        "sport"  in lc || "football" in lc || "soccer" in lc  ->
            DylandosPalette.CategorySports.copy(alpha = 0.25f)
        "news"   in lc || "current"  in lc || "affairs" in lc ->
            DylandosPalette.CategoryNews.copy(alpha = 0.25f)
        "movie"  in lc || "film"     in lc || "cinema"  in lc ->
            DylandosPalette.CategoryMovies.copy(alpha = 0.25f)
        "kid"    in lc || "child"    in lc || "cartoon" in lc ->
            DylandosPalette.CategoryKids.copy(alpha = 0.25f)
        "music"  in lc || "concert"  in lc                    ->
            DylandosPalette.CategoryMusic.copy(alpha = 0.25f)
        "doc"    in lc || "nature"   in lc || "science" in lc ->
            DylandosPalette.CategoryDocumentary.copy(alpha = 0.25f)
        "entertainment" in lc || "variety" in lc              ->
            DylandosPalette.CategoryEntertainment.copy(alpha = 0.25f)
        else                                                   ->
            DylandosPalette.Surface2
    }
}
