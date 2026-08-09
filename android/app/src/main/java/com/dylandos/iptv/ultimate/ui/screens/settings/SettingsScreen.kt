package com.dylandos.iptv.ultimate.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import com.dylandos.iptv.ultimate.ui.focus.FirestickKeyMap
import com.dylandos.iptv.ultimate.ui.focus.isRemoteConfirmKey
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.util.buildDownloadTreeInitialUri
import com.dylandos.iptv.ultimate.ui.util.fireTvSafeOpenTreeIntent
import com.dylandos.iptv.ultimate.ui.util.genericOpenTreeIntent
import com.dylandos.iptv.ultimate.ui.util.openTreeIntentForVolume
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.lazy.items
import com.dylandos.iptv.ultimate.ui.theme.*
import com.dylandos.iptv.ultimate.data.model.SavedAccount
import com.dylandos.iptv.ultimate.ui.components.tvButtonFocusable
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusGroup
import com.dylandos.iptv.ultimate.ui.focus.dylandosFocusable
import com.dylandos.iptv.ultimate.BuildConfig
import com.dylandos.iptv.ultimate.data.util.StorageDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * DYLANDOS IPTV ULTIMATE — Full Settings Screen
 *
 * Tabs (D-pad navigable via ScrollableTabRow):
 *   0 Account  1 Playback  2 Filter  3 EPG  4 Appearance  5 Subtitles  6 System  7 LibVLC
 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(
    navController: NavController,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var selectedTab by remember { mutableIntStateOf(0) }

    // Crash boundary (A1): if a tab throws during composition — e.g. a native
    // UnsatisfiedLinkError probing LibVLC — we show an error card instead of
    // letting the process die back to the Firestick home screen.
    var tabContentError by remember { mutableStateOf<String?>(null) }

    val tabLabels = listOf("Account", "Playback", "Filter", "EPG", "Appearance", "Subtitles", "System", "LibVLC", "Performance", "DVR", "Categories", "Parental", "About")
    val tabCount  = tabLabels.size
    val tabFocusRequesters = remember(tabCount) { List(tabCount) { FocusRequester() } }
    val tabInteractions    = remember(tabCount) { List(tabCount) { MutableInteractionSource() } }
    val contentFocusRequester = remember { FocusRequester() }

    // AUTO-FOCUS FIX: Without this, D-pad focus starts in an undefined position on
    // Settings entry, making the tab bar unresponsive until the user manually tabs to it.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { tabFocusRequesters[0].requestFocus() }
    }

    // Clear the crash boundary whenever the user moves to a different tab.
    LaunchedEffect(selectedTab) { tabContentError = null }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionRight -> {
                        if (selectedTab >= tabCount - 3 && selectedTab < tabCount - 1) {
                            selectedTab += 1
                            runCatching { tabFocusRequesters[selectedTab].requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    Key.DirectionLeft -> {
                        if (selectedTab > tabCount - 3 && selectedTab <= tabCount - 1) {
                            selectedTab -= 1
                            runCatching { tabFocusRequesters[selectedTab].requestFocus() }
                            true
                        } else {
                            false
                        }
                    }
                    else -> false
                }
            }
            .dylandosScreenBackground()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top Bar ────────────────────────────────────────────────────────
            TopAppBar(
                title = { Text("Settings", fontWeight = FontWeight.Bold, color = TextPrimary) },
                navigationIcon = {
                    // Not D-pad focusable — Back remote key / system back exits.
                    // Prevents Right-from-last-tab escaping into Back → finish.
                    SettingsIconBtn(
                        onClick = { navController.popBackStack() },
                        label = "Back",
                        focusable = false
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Accent)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BgSurface, navigationIconContentColor = Accent
                )
            )

            // ── Tabs ───────────────────────────────────────────────────────────
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = BgSurface2,
                contentColor = Accent,
                edgePadding = 8.dp,
                // Soft group: restores last tab; Phase A key handlers still own L/R/Down edges.
                modifier = Modifier.dylandosFocusGroup(trapExit = false)
            ) {
                tabLabels.forEachIndexed { index, label ->
                    val tabFocused by tabInteractions[index].collectIsFocusedAsState()
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(label, fontSize = 13.sp) },
                        modifier = Modifier
                            .focusRequester(tabFocusRequesters[index])
                            .then(if (tabFocused) Modifier.background(Accent.copy(alpha = 0.18f), RoundedCornerShape(4.dp)) else Modifier)
                            .focusable(interactionSource = tabInteractions[index])
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionLeft -> {
                                        if (index > 0) {
                                            selectedTab = index - 1
                                            runCatching { tabFocusRequesters[index - 1].requestFocus() }
                                        }
                                        true // Always consume at the left edge so focus never escapes the tab row
                                    }
                                    Key.DirectionRight -> {
                                        if (index < tabCount - 1) {
                                            selectedTab = index + 1
                                            runCatching { tabFocusRequesters[index + 1].requestFocus() }
                                        } else {
                                            // Last tab: exit into content — never escape to Back/finish.
                                            runCatching { contentFocusRequester.requestFocus() }
                                        }
                                        true
                                    }
                                    Key.DirectionDown -> {
                                        // Exit Down into tab content — never Up/Right to Back.
                                        runCatching { contentFocusRequester.requestFocus() }
                                        true
                                    }
                                    Key.DirectionCenter, Key.Enter -> {
                                        selectedTab = index
                                        true
                                    }
                                    else -> false
                                }
                            }
                    )
                }
            }

            // ── Tab content ────────────────────────────────────────────────────
            // focusable() so contentFocusRequester.requestFocus() from the tab row
            // succeeds (a plain Box is not a focus target — focus was escaping to Back).
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .focusRequester(contentFocusRequester)
                    .focusable()
                    .focusProperties {
                        up = FocusRequester.Cancel
                    }
                    .dylandosFocusGroup(trapExit = false)
            ) {
                val currentTabError = tabContentError
                if (currentTabError != null) {
                    SettingsTabErrorCard(
                        message = currentTabError,
                        onBackToAccount = {
                            tabContentError = null
                            selectedTab = 0
                            runCatching { tabFocusRequesters[0].requestFocus() }
                        }
                    )
                } else {
                    when (selectedTab) {
                        0  -> AccountTab(state, viewModel, navController, keyboardController)
                        1  -> PlaybackTab(state, viewModel)
                        2  -> ContentFilterTab(state, viewModel)
                        3  -> EpgTab(state, viewModel)
                        4  -> AppearanceTab(state, viewModel)
                        5  -> SubtitlesTab(state, viewModel)
                        6  -> SystemTab(state, viewModel)
                        7  -> LibVlcTab(state, viewModel)
                        8  -> PerformanceTab(state, viewModel)
                        9  -> DvrSettingsTab(state, viewModel)
                        10 -> CategoriesTab(state, viewModel)
                        11 -> ParentalTab(state, viewModel)
                        12 -> AboutTab()
                    }
                }
            }
        }
    }
}

/**
 * Fallback UI shown when a Settings tab crashes during composition (A1).
 * Keeps the app alive and gives the user a path back to a working tab
 * instead of the process dying to the Fire TV home screen.
 */
@Composable
private fun SettingsTabErrorCard(
    message: String,
    onBackToAccount: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgBase)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = StatusError,
            modifier = Modifier.size(48.dp)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "This settings section hit an unexpected error.",
            color = TextPrimary,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = TextTertiary,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = onBackToAccount,
            modifier = Modifier.focusable()
        ) {
            Text("Return to Account")
        }
    }
}

// ── Tab: Account ──────────────────────────────────────────────────────────────

@Composable
private fun AccountTab(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    navController: NavController,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    var accountSubTab by remember { mutableIntStateOf(0) }
    val subTabs = listOf("My Accounts", "Manage Accounts")
    val subTabFocusRequesters = remember(subTabs.size) { List(subTabs.size) { FocusRequester() } }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-tab row
        TabRow(
            selectedTabIndex = accountSubTab,
            containerColor = BgSurface3,
            contentColor = Accent,
            modifier = Modifier.dylandosFocusGroup(trapExit = false)
        ) {
            subTabs.forEachIndexed { i, label ->
                Tab(
                    selected = accountSubTab == i,
                    onClick  = { accountSubTab = i },
                    text     = { Text(label, fontSize = 13.sp) },
                    modifier = Modifier
                        .focusRequester(subTabFocusRequesters[i])
                        .focusable()
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionLeft -> {
                                    if (i > 0) {
                                        accountSubTab = i - 1
                                        runCatching { subTabFocusRequesters[i - 1].requestFocus() }
                                        true
                                    } else false
                                }
                                Key.DirectionRight -> {
                                    if (i < subTabs.lastIndex) {
                                        accountSubTab = i + 1
                                        runCatching { subTabFocusRequesters[i + 1].requestFocus() }
                                        true
                                    } else false
                                }
                                else -> false
                            }
                        }
                )
            }
        }

        when (accountSubTab) {
            0 -> AccountSwitcherSubTab(state, viewModel, navController)
            1 -> ManageAccountsSubTab(state, viewModel, keyboardController = keyboardController)
        }
    }

    // Account editor dialog
    if (state.editingAccount != null) {
        AccountEditorDialog(
            account  = state.editingAccount!!,
            onSave   = { viewModel.saveEditingAccount(it) },
            onCancel = { viewModel.cancelEditAccount() }
        )
    }
}

/**
 * "My Accounts" sub-tab — shows all saved accounts with large focusable cards.
 * The active account is highlighted. Switching accounts is one D-pad click away.
 * NO raw credential form here — that lives in "Manage Accounts".
 */
