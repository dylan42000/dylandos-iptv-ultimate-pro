package com.dylandos.iptv.ultimate.player.engine

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * MediaEngine — Unified interface for both LibVLC and MPV playback engines.
 *
 * Both engines are wrapped behind this interface so the DualMediaEngine
 * orchestrator can swap between them transparently.
 *
 * Design principle: The caller should never need to know which engine is active.
 * All playback operations, state observation, and track management go through
 * this interface.
 */
interface MediaEngine {

    /** Human-readable name for logging and UI display */
    val engineName: String

    /** Unique identifier: "libvlc" or "mpv" */
    val engineId: String

    // === State Observation ===

    /** Current playback state as a reactive Flow */
    val playbackState: StateFlow<PlaybackState>

    /** Available audio/subtitle/video tracks */
    val trackList: StateFlow<List<TrackDescriptor>>

    /** Engine events (errors, end-of-file, etc.) */
    val engineEvents: SharedFlow<EngineEvent>

    // === Lifecycle ===

    /**
     * Initialize the engine with the given configuration.
     * Must be called before any playback operations.
     *
     * @param config Engine-specific configuration
     * @return true if initialization succeeded
     */
    suspend fun initialize(config: EngineConfig): Boolean

    /**
     * Release all engine resources.
     * After this call, the engine must be re-initialized before use.
     */
    suspend fun release()

    /** Whether the engine is currently initialized and ready for playback */
    val isInitialized: Boolean

    // === Playback Operations ===

    /**
     * Load and start playing a media source.
     *
     * @param source The media source to play
     * @return true if the load was initiated successfully
     */
    suspend fun loadMedia(source: MediaSource): Boolean

    /** Resume playback */
    fun play()

    /** Pause playback */
    fun pause()

    /** Toggle play/pause */
    fun togglePlayPause()

    /** Stop playback (keeps engine initialized) */
    fun stop()

    /**
     * Seek to an absolute position.
     * @param positionMs Target position in milliseconds
     */
    fun seekTo(positionMs: Long)

    /**
     * Seek relative to current position.
     * @param deltaMs Positive = forward, negative = backward
     */
    fun seekRelative(deltaMs: Long)

    // === Track Selection ===

    fun selectAudioTrack(trackId: Int)
    fun selectSubtitleTrack(trackId: Int)
    fun disableSubtitles()
    fun addExternalSubtitle(path: String)

    // === Adjustments ===

    fun setPlaybackSpeed(speed: Float)
    fun setVolume(volume: Int) // 0-200 (> 100 = boost)
    fun setAspectRatio(ratio: AspectRatio)

    // === DVR Integration (LibVLC-specific, but in the interface for flexibility) ===

    /**
     * Whether this engine supports stream-copy recording.
     * LibVLC supports this via :sout; MPV does not natively.
     */
    val supportsDvrRecording: Boolean get() = false

    /**
     * Start recording the current stream to the given path.
     * @return true if recording started successfully
     */
    fun startRecording(outputPath: String): Boolean = false

    /**
     * Stop the current recording.
     */
    fun stopRecording() {}

    // === Debug ===

    /**
     * Get engine-specific debug info for the stats overlay.
     */
    fun getDebugInfo(): Map<String, String>
}

// ─────────────────────────────────────────────────────────────────────────────
// Supporting Data Classes
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Unified playback state across both engines.
 * This is the single source of truth for the UI layer.
 */
data class PlaybackState(
    val phase: PlaybackPhase = PlaybackPhase.IDLE,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferPercent: Int = 0,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val videoCodec: String = "",
    val audioCodec: String = "",
    val hwDecoder: String = "",
    val fps: Double = 0.0,
    val bitrate: Long = 0L,
    val droppedFrames: Int = 0,
    val speed: Float = 1.0f,
    val volume: Int = 100,
    val activeAudioTrackId: Int = -1,
    val activeSubtitleTrackId: Int = -1,
    val error: EngineError? = null,
)

enum class PlaybackPhase {
    IDLE,
    LOADING,
    BUFFERING,
    PLAYING,
    PAUSED,
    STOPPED,
    ERROR,
    END_OF_MEDIA
}

data class TrackDescriptor(
    val id: Int,
    val type: TrackType,
    val title: String?,
    val language: String?,
    val codec: String?,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val isExternal: Boolean = false,
)

enum class TrackType { VIDEO, AUDIO, SUBTITLE }

data class MediaSource(
    val url: String,
    val title: String = "",
    val headers: Map<String, String> = emptyMap(),
    val startPositionMs: Long = 0L,
    val streamType: StreamType = StreamType.LIVE,
    val containerFormat: String = "",
    val drmInfo: DrmInfo? = null,
)

enum class StreamType { LIVE, VOD, SERIES, RECORDING }

data class DrmInfo(
    val scheme: String, // "widevine", "clearkey"
    val licenseUrl: String,
    val headers: Map<String, String> = emptyMap(),
)

enum class AspectRatio(val label: String, val value: String) {
    FIT("Fit", ""),
    FILL("Fill", "fill"),
    RATIO_16_9("16:9", "16:9"),
    RATIO_4_3("4:3", "4:3"),
    RATIO_21_9("21:9", "21:9"),
}

/**
 * Engine configuration for initialization.
 */
data class EngineConfig(
    val streamType: StreamType = StreamType.LIVE,
    val isLowEndDevice: Boolean = false,
    val enableHdr: Boolean = false,
    val maxCacheSizeMb: Int = 50,
    val networkCachingMs: Int = 1500,
    val enableDeinterlacing: Boolean = false,
    val audioNormalization: Boolean = false,
    val preferredAudioLanguage: String? = null,
    val preferredSubtitleLanguage: String? = null,
)

/**
 * Engine events — errors, completion, etc.
 */
sealed class EngineEvent {
    data object MediaLoaded : EngineEvent()
    data object PlaybackStarted : EngineEvent()
    data object PlaybackCompleted : EngineEvent()
    data class Error(val error: EngineError) : EngineEvent()
    data object Buffering : EngineEvent()
    data object BufferingComplete : EngineEvent()
    data class TrackChanged(val type: TrackType, val trackId: Int) : EngineEvent()
    data class VideoSizeChanged(val width: Int, val height: Int) : EngineEvent()
}

/**
 * Structured error type for engine failures.
 */
data class EngineError(
    val code: ErrorCode,
    val message: String,
    val cause: Throwable? = null,
    val isRecoverable: Boolean = false,
)

enum class ErrorCode {
    INITIALIZATION_FAILED,
    NETWORK_ERROR,
    STREAM_NOT_FOUND,
    CODEC_NOT_SUPPORTED,
    DRM_ERROR,
    SURFACE_ERROR,
    TIMEOUT,
    UNKNOWN,
}
