package com.dylandos.iptv.ultimate.player.engine

import android.content.Context
import com.dylandos.iptv.ultimate.player.MpvEngineFactory
import com.dylandos.iptv.ultimate.player.MpvEvent
import com.dylandos.iptv.ultimate.player.MpvPlayerWrapper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject

/**
 * MpvEngine — Adapts [MpvPlayerWrapper] into the [MediaEngine] interface.
 *
 * This translates [MpvPlayerWrapper] state and events into our unified
 * [PlaybackState] and [EngineEvent] flows, matching the same contract
 * as [LibVlcEngine] so [DualMediaEngine] can swap engines transparently.
 *
 * Bridging strategy:
 *   MpvPlayerWrapper.playbackState → unified PlaybackState (field-by-field)
 *   MpvPlayerWrapper.tracks        → List<TrackDescriptor>
 *   MpvPlayerWrapper.events        → EngineEvent
 *
 * All collection jobs are started in [startStateCollection] and cancelled in [release].
 */
class MpvEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mpvWrapper: MpvPlayerWrapper,
    private val mpvFactory: MpvEngineFactory,
) : MediaEngine {

    override val engineName = "MPV"
    override val engineId = "mpv"
    override val supportsDvrRecording = false // MPV has no :sout equivalent

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // === State Flows ===

    private val _playbackState = MutableStateFlow(PlaybackState())
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _trackList = MutableStateFlow<List<TrackDescriptor>>(emptyList())
    override val trackList: StateFlow<List<TrackDescriptor>> = _trackList.asStateFlow()

    private val _engineEvents = MutableSharedFlow<EngineEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val engineEvents: SharedFlow<EngineEvent> = _engineEvents.asSharedFlow()

    override var isInitialized: Boolean = false
        private set

    private var stateCollectionJob: Job? = null
    private var trackCollectionJob: Job? = null
    private var eventCollectionJob: Job? = null

    // === Lifecycle ===

    override suspend fun initialize(config: EngineConfig): Boolean {
        return try {
            // Map EngineConfig to the appropriate MPV playback profile
            val profile = when {
                config.streamType == StreamType.LIVE ->
                    MpvEngineFactory.PlaybackProfile.LIVE_TV
                config.enableHdr ->
                    MpvEngineFactory.PlaybackProfile.HDR_CONTENT
                config.isLowEndDevice ->
                    MpvEngineFactory.PlaybackProfile.VOD_LOW_END
                else ->
                    MpvEngineFactory.PlaybackProfile.VOD_HIGH_QUALITY
            }

            val ok = mpvWrapper.initialize(profile)
            if (!ok) {
                Timber.e("MPV initialization returned false")
                _playbackState.update {
                    it.copy(
                        phase = PlaybackPhase.ERROR,
                        error = EngineError(
                            ErrorCode.INITIALIZATION_FAILED,
                            mpvWrapper.playbackState.value.error ?: "MPV init failed"
                        )
                    )
                }
                return false
            }
            startStateCollection()
            isInitialized = true
            Timber.d("MPV engine initialized with profile: $profile")
            true
        } catch (e: Exception) {
            Timber.e(e, "MPV initialization failed")
            _playbackState.update {
                it.copy(
                    phase = PlaybackPhase.ERROR,
                    error = EngineError(ErrorCode.INITIALIZATION_FAILED, e.message ?: "", e)
                )
            }
            false
        }
    }

    override suspend fun release() {
        stateCollectionJob?.cancel()
        trackCollectionJob?.cancel()
        eventCollectionJob?.cancel()
        mpvWrapper.destroy()
        isInitialized = false
        _playbackState.value = PlaybackState()
        _trackList.value = emptyList()
        Timber.d("MPV engine released")
    }

    // === Playback ===

    override suspend fun loadMedia(source: MediaSource): Boolean {
        if (!isInitialized) return false
        _playbackState.update { it.copy(phase = PlaybackPhase.LOADING) }
        mpvWrapper.loadUrl(
            url = source.url,
            headers = source.headers,
            startPosition = source.startPositionMs
        )
        return true
    }

    override fun play() = mpvWrapper.play()
    override fun pause() = mpvWrapper.pause()
    override fun togglePlayPause() = mpvWrapper.togglePlayPause()
    override fun stop() = mpvWrapper.stop()
    override fun seekTo(positionMs: Long) = mpvWrapper.seekTo(positionMs)
    override fun seekRelative(deltaMs: Long) = mpvWrapper.seekRelative(deltaMs)

    // === Track Selection ===

    override fun selectAudioTrack(trackId: Int) = mpvWrapper.selectAudioTrack(trackId)
    override fun selectSubtitleTrack(trackId: Int) = mpvWrapper.selectSubtitleTrack(trackId)
    override fun disableSubtitles() = mpvWrapper.selectSubtitleTrack(-1)
    override fun addExternalSubtitle(path: String) = mpvWrapper.addSubtitleFile(path)

    // === Adjustments ===

    override fun setPlaybackSpeed(speed: Float) {
        mpvWrapper.setSpeed(speed)
        _playbackState.update { it.copy(speed = speed) }
    }

    override fun setVolume(volume: Int) {
        mpvWrapper.setVolume(volume)
        _playbackState.update { it.copy(volume = volume) }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        // MPV handles aspect ratio via --video-aspect-override property.
        // Full implementation is deferred to MpvPlayerWrapper if needed.
        Timber.d("MPV setAspectRatio: ${ratio.label} (not yet implemented in wrapper)")
    }

    // === Debug ===

    override fun getDebugInfo(): Map<String, String> {
        val state = _playbackState.value
        return mapOf(
            "engine" to "MPV (libmpv)",
            "state" to state.phase.name,
            "position" to "${state.positionMs}ms / ${state.durationMs}ms",
            "video" to "${state.videoWidth}x${state.videoHeight}",
            "codec" to "${state.videoCodec} / ${state.audioCodec}",
            "hw_decoder" to state.hwDecoder,
            "fps" to "%.1f".format(state.fps),
            "dropped" to state.droppedFrames.toString(),
        )
    }

    // ─── Private: State bridging ──────────────────────────────────────────────

    /**
     * Collect [MpvPlayerWrapper] flows and bridge them into the unified types.
     * All three jobs run concurrently under [stateScope].
     */
    private fun startStateCollection() {
        stateCollectionJob?.cancel()
        trackCollectionJob?.cancel()
        eventCollectionJob?.cancel()

        // Bridge MpvPlaybackState → unified PlaybackState
        stateCollectionJob = stateScope.launch {
            mpvWrapper.playbackState.collect { mpvState ->
                _playbackState.update { current ->
                    current.copy(
                        isPlaying = mpvState.isPlaying,
                        isPaused = mpvState.isPaused,
                        isBuffering = mpvState.isBuffering,
                        positionMs = mpvState.position,
                        durationMs = mpvState.duration,
                        bufferPercent = mpvState.bufferPercent,
                        videoWidth = mpvState.videoWidth,
                        videoHeight = mpvState.videoHeight,
                        videoCodec = mpvState.videoCodec,
                        audioCodec = mpvState.audioCodec,
                        hwDecoder = mpvState.hwDecoder,
                        fps = mpvState.fps,
                        bitrate = mpvState.bitrate,
                        droppedFrames = mpvState.droppedFrames,
                        phase = when {
                            mpvState.error != null -> PlaybackPhase.ERROR
                            mpvState.isBuffering -> PlaybackPhase.BUFFERING
                            mpvState.isPlaying -> PlaybackPhase.PLAYING
                            mpvState.isPaused -> PlaybackPhase.PAUSED
                            else -> current.phase
                        },
                        error = mpvState.error?.let {
                            EngineError(ErrorCode.UNKNOWN, it, isRecoverable = true)
                        }
                    )
                }
            }
        }

        // Bridge MpvPlayerWrapper.TrackInfo → unified TrackDescriptor
        trackCollectionJob = stateScope.launch {
            mpvWrapper.tracks.collect { mpvTracks ->
                _trackList.value = mpvTracks.map { track ->
                    TrackDescriptor(
                        id = track.id,
                        type = when (track.type) {
                            "video" -> TrackType.VIDEO
                            "audio" -> TrackType.AUDIO
                            "sub" -> TrackType.SUBTITLE
                            else -> TrackType.AUDIO
                        },
                        title = track.title,
                        language = track.language,
                        codec = track.codec,
                        isDefault = track.isDefault,
                        isForced = track.isForced,
                        isExternal = track.isExternal,
                    )
                }
            }
        }

        // Bridge MpvEvent → unified EngineEvent
        eventCollectionJob = stateScope.launch {
            mpvWrapper.events.collect { event ->
                val engineEvent: EngineEvent? = when (event) {
                    is MpvEvent.FileLoaded -> EngineEvent.MediaLoaded
                    is MpvEvent.PlaybackRestart -> EngineEvent.PlaybackStarted
                    is MpvEvent.EndFile -> EngineEvent.PlaybackCompleted
                    else -> null
                }
                engineEvent?.let { _engineEvents.emit(it) }
            }
        }
    }
}