@Composable
private fun AccountSwitcherSubTab(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    navController: NavController
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "Logged-In Servers",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text(
                    "${state.savedAccounts.size} of 10 accounts saved",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            if (state.isConnected) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Wifi, null, tint = StatusSuccess, modifier = Modifier.size(14.dp))
                        Text("Connected", color = StatusSuccess, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        HorizontalDivider(color = BorderDefault)

        if (state.savedAccounts.isEmpty()) {
            // Guide to add an account
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.AccountCircle,
                        null,
                        tint = TextTertiary,
                        modifier = Modifier.size(64.dp)
                    )
                    Text("No saved accounts", color = TextSecondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Go to \"Manage Accounts\" to add up to 10 Xtream servers.",
                        color = TextTertiary,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            // Account cards — each fully D-pad focusable
            state.savedAccounts.forEach { account ->
                val isActive = account.id == state.activeAccountId
                AccountSwitchCard(
                    account = account,
                    isActive = isActive,
                    onSwitch = {
                        viewModel.switchToAccount(account) { switched ->
                            if (switched) {
                                navController.navigate(com.dylandos.iptv.ultimate.ui.navigation.Screen.Home.route) {
                                    popUpTo(com.dylandos.iptv.ultimate.ui.navigation.Screen.Home.route) { inclusive = true }
                                }
                            }
                        }
                    }
                )
            }
        }

        // Sign out button (only shown when connected)
        if (state.isConnected) {
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = BorderDefault)
            Spacer(Modifier.height(8.dp))

            val signOutInteraction = remember { MutableInteractionSource() }
            val signOutFocused by signOutInteraction.collectIsFocusedAsState()

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (signOutFocused) Modifier.border(2.dp, StatusError.copy(alpha = 0.7f), RoundedCornerShape(10.dp)) else Modifier)
                    .focusable(interactionSource = signOutInteraction)
                    .clickable(
                        interactionSource = signOutInteraction,
                        indication = null
                    ) {
                        viewModel.signOut()
                        navController.navigate(com.dylandos.iptv.ultimate.ui.navigation.Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                            viewModel.signOut()
                            navController.navigate(com.dylandos.iptv.ultimate.ui.navigation.Screen.Login.route) {
                                popUpTo(0) { inclusive = true }
                            }
                            true
                        } else false
                    },
                color = if (signOutFocused) Color(0xFF330000) else Color(0x22FF4444),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Logout,
                        null,
                        tint = if (signOutFocused) Color(0xFFFF6B6B) else StatusError.copy(alpha = 0.8f),
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        "Sign Out",
                        color = if (signOutFocused) Color(0xFFFF6B6B) else StatusError.copy(alpha = 0.8f),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

/**
 * Single account card in the switcher. Focusable, D-pad OK activates switch.
 */
@Composable
private fun AccountSwitchCard(
    account: SavedAccount,
    isActive: Boolean,
    onSwitch: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    isActive -> Modifier.border(2.dp, StatusSuccess, RoundedCornerShape(14.dp))
                    isFocused -> Modifier.border(3.dp, Accent, RoundedCornerShape(14.dp))
                    else -> Modifier
                }
            )
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = !isActive,
                onClick = onSwitch
            )
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && !isActive) {
                    onSwitch(); true
                } else false
            },
        colors = CardDefaults.cardColors(
            containerColor = when {
                isActive -> StatusSuccess.copy(alpha = 0.08f)
                isFocused -> Accent.copy(alpha = 0.10f)
                else -> BgSurface2
            }
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Status icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = if (isActive) StatusSuccess.copy(alpha = 0.15f) else BgElevated,
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isActive) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                    null,
                    tint = if (isActive) StatusSuccess else TextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            // Account info
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    account.nickname.ifBlank { "Unnamed Account" },
                    color = if (isActive) StatusSuccess else TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    account.username,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
                Text(
                    account.serverUrl.removePrefix("http://").removePrefix("https://"),
                    color = TextTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                // Account type badge
                val typeLabel = when (account.accountType) {
                    "live" -> "📺 Live TV"
                    "dvr"  -> "⏺ DVR Only"
                    else   -> "🌐 All Features"
                }
                Text(typeLabel, color = Accent.copy(alpha = 0.8f), fontSize = 10.sp)
            }

            // Active badge OR switch button
            if (isActive) {
                Surface(
                    color = StatusSuccess.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        "ACTIVE",
                        color = StatusSuccess,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            } else {
                Surface(
                    color = if (isFocused) Accent else Accent.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.SwapHoriz, null, tint = if (isFocused) Color.Black else Accent, modifier = Modifier.size(16.dp))
                        Text(
                            "Switch",
                            color = if (isFocused) Color.Black else Accent,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageAccountsSubTab(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header row with add button
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Text(
                    "Manage Accounts",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    "${state.savedAccounts.size} / 10 accounts",
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
            if (state.savedAccounts.size < 10) {
                val addInteraction = remember { MutableInteractionSource() }
                val addFocused by addInteraction.collectIsFocusedAsState()
                Surface(
                    modifier = Modifier
                        .then(if (addFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                        .focusable(interactionSource = addInteraction)
                        .clickable(
                            interactionSource = addInteraction,
                            indication = null,
                            onClick = { viewModel.openAddAccount() }
                        )
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                viewModel.openAddAccount(); true
                            } else false
                        },
                    color = if (addFocused) Accent else Accent.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(Icons.Default.Add, null, tint = if (addFocused) Color.Black else Accent, modifier = Modifier.size(18.dp))
                        Text("Add Account", color = if (addFocused) Color.Black else Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        HorizontalDivider(color = BorderDefault)

        if (state.savedAccounts.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.AccountCircle, null, tint = TextTertiary, modifier = Modifier.size(48.dp))
                    Text("No saved accounts", color = TextTertiary, fontSize = 14.sp)
                    Text(
                        "Add up to 10 Xtream accounts to quickly switch between them.",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
        } else {
            state.savedAccounts.forEach { account ->
                val isActive = account.id == state.activeAccountId
                ManageAccountCard(
                    account = account,
                    isActive = isActive,
                    onEdit = { viewModel.openEditAccount(account) },
                    onDelete = { viewModel.deleteAccount(account.id) }
                )
            }
        }

        HorizontalDivider(color = BorderDefault, modifier = Modifier.padding(vertical = 4.dp))
        Text(
            "Press OK on a card to edit it. Max 10 accounts total.",
            color = TextTertiary,
            fontSize = 11.sp
        )
    }
}

/**
 * Single account management card — edit / delete buttons fully D-pad navigable.
 */
@Composable
private fun ManageAccountCard(
    account: SavedAccount,
    isActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val editInteraction   = remember { MutableInteractionSource() }
    val deleteInteraction = remember { MutableInteractionSource() }
    val editFocused   by editInteraction.collectIsFocusedAsState()
    val deleteFocused by deleteInteraction.collectIsFocusedAsState()
    val cardHighlighted = editFocused || deleteFocused
    val editFR   = remember { FocusRequester() }
    val deleteFR = remember { FocusRequester() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                when {
                    isActive && cardHighlighted -> Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp))
                    isActive -> Modifier.border(1.5.dp, StatusSuccess.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    cardHighlighted -> Modifier.border(2.dp, Accent, RoundedCornerShape(12.dp))
                    else -> Modifier
                }
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) StatusSuccess.copy(alpha = 0.07f) else BgSurface2
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft  -> { runCatching { editFR.requestFocus() };   true }
                        Key.DirectionRight -> { runCatching { deleteFR.requestFocus() }; true }
                        else -> false
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (isActive) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                null,
                tint = if (isActive) StatusSuccess else TextSecondary,
                modifier = Modifier.size(22.dp)
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account.nickname.ifBlank { "Unnamed Account" }, color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (isActive) {
                        Surface(color = StatusSuccess.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp)) {
                            Text("ACTIVE", color = StatusSuccess, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                        }
                    }
                }
                Text(account.username, color = TextSecondary, fontSize = 12.sp)
                Text(account.serverUrl, color = TextTertiary, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            // Edit button
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .tvButtonFocusable(focusRequester = editFR, interactionSource = editInteraction)
                    .background(
                        if (editFocused) Accent.copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(Icons.Default.Edit, "Edit", tint = if (editFocused) Accent else TextSecondary)
            }
            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .tvButtonFocusable(focusRequester = deleteFR, interactionSource = deleteInteraction)
                    .background(
                        if (deleteFocused) StatusError.copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(8.dp)
                    )
            ) {
                Icon(Icons.Default.Delete, "Delete", tint = if (deleteFocused) StatusError else StatusError.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun AccountEditorDialog(
    account: com.dylandos.iptv.ultimate.data.model.SavedAccount,
    onSave: (com.dylandos.iptv.ultimate.data.model.SavedAccount) -> Unit,
    onCancel: () -> Unit
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var nickname     by remember { mutableStateOf(account.nickname) }
    var serverUrl    by remember { mutableStateOf(account.serverUrl) }
    var username     by remember { mutableStateOf(account.username) }
    var password     by remember { mutableStateOf(account.password) }
    var accountType  by remember { mutableStateOf(account.accountType.ifBlank { "both" }) }
    var showPassword by remember { mutableStateOf(false) }
    val isNew = account.nickname.isBlank() && account.serverUrl.isBlank()

    // FocusRequesters for D-pad / keyboard chain
    val nickFR   = remember { FocusRequester() }
    val serverFR = remember { FocusRequester() }
    val userFR   = remember { FocusRequester() }
    val passFR   = remember { FocusRequester() }
    val saveFR   = remember { FocusRequester() }
    val cancelFR = remember { FocusRequester() }

    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.84f)
                .background(BgBase, RoundedCornerShape(20.dp))
                .border(1.dp, BorderDefault, RoundedCornerShape(20.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // ── Branded Header ──────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            if (isNew) "Add Account" else "Edit Account",
                            color = Accent,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 22.sp
                        )
                        Text(
                            "DYLANDOS IPTV ULTIMATE",
                            color = TextTertiary,
                            fontSize = 10.sp,
                            letterSpacing = 2.sp
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(13.dp))
                            .border(1.dp, Accent.copy(alpha = 0.3f), RoundedCornerShape(13.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.AccountCircle,
                            null,
                            tint = Accent,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                }

                HorizontalDivider(color = BorderDefault)

                // ── Account Name ─────────────────────────────────────────────
                OutlinedTextField(
                    value = nickname,
                    onValueChange = { nickname = it },
                    label = { Text("Account Name  (e.g. Home Server)") },
                    modifier = Modifier.fillMaxWidth().focusRequester(nickFR),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { serverFR.requestFocus() }),
                    colors = accountFieldColors()
                )

                // ── Server URL ────────────────────────────────────────────────
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Server URL") },
                    placeholder = { Text("http://yourserver.com:8080", color = TextTertiary) },
                    modifier = Modifier.fillMaxWidth().focusRequester(serverFR),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { userFR.requestFocus() }),
                    colors = accountFieldColors()
                )

                // ── Username ──────────────────────────────────────────────────
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth().focusRequester(userFR),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { passFR.requestFocus() }),
                    colors = accountFieldColors()
                )

                // ── Password ──────────────────────────────────────────────────
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    modifier = Modifier.fillMaxWidth().focusRequester(passFR),
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                null, tint = TextSecondary, modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        keyboardController?.hide()
                        focusManager.clearFocus(force = true)
                        saveFR.requestFocus()
                    }),
                    colors = accountFieldColors()
                )

                // ── Account Type ──────────────────────────────────────────────
                Text(
                    "Use this account for:",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    listOf(
                        Triple("live", "📺", "Live TV Only"),
                        Triple("dvr",  "⏺", "DVR Only"),
                        Triple("both", "🌐", "All Features")
                    ).forEach { (type, emoji, label) ->
                        val isSelected = accountType == type
                        val typeInteraction = remember { MutableInteractionSource() }
                        val typeFocused by typeInteraction.collectIsFocusedAsState()
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (isSelected || typeFocused)
                                        Modifier.border(2.dp, Accent, RoundedCornerShape(10.dp))
                                    else Modifier
                                )
                                .focusable(interactionSource = typeInteraction)
                                .clickable(
                                    interactionSource = typeInteraction,
                                    indication = null
                                ) { accountType = type }
                                .onKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                    if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                        accountType = type; true
                                    } else false
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) Accent.copy(alpha = 0.12f) else BgSurface2
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 10.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(emoji, fontSize = 18.sp)
                                Text(
                                    label,
                                    color = if (isSelected) Accent else TextSecondary,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 11.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    maxLines = 2
                                )
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        null,
                                        tint = Accent,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = BorderDefault)

                // ── Action Buttons ────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Cancel
                    val cancelInteraction = remember { MutableInteractionSource() }
                    val cancelFocused by cancelInteraction.collectIsFocusedAsState()
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .focusRequester(cancelFR)
                            .focusable(interactionSource = cancelInteraction)
                            .then(
                                if (cancelFocused)
                                    Modifier.border(2.dp, TextSecondary, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                    onCancel(); true
                                } else false
                            },
                        border = BorderStroke(1.dp, BorderDefault),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }

                    // Save
                    val saveInteraction = remember { MutableInteractionSource() }
                    val saveFocused2 by saveInteraction.collectIsFocusedAsState()
                    val canSave = serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
                    Button(
                        onClick = {
                            if (canSave) {
                                keyboardController?.hide()
                                focusManager.clearFocus(force = true)
                                onSave(
                                    account.copy(
                                        nickname    = nickname.trim().ifBlank { username.trim() },
                                        serverUrl   = serverUrl.trim().trimEnd('/'),
                                        username    = username.trim(),
                                        password    = password,
                                        accountType = accountType
                                    )
                                )
                            }
                        },
                        enabled = canSave,
                        modifier = Modifier
                            .weight(2f)
                            .height(52.dp)
                            .focusRequester(saveFR)
                            .focusable(interactionSource = saveInteraction)
                            .then(
                                if (saveFocused2)
                                    Modifier.border(2.dp, AccentBright, RoundedCornerShape(10.dp))
                                else Modifier
                            )
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && canSave) {
                                    keyboardController?.hide()
                                    focusManager.clearFocus(force = true)
                                    onSave(
                                        account.copy(
                                            nickname    = nickname.trim().ifBlank { username.trim() },
                                            serverUrl   = serverUrl.trim().trimEnd('/'),
                                            username    = username.trim(),
                                            password    = password,
                                            accountType = accountType
                                        )
                                    )
                                    true
                                } else false
                            },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Accent,
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (isNew) "Add Account" else "Save Changes",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun accountFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = Accent,
    unfocusedBorderColor = BorderDefault,
    focusedLabelColor    = Accent,
    cursorColor          = Accent,
    focusedTextColor     = TextPrimary,
    unfocusedTextColor   = TextPrimary
)

// ── Tab: Playback ─────────────────────────────────────────────────────────────

