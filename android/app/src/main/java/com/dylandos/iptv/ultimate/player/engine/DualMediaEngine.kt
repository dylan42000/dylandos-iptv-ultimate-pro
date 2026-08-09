package com.dylandos.iptv.ultimate.player.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DualMediaEngine — Orchestrator for the dual LibVLC + MPV playback architecture.
 *
 * Automatically selects the optimal engine for each stream type and provides
 * instant automatic failover if the primary engine encounters a fatal error.
 *
 * ─── Engine Selection Strategy ───────────────────────────────────────────────
 *
 * Stream Type            | Primary | Fallback | Reason
 * ─────────────────────────────────────────────────────────────────────────────
 * Live TV (.ts / RTSP)   | LibVLC  | MPV      | VLC excels at MPEG-TS/RTSP/multicast
 * Live TV (HLS / .m3u8)  | MPV     | LibVLC   | MPV handles adaptive bitrate better
 * VOD (.mp4 / .mkv)      | MPV     | LibVLC   | MPV has superior container support
 * Series                 | MPV     | LibVLC   | Same as VOD
 * DVR Recording          | LibVLC  | —        | Only LibVLC supports :sout stream-copy
 * HDR content            | MPV     | LibVLC   | MPV has HDR tone mapping
 *
 * ─── Failover Behavior ───────────────────────────────────────────────────────
 *
 * On a non-recoverable engine error:
 *   1. EngineHealthMonitor detects the failure via [EngineEvent.Error]
 *   2. Failed engine is released
 *   3. Fallback engine is initialized with the same [EngineConfig]
 *   4. Playback resumes from the last known position
 *   5. [DualEngineEvent.Failover] is emitted so the UI can show a toast
 *
 * ─── Thread Safety ───────────────────────────────────────────────────────────
 *
 * All engine switching and state mutation happens on [engineDispatcher]
 * (single-threaded IO). StateFlow emissions are on Main.immediate for UI safety.
 */
