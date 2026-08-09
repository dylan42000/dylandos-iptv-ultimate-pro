package com.dylandos.iptv.ultimate.player.subtitle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dylandos.iptv.ultimate.ui.focus.DylandosCyan
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * DYLANDOS IPTV ULTIMATE — Subtitle & Closed Caption Picker
 *
 * Full D-pad navigable sheet shown when the user presses the CC button during playback.
 *
 * Two tabs:
 *   TRACKS     — list all embedded CC/subtitle tracks; Load External SRT (VOD only)
 *   APPEARANCE — size, color, background, sync offset
 *
 * Usage (in PlayerScreen):
 *   if (showSubtitlePicker) {
 *       SubtitlePickerSheet(
 *           subtitleManager = subtitleManager,
 *           isLiveTv = isLiveTv,
 *           onDismiss = { showSubtitlePicker = false },
 *           onLoadExternal = { /* open file picker */ }
 *       )
 *   }
 */
@Composable
fun SubtitlePickerSheet(
    subtitleManager: LibVlcSubtitleManager,
    isLiveTv: Boolean,
    onDismiss: () -> Unit,
    onLoadExternal: (() -> Unit)? = null   // null for Live TV
) {
    val tracks       by subtitleManager.availableTracks.collectAsState()
    val currentId    by subtitleManager.currentTrackId.collectAsState()
    val settings     by subtitleManager.settings.collectAsState()

    var activeTab by remember { mutableStateOf(SubtitleTab.TRACKS) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.55f)
            .background(Color(0xEA0D1117), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ─────────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Subtitles & Closed Captions",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Box(modifier = Modifier.dylandosFocusable(onClick = onDismiss).padding(4.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(Modifier.height(8.dp))

            // ── Tab Row ────────────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleTab.entries.forEach { tab ->
                    val selected = activeTab == tab
                    Box(
                        modifier = Modifier
                            .dylandosFocusable(onClick = { activeTab = tab })
                            .background(
                                if (selected) AccentSurface else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            tab.label,
                            color = if (selected) Accent else TextSecondary,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = BorderSubtle)
            Spacer(Modifier.height(8.dp))

            // ── Tab Content ────────────────────────────────────────────────────
            when (activeTab) {
                SubtitleTab.TRACKS -> TracksContent(
                    tracks       = tracks,
                    currentId    = currentId,
                    isLiveTv     = isLiveTv,
                    onSelect     = { id ->
                        subtitleManager.selectTrack(id)
                        onDismiss()
                    },
                    onDisable    = {
                        subtitleManager.disableSubtitles()
                        onDismiss()
                    },
                    onLoadExternal = onLoadExternal
                )
                SubtitleTab.APPEARANCE -> AppearanceContent(
                    settings = settings,
                    onSettingsChanged = subtitleManager::updateSettings
                )
            }
        }
    }
}

// ── Tracks tab ─────────────────────────────────────────────────────────────────