@Composable
private fun PlaybackTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Stream Format")
        SegmentedPick(
            options = listOf("ts", "m3u8"),
            selected = state.streamFormat,
            labels = listOf("MPEG-TS (.ts)", "HLS (.m3u8)"),
            onSelect = viewModel::setStreamFormat
        )

        SectionHeader("Live TV Buffer")
        SegmentedPick(
            options = listOf("Low", "Medium", "High"),
            selected = state.liveBufferPreset,
            labels = listOf("Low (1s)", "Medium (2s)", "High (4s)"),
            onSelect = viewModel::setLiveBufferPreset
        )
        SwitchRow(
            "Live TV Pause & Rewind",
            state.timeshiftEnabled,
            viewModel::setTimeshiftEnabled
        )
        if (state.timeshiftEnabled) {
            SegmentedPick(
                options = listOf("0", "30", "60", "120", "240"),
                selected = state.timeshiftWindowMinutes.toString(),
                labels = listOf("Auto", "30 min", "1 hr", "2 hr", "4 hr"),
                onSelect = { viewModel.setTimeshiftWindowMinutes(it.toIntOrNull() ?: 0) }
            )
        }
        InfoCard(
            "Smart Timeshift",
            "Uses provider catch-up when available and a local disk-backed player buffer otherwise. " +
                "FireStick media Rewind, Fast Forward, and Play/Pause keys are mapped directly. " +
                "Window = how far back you can rewind (Auto sizes the disk ring to free space, " +
                "max 8 GB)."
        )

        SectionHeader("Reconnect")
        SliderRow("Max attempts", state.maxReconnectAttempts.toFloat(), 1f..10f, steps = 8) {
            viewModel.setMaxReconnects(it.toInt())
        }
        SliderRow("Retry delay (ms)", state.reconnectDelayMs.toFloat(), 500f..8000f, steps = 14) {
            viewModel.setReconnectDelay((it / 500).toInt() * 500)
        }

        SectionHeader("VOD / Series")
        SwitchRow("Auto-play next episode", state.autoPlayNextEpisode, viewModel::setAutoPlayNext)
        if (state.autoPlayNextEpisode) {
            SliderRow("Countdown (s)", state.autoPlayCountdown.toFloat(), 3f..15f, steps = 11) {
                viewModel.setAutoPlayCountdown(it.toInt())
            }
        }
        SwitchRow("Resume playback position", state.resumePlayback, viewModel::setResumePlayback)
        SwitchRow("Hardware acceleration", state.hardwareAccelerated, viewModel::setHwAccelerated)

        SectionHeader("Audio")
        SliderRow("Audio offset (ms)", state.audioOffsetMs.toFloat(), -500f..500f, steps = 19) {
            viewModel.setAudioOffset(it.toInt())
        }

        HorizontalDivider(color = BorderDefault)

        // ── Player Experience ──────────────────────────────────────────────────
        SectionHeader("Player Experience")

        SwitchRow("Keep Screen On During Playback", state.keepScreenOnDuringPlayback, viewModel::setKeepScreenOn)
        InfoCard("Firestick Sleep Prevention", "Prevents the FireStick display from sleeping while watching or recording. Eliminates the ~25-min inactivity auto-sleep that breaks video after wake.")

        Text("Skip Interval (Rewind / Fast Forward)", color = TextPrimary, fontSize = 13.sp)
        Text("How many seconds to jump per button press", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("5", "10", "15", "30", "60"),
            selected = state.skipIntervalSeconds.toString(),
            labels = listOf("5s", "10s", "15s", "30s", "60s"),
            onSelect = { viewModel.setSkipInterval(it.toIntOrNull() ?: 10) }
        )

        Text("Controls Auto-Hide Delay", color = TextPrimary, fontSize = 13.sp)
        Text("Seconds before the player overlay hides automatically after input", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("3", "5", "8", "10"),
            selected = state.controlsAutoHideSeconds.toString(),
            labels = listOf("3s", "5s", "8s", "10s"),
            onSelect = { viewModel.setControlsAutoHide(it.toIntOrNull() ?: 5) }
        )

        Text("Channel Zap Banner Duration", color = TextPrimary, fontSize = 13.sp)
        Text("How long the channel info OSD stays on screen when you change channels", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("3", "5", "8"),
            selected = state.zappingOsdDurationSeconds.toString(),
            labels = listOf("3s", "5s", "8s"),
            onSelect = { viewModel.setZappingOsdDuration(it.toIntOrNull() ?: 6) }
        )

        Text("Preferred Audio Language", color = TextPrimary, fontSize = 13.sp)
        Text("Auto-select audio track by language when multiple tracks are present", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("auto", "eng", "spa", "fre", "ger", "jpn", "por"),
            selected = state.preferredAudioLanguage,
            labels = listOf("Auto", "English", "Spanish", "French", "German", "Japanese", "Portuguese"),
            onSelect = viewModel::setPreferredAudioLanguage
        )

        SliderRow(
            "Controls Overlay Opacity (%)",
            state.playerOverlayOpacity.toFloat(),
            40f..100f,
            steps = 11
        ) { viewModel.setPlayerOverlayOpacity(it.toInt()) }

        SwitchRow("Show Clock in Player", state.showClockInPlayer, viewModel::setShowClockInPlayer)
        SwitchRow("Show Channel Numbers in Player HUD", state.showChannelNumbersInPlayer, viewModel::setShowChannelNumInPlayer)

        HorizontalDivider(color = BorderDefault)

        // ── DVR Quick Options ─────────────────────────────────────────────────
        SectionHeader("DVR Quick Options")

        SwitchRow("Allow Background Recording", state.recordInBackground, viewModel::setRecordInBackground)
        InfoCard("Background DVR", "When enabled, recordings continue after closing the player. Start up to 3 simultaneous recordings from the DVR / Guide screen.")

        SliderRow(
            "Default Recording Duration (min)",
            state.defaultRecordingDurationMin.toFloat(),
            15f..180f,
            steps = 10
        ) { viewModel.setDefaultRecordingDuration(((it / 15f).toInt() * 15).coerceAtLeast(15)) }

        HorizontalDivider(color = BorderDefault)

        // ── Content Display ───────────────────────────────────────────────────
        SectionHeader("Content Display")

        SwitchRow("Continue Watching Row on Home", state.continueWatchingEnabled, viewModel::setContinueWatching)

        Text("Thumbnail Image Quality", color = TextPrimary, fontSize = 13.sp)
        Text("Higher quality uses more bandwidth and memory", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("Low", "Medium", "High"),
            selected = state.thumbnailQuality,
            labels = listOf("Low (fast)", "Medium", "High (sharp)"),
            onSelect = viewModel::setThumbnailQuality
        )
    }
}

// ── Tab: EPG ──────────────────────────────────────────────────────────────────

@Composable
private fun EpgTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    val epgUrlFocusRequester = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Guide Window")
        SliderRow("Hours to show", state.epgHoursToShow.toFloat(), 2f..12f, steps = 9) {
            viewModel.setEpgHoursToShow(it.toInt())
        }
        SliderRow("Refresh interval (hours)", state.epgRefreshIntervalHours.toFloat(), 1f..24f, steps = 22) {
            viewModel.setEpgRefreshHours(it.toInt())
        }

        SectionHeader("Display")
        SegmentedPick(
            options = listOf("24h", "12h"),
            selected = state.epgTimeFormat,
            labels = listOf("24-hour", "12-hour AM/PM"),
            onSelect = viewModel::setEpgTimeFormat
        )
        SwitchRow("Show programme descriptions", state.epgShowDescriptions, viewModel::setEpgShowDescriptions)
        SwitchRow("Compact row mode", state.epgCompactMode, viewModel::setEpgCompactMode)

        HorizontalDivider(color = BorderDefault)

        SectionHeader("Third-party XMLTV")
        SwitchRow(
            "Use third-party EPG fallback",
            state.epgThirdPartyEnabled,
            viewModel::setEpgThirdPartyEnabled
        )
        SettingsTextField(
            value = state.epgThirdPartyUrl,
            onValueChange = viewModel::setEpgThirdPartyUrl,
            label = "Fallback XMLTV URLs",
            placeholder = SettingsViewModel.DEFAULT_THIRD_PARTY_EPG_URL,
            focusRequester = epgUrlFocusRequester,
            imeAction = ImeAction.Done,
            keyboardType = KeyboardType.Uri,
            singleLine = false,
            minLines = 4,
            maxLines = 6
        )
        TextButton(onClick = viewModel::resetEpgThirdPartyUrl) {
            Text("Reset to curated fallback sources")
        }

        HorizontalDivider(color = BorderDefault)

        SectionHeader("TV Guide Grid")
        // Hours shown in guide grid (independent of Live TV EPG)
        val guideHoursOptions = listOf(2, 4, 6, 8)
        SegmentedPick(
            options = guideHoursOptions.map { it.toString() },
            selected = state.guideHoursToShow.toString(),
            labels = guideHoursOptions.map { "${it}h" },
            onSelect = { viewModel.setGuideHoursToShow(it.toIntOrNull() ?: 4) }
        )
        // Row height mode — affects how many channels are visible at once
        Text(
            "Row Height  (Compact = more channels, Large = easier to read)",
            color = TextSecondary, fontSize = 12.sp
        )
        SegmentedPick(
            options = listOf("Compact", "Normal", "Large"),
            selected = state.guideRowHeightMode,
            labels = listOf("Compact (~25)", "Normal (~15)", "Large (~10)"),
            onSelect = viewModel::setGuideRowHeightMode
        )
        SliderRow("EPG rows to pre-load", state.guideEpgPreloadRows.toFloat(), 10f..100f, steps = 17) {
            viewModel.setGuideEpgPreloadRows(it.toInt())
        }
        SwitchRow("Show channel numbers", state.guideShowChannelNumbers, viewModel::setGuideShowChannelNumbers)
        SwitchRow("Auto-scroll to current time on open", state.guideAutoScrollToNow, viewModel::setGuideAutoScrollToNow)
    }
}

// ── Tab: Appearance ───────────────────────────────────────────────────────────

@Composable
private fun AppearanceTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── App Theme Picker ───────────────────────────────────────────────
        SectionHeader("App Theme")
        val themes = AppTheme.values().toList()
        // Row of theme cards — 2 per row using a simple grid approach
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            themes.chunked(2).forEach { rowThemes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    rowThemes.forEach { theme ->
                        val isSelected = state.appTheme == theme
                        val themeColors = theme.colors()
                        val interactionSource = remember { MutableInteractionSource() }
                        val isFocused by interactionSource.collectIsFocusedAsState()
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .focusable(interactionSource = interactionSource)
                                .then(
                                    if (isFocused || isSelected)
                                        Modifier.border(2.dp, themeColors.accent, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable(interactionSource = interactionSource, indication = null) {
                                    viewModel.setAppTheme(theme)
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) themeColors.bgSurface2 else BgSurface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(theme.emoji, fontSize = 18.sp)
                                    Text(
                                        theme.displayName,
                                        color = if (isSelected) themeColors.accent else TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        fontSize = 13.sp
                                    )
                                    if (isSelected) {
                                        Spacer(Modifier.weight(1f))
                                        Icon(Icons.Default.Check, null,
                                            tint = themeColors.accent,
                                            modifier = Modifier.size(16.dp))
                                    }
                                }
                                // Color preview dots
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(
                                        themeColors.accent,
                                        themeColors.accentSecondary,
                                        themeColors.accentBlue,
                                        themeColors.bgSurface2,
                                        themeColors.statusSuccess
                                    ).forEach { c ->
                                        Box(
                                            modifier = Modifier
                                                .size(14.dp)
                                                .background(c, RoundedCornerShape(50))
                                        )
                                    }
                                }
                            }
                        }
                    }
                    // Fill last row if odd number of themes
                    if (rowThemes.size < 2) Spacer(Modifier.weight(1f))
                }
            }
        }

        HorizontalDivider(color = BorderDefault)

        SectionHeader("Accent Colour")
        val accentChoices = listOf(
            "#06B6D4" to "Cyan (default)",
            "#FF6B35" to "Orange",
            "#7C3AED" to "Purple",
            "#10B981" to "Emerald",
            "#EF4444" to "Red",
            "#F59E0B" to "Amber"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            accentChoices.forEach { (hex, label) ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isSelected = state.accentColorHex == hex
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Accent }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .focusable(interactionSource = interactionSource)
                        .then(
                            if (isFocused || isSelected)
                                Modifier.border(2.dp, color, RoundedCornerShape(8.dp))
                            else Modifier
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color, RoundedCornerShape(6.dp))
                            .clickable(interactionSource = interactionSource, indication = null) {
                                viewModel.setAccentColor(hex)
                            }
                    ) {
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = Color.White,
                                modifier = Modifier.align(Alignment.Center).size(18.dp))
                        }
                    }
                    Text(label, fontSize = 9.sp, color = TextSecondary)
                }
            }
        }

        SectionHeader("Font & Grid")
        SliderRow("Font scale %", state.fontSizeScalePct.toFloat(), 75f..150f, steps = 14) {
            viewModel.setFontScale(it.toInt())
        }
        SliderRow("Grid columns", state.gridColumnsOverride.toFloat(), 3f..8f, steps = 4) {
            viewModel.setGridCols(it.toInt())
        }

        SectionHeader("Poster Aspect")
        SegmentedPick(
            options = listOf("2:3", "16:9"),
            selected = state.posterAspectRatio,
            labels = listOf("Portrait 2:3", "Widescreen 16:9"),
            onSelect = viewModel::setPosterAspect
        )

        SwitchRow("UI animations", state.uiAnimationsEnabled, viewModel::setUiAnimations)
    }
}

// ── Tab: Subtitles ────────────────────────────────────────────────────────────

