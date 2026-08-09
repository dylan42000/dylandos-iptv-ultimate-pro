package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dylandos.iptv.ultimate.ui.theme.DylandosRed

/**
 * DYLANDOS IPTV ULTIMATE — FireOS USB Setup Dialog
 *
 * Shown when a user on a Fire TV Stick reports that USB/OTG storage is not being
 * detected. Provides the exact manual steps needed to grant storage access on
 * FireOS 8 (Android 11), where the standard file picker is broken for USB volumes.
 *
 * Key insight: FireOS 8 requires a Bluetooth keyboard to navigate the system
 * "Files" app's document picker — a D-pad remote alone cannot complete the flow.
 *
 * Triggered by: "USB Not Detected? FireOS Setup Guide" in [DvrStoragePickerScreen].
 *
 * @param isFireOs          whether the current device is a Fire TV / FireOS device
 * @param onDismiss         called when user taps "Detect USB" (re-scan after setup)
 * @param onUseInternalPath called when user taps "Use Internal Instead"
 */
@Composable
fun FireOsUsbSetupDialog(
    isFireOs: Boolean,
    onDismiss: () -> Unit,
    onUseInternalPath: () -> Unit
) {
    // Show on all devices — the guide is still useful on generic Android 11+.
    // We only customise the title for non-FireOS devices.
    val title = if (isFireOs) "FireOS USB DVR Setup" else "USB Storage Setup"

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector        = Icons.Default.Usb,
                contentDescription = null,
                tint               = DylandosRed,
                modifier           = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(top = 4.dp)
            ) {
                if (isFireOs) {
                    // ── FireOS-specific instructions ──────────────────────────
                    Text(
                        "FireOS 8 requires extra steps for USB recording.",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(10.dp))

                    val steps = listOf(
                        "1. Connect USB drive via a POWERED OTG hub (Firestick alone can't power most drives)",
                        "2. Restart your Firestick with the USB drive already connected",
                        "3. Wait 30 seconds after reboot before returning to this screen",
                        "4. Tap 'Detect USB' below — the app scans automatically",
                        "",
                        "If still not detected, try the advanced SAF method:",
                        "5. Go to Settings → Apps → Manage All Apps",
                        "6. Find the 'Files' app → tap 'Clear Data'",
                        "7. Connect a Bluetooth keyboard (required for FireOS 8 file picker)",
                        "8. In this app, tap 'Browse Custom Folder' and navigate to your USB drive",
                        "9. Tap 'Use This Folder' → Allow"
                    )
                    steps.forEach { step ->
                        if (step.isEmpty()) {
                            Spacer(Modifier.height(8.dp))
                        } else {
                            Text(
                                text     = step,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Surface(
                        color  = DylandosRed.copy(alpha = 0.08f),
                        shape  = MaterialTheme.shapes.small
                    ) {
                        Row(
                            modifier          = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector        = Icons.Default.Warning,
                                contentDescription = null,
                                tint               = DylandosRed,
                                modifier           = Modifier.size(18.dp).padding(top = 2.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text  = "USB must be FAT32 or exFAT formatted. NTFS is NOT supported " +
                                        "on most FireOS devices.",
                                style = MaterialTheme.typography.bodySmall,
                                color = DylandosRed
                            )
                        }
                    }
                } else {
                    // ── Generic Android ───────────────────────────────────────
                    Text(
                        "USB storage not found. Ensure the drive is mounted and tap Detect USB.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "If the drive is connected but not appearing, use 'Browse Custom Folder' " +
                        "to navigate to it manually via the system file picker.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors  = ButtonDefaults.buttonColors(containerColor = DylandosRed)
            ) {
                Icon(Icons.Default.Usb, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Detect USB")
            }
        },
        dismissButton = {
            TextButton(onClick = onUseInternalPath) {
                Text("Use Internal Storage Instead")
            }
        }
    )
}

/**
 * Returns true if the current device is an Amazon Fire TV / FireOS device.
 * Used to show FireOS-specific instructions in [FireOsUsbSetupDialog].
 */
fun isFireOsDevice(): Boolean {
    return Build.MANUFACTURER.equals("Amazon", ignoreCase = true) ||
           Build.MODEL.contains("AFT", ignoreCase = true) ||
           Build.MODEL.contains("Fire TV", ignoreCase = true) ||
           Build.MODEL.contains("AFTM", ignoreCase = true) ||
           Build.MODEL.contains("AFTN", ignoreCase = true) ||
           Build.MODEL.contains("AFTS", ignoreCase = true)
}