@Composable
private fun TracksContent(
    tracks: List<SubtitleTrack>,
    currentId: Int,
    isLiveTv: Boolean,
    onSelect: (Int) -> Unit,
    onDisable: () -> Unit,
    onLoadExternal: (() -> Unit)?
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // "Off" row
        item {
            TrackRow(
                label       = "Off",
                badge       = null,
                icon        = Icons.Default.ClosedCaptionDisabled,
                isSelected  = currentId == -1,
                onClick     = onDisable
            )
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    text = if (isLiveTv)
                        "No closed captions detected in this stream."
                    else
                        "No subtitle tracks found in this content.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
        } else {
            items(tracks, key = { it.id }) { track ->
                TrackRow(
                    label      = buildLabel(track),
                    badge      = track.type.badgeLabel,
                    icon       = when (track.type) {
                        SubtitleTrackType.CLOSED_CAPTION -> Icons.Default.ClosedCaption
                        SubtitleTrackType.SDH            -> Icons.Default.HearingDisabled
                        else                             -> Icons.Default.Subtitles
                    },
                    isSelected = track.id == currentId,
                    onClick    = { onSelect(track.id) }
                )
            }
        }

        // External SRT option — VOD only
        if (!isLiveTv && onLoadExternal != null) {
            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = BorderSubtle)
                Spacer(Modifier.height(8.dp))
                TrackRow(
                    label      = "Load External Subtitle (SRT / VTT)",
                    badge      = null,
                    icon       = Icons.Default.FolderOpen,
                    isSelected = false,
                    onClick    = onLoadExternal
                )
            }
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    badge: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val focusBg by remember(isSelected) { mutableStateOf(if (isSelected) AccentSurface else Color.Transparent) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .dylandosFocusable(onClick = onClick)
            .background(focusBg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) Accent else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            color = if (isSelected) TextPrimary else TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (badge != null) {
            Text(
                text = badge,
                style = MaterialTheme.typography.labelSmall,
                color = Accent,
                modifier = Modifier
                    .background(AccentSurface, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = Accent,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

// ── Appearance tab ─────────────────────────────────────────────────────────────

@Composable
private fun AppearanceContent(
    settings: SubtitleSettings,
    onSettingsChanged: (SubtitleSettings) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Text Size
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text Size", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small" to 70, "Normal" to 100, "Large" to 140, "Huge" to 200)
                        .forEach { (label, pct) ->
                            val selected = settings.sizePercent == pct
                            Box(
                                modifier = Modifier
                                    .dylandosFocusable(
                                        onClick = { onSettingsChanged(settings.copy(sizePercent = pct)) }
                                    )
                                    .background(
                                        if (selected) AccentSurface else BgSurface2,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .border(
                                        width = if (selected) 1.dp else 0.dp,
                                        color = if (selected) Accent else Color.Transparent,
                                        shape = RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    label,
                                    color = if (selected) Accent else TextSecondary,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                }
            }
        }

        // Text Color swatches
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Text Color", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    listOf(
                        "White"  to 0xFFFFFFFF,
                        "Yellow" to 0xFFFFFF00,
                        "Green"  to 0xFF00FF99,
                        "Cyan"   to 0xFF00D4FF
                    ).forEach { (name, colorLong) ->
                        val colorVal = colorLong.toLong()
                        val selected = settings.foregroundColor == colorVal
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .background(Color(colorVal), CircleShape)
                                .then(
                                    if (selected) Modifier.border(3.dp, Accent, CircleShape)
                                    else Modifier.border(1.dp, BorderDefault, CircleShape)
                                )
                                .dylandosFocusable(
                                    onClick = { onSettingsChanged(settings.copy(foregroundColor = colorVal)) },
                                    shape = RoundedCornerShape(50)
                                )
                        )
                    }
                }
            }
        }

        // Sync offset
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Sync Offset: ${settings.delayMs}ms",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("− 500ms" to -500L, "− 250ms" to -250L, "Reset" to Long.MAX_VALUE, "+ 250ms" to 250L, "+ 500ms" to 500L)
                        .forEach { (label, delta) ->
                            Box(
                                modifier = Modifier
                                    .dylandosFocusable(
                                        onClick = {
                                            val newDelay = if (delta == Long.MAX_VALUE) 0L
                                            else settings.delayMs + delta
                                            onSettingsChanged(settings.copy(delayMs = newDelay))
                                        }
                                    )
                                    .background(BgSurface2, RoundedCornerShape(8.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private enum class SubtitleTab(val label: String) {
    TRACKS("Tracks"),
    APPEARANCE("Appearance")
}

private fun buildLabel(track: SubtitleTrack): String = buildString {
    append(track.name)
    if (track.language.isNotEmpty() && !track.name.contains(track.language, ignoreCase = true)) {
        append(" (${track.language})")
    }
}

private val SubtitleTrackType.badgeLabel: String?
    get() = when (this) {
        SubtitleTrackType.CLOSED_CAPTION -> "CC"
        SubtitleTrackType.SDH            -> "SDH"
        SubtitleTrackType.FORCED         -> "Forced"
        SubtitleTrackType.SUBTITLE       -> null
    }
