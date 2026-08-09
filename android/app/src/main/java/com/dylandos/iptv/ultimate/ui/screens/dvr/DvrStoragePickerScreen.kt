package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.dylandos.iptv.ultimate.ui.theme.DylandosGold
import com.dylandos.iptv.ultimate.ui.theme.DylandosRed
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusGroup
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import androidx.compose.ui.input.key.onKeyEvent
import kotlinx.coroutines.delay

/**
 * DYLANDOS IPTV ULTIMATE — DVR Storage Picker Screen
 *
 * Replaces the broken FireOS SAF folder picker with a clean 3-tier in-app UI:
 *   Tier 1: OTG/USB external (detected via getExternalFilesDirs — no permissions needed)
 *   Tier 2: Internal device storage (fallback)
 *   Tier 3: SAF custom folder (advanced, non-FireOS devices only)
 *   Tier 4: SMB Network Share (placeholder — coming soon)
 *
 * D-pad navigable: each card is focusable with TV-style highlight.
 * "Detect USB" re-runs the scan so users can plug in a drive and refresh.
 */
@Composable
fun DvrStoragePickerScreen(
    onSelected: (DvrStorageOption) -> Unit,
    onDismiss: () -> Unit,
    viewModel: DvrStorageViewModel = hiltViewModel()
) {
    val options     by viewModel.options.collectAsState()
    val selectedOption by viewModel.selectedOption.collectAsState()
    val isScanning  by viewModel.isScanning.collectAsState()
    var showFireOsDialog by remember { mutableStateOf(false) }
    val recommendedFr = remember { FocusRequester() }

    // A6: once the scan finishes, park D-pad focus on the recommended/selected card
    // (OTG first) instead of whatever happens to be first focusable.
    LaunchedEffect(options, selectedOption) {
        if (options.isNotEmpty()) {
            delay(120)
            runCatching { recommendedFr.requestFocus() }
        }
    }

    val safLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) viewModel.onSafUriSelected(uri)
    }

    // FireOS USB setup dialog
    if (showFireOsDialog) {
        FireOsUsbSetupDialog(
            isFireOs     = isFireOsDevice(),
            onDismiss    = { showFireOsDialog = false; viewModel.scanStorage() },
                onUseInternalPath = {
                    showFireOsDialog = false
                    options.filterIsInstance<DvrStorageOption.AppPrivateInternal>()
                        .firstOrNull()
                        ?.let {
                            viewModel.selectOption(it)
                            onSelected(it)
                        }
                }
        )
    }

    Surface(
        modifier          = Modifier.fillMaxSize(),
        color             = MaterialTheme.colorScheme.background
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector        = Icons.Default.Storage,
                    contentDescription = null,
                    tint               = DylandosRed,
                    modifier           = Modifier.size(28.dp)
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "DVR Recording Location",
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color      = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        "Select where recordings will be saved",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // ── Refresh / Detect USB button ───────────────────────────────
                OutlinedButton(
                    onClick  = { viewModel.scanStorage() },
                    enabled  = !isScanning,
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    if (isScanning) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(if (isScanning) "Scanning…" else "Detect USB")
                }
            }

            HorizontalDivider()

            // ── Options list ──────────────────────────────────────────────────
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .dylandosFocusGroup(trapExit = false),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // OTG / external options first
                val otgOptions = options.filterIsInstance<DvrStorageOption.AppPrivateExternal>()
                    .filter { it.isOtg }
                val externalOptions = options.filterIsInstance<DvrStorageOption.AppPrivateExternal>()
                    .filter { !it.isOtg }
                val firstRecommended = otgOptions.firstOrNull()
                    ?: externalOptions.firstOrNull()
                    ?: options.filterIsInstance<DvrStorageOption.AppPrivateInternal>().firstOrNull()

                if (otgOptions.isNotEmpty()) {
                    item {
                        StorageSectionLabel(
                            label    = "USB / OTG Drive (Recommended)",
                            color    = DylandosGold
                        )
                    }
                    items(otgOptions) { option ->
                        StorageOptionCard(
                            icon     = Icons.Default.Usb,
                            title    = option.label,
                            subtitle = "OTG • Free: ${"%.1f".format(option.freeSpaceGB)} GB",
                            badge    = if (selectedOption?.persistedStorageValue() == option.persistedStorageValue()) "ACTIVE USB" else "RECOMMENDED",
                            badgeColor = DylandosGold,
                            isSelected = selectedOption?.persistedStorageValue() == option.persistedStorageValue(),
                            focusRequester = if (option == firstRecommended) recommendedFr else null,
                            onClick  = { viewModel.selectOption(option); onSelected(option) }
                        )
                    }
                }

                if (externalOptions.isNotEmpty()) {
                    item { StorageSectionLabel(label = "External Storage") }
                    items(externalOptions) { option ->
                        StorageOptionCard(
                            icon     = Icons.Default.SdCard,
                            title    = option.label,
                            subtitle = "External • Free: ${"%.1f".format(option.freeSpaceGB)} GB",
                            badge    = if (selectedOption?.persistedStorageValue() == option.persistedStorageValue()) "ACTIVE" else null,
                            badgeColor = DylandosGold,
                            isSelected = selectedOption?.persistedStorageValue() == option.persistedStorageValue(),
                            focusRequester = if (option == firstRecommended) recommendedFr else null,
                            onClick  = { viewModel.selectOption(option); onSelected(option) }
                        )
                    }
                }

                val internalOptions = options.filterIsInstance<DvrStorageOption.AppPrivateInternal>()
                if (internalOptions.isNotEmpty()) {
                    item { StorageSectionLabel(label = "Device Internal Storage") }
                    items(internalOptions) { option ->
                        StorageOptionCard(
                            icon     = Icons.Default.PhoneAndroid,
                            title    = "Internal Storage",
                            subtitle = "Device • Free: ${"%.1f".format(option.freeSpaceGB)} GB" +
                                       if (option.freeSpaceGB < 1f) "  ⚠ Low" else "",
                            badge    = if (selectedOption?.persistedStorageValue() == option.persistedStorageValue()) "ACTIVE" else null,
                            badgeColor = DylandosGold,
                            isSelected = selectedOption?.persistedStorageValue() == option.persistedStorageValue(),
                            focusRequester = if (option == firstRecommended) recommendedFr else null,
                            onClick  = { viewModel.selectOption(option); onSelected(option) }
                        )
                    }
                }

                val safOptions = options.filterIsInstance<DvrStorageOption.SafPicked>()
                if (safOptions.isNotEmpty()) {
                    item { StorageSectionLabel(label = "Custom Folder") }
                    items(safOptions) { option ->
                        StorageOptionCard(
                            icon     = Icons.Default.FolderSpecial,
                            title    = option.label,
                            subtitle = "SAF custom folder",
                            badge    = if (selectedOption?.persistedStorageValue() == option.persistedStorageValue()) "ACTIVE" else null,
                            badgeColor = DylandosGold,
                            isSelected = selectedOption?.persistedStorageValue() == option.persistedStorageValue(),
                            onClick  = { viewModel.selectOption(option); onSelected(option) }
                        )
                    }
                }

                // ── Advanced options ──────────────────────────────────────────
                item {
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(4.dp))
                    StorageSectionLabel(label = "Advanced")
                }

                if (!android.os.Build.MANUFACTURER.contains("Amazon", ignoreCase = true)) {
                    item {
                        StorageOptionCard(
                            icon     = Icons.Default.FolderOpen,
                            title    = "Browse Custom Folder",
                            subtitle = "Advanced — requires FireOS keyboard setup",
                            onClick  = { safLauncher.launch(null) }
                        )
                    }
                }

                item {
                    StorageOptionCard(
                        icon      = Icons.Default.Wifi,
                        title     = "Network Share (SMB)",
                        subtitle  = "Coming soon — record to NAS or PC",
                        enabled   = false,
                        onClick   = { /* TODO: SMB config */ }
                    )
                }

                item {
                    // FireOS USB troubleshooting wizard
                    OutlinedButton(
                        onClick   = { showFireOsDialog = true },
                        modifier  = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Help, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("USB Not Detected? FireOS Setup Guide")
                    }
                }
            }

            // ── Bottom action bar ─────────────────────────────────────────────
            HorizontalDivider()
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun StorageSectionLabel(label: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text     = label.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = color,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
    )
}

