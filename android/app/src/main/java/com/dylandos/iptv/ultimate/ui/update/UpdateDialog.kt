package com.dylandos.iptv.ultimate.ui.update

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dylandos.iptv.ultimate.BuildConfig
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * DYLANDOS IPTV — OTA Update Dialog
 *
 * Displayed when UpdateViewModel detects a newer versionCode in the Gist.
 * States:
 *   Available    → shows release notes + "Update Now" / "Later" buttons
 *   Downloading  → animated progress bar
 *   ReadyToInstall → "Install Now" button
 *   Error        → error message + retry
 */
@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    when (val s = state) {
        is UpdateState.Available -> {
            val mandatory = s.info.mandatory
            Dialog(
                onDismissRequest = {
                    if (!mandatory) viewModel.dismiss()
                },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = !mandatory,
                    dismissOnClickOutside = !mandatory
                )
            ) {
                UpdateDialogContent(
                    title = "Update Available — v${s.info.versionName}",
                    subtitle = "Build ${s.info.versionCode}",
                    body = if (mandatory) {
                        "This update is required before continuing.\n\n${s.info.releaseNotes}"
                    } else {
                        s.info.releaseNotes
                    },
                    primaryLabel = "Update Now",
                    secondaryLabel = if (mandatory) null else "Later",
                    onPrimary = { viewModel.startDownload(s.info) },
                    onSecondary = { viewModel.dismiss() }
                )
            }
        }

        is UpdateState.Downloading -> {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = false)
            ) {
                UpdateDialogContent(
                    title = "Downloading Update…",
                    subtitle = "v${s.info.versionName}",
                    body = null,
                    primaryLabel = null,
                    secondaryLabel = null,
                    onPrimary = {},
                    onSecondary = {}
                ) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        progress = { s.progressPct / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Accent,
                        trackColor = BgElevated
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "${s.progressPct}%",
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        is UpdateState.ReadyToInstall -> {
            Dialog(
                onDismissRequest = { viewModel.dismiss() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                UpdateDialogContent(
                    title = "Download Complete!",
                    subtitle = "v${s.info.versionName} is ready to install",
                    body = "Tap Install Now and follow the on-screen prompts.\nThe app will restart after installing.",
                    primaryLabel = "Install Now",
                    secondaryLabel = "Later",
                    onPrimary = { viewModel.installApk(context, s.localUri) },
                    onSecondary = { viewModel.dismiss() }
                )
            }
        }

        is UpdateState.Error -> {
            Dialog(
                onDismissRequest = { viewModel.dismiss() },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                UpdateDialogContent(
                    title = "Update Failed",
                    subtitle = s.message,
                    body = null,
                    primaryLabel = "OK",
                    secondaryLabel = null,
                    onPrimary = { viewModel.dismiss() },
                    onSecondary = {}
                )
            }
        }

        else -> Unit
    }
}

@Composable
private fun UpdateDialogContent(
    title: String,
    subtitle: String,
    body: String?,
    primaryLabel: String?,
    secondaryLabel: String?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    extraContent: (@Composable ColumnScope.() -> Unit)? = null
) {
    Box(
        modifier = Modifier
            .width(560.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.5.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF1A0A12), Color(0xFF0D0D14))
                )
            )
            .padding(28.dp)
    ) {
        Column {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Default.SystemUpdate,
                    contentDescription = null,
                    tint = Accent,
                    modifier = Modifier.size(28.dp)
                )
                Column {
                    Text(
                        text = title,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = subtitle,
                        color = TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (body != null) {
                Spacer(Modifier.height(16.dp))
                Surface(
                    color = BgSurface,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)      // cap height so buttons stay visible
                ) {
                    Text(
                        text = body,
                        color = TextSecondary,
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp,
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp)
                    )
                }
            }

            extraContent?.invoke(this)

            if (primaryLabel != null || secondaryLabel != null) {
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)
                ) {
                    if (secondaryLabel != null) {
                        OutlinedButton(
                            onClick = onSecondary,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text(secondaryLabel)
                        }
                    }
                    if (primaryLabel != null) {
                        Button(
                            onClick = onPrimary,
                            colors = ButtonDefaults.buttonColors(containerColor = Accent)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(primaryLabel, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
