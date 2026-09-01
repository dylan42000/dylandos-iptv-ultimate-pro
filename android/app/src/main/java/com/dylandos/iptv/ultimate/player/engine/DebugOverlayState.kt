package com.dylandos.iptv.ultimate.player.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Diagnostic metrics displayed in the in-app debug HUD (toggleable via MENU+0 or settings).
 */
data class DebugMetrics(
    val isVisible: Boolean = false,
    val activeEngine: String = "LibVLC",
    val streamUrl: String = "",
    val networkCacheMs: Int = 800,
    val videoResolution: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val fps: Double = 0.0,
    val bitrateKbps: Long = 0L,
    val droppedFrames: Int = 0,
    val rebufferCount: Int = 0,
    val usedMemoryMb: Long = 0L,
    val maxMemoryMb: Long = 0L
)

class DebugOverlayState {

    private val _metrics = MutableStateFlow(DebugMetrics())
    val metrics: StateFlow<DebugMetrics> = _metrics.asStateFlow()

    fun toggleVisibility() {
        _metrics.update { it.copy(isVisible = !it.isVisible) }
    }

    fun setVisibility(visible: Boolean) {
        _metrics.update { it.copy(isVisible = visible) }
    }

    fun updateEngine(engine: String) {
        _metrics.update { it.copy(activeEngine = engine) }
    }

    fun updateStreamInfo(
        url: String,
        cacheMs: Int,
        resolution: String = "",
        videoCodec: String = "",
        audioCodec: String = ""
    ) {
        _metrics.update {
            it.copy(
                streamUrl = url,
                networkCacheMs = cacheMs,
                videoResolution = resolution.ifBlank { it.videoResolution },
                videoCodec = videoCodec.ifBlank { it.videoCodec },
                audioCodec = audioCodec.ifBlank { it.audioCodec }
            )
        }
    }

    fun updatePerformanceStats(
        fps: Double,
        bitrateKbps: Long,
        droppedFrames: Int,
        rebufferCount: Int
    ) {
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val maxMem = runtime.maxMemory() / (1024 * 1024)

        _metrics.update {
            it.copy(
                fps = fps,
                bitrateKbps = bitrateKbps,
                droppedFrames = droppedFrames,
                rebufferCount = rebufferCount,
                usedMemoryMb = usedMem,
                maxMemoryMb = maxMem
            )
        }
    }
}
