package com.dylandos.iptv.ultimate.ui.screens.replay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*
import com.dylandos.iptv.ultimate.ui.util.TimeFormatter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ReplayScreen(navController: NavController, viewModel: ReplayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Column(
        Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
            .padding(16.dp)
    ) {
        // ── Top Header ──────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.History, "Replay", tint = Accent, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Catch-Up / Replay (Up to 7 Days)",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Past week's provider recordings by channel — instant rewind & playback",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            Button(
                onClick = viewModel::refreshChannels,
                colors = ButtonDefaults.buttonColors(containerColor = AccentSurface),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(Icons.Default.Refresh, null, tint = Accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Refresh", color = TextPrimary, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        if (!state.enabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Provider Replay is turned off. Enable Show Replay Tab in Settings → Playback.", color = TextSecondary)
            }
            return@Column
        }

        state.error?.let {
            Text(it, color = StatusError, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
        }

        if (state.loadingChannels) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Accent)
            }
            return@Column
        }

        if (state.allChannels.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This provider did not report any archive-capable live channels.", color = TextSecondary)
            }
            return@Column
        }

        // ── Main Content: Split Channel List & Archive Shows ───────────────────
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // Left Column: Channels (with Search)
            Column(Modifier.weight(0.38f).fillMaxHeight()) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = viewModel::onSearchQueryChanged,
                    placeholder = { Text("Filter channels...", color = TextTertiary, fontSize = 12.sp) },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = Accent, modifier = Modifier.size(18.dp)) },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = BorderDefault,
                        focusedContainerColor = BgSurface2,
                        unfocusedContainerColor = BgSurface2
                    )
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.channels, key = { it.streamId }) { channel ->
                        ReplayChannelRow(
                            channel = channel,
                            selected = channel.streamId == state.selectedChannel?.streamId,
                            onClick = { viewModel.selectChannel(channel) }
                        )
                    }
                }
            }

            // Right Column: Past Programmes by Day
            Column(Modifier.weight(0.62f).fillMaxHeight()) {
                val selected = state.selectedChannel
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        selected?.name ?: "Select a channel",
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (state.programs.isNotEmpty()) {
                        Surface(
                            color = Accent.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f))
                        ) {
                            Text(
                                "${state.programs.size} shows available",
                                color = Accent,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (state.loadingPrograms) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else if (state.programs.isEmpty() && selected != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No past recorded shows returned for this channel.", color = TextSecondary)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxSize()) {
                        items(state.programs, key = { "${it.id}_${it.startTimestamp}" }) { program ->
                            ReplayProgramRow(program) {
                                viewModel.setPendingTitle(program)
                                navController.navigateSafe(
                                    Screen.Player.createRoute("timeshift", viewModel.buildReplayToken(program), state.extension)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplayChannelRow(channel: XtreamChannel, selected: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = when {
                selected -> AccentSurface
                isFocused -> FocusBgStrong
                else -> BgSurface2
            }
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            if (isFocused) 2.dp else if (selected) 1.5.dp else 1.dp,
            if (isFocused) Accent else if (selected) Accent.copy(alpha = 0.6f) else Color.Transparent
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.isRemoteConfirmKey()) {
                    onClick()
                    true
                } else false
            }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.Tv, null, tint = if (selected) Accent else TextTertiary, modifier = Modifier.size(16.dp))
            Text(
                channel.name,
                color = if (selected) Accent else TextPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun ReplayProgramRow(program: XtreamEpgProgram, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val durMin = ((program.stopTimestamp - program.startTimestamp + 59L) / 60L).coerceAtLeast(1L)
    val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(Date(program.startTimestamp * 1000L))
    val timeRangeFmt = TimeFormatter.formatTimeRange(program.startTimestamp * 1000L, program.stopTimestamp * 1000L)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) FocusBgStrong else BgSurface2
        ),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            if (isFocused) 2.dp else 1.dp,
            if (isFocused) Accent else BorderDefault
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.isRemoteConfirmKey()) {
                    onClick()
                    true
                } else false
            }
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        program.title.ifBlank { "Programme" },
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 14.sp,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        color = BgSurface3,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            "${durMin}m",
                            color = TextSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    "$dateFmt  •  $timeRangeFmt",
                    color = Accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
                program.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        description,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
            Surface(
                color = Accent.copy(alpha = 0.18f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f))
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    "Play replay",
                    tint = Accent,
                    modifier = Modifier.padding(8.dp).size(20.dp)
                )
            }
        }
    }
}
