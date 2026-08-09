package com.dylandos.iptv.ultimate.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.NewReleases
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dylandos.iptv.ultimate.BuildConfig
import com.dylandos.iptv.ultimate.ui.theme.*

/**
 * DYLANDOS IPTV — What's New Dialog
 *
 * Shown once on first launch after an OTA update is installed.
 * Reads the stored changelog from SharedPreferences (written by UpdateViewModel
 * when the user accepts an update) and displays each item as a check-marked row.
 *
 * Tracks "last seen version" in SharedPreferences so it only shows once per version.
 */

private const val PREFS_WHATSNEW = "dylandos_whatsnew"
private const val KEY_LAST_SEEN  = "last_seen_version_code"
private const val KEY_CHANGELOG  = "cached_changelog"

/** Current version changelog — mirrors ota-update-2.2.0.json */
private val CURRENT_CHANGELOG = listOf(
    "Full Firestick D-pad remapping across all screens",
    "Guide: ENTER opens program details, not Home",
    "Guide: Channel cells are individually D-pad focusable",
    "Guide: UP/DOWN on programs zaps to adjacent channels",
    "Player: Subtitle and audio track pickers use correct LibVLC IDs",
    "Player: Control bar navigation wraps around circularly",
    "Settings: DVR Storage Location with USB/OTG drive picker",
    "Settings: Choose Drive lists Internal + all USB OTG drives",
    "Settings: Choose Folder opens SAF folder browser",
    "Settings: DVR storage path persisted across app restarts",
    "Accounts: Account type tagging — Live TV / DVR Only / All Features",
    "Accounts: Account type badge shown on every account card",
    "Intro video plays on first launch only",
    "Fixed: AccountEditorDialog saves accountType correctly"
)

/** Check if the What's New dialog should be shown this session. Call at app startup. */
fun shouldShowWhatsNew(context: android.content.Context): Boolean {
    val prefs = context.getSharedPreferences(PREFS_WHATSNEW, android.content.Context.MODE_PRIVATE)
    val lastSeen = prefs.getInt(KEY_LAST_SEEN, 0)
    return BuildConfig.VERSION_CODE > lastSeen
}

/** Mark current version as seen so the dialog won't show again. */
fun markWhatsNewSeen(context: android.content.Context) {
    context.getSharedPreferences(PREFS_WHATSNEW, android.content.Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_LAST_SEEN, BuildConfig.VERSION_CODE)
        .apply()
}

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    Dialog(
        onDismissRequest = {
            markWhatsNewSeen(context)
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .width(560.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(1.5.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(20.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xFF100A18), Color(0xFF0B0D16))
                    )
                )
                .padding(28.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                // ── Header ───────────────────────────────────────────────────────
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Accent.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.NewReleases,
                            contentDescription = null,
                            tint = Accent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "What's New",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                        Text(
                            text = "Version ${BuildConfig.VERSION_NAME}  ·  Build ${BuildConfig.VERSION_CODE}",
                            color = TextSecondary,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

                // ── Divider ──────────────────────────────────────────────────────
                HorizontalDivider(color = BorderDefault)

                // ── Changelog list ───────────────────────────────────────────────
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CURRENT_CHANGELOG.forEach { item ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Accent,
                                modifier = Modifier
                                    .size(16.dp)
                                    .padding(top = 1.dp)
                            )
                            Text(
                                text = item,
                                color = TextSecondary,
                                style = MaterialTheme.typography.bodySmall,
                                lineHeight = 18.sp
                            )
                        }
                    }
                }

                // ── Dismiss button ───────────────────────────────────────────────
                Button(
                    onClick = {
                        markWhatsNewSeen(context)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = "Got It — Let's Go!",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        }
    }
}
