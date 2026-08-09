package com.dylandos.iptv.ultimate.ui.screens.dvr

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * RecordingDetailScreen — premium detail view for a single DVR recording.
 *
 * Reuses [DvrViewModel] (application-scoped recordings) so the data mapping stays
 * identical to the DVR list. Shows the recording's metadata, a PLAY / DELETE /
 * OPTIONS action row, and a "MORE FROM THIS CHANNEL" ribbon that deep-links to the
 * detail view of sibling recordings.
 */
@Composable
fun RecordingDetailScreen(
    navController: NavController,
    recordingId: String,
    viewModel: DvrViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val all = remember(uiState.completedRecordings, uiState.activeRecordings) {
        uiState.completedRecordings + uiState.activeRecordings
    }
    val rec = all.firstOrNull { it.id == recordingId }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .dylandosScreenBackground()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ───────────────────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .dylandosFocusable(onClick = { navController.popBackStack() })
                        .padding(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "RECORDING DETAILS",
                    color = TextPrimary,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
            }

            Spacer(Modifier.height(28.dp))

            if (rec == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = DylandosPalette.Cyan)
                        Spacer(Modifier.height(12.dp))
                        Text("Recording not found", color = TextSecondary)
                    }
                }
                return@Column
            }

            // ── Main detail row ────────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                // Poster placeholder (recordings have no artwork — stylised channel tile)
                Box(
                    modifier = Modifier
                        .width(220.dp)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(Color(0xFF1B2030), Color(0xFF0C0F18))
                            )
                        )
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = rec.channelName.take(2).uppercase(),
                        color = DylandosPalette.Cyan,
                        fontSize = 52.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    if (rec.isActive) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(10.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE50914))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text("● REC", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Details column
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = rec.channelName,
                        color = TextPrimary,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(16.dp))

                    MetaLine("Recorded", rec.formattedDate())
                    MetaLine("Duration", rec.formattedDuration())
                    MetaLine("Size", rec.formattedSize())
                    MetaLine("Channel", rec.channelName)
                    MetaLine("Status", if (rec.isActive) "Recording now" else "Completed")

                    Spacer(Modifier.height(24.dp))

                    // Action buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RecDetailButton(label = "▶  PLAY", isPrimary = true) {
                            if (rec.filePath.isNotBlank()) {
                                navController.navigateSafe(
                                    Screen.Player.createRoute("dvr", rec.filePath, "ts")
                                )
                            }
                        }
                        RecDetailButton(label = "DELETE", isPrimary = false) {
                            viewModel.deleteRecording(rec.id)
                            navController.popBackStack()
                        }
                        RecDetailButton(label = "BACK", isPrimary = false) {
                            navController.popBackStack()
                        }
                    }
                }
            }

            // ── More from this channel ─────────────────────────────────────────
            val siblings = remember(all, rec.id, rec.channelName) {
                all.filter { it.id != rec.id && it.channelName == rec.channelName }
                    .ifEmpty { all.filter { it.id != rec.id } }
                    .take(12)
            }
            if (siblings.isNotEmpty()) {
                Spacer(Modifier.height(32.dp))
                Text(
                    text = "MORE FROM THIS CHANNEL",
                    color = TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(siblings, key = { it.id }) { sib ->
                        SiblingCard(rec = sib) {
                            navController.navigateSafe(Screen.RecordingDetail.createRoute(sib.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            text = "$label:",
            color = TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp)
        )
        Text(
            text = value,
            color = TextPrimary,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun RecDetailButton(label: String, isPrimary: Boolean, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.06f else 1f,
        animationSpec = tween(120),
        label = "recBtnScale"
    )
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isPrimary -> Color(0xFFE50914)
                    isFocused -> Color(0x26FFFFFF)
                    else      -> Color(0x14FFFFFF)
                }
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isPrimary -> Color(0xFFE50914)
                    isFocused -> DylandosPalette.Cyan
                    else      -> Color(0x33FFFFFF)
                },
                shape = RoundedCornerShape(8.dp)
            )
            .dylandosFocusable(onClick = onClick)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
            .padding(horizontal = 24.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (isPrimary) Color.White else if (isFocused) TextPrimary else TextSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SiblingCard(rec: DvrRecording, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .width(170.dp)
            .onFocusChanged { isFocused = it.isFocused || it.hasFocus }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    Brush.verticalGradient(listOf(Color(0xFF1B2030), Color(0xFF0C0F18)))
                )
                .then(
                    if (isFocused) Modifier.border(2.5.dp, DylandosPalette.Cyan, RoundedCornerShape(10.dp))
                    else Modifier
                )
                .dylandosFocusable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rec.channelName.take(2).uppercase(),
                color = DylandosPalette.Cyan,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = rec.channelName,
            color = if (isFocused) DylandosPalette.Cyan else TextPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = rec.formattedDate(),
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
