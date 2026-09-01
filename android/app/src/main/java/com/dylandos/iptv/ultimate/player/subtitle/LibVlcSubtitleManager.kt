package com.dylandos.iptv.ultimate.player.subtitle

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.videolan.libvlc.MediaPlayer

/**
 * Manages LibVLC subtitle/closed-caption tracks for both Live TV and VOD.
 *
 * For Live TV (MPEG-TS): CEA-608/708 CC tracks appear as SPU tracks automatically.
 * For VOD: supports embedded tracks + external SRT/VTT via addSlave().
 *
 * Track ID -1 = subtitles disabled.
 */
class LibVlcSubtitleManager(
    private val mediaPlayer: MediaPlayer,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
) {

    private val _currentTrackId = MutableStateFlow(mediaPlayer.spuTrack)
    val currentTrackId: StateFlow<Int> = _currentTrackId.asStateFlow()

    private val _availableTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val availableTracks: StateFlow<List<SubtitleTrack>> = _availableTracks.asStateFlow()

    private val _settings = MutableStateFlow(SubtitleSettings())
    val settings: StateFlow<SubtitleSettings> = _settings.asStateFlow()

    private var pollJob: Job? = null

    init {
        refreshTracks()
    }

    /** Call after media has started playing to enumerate SPU/CC tracks. */
    fun refreshTracks() {
        val spuCount = mediaPlayer.spuTracksCount
        Log.d(TAG, "SPU track count: $spuCount")
        if (spuCount <= 0) {
            _availableTracks.value = emptyList()
            return
        }

        val rawTracks = mediaPlayer.spuTracks?.toList() ?: emptyList()
        val tracks = rawTracks.map { t ->
            val trackName = t.name?.takeIf { it.isNotBlank() } ?: "Track ${t.id}"
            SubtitleTrack(
                id = t.id,
                name = formatDisplayName(t.id, trackName),
                type = detectType(trackName),
                language = extractLanguage(trackName)
            )
        }

        _availableTracks.value = tracks
        _currentTrackId.value = mediaPlayer.spuTrack
        Log.d(TAG, "Tracks updated: ${tracks.map { "${it.id}:${it.name}" }} (current=${_currentTrackId.value})")
    }

    /**
     * Periodically polls for tracks right after playback starts.
     * MPEG-TS closed captions (CEA-608/708) are demuxed 1-3 seconds into playback.
     */
    fun startTrackDiscovery(durationMs: Long = 6_000L) {
        pollJob?.cancel()
        pollJob = coroutineScope.launch {
            val startTime = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - startTime < durationMs) {
                refreshTracks()
                if (_availableTracks.value.isNotEmpty()) {
                    delay(1_500L)
                } else {
                    delay(600L)
                }
            }
        }
    }

    /** Enable a track by ID. Pass -1 to disable. */
    fun selectTrack(trackId: Int) {
        val result = mediaPlayer.setSpuTrack(trackId)
        if (result || trackId == -1) {
            _currentTrackId.value = trackId
        }
        Log.d(TAG, "setSpuTrack($trackId) → $result (active=${mediaPlayer.spuTrack})")
    }

    fun disableSubtitles() = selectTrack(-1)

    /** Load external subtitle file (SRT/VTT/ASS). VOD only. */
    fun loadExternalSubtitle(uri: String, isDefault: Boolean = true) {
        mediaPlayer.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle, uri, isDefault)
        refreshTracks()
    }

    /** Timing offset in milliseconds (positive = subtitles appear later). */
    fun setSubtitleDelay(delayMs: Long) {
        mediaPlayer.setSpuDelay(delayMs * 1000L) // VLC uses microseconds
    }

    fun updateSettings(settings: SubtitleSettings) {
        _settings.value = settings
        setSubtitleDelay(settings.delayMs)
    }

    private fun formatDisplayName(id: Int, name: String): String {
        return when {
            name.contains("608", ignoreCase = true) -> "CC (CEA-608) - $name"
            name.contains("708", ignoreCase = true) -> "CC (CEA-708) - $name"
            name.contains("teletext", ignoreCase = true) -> "Teletext - $name"
            name.contains("dvb", ignoreCase = true) -> "DVB Subtitle - $name"
            else -> name
        }
    }

    private fun detectType(name: String): SubtitleTrackType = when {
        name.contains("CC", ignoreCase = true) ||
        name.contains("caption", ignoreCase = true) ||
        name.contains("608") || name.contains("708") -> SubtitleTrackType.CLOSED_CAPTION
        name.contains("SDH", ignoreCase = true)      -> SubtitleTrackType.SDH
        name.contains("forced", ignoreCase = true)   -> SubtitleTrackType.FORCED
        else                                          -> SubtitleTrackType.SUBTITLE
    }

    private fun extractLanguage(name: String): String {
        val langs = listOf("English", "Spanish", "French", "German", "Portuguese", "Italian", "Russian", "Arabic")
        return langs.firstOrNull { name.contains(it, ignoreCase = true) } ?: ""
    }

    fun release() {
        pollJob?.cancel()
    }

    companion object { private const val TAG = "LibVlcSubtitleMgr" }
}

data class SubtitleTrack(
    val id: Int,
    val name: String,
    val type: SubtitleTrackType,
    val language: String
)

enum class SubtitleTrackType {
    CLOSED_CAPTION, SDH, FORCED, SUBTITLE
}

data class SubtitleSettings(
    val sizePercent: Int = 100,
    val foregroundColor: Long = 0xFFFFFFFFL,
    val backgroundColor: Long = 0x80000000L,
    val backgroundEnabled: Boolean = true,
    val bold: Boolean = false,
    val position: SubtitlePosition = SubtitlePosition.BOTTOM,
    val delayMs: Long = 0L
)

enum class SubtitlePosition { TOP, MIDDLE, BOTTOM }
