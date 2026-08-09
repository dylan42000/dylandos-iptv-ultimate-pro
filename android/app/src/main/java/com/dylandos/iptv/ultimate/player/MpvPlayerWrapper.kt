package com.dylandos.iptv.ultimate.player

import android.content.Context
import android.view.Surface
import android.view.SurfaceView
import com.dylandos.iptv.ultimate.player.subtitle.SubtitleLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import `is`.xyz.mpv.MPVLib
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MpvPlayerWrapper — Wraps the mpv-android native library in a Kotlin-friendly API.
 *
 * Thread model:
 *   [mpvDispatcher] — single-threaded, owns all MPV API calls
 *   [stateScope]    — Main.immediate, safe for StateFlow/UI updates
 */
@Singleton
class MpvPlayerWrapper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engineFactory: MpvEngineFactory
) : MPVLib.EventObserver {

    private val mpvDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val commandScope = CoroutineScope(SupervisorJob() + mpvDispatcher)

    data class MpvPlaybackState(
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val isBuffering: Boolean = false,
        val position: Long = 0L,
        val duration: Long = 0L,
        val bufferPercent: Int = 0,
        val currentAudioTrack: Int = -1,
        val currentSubtitleTrack: Int = -1,
        val videoWidth: Int = 0,
        val videoHeight: Int = 0,
        val videoCodec: String = "",
        val audioCodec: String = "",
        val hwDecoder: String = "",
        val fps: Double = 0.0,
        val bitrate: Long = 0L,
        val droppedFrames: Int = 0,
        val error: String? = null,
        val nativesAvailable: Boolean = true,
    )

    data class TrackInfo(
        val id: Int,
        val type: String,
        val title: String?,
        val language: String?,
        val codec: String?,
        val isDefault: Boolean,
        val isForced: Boolean,
        val isExternal: Boolean,
    )

    private val _playbackState = MutableStateFlow(MpvPlaybackState())
    val playbackState: StateFlow<MpvPlaybackState> = _playbackState.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    private val _eventChannel = MutableSharedFlow<MpvEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<MpvEvent> = _eventChannel.asSharedFlow()

    private var isInitialized = false
    private var currentProfile: MpvEngineFactory.PlaybackProfile? = null
    private var surfaceAttached = false
    private var pendingSurfaceWidth = 0
    private var pendingSurfaceHeight = 0
    /** hwdec cycle index used when audio plays but video stays black (common on Fire OS HEVC). */
    private var hwdecRecoveryStep = 0

    /** True when libmpv/libplayer JNI loaded successfully at least once. */
    fun isNativeAvailable(): Boolean = _playbackState.value.nativesAvailable && isInitialized

    /** True once an Android Surface is attached and VO restored. */
    fun isSurfaceAttached(): Boolean = surfaceAttached

    suspend fun initialize(profile: MpvEngineFactory.PlaybackProfile): Boolean {
        return withContext(mpvDispatcher) {
            if (isInitialized) {
                if (currentProfile != profile) applyProfile(profile)
                return@withContext true
            }
            try {
                Timber.d("Initializing MPV with profile: $profile")
                engineFactory.initialize()
                MPVLib.ensureLibrariesLoaded()
                MPVLib.create(context)
                applyProfile(profile)
                MPVLib.init()
                MPVLib.addObserver(this@MpvPlayerWrapper)

                MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                MPVLib.observeProperty("cache-buffering-state", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("aid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("sid", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("sub-visibility", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
                MPVLib.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("video-codec", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("audio-codec-name", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_STRING)
                MPVLib.observeProperty("estimated-vf-fps", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
                MPVLib.observeProperty("video-bitrate", MPVLib.MpvFormat.MPV_FORMAT_INT64)
                MPVLib.observeProperty("decoder-frame-drop-count", MPVLib.MpvFormat.MPV_FORMAT_INT64)

                isInitialized = true
                _playbackState.update { it.copy(error = null, nativesAvailable = true) }
                Timber.d("MPV initialized successfully")
                true
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "MPV native libraries missing")
                _playbackState.update {
                    it.copy(error = "MPV natives missing: ${e.message}", nativesAvailable = false)
                }
                false
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize MPV")
                _playbackState.update {
                    it.copy(error = "MPV init failed: ${e.message}", nativesAvailable = false)
                }
                false
            }
        }
    }

    private fun applyProfile(profile: MpvEngineFactory.PlaybackProfile) {
        val options = engineFactory.getProfileOptions(profile)
        for ((key, value) in options) {
            try {
                MPVLib.setOptionString(key, value)
            } catch (e: Exception) {
                Timber.w("Failed to set MPV option $key=$value: ${e.message}")
            }
        }
        currentProfile = profile
        Timber.d("Applied MPV profile: $profile (${options.size} options)")
    }

    fun attachSurface(surfaceView: SurfaceView) {
        commandScope.launch {
            if (!isInitialized) {
                Timber.w("Cannot attach surface — MPV not initialized")
                return@launch
            }
            val surface: Surface? = surfaceView.holder.surface
            if (surface == null || !surface.isValid) {
                Timber.w("MPV surface not valid yet")
                return@launch
            }
            try {
                MPVLib.attachSurface(surface)
                // Critical: detachSurface() sets vo=null — must restore GPU VO or you get audio-only.
                MPVLib.setPropertyString("vo", "gpu")
                MPVLib.setOptionString("force-window", "yes")
                val w = surfaceView.width.coerceAtLeast(pendingSurfaceWidth)
                val h = surfaceView.height.coerceAtLeast(pendingSurfaceHeight)
                if (w > 0 && h > 0) {
                    MPVLib.setPropertyString("android-surface-size", "${w}x${h}")
                    pendingSurfaceWidth = w
                    pendingSurfaceHeight = h
                }
                surfaceAttached = true
                Timber.d("MPV surface attached: ${w}x${h} (view=${surfaceView.width}x${surfaceView.height})")
            } catch (e: Exception) {
                Timber.e(e, "MPV attachSurface failed")
                surfaceAttached = false
            }
        }
    }

    /** Call from SurfaceHolder.surfaceChanged — Firestick often reports 0x0 in surfaceCreated. */
    fun updateSurfaceSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        pendingSurfaceWidth = width
        pendingSurfaceHeight = height
        commandScope.launch {
            if (!isInitialized || !surfaceAttached) return@launch
            runCatching {
                MPVLib.setPropertyString("android-surface-size", "${width}x${height}")
                Timber.d("MPV surface size updated: ${width}x${height}")
            }
        }
    }

    fun detachSurface() {
        commandScope.launch {
            if (surfaceAttached) {
                try {
                    MPVLib.setPropertyString("vo", "null")
                    MPVLib.detachSurface()
                } catch (e: Exception) {
                    Timber.w(e, "MPV detachSurface failed")
                }
                surfaceAttached = false
                Timber.d("MPV surface detached")
            }
        }
    }

    /**
     * Wait until [attachSurface] succeeds (or [timeoutMs]). Prevents loadfile-before-surface
     * which is the #1 cause of audio-only / black video on Firestick MPV.
     */
    suspend fun awaitSurfaceAttached(timeoutMs: Long = 4_000L): Boolean {
        if (surfaceAttached) return true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (surfaceAttached) return true
            kotlinx.coroutines.delay(50L)
        }
        return surfaceAttached
    }

    fun loadUrl(
        url: String,
        headers: Map<String, String> = emptyMap(),
        startPosition: Long = 0L
    ) {
        commandScope.launch {
            if (!isInitialized) {
                Timber.e("Cannot load URL — MPV not initialized")
                _playbackState.update { it.copy(error = "MPV not initialized") }
                return@launch
            }

            hwdecRecoveryStep = 0
            _playbackState.update { MpvPlaybackState(nativesAvailable = true) }

            // Ensure VO is alive before demux (surface may have been re-attached).
            if (surfaceAttached) {
                runCatching {
                    MPVLib.setPropertyString("vo", "gpu")
                    MPVLib.setOptionString("force-window", "yes")
                    if (pendingSurfaceWidth > 0 && pendingSurfaceHeight > 0) {
                        MPVLib.setPropertyString(
                            "android-surface-size",
                            "${pendingSurfaceWidth}x$pendingSurfaceHeight"
                        )
                    }
                }
            } else {
                Timber.w("MPV loadUrl without attached surface — video may be black until attach")
            }

            if (headers.isNotEmpty()) {
                val headerStr = headers.entries.joinToString("\r\n") { "${it.key}: ${it.value}" }
                MPVLib.setOptionString("http-header-fields", headerStr)
            }
            // Spoof VLC UA — many IPTV panels whitelist it.
            MPVLib.setOptionString("user-agent", "VLC/3.0.18 LibVLC/3.0.18")

            if (startPosition > 0) {
                val startSeconds = startPosition / 1000.0
                MPVLib.setOptionString("start", startSeconds.toString())
            }

            Timber.d("MPV loading URL: $url (start: ${startPosition}ms, surface=$surfaceAttached)")
            try {
                MPVLib.command(arrayOf("loadfile", url, "replace"))
                MPVLib.setPropertyBoolean("pause", false)
            } catch (e: Exception) {
                Timber.e(e, "MPV loadfile failed")
                _playbackState.update { it.copy(error = e.message ?: "loadfile failed") }
            }
        }
    }

    /**
     * Firestick / Netflix-style VOD: audio plays, video stays black (HEVC/10-bit hwdec fail).
     * Cycles hwdec modes and forces a decoder reinit via `seek 0 relative exact`.
     * Skips bare `mediacodec` (direct) — it cannot blend softsubs onto vo=gpu.
     * @return true if another recovery step was applied
     */
    fun recoverBlackVideo(): Boolean {
        val modes = listOf("mediacodec-copy", "no", "auto")
        if (hwdecRecoveryStep >= modes.size) return false
        val mode = modes[hwdecRecoveryStep++]
        commandScope.launch {
            try {
                Timber.w("MPV black-video recovery → hwdec=$mode (step $hwdecRecoveryStep)")
                MPVLib.setPropertyString("hwdec", mode)
                // Re-init video decoder pipeline without full reload when possible.
                MPVLib.command(arrayOf("seek", "0", "relative", "exact"))
            } catch (e: Exception) {
                Timber.w(e, "MPV black-video recovery failed for hwdec=$mode")
            }
        }
        return true
    }

    fun play() {
        commandScope.launch { MPVLib.setPropertyBoolean("pause", false) }
    }

    fun pause() {
        commandScope.launch { MPVLib.setPropertyBoolean("pause", true) }
    }

    fun togglePlayPause() {
        commandScope.launch {
            val currentPause = _playbackState.value.isPaused
            MPVLib.setPropertyBoolean("pause", !currentPause)
        }
    }

    fun seekTo(positionMs: Long) {
        commandScope.launch {
            val positionSec = positionMs / 1000.0
            MPVLib.command(arrayOf("seek", positionSec.toString(), "absolute"))
        }
    }

    fun seekRelative(deltaMs: Long) {
        commandScope.launch {
            val deltaSec = deltaMs / 1000.0
            MPVLib.command(arrayOf("seek", deltaSec.toString(), "relative"))
        }
    }

    fun setSpeed(speed: Float) {
        commandScope.launch { MPVLib.setPropertyDouble("speed", speed.toDouble()) }
    }

    fun setVolume(volume: Int) {
        commandScope.launch { MPVLib.setPropertyInt("volume", volume.coerceIn(0, 130)) }
    }

    /** Relative volume change for D-pad / remote (MPV scale is typically 0–100, up to ~130). */
    fun adjustVolume(delta: Int) {
        commandScope.launch {
            val current = MPVLib.getPropertyInt("volume") ?: 100
            MPVLib.setPropertyInt("volume", (current + delta).coerceIn(0, 130))
        }
    }

    fun selectAudioTrack(trackId: Int) {
        commandScope.launch {
            if (trackId < 0) MPVLib.setPropertyString("aid", "no")
            else MPVLib.setPropertyInt("aid", trackId)
            _playbackState.update { it.copy(currentAudioTrack = trackId) }
        }
    }

    fun selectSubtitleTrack(trackId: Int) {
        commandScope.launch {
            ensureSubtitleOverlayPipeline()
            if (trackId < 0) {
                MPVLib.setPropertyString("sid", "no")
                MPVLib.setPropertyString("sub-visibility", "no")
            } else {
                MPVLib.setPropertyInt("sid", trackId)
                MPVLib.setPropertyString("sub-visibility", "yes")
            }
            _playbackState.update { it.copy(currentSubtitleTrack = trackId) }
            Timber.i("MPV subtitle track set to $trackId (visibility=${trackId >= 0})")
        }
    }

    /**
     * If no subtitle is active yet, enable the preferred-language track (or the first
     * embedded soft-sub). IPTV VOD packs often omit the Matroska "default" flag, so
     * mpv's `sid=auto` leaves softsubs off until the user picks one.
     */
    fun autoEnableSubtitleIfNeeded(preferredLanguage: String) {
        commandScope.launch {
            val subs = _tracks.value.filter { it.type == "sub" }
            if (subs.isEmpty()) {
                Timber.d("MPV auto-sub: no subtitle tracks in track-list yet")
                return@launch
            }
            ensureSubtitleOverlayPipeline()
            val sidNow = parseMpvTrackId(MPVLib.getPropertyString("sid") ?: "no")
            if (sidNow >= 0) {
                MPVLib.setPropertyString("sub-visibility", "yes")
                _playbackState.update { it.copy(currentSubtitleTrack = sidNow) }
                Timber.d("MPV auto-sub: already active sid=$sidNow — forced visibility")
                return@launch
            }
            val pick = pickPreferredSubtitleId(preferredLanguage, subs) ?: return@launch
            MPVLib.setPropertyInt("sid", pick)
            MPVLib.setPropertyString("sub-visibility", "yes")
            _playbackState.update { it.copy(currentSubtitleTrack = pick) }
            val chosen = subs.firstOrNull { it.id == pick }
            Timber.i(
                "MPV auto-enabled subtitle track $pick " +
                    "(lang=${chosen?.language} title=${chosen?.title} pref=$preferredLanguage)"
            )
        }
    }

    /**
     * Softsubs need vo=gpu + copy-mode (or software) hwdec. Direct mediacodec leaves
     * libass with nowhere to paint.
     */
    private fun ensureSubtitleOverlayPipeline() {
        if (!isInitialized) return
        runCatching {
            val hwdec = (MPVLib.getPropertyString("hwdec") ?: "").lowercase()
            val hwCurrent = (MPVLib.getPropertyString("hwdec-current") ?: "").lowercase()
            val directMediacodec =
                (hwdec == "mediacodec" || hwCurrent.contains("mediacodec")) &&
                    !hwdec.contains("copy") &&
                    !hwCurrent.contains("copy")
            if (directMediacodec) {
                Timber.w("MPV switching hwdec mediacodec → mediacodec-copy for softsubs")
                MPVLib.setPropertyString("hwdec", "mediacodec-copy")
                // Re-init video decoder pipeline to apply copy-mode
                MPVLib.command(arrayOf("seek", "0", "relative", "exact"))
            }
        }.onFailure { Timber.w(it, "MPV subtitle overlay pipeline assert failed") }
    }

    private fun pickPreferredSubtitleId(
        preferredLanguage: String,
        subs: List<TrackInfo>
    ): Int? {
        // Prefer full dialogue tracks over sparse "forced" naming/sign tracks.
        val nonForced = subs.filter { !it.isForced }
        val pool = nonForced.ifEmpty { subs }
        val byLang = pool.firstOrNull {
            SubtitleLanguage.matches(it.language, preferredLanguage) ||
                SubtitleLanguage.labelContainsPreferred(it.title, preferredLanguage)
        }
        if (byLang != null) return byLang.id
        pool.firstOrNull { it.isDefault }?.id?.let { return it }
        return pool.firstOrNull()?.id
    }

    /**
     * Mirror Settings subtitle appearance onto libass (VOD MPV path).
     * Colors are #RRGGBB; mpv expects #AARRGGBB for sub-color / border / back.
     */
    fun applySubtitleStyle(
        sizeSp: Int,
        colorHex: String,
        outlineHex: String,
        bgOpacityPercent: Int
    ) {
        commandScope.launch {
            if (!isInitialized) return@launch
            runCatching {
                MPVLib.setPropertyInt("sub-font-size", sizeSp.coerceIn(12, 72) * 2)
                MPVLib.setPropertyString("sub-color", hexToMpvColor(colorHex, 255))
                MPVLib.setPropertyString("sub-border-color", hexToMpvColor(outlineHex, 255))
                val bgAlpha = ((bgOpacityPercent.coerceIn(0, 100) / 100f) * 255f).toInt().coerceIn(0, 255)
                MPVLib.setPropertyString("sub-back-color", hexToMpvColor("#000000", bgAlpha))
                MPVLib.setPropertyInt("sub-border-size", 2)
            }.onFailure { Timber.w(it, "MPV subtitle style apply failed") }
        }
    }

    private fun hexToMpvColor(hex: String, alpha: Int): String {
        val cleaned = hex.trim().removePrefix("#")
        val rgb = when (cleaned.length) {
            6 -> cleaned
            8 -> cleaned.takeLast(6)
            3 -> cleaned.map { "$it$it" }.joinToString("")
            else -> "FFFFFF"
        }
        return "#%02X%s".format(alpha.coerceIn(0, 255), rgb.uppercase())
    }

    fun addSubtitleFile(path: String) {
        commandScope.launch { MPVLib.command(arrayOf("sub-add", path, "auto")) }
    }

    fun stop() {
        commandScope.launch {
            runCatching { MPVLib.command(arrayOf("stop")) }
            _playbackState.update { MpvPlaybackState(nativesAvailable = isInitialized) }
        }
    }

    fun destroy() {
        commandScope.launch {
            try {
                if (surfaceAttached) {
                    runCatching { MPVLib.detachSurface() }
                    surfaceAttached = false
                }
                if (isInitialized) {
                    MPVLib.removeObserver(this@MpvPlayerWrapper)
                    MPVLib.destroy()
                }
                isInitialized = false
                currentProfile = null
                Timber.d("MPV destroyed")
            } catch (e: Exception) {
                Timber.e(e, "Error destroying MPV")
            }
        }
    }

    override fun eventProperty(property: String, value: Long) {
        stateScope.launch {
            when (property) {
                "cache-buffering-state" ->
                    _playbackState.update { it.copy(bufferPercent = value.toInt()) }
                "video-params/w" ->
                    _playbackState.update { it.copy(videoWidth = value.toInt()) }
                "video-params/h" ->
                    _playbackState.update { it.copy(videoHeight = value.toInt()) }
                "video-bitrate" ->
                    _playbackState.update { it.copy(bitrate = value) }
                "decoder-frame-drop-count" ->
                    _playbackState.update { it.copy(droppedFrames = value.toInt()) }
                "track-list/count" -> refreshTrackList()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        stateScope.launch {
            when (property) {
                "pause" ->
                    _playbackState.update { it.copy(isPaused = value, isPlaying = !value) }
                "paused-for-cache" ->
                    _playbackState.update { it.copy(isBuffering = value) }
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        stateScope.launch {
            when (property) {
                "time-pos" ->
                    _playbackState.update { it.copy(position = (value * 1000.0).toLong()) }
                "duration" ->
                    _playbackState.update { it.copy(duration = (value * 1000.0).toLong()) }
                "estimated-vf-fps" ->
                    _playbackState.update { it.copy(fps = value) }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        stateScope.launch {
            when (property) {
                "video-codec" ->
                    _playbackState.update { it.copy(videoCodec = value) }
                "audio-codec-name" ->
                    _playbackState.update { it.copy(audioCodec = value) }
                "hwdec-current" ->
                    _playbackState.update { it.copy(hwDecoder = value) }
                "aid" ->
                    _playbackState.update {
                        it.copy(currentAudioTrack = parseMpvTrackId(value))
                    }
                "sid" ->
                    _playbackState.update {
                        it.copy(currentSubtitleTrack = parseMpvTrackId(value))
                    }
            }
        }
    }

    /** mpv reports aid/sid as int strings, or "no" / "auto" when unset. */
    private fun parseMpvTrackId(value: String): Int =
        when (value.lowercase()) {
            "no", "auto", "" -> -1
            else -> value.toIntOrNull() ?: -1
        }

    override fun eventProperty(property: String) {
        // no-op
    }

    override fun event(eventId: Int) {
        stateScope.launch {
            val event = when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> MpvEvent.StartFile
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    refreshTrackList()
                    MpvEvent.FileLoaded
                }
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    _playbackState.update { it.copy(isPlaying = true, isBuffering = false) }
                    MpvEvent.PlaybackRestart
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    _playbackState.update { it.copy(isPlaying = false) }
                    MpvEvent.EndFile
                }
                MPVLib.MpvEvent.MPV_EVENT_SHUTDOWN -> MpvEvent.Shutdown
                MPVLib.MpvEvent.MPV_EVENT_IDLE -> MpvEvent.Idle
                else -> MpvEvent.Unknown(eventId)
            }
            _eventChannel.emit(event)
        }
    }

    private fun refreshTrackList() {
        commandScope.launch {
            try {
                val count = MPVLib.getPropertyInt("track-list/count") ?: 0
                val trackList = mutableListOf<TrackInfo>()
                for (i in 0 until count) {
                    val prefix = "track-list/$i"
                    trackList.add(
                        TrackInfo(
                            id = MPVLib.getPropertyInt("$prefix/id") ?: i,
                            type = MPVLib.getPropertyString("$prefix/type") ?: "unknown",
                            title = MPVLib.getPropertyString("$prefix/title"),
                            language = MPVLib.getPropertyString("$prefix/lang"),
                            codec = MPVLib.getPropertyString("$prefix/codec"),
                            isDefault = MPVLib.getPropertyBoolean("$prefix/default") ?: false,
                            isForced = MPVLib.getPropertyBoolean("$prefix/forced") ?: false,
                            isExternal = MPVLib.getPropertyBoolean("$prefix/external") ?: false,
                        )
                    )
                }
                val aid = parseMpvTrackId(MPVLib.getPropertyString("aid") ?: "no")
                val sid = parseMpvTrackId(MPVLib.getPropertyString("sid") ?: "no")
                stateScope.launch {
                    _tracks.value = trackList
                    _playbackState.update {
                        it.copy(currentAudioTrack = aid, currentSubtitleTrack = sid)
                    }
                    Timber.d(
                        "MPV track list refreshed: ${trackList.size} tracks " +
                            "(aid=$aid sid=$sid subs=${trackList.count { it.type == "sub" }})"
                    )
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to refresh MPV track list")
            }
        }
    }
}

sealed class MpvEvent {
    data object StartFile : MpvEvent()
    data object FileLoaded : MpvEvent()
    data object PlaybackRestart : MpvEvent()
    data object EndFile : MpvEvent()
    data object Shutdown : MpvEvent()
    data object Idle : MpvEvent()
    data class Unknown(val eventId: Int) : MpvEvent()
}
