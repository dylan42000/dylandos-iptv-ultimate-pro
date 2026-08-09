package com.dylandos.iptv.ultimate.ui.screens.splash

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.R
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import kotlinx.coroutines.delay

private const val PREF_INTRO_SHOWN = "intro_video_shown"
private const val PREFS_NAME       = "dylandos_prefs"

/**
 * DYLANDOS IPTV ULTIMATE — Intro Splash Screen
 *
 * Plays the branded intro video (res/raw/intro_video.mp4) ONLY on the very first
 * cold launch after install. Subsequent launches skip directly to LoginScreen.
 *
 * • Any D-pad key press or tap skips the video immediately
 * • Auto-navigates when the video finishes
 * • "SKIP ▶" hint appears after 1.5 s
 * • Black background so there's no flash before the video starts
 *
 * TO ADD YOUR INTRO VIDEO:
 *   1. Save your .mp4 file to:
 *      android/app/src/main/res/raw/intro_video.mp4
 *   2. Rebuild — the splash will appear automatically on the FIRST launch only.
 */
@Composable
fun SplashScreen(navController: NavController) {
    val context = LocalContext.current

    // Connection pre-warm (background — no UI dependency)
    @Suppress("UNUSED_VARIABLE")
    val splashViewModel: SplashViewModel = hiltViewModel()

    // Check if this is the very first launch
    val isFirstLaunch = remember {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        !prefs.getBoolean(PREF_INTRO_SHOWN, false)
    }

    var showSkip by remember { mutableStateOf(false) }
    var hasNavigated by remember { mutableStateOf(false) }

    fun navigateToApp() {
        if (hasNavigated) return
        hasNavigated = true
        // Mark intro as shown so it never plays again
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(PREF_INTRO_SHOWN, true).apply()
        navController.navigate(Screen.Login.route) {
            popUpTo(Screen.Splash.route) { inclusive = true }
        }
    }

    // If NOT first launch → skip directly, no video
    if (!isFirstLaunch) {
        LaunchedEffect(Unit) { navigateToApp() }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        return
    }

    // FIRST LAUNCH — build and play intro video
    val exoPlayer = remember {
        buildIntroPlayer(context) { navigateToApp() }
    }

    // A release build may intentionally ship without the optional branded intro
    // asset.  Do not navigate from the remember block above: that block runs
    // while Compose is composing, and mutating NavController at that point can
    // crash the Activity on Fire OS.  Navigate after the first composition
    // instead.
    LaunchedEffect(exoPlayer) {
        if (exoPlayer == null) navigateToApp()
    }

    LaunchedEffect(Unit) {
        delay(1500)
        showSkip = true
    }

    // Never hold a low-end Fire TV on a decoder that is stuck buffering.
    LaunchedEffect(Unit) {
        delay(12_000)
        navigateToApp()
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer?.release() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    navigateToApp()
                    true
                } else false
            }
    ) {
        if (exoPlayer != null) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setOnClickListener { navigateToApp() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
        }

        AnimatedVisibility(
            visible = showSkip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
        ) {
            Surface(
                color = Color.White.copy(alpha = 0.18f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = { navigateToApp() }
                )
            ) {
                Text(
                    text = "SKIP  ▶",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }
    }
}

private fun buildIntroPlayer(context: Context, onEnd: () -> Unit): ExoPlayer? {
    // The video is optional.  Lookup by name keeps builds valid when
    // res/raw/intro_video.mp4 is not included, while automatically using it
    // when a branded intro is added to a future release.
    val introResourceId = context.resources.getIdentifier(
        "intro_video",
        "raw",
        context.packageName
    )
    if (introResourceId == 0) return null

    val introUri = Uri.parse("android.resource://${context.packageName}/$introResourceId")

    return try {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(introUri))
            repeatMode = Player.REPEAT_MODE_OFF
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) onEnd()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    onEnd()   // File missing, corrupt, or unsupported codec — skip gracefully
                }
            })
            prepare()
            playWhenReady = true
        }
    } catch (_: Exception) {
        // ExoPlayer init failed (e.g. missing codec on emulator) — skip the intro
        null
    }
}