@Composable
private fun SubtitlesTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("Language")
        val langs = listOf("eng" to "English", "fra" to "French", "spa" to "Spanish",
            "deu" to "German", "ara" to "Arabic", "por" to "Portuguese")
        SegmentedPick(
            options = langs.map { it.first },
            selected = state.subtitleLanguage,
            labels = langs.map { it.second },
            onSelect = viewModel::setSubtitleLanguage
        )

        SectionHeader("Text Style")
        SliderRow("Size (sp)", state.subtitleSizeSp.toFloat(), 10f..72f, steps = 61) {
            viewModel.setSubtitleSize(it.toInt())
        }

        SectionHeader("Subtitle colour")
        val colorChoices = listOf(
            "#FFFFFF" to "White",
            "#FFFF00" to "Yellow",
            "#00FFFF" to "Cyan",
            "#FFA500" to "Orange",
            "#FF6B6B" to "Red"
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            colorChoices.forEach { (hex, label) ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isSelected = state.subtitleColorHex == hex
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.White }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .focusable(interactionSource = interactionSource)
                        .then(if (isFocused || isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color, RoundedCornerShape(6.dp))
                            .clickable(interactionSource = interactionSource, indication = null) {
                                viewModel.setSubtitleColor(hex)
                            }
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null, tint = Color.Black,
                            modifier = Modifier.align(Alignment.Center).size(18.dp))
                    }
                    Text(label, fontSize = 9.sp, color = TextSecondary)
                }
            }
        }

        SectionHeader("Background opacity")
        SliderRow("Opacity %", state.subtitleBgOpacity.toFloat(), 0f..100f, steps = 9) {
            viewModel.setSubtitleBgOpacity(it.toInt())
        }

        SectionHeader("Outline / Border Colour")
        Text(
            "Colour of the border drawn around subtitle text. Black is default for readability.",
            color = TextTertiary, fontSize = 11.sp
        )
        val outlineChoices = listOf(
            "#000000" to "Black",
            "#1A1A2E" to "Dark Navy",
            "#3D0000" to "Dark Red",
            "#330033" to "Dark Purple",
            "#002200" to "Dark Green",
            "#333333" to "Charcoal",
            "#FFFFFF" to "White",
            "#FF0076" to "Hot Pink"
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState())
        ) {
            outlineChoices.forEach { (hex, label) ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()
                val isSelected = state.subtitleOutlineColorHex == hex
                val color = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Black }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .focusable(interactionSource = interactionSource)
                        .then(if (isFocused || isSelected) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(color, RoundedCornerShape(6.dp))
                            .border(1.dp, BorderDefault, RoundedCornerShape(6.dp))
                            .clickable(interactionSource = interactionSource, indication = null) {
                                viewModel.setSubtitleOutlineColor(hex)
                            }
                    ) {
                        if (isSelected) Icon(Icons.Default.Check, null,
                            tint = if (hex == "#FFFFFF" || hex == "#FFFFFF") Color.Black else Color.White,
                            modifier = Modifier.align(Alignment.Center).size(18.dp))
                    }
                    Text(label, fontSize = 9.sp, color = TextSecondary)
                }
            }
        }

        InfoCard(
            "Subtitle settings",
            "Font size, colour, outline, and background are applied to LibVLC and Media3 playback where supported."
        )
    }
}

// ── Tab: System ───────────────────────────────────────────────────────────────

@Composable
private fun SystemTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SectionHeader("LibVLC Network")
        SliderRow("Network cache (ms)", state.networkCacheMs.toFloat(), 500f..16000f, steps = 30) {
            viewModel.setNetworkCache((it / 500).toInt() * 500)
        }

        SectionHeader("Hardware Decoding")
        SegmentedPick(
            options = listOf("auto", "disabled", "force"),
            selected = state.hwDecodingMode,
            labels = listOf("Auto", "Software only", "Force HW"),
            onSelect = viewModel::setHwDecodingMode
        )

        SectionHeader("Deinterlace")
        SegmentedPick(
            options = listOf("blend", "bob", "linear", "disabled"),
            selected = state.deinterlaceMode,
            labels = listOf("Blend", "Bob", "Linear", "Off"),
            onSelect = viewModel::setDeinterlaceMode
        )

        SwitchRow("Audio passthrough (AC3/DTS)", state.audioPassthrough, viewModel::setAudioPassthrough)

        InfoCard(
            "LibVLC Options",
            "Changes apply to the next stream. Live TV uses --network-caching, " +
            "VOD uses --file-caching. Hardware decoding requires device support."
        )
    }
}

// ── Tab: LibVLC ───────────────────────────────────────────────────────────────
// Fine-grained LibVLC engine options.  All values take effect on next stream open.

@Composable
private fun LibVlcTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Info card
        Card(
            colors = CardDefaults.cardColors(containerColor = BgElevated),
            shape = RoundedCornerShape(10.dp)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(18.dp))
                Text(
                    "LibVLC engine settings. Changes take effect on the next opened stream. Defaults are tuned for FireStick.",
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }

        // ── Audio ──────────────────────────────────────────────────────────────
        SectionHeader("Audio Engine")

        Text("Audio Output Module", color = TextPrimary, fontSize = 13.sp)
        Text("android_audiotrack = recommended for FireTV; opensles = alternative low-latency path", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("android_audiotrack", "opensles", "auto"),
            selected = state.vlcAudioOutput,
            labels  = listOf("AudioTrack", "OpenSL ES", "Auto"),
            onSelect = { viewModel.setVlcAudioOutput(it) }
        )

        Text("Audio Resampler", color = TextPrimary, fontSize = 13.sp)
        Text("soxr = high quality; speex = balanced CPU/quality; ugly = minimal CPU", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("soxr", "speex", "ugly"),
            selected = state.vlcAudioResampler,
            labels  = listOf("SoXr (HQ)", "Speex", "Ugly (Fast)"),
            onSelect = { viewModel.setVlcAudioResampler(it) }
        )

        // ── Video ──────────────────────────────────────────────────────────────
        SectionHeader("Video Rendering")

        Text("Display Chroma Format", color = TextPrimary, fontSize = 13.sp)
        Text("RV32 = best compatibility; RV16 = lower memory; RGBA = transparency support", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("RV32", "RV16", "RGBA", "auto"),
            selected = state.vlcChromaFormat,
            labels  = listOf("RV32", "RV16", "RGBA", "Auto"),
            onSelect = { viewModel.setVlcChromaFormat(it) }
        )

        // ── Subtitles & CC ─────────────────────────────────────────────────────
        SectionHeader("Subtitles & CC")

        Text("Subtitle Text Encoding", color = TextPrimary, fontSize = 13.sp)
        Text("auto = detect automatically. Change if subtitles show garbled text.", color = TextTertiary, fontSize = 11.sp)
        SegmentedPick(
            options = listOf("auto", "UTF-8", "ISO-8859-1", "windows-1252", "KOI8-R"),
            selected = state.vlcSubtitleEncoding,
            labels  = listOf("Auto", "UTF-8", "Latin-1", "Win-1252", "KOI8-R"),
            onSelect = { viewModel.setVlcSubtitleEncoding(it) }
        )

        SwitchRow(
            label   = "Hardware Subtitle Rendering",
            checked = state.vlcHwSubtitles,
            onChange = { viewModel.setVlcHwSubtitles(it) }
        )

        // ── Sync & Timing ──────────────────────────────────────────────────────
        SectionHeader("Sync & Timing")

        SliderRow(
            label  = "Clock Jitter (ms) — 0 = off, recommended for IPTV live",
            value  = state.vlcClockJitterMs.toFloat(),
            range  = 0f..200f,
            steps  = 19,
            onChange = { viewModel.setVlcClockJitterMs(it.toInt()) }
        )

        SwitchRow(
            label    = "Drop Late Frames",
            checked  = state.vlcDropLateFrames,
            onChange = { viewModel.setVlcDropLateFrames(it) }
        )
        SwitchRow(
            label    = "Skip Frames (aggressive sync)",
            checked  = state.vlcSkipFrames,
            onChange = { viewModel.setVlcSkipFrames(it) }
        )

        // ── Network ────────────────────────────────────────────────────────────
        SectionHeader("Network")

        SliderRow(
            label  = "Network MTU (bytes) — 0 = auto",
            value  = state.vlcNetworkMtu.toFloat(),
            range  = 0f..9000f,
            steps  = 89,
            onChange = { viewModel.setVlcNetworkMtu((it / 100f).toInt() * 100) }
        )

        // ── Debug ──────────────────────────────────────────────────────────────
        SectionHeader("Debug")

        SwitchRow(
            label    = "Verbose LibVLC Logging (logcat)",
            checked  = state.vlcVerboseLog,
            onChange = { viewModel.setVlcVerboseLog(it) }
        )

        Spacer(Modifier.height(32.dp))
    }
}

// ── Tab: Performance ──────────────────────────────────────────────────────────

@Composable
private fun PerformanceTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── General ──────────────────────────────────────────────────────────
        SectionHeader("General")
        SliderRow("Max concurrent streams", state.maxConcurrentStreams.toFloat(), 1f..4f, steps = 2) {
            viewModel.setMaxConcurrentStreams(it.toInt())
        }
        SliderRow("Image cache (MB)", state.imageCacheMb.toFloat(), 32f..512f, steps = 14) {
            viewModel.setImageCacheMb((it / 32).toInt() * 32)
        }
        SliderRow("EPG cache duration (hours)", state.epgCacheDurationHours.toFloat(), 1f..72f, steps = 17) {
            viewModel.setEpgCacheHours(it.toInt())
        }

        // ── Live TV Performance ───────────────────────────────────────────────
        SectionHeader("Live TV Performance")
        SliderRow(
            label = "Live network cache (ms)   [current: ${state.liveNetworkCacheMs}ms]",
            value = state.liveNetworkCacheMs.toFloat(),
            range = 500f..8000f,
            steps = 14
        ) {
            // Snap to 500ms steps
            viewModel.setLiveNetworkCache(((it / 500).toInt() * 500).coerceAtLeast(500))
        }
        SliderRow(
            label = "Live audio buffer (ms)   [current: ${state.liveAudioBufferMs}ms]",
            value = state.liveAudioBufferMs.toFloat(),
            range = 0f..2000f,
            steps = 19
        ) {
            viewModel.setLiveAudioBuffer(((it / 100).toInt() * 100))
        }
        ToggleRow(
            label = "Force clock sync (fixes AV desync on live)",
            value = state.liveClockJitter,
            onToggle = { viewModel.setLiveClockJitter(it) }
        )
        ToggleRow(
            label = "Adaptive live buffer (auto-tunes cache on rebuffers)",
            value = state.adaptiveBuffer,
            onToggle = { viewModel.setAdaptiveBuffer(it) }
        )
        if (state.adaptiveBuffer) {
            SliderRow(
                label = "Adaptive floor (ms)   [current: ${state.bufferFloorMs}ms]",
                value = state.bufferFloorMs.toFloat(),
                range = 300f..2000f,
                steps = 16
            ) {
                viewModel.setBufferFloor((it / 100).toInt() * 100)
            }
            SliderRow(
                label = "Adaptive ceiling (ms)   [current: ${state.bufferCeilingMs}ms]",
                value = state.bufferCeilingMs.toFloat(),
                range = 600f..8000f,
                steps = 36
            ) {
                viewModel.setBufferCeiling((it / 100).toInt() * 100)
            }
        }

        // ── VOD Performance ───────────────────────────────────────────────────
        SectionHeader("VOD / Movies / Series Performance")
        SliderRow(
            label = "VOD network cache (ms)   [current: ${state.vodNetworkCacheMs}ms]",
            value = state.vodNetworkCacheMs.toFloat(),
            range = 500f..6000f,
            steps = 10
        ) {
            viewModel.setVodNetworkCache(((it / 500).toInt() * 500).coerceAtLeast(500))
        }
        SliderRow(
            label = "VOD file cache (ms)   [current: ${state.vodFileCacheMs}ms]",
            value = state.vodFileCacheMs.toFloat(),
            range = 500f..4000f,
            steps = 6
        ) {
            viewModel.setVodFileCache(((it / 500).toInt() * 500).coerceAtLeast(500))
        }
        ToggleRow(
            label = "Prefer hardware decode for VOD",
            value = state.vodPreferHwDecode,
            onToggle = { viewModel.setVodPreferHw(it) }
        )

        // ── Memory Management ─────────────────────────────────────────────────
        SectionHeader("Memory Management")
        SliderRow(
            label = "Thumbnail cache (MB)   [current: ${state.thumbnailCacheMb}MB]",
            value = state.thumbnailCacheMb.toFloat(),
            range = 16f..256f,
            steps = 14
        ) {
            viewModel.setThumbnailCacheMb(((it / 16).toInt() * 16).coerceAtLeast(16))
        }
        SliderRow(
            label = "Bitmap pool (MB)   [current: ${state.bitmapPoolMb}MB]",
            value = state.bitmapPoolMb.toFloat(),
            range = 8f..128f,
            steps = 14
        ) {
            viewModel.setBitmapPoolMb(((it / 8).toInt() * 8).coerceAtLeast(8))
        }
        SliderRow(
            label = "HTTP response cache (MB)   [current: ${state.responseCacheMb}MB]",
            value = state.responseCacheMb.toFloat(),
            range = 10f..200f,
            steps = 18
        ) {
            viewModel.setResponseCacheMb(((it / 10).toInt() * 10).coerceAtLeast(10))
        }

        // ── DVR Performance ───────────────────────────────────────────────────
        SectionHeader("DVR Performance")
        SliderRow(
            label = "Max simultaneous recordings   [current: ${state.maxSimultaneousDvr}]",
            value = state.maxSimultaneousDvr.toFloat(),
            range = 1f..3f,
            steps = 1
        ) {
            viewModel.setMaxSimultaneousDvr(it.toInt())
        }

        InfoCard(
            "Performance Tips for Firestick 4K",
            "• Live TV: 3000ms network cache is safe for most ISPs. Reduce to 1000ms on fast connections.\n" +
            "• VOD: 2000ms network + 1500ms file cache avoids initial buffering stalls.\n" +
            "• Memory: Firestick Lite (1 GB RAM) → thumbnail 32 MB, bitmap pool 16 MB.\n" +
            "• Firestick 4K (2 GB RAM) → thumbnail 128 MB, bitmap pool 64 MB.\n" +
            "• DVR: Each recording uses ~8 MB RAM via LibVLC :sout pipeline. Max 3 on premium devices.\n" +
            "• Clock sync ON is strongly recommended for live TV AV sync stability."
        )
    }
}

