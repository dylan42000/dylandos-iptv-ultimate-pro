package com.dylandos.iptv.ultimate.player

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MpvEngineFactory — Creates and configures MPV player instances.
 *
 * MPV uses a configuration file (mpv.conf) and supports runtime property
 * changes via the client API. This factory generates optimized configs
 * for different playback scenarios.
 *
 * Architecture note: Unlike LibVLC which takes command-line-style options
 * at init time, MPV reads a config file and also accepts property changes
 * at any time during playback. We write the config file to the app's
 * files directory and point MPV to it.
 */
@Singleton
class MpvEngineFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Directory where mpv.conf and subfont.ttf live
    private val mpvConfigDir: File by lazy {
        File(context.filesDir, "mpv").also { it.mkdirs() }
    }

    // Directory for MPV's font cache
    private val mpvFontDir: File by lazy {
        File(mpvConfigDir, "fonts").also { it.mkdirs() }
    }

    // Directory for MPV's shader cache (for GPU shader compilation)
    @Suppress("unused")
    private val mpvCacheDir: File by lazy {
        File(context.cacheDir, "mpv").also { it.mkdirs() }
    }

    /**
     * Playback profile definitions.
     * Each profile is a set of MPV properties optimized for a specific use case.
     */
    enum class PlaybackProfile {
        /** Live TV — low latency, fast channel switching */
        LIVE_TV,
        /** VOD — high quality, full post-processing */
        VOD_HIGH_QUALITY,
        /** VOD on low-end device — reduced post-processing */
        VOD_LOW_END,
        /** HDR content — tone mapping and color management */
        HDR_CONTENT,
        /** Audio-only — no video output, minimal resources */
        AUDIO_ONLY
    }

    /**
     * Initialize the MPV config directory with base configuration.
     * Call this once during Application.onCreate().
     */
    fun initialize() {
        writeBaseConfig()
        writeInputConfig()
        extractBundledFonts()
        ensureDefaultSubtitleFont()
        Timber.d("MPV engine initialized. Config dir: ${mpvConfigDir.absolutePath}")
    }

    /** Absolute path for libass font lookup (also used as MPV option). */
    fun subtitleFontsDir(): String = mpvFontDir.absolutePath

    /**
     * Get MPV initialization options for the given profile.
     * These are passed to MPV_CLIENT_API via mpv_set_option_string.
     *
     * @return Map of property name to value
     */
    fun getProfileOptions(profile: PlaybackProfile): Map<String, String> {
        val base = getBaseOptions()
        val profileSpecific = when (profile) {
            PlaybackProfile.LIVE_TV -> getLiveTvOptions()
            PlaybackProfile.VOD_HIGH_QUALITY -> getVodHighQualityOptions()
            PlaybackProfile.VOD_LOW_END -> getVodLowEndOptions()
            PlaybackProfile.HDR_CONTENT -> getHdrOptions()
            PlaybackProfile.AUDIO_ONLY -> getAudioOnlyOptions()
        }
        return base + profileSpecific // profile-specific overrides base
    }

    /**
     * Base options applied to ALL profiles.
     * These handle hardware decoding, demuxer settings, and general behavior.
     */
    private fun getBaseOptions(): Map<String, String> = mapOf(
        // === Hardware Decoding & Video Rendering Pipeline ===
        // Firestick: mediacodec-copy alone blackscreens some Netflix-style HEVC/10-bit
        // VOD while audio still plays. "auto" lets mpv fall back; recovery cycles further.
        "hwdec" to "auto",
        "hwdec-codecs" to "h264,hevc,mpeg2video,mpeg4,vp8,vp9",
        "vd-lavc-threads" to "4",
        "vd-lavc-dr" to "yes",

        // === Video Output & OpenGL ES Optimization ===
        "vo" to "gpu",
        "gpu-context" to "android",
        "gpu-api" to "opengl",
        "opengl-es" to "yes",
        "opengl-pbo" to "yes",
        // Required so gpu VO paints into the Android Surface from attachSurface().
        "force-window" to "yes",
        "video-sync" to "audio",
        "framedrop" to "vo",

        // === Audio Output ===
        "ao" to "opensles,audiotrack",

        // === Demuxer & Network Resilience ===
        "demuxer-max-bytes" to "128MiB",
        "demuxer-max-back-bytes" to "32MiB",
        "demuxer-readahead-secs" to "60",
        "stream-buffer-size" to "4MiB",
        "network-timeout" to "15",
        "http-persistent-connection" to "yes",
        "tls-verify" to "no",
        "demuxer-lavf-o" to "reconnect=1,reconnect_at_eof=1,reconnect_streamed=1,reconnect_delay_max=5",

        // === Cache ===
        "cache" to "yes",
        "cache-secs" to "60",

        // === Subtitles ===
        // vo=gpu + mediacodec-copy (VOD profile) is required for softsub overlays.
        // Direct mediacodec / mediacodec_embed cannot blend libass onto the surface.
        "sub-ass" to "yes",
        "sub-auto" to "fuzzy",
        // Prefer a soft-sub when the container marks one; PlayerScreen also auto-picks
        // by preferred language because many IPTV packs omit the default flag.
        "sid" to "auto",
        "sub-visibility" to "yes",
        // Force style so tiny/transparent embedded ASS styles still read on a TV.
        "sub-ass-override" to "force",
        "sub-font-provider" to "auto",
        "sub-fonts-dir" to mpvFontDir.absolutePath,
        "sub-font" to "sans-serif",
        "sub-font-size" to "55",
        "sub-border-size" to "3",
        "sub-shadow-offset" to "1",
        "sub-shadow-color" to "#80000000",
        "sub-color" to "#FFFFFFFF",
        "sub-border-color" to "#FF000000",
        "sub-pos" to "95",

        // === Misc ===
        "config-dir" to mpvConfigDir.absolutePath,
        "screenshot-directory" to (
            File(context.getExternalFilesDir(null), "screenshots")
                .also { it?.mkdirs() }.toString()
            ),
        "msg-level" to "all=warn,vd=v,vo=v,hwdec=v",

        // Don't pause on init failure — allows partial stream playback
        "stop-playback-on-init-failure" to "no",
        // Prefer video track when multiple exist (some VOD packs audio-first).
        "vid" to "auto",
        "aid" to "auto",
    )

    /**
     * Live TV profile — optimized for low latency and fast channel switching.
     */
    private fun getLiveTvOptions(): Map<String, String> = mapOf(
        // Minimize buffering for live streams
        "cache-secs" to "3",
        "demuxer-max-bytes" to "10MiB",
        "demuxer-max-back-bytes" to "5MiB",

        // Low latency network settings
        "demuxer-lavf-o" to "fflags=+nobuffer+fastseek,analyzeduration=500000,probesize=500000,reconnect=1,reconnect_at_eof=1,reconnect_streamed=1,reconnect_delay_max=3",

        // Disable video filters that add latency
        "vf" to "",

        // Keyframe-only seeking for fast channel switching
        "hr-seek" to "no",

        // MPEG-TS specific: don't wait for full analysis
        "demuxer-lavf-analyzeduration" to "0.5",
        "demuxer-lavf-probesize" to "500000",

        // Reset audio on seek to prevent desync on channel switch
        "audio-stream-silence" to "yes",

        // Drop frames rather than accumulate delay
        "framedrop" to "decoder+vo",

        // Most IPTV streams are progressive
        "deinterlace" to "no",

        // Always maintain correct aspect ratio
        "keepaspect" to "yes",
    )

    /**
     * VOD High Quality profile — full post-processing pipeline.
     * Used on premium devices with ARM64 and 4+ GB RAM.
     */
    private fun getVodHighQualityOptions(): Map<String, String> = mapOf(
        // Large buffer for smooth playback of high-bitrate files
        "cache-secs" to "60",
        "demuxer-readahead-secs" to "60",
        "demuxer-max-bytes" to "150MiB",
        "demuxer-max-back-bytes" to "50MiB",

        // Precise seeking for frame-accurate resume with framedrop
        "hr-seek" to "yes",
        "hr-seek-framedrop" to "yes",

        // Video post-processing — high-quality scaling
        "scale" to "ewa_lanczossharp",
        "cscale" to "ewa_lanczossharp",
        "dscale" to "mitchell",
        "correct-downscaling" to "yes",
        "sigmoid-upscaling" to "yes",

        // Dithering for banding reduction
        "dither-depth" to "auto",
        "temporal-dither" to "yes",

        // Deband filter
        "deband" to "yes",
        "deband-iterations" to "4",
        "deband-threshold" to "48",
        "deband-range" to "16",

        // Interpolation for smooth 24fps→60fps display
        "interpolation" to "yes",
        "tscale" to "oversample",

        // Audio normalization
        "audio-normalize-downmix" to "yes",
        "volume-max" to "200",
        "af" to "dynaudnorm=f=250:g=31:p=0.95",
    )

    /**
     * VOD Low-End profile — ultra-optimized zero-lag processing for Firestick / TV devices.
     */
    private fun getVodLowEndOptions(): Map<String, String> = mapOf(
        "cache-secs" to "60",
        "demuxer-readahead-secs" to "60",
        "demuxer-max-bytes" to "128MiB",
        "demuxer-max-back-bytes" to "32MiB",

        // Instant keyframe seeking for smooth D-pad response
        "hr-seek" to "no",
        "hr-seek-framedrop" to "yes",

        // Prefer copy-mode first on Firestick; black-video recovery can switch.
        "hwdec" to "mediacodec-copy",

        // Bilinear scaling is fastest on constrained devices
        "scale" to "bilinear",
        "cscale" to "bilinear",
        "dscale" to "bilinear",

        // Disable expensive post-processing
        "deband" to "no",
        "interpolation" to "no",
        "correct-downscaling" to "no",
        "sigmoid-upscaling" to "no",
        "temporal-dither" to "no",

        // Conservative frame dropping
        "framedrop" to "vo",

        "audio-pitch-correction" to "no",
        "audio-normalize-downmix" to "no",
        "volume-max" to "150",
    )

    /**
     * HDR content profile — tone mapping for SDR displays.
     */
    private fun getHdrOptions(): Map<String, String> = mapOf(
        "cache-secs" to "30",
        "demuxer-max-bytes" to "100MiB",

        // Tone mapping for HDR → SDR conversion
        "tone-mapping" to "hable",
        "tone-mapping-param" to "0.3",
        "hdr-compute-peak" to "yes",
        "target-trc" to "auto",
        "target-prim" to "auto",

        // Force BT.2020 colorspace handling
        "target-peak" to "auto",

        // High quality scaling
        "scale" to "ewa_lanczossharp",
        "cscale" to "ewa_lanczossharp",

        "hr-seek" to "yes",
        "deband" to "yes",
    )

    /**
     * Audio-only profile — no video rendering.
     */
    private fun getAudioOnlyOptions(): Map<String, String> = mapOf(
        "vo" to "null",
        "vid" to "no",
        "cache-secs" to "60",
        "audio-normalize-downmix" to "yes",
        "volume-max" to "200",
    )

    /**
     * Write the base mpv.conf file.
     * MPV reads this on initialization.
     */
    private fun writeBaseConfig() {
        val config = buildString {
            appendLine("# DYLANDOS IPTV ULTIMATE — MPV Configuration")
            appendLine("# Auto-generated. Do not edit manually.")
            appendLine()
            appendLine("# Use GPU video output")
            appendLine("vo=gpu")
            appendLine("gpu-context=android")
            appendLine("force-window=yes")
            appendLine()
            appendLine("# Hardware decoding (Firestick: auto + runtime recovery)")
            appendLine("hwdec=auto")
            appendLine("hwdec-codecs=h264,hevc,mpeg2video,mpeg4,vp8,vp9")
            appendLine()
            appendLine("# Audio")
            appendLine("ao=opensles,audiotrack")
            appendLine()
            appendLine("# Subtitles (softsubs need fonts + copy-mode hwdec under vo=gpu)")
            appendLine("sub-ass=yes")
            appendLine("sub-auto=fuzzy")
            appendLine("sid=auto")
            appendLine("sub-visibility=yes")
            appendLine("sub-ass-override=force")
            appendLine("sub-fonts-dir=${mpvFontDir.absolutePath}")
            appendLine("sub-font=sans-serif")
            appendLine("sub-font-size=55")
            appendLine("sub-border-size=3")
            appendLine("sub-color=#FFFFFFFF")
            appendLine("sub-border-color=#FF000000")
            appendLine("sub-pos=95")
            appendLine()
            appendLine("# OSD")
            appendLine("osd-level=1")
            appendLine("osd-bar=yes")
        }
        File(mpvConfigDir, "mpv.conf").writeText(config)
    }

    /**
     * Write input.conf for custom key bindings.
     * Handled primarily by Android key events, but defines fallbacks here.
     */
    private fun writeInputConfig() {
        val inputConf = buildString {
            appendLine("# DYLANDOS key bindings")
            appendLine("# Handled by Android key events, but define fallbacks here")
            appendLine("SPACE cycle pause")
            appendLine("m cycle mute")
            appendLine("s screenshot")
            appendLine("f cycle fullscreen")
        }
        File(mpvConfigDir, "input.conf").writeText(inputConf)
    }

    /**
     * Extract bundled subtitle fonts from assets to the MPV fonts directory.
     */
    private fun extractBundledFonts() {
        try {
            val fontsInAssets = context.assets.list("fonts") ?: return
            for (fontName in fontsInAssets) {
                val destFile = File(mpvFontDir, fontName)
                if (!destFile.exists()) {
                    context.assets.open("fonts/$fontName").use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Timber.d("Extracted font: $fontName")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not extract bundled fonts (no fonts/ in assets — this is OK)")
        }
    }

    /**
     * libass needs at least one TTF or text softsubs render as invisible blanks.
     * Prefer assets; otherwise seed from a Fire OS / Android system font into
     * both `fonts/` and classic `subfont.ttf` under the mpv config dir.
     */
    private fun ensureDefaultSubtitleFont() {
        val subfont = File(mpvConfigDir, "subfont.ttf")
        val fontsCopy = File(mpvFontDir, "subfont.ttf")
        if (subfont.exists() && subfont.length() > 1024L &&
            fontsCopy.exists() && fontsCopy.length() > 1024L
        ) {
            return
        }
        val systemCandidates = listOf(
            "/system/fonts/Roboto-Regular.ttf",
            "/system/fonts/Roboto.ttf",
            "/system/fonts/NotoSans-Regular.ttf",
            "/system/fonts/NotoSansCJK-Regular.ttc",
            "/system/fonts/DroidSans.ttf",
            "/system/fonts/DroidSansFallback.ttf",
            "/system/fonts/ComingSoon.ttf",
        )
        val source = systemCandidates
            .map(::File)
            .firstOrNull { it.exists() && it.length() > 1024L }
        if (source == null) {
            Timber.w("No system subtitle font found — text softsubs may be blank")
            return
        }
        runCatching {
            if (!subfont.exists() || subfont.length() < 1024L) {
                source.copyTo(subfont, overwrite = true)
            }
            if (!fontsCopy.exists() || fontsCopy.length() < 1024L) {
                source.copyTo(fontsCopy, overwrite = true)
            }
            Timber.i("Seeded MPV subtitle font from ${source.absolutePath}")
        }.onFailure {
            Timber.w(it, "Failed to seed MPV subtitle font")
        }
    }
}
