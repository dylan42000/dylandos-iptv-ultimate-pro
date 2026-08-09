package com.dylandos.iptv.ultimate.ui.screens.guide

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.ui.theme.Accent
import com.dylandos.iptv.ultimate.ui.theme.AccentBright
import com.dylandos.iptv.ultimate.ui.theme.BgElevated
import com.dylandos.iptv.ultimate.ui.theme.BgSurface2
import com.dylandos.iptv.ultimate.ui.theme.BgSurface3
import com.dylandos.iptv.ultimate.ui.theme.BorderSubtle
import com.dylandos.iptv.ultimate.ui.theme.TextPrimary
import com.dylandos.iptv.ultimate.ui.theme.TextTertiary

/**
 * DYLANDOS IPTV ULTIMATE — 2D Canvas EPG Grid
 *
 * High-performance replacement for the ProgramRow + LazyColumn approach.
 *
 * Architecture:
 *  • LazyColumn provides vertical virtualization (only visible rows are composed)
 *  • Each row renders ALL its program blocks in a SINGLE Canvas draw call instead
 *    of creating hundreds of Box/Text composable nodes per channel row.
 *
 * This eliminates the composable tree explosion that caused EPG lag and OOM
 * crashes on Firestick devices when channels × programs scaled to thousands of nodes.
 */