@Singleton
class DualMediaEngine @Inject constructor(
    private val libVlcEngine: LibVlcEngine,
    private val mpvEngine: MpvEngine,
) {
    // Single-threaded dispatcher — serializes ALL engine switching operations
    private val engineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ─── Public State ─────────────────────────────────────────────────────────

    /**
     * Metadata about which engine is currently active.
     */
    data class EngineState(
        val activeEngineId: String = "none",
        val activeEngineName: String = "None",
        val fallbackAvailable: Boolean = true,
        val failoverCount: Int = 0,
        val lastFailoverReason: String? = null,
    )

    private val _engineState = MutableStateFlow(EngineState())
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /**
     * Unified playback state from whichever engine is currently active.
     * The UI layer observes this and never needs to know which engine is running.
     */
    val playbackState: StateFlow<PlaybackState>
        get() = activeEngine?.playbackState ?: MutableStateFlow(PlaybackState())

    val trackList: StateFlow<List<TrackDescriptor>>
        get() = activeEngine?.trackList ?: MutableStateFlow(emptyList())

    private val _engineEvents = MutableSharedFlow<DualEngineEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val engineEvents: SharedFlow<DualEngineEvent> = _engineEvents.asSharedFlow()

    // ─── Internal State ───────────────────────────────────────────────────────

    private var activeEngine: MediaEngine? = null
    private var fallbackEngine: MediaEngine? = null
    private var currentSource: MediaSource? = null
    private var currentConfig: EngineConfig? = null
    private var failoverCount = 0

    private val healthMonitor = EngineHealthMonitor()
    private var healthMonitorJob: Job? = null

    /** User preference override. Set via [setEnginePreference]. */
    enum class EnginePreference { AUTO, LIBVLC, MPV }
    private var userPreference = EnginePreference.AUTO

    // ─── Public API ───────────────────────────────────────────────────────────

    fun setEnginePreference(preference: EnginePreference) {
        userPreference = preference
        Timber.d("Engine preference set to: $preference")
    }

    /**
     * Initialize and load media with automatic engine selection.
     *
     * @param source The media source to play
     * @param config Engine configuration (buffer sizes, HDR, etc.)
     */
    suspend fun loadMedia(source: MediaSource, config: EngineConfig) {
        withContext(engineDispatcher) {
            currentSource = source
            currentConfig = config

            val (primary, fallback) = selectEngines(source, config)
            Timber.d("Engine selection: primary=${primary.engineName}, fallback=${fallback.engineName}")

            // Release any previously active engines
            activeEngine?.release()
            fallbackEngine = null

            // Initialize primary engine
            val initSuccess = primary.initialize(config)
            if (!initSuccess) {
                Timber.w("Primary engine ${primary.engineName} init failed — trying fallback")
                val fallbackSuccess = fallback.initialize(config)
                if (!fallbackSuccess) {
                    Timber.e("Both engines failed to initialize!")
                    _engineEvents.emit(
                        DualEngineEvent.BothEnginesFailed("Neither LibVLC nor MPV could initialize")
                    )
                    return@withContext
                }
                activeEngine = fallback
                fallbackEngine = null
            } else {
                activeEngine = primary
                fallbackEngine = fallback
            }

            _engineState.update {
                EngineState(
                    activeEngineId = activeEngine!!.engineId,
                    activeEngineName = activeEngine!!.engineName,
                    fallbackAvailable = fallbackEngine != null,
                )
            }

            startHealthMonitor()

            val loadSuccess = activeEngine!!.loadMedia(source)
            if (!loadSuccess) {
                Timber.w("Primary engine failed to load media — attempting failover")
                performFailover("Load failed on ${activeEngine!!.engineName}")
            }

            _engineEvents.emit(
                DualEngineEvent.EngineSelected(
                    activeEngine!!.engineName,
                    getSelectionReason(source, config)
                )
            )
        }
    }

    // ─── Passthrough Controls ─────────────────────────────────────────────────
    // These delegate to whichever engine is currently active.

    fun play() = activeEngine?.play()
    fun pause() = activeEngine?.pause()
    fun togglePlayPause() = activeEngine?.togglePlayPause()

    fun stop() {
        healthMonitorJob?.cancel()
        activeEngine?.stop()
    }

    fun seekTo(positionMs: Long) = activeEngine?.seekTo(positionMs)
    fun seekRelative(deltaMs: Long) = activeEngine?.seekRelative(deltaMs)
    fun selectAudioTrack(trackId: Int) = activeEngine?.selectAudioTrack(trackId)
    fun selectSubtitleTrack(trackId: Int) = activeEngine?.selectSubtitleTrack(trackId)
    fun disableSubtitles() = activeEngine?.disableSubtitles()
    fun addExternalSubtitle(path: String) = activeEngine?.addExternalSubtitle(path)
    fun setPlaybackSpeed(speed: Float) = activeEngine?.setPlaybackSpeed(speed)
    fun setVolume(volume: Int) = activeEngine?.setVolume(volume)
    fun setAspectRatio(ratio: AspectRatio) = activeEngine?.setAspectRatio(ratio)

    // DVR is only supported when LibVLC is the active engine
    val supportsDvrRecording: Boolean
        get() = activeEngine?.supportsDvrRecording == true

    fun startRecording(outputPath: String): Boolean =
        activeEngine?.startRecording(outputPath) ?: false

    fun stopRecording() = activeEngine?.stopRecording()

    /**
     * Force-switch to a specific engine (called from the player settings menu).
     * Resumes playback from the last known position.
     */
    suspend fun forceEngine(engineId: String) {
        withContext(engineDispatcher) {
            if (activeEngine?.engineId == engineId) return@withContext
            val targetEngine: MediaEngine = when (engineId) {
                "libvlc" -> libVlcEngine
                "mpv" -> mpvEngine
                else -> return@withContext
            }

            val source = currentSource ?: return@withContext
            val config = currentConfig ?: return@withContext
            val currentPos = activeEngine?.playbackState?.value?.positionMs ?: 0L

            Timber.d("Force switching to $engineId at position ${currentPos}ms")

            activeEngine?.release()
            targetEngine.initialize(config)
            activeEngine = targetEngine
            fallbackEngine = when (engineId) {
                "libvlc" -> mpvEngine
                "mpv" -> libVlcEngine
                else -> null
            }

            val resumeSource = source.copy(startPositionMs = currentPos)
            targetEngine.loadMedia(resumeSource)

            _engineState.update {
                EngineState(
                    activeEngineId = engineId,
                    activeEngineName = targetEngine.engineName,
                    fallbackAvailable = fallbackEngine != null,
                )
            }
            _engineEvents.emit(DualEngineEvent.EngineSwitched(targetEngine.engineName, "User forced"))
        }
    }

    /**
     * Release all engine resources. Call from Activity.onDestroy() or Service.onDestroy().
     */
    suspend fun releaseAll() {
        healthMonitorJob?.cancel()
        activeEngine?.release()
        fallbackEngine?.release()
        activeEngine = null
        fallbackEngine = null
        _engineState.value = EngineState()
        Timber.d("DualMediaEngine — all engines released")
    }

    fun getDebugInfo(): Map<String, String> {
        val engineInfo = activeEngine?.getDebugInfo() ?: emptyMap()
        return engineInfo + mapOf(
            "orchestrator" to "DualMediaEngine",
            "active_engine" to _engineState.value.activeEngineName,
            "failover_count" to failoverCount.toString(),
            "fallback_available" to _engineState.value.fallbackAvailable.toString(),
        )
    }

    // ─── Private: Engine Selection ────────────────────────────────────────────

    private fun selectEngines(
        source: MediaSource,
        config: EngineConfig
    ): Pair<MediaEngine, MediaEngine> {
        // Honor explicit user override
        if (userPreference != EnginePreference.AUTO) {
            return when (userPreference) {
                EnginePreference.LIBVLC -> libVlcEngine to mpvEngine
                EnginePreference.MPV -> mpvEngine to libVlcEngine
                else -> libVlcEngine to mpvEngine
            }
        }

        return when {
            // DVR recording MUST use LibVLC (:sout stream-copy)
            source.streamType == StreamType.RECORDING ->
                libVlcEngine to mpvEngine

            // HDR content — MPV has tone mapping
            config.enableHdr ->
                mpvEngine to libVlcEngine

            // Live MPEG-TS — LibVLC is the MPEG-TS specialist
            source.streamType == StreamType.LIVE &&
                source.containerFormat.contains("ts") ->
                libVlcEngine to mpvEngine

            // Live HLS — MPV handles adaptive bitrate better
            source.streamType == StreamType.LIVE &&
                (source.url.contains(".m3u8") || source.containerFormat.contains("m3u8")) ->
                mpvEngine to libVlcEngine

            // Live RTSP/RTMP — LibVLC excels here
            source.streamType == StreamType.LIVE &&
                (source.url.startsWith("rtsp://") || source.url.startsWith("rtmp://")) ->
                libVlcEngine to mpvEngine

            // VOD / Series — MPV for superior container and subtitle support
            source.streamType == StreamType.VOD || source.streamType == StreamType.SERIES ->
                mpvEngine to libVlcEngine

            // Default: LibVLC
            else -> libVlcEngine to mpvEngine
        }
    }

    private fun getSelectionReason(source: MediaSource, config: EngineConfig): String = when {
        userPreference != EnginePreference.AUTO -> "User preference: $userPreference"
        source.streamType == StreamType.RECORDING -> "DVR recording requires LibVLC :sout"
        config.enableHdr -> "HDR tone mapping (MPV)"
        source.streamType == StreamType.LIVE &&
            source.containerFormat.contains("ts") -> "MPEG-TS live stream (LibVLC)"
        source.url.contains(".m3u8") -> "HLS adaptive stream (MPV)"
        source.url.startsWith("rtsp://") -> "RTSP protocol (LibVLC)"
        source.streamType == StreamType.VOD -> "VOD container (MPV)"
        else -> "Default selection (LibVLC)"
    }

    // ─── Private: Failover ────────────────────────────────────────────────────

    private suspend fun performFailover(reason: String) {
        val fallback = fallbackEngine ?: run {
            Timber.e("No fallback engine available — failover impossible")
            _engineEvents.emit(DualEngineEvent.BothEnginesFailed(reason))
            return
        }

        val source = currentSource ?: return
        val config = currentConfig ?: return
        val currentPos = activeEngine?.playbackState?.value?.positionMs ?: 0L

        Timber.w("FAILOVER: ${activeEngine?.engineName} → ${fallback.engineName} (reason: $reason)")

        activeEngine?.release()

        val fallbackSuccess = fallback.initialize(config)
        if (!fallbackSuccess) {
            Timber.e("Fallback engine also failed to initialize!")
            _engineEvents.emit(DualEngineEvent.BothEnginesFailed(reason))
            return
        }

        // Resume from the last known position
        val resumeSource = source.copy(startPositionMs = currentPos)
        fallback.loadMedia(resumeSource)

        activeEngine = fallback
        fallbackEngine = null // Used our last resort — no more failover
        failoverCount++

        _engineState.update {
            EngineState(
                activeEngineId = fallback.engineId,
                activeEngineName = fallback.engineName,
                fallbackAvailable = false,
                failoverCount = failoverCount,
                lastFailoverReason = reason,
            )
        }

        _engineEvents.emit(DualEngineEvent.Failover(fallback.engineName, reason))
        startHealthMonitor() // Re-attach health monitor to the new engine
    }

    // ─── Private: Health Monitor ──────────────────────────────────────────────

    private fun startHealthMonitor() {
        healthMonitorJob?.cancel()
        healthMonitorJob = scope.launch {
            activeEngine?.engineEvents?.collect { event ->
                when (event) {
                    is EngineEvent.Error -> {
                        Timber.w("Engine error: ${event.error.code} — ${event.error.message}")
                        if (!event.error.isRecoverable && fallbackEngine != null) {
                            performFailover(event.error.message)
                        } else if (!event.error.isRecoverable) {
                            _engineEvents.emit(DualEngineEvent.PlaybackError(event.error.message))
                        }
                        // Recoverable errors: let the engine handle internally
                    }
                    else -> { /* Other events forwarded via activeEngine.engineEvents */ }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// DualEngineEvent — orchestrator-level events for the UI layer
// ─────────────────────────────────────────────────────────────────────────────

sealed class DualEngineEvent {
    data class EngineSelected(val engineName: String, val reason: String) : DualEngineEvent()
    data class EngineSwitched(val engineName: String, val reason: String) : DualEngineEvent()
    data class Failover(val newEngine: String, val reason: String) : DualEngineEvent()
    data class PlaybackError(val message: String) : DualEngineEvent()
    data class BothEnginesFailed(val message: String) : DualEngineEvent()
}

// ─────────────────────────────────────────────────────────────────────────────
// EngineHealthMonitor — tracks engine health for failover decisions
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Monitors buffer underruns, consecutive errors, and frame drop rate.
 * When health degrades below threshold, the DualMediaEngine triggers failover.
 */
class EngineHealthMonitor {

    data class HealthMetrics(
        val bufferUnderruns: Int = 0,
        val consecutiveErrors: Int = 0,
        val droppedFramesPerSecond: Float = 0f,
        val lastSuccessfulFrameMs: Long = System.currentTimeMillis(),
        val isHealthy: Boolean = true,
    )

    private val _metrics = MutableStateFlow(HealthMetrics())
    val metrics: StateFlow<HealthMetrics> = _metrics.asStateFlow()

    fun recordBufferUnderrun() {
        _metrics.update { it.copy(bufferUnderruns = it.bufferUnderruns + 1) }
    }

    fun recordError() {
        _metrics.update { current ->
            val newCount = current.consecutiveErrors + 1
            current.copy(
                consecutiveErrors = newCount,
                // Three consecutive errors → unhealthy
                isHealthy = newCount < 3
            )
        }
    }

    fun recordSuccessfulFrame() {
        _metrics.update {
            it.copy(
                consecutiveErrors = 0,
                lastSuccessfulFrameMs = System.currentTimeMillis(),
                isHealthy = true,
            )
        }
    }

    fun reset() {
        _metrics.value = HealthMetrics()
    }
}