// ── Tab: DVR Settings ─────────────────────────────────────────────────────────

private data class DvrVolumeListEntry(
    val label: String,
    val pathForDisplay: String,
    val storageVolume: StorageVolume?
)

/** Binds each app-specific files dir to [StorageManager.getStorageVolume] so the USB row opens the right picker. */
private fun buildDvrVolumeList(context: android.content.Context): List<DvrVolumeListEntry> {
    val dirs = context.getExternalFilesDirs(null).filterNotNull()
    if (dirs.isEmpty()) return emptyList()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
        return dirs.mapIndexed { i, f ->
            DvrVolumeListEntry(
                if (i == 0) "Internal Storage" else "USB $i (OTG)",
                f.absolutePath,
                null
            )
        }
    }
    val sm = context.getSystemService(StorageManager::class.java)
        ?: return dirs.mapIndexed { i, f ->
            DvrVolumeListEntry(
                if (i == 0) "Internal Storage" else "USB $i (OTG)",
                f.absolutePath,
                null
            )
        }
    return dirs.mapIndexed { index, file ->
        val sv = sm.getStorageVolume(file)
        val label = when {
            sv == null -> if (index == 0) "Internal Storage (Fire TV)" else "USB / OTG $index"
            sv.isPrimary -> "Internal Storage (Fire TV)"
            else -> (sv.getDescription(context).toString()).ifBlank { "USB / OTG" }
        }
        DvrVolumeListEntry(label, file.absolutePath, sv)
    }
}

