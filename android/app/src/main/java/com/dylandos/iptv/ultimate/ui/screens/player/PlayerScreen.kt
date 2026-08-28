package com.dylandos.iptv.ultimate.ui.screens.player

import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.ViewGroup
import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.*
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.style.TextOverflow
import com.dylandos.iptv.ultimate.ui.navigation.Screen
import com.dylandos.iptv.ultimate.ui.navigation.navigateSafe
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.dylandos.iptv.ultimate.BuildConfig
import com.dylandos.iptv.ultimate.data.util.StorageDetector
import com.dylandos.iptv.ultimate.player.MpvEngineFactory
import com.dylandos.iptv.ultimate.player.engine.LiveBufferController
import com.dylandos.iptv.ultimate.player.engine.LiveBufferEvent
import com.dylandos.iptv.ultimate.player.timeshift.TimeshiftRingMath
import com.dylandos.iptv.ultimate.player.MpvEvent
import com.dylandos.iptv.ultimate.player.MpvSurface
import com.dylandos.iptv.ultimate.player.subtitle.SubtitleLanguage
import com.dylandos.iptv.ultimate.service.PlayerRecordingBridge
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsUiState
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.navigation.popBackStackSafe
import com.dylandos.iptv.ultimate.ui.navigation.popBackStackSafeDebounced
import com.dylandos.iptv.ultimate.ui.player.PipController
import com.dylandos.iptv.ultimate.ui.theme.*
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import timber.log.Timber

/**
 * DYLANDOS IPTV ULTIMATE — Player Screen
 *
 * Engines:
 * - VOD / Series: MPV primary → LibVLC backup → Media3 last resort
 * - Live / DVR / catch-up: LibVLC primary → Media3 backup (USB timeshift = Media3)
 *
 * Controls bar (shown on any D-pad press, auto-hides after 5 s):
 *   Live TV :  ◀◀  ▶/⏸  ▶▶  [CC]  [Audio]  [REC]  [⛶]  [✕]
 *   VOD     :  ◀◀  ▶/⏸  ▶▶  [Sub] [Audio]  [REC]  [⛶]  [✕]
 *
 * D-pad:
 *   LEFT / RIGHT  → move focus between control buttons
 *   OK            → activate focused button
 *   BACK          → close player
 *   UP (live)     → zap up (placeholder — wired via intent extras)
 *   DOWN (live)   → zap down
 */

// ── LibVLC factory functions ──────────────────────────────────────────────────

/**
 * Converts a hex color string like "#FFFFFF" to a LibVLC freetype ARGB integer
 * (0xAARRGGBB format with full alpha). Returns 0xFFFFFFFF on any parse error.
 */
private fun hexToVlcColor(hex: String): String {
    return try {
        val raw = android.graphics.Color.parseColor(hex)
        // LibVLC freetype color is 0xRRGGBB (no alpha in --freetype-color)
        "0x" + String.format("%06X", raw and 0xFFFFFF)
    } catch (_: Exception) { "0xFFFFFF" }
}

private fun createLibVlcWithFallback(
    ctx: android.content.Context,
    label: String,
    preferredOptions: ArrayList<String>,
    fallbackOptions: ArrayList<String>
): LibVLC {
    return runCatching { LibVLC(ctx, preferredOptions) }
        .onFailure { Timber.e(it, "LibVLC $label init failed with tuned options; retrying safe defaults") }
        .getOrElse { LibVLC(ctx, fallbackOptions) }
}

private fun makeLiveLibVLC(
    ctx: android.content.Context,
    settingsState: SettingsUiState,
    subtitleSizeSp: Int = 24,
    subtitleColorHex: String = "#FFFFFF",
    subtitleOutlineColorHex: String = "#000000",
    subtitleBgOpacity: Int = 60
): LibVLC {
    // Ceiling raised to 4000 ms (v5.0): the Adaptive Buffer Controller can push the
    // cache higher than the old 2000 ms clamp when the network is jittery, which is
    // the primary cause of "channels periodically lag" on WiFi Firesticks.
    val liveCacheMs = settingsState.liveNetworkCacheMs.coerceIn(300, 4_000)
    val audioOutput = settingsState.vlcAudioOutput
        .takeIf { it == "android_audiotrack" || it == "aaudio" || it == "opensles" || it == "auto" }
        ?: "android_audiotrack"
    val resampler = settingsState.vlcAudioResampler
        .takeIf { it == "ugly" || it == "speex" || it == "soxr" }
        ?: "ugly"
    val chroma = settingsState.vlcChromaFormat
        .takeIf { it == "RV16" || it == "RV32" || it == "RGBA" }
    val subtitleEncoding = settingsState.vlcSubtitleEncoding
        .takeIf { it.isNotBlank() && it != "auto" }

    val options = arrayListOf(
        "--network-caching=$liveCacheMs",
        "--live-caching=$liveCacheMs",
        "--file-caching=250",
        "--clock-jitter=${settingsState.vlcClockJitterMs.coerceIn(0, 200)}",
        "--clock-synchro=0",
        "--http-reconnect",
        // v5.0 lag fix: RTSP over TCP eliminates UDP packet loss on 2.4 GHz WiFi
        // (silent freezes); --http-continuous keeps flaky HTTP/TS servers from
        // dropping idle connections and triggering reconnect storms.
        "--rtsp-tcp",
        "--http-continuous",
        "--aout=$audioOutput",
        "--audio-resampler=$resampler",
        "--codec=mediacodec_ndk,mediacodec_jni,iomx,all",
        "--mediacodec-dr",                 // Direct rendering
        "--avcodec-fast",
        "--no-video-title-show",
        if (settingsState.vlcDropLateFrames) "--drop-late-frames" else "--no-drop-late-frames",
        if (settingsState.vlcSkipFrames) "--skip-frames" else "--no-skip-frames",
        // ── Subtitle rendering settings ───────────────────────────────────────
        "--freetype-fontsize=$subtitleSizeSp",
        "--freetype-color=${hexToVlcColor(subtitleColorHex)}",
        "--freetype-outline-color=${hexToVlcColor(subtitleOutlineColorHex)}",
        "--freetype-outline-thickness=2",
        "--freetype-background-opacity=${(subtitleBgOpacity * 255 / 100).coerceIn(0, 255)}"
    )
    chroma?.let { options.add("--android-display-chroma=$it") }
    subtitleEncoding?.let { options.add("--sub-text-encoding=$it") }
    if (settingsState.vlcNetworkMtu > 0) options.add("--mtu=${settingsState.vlcNetworkMtu.coerceIn(500, 9_000)}")
    // v5.0: verbose LibVLC logging only in debug builds — logcat IO on slow flash
    // storage causes visible frame drops on 2 GB sticks. Release is always quiet.
    options.add(if (settingsState.vlcVerboseLog && BuildConfig.DEBUG) "--verbose=2" else "--no-stats")
    return createLibVlcWithFallback(
        ctx = ctx,
        label = "live",
        preferredOptions = options,
        fallbackOptions = arrayListOf(
            "--network-caching=$liveCacheMs",
            "--live-caching=$liveCacheMs",
            "--http-reconnect",
            "--aout=$audioOutput",
            "--audio-resampler=$resampler",
            "--drop-late-frames",
            "--skip-frames",
            "--no-stats",
        )
    )
}

private fun resolveTimeshiftDirectory(ctx: android.content.Context, persistedPath: String?): File? {
    // Live timeshift writes continuously; keep it on the removable USB used by
    // DVR and never consume scarce Firestick internal flash. The persisted path
    // remains available for DVR, but USB is the only valid timeshift target.
    val root = StorageDetector.findPreferredTimeshiftBasePath(ctx, persistedPath) ?: return null
    return File(root, "live_timeshift").also { dir ->
        if (!dir.exists() && !dir.mkdirs()) return null
        if (!dir.canWrite()) return null
        val probe = File(dir, ".dylandos_timeshift_probe")
        val writable = runCatching {
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
        if (!writable) return null
        val freeBytes = maxOf(dir.usableSpace, dir.freeSpace)
        // The cache ring itself has a 512 MiB floor.  Admitting a 256 MiB USB
        // volume made SimpleCache fill the drive and report a misleading playback
        // error with a Retry button instead of falling back to normal Live TV.
        val requiredFreeBytes = TimeshiftRingMath.MIN_RING_BYTES + 128L * 1024L * 1024L
        if (freeBytes in 1 until requiredFreeBytes) {
            Timber.w(
                "Timeshift disabled: only ${freeBytes / (1024 * 1024)}MB free on " +
                    "${dir.absolutePath}; requires ${requiredFreeBytes / (1024 * 1024)}MB"
            )
            return null
        }
        if (freeBytes <= 0L) {
            Timber.w("Timeshift storage stats unknown for ${dir.absolutePath}; writable probe passed")
        }
        dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 6 * 3_600_000L }
            ?.forEach { runCatching { it.delete() } }
    }
}

private fun parseArgb(hex: String, alphaPct: Int = 100, fallback: Int = android.graphics.Color.WHITE): Int {
    return try {
        val rgb = android.graphics.Color.parseColor(hex) and 0x00FFFFFF
        val alpha = (alphaPct.coerceIn(0, 100) * 255 / 100) shl 24
        alpha or rgb
    } catch (_: Exception) {
        fallback
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun applyMedia3SubtitleStyle(playerView: PlayerView, settingsState: com.dylandos.iptv.ultimate.ui.screens.settings.SettingsUiState) {
    val foreground = parseArgb(settingsState.subtitleColorHex)
    val background = parseArgb("#000000", settingsState.subtitleBgOpacity, android.graphics.Color.TRANSPARENT)
    val edge = parseArgb(settingsState.subtitleOutlineColorHex, fallback = android.graphics.Color.BLACK)
    playerView.subtitleView?.setStyle(
        CaptionStyleCompat(
            foreground,
            background,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            edge,
            null
        )
    )
    playerView.subtitleView?.setFixedTextSize(
        android.util.TypedValue.COMPLEX_UNIT_SP,
        settingsState.subtitleSizeSp.coerceIn(10, 72).toFloat()
    )
}

private fun makeVodLibVLC(
    ctx: android.content.Context,
    subtitleSizeSp: Int = 24,
    subtitleColorHex: String = "#FFFFFF",
    subtitleOutlineColorHex: String = "#000000",
    subtitleBgOpacity: Int = 60
): LibVLC {
    val options = arrayListOf(
            "--network-caching=3000",
            "--file-caching=3000",
            "--disc-caching=3000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--http-reconnect",            // auto-reconnect stalled HLS segments without user intervention
            "--aout=android_audiotrack",         // AudioTrack is more reliable than OpenSL ES on Fire TV
            "--audio-resampler=soxr",            // High-quality resampler for VOD files
            "--codec=mediacodec_ndk,iomx,all",   // Prefer hardware decoder
            "--mediacodec-dr",                   // Direct rendering
            "--no-mediacodec-adaptive-playback", // Prevent resolution flicker
            "--codec-threads=2",                 // Limit decode threads
            "--no-stats",
            // ── Subtitle rendering settings ───────────────────────────────────────
            "--freetype-fontsize=$subtitleSizeSp",
            "--freetype-color=${hexToVlcColor(subtitleColorHex)}",
            "--freetype-outline-color=${hexToVlcColor(subtitleOutlineColorHex)}",
            "--freetype-outline-thickness=2",
            "--freetype-background-opacity=${(subtitleBgOpacity * 255 / 100).coerceIn(0, 255)}"
        )
    return createLibVlcWithFallback(
        ctx = ctx,
        label = "vod",
        preferredOptions = options,
        fallbackOptions = arrayListOf(
            "--network-caching=3000",
            "--file-caching=3000",
            "--http-reconnect",
            "--aout=android_audiotrack",
            "--no-stats",
        )
    )
}

private fun buildMedia(
    libVLC: LibVLC,
    url: String,
    isLive: Boolean,
    liveCacheMs: Int = 800,
    dropLateFrames: Boolean = true,
    skipFrames: Boolean = true,
    timeshiftEnabled: Boolean = false,
    timeshiftPath: String? = null
): Media =
    Media(libVLC, Uri.parse(url)).also { media ->
        if (isLive) {
            // Live TV must stay real-time on Firestick. If the decoder falls behind,
            // skip stale frames instead of drifting into slow-motion playback.
            // v5.0: allow the adaptive controller's raised cache through (ceiling 4000 ms).
            val boundedCacheMs = liveCacheMs.coerceIn(300, 4_000)
            media.addOption(":network-caching=$boundedCacheMs")
            media.addOption(":live-caching=$boundedCacheMs")
            media.addOption(":clock-jitter=0")
            media.addOption(":clock-synchro=0")
            media.addOption(if (dropLateFrames) ":drop-late-frames" else ":no-drop-late-frames")
            media.addOption(if (skipFrames) ":skip-frames" else ":no-skip-frames")
            media.addOption(":sub-track=-1")
            if (timeshiftEnabled && !timeshiftPath.isNullOrBlank()) {
                media.addOption(":input-timeshift-path=$timeshiftPath")
                media.addOption(":input-timeshift-granularity=${4 * 1024 * 1024}")
            }
        } else {
            media.addOption(":network-caching=3000")
            media.addOption(":file-caching=3000")
            media.addOption(":no-sub-autodetect-file")
            // Do not force :sub-track=-1 on VOD — softsubs must remain selectable/auto-enabled.
        }
    }

// ── Screen ────────────────────────────────────────────────────────────────────

/**
 * Resolves a DVR file URI to a LibVLC-playable URL string.
 *
 * SAF content:// → opens a fresh PFD and returns "file:///proc/self/fd/{n}"
 *   LibVLC Android opens /proc/self/fd/N as a regular file via the kernel symlink.
 *   "fd://{n}" is NOT supported by LibVLC on Android — it crashes immediately.
 *
 * Absolute path (no scheme) → prefix with "file://" so LibVLC knows the scheme.
 *   Uri.parse("/data/...") has null scheme → LibVLC native crash.
 *
 * file:// URI → pass through unchanged.
 */
private suspend fun resolveDvrUri(
    context: android.content.Context,
    rawUri: String
): Pair<String, ParcelFileDescriptor?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    return@withContext when {
        rawUri.startsWith("content://") -> {
            try {
                val uri = Uri.parse(rawUri)
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    // /proc/self/fd/{n} is the correct LibVLC format on Android.
                    // "fd://{n}" is a Windows VLC convention — NOT valid here.
                    val procPath = "file:///proc/self/fd/${pfd.fd}"
                    Timber.i("DVR playback: opened PFD fd=${pfd.fd} → $procPath for $rawUri")
                    Pair(procPath, pfd)
                } else {
                    Timber.w("DVR playback: contentResolver returned null PFD for $rawUri")
                    Pair(rawUri, null)
                }
            } catch (e: Exception) {
                Timber.e(e, "DVR playback: failed to open PFD for $rawUri")
                Pair(rawUri, null)
            }
        }
        rawUri.startsWith("file://") -> {
            // Already a proper URI — pass through
            Pair(rawUri, null)
        }
        rawUri.startsWith("/") -> {
            // Absolute path without scheme — add file:// so LibVLC can parse it
            Pair("file://$rawUri", null)
        }
        else -> Pair(rawUri, null)
    }
}

/**
 * Media3 can read content:// directly and needs file:// for raw filesystem paths.
 * Keep this separate from [resolveDvrUri], which opens a LibVLC-specific PFD.
 */
private fun resolveDvrMedia3Uri(rawUri: String): String = when {
    rawUri.startsWith("content://") -> rawUri
    rawUri.startsWith("file://") -> rawUri
    rawUri.startsWith("/") -> Uri.fromFile(File(rawUri)).toString()
    else -> rawUri
}

/** Elapsed and total (or "—" for unknown), plus local clock for live. */
private fun formatPlaybackTimeMs(ms: Long): String {
    if (ms < 0) return "—"
    val totalSec = (ms / 1000L).toInt()
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", h, m, s)
    else String.format(Locale.getDefault(), "%d:%02d", m, s)
}

private fun localTimeShortString(): String =
    DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date())

