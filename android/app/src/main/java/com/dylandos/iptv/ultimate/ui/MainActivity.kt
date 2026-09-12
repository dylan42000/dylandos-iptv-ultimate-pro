package com.dylandos.iptv.ultimate.ui

import android.app.PictureInPictureParams
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.navigation.DylandosNavGraph
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.player.PipController
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrViewModel
import com.dylandos.iptv.ultimate.ui.theme.AppTheme
import com.dylandos.iptv.ultimate.ui.theme.DylandosTheme
import com.dylandos.iptv.ultimate.ui.theme.appThemeFromName
import com.dylandos.iptv.ultimate.ui.update.UpdateDialog
import com.dylandos.iptv.ultimate.ui.update.UpdateViewModel
import com.dylandos.iptv.ultimate.ui.update.WhatsNewDialog
import com.dylandos.iptv.ultimate.ui.update.shouldShowWhatsNew
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.map
import timber.log.Timber

/**
 * DYLANDOS IPTV ULTIMATE - Main Activity
 *
 * Single activity hosting Jetpack Compose UI.
 * Reads the selected app theme from DataStore and applies it to DylandosTheme
 * so the entire app reflects the user's choice immediately on every launch.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_DVR = "com.dylandos.iptv.ultimate.action.OPEN_DVR"
        const val ACTION_OPEN_GUIDE = "com.dylandos.iptv.ultimate.action.OPEN_GUIDE"
    }

    private var openDvrRequest by mutableStateOf(false)
    private var openGuideRequest by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDvrRequest = intent?.action == ACTION_OPEN_DVR
        openGuideRequest = intent?.action == ACTION_OPEN_GUIDE

        Timber.d("MainActivity created")

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val themeNameFlow = remember {
                dataStore.data.map { prefs -> prefs[SettingsViewModel.KEY_APP_THEME] }
            }
            val accentHexFlow = remember {
                dataStore.data.map { prefs -> prefs[SettingsViewModel.KEY_ACCENT_COLOR] }
            }

            // Observe theme preference from DataStore — updates instantly when changed in Settings
            val themeName by themeNameFlow.collectAsState(initial = null)
            val currentTheme = remember(themeName) { appThemeFromName(themeName) }

            val accentHex by accentHexFlow.collectAsState(initial = null)

            DylandosTheme(appTheme = currentTheme, customAccentHex = accentHex) {
                Surface(
                    // Fire TV has no meaningful system bars, while phones do.  Keeping
                    // Compose content inside the safe drawing area prevents guide dates,
                    // touch targets, and player controls from being hidden under a phone's
                    // status/navigation bars without changing the Fire TV layout.
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DylandosApp(
                        openDvrRequest = openDvrRequest,
                        openGuideRequest = openGuideRequest,
                        onGuideRequestConsumed = { openGuideRequest = false },
                        onDvrRequestConsumed = { openDvrRequest = false }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ACTION_OPEN_DVR) openDvrRequest = true
        if (intent.action == ACTION_OPEN_GUIDE) openGuideRequest = true
    }

    /**
     * Called when the user presses the Home button.
     * If PlayerScreen is active (it sets PipController.activate()), the LibVLC
     * surface stays alive and the player shrinks to PiP window.
     * Requires android:supportsPictureInPicture="true" on the activity in the manifest.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (PipController.shouldEnterPip.value &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }
}

@Composable
fun DylandosApp(
    openGuideRequest: Boolean = false,
    onGuideRequestConsumed: () -> Unit = {},
    openDvrRequest: Boolean = false,
    onDvrRequestConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val dvrViewModel: DvrViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = context as? android.app.Activity
    val dvrState by dvrViewModel.uiState.collectAsState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val onMainMenu = backStackEntry?.destination?.route == "home"
    var showExitForRecordingDialog by remember { mutableStateOf(false) }
    LaunchedEffect(openGuideRequest) {
        if (openGuideRequest) {
            navController.navigate(Screen.Guide.route) { launchSingleTop = true }
            onGuideRequestConsumed()
        }
    }

    LaunchedEffect(openDvrRequest) {
        if (openDvrRequest) {
            navController.navigate(Screen.Dvr.route) { launchSingleTop = true }
            onDvrRequestConsumed()
        }
    }

    // Fire TV's Back button should never make a recording look like it will be lost.
    // Moving the task to the background leaves the foreground RecordingService running,
    // with the small ongoing DVR notification as the low-resource control surface.
    BackHandler(enabled = onMainMenu) {
        showExitForRecordingDialog = true
    }

    // Fire the update check once when the app starts
    LaunchedEffect(Unit) {
        updateViewModel.checkOnLaunch(force = true)
    }

    // Re-check on resume (cooldown-protected inside UpdateViewModel)
    DisposableEffect(lifecycleOwner, updateViewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                updateViewModel.checkOnLaunch()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // What's New: show once on first launch after each OTA update
    var showWhatsNew by remember { mutableStateOf(shouldShowWhatsNew(context)) }

    DylandosNavGraph(navController = navController)

    if (showExitForRecordingDialog) {
        val activeCount = dvrState.activeRecordings.size
        val scheduledCount = dvrState.scheduledRecordings.size
        AlertDialog(
            onDismissRequest = { showExitForRecordingDialog = false },
            title = { Text(if (activeCount > 0) "DVR is recording" else "Exit DYLANDOS IPTV?") },
            text = {
                Text(
                    when {
                        activeCount > 0 -> "$activeCount recording${if (activeCount == 1) " is" else "s are"} active. Minimize the app to keep the low-resource DVR service running. You can return from the ongoing DVR notification."
                        scheduledCount > 0 -> "$scheduledCount future recording${if (scheduledCount == 1) " is" else "s are"} scheduled. They use Android's scheduler and do not require the full app screen to remain open."
                        else -> "Close the app, or cancel to keep browsing."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExitForRecordingDialog = false
                    activity?.moveTaskToBack(true)
                }) {
                    Text(if (activeCount > 0) "Minimize & keep DVR" else "Minimize")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitForRecordingDialog = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        showExitForRecordingDialog = false
                        activity?.finishAndRemoveTask()
                    }) { Text("Close UI") }
                }
            }
        )
    }

    // What's New overlay — shown before update dialog so user sees changelog first
    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    // OTA update dialog — overlay on top of whatever screen is showing
    UpdateDialog(viewModel = updateViewModel)
}