@Composable
private fun DvrStorageDriveRow(
    label: String,
    path: String,
    onPick: () -> Unit,
    focusRequester: FocusRequester? = null,
    isActive: Boolean = false
) {
    val di = remember { MutableInteractionSource() }
    val df by di.collectIsFocusedAsState()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable(interactionSource = di)
            .then(
                when {
                    df -> Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp))
                    isActive -> Modifier.border(1.dp, StatusSuccess, RoundedCornerShape(8.dp))
                    else -> Modifier
                }
            )
            .clickable(interactionSource = di, indication = null, onClick = onPick)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    onPick()
                    true
                } else false
            },
        color = when {
            df -> Accent.copy(alpha = 0.10f)
            isActive -> StatusSuccess.copy(alpha = 0.08f)
            else -> BgSurface2
        },
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (label.contains("USB", ignoreCase = true)) Icons.Default.UsbOff else Icons.Default.Storage,
                null,
                tint = if (isActive) StatusSuccess else Accent,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    if (isActive) {
                        Text("ACTIVE", color = StatusSuccess, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                    }
                }
                Text(
                    path,
                    color = TextTertiary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun DvrSettingsTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val dvrSnackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun applyDvrPickedTreeUri(uri: android.net.Uri, resultData: Intent? = null) {
        val resultFlags = resultData?.flags ?: 0
        val readFlag = if (resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        } else {
            0
        }
        val writeFlag = if (resultFlags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0) {
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        } else {
            0
        }
        val flags = (readFlag or writeFlag).takeIf { it != 0 }
            ?: (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        runCatching { viewModel.setDvrStoragePath(uri.toString()) }
    }

    val dvrTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        when {
            result.resultCode != Activity.RESULT_OK && uri == null -> {
                scope.launch {
                    dvrSnackbar.showSnackbar("Storage picker cancelled. Try “Choose folder” and select USB in the left sidebar.")
                }
            }
            uri != null -> {
                runCatching { applyDvrPickedTreeUri(uri, result.data) }
                scope.launch {
                    dvrSnackbar.showSnackbar("Recording folder saved. New recordings will use it.")
                }
            }
            else -> {
                scope.launch {
                    dvrSnackbar.showSnackbar("No folder returned. Open “Choose folder” → menu → your USB / thumb drive, then use “Use this folder”.")
                }
            }
        }
    }

    fun launchFolderPickerForRow(entry: DvrVolumeListEntry) {
        // Try volume-specific intent (may include EXTRA_INITIAL_URI) first
        val intent = openTreeIntentForVolume(context, entry.storageVolume)
        val launched = try { dvrTreeLauncher.launch(intent); true } catch (_: Exception) { false }
        if (!launched) {
            // Fallback: Fire TV safe intent — no EXTRA_INITIAL_URI to prevent file manager crash
            try { dvrTreeLauncher.launch(fireTvSafeOpenTreeIntent()) }
            catch (_: android.content.ActivityNotFoundException) {
                scope.launch {
                    dvrSnackbar.showSnackbar("No file manager found. Plug in USB and try again.")
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        // ── STORAGE LOCATION — PRIMARY FEATURE ────────────────────────────────
        SectionHeader("Recording Storage Location")

        val volumes = remember(context) { buildDvrVolumeList(context) }

        // Current storage path display card
        val currentPath = state.dvrStoragePath
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (currentPath != null) StatusSuccess.copy(alpha = 0.08f) else BgSurface2
            ),
            shape = RoundedCornerShape(10.dp),
            border = if (currentPath != null)
                androidx.compose.foundation.BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.4f))
            else null
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    if (currentPath != null) Icons.Default.CheckCircle else Icons.Default.Folder,
                    null,
                    tint = if (currentPath != null) StatusSuccess else TextTertiary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (currentPath != null) "Custom Storage Active" else "Using Internal Storage",
                        color = if (currentPath != null) StatusSuccess else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    Text(
                        currentPath?.let {
                            // Pretty-print: show only the last 60 chars so it fits on screen
                            if (it.length > 60) "…" + it.takeLast(60) else it
                        } ?: run {
                            val externalBase = context.getExternalFilesDir(null)?.absolutePath
                                ?: android.os.Environment.getExternalStorageDirectory().absolutePath
                            "$externalBase/DVR/"
                        },
                        color = TextTertiary,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Storage action buttons ─────────────────────────────────────────────
        // Amazon/FireOS 8: ACTION_OPEN_DOCUMENT_TREE is silently blocked.
        // Instead, use getExternalFilesDirs() which gives app-private writable paths
        // on every mounted volume with ZERO permission grants — no file manager needed.
        val isAmazonDevice = Build.MANUFACTURER.contains("Amazon", ignoreCase = true)

        if (isAmazonDevice) {
            // ── Amazon/FireOS: auto-detected volume buttons (no SAF) ───────────
            Text(
                "Select Recording Location",
                color = TextSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
            if (volumes.isEmpty()) {
                Text(
                    "No storage detected. Plug in a USB OTG drive and reopen this screen.",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            } else {
                val preferredOtgIndex = volumes.indexOfFirst {
                    !it.pathForDisplay.contains("/emulated/", ignoreCase = true)
                }.takeIf { it >= 0 } ?: 0
                val preferredUsbFr = remember { FocusRequester() }
                // Prefer USB/OTG on tab open: autofocus + auto-persist (matches DvrStorageViewModel).
                LaunchedEffect(volumes.map { it.pathForDisplay }) {
                    val preferred = volumes.getOrNull(preferredOtgIndex) ?: return@LaunchedEffect
                    val current = state.dvrStoragePath
                    val preferredIsOtg = !preferred.pathForDisplay.contains("/emulated/", ignoreCase = true)
                    if (preferredIsOtg && current != preferred.pathForDisplay) {
                        viewModel.setDvrStoragePath(preferred.pathForDisplay)
                    } else if (current.isNullOrBlank()) {
                        viewModel.setDvrStoragePath(preferred.pathForDisplay)
                    }
                    delay(120)
                    runCatching { preferredUsbFr.requestFocus() }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    volumes.forEachIndexed { index, entry ->
                        key(entry.pathForDisplay) {
                            // Classify label: OTG drives have paths that do NOT contain /emulated/
                            val isOtg = !entry.pathForDisplay.contains("/emulated/", ignoreCase = true)
                            val displayLabel = when {
                                isOtg && index == 0 -> "USB Drive 1 (OTG)"
                                isOtg               -> "USB Drive ${index + 1} (OTG)"
                                else                -> "Internal Storage"
                            }
                            val isActive = state.dvrStoragePath == entry.pathForDisplay ||
                                (state.dvrStoragePath.isNullOrBlank() && index == preferredOtgIndex)
                            DvrStorageDriveRow(
                                label = displayLabel,
                                path  = entry.pathForDisplay,
                                focusRequester = if (index == preferredOtgIndex) preferredUsbFr else null,
                                isActive = isActive,
                                onPick = {
                                    // Directly apply the app-private path — no SAF required.
                                    viewModel.setDvrStoragePath(entry.pathForDisplay)
                                    scope.launch {
                                        dvrSnackbar.showSnackbar("Recording location set to $displayLabel")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        } else {
            // ── Non-Amazon: Keep original Choose Drive + Choose Folder buttons ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // BUTTON 1: Show detected drives in a dialog
                var showDrivePicker by remember { mutableStateOf(false) }
                val driveInteraction = remember { MutableInteractionSource() }
                val driveFocused by driveInteraction.collectIsFocusedAsState()

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .focusable(interactionSource = driveInteraction)
                        .then(if (driveFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable(interactionSource = driveInteraction, indication = null) {
                            showDrivePicker = true
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                showDrivePicker = true; true
                            } else false
                        },
                    color = if (driveFocused) Accent.copy(alpha = 0.15f) else BgSurface2,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.Storage,
                            null,
                            tint = if (driveFocused) Accent else Accent.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Choose Drive",
                            color = if (driveFocused) Accent else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            "Internal or USB OTG",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                    }
                }

                if (showDrivePicker) {
                    AlertDialog(
                        onDismissRequest = { showDrivePicker = false },
                        containerColor = BgElevated,
                        titleContentColor = TextPrimary,
                        title = { Text("Select Storage Drive", fontWeight = FontWeight.Bold) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (volumes.isEmpty()) {
                                    Text("No external storage detected.", color = TextSecondary)
                                } else {
                                    volumes.forEach { entry ->
                                        key(entry.pathForDisplay) {
                                            DvrStorageDriveRow(
                                                label = entry.label,
                                                path = entry.pathForDisplay,
                                                onPick = {
                                                    launchFolderPickerForRow(entry)
                                                    showDrivePicker = false
                                                }
                                            )
                                        }
                                    }
                                    if (state.dvrStoragePath != null) {
                                        Text(
                                            "Custom folder active — see card above. Choose a row to pick again.",
                                            color = TextTertiary,
                                            fontSize = 10.sp,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Select a drive then choose your recording folder inside it.",
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showDrivePicker = false }) {
                                Text("Cancel", color = TextSecondary)
                            }
                        }
                    )
                }

                // BUTTON 2: Open SAF folder browser directly (hidden on Amazon/FireOS)
                val folderInteraction = remember { MutableInteractionSource() }
                val folderFocused by folderInteraction.collectIsFocusedAsState()

                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .focusable(interactionSource = folderInteraction)
                        .then(if (folderFocused) Modifier.border(2.dp, AccentSecondary, RoundedCornerShape(8.dp)) else Modifier)
                        .clickable(interactionSource = folderInteraction, indication = null) {
                            // Fire TV safe: no EXTRA_INITIAL_URI — prevents Amazon file manager crash
                            try { dvrTreeLauncher.launch(fireTvSafeOpenTreeIntent()) }
                            catch (_: android.content.ActivityNotFoundException) {
                                scope.launch { dvrSnackbar.showSnackbar("No file manager available. Install a file manager app and try again.") }
                            }
                        }
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                                try { dvrTreeLauncher.launch(fireTvSafeOpenTreeIntent()) }
                                catch (_: android.content.ActivityNotFoundException) {
                                    scope.launch { dvrSnackbar.showSnackbar("No file manager available. Install a file manager app and try again.") }
                                }
                                true
                            } else false
                        },
                    color = if (folderFocused) AccentSecondary.copy(alpha = 0.15f) else BgSurface2,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            null,
                            tint = if (folderFocused) AccentSecondary else AccentSecondary.copy(alpha = 0.7f),
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            "Choose Folder",
                            color = if (folderFocused) AccentSecondary else TextSecondary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                        Text(
                            "Browse all drives",
                            color = TextTertiary,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }

        // Clear custom storage button
        if (state.dvrStoragePath != null) {
            val clearInteraction = remember { MutableInteractionSource() }
            val clearFocused by clearInteraction.collectIsFocusedAsState()
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(interactionSource = clearInteraction)
                    .then(if (clearFocused) Modifier.border(2.dp, StatusError.copy(alpha = 0.5f), RoundedCornerShape(8.dp)) else Modifier)
                    .clickable(interactionSource = clearInteraction, indication = null) {
                        viewModel.clearDvrStoragePath()
                    }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                            viewModel.clearDvrStoragePath(); true
                        } else false
                    },
                color = if (clearFocused) StatusError.copy(alpha = 0.10f) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.ClearAll, null,
                        tint = if (clearFocused) StatusError else StatusError.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp))
                    Text(
                        "Reset to Internal Storage",
                        color = if (clearFocused) StatusError else StatusError.copy(alpha = 0.6f),
                        fontSize = 12.sp
                    )
                }
            }
        }

        InfoCard(
            "USB / OTG Storage Tips (Fire TV)",
            "• Easiest: press “Choose folder” → open the left menu → select your USB / thumb drive → enter Download (or any folder) → “Use this folder”.\n" +
            "• “Choose drive” is optional; if the top card still says Internal, the folder pick still worked — check the green path text.\n" +
            "• Plug in USB before opening this screen. FAT32 and exFAT are fine."
        )

        HorizontalDivider(color = BorderDefault)

        // ── Recording Buffers ──────────────────────────────────────────────────
        SectionHeader("Recording Buffers")
        SliderRow("Pre-record buffer (min)", state.dvrPreBufferMinutes.toFloat(), 0f..15f, steps = 14) {
            viewModel.setDvrPreBuffer(it.toInt())
        }
        SliderRow("Post-record buffer (min)", state.dvrPostBufferMinutes.toFloat(), 0f..30f, steps = 29) {
            viewModel.setDvrPostBuffer(it.toInt())
        }

        SectionHeader("Storage Quota")

        // ── Live Storage Usage Bar ─────────────────────────────────────────────
        val storageStats = remember(state.dvrStoragePath) {
            try {
                val targetDir = StorageDetector.findPreferredDvrBasePath(context, state.dvrStoragePath)
                    ?: context.getExternalFilesDir(null)
                if (targetDir != null) {
                    if (!targetDir.exists()) targetDir.mkdirs()
                    val stat = android.os.StatFs(targetDir.absolutePath)
                    val total = maxOf(stat.totalBytes, targetDir.totalSpace)
                    val free  = maxOf(stat.availableBytes, targetDir.usableSpace, targetDir.freeSpace)
                    val used  = total - free
                    if (total > 0L && free >= 0L) Triple(total, free, used.coerceAtLeast(0L)) else null
                } else null
            } catch (_: Exception) { null }
        }

        if (storageStats != null) {
            val (total, free, used) = storageStats
            val usedFraction = if (total > 0) used.toFloat() / total.toFloat() else 0f
            val usedGb  = "%.1f".format(used  / 1_073_741_824.0)
            val totalGb = "%.1f".format(total / 1_073_741_824.0)
            val freeGb  = "%.1f".format(free  / 1_073_741_824.0)
            val barColor = when {
                usedFraction > 0.90f -> StatusError
                usedFraction > 0.70f -> StatusWarning
                else                 -> StatusSuccess
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = BgSurface2),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Storage Usage", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "$usedGb GB used of $totalGb GB  ·  $freeGb GB free",
                            color = TextTertiary,
                            fontSize = 11.sp
                        )
                    }
                    LinearProgressIndicator(
                        progress = { usedFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp)),
                        color = barColor,
                        trackColor = BgElevated
                    )
                    if (usedFraction > 0.85f) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = StatusWarning, modifier = Modifier.size(14.dp))
                            Text("Storage is nearly full. Enable auto-delete or increase quota.", color = StatusWarning, fontSize = 11.sp)
                        }
                    }
                }
            }
        } else {
            InfoCard(
                "Storage Capacity",
                "Fire OS is hiding exact capacity for this target. DVR will still prefer USB and verify the folder with a write test before each recording."
            )
        }

        SliderRow("Storage quota (GB)", state.dvrStorageQuotaGb.toFloat(), 1f..100f, steps = 18) {
            viewModel.setDvrStorageQuota(it.toInt())
        }
        SwitchRow("Auto-delete completed recordings", state.dvrAutoDeleteCompleted, viewModel::setDvrAutoDelete)
        InfoCard(
            "Live Pause & Rewind",
            "Enabled automatically for channels whose provider supports catch-up/archive. " +
                "Press Pause to hold the current live position, or choose Restart to watch the current program from the beginning."
        )

        SectionHeader("Quality")
        SegmentedPick(
            options = listOf("Original", "720p", "480p"),
            selected = state.dvrDefaultQuality,
            labels = listOf("Original (Best)", "HD 720p", "SD 480p"),
            onSelect = viewModel::setDvrDefaultQuality
        )

        SectionHeader("Notifications")
        SwitchRow("Recording start/stop notifications", state.dvrNotificationsEnabled, viewModel::setDvrNotifications)

        SnackbarHost(
            hostState = dvrSnackbar,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ── Tab: Categories ───────────────────────────────────────────────────────────
//
// OOM FIX: The previous implementation used Column + forEachIndexed inside a verticalScroll
// container. With 500+ categories per section (×3 sections), that created 1500+ simultaneous
// MutableInteractionSource + collectIsFocusedAsState() flow collectors, causing OOM/crash on
// Firestick. The fix: use LazyColumn for the entire tab so category rows are rendered lazily
// (only visible rows are in the composition tree). MutableInteractionSource is replaced with
// a simple Modifier.onFocusChanged + local state for the D-pad focus ring.
@Composable
private fun CategoriesTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    LaunchedEffect(Unit) {
        if (state.availableLiveCategories.isEmpty() && !state.categoriesLoading) {
            viewModel.loadCategoriesForSettings()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 24.dp)
    ) {
        item {
            InfoCard(
                "Customize Your Categories",
                "Toggle categories on or off per section. Hidden categories won't appear in the " +
                "channel rail or category filter. Enable 'US/EN First' to pin American/English " +
                "content categories at the top of each section automatically."
            )
        }

        if (state.categoriesLoading) {
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = Accent, modifier = Modifier.size(20.dp))
                        Text("Loading categories from server...", color = TextSecondary)
                    }
                }
            }
        }

        // ── Live TV Categories ────────────────────────────────────────────────
        item { Spacer(Modifier.height(8.dp)); SectionHeader("Live TV Categories") }
        item { SwitchRow("US / English channels first", state.usEnFirstLive, viewModel::setUsEnFirstLive) }
        if (state.availableLiveCategories.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show/hide categories:", color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.resetHiddenLiveCategories() }) {
                        Text("Show All", color = Accent, fontSize = 12.sp)
                    }
                }
            }
            items(
                items = state.availableLiveCategories,
                key = { cat -> "live_${cat.categoryId}" }
            ) { cat ->
                CategoryToggleRow(
                    cat = cat,
                    isVisible = cat.categoryId !in state.hiddenLiveCategories,
                    isLast = cat == state.availableLiveCategories.last(),
                    onToggle = { viewModel.toggleHiddenLiveCategory(cat.categoryId) }
                )
            }
        } else if (!state.categoriesLoading) {
            item { Text("Connect to your server to load categories", color = TextTertiary, fontSize = 13.sp) }
        }

        item { Spacer(Modifier.height(4.dp)); HorizontalDivider(color = BorderDefault) }

        // ── Movies Categories ─────────────────────────────────────────────────
        item { SectionHeader("Movies Categories") }
        item { SwitchRow("US / English movies first", state.usEnFirstMovies, viewModel::setUsEnFirstMovies) }
        if (state.availableMovieCategories.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show/hide categories:", color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.resetHiddenMovieCategories() }) {
                        Text("Show All", color = Accent, fontSize = 12.sp)
                    }
                }
            }
            items(
                items = state.availableMovieCategories,
                key = { cat -> "movie_${cat.categoryId}" }
            ) { cat ->
                CategoryToggleRow(
                    cat = cat,
                    isVisible = cat.categoryId !in state.hiddenMovieCategories,
                    isLast = cat == state.availableMovieCategories.last(),
                    onToggle = { viewModel.toggleHiddenMovieCategory(cat.categoryId) }
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)); HorizontalDivider(color = BorderDefault) }

        // ── Series Categories ─────────────────────────────────────────────────
        item { SectionHeader("Series / TV Shows Categories") }
        item { SwitchRow("US / English series first", state.usEnFirstSeries, viewModel::setUsEnFirstSeries) }
        if (state.availableSeriesCategories.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show/hide categories:", color = TextSecondary, fontSize = 13.sp)
                    TextButton(onClick = { viewModel.resetHiddenSeriesCategories() }) {
                        Text("Show All", color = Accent, fontSize = 12.sp)
                    }
                }
            }
            items(
                items = state.availableSeriesCategories,
                key = { cat -> "series_${cat.categoryId}" }
            ) { cat ->
                CategoryToggleRow(
                    cat = cat,
                    isVisible = cat.categoryId !in state.hiddenSeriesCategories,
                    isLast = cat == state.availableSeriesCategories.last(),
                    onToggle = { viewModel.toggleHiddenSeriesCategory(cat.categoryId) }
                )
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

/**
 * A single lazy category toggle row.
 *
 * Uses Modifier.onFocusChanged + a local Boolean instead of MutableInteractionSource +
 * collectIsFocusedAsState(). This avoids creating a live Flow collector per row — critical
 * when rendering 500+ items in a LazyColumn on a memory-constrained Firestick.
 */
@Composable
private fun CategoryToggleRow(
    cat: com.dylandos.iptv.ultimate.data.model.XtreamCategory,
    isVisible: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isFocused = it.isFocused }
                .focusable()
                .onKeyEvent { ev ->
                    when {
                        ev.isRemoteConfirmKey() -> {
                            onToggle()
                            true
                        }
                        ev.type == KeyEventType.KeyDown && ev.key == FirestickKeyMap.MENU -> {
                            onToggle()
                            true
                        }
                        else -> false
                    }
                }
                .clickable { onToggle() }
                .then(
                    if (isFocused) Modifier
                        .background(Accent.copy(alpha = 0.14f))
                        .border(1.dp, Accent.copy(alpha = 0.55f))
                    else Modifier.background(BgSurface2)
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                    contentDescription = null,
                    tint = if (isVisible) Accent else TextTertiary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    cat.categoryName,
                    color = if (isVisible) TextPrimary else TextTertiary,
                    fontSize = 13.sp
                )
            }
            Switch(
                checked = isVisible,
                onCheckedChange = { onToggle() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = TextTertiary,
                    uncheckedTrackColor = BgElevated
                )
            )
        }
        if (!isLast) HorizontalDivider(
            color = BorderSubtle,
            modifier = Modifier.padding(horizontal = 16.dp)
        )
    }
}

// ── Tab: Parental Controls ────────────────────────────────────────────────────

