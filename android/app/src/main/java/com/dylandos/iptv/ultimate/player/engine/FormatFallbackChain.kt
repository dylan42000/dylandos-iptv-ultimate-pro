package com.dylandos.iptv.ultimate.player.engine

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * FormatFallbackChain — Manages candidate stream URLs and preferred media engines per stream.
 *
 * Provides:
 * 1. Automatic container rotation (e.g. .ts -> .m3u8 -> alternative endpoints) on failure.
 * 2. Per-channel engine memory ("this channel performs best on LibVLC vs Media3 vs MPV").
 * 3. Fast reset after a stream plays cleanly.
 */
class FormatFallbackChain {

    enum class EngineType {
        LIBVLC,
        MPV,
        MEDIA3
    }

    data class PlaybackCandidate(
        val url: String,
        val preferredEngine: EngineType,
        val extension: String
    )

    private val channelEngineMemory = ConcurrentHashMap<Int, EngineType>()

    /**
     * Builds candidate URLs for a given stream with primary and fallback configurations.
     */
    fun buildLiveCandidates(
        streamId: Int,
        baseUrl: String,
        configuredFormat: String = "ts"
    ): List<PlaybackCandidate> {
        val cleanBase = baseUrl.substringBeforeLast('.')
        val primaryExt = configuredFormat.lowercase().removePrefix(".").ifBlank { "ts" }
        val secondaryExt = if (primaryExt == "m3u8") "ts" else "m3u8"

        val rememberedEngine = channelEngineMemory[streamId] ?: EngineType.LIBVLC

        return listOf(
            PlaybackCandidate(
                url = if (baseUrl.endsWith(".$primaryExt")) baseUrl else "$cleanBase.$primaryExt",
                preferredEngine = rememberedEngine,
                extension = primaryExt
            ),
            PlaybackCandidate(
                url = "$cleanBase.$secondaryExt",
                preferredEngine = if (rememberedEngine == EngineType.LIBVLC) EngineType.MEDIA3 else EngineType.LIBVLC,
                extension = secondaryExt
            ),
            PlaybackCandidate(
                url = if (baseUrl.endsWith(".$primaryExt")) baseUrl else "$cleanBase.$primaryExt",
                preferredEngine = EngineType.MEDIA3,
                extension = primaryExt
            )
        )
    }

    /** Record that a particular engine succeeded for this channel. */
    fun recordEngineSuccess(streamId: Int, engine: EngineType) {
        if (streamId > 0) {
            channelEngineMemory[streamId] = engine
            Timber.d("Channel $streamId engine preference saved: $engine")
        }
    }

    /** Retrieve remembered engine for a stream. */
    fun getPreferredEngine(streamId: Int): EngineType? = channelEngineMemory[streamId]

    /** Clear all remembered engine preferences. */
    fun clearMemory() {
        channelEngineMemory.clear()
    }
}
