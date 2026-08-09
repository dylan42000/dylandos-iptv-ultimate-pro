package com.dylandos.iptv.ultimate.service

import android.os.ParcelFileDescriptor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DYLANDOS IPTV ULTIMATE — Player Recording Bridge
 *
 * Singleton coordinator that prevents double Xtream connection on DVR start.
 *
 * Problem:
 *   When RecordingService opens a NEW LibVLC connection to a URL that PlayerScreen
 *   is already playing, Xtream sees 2 simultaneous connections from the same account.
 *   With a 1-connection subscription the server terminates one → recording dies in < 1 min.
 *
 * Solution (TEE via VLC #duplicate):
 *   RecordingService checks this bridge before opening a new connection.
 *   If the same URL is already playing in PlayerScreen, it posts a TeeRequest here.
 *   PlayerScreen observes the request and reloads VLC with:
 *     :sout=#duplicate{dst=display,dst=std{access=file,mux=ts,dst=outputPath}}
 *   Result: one Xtream connection feeds both real-time display AND file recording.
 *
 * TEE watchdog:
 *   RecordingService polls currentPlaybackUrl every 3 s in TEE mode.
 *   If the player exits (url goes null), the recording is auto-finalized.
 */
object PlayerRecordingBridge {

    // ── Currently playing URL (set/cleared by PlayerScreen) ──────────────────

    @Volatile private var _currentPlaybackUrl: String? = null

    /** Last URL reported by PlayerScreen as actively streaming. Null when player is closed. */
    val currentPlaybackUrl: String? get() = _currentPlaybackUrl

    /** Called by PlayerScreen when VLC starts playing a stream. Not called for DVR playback. */
    fun reportPlaying(url: String) { _currentPlaybackUrl = url }

    /** Called by PlayerScreen onDispose — clears the active URL. */
    fun reportStopped() { _currentPlaybackUrl = null }

    /** True if the given URL is the same as the one currently playing. */
    fun isPlayingUrl(url: String): Boolean =
        urlsMatch(_currentPlaybackUrl, url)

    fun urlsMatch(a: String?, b: String?): Boolean {
        val left = normalizeStreamUrl(a)
        val right = normalizeStreamUrl(b)
        return left.isNotBlank() && left == right
    }

    private fun normalizeStreamUrl(url: String?): String =
        url
            ?.trim()
            ?.replace("&amp;", "&")
            ?.trimEnd('/')
            .orEmpty()

    // ── TEE start request (RecordingService → PlayerScreen) ──────────────────

    /**
     * Carries the parameters needed by PlayerScreen to reload VLC with #duplicate.
     *
     * @param recordingId  DVR recording ID — used to track and later stop the slot.
     * @param outputPath   LibVLC-compatible output path:
     *                       SAF   → /proc/self/fd/{n}
     *                       Local → /data/user/0/.../DVR/Channel_20250420_123456.ts
     * @param pfd          Non-null for SAF recordings — kept alive by RecordingService
     *                     until doStopRecording() is called; null for internal storage.
     */
    data class TeeRequest(
        val recordingId: String,
        val outputPath:  String,
        val pfd:         ParcelFileDescriptor?
    )

    private val _teeRequest = MutableStateFlow<TeeRequest?>(null)

    /** Observed by PlayerScreen to trigger VLC #duplicate reload. */
    val teeRequest: StateFlow<TeeRequest?> = _teeRequest.asStateFlow()

    fun postTeeRequest(req: TeeRequest) { _teeRequest.value = req }
    fun consumeTeeRequest()             { _teeRequest.value = null }

    // ── TEE stop request (RecordingService → PlayerScreen) ───────────────────

    private val _teeStopId = MutableStateFlow<String?>(null)

    /**
     * Observed by PlayerScreen to remove :sout from VLC (reload clean without #duplicate).
     * Set by RecordingService when user stops a TEE recording from the DVR screen.
     */
    val teeStopId: StateFlow<String?> = _teeStopId.asStateFlow()

    fun postTeeStop(recordingId: String) { _teeStopId.value = recordingId }
    fun consumeTeeStop()                 { _teeStopId.value = null }
}