@Composable
fun EpgCanvasGrid(
    channels:            List<XtreamChannel>,
    epgData:             Map<Int, List<XtreamEpgProgram>>,
    windowStartMs:       Long,
    windowEndMs:         Long,
    hScrollState:        ScrollState,
    listState:           LazyListState,
    rowHeight:           Dp,
    focusedChannelIndex: Int,
    dpPerMinute:         Dp             = 5.dp,
    modifier:            Modifier       = Modifier,
    onProgramSelected:   (channelId: Int, channelName: String, title: String, startMs: Long, endMs: Long, hasArchive: Boolean) -> Unit
) {
    val density      = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val now          = System.currentTimeMillis()

    val windowMinutes    = ((windowEndMs - windowStartMs) / 60_000L).toInt().coerceAtLeast(1)
    val totalGridWidthDp = dpPerMinute * windowMinutes

    // Compute pixel constants once per density change (stable across recompositions)
    val rowHeightPx   = with(density) { rowHeight.toPx()   }
    val dpPerMinPx    = with(density) { dpPerMinute.toPx() }
    val gapPx         = with(density) { 3.dp.toPx()        }
    val cornerPx      = with(density) { 6.dp.toPx()        }
    val strokePx      = with(density) { 1.5.dp.toPx()      }
    val paddingHPx    = with(density) { 8.dp.toPx()        }
    val progressBarH  = with(density) { 4.dp.toPx()        }

    // Text styles — allocated once, not per-cell
    val styleNormal = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
    val styleLive   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold,   color = AccentBright)
    val stylePast   = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Normal, color = TextTertiary)

    LazyColumn(
        state         = listState,
        modifier      = modifier.fillMaxSize(),
        userScrollEnabled = false
    ) {
        itemsIndexed(channels, key = { idx, ch -> "${ch.streamId}_$idx" }) { idx, channel ->
            val programs     = epgData[channel.streamId] ?: emptyList()
            val isFocusedRow = focusedChannelIndex == idx

            // Filter outside Canvas so the same list is usable in pointerInput
            val visibleProgs = remember(programs, windowStartMs, windowEndMs) {
                programs.filter { prog ->
                    prog.stopTimestamp  * 1000L > windowStartMs &&
                    prog.startTimestamp * 1000L < windowEndMs
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowHeight)
                    .horizontalScroll(hScrollState, enabled = false)
                    .pointerInput(visibleProgs, windowStartMs, hScrollState) {
                        detectTapGestures { tapOffset ->
                            val absX         = tapOffset.x + hScrollState.value.toFloat()
                            val minuteOffset = (absX / dpPerMinPx).toInt()
                            val tappedMs     = windowStartMs + minuteOffset * 60_000L
                            val prog         = visibleProgs.find { prog ->
                                val s = prog.startTimestamp * 1000L
                                val e = prog.stopTimestamp  * 1000L
                                tappedMs in s until e
                            }
                            if (prog != null) {
                                onProgramSelected(
                                    channel.streamId, channel.name,
                                    prog.title.ifBlank { channel.name },
                                    prog.startTimestamp * 1000L,
                                    prog.stopTimestamp  * 1000L,
                                    prog.hasArchive > 0
                                )
                            }
                        }
                    }
            ) {
                // ── Single Canvas draw call replaces N×Box composables per row ──
                Canvas(
                    modifier = Modifier
                        .width(totalGridWidthDp + 80.dp)
                        .fillMaxHeight()
                ) {
                    // Focused row highlight
                    if (isFocusedRow) {
                        drawRect(color = BgSurface2.copy(alpha = 0.45f))
                    }

                    if (visibleProgs.isEmpty()) {
                        // Empty placeholder block for channels with no EPG data
                        drawRoundRect(
                            color       = BgSurface3,
                            topLeft     = Offset(gapPx, gapPx),
                            size        = Size(size.width - gapPx * 2, rowHeightPx - gapPx * 2),
                            cornerRadius = CornerRadius(cornerPx)
                        )
                        return@Canvas
                    }

                    // Gap fill before the first visible program
                    val firstStartMs = visibleProgs.first().startTimestamp * 1000L
                    if (firstStartMs > windowStartMs) {
                        val gapMins  = ((firstStartMs - windowStartMs) / 60_000L).toInt()
                        val gapWidth = gapMins * dpPerMinPx - gapPx
                        if (gapWidth > 0f) {
                            drawRoundRect(
                                color       = BgSurface3.copy(alpha = 0.40f),
                                topLeft     = Offset(gapPx, gapPx),
                                size        = Size(gapWidth, rowHeightPx - gapPx * 2),
                                cornerRadius = CornerRadius(cornerPx)
                            )
                        }
                    }

                    // Draw each program block
                    for (prog in visibleProgs) {
                        val progStartMs  = prog.startTimestamp * 1000L
                        val progEndMs    = prog.stopTimestamp  * 1000L
                        val clampedStart = maxOf(progStartMs, windowStartMs)
                        val clampedEnd   = minOf(progEndMs,   windowEndMs)
                        val durationMins = ((clampedEnd - clampedStart) / 60_000L).toInt().coerceAtLeast(1)
                        val cellW        = durationMins * dpPerMinPx
                        val cellX        = ((clampedStart - windowStartMs) / 60_000L).toInt() * dpPerMinPx

                        val isLive = now in progStartMs until progEndMs
                        val isPast = progEndMs <= now

                        val cellColor = when {
                            isLive && isFocusedRow -> Accent.copy(alpha = 0.30f)
                            isLive                 -> Accent.copy(alpha = 0.16f)
                            isPast                 -> BgSurface3.copy(alpha = 0.55f)
                            isFocusedRow           -> BgElevated.copy(alpha = 0.80f)
                            else                   -> BgSurface3
                        }
                        val borderColor = when {
                            isFocusedRow && isLive -> Accent.copy(alpha = 0.80f)
                            isFocusedRow           -> Accent.copy(alpha = 0.50f)
                            isLive                 -> Accent.copy(alpha = 0.40f)
                            else                   -> BorderSubtle
                        }

                        val left = cellX + gapPx
                        val top  = gapPx
                        val w    = (cellW - gapPx * 2).coerceAtLeast(1f)
                        val h    = (rowHeightPx - gapPx * 2).coerceAtLeast(1f)

                        // Cell fill
                        drawRoundRect(
                            color        = cellColor,
                            topLeft      = Offset(left, top),
                            size         = Size(w, h),
                            cornerRadius = CornerRadius(cornerPx)
                        )
                        // Cell border
                        drawRoundRect(
                            color        = borderColor,
                            topLeft      = Offset(left, top),
                            size         = Size(w, h),
                            cornerRadius = CornerRadius(cornerPx),
                            style        = Stroke(width = strokePx)
                        )

                        // Live progress bar along the bottom of the cell
                        if (isLive && progEndMs > progStartMs) {
                            val fraction = ((now - progStartMs).toFloat() /
                                    (progEndMs - progStartMs).toFloat()).coerceIn(0f, 1f)
                            drawRoundRect(
                                color        = Accent.copy(alpha = 0.60f),
                                topLeft      = Offset(left, top + h - progressBarH),
                                size         = Size((w * fraction).coerceAtLeast(0f), progressBarH),
                                cornerRadius = CornerRadius(2f)
                            )
                        }

                        // Title text — skip if the cell is too narrow to be readable
                        if (cellW > 40f) {
                            val textStyle = when {
                                isLive -> styleLive
                                isPast -> stylePast
                                else   -> styleNormal
                            }
                            val title    = prog.title.ifBlank { "No Info" }
                            val maxW     = ((w - paddingHPx * 2).toInt()).coerceAtLeast(0)
                            if (maxW > 0) {
                                val layout  = textMeasurer.measure(
                                    text        = title,
                                    style       = textStyle,
                                    maxLines    = 1,
                                    constraints = Constraints.fixedWidth(maxW)
                                )
                                val textTop = (rowHeightPx - layout.size.height) / 2f
                                clipRect(
                                    left   = left + paddingHPx,
                                    top    = 0f,
                                    right  = left + w - gapPx,
                                    bottom = rowHeightPx
                                ) {
                                    drawText(layout, topLeft = Offset(left + paddingHPx, textTop))
                                }
                            }
                        }
                    }
                }
            }

        }
    }
}
