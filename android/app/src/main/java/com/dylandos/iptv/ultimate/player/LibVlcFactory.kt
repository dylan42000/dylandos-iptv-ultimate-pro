package com.dylandos.iptv.ultimate.player

import android.content.Context
import com.dylandos.iptv.ultimate.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.videolan.libvlc.LibVLC
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LibVLC option factory for live TV, VOD, and headless DVR recording.
 * PlayerScreen builds its own LibVLC instances; this remains available for
 * RecordingService and any future shared VLC configuration.
 */
@Singleton
class LibVlcFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun createForLiveTV(): LibVLC = LibVLC(context, buildLiveOptions())

    fun createForVod(): LibVLC = LibVLC(context, buildVodOptions())

    fun createForRecording(): LibVLC = LibVLC(context, buildRecordingOptions())

    fun buildLiveOptions(
        networkCachingMs: Int = 1500,
        enableDeinterlacing: Boolean = false,
    ): ArrayList<String> {
        return arrayListOf(
            "--network-caching=$networkCachingMs",
            "--live-caching=$networkCachingMs",
            "--file-caching=700",
            "--disc-caching=700",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--drop-late-frames",
            "--skip-frames",
            "--rtsp-tcp",
            "--http-reconnect",
            "--http-continuous",
            "--aout=opensles",
            if (BuildConfig.DEBUG) "-v" else "--quiet",
            "--audio-resampler=ugly",
            "--codec=mediacodec_ndk,iomx,all",
            "--mediacodec-dr",
            "--no-mediacodec-adaptive-playback",
            "--codec-threads=2",
            "--no-stats",
        ).also { opts ->
            if (enableDeinterlacing) {
                opts.add("--deinterlace=1")
                opts.add("--deinterlace-mode=yadif")
            }
        }
    }

    fun buildVodOptions(
        isLowEndDevice: Boolean = false,
        audioNormalization: Boolean = false,
    ): ArrayList<String> {
        val cachingMs = if (isLowEndDevice) 2000 else 3000
        return arrayListOf(
            "--network-caching=$cachingMs",
            "--file-caching=$cachingMs",
            "--disc-caching=1000",
            "--sout-mux-caching=$cachingMs",
            "--http-reconnect",
            "--aout=opensles",
            if (BuildConfig.DEBUG) "-v" else "--quiet",
            if (isLowEndDevice) "--audio-resampler=ugly" else "--audio-resampler=soxr",
            "--codec=mediacodec_ndk,iomx,all",
            "--mediacodec-dr",
            "--no-mediacodec-adaptive-playback",
            "--codec-threads=2",
            "--no-stats",
        ).also { opts ->
            if (audioNormalization) {
                opts.add("--audio-filter=normvol")
            }
        }
    }

    fun buildRecordingOptions(): ArrayList<String> {
        return arrayListOf(
            "--aout=dummy",
            "--vout=dummy",
            "--no-audio",
            "--no-video",
            "--network-caching=3000",
            "--live-caching=3000",
            "--file-caching=1000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--no-drop-late-frames",
            "--sout-mux-caching=3000",
            "--no-video-title-show",
            "--no-stats",
            "--no-sub-autodetect-file",
        )
    }

    fun buildLiveOptions(config: com.dylandos.iptv.ultimate.player.engine.EngineConfig): ArrayList<String> =
        buildLiveOptions(
            networkCachingMs = config.networkCachingMs,
            enableDeinterlacing = config.enableDeinterlacing,
        )

    fun buildVodOptions(config: com.dylandos.iptv.ultimate.player.engine.EngineConfig): ArrayList<String> =
        buildVodOptions(
            isLowEndDevice = config.isLowEndDevice,
            audioNormalization = config.audioNormalization,
        )
}
