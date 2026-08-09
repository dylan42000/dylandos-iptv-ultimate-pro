package com.dylandos.iptv.ultimate.player.engine

import android.content.Context
import android.net.Uri
import com.dylandos.iptv.ultimate.player.LibVlcFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import timber.log.Timber
import java.io.File
import javax.inject.Inject

/**
 * LibVlcEngine — Adapts the existing LibVLC player into the [MediaEngine] interface.
 *
 * Wraps LibVLC 3.6.0 and translates its event callback system into our unified
 * [PlaybackState] / [EngineEvent] Flows.
 *
 * Key responsibilities:
 * - Create/destroy LibVLC + MediaPlayer instances via [LibVlcFactory]
 * - Translate VLC events into unified [EngineEvent]s
 * - Manage VLC surface attachment (VLCVout)
 * - Provide DVR recording via the VLC :sout stream-copy mux
 * - Poll position every 500 ms (LibVLC does not push position changes)
 *
 * Threading: LibVLC fires events on its own threads. All StateFlow updates
 * are dispatched to [stateScope] (Main.immediate) for UI thread safety.
 */
class LibVlcEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vlcFactory: LibVlcFactory
) : MediaEngine {

    override val engineName = "LibVLC"
    override val engineId = "libvlc"
    override val supportsDvrRecording = true

    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    private var currentMedia: Media? = null
    private var currentConfig: EngineConfig? = null
    private var recordingActive = false

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

    // LibVLC doesn't push position events — we poll every 500 ms
    private var positionPollingJob: Job? = null

    // === Lifecycle ===

    override suspend fun initialize(config: EngineConfig): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                currentConfig = config

                // Select the appropriate LibVLC option set based on stream type
                val vlcOptions = when (config.streamType) {
                    StreamType.LIVE -> vlcFactory.buildLiveOptions(config)
                    StreamType.VOD, StreamType.SERIES -> vlcFactory.buildVodOptions(config)
                    StreamType.RECORDING -> vlcFactory.buildRecordingOptions()
                }

                libVlc = LibVLC(context, vlcOptions)
                mediaPlayer = MediaPlayer(libVlc!!).apply {
                    setEventListener(vlcEventListener)
                }

                isInitialized = true
                Timber.d("LibVLC engine initialized for ${config.streamType}")
                true
            } catch (e: Exception) {
                Timber.e(e, "LibVLC initialization failed")
                _playbackState.update {
                    it.copy(
                        phase = PlaybackPhase.ERROR,
                        error = EngineError(
                            ErrorCode.INITIALIZATION_FAILED,
                            "LibVLC init failed: ${e.message}",
                            e
                        )
                    )
                }
                false
            }
        }
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            positionPollingJob?.cancel()
            try {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) mp.stop()
                    mp.vlcVout?.detachViews()
                    mp.release()
                }
                currentMedia?.release()
                libVlc?.release()
            } catch (e: Exception) {
                Timber.e(e, "Error releasing LibVLC")
            }
            mediaPlayer = null
            currentMedia = null
            libVlc = null
            isInitialized = false
            _playbackState.value = PlaybackState()
            Timber.d("LibVLC engine released")
        }
    }

    // === Playback ===

    override suspend fun loadMedia(source: MediaSource): Boolean {
        if (!isInitialized) {
            Timber.e("LibVLC not initialized")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                _playbackState.update { it.copy(phase = PlaybackPhase.LOADING) }

                // Release previous media object
                currentMedia?.release()

                val media = Media(libVlc!!, Uri.parse(source.url))

                // Apply HTTP headers
                source.headers.forEach { (key, value) ->
                    media.addOption(":http-header=$key: $value")
                }

                // Start position for resume
                if (source.startPositionMs > 0) {
                    media.addOption(":start-time=${source.startPositionMs / 1000.0}")
                }

                // Network caching from config
                currentConfig?.let { cfg ->
                    media.addOption(":network-caching=${cfg.networkCachingMs}")
                }

                currentMedia = media
                mediaPlayer?.media = media

                // Trigger playback
                mediaPlayer?.play()

                // Begin position polling
                startPositionPolling()

                Timber.d("LibVLC loading: ${source.url}")
                true
            } catch (e: Exception) {
                Timber.e(e, "LibVLC failed to load media")
                _playbackState.update {
                    it.copy(
                        phase = PlaybackPhase.ERROR,
                        error = EngineError(ErrorCode.NETWORK_ERROR, e.message ?: "Unknown", e)
                    )
                }
                false
            }
        }
    }

    override fun play() {
        mediaPlayer?.play()
    }

    override fun pause() {
        mediaPlayer?.pause()
    }

    override fun togglePlayPause() {
        mediaPlayer?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    override fun stop() {
        positionPollingJob?.cancel()
        mediaPlayer?.stop()
        _playbackState.update { it.copy(phase = PlaybackPhase.STOPPED, isPlaying = false) }
    }

    override fun seekTo(positionMs: Long) {
        mediaPlayer?.let { mp ->
            val duration = mp.length
            if (duration > 0) {
                mp.position = positionMs.toFloat() / duration.toFloat()
            }
        }
    }

    override fun seekRelative(deltaMs: Long) {
        mediaPlayer?.let { mp ->
            val targetMs = (mp.time + deltaMs).coerceIn(0, mp.length)
            mp.time = targetMs
        }
    }

    // === Track Selection ===

    override fun selectAudioTrack(trackId: Int) {
        mediaPlayer?.audioTrack = trackId
    }

    override fun selectSubtitleTrack(trackId: Int) {
        mediaPlayer?.spuTrack = trackId
    }

    override fun disableSubtitles() {
        mediaPlayer?.spuTrack = -1
    }

    override fun addExternalSubtitle(path: String) {
        // Media.Slave.Type.Subtitle == 0 (libvlc_media_slave_type_subtitle)
        mediaPlayer?.addSlave(0, Uri.parse(path), true)
    }

    // === Adjustments ===

    override fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed
        _playbackState.update { it.copy(speed = speed) }
    }

    override fun setVolume(volume: Int) {
        mediaPlayer?.volume = volume
        _playbackState.update { it.copy(volume = volume) }
    }

    override fun setAspectRatio(ratio: AspectRatio) {
        mediaPlayer?.let { mp ->
            when (ratio) {
                AspectRatio.FIT -> mp.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
                AspectRatio.FILL -> mp.videoScale = MediaPlayer.ScaleType.SURFACE_FILL
                else -> mp.aspectRatio = ratio.value
            }
        }
    }

    // === DVR Recording ===

    override fun startRecording(outputPath: String): Boolean {
        if (!isInitialized || recordingActive) return false
        return try {
            File(outputPath).parentFile?.mkdirs()
            currentMedia?.let { media ->
                media.addOption(":sout=#std{access=file,mux=ts,dst=$outputPath}")
                media.addOption(":sout-keep")
                // Restart playback with recording sout enabled
                mediaPlayer?.media = media
                mediaPlayer?.play()
                recordingActive = true
                Timber.d("LibVLC DVR recording started: $outputPath")
                true
            } ?: false
        } catch (e: Exception) {
            Timber.e(e, "Failed to start DVR recording")
            false
        }
    }

    override fun stopRecording() {
        if (!recordingActive) return
        currentMedia?.let { media ->
            // Reload fresh media without the :sout option to stop recording
            val url = media.uri.toString()
            media.release()
            val freshMedia = Media(libVlc!!, Uri.parse(url))
            currentMedia = freshMedia
            mediaPlayer?.media = freshMedia
            mediaPlayer?.play()
        }
        recordingActive = false
        Timber.d("LibVLC DVR recording stopped")
    }

    // === Debug ===

    override fun getDebugInfo(): Map<String, String> {
        val mp = mediaPlayer ?: return mapOf("status" to "not initialized")
        return buildMap {
            put("engine", "LibVLC 3.6.0")
            put("state", if (mp.isPlaying) "playing" else "paused")
            put("position", "${mp.time}ms / ${mp.length}ms")
            put("buffer", "${(mp.position * 100).toInt()}%")
            put("audio_track", mp.audioTrack.toString())
            put("spu_track", mp.spuTrack.toString())
            put("rate", mp.rate.toString())
            put("recording", recordingActive.toString())
        }
    }

    // ─── Private: VLC Event Listener ─────────────────────────────────────────

    private val vlcEventListener = MediaPlayer.EventListener { event ->
        stateScope.launch {
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    _playbackState.update {
                        it.copy(
                            phase = PlaybackPhase.PLAYING,
                            isPlaying = true,
                            isPaused = false,
                            isBuffering = false,
                        )
                    }
                    _engineEvents.emit(EngineEvent.PlaybackStarted)
                    refreshVlcTracks()
                }
                MediaPlayer.Event.Paused -> {
                    _playbackState.update {
                        it.copy(phase = PlaybackPhase.PAUSED, isPlaying = false, isPaused = true)
                    }
                }
                MediaPlayer.Event.Stopped -> {
                    _playbackState.update {
                        it.copy(phase = PlaybackPhase.STOPPED, isPlaying = false)
                    }
                }
                MediaPlayer.Event.EndReached -> {
                    _playbackState.update { it.copy(phase = PlaybackPhase.END_OF_MEDIA) }
                    _engineEvents.emit(EngineEvent.PlaybackCompleted)
                }
                MediaPlayer.Event.EncounteredError -> {
                    val error = EngineError(
                        ErrorCode.UNKNOWN,
                        "LibVLC playback error",
                        isRecoverable = true
                    )
                    _playbackState.update {
                        it.copy(phase = PlaybackPhase.ERROR, error = error)
                    }
                    _engineEvents.emit(EngineEvent.Error(error))
                }
                MediaPlayer.Event.Buffering -> {
                    val percent = event.buffering.toInt()
                    val buffering = percent < 100
                    _playbackState.update {
                        it.copy(
                            isBuffering = buffering,
                            bufferPercent = percent,
                            phase = if (buffering) PlaybackPhase.BUFFERING else PlaybackPhase.PLAYING
                        )
                    }
                    if (buffering) {
                        _engineEvents.emit(EngineEvent.Buffering)
                    } else {
                        _engineEvents.emit(EngineEvent.BufferingComplete)
                    }
                }
                MediaPlayer.Event.Vout -> {
                    // Video output count changed — update video dimensions
                    mediaPlayer?.let { mp ->
                        val vTrack = mp.currentVideoTrack
                        if (vTrack != null) {
                            _playbackState.update {
                                it.copy(
                                    videoWidth = vTrack.width,
                                    videoHeight = vTrack.height,
                                    videoCodec = vTrack.codec.toString()
                                )
                            }
                            _engineEvents.emit(
                                EngineEvent.VideoSizeChanged(vTrack.width, vTrack.height)
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * Poll position and duration every 500 ms.
     * LibVLC does not push time-position changes; we must poll.
     */
    private fun startPositionPolling() {
        positionPollingJob?.cancel()
        positionPollingJob = stateScope.launch {
            while (isActive) {
                mediaPlayer?.let { mp ->
                    if (mp.isPlaying) {
                        _playbackState.update {
                            it.copy(
                                positionMs = mp.time,
                                durationMs = mp.length,
                            )
                        }
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * Refresh the audio and subtitle track list from LibVLC.
     */
    private fun refreshVlcTracks() {
        val mp = mediaPlayer ?: return
        val tracks = mutableListOf<TrackDescriptor>()

        mp.audioTracks?.forEach { track ->
            tracks.add(
                TrackDescriptor(
                    id = track.id,
                    type = TrackType.AUDIO,
                    title = track.name,
                    language = null,
                    codec = null,
                    isDefault = track.id == mp.audioTrack,
                )
            )
        }

        mp.spuTracks?.forEach { track ->
            tracks.add(
                TrackDescriptor(
                    id = track.id,
                    type = TrackType.SUBTITLE,
                    title = track.name,
                    language = null,
                    codec = null,
                    isDefault = track.id == mp.spuTrack,
                )
            )
        }

        _trackList.value = tracks
    }
}