@Composable
private fun ParentalTab(state: SettingsUiState, viewModel: SettingsViewModel) {
    var pinInput     by remember { mutableStateOf("") }
    var confirmInput by remember { mutableStateOf("") }
    var verifyInput  by remember { mutableStateOf("") }
    var mode         by remember { mutableStateOf(if (state.parentalPin.isEmpty()) "setup" else "manage") }
    var errorMsg     by remember { mutableStateOf<String?>(null) }
    var successMsg   by remember { mutableStateOf<String?>(null) }

    // Re-sync when state changes (e.g. after clearing PIN)
    LaunchedEffect(state.parentalPin) {
        mode = if (state.parentalPin.isEmpty()) "setup" else "manage"
        pinInput = ""; confirmInput = ""; verifyInput = ""
        errorMsg = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Filled.Lock, "Parental Controls", tint = Accent, modifier = Modifier.size(28.dp))
            Column {
                Text("Parental Controls", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary)
                Text(
                    if (state.parentalPin.isEmpty()) "Set a 4-digit PIN to restrict access"
                    else if (state.parentalEnabled) "PIN protection is ACTIVE" else "PIN set but disabled",
                    fontSize = 13.sp,
                    color = if (state.parentalEnabled) StatusSuccess else TextSecondary
                )
            }
        }

        HorizontalDivider(color = BgSurface3)

        if (mode == "setup") {
            // ── Set PIN flow ──────────────────────────────────────────────────
            Text("Create PIN", fontWeight = FontWeight.SemiBold, color = TextPrimary)

            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { pinInput = it; errorMsg = null } },
                label = { Text("New PIN (4 digits)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                    cursorColor = Accent
                )
            )

            OutlinedTextField(
                value = confirmInput,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { confirmInput = it; errorMsg = null } },
                label = { Text("Confirm PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    focusedLabelColor = Accent,
                    cursorColor = Accent
                )
            )

            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }

            val setPinInteraction = remember { MutableInteractionSource() }
            val setPinFocused by setPinInteraction.collectIsFocusedAsState()

            Button(
                onClick = {
                    when {
                        pinInput.length < 4     -> errorMsg = "PIN must be 4 digits"
                        pinInput != confirmInput -> errorMsg = "PINs do not match"
                        else -> {
                            viewModel.setParentalPin(pinInput)
                            successMsg = "PIN set successfully"
                            errorMsg = null
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = if (setPinFocused) Accent.copy(alpha = 0.8f) else Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .tvButtonFocusable(interactionSource = setPinInteraction)
            ) { Text("Set PIN", color = Color.Black, fontWeight = FontWeight.Bold) }

        } else {
            // ── Manage existing PIN ───────────────────────────────────────────

            // Enable/disable toggle
            val enableToggleInteraction = remember { MutableInteractionSource() }
            val enableToggleFocused by enableToggleInteraction.collectIsFocusedAsState()
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = BgSurface2,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusable(interactionSource = enableToggleInteraction)
                    .then(if (enableToggleFocused) Modifier.border(2.dp, Accent.copy(alpha = 0.7f), RoundedCornerShape(8.dp)) else Modifier)
                    .onKeyEvent { ev ->
                        if (ev.type == KeyEventType.KeyDown && (ev.key == Key.Enter || ev.key == Key.DirectionCenter)) {
                            viewModel.setParentalEnabled(!state.parentalEnabled); true
                        } else false
                    }
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Parental Controls Enabled", fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("Require PIN to access restricted content", fontSize = 12.sp, color = TextSecondary)
                    }
                    Switch(
                        checked = state.parentalEnabled,
                        onCheckedChange = { viewModel.setParentalEnabled(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = Accent, checkedTrackColor = Accent.copy(alpha = 0.4f))
                    )
                }
            }

            HorizontalDivider(color = BgSurface3)

            // Change PIN section
            Text("Change PIN", fontWeight = FontWeight.SemiBold, color = TextPrimary)

            OutlinedTextField(
                value = verifyInput,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { verifyInput = it; errorMsg = null } },
                label = { Text("Current PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent)
            )

            OutlinedTextField(
                value = pinInput,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { pinInput = it; errorMsg = null } },
                label = { Text("New PIN (4 digits)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Next),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent)
            )

            OutlinedTextField(
                value = confirmInput,
                onValueChange = { if (it.length <= 4 && it.all(Char::isDigit)) { confirmInput = it; errorMsg = null } },
                label = { Text("Confirm New PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Accent, focusedLabelColor = Accent, cursorColor = Accent)
            )

            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
            successMsg?.let { Text(it, color = StatusSuccess, fontSize = 13.sp) }

            val changePinInteraction = remember { MutableInteractionSource() }
            val removePinInteraction = remember { MutableInteractionSource() }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        when {
                            verifyInput.length < 4  -> errorMsg = "Enter current PIN"
                            !viewModel.verifyParentalPin(verifyInput) -> errorMsg = "Current PIN is incorrect"
                            pinInput.length < 4     -> errorMsg = "New PIN must be 4 digits"
                            pinInput != confirmInput -> errorMsg = "New PINs do not match"
                            else -> {
                                viewModel.setParentalPin(pinInput)
                                successMsg = "PIN changed"; errorMsg = null
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier
                        .weight(1f)
                        .tvButtonFocusable(interactionSource = changePinInteraction)
                ) { Text("Change PIN", color = Color.Black, fontWeight = FontWeight.Bold) }

                OutlinedButton(
                    onClick = {
                        if (verifyInput.length < 4) { errorMsg = "Enter current PIN to confirm" }
                        else if (!viewModel.verifyParentalPin(verifyInput)) { errorMsg = "Incorrect PIN" }
                        else { viewModel.clearParentalPin(); successMsg = null }
                    },
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                    modifier = Modifier
                        .weight(1f)
                        .tvButtonFocusable(interactionSource = removePinInteraction)
                ) { Text("Remove PIN", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (state.parentalEnabled && state.parentalPin.isNotEmpty() && state.availableLiveCategories.isNotEmpty()) {
        Text("Locked Live Categories (PIN to open)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
        Text(
            "Lock adult or kids groups. Live TV asks for your PIN when opening a locked category.",
            color = TextSecondary,
            fontSize = 12.sp
        )
        state.availableLiveCategories.take(40).forEach { cat ->
            val locked = cat.categoryId in state.lockedLiveCategories
            val rowInteraction = remember { MutableInteractionSource() }
            val rowFocused by rowInteraction.collectIsFocusedAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(if (rowFocused) 2.dp else 0.dp, if (rowFocused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = rowInteraction, indication = null) {
                        viewModel.toggleLockedLiveCategory(cat.categoryId)
                    }
                    .focusable(interactionSource = rowInteraction)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(cat.categoryName, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Switch(
                    checked = locked,
                    onCheckedChange = { viewModel.toggleLockedLiveCategory(cat.categoryId) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                )
            }
        }
    }

    if (state.parentalEnabled && state.parentalPin.isNotEmpty() && state.availableMovieCategories.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Locked Movie Categories (PIN to open)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
        state.availableMovieCategories.take(40).forEach { cat ->
            val locked = cat.categoryId in state.lockedMovieCategories
            val rowInteraction = remember { MutableInteractionSource() }
            val rowFocused by rowInteraction.collectIsFocusedAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(if (rowFocused) 2.dp else 0.dp, if (rowFocused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = rowInteraction, indication = null) {
                        viewModel.toggleLockedMovieCategory(cat.categoryId)
                    }
                    .focusable(interactionSource = rowInteraction)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(cat.categoryName, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Switch(
                    checked = locked,
                    onCheckedChange = { viewModel.toggleLockedMovieCategory(cat.categoryId) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                )
            }
        }
    }

    if (state.parentalEnabled && state.parentalPin.isNotEmpty() && state.availableSeriesCategories.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text("Locked Series Categories (PIN to open)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
        state.availableSeriesCategories.take(40).forEach { cat ->
            val locked = cat.categoryId in state.lockedSeriesCategories
            val rowInteraction = remember { MutableInteractionSource() }
            val rowFocused by rowInteraction.collectIsFocusedAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(if (rowFocused) 2.dp else 0.dp, if (rowFocused) Accent else Color.Transparent, RoundedCornerShape(8.dp))
                    .clickable(interactionSource = rowInteraction, indication = null) {
                        viewModel.toggleLockedSeriesCategory(cat.categoryId)
                    }
                    .focusable(interactionSource = rowInteraction)
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(cat.categoryName, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Switch(
                    checked = locked,
                    onCheckedChange = { viewModel.toggleLockedSeriesCategory(cat.categoryId) },
                    colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                )
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    Text("Profiles (scopes My Lists)", fontWeight = FontWeight.SemiBold, color = TextPrimary)
    Text("Switch profile to keep Kids / Default custom lists separate.", color = TextSecondary, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        state.profileIds.forEach { pid ->
            FilterChip(
                selected = pid == state.activeProfileId,
                onClick = { viewModel.setActiveProfile(pid) },
                label = { Text(pid) }
            )
        }
    }
    var newProfile by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = newProfile,
            onValueChange = { newProfile = it.take(24) },
            label = { Text("New profile") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(
            onClick = { viewModel.addProfile(newProfile); newProfile = "" },
            enabled = newProfile.isNotBlank()
        ) { Text("Add") }
    }
}

@Composable
private fun AboutTab() {
    // Never call native LibVLC from composition without catching Throwable —
    // UnsatisfiedLinkError is an Error (not Exception) and was killing the process
    // when scrolling to the About tab on Firestick.
    val vlcVersion = remember { "3.6.0" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Accent.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
                    .border(1.dp, Accent, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Tv, null, tint = Accent, modifier = Modifier.size(36.dp))
            }
            Column {
                Text("DYLANDOS IPTV ULTIMATE", color = Accent, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Text("Premium Android TV Player", color = TextSecondary, fontSize = 13.sp)
            }
        }

        HorizontalDivider(color = BorderDefault)

        AboutRow("App Version", BuildConfig.VERSION_NAME)
        AboutRow("Version Code", BuildConfig.VERSION_CODE.toString())
        AboutRow("Build Flavours", "firestick (armeabi-v7a) · premium (arm64-v8a)")
        AboutRow("Min SDK", "Android 5.0 Lollipop (API 21)")
        AboutRow("Target SDK", "Android 15 (API 35)")
        AboutRow("LibVLC Version", vlcVersion)
        AboutRow("DI Framework", "Hilt 2.57.2")
        AboutRow("UI Framework", "Jetpack Compose")
        AboutRow("Network", "OkHttp + Retrofit · Xtream Codes API")
        AboutRow("Database", "Room · dylandos_iptv_db")
        AboutRow("OTA Channel", "GitHub Gist auto-check (launch + resume)")

        HorizontalDivider(color = BorderDefault)

        Text(
            "Open Source Libraries",
            color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp
        )
        val libraries = listOf(
            "VideoLAN — libvlc-android",
            "Google — AndroidX Compose, Hilt, Room, Media3",
            "Square — OkHttp, Retrofit",
            "Coil — Image loading",
            "Timber — Logging"
        )
        libraries.forEach { lib ->
            Text("• $lib", color = TextSecondary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "© 2025 DYLANDOS. All rights reserved.",
            color = TextTertiary, fontSize = 11.sp
        )
    }
}

// ── Tab: Content Filter ───────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentFilterTab(
    state: SettingsUiState,
    viewModel: SettingsViewModel
) {
    val scope = rememberCoroutineScope()
    val filterSettings by viewModel.filterSettings.collectAsState()
    val save: (com.dylandos.iptv.ultimate.data.filter.FilterSettings) -> Unit = { updated ->
        scope.launch { viewModel.saveFilterSettings(updated) }
    }

    val presets = com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository.REGION_PRESETS

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp, horizontal = 2.dp)
    ) {
        item { SectionHeader("Provider Category Filter") }
        item {
            FilterStatusCard(
                enabled = filterSettings.isEnabled,
                mode = filterSettings.mode,
                allowCount = filterSettings.allowedPrefixes.size,
                blockCount = filterSettings.blockedPrefixes.size
            )
        }
        item {
            SwitchRow(
                label = "Enable category whitelist / blacklist",
                checked = filterSettings.isEnabled
            ) { enabled ->
                val base = if (enabled && filterSettings.allowedPrefixes.isEmpty()) {
                    filterSettings.copy(
                        isEnabled = true,
                        mode = com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST,
                        allowedPrefixes = com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository.DEFAULT_EN_PREFIXES,
                        blockedPrefixes = com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository.DEFAULT_CLEANUP_BLOCKS,
                        showUntagged = false
                    )
                } else {
                    filterSettings.copy(isEnabled = enabled)
                }
                save(base)
            }
        }

        item { SectionHeader("Recommended Presets") }
        items(presets, key = { it.id }) { preset ->
            FilterPresetCard(
                preset = preset,
                isSelected = filterSettings.isEnabled &&
                    filterSettings.mode == preset.mode &&
                    filterSettings.allowedPrefixes == preset.allowedPrefixes &&
                    filterSettings.blockedPrefixes == preset.blockedPrefixes,
                onApply = {
                    save(
                        filterSettings.copy(
                            isEnabled = true,
                            mode = preset.mode,
                            allowedPrefixes = preset.allowedPrefixes,
                            blockedPrefixes = preset.blockedPrefixes,
                            showUntagged = preset.showUntagged
                        )
                    )
                }
            )
        }

        item { SectionHeader("Mode") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                FilterModePill(
                    title = "Whitelist",
                    subtitle = "Only show selected regions",
                    selected = filterSettings.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST,
                    modifier = Modifier.weight(1f)
                ) {
                    save(filterSettings.copy(mode = com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST))
                }
                FilterModePill(
                    title = "Blacklist",
                    subtitle = "Hide selected clutter",
                    selected = filterSettings.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.BLACKLIST,
                    modifier = Modifier.weight(1f)
                ) {
                    save(filterSettings.copy(mode = com.dylandos.iptv.ultimate.data.filter.FilterMode.BLACKLIST))
                }
            }
        }

        item { SectionHeader("Advanced Tokens") }
        item {
            Text(
                "Omega IPTV, Trex IPTV and Strong IPTV commonly tag categories with EN, NA, US, USA, English, Canada and similar labels. Use whitelist for a clean home-area list; use blacklist when you only want to hide clutter.",
                color = TextTertiary,
                style = MaterialTheme.typography.bodySmall
            )
        }
        item {
            val editingWhitelist = filterSettings.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST
            PrefixChipGrid(
                selectedPrefixes = if (editingWhitelist) filterSettings.allowedPrefixes else filterSettings.blockedPrefixes,
                editingWhitelist = editingWhitelist,
                onToggle = { prefix, nowSelected ->
                    if (editingWhitelist) {
                        val updated = if (nowSelected) filterSettings.allowedPrefixes + prefix else filterSettings.allowedPrefixes - prefix
                        save(filterSettings.copy(allowedPrefixes = updated))
                    } else {
                        val updated = if (nowSelected) filterSettings.blockedPrefixes + prefix else filterSettings.blockedPrefixes - prefix
                        save(filterSettings.copy(blockedPrefixes = updated))
                    }
                }
            )
        }
        item {
            SwitchRow(
                label = "Show untagged categories",
                checked = filterSettings.showUntagged
            ) { value ->
                save(filterSettings.copy(showUntagged = value))
            }
        }
        item {
            Text(
                if (filterSettings.showUntagged) {
                    "Untagged provider groups stay visible. Turn this off for the strictest English / North America view."
                } else {
                    "Strict mode is active: categories without a recognized region/language tag are hidden in whitelist mode."
                },
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PrefixChipGrid(
    selectedPrefixes: Set<String>,
    editingWhitelist: Boolean,
    onToggle: (String, Boolean) -> Unit
) {
    val allPrefixes = listOf(
        "EN", "ENG", "ENGLISH", "NA", "NORTHAMERICA", "US", "USA", "UNITEDSTATES",
        "CA", "CANADA", "UK", "GB", "AU", "NZ", "ES", "SPANISH", "LATINO", "MX",
        "PT", "BR", "FR", "DE", "IT", "NL", "AR", "MENA", "IN", "HINDI",
        "XXX", "ADULT", "TEST", "BACKUP", "VOD", "MOVIES", "SERIES", "RADIO", "MUSIC"
    )
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        allPrefixes.forEach { prefix ->
            val selected = prefix in selectedPrefixes
            Box(
                modifier = Modifier
                    .dylandosFocusable(onClick = { onToggle(prefix, !selected) })
                    .background(
                        if (selected) AccentSurface else BgSurface2,
                        RoundedCornerShape(16.dp)
                    )
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) Accent else Color.Transparent,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(
                    prefix,
                    color = if (selected) {
                        if (editingWhitelist) Accent else Color(0xFFFFB74D)
                    } else TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun FilterStatusCard(
    enabled: Boolean,
    mode: com.dylandos.iptv.ultimate.data.filter.FilterMode,
    allowCount: Int,
    blockCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgElevated, RoundedCornerShape(8.dp))
            .border(1.dp, BorderDefault, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = if (enabled) Icons.Default.FilterAlt else Icons.Default.FilterList,
            contentDescription = null,
            tint = if (enabled) Accent else TextTertiary,
            modifier = Modifier.size(24.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = if (enabled) "Filtering active" else "Filtering off",
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST) {
                    "Whitelist: $allowCount show tokens, $blockCount cleanup tokens saved"
                } else {
                    "Blacklist: $blockCount hidden tokens, $allowCount whitelist tokens saved"
                },
                color = TextTertiary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun FilterPresetCard(
    preset: com.dylandos.iptv.ultimate.data.filter.FilterPreset,
    isSelected: Boolean,
    onApply: () -> Unit
) {
    val borderColor = if (isSelected) Accent else BorderDefault
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .dylandosFocusable(onClick = onApply)
            .background(if (isSelected) AccentSurface else BgSurface2, RoundedCornerShape(8.dp))
            .border(1.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                imageVector = if (isSelected) Icons.Default.CheckCircle else Icons.Default.Public,
                contentDescription = null,
                tint = if (isSelected) Accent else TextSecondary,
                modifier = Modifier.size(18.dp)
            )
            Text(preset.title, color = TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(
                if (preset.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST) "SHOW" else "HIDE",
                color = if (preset.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST) Accent else Color(0xFFFFB74D),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Text(preset.description, color = TextTertiary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text = if (preset.mode == com.dylandos.iptv.ultimate.data.filter.FilterMode.WHITELIST) {
                "Shows: ${preset.allowedPrefixes.take(9).joinToString(", ")}"
            } else {
                "Hides: ${preset.blockedPrefixes.take(9).joinToString(", ")}"
            },
            color = TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FilterModePill(
    title: String,
    subtitle: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Column(
        modifier = modifier
            .dylandosFocusable(onClick = onClick)
            .background(if (selected) AccentSurface else BgSurface2, RoundedCornerShape(8.dp))
            .border(1.dp, if (selected) Accent else BorderDefault, RoundedCornerShape(8.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, color = if (selected) Accent else TextPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(subtitle, color = TextTertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Shared Components ──────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    Text(text, color = Accent, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) AccentSurfaceHover else BgSurface,
                RoundedCornerShape(8.dp)
            )
            .then(if (isFocused) Modifier.border(2.dp, AccentBright, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = if (isFocused) TextPrimary else TextPrimary, modifier = Modifier.weight(1f))
        if (isFocused) {
            Text("OK", color = Accent.copy(alpha = 0.7f), fontSize = 9.sp,
                modifier = Modifier.padding(end = 6.dp))
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = BgSurface3
            ),
            modifier = Modifier
                .focusable(interactionSource = interactionSource)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> { onChange(!checked); true }
                        else -> false
                    }
                }
        )
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onToggle: (Boolean) -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) AccentSurfaceHover else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(if (isFocused) Modifier.border(2.dp, AccentBright, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = value,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = Accent,
                uncheckedThumbColor = TextTertiary,
                uncheckedTrackColor = BgSurface3
            ),
            modifier = Modifier
                .focusable(interactionSource = interactionSource)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> { onToggle(!value); true }
                        else -> false
                    }
                }
        )
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    val display = if (value == value.toLong().toFloat()) "${value.toLong()}" else "%.1f".format(value)
    val step = if (steps > 0) (range.endInclusive - range.start) / (steps + 1) else 1f
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) AccentSurfaceHover else Color.Transparent,
                RoundedCornerShape(8.dp)
            )
            .then(if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .focusable(interactionSource = interactionSource)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        onChange((value - step).coerceAtLeast(range.start)); true
                    }
                    Key.DirectionRight -> {
                        onChange((value + step).coerceAtMost(range.endInclusive)); true
                    }
                    else -> false
                }
            }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = TextPrimary, fontSize = 13.sp, modifier = Modifier.weight(1f))
            if (isFocused) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, null, tint = Accent, modifier = Modifier.size(14.dp))
                    Text(display, color = AccentBright, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                    Icon(Icons.Default.ChevronRight, null, tint = Accent, modifier = Modifier.size(14.dp))
                }
            } else {
                Text(display, color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(BgSurface3, RoundedCornerShape(3.dp))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(if (isFocused) AccentBright else Accent, RoundedCornerShape(3.dp))
            )
        }
        if (isFocused) {
            Spacer(Modifier.height(4.dp))
            Text(
                "◄ LEFT / RIGHT to adjust ►",
                color = Accent.copy(alpha = 0.8f),
                fontSize = 9.sp
            )
        }
    }
}

@Composable
private fun SegmentedPick(options: List<String>, selected: String, labels: List<String>, onSelect: (String) -> Unit) {
    // One FocusRequester per option so LEFT/RIGHT D-pad moves between them explicitly.
    // This prevents events from bubbling to the root Box and switching the settings tab.
    val focusRequesters = remember(options.size) { List(options.size) { FocusRequester() } }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEachIndexed { i, opt ->
            val interactionSource = remember { MutableInteractionSource() }
            val isFocused by interactionSource.collectIsFocusedAsState()
            val isSelected = opt == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(
                        when {
                            isSelected && isFocused -> AccentSurfaceHover
                            isSelected -> AccentSurface
                            isFocused  -> AccentSurfaceHover
                            else       -> BgSurface2
                        },
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        width = if (isFocused) 2.5.dp else 1.dp,
                        color = when {
                            isFocused  -> AccentBright
                            isSelected -> Accent
                            else       -> BorderDefault
                        },
                        shape = RoundedCornerShape(8.dp)
                    )
                    .focusRequester(focusRequesters[i])
                    .focusable(interactionSource = interactionSource)
                    .clickable(interactionSource = interactionSource, indication = null) { onSelect(opt) }
                    .onKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft -> {
                                if (i > 0) runCatching { focusRequesters[i - 1].requestFocus() }
                                true
                            }
                            Key.DirectionRight -> {
                                if (i < options.size - 1) runCatching { focusRequesters[i + 1].requestFocus() }
                                true
                            }
                            Key.DirectionCenter, Key.Enter -> { onSelect(opt); true }
                            else -> false
                        }
                    }
            ) {
                Text(
                    text = labels.getOrElse(i) { opt },
                    color = when {
                        isFocused  -> AccentBright
                        isSelected -> Accent
                        else       -> TextSecondary
                    },
                    fontWeight = if (isSelected || isFocused) FontWeight.Bold else FontWeight.Normal,
                    fontSize = 11.sp,
                    maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun InfoCard(title: String, body: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = BgSurface2),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Info, null, tint = Accent, modifier = Modifier.size(16.dp))
                Text(title, color = Accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            Text(body, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun ConnectedBadge(serverUrl: String, username: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x1400FF88), RoundedCornerShape(10.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF00CC66))
        Column {
            Text("Connected", fontWeight = FontWeight.Bold, color = Color(0xFF00CC66))
            Text(serverUrl, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            if (username.isNotEmpty())
                Text("User: $username", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
    }
}

@Composable
private fun StatusRow(msg: String, isError: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) Color(0x33FF4444) else Color(0x1400FF88),
                RoundedCornerShape(8.dp)
            )
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            if (isError) Icons.Default.Error else Icons.Default.CheckCircle,
            null,
            tint = if (isError) Color(0xFFFF6B6B) else Color(0xFF00CC66),
            modifier = Modifier.size(18.dp)
        )
        Text(msg, color = if (isError) Color(0xFFFF6B6B) else Color(0xFF00CC66),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AboutRow(key: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(key, color = TextSecondary, fontSize = 12.sp)
        Text(value, color = TextPrimary, fontWeight = FontWeight.Medium, fontSize = 12.sp)
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun SettingsIconBtn(
    onClick: () -> Unit,
    label: String,
    focusable: Boolean = true,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .then(
                if (focusable) {
                    Modifier
                        .focusable(interactionSource = interactionSource)
                        .then(if (isFocused) Modifier.border(2.dp, Accent, RoundedCornerShape(8.dp)) else Modifier)
                } else {
                    // IconButton is focusable by default — must disable or D-pad Right/Up
                    // from the last Settings tab lands on Back and pops (looks like app close).
                    Modifier.focusProperties { canFocus = false }
                }
            )
    ) { content() }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester? = null,
    imeAction: ImeAction = ImeAction.Next,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = 1,
    onDone: (() -> Unit)? = null
) {
    var isFocused by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder, color = TextSecondary.copy(alpha = 0.4f)) },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = maxLines,
        visualTransformation = if (isPassword && !showPassword) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = if (isPassword) ({
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    null, tint = if (isFocused) Accent else TextSecondary
                )
            }
        }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions = KeyboardActions(
            onNext = { nextFocusRequester?.requestFocus() },
            onDone = { onDone?.invoke() }
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Accent, unfocusedBorderColor = BorderDefault,
            focusedLabelColor = Accent, unfocusedLabelColor = TextSecondary,
            cursorColor = Accent, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
    )
}

@Composable
private fun SettingsButton(
    text: String,
    focusRequester: FocusRequester,
    containerColor: Color,
    contentColor: Color,
    isLoading: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Button(
        onClick = onClick,
        enabled = !isLoading,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .then(if (isFocused) Modifier.border(2.dp, AccentBright, RoundedCornerShape(10.dp)) else Modifier),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
        shape = RoundedCornerShape(10.dp)
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = contentColor, strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        } else {
            leadingIcon?.invoke()
            if (leadingIcon != null) Spacer(Modifier.width(8.dp))
        }
        Text(text, fontWeight = FontWeight.Bold)
    }
}