@Composable
private fun PlaybackTimeReadout(
    isLive: Boolean,
    positionMs: Long,
    durationMs: Long,
    timeTick: Int,
    textColor: Color,
    subtextColor: Color,
    mainFont: TextUnit,
    alignEnd: Boolean
) {
    // timeTick drives periodic recomposition for position, duration, and wall clock (live)
    @Suppress("unused")
    val _tick = timeTick
    val (current, endLabel) = run {
        val cur = formatPlaybackTimeMs(positionMs)
        if (durationMs > 0) cur to formatPlaybackTimeMs(durationMs)
        else {
            if (positionMs >= 0) cur to "—"
            else "—" to "—"
        }
    }

    val local = if (isLive) localTimeShortString() else null
    val hAlign = if (alignEnd) Alignment.End else Alignment.Start
    Column(horizontalAlignment = hAlign) {
        Text(
            text = "$current / $endLabel",
            color = textColor,
            fontSize = mainFont,
            fontWeight = FontWeight.Medium
        )
        if (isLive) {
            Text(
                text = "Now: $local",
                color = subtextColor,
                fontSize = (mainFont.value * 0.75f).sp
            )
        }
    }
}

/** Identity for the TEE recording leg — kept in lockstep with RecordingService.RECORDING_USER_AGENT. */
private const val TEE_RECORDING_USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18"

private fun buildDuplicateSout(path: String): String =
    ":sout=#duplicate{dst=display,dst=std{access=file,mux=ts,dst=${vlcSoutQuoted(path)}}}"

