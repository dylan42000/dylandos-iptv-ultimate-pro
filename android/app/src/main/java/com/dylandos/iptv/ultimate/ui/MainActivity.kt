package com.dylandos.iptv.ultimate.ui

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
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
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.navigation.DylandosNavGraph
import com.dylandos.iptv.ultimate.ui.player.PipController
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DylandosApp()
                }
            }
        }
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
fun DylandosApp() {
    val navController = rememberNavController()
    val updateViewModel: UpdateViewModel = hiltViewModel()
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

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

    // What's New overlay — shown before update dialog so user sees changelog first
    if (showWhatsNew) {
        WhatsNewDialog(onDismiss = { showWhatsNew = false })
    }

    // OTA update dialog — overlay on top of whatever screen is showing
    UpdateDialog(viewModel = updateViewModel)
}
