package com.dylandos.iptv.ultimate.player.subtitle

import android.util.Log
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
class LibVlcSubtitleManager(private val mediaPlayer: MediaPlayer) {

    private val _currentTrackId  = MutableStateFlow(-1)
    val currentTrackId: StateFlow<Int> = _currentTrackId.asStateFlow()

    private val _availableTracks = MutableStateFlow<List<SubtitleTrack>>(emptyList())
    val availableTracks: StateFlow<List<SubtitleTrack>> = _availableTracks.asStateFlow()

    private val _settings = MutableStateFlow(SubtitleSettings())
    val settings: StateFlow<SubtitleSettings> = _settings.asStateFlow()

    /** Call after media has started playing to enumerate SPU/CC tracks. */
    fun refreshTracks() {
        val spuCount = mediaPlayer.spuTracksCount
        Log.d(TAG, "SPU track count: $spuCount")
        if (spuCount <= 0) { _availableTracks.value = emptyList(); return }

        val tracks = mediaPlayer.spuTracks?.mapNotNull { t ->
            SubtitleTrack(
                id       = t.id,
                name     = t.name ?: "Track ${t.id}",
                type     = detectType(t.name ?: ""),
                language = extractLanguage(t.name ?: "")
            )
        } ?: emptyList()

        _availableTracks.value = tracks
        Log.d(TAG, "Tracks: ${tracks.map { "${it.id}:${it.name}" }}")
    }

    /** Enable a track by ID. Pass -1 to disable. */
    fun selectTrack(trackId: Int) {
        val result = mediaPlayer.setSpuTrack(trackId)
        if (result) _currentTrackId.value = trackId
        Log.d(TAG, "setSpuTrack($trackId) → $result")
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

    private fun detectType(name: String): SubtitleTrackType = when {
        name.contains("CC", ignoreCase = true) ||
        name.contains("caption", ignoreCase = true) ||
        name.contains("608") || name.contains("708") -> SubtitleTrackType.CLOSED_CAPTION
        name.contains("SDH", ignoreCase = true)      -> SubtitleTrackType.SDH
        name.contains("forced", ignoreCase = true)   -> SubtitleTrackType.FORCED
        else                                          -> SubtitleTrackType.SUBTITLE
    }

    private fun extractLanguage(name: String): String {
        val langs = listOf("English", "Spanish", "French", "German", "Portuguese")
        return langs.firstOrNull { name.contains(it, ignoreCase = true) } ?: ""
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
