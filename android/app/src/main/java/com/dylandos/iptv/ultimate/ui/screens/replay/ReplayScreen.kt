package com.dylandos.iptv.ultimate.ui.screens.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*
import com.dylandos.iptv.ultimate.ui.util.TimeFormatter

@Composable
fun ReplayScreen(navController: NavController, viewModel: ReplayViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()
    Column(Modifier.fillMaxSize().dylandosScreenBackground().padding(24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.History, null, tint = Accent)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Replay", color = TextPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("Provider recordings from previous days — independent of local Timeshift", color = TextSecondary, fontSize = 12.sp)
            }
            Button(onClick = viewModel::refreshChannels) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Refresh") }
        }
        Spacer(Modifier.height(18.dp))
        if (!state.enabled) {
            Text("Provider Replay is turned off. Enable Show Replay Tab in Settings → Playback.", color = TextSecondary)
            return@Column
        }
        state.error?.let { Text(it, color = StatusError, modifier = Modifier.padding(bottom = 10.dp)) }
        if (state.loadingChannels) {
            CircularProgressIndicator(color = Accent, modifier = Modifier.align(Alignment.CenterHorizontally))
            return@Column
        }
        if (state.channels.isEmpty()) {
            Text("This provider did not report any archive-capable live channels.", color = TextSecondary)
            return@Column
        }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LazyColumn(Modifier.weight(0.38f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.channels, key = { it.streamId }) { channel ->
                    ReplayChannelRow(channel, channel == state.selectedChannel, onClick = { viewModel.selectChannel(channel) })
                }
            }
            Column(Modifier.weight(0.62f).fillMaxHeight()) {
                val selected = state.selectedChannel
                Text(selected?.name ?: "Select a channel", color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text("Past programmes", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                if (state.loadingPrograms) {
                    CircularProgressIndicator(color = Accent)
                } else if (state.programs.isEmpty() && selected != null) {
                    Text("No named past programmes were returned for this channel.", color = TextSecondary)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.programs, key = { "${it.id}_${it.startTimestamp}" }) { program ->
                            ReplayProgramRow(program) {
                                viewModel.setPendingTitle(program)
                                navController.navigateSafe(Screen.Player.createRoute("timeshift", viewModel.buildReplayToken(program), state.extension))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun ReplayChannelRow(channel: XtreamChannel, selected: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = if (selected) AccentSurface else BgSurface2), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(channel.name, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable private fun ReplayProgramRow(program: XtreamEpgProgram, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = BgSurface2), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(program.title.ifBlank { "Programme" }, color = TextPrimary, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    TimeFormatter.formatFullDateTime(program.startTimestamp * 1000L) +
                        "  •  " + TimeFormatter.formatTimeRange(program.startTimestamp * 1000L, program.stopTimestamp * 1000L),
                    color = TextTertiary,
                    fontSize = 11.sp
                )
                program.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(description, color = TextSecondary, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Icon(Icons.Default.PlayArrow, "Play replay", tint = Accent)
        }
    }
}