@Composable
private fun StorageOptionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badge: String? = null,
    badgeColor: Color = MaterialTheme.colorScheme.primary,
    isSelected: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        onClick           = onClick,
        enabled           = enabled,
        modifier          = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (enabled && event.isRemoteConfirmKey()) {
                    onClick()
                    true
                } else false
            },
        colors            = CardDefaults.cardColors(
            containerColor = if (isFocused || isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        border            = if (isFocused || isSelected)
            BorderStroke(
                if (isFocused) 2.dp else 1.dp,
                if (isSelected) badgeColor else MaterialTheme.colorScheme.primary
            )
        else
            null
    ) {
        Row(
            modifier           = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment  = Alignment.CenterVertically
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (enabled) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier           = Modifier.size(32.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text       = title,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (enabled) MaterialTheme.colorScheme.onSurface
                                     else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                    if (badge != null) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            shape = MaterialTheme.shapes.extraSmall,
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text     = badge,
                                style    = MaterialTheme.typography.labelSmall,
                                color    = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                        alpha = if (enabled) 1f else 0.4f
                    )
                )
            }
            Icon(
                imageVector        = Icons.Default.ChevronRight,
                contentDescription = null,
                tint               = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                     else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                modifier           = Modifier.size(20.dp)
            )
        }
    }
}