private fun vlcSoutQuoted(path: String): String =
    "'${path.replace("'", "_")}'"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun PlayerScreen(
    navController: NavController,
    streamType: String,
    streamId: String,        // String so series episode IDs (non-numeric) work correctly
    extension: String,      // Container extension: "ts" for live, "mp4"/"mkv" for VOD/series
    viewModel: PlayerViewModel = hiltViewModel(),
    dvrViewModel: com.dylandos.iptv.ultimate.ui.screens.dvr.DvrViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val settingsState by settingsViewModel.state.collectAsState()
    val dvrState by dvrViewModel.uiState.collectAsState()
    val isLive = streamType == "live"
    val isDvr  = streamType == "dvr"
    // Engine policy:
    // - VOD/Series/Provider Replay: optional MPV → LibVLC → Media3
    // - Live/DVR: LibVLC → Media3 (USB timeshift live = Media3 only)
    val isVodOrSeries = streamType == "vod" || streamType == "series"
    val isMpvEligible = isVodOrSeries || streamType == "timeshift"
    val preferMpvVod = settingsState.preferMpvPlayback && isMpvEligible
    val preferMedia3Live = settingsState.timeshiftEnabled && isLive
    val preferMedia3Playback = preferMedia3Live
    // ── v5.0 Adaptive Buffer Controller ─────────────────────────────────────────
    // One controller per live session (new channel = fresh session). Starts at the
    // user's static cache setting (not the floor) so enabling it never weakens a
    // manually-tuned value. The recommended cache is applied at each media build via
    // the media-level :network-caching option, so the next channel load picks it up
    // without tearing down the LibVLC engine mid-stream.
    val bufferController = remember(streamId, isLive) {
        if (isLive) {
            LiveBufferController(
                floorMs = settingsState.bufferFloorMs,
                ceilingMs = settingsState.bufferCeilingMs,
                initialCacheMs = settingsState.liveNetworkCacheMs.coerceIn(
                    settingsState.bufferFloorMs,
                    settingsState.bufferCeilingMs
                )
            )
        } else {
            null
        }
    }
    /** Cache used when building live media: adaptive value when enabled, else static. */
    val effectiveLiveCacheMs = if (settingsState.adaptiveBuffer && bufferController != null) {
        bufferController.cacheMs.value
    } else {
        settingsState.liveNetworkCacheMs
    }
    val mpvWrapper = viewModel.mpvWrapper
    /** Center “now playing” card on each channel change / zap (live only). */
    var showLiveZapOsd by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var controlsInteractionTick by remember { mutableIntStateOf(0) }
    var isPlaying by remember { mutableStateOf(false) }
    var controlIndex by remember { mutableIntStateOf(1) }  // Start on Play/Pause button
    // Single focus owner for Firestick D-pad — child control buttons must NOT steal focus
    // or OK activates the stale Compose-focused button (e.g. Record highlight → Rewind action).
    val playerKeyFocusRequester = remember { FocusRequester() }
    var isRecording by remember { mutableStateOf(false) }  // DVR toggle state
    var autoRecoveryAttempts by remember { mutableIntStateOf(0) }  // VLC crash auto-recovery counter
    // AFR: captured on first play so we can restore the original Hz when the player exits
    var afrOriginalRefreshRate by remember { mutableFloatStateOf(-1f) }
    var activeRecordingId by remember { mutableStateOf<String?>(null) }  // DVR: ID of current recording for stop
    var lastDvrToggleAtMs by remember { mutableLongStateOf(0L) }
    // Scope for sequencing the Module 2.3 "restore LibVLC before recording" handshake.
    val dvrEngineScope = rememberCoroutineScope()
    var isFullscreen by remember { mutableStateOf(false) }            // Fullscreen / immersive mode toggle
    var subtitleTracks by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    var audioTracks    by remember { mutableStateOf<List<MediaPlayer.TrackDescription>>(emptyList()) }
    // Full subtitle picker sheet (LibVlcSubtitleManager-backed, replaces inline picker on long-press CC)
    var showFullSubtitleSheet by remember { mutableStateOf(false) }
    var showCatchupSheet by remember { mutableStateOf(false) }
    // S-017: MENU key cycles VLC aspect ratio; null = auto (default LibVLC), then 16:9, 4:3, 1:1, fill
    val aspectRatioOptions = remember { listOf(null, "16:9", "4:3", "1:1", "fill") }
    var aspectIndex by remember { mutableIntStateOf(0) }
    var aspectOsdVisible by remember { mutableStateOf(false) }
    // S-028: MENU key shows/hides stream codec info overlay
    var showStreamInfo by remember { mutableStateOf(false) }

    // One authoritative subtitle list for picker rendering + D-pad selection.
    // Keeping this single source prevents index drift between UI rows and Enter handling.
    val enhancedSubtitleTracks = remember(subtitleTracks, isLive) {
        val disableEntry = Pair(-1, "Disable CC / Subtitles")
        val validTracks = subtitleTracks.filter { it.id >= 0 }
        listOf(disableEntry) + validTracks.map { track ->
            val rawName = track.name ?: ""
            val displayName = when {
                rawName.isBlank() -> if (isLive) "Closed Caption (${track.id})" else "Subtitle ${track.id}"
                rawName.contains("Track 1", ignoreCase = true) ||
                    rawName.contains("Track 2", ignoreCase = true) ||
                    rawName.contains("CC", ignoreCase = true) ||
                    rawName.contains("CEA", ignoreCase = true) ||
                    rawName.contains("Teletext", ignoreCase = true) -> {
                    if (isLive) "CC $rawName" else rawName
                }
                else -> rawName
            }
            Pair(track.id, displayName)
        }
    }

    // LibVLC engine — use runCatching so a native load failure doesn't crash the
    // entire composable. If the .so cannot be loaded, vlcInitError is set and the
    // UI shows a recoverable error card instead of silently killing the activity.
    // Subtitle settings from SettingsViewModel are baked into LibVLC init args so
    // font size, color, outline color, and background opacity are applied immediately.
    var vlcInitError by remember { mutableStateOf<String?>(null) }
    val liveTimeshiftDirectory = remember(
        settingsState.timeshiftEnabled,
        settingsState.dvrStoragePath,
        isLive
    ) {
        if (settingsState.timeshiftEnabled && isLive) {
            resolveTimeshiftDirectory(context, settingsState.dvrStoragePath)
        } else {
            null
        }
    }
    val liveTimeshiftEnabled = settingsState.timeshiftEnabled && isLive && liveTimeshiftDirectory != null
    val liveTimeshiftPath = liveTimeshiftDirectory?.absolutePath
    // MPV owns VOD/series until it fails; Media3 owns USB timeshift live.
    var useMpvPrimary by remember(streamType) { mutableStateOf(preferMpvVod) }
    val provisionVlc = !preferMedia3Live && !useMpvPrimary
    val libVLC = remember(provisionVlc, streamType, settingsState.subtitleSizeSp, settingsState.subtitleColorHex,
                          settingsState.subtitleOutlineColorHex, settingsState.subtitleBgOpacity,
                          settingsState.liveNetworkCacheMs, settingsState.vlcAudioOutput,
                          settingsState.vlcAudioResampler, settingsState.vlcChromaFormat,
                          settingsState.vlcSubtitleEncoding, settingsState.vlcDropLateFrames,
                          settingsState.vlcSkipFrames, settingsState.vlcClockJitterMs,
                          settingsState.vlcNetworkMtu, settingsState.vlcVerboseLog) {
        if (!provisionVlc) null else runCatching {
            if (isLive) makeLiveLibVLC(
                ctx = context,
                settingsState = settingsState,
                subtitleSizeSp = settingsState.subtitleSizeSp,
                subtitleColorHex = settingsState.subtitleColorHex,
                subtitleOutlineColorHex = settingsState.subtitleOutlineColorHex,
                subtitleBgOpacity = settingsState.subtitleBgOpacity
            ) else makeVodLibVLC(
                ctx = context,
                subtitleSizeSp = settingsState.subtitleSizeSp,
                subtitleColorHex = settingsState.subtitleColorHex,
                subtitleOutlineColorHex = settingsState.subtitleOutlineColorHex,
                subtitleBgOpacity = settingsState.subtitleBgOpacity
            )
        }
            .onFailure { Timber.e(it, "LibVLC native init failed") }
            .getOrNull()
    }
    val mediaPlayer = remember(libVLC, streamType) {
        libVLC?.let {
            runCatching { MediaPlayer(it) }
                .onFailure { e -> Timber.e(e, "MediaPlayer creation failed") }
                .getOrNull()
        }
    }
    // Feed the adaptive buffer controller: poll playback state every 2 s while live.
    LaunchedEffect(mediaPlayer, isLive, bufferController) {
        if (!isLive || bufferController == null || mediaPlayer == null) return@LaunchedEffect
        while (isActive) {
            val buffering = false
            bufferController.onEvent(
                if (buffering) LiveBufferEvent.REBUFFER else LiveBufferEvent.HEALTHY_TICK
            )
            delay(2_000)
        }
    }
    // Lazy Media3: do NOT construct ExoPlayer until timeshift or LibVLC fallback needs it.
    val exoHost = remember { LazyExoPlayerHost(context.applicationContext) }
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    fun ensureExoPlayer(): ExoPlayer {
        val existing = exoPlayer
        if (existing != null) return existing
        val ringMaxBytes = liveTimeshiftPath?.let { path ->
            val cacheDir = File(path, "media3_cache")
            TimeshiftRingMath.computeRingMaxBytes(
                freeBytes = maxOf(cacheDir.usableSpace, cacheDir.freeSpace),
                windowMinutes = settingsState.timeshiftWindowMinutes
            )
        }
        val created = exoHost.getOrCreate(
            timeshiftEnabled = liveTimeshiftEnabled,
            timeshiftPath = liveTimeshiftPath,
            ringMaxBytes = ringMaxBytes
        )
        exoPlayer = created
        return created
    }
    var useExoFallback by remember { mutableStateOf(preferMedia3Playback) }
    // Eager-create only when USB timeshift owns live playback from the start.
    LaunchedEffect(preferMedia3Playback, liveTimeshiftEnabled, liveTimeshiftPath) {
        if (preferMedia3Playback) ensureExoPlayer()
    }
    var exoPlaybackError by remember { mutableStateOf<String?>(null) }
    var libVlcFailedForCurrentStream by remember { mutableStateOf(false) }
    var media3SubtitlesEnabled by remember { mutableStateOf(false) }
    var media3TextTrackCount by remember { mutableIntStateOf(0) }
    var media3SubtitleTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var media3AudioTracks by remember { mutableStateOf<List<Pair<Int, String>>>(emptyList()) }
    var selectedMedia3SubtitleId by remember { mutableIntStateOf(-1) }
    var selectedMedia3AudioId by remember { mutableIntStateOf(-1) }
    // VOD: auto-enable softsubs once; honor an explicit user "Off" for this stream.
    var userDisabledSubtitles by remember { mutableStateOf(false) }
    var pauseCatchupWhenReady by remember { mutableStateOf(false) }
    var lastLibVlcPlayingAtMs by remember { mutableLongStateOf(0L) }
    var userRequestedPause by remember { mutableStateOf(false) }
    var vlcLayout by remember { mutableStateOf<VLCVideoLayout?>(null) }
    // Holds the open PFD for DVR content:// playback — closed when player is disposed
    var dvrPfd by remember { mutableStateOf<ParcelFileDescriptor?>(null) }
    val streamLoadToken = remember(uiState.streamUrl) { Any() }
    val latestStreamLoadToken by rememberUpdatedState(streamLoadToken)
    val vlcPlaybackMutex = remember { kotlinx.coroutines.sync.Mutex() }

    fun isAnyEngineActuallyPlaying(): Boolean {
        val vlcPlaying = runCatching { mediaPlayer?.isPlaying == true }.getOrDefault(false)
        val mpvPlaying = useMpvPrimary && mpvWrapper.playbackState.value.isPlaying
        return mpvPlaying || vlcPlaying || (exoPlayer?.isPlaying == true)
    }

    fun failoverMpvToVlc(reason: String) {
        if (!useMpvPrimary) return
        Timber.w("MPV failover → LibVLC: $reason")
        runCatching { mpvWrapper.stop() }
        useMpvPrimary = false
        useExoFallback = false
        libVlcFailedForCurrentStream = false
    }

    fun failoverVlcToMedia3(reason: String) {
        Timber.w("LibVLC failover → Media3: $reason")
        libVlcFailedForCurrentStream = true
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
        }
        useMpvPrimary = false
        useExoFallback = true
    }

    fun stopNonSelectedEngines() {
        when {
            useMpvPrimary -> {
                runCatching {
                    mediaPlayer?.stop()
                    mediaPlayer?.detachViews()
                }
                runCatching {
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                }
            }
            useExoFallback -> {
                runCatching {
                    mediaPlayer?.stop()
                    mediaPlayer?.detachViews()
                }
                runCatching { mpvWrapper.stop() }
            }
            else -> {
                runCatching {
                    exoPlayer?.stop()
                    exoPlayer?.clearMediaItems()
                }
                runCatching { mpvWrapper.stop() }
            }
        }
    }

    fun currentPositionMs(): Long =
        when {
            useMpvPrimary -> mpvWrapper.playbackState.value.position.coerceAtLeast(0L)
            useExoFallback -> ensureExoPlayer().currentPosition.coerceAtLeast(0L)
            else -> runCatching { mediaPlayer?.time ?: 0L }.getOrDefault(0L)
        }

    fun currentDurationMs(): Long =
        when {
            useMpvPrimary -> mpvWrapper.playbackState.value.duration.coerceAtLeast(0L)
            useExoFallback -> ensureExoPlayer().duration.coerceAtLeast(0L)
            else -> runCatching { mediaPlayer?.length ?: 0L }.getOrDefault(0L)
        }

    fun stopActivePlayer() {
        when {
            useMpvPrimary -> mpvWrapper.stop()
            useExoFallback -> ensureExoPlayer().stop()
            else -> runCatching { mediaPlayer?.stop() }
        }
    }

    fun playActivePlayer() {
        userRequestedPause = false
        when {
            useMpvPrimary -> mpvWrapper.play()
            useExoFallback -> ensureExoPlayer().play()
            else -> runCatching { mediaPlayer?.play() }
        }
        isPlaying = true
    }

    fun pauseActivePlayer() {
        userRequestedPause = true
        when {
            useMpvPrimary -> mpvWrapper.pause()
            useExoFallback -> ensureExoPlayer().pause()
            else -> runCatching { mediaPlayer?.pause() }
        }
        isPlaying = false
    }

    fun toggleActivePlayer() {
        when {
            useMpvPrimary -> {
                if (userRequestedPause || !isPlaying) playActivePlayer() else pauseActivePlayer()
            }
            useExoFallback -> {
                val ep = ensureExoPlayer()
                if (ep.isPlaying) ep.pause() else ep.play()
            }
            else -> {
                runCatching {
                    if (userRequestedPause || !isPlaying) playActivePlayer() else pauseActivePlayer()
                }
            }
        }
    }

    fun seekActivePlayer(deltaMs: Long) {
        val target = (currentPositionMs() + deltaMs).coerceAtLeast(0L)
        when {
            useMpvPrimary -> mpvWrapper.seekTo(target)
            useExoFallback -> ensureExoPlayer().seekTo(target)
            else -> runCatching { mediaPlayer?.time = target }
        }
    }

    fun canSeekActivePlayer(): Boolean =
        when {
            useMpvPrimary -> mpvWrapper.playbackState.value.duration > 0L
            useExoFallback -> ensureExoPlayer().isCurrentMediaItemSeekable
            else -> runCatching { mediaPlayer?.isSeekable == true }.getOrDefault(false)
        }

    val configuredSkipMs = settingsState.skipIntervalSeconds.coerceIn(5, 60) * 1_000L

    fun rewindLiveOrCatchup(deltaMs: Long = configuredSkipMs) {
        when {
            uiState.isCatchupPlayback -> seekActivePlayer(-deltaMs)
            uiState.canRestartCurrentProgram -> viewModel.restartCurrentProgram()
            liveTimeshiftEnabled && canSeekActivePlayer() -> seekActivePlayer(-deltaMs)
            liveTimeshiftEnabled -> pauseActivePlayer()
        }
    }

    fun fastForwardLiveOrCatchup(deltaMs: Long = configuredSkipMs) {
        if (uiState.isCatchupPlayback || canSeekActivePlayer()) {
            seekActivePlayer(deltaMs)
        }
    }

    fun changeActiveVolume(delta: Int) {
        when {
            useMpvPrimary -> mpvWrapper.adjustVolume(delta)
            useExoFallback -> {
                ensureExoPlayer().let { ep ->
                    ep.volume = (ep.volume + delta / 100f).coerceIn(0f, 1f)
                }
            }
            else -> {
                runCatching {
                    mediaPlayer?.let { it.volume = (it.volume + delta).coerceIn(0, 200) }
                }
            }
        }
    }

    fun selectMedia3Track(encodedId: Int, trackType: Int) {
        val ep = ensureExoPlayer()
        if (encodedId < 0) {
            ep.trackSelectionParameters = ep.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(trackType, true)
                .build()
            if (trackType == C.TRACK_TYPE_TEXT) {
                media3SubtitlesEnabled = false
                selectedMedia3SubtitleId = -1
            }
            return
        }
        if (encodedId in 999_001..999_004) {
            ep.trackSelectionParameters = ep.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredTextLanguage("en")
                .setSelectUndeterminedTextLanguage(true)
                .build()
            media3SubtitlesEnabled = true
            selectedMedia3SubtitleId = encodedId
            return
        }
        val groupIndex = encodedId / 1_000
        val trackIndex = encodedId % 1_000
        val group = ep.currentTracks.groups.getOrNull(groupIndex) ?: return
        if (group.type != trackType || trackIndex !in 0 until group.length) return
        val override = TrackSelectionOverride(group.mediaTrackGroup, listOf(trackIndex))
        ep.trackSelectionParameters = ep.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(override)
            .build()
        if (trackType == C.TRACK_TYPE_TEXT) {
            media3SubtitlesEnabled = true
            selectedMedia3SubtitleId = encodedId
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
            selectedMedia3AudioId = encodedId
        }
    }

    fun selectActiveSubtitleTrack(trackId: Int) {
        userDisabledSubtitles = trackId < 0
        when {
            useMpvPrimary -> mpvWrapper.selectSubtitleTrack(trackId)
            useExoFallback -> selectMedia3Track(trackId, C.TRACK_TYPE_TEXT)
            else -> try { mediaPlayer?.spuTrack = trackId } catch (_: Exception) {}
        }
    }

    fun selectActiveAudioTrack(trackId: Int) {
        when {
            useMpvPrimary -> mpvWrapper.selectAudioTrack(trackId)
            useExoFallback -> selectMedia3Track(trackId, C.TRACK_TYPE_AUDIO)
            else -> try { mediaPlayer?.audioTrack = trackId } catch (_: Exception) {}
        }
    }

    fun persistPlaybackPositionSnapshot() {
        if (isLive || isDvr) return
        val positionMs = currentPositionMs()
        val durationMs = currentDurationMs()
        if (positionMs > 0L || durationMs > 0L) {
            viewModel.updatePlaybackPosition(positionMs, durationMs)
            viewModel.savePositionNow()
        }
    }

    fun toggleDvrRecordingFromPlayer() {
        val now = System.currentTimeMillis()
        if (now - lastDvrToggleAtMs < 1_200L) return
        lastDvrToggleAtMs = now

        if (isRecording) {
            activeRecordingId?.let { dvrViewModel.stopRecording(it) }
            activeRecordingId = null
            isRecording = false
            return
        }

        val state = uiState
        if (state.streamUrl.isBlank()) return

        fun beginRecording() {
            val newRecordingId = dvrViewModel.startRecording(
                channelName = state.streamTitle.ifEmpty { "Channel $streamId" },
                channelId = streamId.toIntOrNull() ?: state.currentLiveStreamId ?: 0,
                streamUrl = state.streamUrl,
                programTitle = state.liveEpgTitle?.takeIf { it.isNotBlank() }
            )
            if (newRecordingId.isNotBlank()) {
                activeRecordingId = newRecordingId
                isRecording = true
            } else {
                activeRecordingId = null
                isRecording = false
            }
        }

        // Module 2.3 — Active Recording Safety. DVR recording (TEE #duplicate / :sout
        // stream-copy) is only supported by LibVLC. If live playback fell back to
        // Media3, restore LibVLC first so the recorder reuses the player's single
        // provider connection (TEE) instead of opening a second one that the provider
        // is likely to block (the empty-file failure mode).
        if (isLive && useExoFallback && !liveTimeshiftEnabled) {
            Timber.i("DVR: live is on a non-LibVLC engine — restoring LibVLC before recording")
            android.widget.Toast.makeText(
                context,
                "Switching to LibVLC for recording…",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            libVlcFailedForCurrentStream = false
            useExoFallback = false
            // Wait (bounded) for LibVLC to actually start streaming so the bridge reports
            // the URL and RecordingService selects the single-connection TEE path.
            dvrEngineScope.launch {
                val deadline = System.currentTimeMillis() + 8_000L
                while (System.currentTimeMillis() < deadline &&
                    !PlayerRecordingBridge.isPlayingUrl(state.streamUrl)
                ) {
                    delay(150)
                }
                beginRecording()
            }
        } else {
            beginRecording()
        }
    }

    // Restart the hide countdown after every remote interaction, including when
    // the controls were already visible.
    LaunchedEffect(showControls, controlsInteractionTick) {
        if (showControls) {
            delay(settingsState.controlsAutoHideSeconds.coerceIn(3, 10) * 1_000L)
            showControls = false
        }
    }

    LaunchedEffect(uiState.streamUrl, isLive) {
        if (!isLive || uiState.streamUrl.isEmpty()) {
            showLiveZapOsd = false
            return@LaunchedEffect
        }
        showLiveZapOsd = true
        delay(settingsState.zappingOsdDurationSeconds.coerceIn(3, 10) * 1_000L)
        showLiveZapOsd = false
    }

    // Drive OSD time + progress bar updates ~1s while a player instance exists.
    // Only increments when controls are visible — saves ~50 recompositions/min on Firestick
    // when the user is just watching (HUD auto-hides after 5s).
    var timeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(mediaPlayer, exoPlayer, useExoFallback) {
        if (mediaPlayer == null && !useExoFallback) return@LaunchedEffect
        while (isActive) {
            delay(1000)
            if (showControls) timeTick++   // MEDIUM-009: no-op when HUD hidden
        }
    }

    // Keep ViewModel watch-position state in sync while VOD/series content is playing.
    LaunchedEffect(mediaPlayer, exoPlayer, useExoFallback, isLive, isDvr) {
        if (mediaPlayer == null && !useExoFallback) return@LaunchedEffect
        if (isLive || isDvr) return@LaunchedEffect
        while (isActive) {
            delay(1000)
            val positionMs = currentPositionMs()
            val durationMs = currentDurationMs()
            if (positionMs > 0L || durationMs > 0L) {
                viewModel.updatePlaybackPosition(positionMs, durationMs)
            }
        }
    }

    DisposableEffect(exoPlayer, useExoFallback) {
        val player = exoPlayer ?: return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                if (useExoFallback) isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                Timber.e(error, "Media3 fallback playback failed")
                exoPlaybackError = error.errorCodeName
                runCatching {
                    (context.applicationContext as? com.dylandos.iptv.ultimate.DylandosApp)
                        ?.fieldTelemetry
                        ?.recordPlaybackFailure(
                            engine = "media3",
                            errorCode = error.errorCodeName,
                            streamType = streamType,
                            extra = error.errorCode.toString()
                        )
                }
                if (error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                    error.errorCode == PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
                ) {
                    runCatching {
                        (context.applicationContext as? com.dylandos.iptv.ultimate.DylandosApp)
                            ?.fieldTelemetry
                            ?.recordDecodeFailure("media3", error.errorCodeName)
                    }
                }
                if (isLive && liveTimeshiftEnabled) {
                    // Never start LibVLC while the USB timeshift pipeline owns the
                    // live stream. A mixed-engine handoff is what creates dual audio.
                    vlcInitError = "USB timeshift playback failed: ${error.errorCodeName}. Disable Timeshift to retry normal live TV."
                } else if (uiState.isCatchupPlayback && viewModel.retryCatchupWithNextUrl()) {
                    // Provider Replay has multiple endpoint/container candidates. Restart
                    // from LibVLC for each one, then allow the normal Media3 fallback.
                    useExoFallback = false
                    libVlcFailedForCurrentStream = false
                    Timber.w("Media3 Replay failure; advancing to next provider URL")
                } else if (isLive && autoRecoveryAttempts < 8) {
                    useExoFallback = false
                    autoRecoveryAttempts++
                } else {
                    vlcInitError = "Playback failed in both LibVLC and Media3 (${error.errorCodeName})."
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                val subtitles = mutableListOf<Pair<Int, String>>()
                val audio = mutableListOf<Pair<Int, String>>()
                var selectedSubtitle = -1
                var selectedAudio = -1
                tracks.groups.forEachIndexed { groupIndex, group ->
                    for (trackIndex in 0 until group.length) {
                        val format = group.getTrackFormat(trackIndex)
                        val encodedId = groupIndex * 1_000 + trackIndex
                        val fallback = if (group.type == C.TRACK_TYPE_TEXT) "Subtitle" else "Audio"
                        val label = listOfNotNull(
                            format.label?.takeIf { it.isNotBlank() },
                            format.language?.takeIf { it.isNotBlank() }?.uppercase(Locale.getDefault()),
                            format.sampleMimeType?.substringAfterLast('/')?.uppercase(Locale.getDefault())
                        ).distinct().joinToString(" · ").ifBlank { "$fallback ${trackIndex + 1}" }
                        when (group.type) {
                            C.TRACK_TYPE_TEXT -> {
                                val isCc = format.label?.contains("CC", ignoreCase = true) == true ||
                                    format.sampleMimeType?.contains("cea", ignoreCase = true) == true ||
                                    format.sampleMimeType?.contains("closedcaption", ignoreCase = true) == true ||
                                    isLive
                                val finalLabel = if (isLive && !label.contains("CC", ignoreCase = true)) "CC: $label" else label
                                subtitles += encodedId to finalLabel
                                if (group.isTrackSelected(trackIndex)) selectedSubtitle = encodedId
                            }
                            C.TRACK_TYPE_AUDIO -> {
                                audio += encodedId to label
                                if (group.isTrackSelected(trackIndex)) selectedAudio = encodedId
                            }
                        }
                    }
                }
                if (isLive && subtitles.isEmpty()) {
                    subtitles += 999_001 to "CC 1 (Auto Detect / CEA-608)"
                    subtitles += 999_002 to "CC 2 (Secondary)"
                }
                media3SubtitleTracks = subtitles
                media3AudioTracks = audio
                media3TextTrackCount = subtitles.size
                selectedMedia3SubtitleId = if (media3SubtitlesEnabled) selectedSubtitle else -1
                selectedMedia3AudioId = selectedAudio
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(uiState.streamUrl) {
        libVlcFailedForCurrentStream = false
        userDisabledSubtitles = false
    }

    LaunchedEffect(activeRecordingId, dvrState.activeRecordings, dvrState.completedRecordings) {
        val id = activeRecordingId ?: return@LaunchedEffect
        when {
            dvrState.activeRecordings.any { it.id == id } -> isRecording = true
            dvrState.completedRecordings.any { it.id == id } -> {
                activeRecordingId = null
                isRecording = false
            }
        }
    }

    // Module 1.3 — surface real DVR outcomes reported by the background service.
    // A failed recording no longer reverts the button silently: the user sees why.
    LaunchedEffect(Unit) {
        dvrViewModel.recordingEvents.collect { event ->
            when (event) {
                is com.dylandos.iptv.ultimate.ui.screens.dvr.DvrEvent.RecordingFailed -> {
                    if (event.id == activeRecordingId) {
                        activeRecordingId = null
                        isRecording = false
                    }
                    android.widget.Toast.makeText(
                        context,
                        "Recording failed: ${event.reason}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                is com.dylandos.iptv.ultimate.ui.screens.dvr.DvrEvent.RecordingStarted -> {
                    if (event.id == activeRecordingId) isRecording = true
                    android.widget.Toast.makeText(
                        context,
                        "Recording ${event.channelName}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
                is com.dylandos.iptv.ultimate.ui.screens.dvr.DvrEvent.RecordingSaved -> {
                    android.widget.Toast.makeText(
                        context,
                        "Saved recording: ${event.channelName}",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // Prepare the independent Media3 engine only after both native engines fail or stall.
    // This keeps Exo/Media3 out of the normal Firestick playback path.
    LaunchedEffect(useExoFallback, streamLoadToken) {
        val requestToken = streamLoadToken
        if (!useExoFallback) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            exoPlaybackError = null
            return@LaunchedEffect
        }
        val exoPlayer = ensureExoPlayer()
        val url = uiState.streamUrl.ifEmpty { return@LaunchedEffect }
        val playbackUrl = if (isDvr) resolveDvrMedia3Uri(url) else url
        if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
        runCatching {
            mediaPlayer?.stop()
            mediaPlayer?.detachViews()
        }
        if (isDvr) {
            dvrPfd?.let { runCatching { it.close() } }
            dvrPfd = null
        }
        PlayerRecordingBridge.reportStopped()
        exoPlaybackError = null
        vlcInitError = null
        media3SubtitlesEnabled = false
        selectedMedia3SubtitleId = -1
        // Live CC stays off until the user enables it. VOD softsubs should be visible
        // using the preferred language (Media3 still needs an explicit override once
        // tracks arrive — see onTracksChanged / auto-enable effect).
        val disableTextByDefault = isLive || isDvr
        exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters
            .buildUpon()
            .setPreferredTextLanguage(SubtitleLanguage.toMedia3LanguageTag(settingsState.subtitleLanguage))
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disableTextByDefault)
            .build()
        exoPlayer.setMediaItem(MediaItem.fromUri(playbackUrl))
        exoPlayer.prepare()
        if (requestToken !== latestStreamLoadToken) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            return@LaunchedEffect
        }
        exoPlayer.play()
        Timber.w("Switched playback to Media3 fallback: $playbackUrl")
    }

    // Startup stall watchdog — VOD/Series: MPV → LibVLC → Media3. Live: LibVLC → Media3.
    LaunchedEffect(streamLoadToken, preferMedia3Playback, preferMpvVod) {
        val requestToken = streamLoadToken
        if (uiState.streamUrl.isEmpty()) return@LaunchedEffect
        isPlaying = false
        userRequestedPause = false
        lastLibVlcPlayingAtMs = 0L
        media3SubtitlesEnabled = false
        selectedMedia3SubtitleId = -1
        useMpvPrimary = preferMpvVod
        useExoFallback = preferMedia3Playback
        val firstTimeout = when {
            preferMpvVod -> 20_000L
            isLive -> 12_000L
            else -> 30_000L
        }
        delay(firstTimeout)
        if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
        if (isAnyEngineActuallyPlaying()) {
            isPlaying = true
            stopNonSelectedEngines()
            return@LaunchedEffect
        }
        if (useMpvPrimary) {
            failoverMpvToVlc("MPV startup timeout")
            delay(25_000L)
            if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
            if (isAnyEngineActuallyPlaying()) {
                isPlaying = true
                stopNonSelectedEngines()
                return@LaunchedEffect
            }
        }
        if (!isPlaying) {
            if (useExoFallback && preferMedia3Live) {
                Timber.w("Media3 USB timeshift startup timed out; keeping a single Media3 engine")
                vlcInitError = "USB timeshift could not start this stream in Media3. Disable Timeshift to use normal live playback."
            } else {
                failoverVlcToMedia3("startup timeout")
            }
        }
    }


    // ── MPV primary path for VOD / Series ─────────────────────────────────────
    val mpvPlaybackState by mpvWrapper.playbackState.collectAsState()
    val mpvTracks by mpvWrapper.tracks.collectAsState()
    val mpvSubtitleTracks = remember(mpvTracks, isLive) {
        val disableEntry = -1 to "Disable CC / Subtitles"
        listOf(disableEntry) + mpvTracks.filter { it.type == "sub" }.map { track ->
            val lang = track.language?.takeIf { it.isNotBlank() }
            val title = track.title?.takeIf { it.isNotBlank() }
                ?: if (isLive) "Closed Caption ${track.id}" else "Subtitle ${track.id}"
            val codec = track.codec?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val label = when {
                lang != null && title != null -> "$lang — $title$codec"
                lang != null -> "$lang$codec"
                else -> "$title$codec"
            }
            track.id to label
        }
    }
    val mpvAudioTracks = remember(mpvTracks) {
        mpvTracks.filter { it.type == "audio" }.map { track ->
            val lang = track.language?.takeIf { it.isNotBlank() }
            val title = track.title?.takeIf { it.isNotBlank() } ?: "Audio ${track.id}"
            val codec = track.codec?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            val label = when {
                lang != null -> "$lang — $title$codec"
                else -> "$title$codec"
            }
            track.id to label
        }
    }
    LaunchedEffect(mpvPlaybackState.isPlaying, mpvPlaybackState.isBuffering, mpvPlaybackState.error, useMpvPrimary) {
        if (!useMpvPrimary) return@LaunchedEffect
        if (mpvPlaybackState.isPlaying) {
            isPlaying = true
            userRequestedPause = false
        } else if (mpvPlaybackState.error != null) {
            failoverMpvToVlc(mpvPlaybackState.error ?: "MPV error")
        } else if (!mpvPlaybackState.isBuffering && userRequestedPause) {
            isPlaying = false
        }
    }
    LaunchedEffect(useMpvPrimary) {
        if (!useMpvPrimary) return@LaunchedEffect
        mpvWrapper.events.collect { event ->
            when (event) {
                is MpvEvent.PlaybackRestart, is MpvEvent.FileLoaded -> {
                    isPlaying = true
                    stopNonSelectedEngines()
                }
                else -> Unit
            }
        }
    }
    LaunchedEffect(uiState.streamUrl, useMpvPrimary, streamLoadToken) {
        if (!useMpvPrimary) return@LaunchedEffect
        val url = uiState.streamUrl
        if (url.isEmpty()) return@LaunchedEffect
        val requestToken = streamLoadToken
        val profile = MpvEngineFactory.PlaybackProfile.VOD_LOW_END
        val ok = mpvWrapper.initialize(profile)
        if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
        if (!ok) {
            failoverMpvToVlc(mpvWrapper.playbackState.value.error ?: "MPV init failed")
            return@LaunchedEffect
        }
        // Wait for SurfaceView — loading before attach causes audio-only / black video on Firestick.
        val surfaceReady = mpvWrapper.awaitSurfaceAttached(4_500L)
        if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
        if (!surfaceReady) {
            Timber.w("MPV surface not ready in time — loading anyway (recovery may still help)")
        }
        val resumeMs = uiState.resumePositionMs
        mpvWrapper.loadUrl(url, startPosition = if (resumeMs > 5_000L) resumeMs else 0L)
        // Apply user subtitle appearance (shared settings with VLC/Media3).
        runCatching {
            mpvWrapper.applySubtitleStyle(
                sizeSp = settingsState.subtitleSizeSp,
                colorHex = settingsState.subtitleColorHex,
                outlineHex = settingsState.subtitleOutlineColorHex,
                bgOpacityPercent = settingsState.subtitleBgOpacity
            )
        }
        Timber.i("MPV loading VOD/series: $url (surfaceAttached=${mpvWrapper.isSurfaceAttached()})")
    }

    // Netflix-style VOD: audio plays, video black → cycle hwdec (HEVC/10-bit Fire OS quirk).
    LaunchedEffect(useMpvPrimary, uiState.streamUrl, streamLoadToken, mpvPlaybackState.isPlaying) {
        if (!useMpvPrimary) return@LaunchedEffect
        if (uiState.streamUrl.isEmpty()) return@LaunchedEffect
        // Give demux/decode a moment before declaring black video.
        delay(2_500L)
        var attempts = 0
        while (useMpvPrimary && attempts < 4) {
            val state = mpvWrapper.playbackState.value
            val hasAudioProgress = state.isPlaying || state.position > 500L
            val noVideoFrame = state.videoWidth <= 0
            if (hasAudioProgress && noVideoFrame) {
                Timber.w(
                    "MPV audio-without-video detected (pos=${state.position} codec=${state.videoCodec}) — recovering"
                )
                if (!mpvWrapper.recoverBlackVideo()) break
                attempts++
                delay(2_000L)
            } else {
                break
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { mpvWrapper.stop() }
        }
    }

    // Track LibVLC playback state — nullable-safe (mediaPlayer can be null if init failed)
    DisposableEffect(mediaPlayer) {
        val listener = MediaPlayer.EventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaying = true
                    userRequestedPause = false
                    lastLibVlcPlayingAtMs = System.currentTimeMillis()
                    autoRecoveryAttempts = 0  // Reset on successful play
                    // Live: keep CC off until the user enables it.
                    // VOD: do not wipe softsubs — auto-enable runs after tracks populate.
                    if (isLive) {
                        runCatching { mediaPlayer?.spuTrack = -1 }
                    }
                    // Re-read tracks — LibVLC only populates them AFTER Playing fires
                    subtitleTracks = try { mediaPlayer?.spuTracks?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
                    audioTracks    = try { mediaPlayer?.audioTracks?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
                }
                MediaPlayer.Event.Paused,
                MediaPlayer.Event.Stopped -> {
                    userRequestedPause = event.type == MediaPlayer.Event.Paused
                    if (!isAnyEngineActuallyPlaying()) {
                        isPlaying = false
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    Timber.e("LibVLC error [$streamType]")
                    if (isLive) {
                        val playedRecently = System.currentTimeMillis() - lastLibVlcPlayingAtMs < 15_000L
                        if (playedRecently && autoRecoveryAttempts < 3) {
                            // A live stream can raise EncounteredError after it has been
                            // healthy (stale manifest, dropped TS connection). The prior
                            // code ignored that signal and left a frozen/blank surface.
                            // Retry the same engine first so a transient outage does not
                            // consume another provider connection; escalate after 3 tries.
                            autoRecoveryAttempts++
                            Timber.w("LibVLC live error after playback; scheduling recovery $autoRecoveryAttempts/3")
                        } else {
                            Timber.w("LibVLC live recovery exhausted or failed at startup; switching to Media3 fallback")
                            libVlcFailedForCurrentStream = true
                            runCatching {
                                mediaPlayer?.stop()
                                mediaPlayer?.detachViews()
                            }
                            useExoFallback = true
                        }
                    } else if (uiState.isCatchupPlayback && !libVlcFailedForCurrentStream) {
                        // A number of providers expose archive HLS/TS variants that LibVLC
                        // rejects but Media3 can parse.  Exhaust both engines for the same
                        // URL before changing the provider time/container candidate.
                        failoverVlcToMedia3("Replay URL rejected by LibVLC")
                    } else if (uiState.isCatchupPlayback && viewModel.retryCatchupWithNextUrl()) {
                        useExoFallback = false
                        libVlcFailedForCurrentStream = false
                        Timber.w("LibVLC + Media3 Replay failure; advancing to next provider URL")
                    } else if (isDvr) {
                        libVlcFailedForCurrentStream = true
                        Timber.w("DVR LibVLC playback failed; switching to Media3 local-file last resort")
                        useExoFallback = true
                    } else {
                        libVlcFailedForCurrentStream = true
                        Timber.w("LibVLC failed; switching to Media3 fallback")
                        useExoFallback = true
                    }
                }
            }
        }
        mediaPlayer?.setEventListener(listener)
        onDispose {
            mediaPlayer?.setEventListener(null)
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.detachViews() }
            runCatching { mediaPlayer?.release() }
        }
    }

    // Broadcast CC and embedded subtitle tracks often arrive several seconds
    // after video starts. Poll briefly so the picker updates instead of staying empty.
    LaunchedEffect(uiState.streamUrl, useExoFallback, isPlaying) {
        if (uiState.streamUrl.isEmpty() || useExoFallback || !isPlaying) return@LaunchedEffect
        repeat(15) {
            subtitleTracks = try {
                mediaPlayer?.spuTracks?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            audioTracks = try {
                mediaPlayer?.audioTracks?.toList() ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
            delay(1_000L)
        }
    }

    // VOD LibVLC: auto-enable preferred (or first) soft-sub once tracks exist.
    LaunchedEffect(isVodOrSeries, useMpvPrimary, useExoFallback, subtitleTracks, userDisabledSubtitles, isPlaying) {
        if (!isVodOrSeries || useMpvPrimary || useExoFallback || userDisabledSubtitles || !isPlaying) {
            return@LaunchedEffect
        }
        val tracks = subtitleTracks.filter { it.id >= 0 }
        if (tracks.isEmpty()) return@LaunchedEffect
        val current = try { mediaPlayer?.spuTrack ?: -1 } catch (_: Exception) { -1 }
        if (current >= 0) return@LaunchedEffect
        val preferred = settingsState.subtitleLanguage
        val pick = tracks.firstOrNull {
            SubtitleLanguage.labelContainsPreferred(it.name, preferred) ||
                SubtitleLanguage.matches(it.name, preferred)
        } ?: tracks.first()
        runCatching { mediaPlayer?.spuTrack = pick.id }
        Timber.i("LibVLC auto-enabled subtitle track ${pick.id} (${pick.name})")
    }

    // VOD MPV: softsubs often lack a default flag — pick preferred/first after demux.
    // Also re-assert after playback restart (hwdec recovery can clear visibility).
    LaunchedEffect(
        isVodOrSeries,
        useMpvPrimary,
        mpvTracks,
        userDisabledSubtitles,
        settingsState.subtitleLanguage,
        mpvPlaybackState.isPlaying,
        mpvPlaybackState.currentSubtitleTrack
    ) {
        if (!isVodOrSeries || !useMpvPrimary || userDisabledSubtitles) return@LaunchedEffect
        if (mpvTracks.none { it.type == "sub" }) return@LaunchedEffect
        mpvWrapper.autoEnableSubtitleIfNeeded(settingsState.subtitleLanguage)
    }

    // VOD Media3 fallback: enable preferred text track once groups are known.
    LaunchedEffect(
        isVodOrSeries,
        useExoFallback,
        media3SubtitleTracks,
        userDisabledSubtitles,
        settingsState.subtitleLanguage
    ) {
        if (!isVodOrSeries || !useExoFallback || userDisabledSubtitles) return@LaunchedEffect
        if (media3SubtitleTracks.isEmpty() || media3SubtitlesEnabled) return@LaunchedEffect
        val preferred = settingsState.subtitleLanguage
        val pick = media3SubtitleTracks.firstOrNull { (_, label) ->
            SubtitleLanguage.labelContainsPreferred(label, preferred)
        } ?: media3SubtitleTracks.firstOrNull() ?: return@LaunchedEffect
        selectMedia3Track(pick.first, C.TRACK_TYPE_TEXT)
        Timber.i("Media3 auto-enabled subtitle track ${pick.first} (${pick.second})")
    }

    LaunchedEffect(isPlaying, uiState.isCatchupPlayback, uiState.catchupResumePositionMs) {
        val resumeMs = uiState.catchupResumePositionMs
        if (!isPlaying || !uiState.isCatchupPlayback || resumeMs <= 0L) return@LaunchedEffect
        delay(350L)
        when {
            useExoFallback -> ensureExoPlayer().seekTo(resumeMs)
            else -> mediaPlayer?.time = resumeMs
        }
        if (pauseCatchupWhenReady) {
            pauseCatchupWhenReady = false
            pauseActivePlayer()
        }
    }

    // VOD resume: reset seek flag when a new stream starts; seek on first Playing event
    var resumedOnFirstPlay by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.streamUrl) { resumedOnFirstPlay = false }
    LaunchedEffect(isPlaying) {
        if (!isPlaying || isLive || isDvr || resumedOnFirstPlay) return@LaunchedEffect
        val resumeMs = uiState.resumePositionMs
        if (resumeMs <= 5_000L) return@LaunchedEffect  // skip trivial positions
        resumedOnFirstPlay = true
        delay(300L) // let the stream buffer briefly before seeking
        try {
            when {
                useExoFallback -> ensureExoPlayer().seekTo(resumeMs)
                else -> mediaPlayer?.time = resumeMs
            }
            Timber.i("VOD resume: seeked to ${resumeMs}ms")
        } catch (e: Exception) {
            Timber.e(e, "VOD resume seek failed")
        }
    }

    // AFR: when playback starts, match the window's preferred refresh rate to the content type.
    // Live IPTV is almost universally 25fps (PAL) or 29.97fps (NTSC) — set to 50Hz/60Hz respectively.
    // VOD/movies are predominantly 23.976fps — set to 24Hz so the display can present each frame
    // exactly once with no judder. On exit, the original Hz is restored.
    // Only applies on API 23+ (Marshmallow) which is guaranteed by Firestick 4K (Fire OS 8 = API 30).
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val activity = context as? android.app.Activity ?: return@LaunchedEffect
            // Capture original refresh rate once so we can restore it on exit
            if (afrOriginalRefreshRate < 0f) {
                afrOriginalRefreshRate = activity.window.attributes.preferredRefreshRate
            }
            val targetHz = when {
                isLive -> 50f   // Live IPTV: 25fps content → 50Hz display eliminates judder
                isDvr  -> 50f   // DVR recordings from live streams — same 50Hz target
                else   -> 24f   // VOD/movies: 23.976fps content → 24Hz nearest match
            }
            runCatching {
                val lp = activity.window.attributes
                lp.preferredRefreshRate = targetHz
                activity.window.attributes = lp
                Timber.d("AFR: set preferredRefreshRate=${targetHz}Hz for $streamType")
            }.onFailure { Timber.w(it, "AFR set failed") }
        }
    }

    // Reconnect handler: live TV gets a short LibVLC retry budget for transient
    // server hiccups before Media3 is considered.
    // 1500ms matches LibVLC surface teardown settle time on Firestick 4K.
    LaunchedEffect(autoRecoveryAttempts) {
        // This recovery path is for normal live TV. USB timeshift is Media3-owned;
        // mixing engines there can produce duplicate audio and corrupt its ring buffer.
        if (!isLive || useExoFallback || liveTimeshiftEnabled) return@LaunchedEffect
        if (autoRecoveryAttempts in 1..3) {
            val requestToken = streamLoadToken
            val mp     = mediaPlayer ?: return@LaunchedEffect
            val lc     = libVLC     ?: return@LaunchedEffect
            val layout = vlcLayout  ?: return@LaunchedEffect
            val rawUrl = uiState.streamUrl.ifEmpty { return@LaunchedEffect }
            delay(if (autoRecoveryAttempts <= 4) 1500L else 2500L)
            if (requestToken !== latestStreamLoadToken || rawUrl != uiState.streamUrl) return@LaunchedEffect
            Timber.i("LibVLC auto-recovery attempt $autoRecoveryAttempts for $rawUrl")
            // For DVR, we must re-resolve the URI (open a fresh PFD) just like the
            // main playback LaunchedEffect does.  Passing the raw content:// or
            // absolute path directly to LibVLC causes the same crash we're recovering from.
            val (url, freshPfd) = if (isDvr) {
                dvrPfd?.let { try { it.close() } catch (_: Exception) {} }
                resolveDvrUri(context, rawUrl)
            } else {
                Pair(rawUrl, null)
            }
            dvrPfd = freshPfd
            if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
            try {
                vlcPlaybackMutex.lock()
                try {
                    if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
                    try { mp.stop() } catch (_: Exception) {}
                    try { mp.detachViews() } catch (_: Exception) {}
                    delay(120)
                    if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
                    mp.attachViews(layout, null, false, false)
                    val media = buildMedia(
                        libVLC = lc,
                        url = url,
                        isLive = isLive,
                        liveCacheMs = effectiveLiveCacheMs,
                        dropLateFrames = settingsState.vlcDropLateFrames,
                        skipFrames = settingsState.vlcSkipFrames,
                        timeshiftEnabled = liveTimeshiftEnabled,
                        timeshiftPath = liveTimeshiftPath
                    )
                    mp.media = media
                    media.release()
                    mp.play()
                } finally {
                    vlcPlaybackMutex.unlock()
                }
            } catch (e: Exception) {
                Timber.e(e, "Auto-recovery failed: $url")
            }
        }
    }

    // Release on exit — nullable-safe. Also close any open DVR PFD.
    // Also clear the bridge so RecordingService knows the player is gone.
    DisposableEffect(Unit) {
        PipController.activate()   // signal MainActivity that PiP is available
        onDispose {
            persistPlaybackPositionSnapshot()
            PipController.deactivate()
            runCatching { mediaPlayer?.stop() }
            runCatching { mediaPlayer?.detachViews() }
            runCatching { mediaPlayer?.release() }
            runCatching { libVLC?.release() }
            exoHost.release()
            exoPlayer = null
            dvrPfd?.let { try { it.close() } catch (_: Exception) {} }
            PlayerRecordingBridge.reportStopped()   // clears bridge URL → TEE watchdog auto-finalizes
            // Free Coil bitmaps so returning to Movies/Series on Firestick does not
            // stack poster decode peak on top of player buffers.
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
            // AFR cleanup: restore display refresh rate to whatever it was before playback
            if (afrOriginalRefreshRate >= 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching {
                    val activity = context as? android.app.Activity
                    activity?.window?.let { w ->
                        val lp = w.attributes
                        lp.preferredRefreshRate = afrOriginalRefreshRate
                        w.attributes = lp
                        Timber.d("AFR restored: ${afrOriginalRefreshRate}Hz")
                    }
                }
            }
        }
    }

    // ── Firestick Sleep Prevention ──────────────────────────────────────────────
    // FLAG_KEEP_SCREEN_ON: signals to the window manager that display must stay on.
    // SCREEN_BRIGHT_WAKE_LOCK: low-level power lock that forces the display to stay
    //   fully lit, bypassing Fire OS's aggressive 30-min display sleep timer.
    //   Combined with FLAG_KEEP_SCREEN_ON this reliably prevents Fire TV sleep
    //   (the 30-min inactivity shutdown that kills the video surface mid-stream).
    // ON_AFTER_RELEASE: keeps the screen on briefly after the lock releases so the
    //   user sees the UI rather than a black screen on player exit.
    // NO timeout cap — wake lock is held for the entire player session and released
    //   in onDispose when the player exits. This is safe because onDispose is always
    //   called (Compose guarantees DisposableEffect cleanup on recomposition exit).
    DisposableEffect(Unit) {
        val activity = context as? android.app.Activity
        activity?.window?.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        @Suppress("DEPRECATION")
        val wl = pm?.newWakeLock(
            android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
            android.os.PowerManager.ON_AFTER_RELEASE,
            "DylandosIPTV:PlayerScreenWakeLock"
        )?.also { it.acquire() }  // No timeout — released in onDispose below
        onDispose {
            wl?.let { if (it.isHeld) try { it.release() } catch (_: Exception) {} }
            activity?.window?.clearFlags(
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
            // Restore navigation bars if user exits while fullscreen mode is active
            activity?.window?.decorView?.let { dv ->
                @Suppress("DEPRECATION")
                dv.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
            }
        }
    }

    // Single LaunchedEffect that guarantees attachViews() runs BEFORE play().
    // On channel zap (URL change), we must stop + detachViews() before re-attaching
    // to prevent LibVLC's "Can't set view when already attached" native exception.
    LaunchedEffect(vlcLayout, streamLoadToken, uiState.isCatchupPlayback, useExoFallback, useMpvPrimary) {
        val requestToken = streamLoadToken
        if (useExoFallback || useMpvPrimary) return@LaunchedEffect
        val mp = mediaPlayer ?: run {
            libVlcFailedForCurrentStream = true
            useExoFallback = true
            return@LaunchedEffect
        }
        val lc = libVLC ?: run {
            libVlcFailedForCurrentStream = true
            useExoFallback = true
            return@LaunchedEffect
        }
        val layout = vlcLayout ?: return@LaunchedEffect
        val rawUrl = uiState.streamUrl.ifEmpty { return@LaunchedEffect }
        isPlaying = false
        subtitleTracks = emptyList()
        audioTracks = emptyList()
        runCatching {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        PlayerRecordingBridge.reportStopped()

        // For DVR recordings with content:// URIs, open a fresh PFD every time the
        // URL changes. Close the previous one first to avoid FD leaks.
        val (url, freshPfd) = if (isDvr) {
            dvrPfd?.let { try { it.close() } catch (_: Exception) {} }
            resolveDvrUri(context, rawUrl)
        } else {
            Pair(rawUrl, null)
        }
        dvrPfd = freshPfd
        if (requestToken !== latestStreamLoadToken) return@LaunchedEffect

        try {
            vlcPlaybackMutex.lock()
            try {
                if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
                // Always stop + detach first. On first play mp.isPlaying=false and
                // detachViews() is a no-op if nothing is attached, so this is safe.
                try { mp.stop() } catch (_: Exception) {}
                try { mp.detachViews() } catch (_: Exception) {}
                // Small settle for the native surface/audio sink without making channel zaps feel heavy.
                kotlinx.coroutines.delay(if (isLive) 120L else 80L)
                if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
                mp.attachViews(layout, null, false, false)  // surface MUST be attached first
                val media = buildMedia(
                    libVLC = lc,
                    url = url,
                    isLive = isLive && !uiState.isCatchupPlayback,
                    liveCacheMs = effectiveLiveCacheMs,
                    dropLateFrames = settingsState.vlcDropLateFrames,
                    skipFrames = settingsState.vlcSkipFrames,
                    timeshiftEnabled = liveTimeshiftEnabled,
                    timeshiftPath = liveTimeshiftPath
                )
                mp.media = media
                media.release()
                if (requestToken !== latestStreamLoadToken) return@LaunchedEffect
                mp.play()
                // Report active URL to bridge so RecordingService can detect it and
                // avoid opening a second Xtream connection (TEE mode).
                if (!isDvr) PlayerRecordingBridge.reportPlaying(url)
            } finally {
                vlcPlaybackMutex.unlock()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "LibVLC playback failed: $url")
            runCatching {
                (context.applicationContext as? com.dylandos.iptv.ultimate.DylandosApp)
                    ?.fieldTelemetry
                    ?.recordPlaybackFailure(
                        engine = "libvlc",
                        errorCode = e.javaClass.simpleName,
                        streamType = if (isLive) "live" else "vod",
                        extra = e.message
                    )
            }
            if (e.message?.contains("Decoder", ignoreCase = true) == true ||
                e.message?.contains("codec", ignoreCase = true) == true
            ) {
                runCatching {
                    (context.applicationContext as? com.dylandos.iptv.ultimate.DylandosApp)
                        ?.fieldTelemetry
                        ?.recordDecodeFailure("libvlc", e.message ?: "decode")
                }
            }
            libVlcFailedForCurrentStream = true
            useExoFallback = true
        }
    }

    // TEE recording: observe start request from RecordingService.
    // When DVR starts while this player is streaming the same URL, we reload VLC
    // with :sout=#duplicate so one connection feeds both display and file write.
    val teeRequest by PlayerRecordingBridge.teeRequest.collectAsState()
    LaunchedEffect(teeRequest) {
        val req    = teeRequest ?: return@LaunchedEffect
        if (useExoFallback) { PlayerRecordingBridge.consumeTeeRequest(); return@LaunchedEffect }
        val mp     = mediaPlayer ?: run { PlayerRecordingBridge.consumeTeeRequest(); return@LaunchedEffect }
        val lc     = libVLC     ?: run { PlayerRecordingBridge.consumeTeeRequest(); return@LaunchedEffect }
        val layout = vlcLayout  ?: run { PlayerRecordingBridge.consumeTeeRequest(); return@LaunchedEffect }
        val url    = uiState.streamUrl.ifEmpty { PlayerRecordingBridge.consumeTeeRequest(); return@LaunchedEffect }
        Timber.i("DVR TEE: reloading VLC with #duplicate for recording ${req.recordingId}")
        try {
            vlcPlaybackMutex.lock()
            try {
                mp.stop()
                mp.detachViews()
                delay(120)
                mp.attachViews(layout, null, false, false)
                val media = Media(lc, Uri.parse(url)).apply {
                    val boundedCacheMs = effectiveLiveCacheMs.coerceIn(300, 4_000)
                    addOption(":network-caching=$boundedCacheMs")
                    addOption(":live-caching=$boundedCacheMs")
                    addOption(":clock-jitter=0")
                    addOption(":clock-synchro=0")
                    // Preserve the live stream tuning the normal player applies, and pin the
                    // User-Agent so the #duplicate sout branch authenticates the same way the
                    // display branch did — otherwise the provider can drop the recording leg
                    // and the file ends up empty.
                    addOption(if (settingsState.vlcDropLateFrames) ":drop-late-frames" else ":no-drop-late-frames")
                    addOption(if (settingsState.vlcSkipFrames) ":skip-frames" else ":no-skip-frames")
                    addOption(":http-user-agent=$TEE_RECORDING_USER_AGENT")
                    addOption(":http-reconnect")
                    addOption(buildDuplicateSout(req.outputPath))
                    addOption(":sout-keep")
                }
                mp.media = media
                media.release()
                mp.play()
                if (!isDvr) PlayerRecordingBridge.reportPlaying(url)
            } finally {
                vlcPlaybackMutex.unlock()
            }
            isRecording = true
            activeRecordingId = req.recordingId
        } catch (e: Exception) {
            Timber.e(e, "DVR TEE reload failed")
        }
        PlayerRecordingBridge.consumeTeeRequest()
    }

    // TEE recording: observe stop request — reload VLC without :sout to resume clean playback.
    val teeStopId by PlayerRecordingBridge.teeStopId.collectAsState()
    LaunchedEffect(teeStopId) {
        val stopId = teeStopId ?: return@LaunchedEffect
        if (useExoFallback) { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        if (activeRecordingId != stopId) { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        val mp     = mediaPlayer ?: run { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        val lc     = libVLC     ?: run { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        val layout = vlcLayout  ?: run { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        val url    = uiState.streamUrl.ifEmpty { PlayerRecordingBridge.consumeTeeStop(); return@LaunchedEffect }
        Timber.i("DVR TEE STOP: reloading VLC without :sout for recording $stopId")
        try {
            vlcPlaybackMutex.lock()
            try {
                mp.stop()
                mp.detachViews()
                delay(120)
                mp.attachViews(layout, null, false, false)
                val media = buildMedia(
                    libVLC = lc,
                    url = url,
                    isLive = isLive && !uiState.isCatchupPlayback,
                    liveCacheMs = effectiveLiveCacheMs,
                    dropLateFrames = settingsState.vlcDropLateFrames,
                    skipFrames = settingsState.vlcSkipFrames,
                    timeshiftEnabled = liveTimeshiftEnabled,
                    timeshiftPath = liveTimeshiftPath
                )
                mp.media = media
                media.release()
                mp.play()
                if (!isDvr) PlayerRecordingBridge.reportPlaying(url)
            } finally {
                vlcPlaybackMutex.unlock()
            }
        } catch (e: Exception) {
            Timber.e(e, "DVR TEE stop reload failed")
        }
        isRecording = false
        activeRecordingId = null
        PlayerRecordingBridge.consumeTeeStop()
    }

    // Load stream URL — pass extension so VOD/series use the correct file extension
    LaunchedEffect(streamId, streamType) {
        viewModel.loadStream(streamType, streamId, extension)
    }

    // Subtitle/audio picker state hoisted here so the outer onKeyEvent can toggle them
    // directly when the user presses OK/Enter on control buttons 3 (sub) and 4 (audio).
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showAudioPicker    by remember { mutableStateOf(false) }
    // D-pad cursor index inside the open picker (0-based track index)
    var subtitlePickerFocusIndex by remember { mutableIntStateOf(0) }
    var audioPickerFocusIndex    by remember { mutableIntStateOf(0) }

    // Reset picker cursor to top whenever a picker is freshly opened
    LaunchedEffect(showSubtitlePicker) { if (showSubtitlePicker) subtitlePickerFocusIndex = 0 }
    LaunchedEffect(showAudioPicker)    { if (showAudioPicker)    audioPickerFocusIndex    = 0 }

    val currentSubtitleTracks = remember(
        useMpvPrimary,
        useExoFallback,
        enhancedSubtitleTracks,
        media3SubtitleTracks,
        mpvSubtitleTracks,
        isLive
    ) {
        when {
            useMpvPrimary -> mpvSubtitleTracks
            useExoFallback -> {
                val disableLabel = if (isLive) "Disable Closed Captions" else "Disable subtitles"
                val tracks = if (media3SubtitleTracks.isNotEmpty()) media3SubtitleTracks
                    else if (isLive) listOf(999_001 to "CC 1 (Auto Detect)", 999_002 to "CC 2 (Secondary)")
                    else emptyList()
                listOf(-1 to disableLabel) + tracks
            }
            else -> enhancedSubtitleTracks
        }
    }
    val currentAudioTracks = remember(
        useMpvPrimary,
        useExoFallback,
        audioTracks,
        media3AudioTracks,
        mpvAudioTracks
    ) {
        when {
            useMpvPrimary -> mpvAudioTracks
            useExoFallback -> media3AudioTracks
            else -> audioTracks.filter { it.id >= 0 }.map { it.id to (it.name ?: "Audio ${it.id}") }
        }
    }

    // Any active engine may show the shared control HUD (MPV / LibVLC / Media3).
    val hasPlayerControlsEngine = useMpvPrimary || useExoFallback || mediaPlayer != null

    // Keep the key host focused so D-pad Left/Right update controlIndex (not Compose focus).
    LaunchedEffect(Unit) {
        runCatching { playerKeyFocusRequester.requestFocus() }
    }
    LaunchedEffect(showControls) {
        if (showControls) {
            runCatching { playerKeyFocusRequester.requestFocus() }
        }
    }

    // Number of control buttons for the current mode: ◀◀ ▶/⏸ ▶▶ CC/Sub Audio Rec PiP ⛶ ✕
    // 9 total (indices 0-8) — Close button at index 8 must be reachable by D-pad.
    // Using 8 here previously caused modulo wrap to skip the Close button entirely.
    val controlCount = 9

    // Full-screen container — intercepts ALL D-pad
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(playerKeyFocusRequester)
            .focusProperties {
                // Trap horizontal focus inside the player key host.
                left = playerKeyFocusRequester
                right = playerKeyFocusRequester
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val controlsWereVisible = showControls
                // Any key shows controls
                showControls = true
                controlsInteractionTick++
                when (event.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_HEADSETHOOK,
                    android.view.KeyEvent.KEYCODE_SPACE -> {
                        toggleActivePlayer()
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY -> {
                        playActivePlayer()
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                        pauseActivePlayer()
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> {
                        if (isLive) rewindLiveOrCatchup() else seekActivePlayer(-configuredSkipMs)
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                        if (isLive) fastForwardLiveOrCatchup() else seekActivePlayer(configuredSkipMs)
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_RECORD -> {
                        controlIndex = 5
                        toggleDvrRecordingFromPlayer()
                        return@onKeyEvent true
                    }
                    android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                    android.view.KeyEvent.KEYCODE_LAST_CHANNEL -> {
                        if (isLive && viewModel.zapLastChannel()) return@onKeyEvent true
                    }
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 -> {
                        if (isLive) {
                            val digit = ('0' + (event.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_0))
                            viewModel.onNumberKeyPressed(digit)
                            return@onKeyEvent true
                        }
                    }
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 -> {
                        if (isLive) {
                            val digit = ('0' + (event.nativeKeyEvent.keyCode - android.view.KeyEvent.KEYCODE_NUMPAD_0))
                            viewModel.onNumberKeyPressed(digit)
                            return@onKeyEvent true
                        }
                    }
                }
                when (event.key) {
                    Key.Back -> {
                        when {
                            showSubtitlePicker -> { showSubtitlePicker = false; true }
                            showAudioPicker    -> { showAudioPicker    = false; true }
                            else -> {
                                persistPlaybackPositionSnapshot()
                                stopActivePlayer()
                                navController.popBackStackSafeDebounced()
                                true
                            }
                        }
                    }
                    Key.DirectionLeft -> {
                        when {
                            showSubtitlePicker || showAudioPicker -> true  // pickers are vertical-only
                            isLive && showLiveZapOsd && !controlsWereVisible -> {
                                viewModel.zapChannel(-1)
                                true
                            }
                            showControls -> {
                                // Circular: wrap from 0 back to last button
                                controlIndex = (controlIndex - 1 + controlCount) % controlCount
                                true
                            }
                            else -> {
                                if (hasPlayerControlsEngine) {
                                    if (isLive) rewindLiveOrCatchup() else seekActivePlayer(-configuredSkipMs)
                                    showControls = true
                                    controlsInteractionTick++
                                    true
                                } else false
                            }
                        }
                    }
                    Key.DirectionRight -> {
                        when {
                            showSubtitlePicker || showAudioPicker -> true  // pickers are vertical-only
                            isLive && showLiveZapOsd && !controlsWereVisible -> {
                                viewModel.zapChannel(+1)
                                true
                            }
                            showControls -> {
                                // Circular: wrap from last button back to 0
                                controlIndex = (controlIndex + 1) % controlCount
                                true
                            }
                            else -> {
                                if (hasPlayerControlsEngine) {
                                    if (isLive) fastForwardLiveOrCatchup() else seekActivePlayer(configuredSkipMs)
                                    showControls = true
                                    controlsInteractionTick++
                                    true
                                } else false
                            }
                        }
                    }
                    Key.DirectionCenter, Key.Enter -> {
                        showControls = true
                        // Picker selection takes priority — outer Box must not dispatch to
                        // control buttons while a track picker is open.
                        if (showSubtitlePicker) {
                            val trackId = currentSubtitleTracks
                                .getOrNull(subtitlePickerFocusIndex)
                                ?.first
                                ?: -1
                            selectActiveSubtitleTrack(trackId)
                            showSubtitlePicker = false
                            return@onKeyEvent true
                        }
                        if (showAudioPicker) {
                            val trackId = currentAudioTracks.getOrNull(audioPickerFocusIndex)?.first
                            if (trackId != null) {
                                selectActiveAudioTrack(trackId)
                            }
                            showAudioPicker = false
                            return@onKeyEvent true
                        }
                        if (hasPlayerControlsEngine) {
                            when (controlIndex) {
                                0 -> {
                                    if (isLive) {
                                        rewindLiveOrCatchup()
                                    } else {
                                        seekActivePlayer(-configuredSkipMs)
                                    }
                                }
                                1 -> {
                                    toggleActivePlayer()
                                }
                                2 -> {
                                    if (isLive) fastForwardLiveOrCatchup()
                                    else seekActivePlayer(configuredSkipMs)
                                }
                                3 -> {
                                    when {
                                        useExoFallback || useMpvPrimary ->
                                            showSubtitlePicker = !showSubtitlePicker
                                        else -> {
                                            subtitleTracks = try {
                                                mediaPlayer?.spuTracks?.toList() ?: emptyList()
                                            } catch (_: Exception) {
                                                emptyList()
                                            }
                                            showSubtitlePicker = !showSubtitlePicker
                                        }
                                    }
                                    if (showSubtitlePicker) {
                                        val activeId = when {
                                            useMpvPrimary -> mpvPlaybackState.currentSubtitleTrack
                                            useExoFallback -> selectedMedia3SubtitleId
                                            else -> try { mediaPlayer?.spuTrack ?: -1 } catch (_: Exception) { -1 }
                                        }
                                        val idx = currentSubtitleTracks.indexOfFirst { it.first == activeId }
                                        subtitlePickerFocusIndex = if (idx >= 0) idx else 0
                                    }
                                }
                                4 -> {
                                    when {
                                        useExoFallback || useMpvPrimary ->
                                            showAudioPicker = !showAudioPicker
                                        else -> {
                                            audioTracks = try {
                                                mediaPlayer?.audioTracks?.toList() ?: emptyList()
                                            } catch (_: Exception) {
                                                emptyList()
                                            }
                                            showAudioPicker = !showAudioPicker
                                        }
                                    }
                                    if (showAudioPicker) {
                                        val activeId = when {
                                            useMpvPrimary -> mpvPlaybackState.currentAudioTrack
                                            useExoFallback -> selectedMedia3AudioId
                                            else -> try { mediaPlayer?.audioTrack ?: -1 } catch (_: Exception) { -1 }
                                        }
                                        val idx = currentAudioTracks.indexOfFirst { it.first == activeId }
                                        audioPickerFocusIndex = if (idx >= 0) idx else 0
                                    }
                                }
                                5 -> {
                                    toggleDvrRecordingFromPlayer()
                                }
                                6 -> {
                                    // Picture-in-Picture: the LibVLC surface keeps rendering;
                                    // MainActivity.onUserLeaveHint handles the API call, but we
                                    // also trigger directly here for the remote button press path.
                                    val activity = context as? android.app.Activity
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                        && activity != null) {
                                        val params = android.app.PictureInPictureParams.Builder()
                                            .setAspectRatio(android.util.Rational(16, 9))
                                            .build()
                                        activity.enterPictureInPictureMode(params)
                                    }
                                }
                                7 -> {
                                    // Fullscreen toggle — hides Android navigation + status bars
                                    // for a true cinema-mode immersive experience.
                                    val activity = context as? android.app.Activity
                                    if (activity != null) {
                                        val decorView = activity.window.decorView
                                        if (isFullscreen) {
                                            @Suppress("DEPRECATION")
                                            decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                            isFullscreen = false
                                        } else {
                                            @Suppress("DEPRECATION")
                                            decorView.systemUiVisibility = (
                                                android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                                                android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                            )
                                            isFullscreen = true
                                        }
                                    }
                                }
                                8 -> {
                                    persistPlaybackPositionSnapshot()
                                    stopActivePlayer()
                                    navController.popBackStackSafeDebounced()
                                }
                            }
                        }
                        true  // Always consume — prevents system Back on some Firestick remotes
                    }
                    Key.DirectionUp -> {
                        when {
                            showSubtitlePicker -> {
                                subtitlePickerFocusIndex = (subtitlePickerFocusIndex - 1).coerceAtLeast(0)
                                true
                            }
                            showAudioPicker -> {
                                audioPickerFocusIndex = (audioPickerFocusIndex - 1).coerceAtLeast(0)
                                true
                            }
                            isLive -> {
                                showControls = true
                                viewModel.zapChannel(-1)
                                true
                            }
                            else -> {
                                // S-018: VOD/DVR — UP key raises volume by 10 (cap 200)
                                changeActiveVolume(10)
                                showControls = true
                                true
                            }
                        }
                    }
                    Key.DirectionDown -> {
                        when {
                            showSubtitlePicker -> {
                                val maxIdx = (currentSubtitleTracks.size - 1).coerceAtLeast(0)
                                subtitlePickerFocusIndex = (subtitlePickerFocusIndex + 1).coerceAtMost(maxIdx)
                                true
                            }
                            showAudioPicker -> {
                                val maxIdx = (currentAudioTracks.size - 1).coerceAtLeast(0)
                                audioPickerFocusIndex = (audioPickerFocusIndex + 1).coerceAtMost(maxIdx)
                                true
                            }
                            isLive -> {
                                showControls = true
                                viewModel.zapChannel(+1)
                                true
                            }
                            else -> {
                                // S-018: VOD/DVR — DOWN key lowers volume by 10 (floor 0)
                                changeActiveVolume(-10)
                                showControls = true
                                true
                            }
                        }
                    }
                    // ── FireStick / Android media remote keys ─────────────────────
                    // The dedicated Play/Pause, Rewind, and FastForward physical buttons
                    // on the Fire TV remote and most Android media remotes send these
                    // key codes. Map them directly to player actions regardless of
                    // whether the controls overlay is visible.
                    Key.MediaPlayPause -> {
                        showControls = true
                        toggleActivePlayer()
                        true
                    }
                    Key.MediaPlay -> {
                        showControls = true
                        playActivePlayer()
                        true
                    }
                    Key.MediaPause -> {
                        showControls = true
                        pauseActivePlayer()
                        true
                    }
                    // Rewind / fast-forward — seek ±10 s for quick skips.
                    // Use MediaRewind / MediaFastForward (KEYCODE 89/90 on the remote).
                    Key.MediaRewind -> {
                        showControls = true
                        controlIndex = 0
                        if (isLive) rewindLiveOrCatchup() else seekActivePlayer(-configuredSkipMs)
                        true
                    }
                    Key.MediaFastForward -> {
                        showControls = true
                        controlIndex = 2
                        if (isLive) fastForwardLiveOrCatchup() else seekActivePlayer(configuredSkipMs)
                        true
                    }
                    // Physical / CEC Record — must NOT fall through to focus Rewind.
                    Key.MediaRecord -> {
                        showControls = true
                        controlIndex = 5
                        toggleDvrRecordingFromPlayer()
                        true
                    }
                    // MediaSkipBackward / MediaSkipForward are KEYCODE 272/273 (Fire TV "chapter skip")
                    // — treat as ±30 s for a longer jump.
                    Key.MediaSkipBackward -> {
                        showControls = true
                        if (isLive) rewindLiveOrCatchup(configuredSkipMs * 3)
                        else seekActivePlayer(-configuredSkipMs * 3)
                        true
                    }
                    Key.MediaSkipForward -> {
                        showControls = true
                        if (isLive) fastForwardLiveOrCatchup(configuredSkipMs * 3)
                        else seekActivePlayer(configuredSkipMs * 3)
                        true
                    }
                    // MediaStop — stop and go back
                    Key.MediaStop -> {
                        persistPlaybackPositionSnapshot()
                        stopActivePlayer()
                        navController.popBackStackSafeDebounced()
                        true
                    }
                    // S-017: MENU key — cycle aspect ratio (short press) + show stream info (long press handled as toggle)
                    Key.Menu -> {
                        if (showStreamInfo) {
                            // Second MENU press: hide stream info and reset aspect to auto
                            showStreamInfo = false
                        } else if (aspectOsdVisible) {
                            // MENU pressed while OSD showing → show stream info instead
                            aspectOsdVisible = false
                            showStreamInfo = true
                        } else {
                            // First MENU press: cycle aspect ratio and show OSD
                            aspectIndex = (aspectIndex + 1) % aspectRatioOptions.size
                            val ratio = aspectRatioOptions[aspectIndex]
                            try { mediaPlayer?.aspectRatio = ratio } catch (_: Exception) {}
                            aspectOsdVisible = true
                        }
                        showControls = true
                        true
                    }
                    else -> false
                }
            }
            .focusable()
    ) {
        when {
            uiState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CircularProgressIndicator(color = Accent)
                        Text("Loading stream...", color = TextPrimary)
                    }
                }
            }

            // vlcInitError catches LibVLC native failures; uiState.error catches network/ViewModel failures
            vlcInitError != null || uiState.error != null -> {
                val displayError = vlcInitError ?: uiState.error ?: "Unknown error"
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Icon(Icons.Default.Error, "Error", tint = StatusError, modifier = Modifier.size(64.dp))
                        Text("Playback Error", color = TextPrimary, fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.headlineSmall)
                        Text(displayError, color = TextSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = {
                                    vlcInitError = null
                                    autoRecoveryAttempts = 0
                                    useMpvPrimary = preferMpvVod
                                    useExoFallback = false
                                    libVlcFailedForCurrentStream = false
                                    // Replay retries the complete provider URL + engine ladder;
                                    // other stream types simply restart their current URL.
                                    if (uiState.isCatchupPlayback) {
                                        viewModel.restartCatchupRecovery()
                                    } else {
                                        viewModel.retryStream()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentSecondary)
                            ) { Text(if (uiState.isCatchupPlayback) "Retry Replay" else "Retry") }
                            Button(
                                onClick = { navController.popBackStackSafeDebounced() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent)
                            ) { Text("Go Back") }
                        }
                    }
                }
            }

            else -> {
                if (useMpvPrimary) {
                    MpvSurface(
                        mpvWrapper = mpvWrapper,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (useExoFallback) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                useController = false
                                // Android phones must preserve the source frame rather than
                                // stretching video to fill a tall portrait viewport.
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                isFocusable = false
                                isFocusableInTouchMode = false
                                descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                player = ensureExoPlayer()
                                applyMedia3SubtitleStyle(this, settingsState)
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            }
                        },
                        update = {
                            it.player = ensureExoPlayer()
                            applyMedia3SubtitleStyle(it, settingsState)
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    AndroidView(
                        factory = { ctx ->
                            VLCVideoLayout(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                                vlcLayout = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Premium/phone controls: a tap exposes the same control HUD that the
                // Fire TV remote opens; double-tap skips, long-press cycles aspect.
                // Kept below the HUD so visible buttons retain their normal touch actions.
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(isLive, configuredSkipMs) {
                            detectTapGestures(
                                onTap = {
                                    showControls = !showControls
                                    controlsInteractionTick++
                                },
                                onDoubleTap = { offset ->
                                    showControls = true
                                    controlsInteractionTick++
                                    if (offset.x < size.width / 2f) {
                                        if (isLive) rewindLiveOrCatchup() else seekActivePlayer(-configuredSkipMs)
                                    } else {
                                        if (isLive) fastForwardLiveOrCatchup() else seekActivePlayer(configuredSkipMs)
                                    }
                                },
                                onLongPress = {
                                    aspectIndex = (aspectIndex + 1) % aspectRatioOptions.size
                                    val ratio = aspectRatioOptions[aspectIndex]
                                    runCatching { mediaPlayer?.aspectRatio = ratio }
                                    aspectOsdVisible = true
                                    showControls = true
                                }
                            )
                        }
                )

                // ── Premium Zap OSD (Mini-EPG) ────────────────────────────────
                // High-end live zapping / channel info (bottom-third) — EPG from short EPG
                if (isLive && uiState.showChannelJumpOverlay) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(28.dp)
                            .background(Color(0xE6101014), RoundedCornerShape(10.dp))
                            .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = uiState.channelNumberInput.ifEmpty { "—" },
                            color = Accent,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                AnimatedVisibility(
                    visible = isLive && showLiveZapOsd && uiState.streamUrl.isNotEmpty(),
                    enter = slideInVertically(initialOffsetY = { it / 2 }, animationSpec = tween(250)) + fadeIn(animationSpec = tween(200)),
                    exit = slideOutVertically(targetOffsetY = { it / 2 }, animationSpec = tween(300)) + fadeOut(animationSpec = tween(250)),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = if (showControls) 150.dp else 12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.94f)
                            .widthIn(max = 1_080.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color(0xE8151820),
                                        Color(0xF2080A0E)
                                    )
                                )
                            )
                            .border(1.dp, Accent.copy(alpha = 0.25f), RoundedCornerShape(18.dp))
                            .padding(horizontal = 28.dp, vertical = 20.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // Current Channel & Program Info
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (uiState.streamIconUrl.isNotEmpty()) {
                                        coil.compose.AsyncImage(
                                            model = uiState.streamIconUrl,
                                            contentDescription = uiState.streamTitle,
                                            modifier = Modifier
                                                .size(64.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(Color(0xFF1E1E1E)),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                                        )
                                        Spacer(Modifier.width(16.dp))
                                    }
                                    Column {
                                        Text(
                                            text = uiState.streamTitle,
                                            color = Color.White,
                                            fontSize = 28.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = 1
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = uiState.liveEpgTitle ?: "Program info loading…",
                                            color = Accent,
                                            fontSize = 18.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1
                                        )
                                        if (uiState.liveEpgTime != null) {
                                            Text(
                                                text = uiState.liveEpgTime!!,
                                                color = TextSecondary,
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(16.dp))
                                // Progress bar for current program
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.85f)
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFF333333))
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth(fraction = uiState.liveEpgProgress)
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(2.dp))
                                            .background(Brush.horizontalGradient(listOf(Color(0xFF00C6FF), Color(0xFF0072FF))))
                                    )
                                }
                            }

                            // Upcoming shows + Smart Neighbor Peek.
                            if (uiState.upcomingPrograms.isNotEmpty() || uiState.neighborPeek.isNotEmpty()) {
                                Column(
                                    modifier = Modifier.width(360.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (uiState.upcomingPrograms.isNotEmpty()) {
                                        Text(
                                            "UPCOMING",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp
                                        )
                                        uiState.upcomingPrograms.take(2).forEach { prog ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 12.dp, vertical = 7.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val tStr = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatShortTime(
                                                    prog.startTimestamp * 1000L,
                                                    settingsState.epgTimeFormat
                                                )
                                                Text(
                                                    text = tStr,
                                                    color = Accent,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.width(64.dp)
                                                )
                                                Text(
                                                    text = prog.title,
                                                    color = TextPrimary,
                                                    fontSize = 14.sp,
                                                    maxLines = 1,
                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }
                                    if (uiState.neighborPeek.isNotEmpty()) {
                                        Text(
                                            "NEARBY CHANNELS",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.padding(top = if (uiState.upcomingPrograms.isNotEmpty()) 6.dp else 0.dp)
                                        )
                                        uiState.neighborPeek.forEach { peek ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .background(
                                                        if (peek.isCurrent) Accent.copy(alpha = 0.22f) else Color(0x22FFFFFF),
                                                        RoundedCornerShape(6.dp)
                                                    )
                                                    .border(
                                                        width = if (peek.isCurrent) 1.dp else 0.dp,
                                                        color = if (peek.isCurrent) Accent else Color.Transparent,
                                                        shape = RoundedCornerShape(6.dp)
                                                    )
                                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = peek.positionLabel,
                                                    color = if (peek.isCurrent) Accent else TextSecondary,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.width(38.dp)
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        text = peek.channelName,
                                                        color = TextPrimary,
                                                        fontSize = 13.sp,
                                                        fontWeight = if (peek.isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = peek.programTitle ?: "Guide pending",
                                                        color = TextTertiary,
                                                        fontSize = 11.sp,
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Controls overlay (animated slide-in from bottom)
                AnimatedVisibility(
                    visible = showControls,
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    if (hasPlayerControlsEngine) {
                        PlayerControls(
                            title = uiState.streamTitle,
                            programTitle = uiState.liveEpgTitle,
                            programTime = uiState.liveEpgTime,
                            isLive = isLive,
                            isCatchupPlayback = uiState.isCatchupPlayback,
                            canRestartCurrentProgram = uiState.canRestartCurrentProgram,
                            isPlaying = isPlaying,
                            isRecording = isRecording,
                            isFullscreen = isFullscreen,
                            focusedIndex = controlIndex,
                            mediaPlayer = mediaPlayer,
                            useExoFallback = useExoFallback,
                            media3SubtitlesEnabled = media3SubtitlesEnabled,
                            media3TextTrackCount = media3TextTrackCount,
                            positionMs = currentPositionMs(),
                            durationMs = currentDurationMs(),
                            timeTick = timeTick,
                            subtitleTracks = currentSubtitleTracks,
                            audioTracks = currentAudioTracks,
                            selectedSubtitleId = when {
                                useMpvPrimary -> mpvPlaybackState.currentSubtitleTrack
                                useExoFallback -> selectedMedia3SubtitleId
                                else -> try { mediaPlayer?.spuTrack ?: -1 } catch (_: Exception) { -1 }
                            },
                            selectedAudioId = when {
                                useMpvPrimary -> mpvPlaybackState.currentAudioTrack
                                useExoFallback -> selectedMedia3AudioId
                                else -> try { mediaPlayer?.audioTrack ?: -1 } catch (_: Exception) { -1 }
                            },
                            showSubtitlePicker = showSubtitlePicker,
                            showAudioPicker    = showAudioPicker,
                            subtitlePickerFocusIndex = subtitlePickerFocusIndex,
                            audioPickerFocusIndex    = audioPickerFocusIndex,
                            onFocusChanged = { controlIndex = it },
                            onSubtitlePickerChange = { showSubtitlePicker = it },
                            onAudioPickerChange    = { showAudioPicker = it },
                            onControlAction = { idx ->
                                showControls = true
                                controlsInteractionTick++
                                when (idx) {
                                    0 -> {
                                        if (settingsState.providerReplayEnabled && isLive && !uiState.isCatchupPlayback && (uiState.canRestartCurrentProgram || uiState.catchupPrograms.isNotEmpty())) {
                                            showCatchupSheet = true
                                        } else if (isLive) {
                                            rewindLiveOrCatchup()
                                        } else {
                                            seekActivePlayer(-configuredSkipMs)
                                        }
                                    }
                                    1 -> {
                                        toggleActivePlayer()
                                    }
                                    2 -> {
                                        if (isLive) fastForwardLiveOrCatchup()
                                        else seekActivePlayer(configuredSkipMs)
                                    }
                                    // 3: CC/Subtitle — first press toggles quick inline picker;
                                    //    second press (while picker is open) opens full sheet (LibVLC only).
                                    3 -> {
                                        when {
                                            useExoFallback || useMpvPrimary ->
                                                showSubtitlePicker = !showSubtitlePicker
                                            showSubtitlePicker -> {
                                                showSubtitlePicker = false
                                                showFullSubtitleSheet = true
                                            }
                                            else -> showSubtitlePicker = true
                                        }
                                    }
                                    4 -> showAudioPicker = !showAudioPicker
                                    5 -> {
                                        toggleDvrRecordingFromPlayer()
                                    }
                                    6 -> {
                                        val activity = context as? android.app.Activity
                                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
                                            && activity != null) {
                                            val params = android.app.PictureInPictureParams.Builder()
                                                .setAspectRatio(android.util.Rational(16, 9))
                                                .build()
                                            activity.enterPictureInPictureMode(params)
                                        }
                                    }
                                    7 -> {
                                        val activity = context as? android.app.Activity
                                        if (activity != null) {
                                            val decorView = activity.window.decorView
                                            if (isFullscreen) {
                                                @Suppress("DEPRECATION")
                                                decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
                                                isFullscreen = false
                                            } else {
                                                @Suppress("DEPRECATION")
                                                decorView.systemUiVisibility = (
                                                    android.view.View.SYSTEM_UI_FLAG_FULLSCREEN or
                                                    android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                                    android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                                                )
                                                isFullscreen = true
                                            }
                                        }
                                    }
                                    8 -> {
                                        persistPlaybackPositionSnapshot()
                                        stopActivePlayer()
                                        navController.popBackStackSafeDebounced()
                                    }
                                }
                            },
                            onBack = {
                                persistPlaybackPositionSnapshot()
                                stopActivePlayer()
                                navController.popBackStackSafeDebounced()
                            },
                            onSelectSubtitle = { trackId ->
                                selectActiveSubtitleTrack(trackId)
                                showSubtitlePicker = false
                            },
                            onSelectAudio = { trackId ->
                                selectActiveAudioTrack(trackId)
                                showAudioPicker = false
                            },
                            onHide = { showControls = false }
                        )
                    }
                }

                // Title overlay (top) always visible for 3 s on start
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    val activePlayerLabel = when {
                        useMpvPrimary -> "MPV"
                        useExoFallback && liveTimeshiftEnabled -> "MEDIA3 • USB TIMESHIFT"
                        useExoFallback -> "MEDIA3 FALLBACK"
                        else -> "VLC"
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xAA000000))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Channel logo (only shown for live TV streams when an icon URL is available)
                        if (uiState.streamIconUrl.isNotEmpty()) {
                            coil.compose.AsyncImage(
                                model = uiState.streamIconUrl,
                                contentDescription = uiState.streamTitle,
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Text(
                            text = uiState.streamTitle,
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (isLive) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                color = StatusError.copy(alpha = 0.8f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    "LIVE",
                                    color = Color.White,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (liveTimeshiftEnabled) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    color = StatusInfo.copy(alpha = 0.18f),
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, StatusInfo.copy(alpha = 0.40f))
                                ) {
                                    Text(
                                        if (!isPlaying) "TIMESHIFT PAUSED" else "USB TIMESHIFT",
                                        color = StatusInfo,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Accent.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(4.dp),
                            border = BorderStroke(1.dp, Accent.copy(alpha = 0.35f))
                        ) {
                            Text(
                                "PLAYER: $activePlayerLabel",
                                color = Accent,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        if (settingsState.showClockInPlayer) {
                            Spacer(modifier = Modifier.width(10.dp))
                            val playerClock = com.dylandos.iptv.ultimate.ui.util.rememberLiveClock(settingsState.epgTimeFormat)
                            Surface(
                                color = BgSurface2,
                                shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, BorderDefault)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.Default.Schedule, contentDescription = null, tint = Accent, modifier = Modifier.size(12.dp))
                                    Text(playerClock, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (hasPlayerControlsEngine) {
                            Spacer(modifier = Modifier.weight(1f))
                            PlaybackTimeReadout(
                                isLive = isLive,
                                positionMs = currentPositionMs(),
                                durationMs = currentDurationMs(),
                                timeTick = timeTick,
                                textColor = TextPrimary,
                                subtextColor = TextSecondary,
                                mainFont = 14.sp,
                                alignEnd = true
                            )
                        }
                    }
                }

                // ── Full-featured SubtitlePickerSheet overlay ──────────────────────
                // Uses LibVlcSubtitleManager to enumerate/select embedded tracks and
                // expose subtitle appearance controls without leaving playback.
                if (showFullSubtitleSheet && mediaPlayer != null) {
                    val subtitleManager = remember(mediaPlayer) {
                        com.dylandos.iptv.ultimate.player.subtitle.LibVlcSubtitleManager(mediaPlayer)
                    }
                    com.dylandos.iptv.ultimate.player.subtitle.SubtitlePickerSheet(
                        subtitleManager = subtitleManager,
                        isLiveTv = isLive,
                        onDismiss = { showFullSubtitleSheet = false }
                    )
                }

                if (showCatchupSheet) {
                    AlertDialog(
                        onDismissRequest = { showCatchupSheet = false },
                        title = {
                            Text(
                                text = "Catch-Up / Timeshift (${uiState.streamTitle})",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        },
                        text = {
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (uiState.canRestartCurrentProgram) {
                                    item {
                                        Surface(
                                            onClick = {
                                                showCatchupSheet = false
                                                viewModel.restartCurrentProgram()
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = AccentSurface,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(12.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Icon(Icons.Default.Replay, null, tint = Accent, modifier = Modifier.size(18.dp))
                                                Column {
                                                    Text("Restart Current Program", color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                                    uiState.liveEpgTitle?.let {
                                                        Text(it, color = TextPrimary, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (uiState.catchupPrograms.isEmpty() && !uiState.canRestartCurrentProgram) {
                                    item {
                                        Text(
                                            "No past program recordings found for this channel.",
                                            color = TextSecondary,
                                            fontSize = 12.sp,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                } else {
                                    items(uiState.catchupPrograms) { prog ->
                                        val startFmt = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault()).format(java.util.Date(prog.startTimestamp * 1000L))
                                        val stopFmt = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(prog.stopTimestamp * 1000L))
                                        val durMin = ((prog.stopTimestamp - prog.startTimestamp) / 60L).coerceAtLeast(1L)
                                        Surface(
                                            onClick = {
                                                showCatchupSheet = false
                                                val token = viewModel.buildTimeShiftToken(
                                                    streamId = streamId.toIntOrNull() ?: uiState.currentLiveStreamId ?: 0,
                                                    startMs = prog.startTimestamp * 1000L,
                                                    endMs = prog.stopTimestamp * 1000L
                                                )
                                                viewModel.setPendingPlaybackTitle("${uiState.streamTitle} - ${prog.title} ($startFmt)")
                                                navController.navigateSafe(Screen.Player.createRoute("timeshift", token, "ts"))
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            color = BgSurface2,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(prog.title.ifBlank { "Program" }, color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                                    Text("$startFmt - $stopFmt (${durMin}m)", color = TextTertiary, fontSize = 11.sp)
                                                }
                                                Icon(Icons.Default.PlayArrow, "Play", tint = Accent, modifier = Modifier.size(20.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { showCatchupSheet = false }) { Text("Close") }
                        },
                        containerColor = BgSurface,
                        titleContentColor = TextPrimary
                    )
                }

                // S-017: Aspect ratio OSD — shows current ratio briefly then auto-dismisses
                LaunchedEffect(aspectOsdVisible) {
                    if (aspectOsdVisible) {
                        delay(1500)
                        aspectOsdVisible = false
                    }
                }
                AnimatedVisibility(
                    visible = aspectOsdVisible,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(500)),
                    modifier = Modifier.align(Alignment.TopEnd)
                ) {
                    Surface(
                        color = Color(0xCC000000),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(top = 56.dp, end = 16.dp)
                    ) {
                        Text(
                            text = "Aspect: " + (aspectRatioOptions[aspectIndex] ?: "Auto"),
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }

                // S-028: Stream info overlay — shows codec, resolution, bitrate (MENU key toggle)
                AnimatedVisibility(
                    visible = showStreamInfo && hasPlayerControlsEngine,
                    enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { -it / 2 },
                    exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -it / 2 },
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    if (useMpvPrimary) {
                        val _tick = timeTick
                        @Suppress("unused") val ignored = _tick
                        val urlDisplay = uiState.streamUrl.let { u ->
                            if (u.length > 60) u.take(60) + "..." else u
                        }
                        val res = if (mpvPlaybackState.videoWidth > 0 && mpvPlaybackState.videoHeight > 0) {
                            "${mpvPlaybackState.videoWidth}x${mpvPlaybackState.videoHeight}"
                        } else "—"
                        val videoStr = "Video: ${mpvPlaybackState.videoCodec.ifBlank { "—" }} @ $res"
                        val audioStr = "Audio: ${mpvPlaybackState.audioCodec.ifBlank { "—" }}"
                        val hwStr = "HW: ${mpvPlaybackState.hwDecoder.ifBlank { "—" }}"
                        val posStr = formatPlaybackTimeMs(mpvPlaybackState.position)
                        Surface(
                            color = Color(0xDD0D1117),
                            shape = RoundedCornerShape(0.dp, 0.dp, 12.dp, 0.dp),
                            modifier = Modifier.padding(top = if (showControls) 48.dp else 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("STREAM INFO (MPV)", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp)
                                Text("URL: $urlDisplay", color = TextTertiary, fontSize = 9.sp)
                                Text(videoStr, color = TextSecondary, fontSize = 10.sp)
                                Text(audioStr, color = TextSecondary, fontSize = 10.sp)
                                Text(hwStr, color = TextSecondary, fontSize = 10.sp)
                                Text(
                                    "FPS: ${"%.1f".format(mpvPlaybackState.fps)}  |  Dropped: ${mpvPlaybackState.droppedFrames}  |  Pos: $posStr",
                                    color = TextSecondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    } else if (mediaPlayer != null) {
                        val _tick = timeTick
                        @Suppress("unused") val ignored = _tick
                        val videoTrack = try { mediaPlayer.videoTracks?.firstOrNull { it.id >= 0 } } catch (_: Exception) { null }
                        val audioTrack = try { mediaPlayer.audioTracks?.firstOrNull { it.id >= 0 } } catch (_: Exception) { null }
                        val volStr = try { mediaPlayer.volume.toString() } catch (_: Exception) { "-" }
                        val posStr = formatPlaybackTimeMs(try { mediaPlayer.time } catch (_: Exception) { 0L })
                        val urlDisplay = uiState.streamUrl.let { u ->
                            if (u.length > 60) u.take(60) + "..." else u
                        }
                        val videoStr = videoTrack?.let { "Video: " + it.name.ifBlank { "Track ${it.id}" } }
                        val audioStr = audioTrack?.let { "Audio: " + it.name.ifBlank { "Track ${it.id}" } }
                        val aspectLabel = aspectRatioOptions[aspectIndex] ?: "Auto"
                        Surface(
                            color = Color(0xDD0D1117),
                            shape = RoundedCornerShape(0.dp, 0.dp, 12.dp, 0.dp),
                            modifier = Modifier.padding(top = if (showControls) 48.dp else 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text("STREAM INFO", color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp)
                                Text("URL: $urlDisplay", color = TextTertiary, fontSize = 9.sp)
                                if (videoStr != null) {
                                    Text(videoStr, color = TextSecondary, fontSize = 10.sp)
                                }
                                if (audioStr != null) {
                                    Text(audioStr, color = TextSecondary, fontSize = 10.sp)
                                }
                                Text("Volume: $volStr%", color = TextSecondary, fontSize = 10.sp)
                                Text("Aspect: $aspectLabel  |  Pos: $posStr",
                                    color = TextSecondary, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Controls Overlay ──────────────────────────────────────────────────────────

@Composable
private fun PlayerControls(
    title: String,
    programTitle: String?,
    programTime: String?,
    isLive: Boolean,
    isCatchupPlayback: Boolean,
    canRestartCurrentProgram: Boolean,
    isPlaying: Boolean,
    isRecording: Boolean,
    isFullscreen: Boolean,
    focusedIndex: Int,
    mediaPlayer: MediaPlayer?,
    useExoFallback: Boolean,
    media3SubtitlesEnabled: Boolean,
    media3TextTrackCount: Int,
    positionMs: Long,
    durationMs: Long,
    timeTick: Int,
    subtitleTracks: List<Pair<Int, String>>,
    audioTracks: List<Pair<Int, String>>,
    selectedSubtitleId: Int,
    selectedAudioId: Int,
    showSubtitlePicker: Boolean,
    showAudioPicker: Boolean,
    subtitlePickerFocusIndex: Int,
    audioPickerFocusIndex: Int,
    onFocusChanged: (Int) -> Unit,
    onSubtitlePickerChange: (Boolean) -> Unit,
    onAudioPickerChange: (Boolean) -> Unit,
    onControlAction: (Int) -> Unit,
    onBack: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onHide: () -> Unit
    ) {
    @Suppress("unused")
    val _tick = timeTick
    val secondaryTitle = remember(isLive, programTitle, programTime) {
        if (!isLive) {
            ""
        } else {
            listOfNotNull(
                programTitle?.trim()?.takeIf { it.isNotBlank() },
                programTime?.trim()?.takeIf { it.isNotBlank() }
            ).joinToString("  |  ")
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth(0.94f)
            .widthIn(max = 980.dp)
            .padding(bottom = 20.dp)
            .background(Color(0xE6101014), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x334DD0E1), RoundedCornerShape(18.dp))
            .padding(vertical = 14.dp, horizontal = 20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = title.ifBlank { "Now Playing" },
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (isLive) {
                    Text(
                        text = secondaryTitle.ifBlank { "Guide info loading" },
                        color = if (secondaryTitle.isBlank()) TextTertiary else Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1.2f)
                    )
                }
            }

            // Progress bar (VOD / DVR; live usually has no duration)
            if (!isLive || isCatchupPlayback) {
                val position = if (durationMs > 0L) {
                    (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
                } else 0f
                LinearProgressIndicator(
                    progress = { position },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = Accent,
                    trackColor = BgSurface3
                )
            }

            PlaybackTimeReadout(
                isLive = isLive,
                positionMs = positionMs,
                durationMs = durationMs,
                timeTick = timeTick,
                textColor = TextPrimary,
                subtextColor = TextSecondary,
                mainFont = 13.sp,
                alignEnd = false
            )

            // Controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 0: Rewind / Restart / Catch-up
                PlayerControlBtn(
                    icon = if (isLive && !isCatchupPlayback) Icons.Default.History else Icons.Default.FastRewind,
                    label = when {
                        isLive && !isCatchupPlayback -> "Catch-up"
                        else -> "Rewind"
                    },
                    index = 0,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(0) },
                    isActive = isLive && !isCatchupPlayback,
                    onClick = { onControlAction(0) }
                )

                // 1: Play / Pause
                PlayerControlBtn(
                    icon = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    label = if (isPlaying) "Pause" else "Play",
                    index = 1,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(1) },
                    isPrimary = true,
                    onClick = { onControlAction(1) }
                )

                // 2: Fast-forward
                PlayerControlBtn(
                    icon = Icons.Default.FastForward,
                    label = "Forward",
                    index = 2,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(2) },
                    onClick = { onControlAction(2) }
                )

                // Divider visual
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(BorderDefault))

                // 3: Subtitles (VOD) / Closed Caption (Live)
                PlayerControlBtn(
                    icon = Icons.Default.ClosedCaption,
                    label = when {
                        useExoFallback && media3SubtitlesEnabled -> if (isLive) "CC On" else "Sub On"
                        useExoFallback -> if (isLive) "CC Off" else "Sub Off"
                        isLive -> "CC"
                        else -> "Sub"
                    },
                    index = 3,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(3) },
                    isActive = if (useExoFallback) media3SubtitlesEnabled else (subtitleTracks.isNotEmpty()),
                    onClick = { onControlAction(3) }
                )

                // 4: Audio track
                PlayerControlBtn(
                    icon = Icons.Default.RecordVoiceOver,
                    label = "Audio",
                    index = 4,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(4) },
                    onClick = { onControlAction(4) }
                )

                // Divider visual
                Box(modifier = Modifier.width(1.dp).height(32.dp).background(BorderDefault))

                // 5: Record / Stop Recording
                PlayerControlBtn(
                    icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    label = if (isRecording) "Stop REC" else "REC",
                    index = 5,
                    focusedIndex = focusedIndex,
                    isActive = isRecording,
                    onFocused = { onFocusChanged(5) },
                    tint = StatusError,
                    onClick = { onControlAction(5) }
                )

                // 6: Picture-in-Picture
                PlayerControlBtn(
                    icon = Icons.Default.PictureInPicture,
                    label = "PiP",
                    index = 6,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(6) },
                    onClick = { onControlAction(6) }
                )

                // 7: Fullscreen / Exit Fullscreen
                PlayerControlBtn(
                    icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    label = if (isFullscreen) "Exit FS" else "Full",
                    index = 7,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(7) },
                    onClick = { onControlAction(7) }
                )

                // 8: Close / Back
                PlayerControlBtn(
                    icon = Icons.Default.Close,
                    label = "Close",
                    index = 8,
                    focusedIndex = focusedIndex,
                    onFocused = { onFocusChanged(8) },
                    onClick = { onControlAction(8) }
                )
            }
        }

        // Subtitle / CC track picker — shows feedback even when no tracks found
        if (showSubtitlePicker) {
            if (mediaPlayer != null && !useExoFallback) {
                EnhancedSubtitlePicker(
                    tracks = subtitleTracks,
                    selectedId = selectedSubtitleId,
                    focusedItemIndex = subtitlePickerFocusIndex,
                    isLive = isLive,
                    mediaPlayer = mediaPlayer,
                    onSelect = onSelectSubtitle,
                    onDismiss = { onSubtitlePickerChange(false) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                TrackPickerDropdown(
                    tracks = subtitleTracks,
                    selectedId = selectedSubtitleId,
                    focusedItemIndex = subtitlePickerFocusIndex,
                    onSelect = onSelectSubtitle,
                    onDismiss = { onSubtitlePickerChange(false) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        // Audio track picker
        if (showAudioPicker) {
            if (audioTracks.isNotEmpty()) {
                TrackPickerDropdown(
                    tracks = audioTracks,
                    selectedId = selectedAudioId,
                    focusedItemIndex = audioPickerFocusIndex,
                    onSelect = onSelectAudio,
                    onDismiss = { onAudioPickerChange(false) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            } else {
                NoTracksCard(
                    message = "No audio tracks found — stream may still be loading",
                    onDismiss = { onAudioPickerChange(false) },
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }
}

// ── Control Button ────────────────────────────────────────────────────────────

@Composable
private fun PlayerControlBtn(
    icon: ImageVector,
    label: String,
    index: Int,
    focusedIndex: Int,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    isPrimary: Boolean = false,
    isActive: Boolean = false,   // true while DVR is recording — drives pulsing red ring
    tint: Color = TextPrimary
) {
    // Visual focus only — D-pad focus stays on the player key host (controlIndex).
    // Making these focusable caused OK on Record to fire Rewind when Compose focus
    // was still stuck on the first button.
    val isKeyFocused = index == focusedIndex
    val showFocus = isKeyFocused

    val scale by animateFloatAsState(
        targetValue = if (showFocus) 1.18f else 1f,
        animationSpec = tween(100),
        label = "btnScale"
    )

    // Pulsing alpha for the recording-active ring
    val pulseTransition = rememberInfiniteTransition(label = "activePulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(750, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val circleSize = if (isPrimary) 52.dp else 44.dp
    val iconSize   = if (isPrimary) 28.dp else 22.dp

    val bgColor = when {
        isActive && showFocus -> StatusError.copy(alpha = 0.35f)
        isActive              -> StatusError.copy(alpha = 0.20f)
        showFocus             -> Accent.copy(alpha = 0.28f)
        else                  -> BgSurface2
    }
    val borderColor = when {
        isActive  -> StatusError.copy(alpha = pulseAlpha)
        showFocus -> Accent
        else      -> Color.Transparent
    }
    val borderWidth = when {
        isActive && showFocus -> 3.dp
        isActive              -> 2.5.dp
        showFocus             -> 2.dp
        else                  -> 0.dp
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
    ) {
        Box(
            modifier = Modifier
                .size(circleSize)
                .background(color = bgColor, shape = CircleShape)
                .border(borderWidth, borderColor, CircleShape)
                .focusProperties { canFocus = false }
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onFocused()
                    onClick()
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    isActive  -> StatusError
                    showFocus -> Accent
                    else      -> tint
                },
                modifier = Modifier.size(iconSize)
            )
        }
        Text(
            text = label,
            color = when {
                isActive  -> StatusError
                showFocus -> Accent
                else      -> TextTertiary
            },
            fontSize = 9.sp,
            fontWeight = if (showFocus || isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ── No-Tracks Feedback Card ───────────────────────────────────────────────────

@Composable
private fun NoTracksCard(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .widthIn(min = 220.dp, max = 360.dp)
            .padding(bottom = 80.dp)
            .clickable { onDismiss() },
        colors = CardDefaults.cardColors(containerColor = BgElevated),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Default.Info, null, tint = TextSecondary, modifier = Modifier.size(18.dp))
            Text(message, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TrackPickerDropdown(
    tracks: List<Pair<Int, String>>,   // Pair(libVlcTrackId, displayName)
    selectedId: Int,
    focusedItemIndex: Int = -1,
    onSelect: (Int) -> Unit,           // called with actual LibVLC track ID
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    LaunchedEffect(focusedItemIndex, tracks.size) {
        if (tracks.isNotEmpty()) {
            listState.scrollToItem(focusedItemIndex.coerceIn(0, tracks.lastIndex))
        }
    }

    Card(
        modifier = modifier
            .widthIn(min = 200.dp, max = 320.dp)
            .padding(bottom = 80.dp),
        colors = CardDefaults.cardColors(containerColor = BgElevated),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(8.dp)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .heightIn(max = 320.dp)
                .padding(8.dp)
        ) {
            itemsIndexed(
                items = tracks,
                key = { index, item -> "${item.first}_${index}_${item.second}" }
            ) { listIndex, (trackId, name) ->
                val interactionSource = remember { MutableInteractionSource() }
                val isFocused by interactionSource.collectIsFocusedAsState()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (isFocused || listIndex == focusedItemIndex) AccentSurfaceHover else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .then(
                            if (isFocused || listIndex == focusedItemIndex)
                                Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp))
                            else Modifier
                        )
                        // Pass the ACTUAL LibVLC track ID — not the list index
                        .clickable { onSelect(trackId) }
                        .focusable(interactionSource = interactionSource)
                        .onKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionCenter, Key.Enter -> { onSelect(trackId); true }
                                else -> false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (trackId == selectedId) {
                        Icon(Icons.Default.Check, "Selected", tint = Accent, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                    } else {
                        Spacer(modifier = Modifier.width(20.dp))
                    }
                    Text(
                        text = name,
                        color = if (trackId == selectedId) Accent else TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

// ── Enhanced Subtitle / CC Picker ─────────────────────────────────────────────
// Shows track list + a subtitle delay control row so the user can nudge
// subtitles early/late without leaving the picker.

@Composable
private fun EnhancedSubtitlePicker(
    tracks: List<Pair<Int, String>>,   // Pair(libVlcTrackId, displayName)
    selectedId: Int,
    focusedItemIndex: Int = -1,
    isLive: Boolean,
    mediaPlayer: MediaPlayer,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Current subtitle delay in milliseconds (LibVLC spuDelay is in microseconds)
    var delayMs by remember {
        mutableIntStateOf(
            try { (mediaPlayer.spuDelay / 1000L).toInt() } catch (_: Exception) { 0 }
        )
    }
    fun applyDelay(newMs: Int) {
        delayMs = newMs
        try { mediaPlayer.spuDelay = newMs * 1000L } catch (_: Exception) {}
    }
    val listState = rememberLazyListState()
    LaunchedEffect(focusedItemIndex, tracks.size) {
        if (tracks.isNotEmpty()) {
            listState.scrollToItem(focusedItemIndex.coerceIn(0, tracks.lastIndex))
        }
    }

    Card(
        modifier = modifier
            .widthIn(min = 240.dp, max = 400.dp)
            .padding(bottom = 80.dp),
        colors = CardDefaults.cardColors(containerColor = BgElevated),
        shape = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(10.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Section header
            Text(
                text = if (isLive) "Closed Captions" else "Subtitles",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            )

            // Track list (includes Disable entry at top)
            if (tracks.size > 1) {  // >1 because "Disable" is always prepended
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 300.dp)
                ) {
                    itemsIndexed(
                        items = tracks,
                        key = { index, item -> "${item.first}_${index}_${item.second}" }
                    ) { listIndex, (trackId, name) ->
                    val interactionSource = remember { MutableInteractionSource() }
                    val isFocused by interactionSource.collectIsFocusedAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isFocused || listIndex == focusedItemIndex) AccentSurfaceHover
                                else Color.Transparent,
                                RoundedCornerShape(4.dp)
                            )
                            .then(
                                if (isFocused || listIndex == focusedItemIndex)
                                    Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp))
                                else Modifier
                            )
                            .clickable { onSelect(trackId) }
                            .focusable(interactionSource = interactionSource)
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                                when (event.key) {
                                    Key.DirectionCenter, Key.Enter -> { onSelect(trackId); true }
                                    else -> false
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isSelected = trackId == selectedId
                        if (isSelected) {
                            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                        } else {
                            Spacer(Modifier.width(20.dp))
                        }
                        Text(
                            text = name,
                            color = if (isSelected) Accent else TextSecondary,
                            fontSize = 13.sp
                        )
                    }
                    }
                }
            } else {
                // No actual subtitle tracks — show info + Disable option
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.Info, null, tint = TextTertiary, modifier = Modifier.size(14.dp))
                    Text(
                        "No ${if (isLive) "CC" else "subtitle"} tracks found.\nStream may still be loading.",
                        color = TextTertiary,
                        fontSize = 11.sp
                    )
                }
            }

            HorizontalDivider(color = BorderDefault, modifier = Modifier.padding(vertical = 4.dp))

            // ── Subtitle Delay row ──────────────────────────────────────────
            Text(
                "Subtitle Delay",
                color = TextSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SubDelayBtn("-500") { applyDelay(delayMs - 500) }
                SubDelayBtn("-100") { applyDelay(delayMs - 100) }
                Text(
                    text = if (delayMs == 0) "0 ms" else "${if (delayMs > 0) "+" else ""}${delayMs} ms",
                    color = if (delayMs == 0) TextSecondary else Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                SubDelayBtn("+100") { applyDelay(delayMs + 100) }
                SubDelayBtn("+500") { applyDelay(delayMs + 500) }
            }
            if (delayMs != 0) {
                val resetInteraction = remember { MutableInteractionSource() }
                val resetFocused by resetInteraction.collectIsFocusedAsState()
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.Center) {
                    Text(
                        "Reset delay",
                        color = if (resetFocused) Accent else TextTertiary,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .focusable(interactionSource = resetInteraction)
                            .clickable(resetInteraction, null) { applyDelay(0) }
                            .padding(4.dp)
                    )
                }
            }

            // Close row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                val dismissInteraction = remember { MutableInteractionSource() }
                val dismissFocused by dismissInteraction.collectIsFocusedAsState()
                Text(
                    "Close",
                    color = if (dismissFocused) Accent else TextTertiary,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .focusable(interactionSource = dismissInteraction)
                        .clickable(dismissInteraction, null) { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun SubDelayBtn(label: String, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier = Modifier
            .focusable(interactionSource = interactionSource)
            .then(if (isFocused) Modifier.border(1.dp, Accent, RoundedCornerShape(4.dp)) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        color = if (isFocused) Accent.copy(alpha = 0.18f) else BgSurface2,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            label,
            color = if (isFocused) Accent else TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
