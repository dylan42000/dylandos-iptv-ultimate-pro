package com.dylandos.iptv.ultimate.player.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * LiveBufferController — adaptive network-cache controller for live TV (v5.0).
 *
 * The old design used a static `--network-caching` value (default 800 ms). On a
 * jittery 2.4 GHz WiFi connection a fixed small buffer underruns whenever latency
 * spikes, producing the classic "channel periodically lags" symptom. This controller
 * raises the cache quickly after a rebuffer and relaxes it slowly during clean
 * playback, so the buffer self-tunes to the network instead of guessing once.
 *
 * Pure Kotlin + coroutines flows, no Android imports — fully unit-testable.
 *
 * Usage (see PlayerScreen wiring instructions in save_point.md):
 *   val controller = LiveBufferController(floorMs, ceilingMs)
 *   // poll every ~2 s while a live channel plays:
 *   controller.onEvent(if (buffering) LiveBufferEvent.REBUFFER else LiveBufferEvent.HEALTHY_TICK)
 *   // use controller.cacheMs.value when building live media options:
 *   buildMedia(..., liveCacheMs = controller.cacheMs.value, ...)
 *
 * Tuning:
 *  - REBUFFER  → cache += min(400 ms, ceiling − cache); after 6 raises within a
 *                session the controller stops raising and suggests "Adaptive HD mode".
 *  - HEALTHY_TICK (every 2 s) → after 90 consecutive healthy ticks (~3 min) the
 *                cache relaxes by 200 ms toward the floor. Any rebuffer resets the
 *                healthy counter so we never relax right after a spike.
 */
enum class LiveBufferEvent { REBUFFER, HEALTHY_TICK }

class LiveBufferController(
    val floorMs: Int = 600,
    val ceilingMs: Int = 4000,
    private val maxRaises: Int = 6,
    /** Optional starting cache (honours the user's static setting); defaults to [floorMs]. */
    initialCacheMs: Int? = null,
) {
    init {
        require(floorMs in 100..20_000) { "floorMs out of range: $floorMs" }
        require(ceilingMs >= floorMs) { "ceilingMs ($ceilingMs) must be >= floorMs ($floorMs)" }
        require(maxRaises > 0) { "maxRaises must be > 0" }
    }

    /** Session start value — where the controller begins (and reset() returns to). */
    private val startMs = (initialCacheMs ?: floorMs).coerceIn(floorMs, ceilingMs)

    private val _cacheMs = MutableStateFlow(startMs)

    /** Current recommended network cache in ms — the value callers feed to LibVLC. */
    val cacheMs: StateFlow<Int> = _cacheMs.asStateFlow()

    /** How many times the buffer has been raised this session (diagnostics). */
    var raiseCount: Int = 0
        private set

    /** True after [maxRaises] raises — suggest the user switch to a lower-bitrate server. */
    var suggestHdMode: Boolean = false
        private set

    private var healthyTicks = 0
    private var lastRaiseAtMs = 0L

    /**
     * Feed a playback event. `nowMs` is injectable for deterministic tests.
     */
    fun onEvent(event: LiveBufferEvent, nowMs: Long = System.currentTimeMillis()) {
        when (event) {
            LiveBufferEvent.REBUFFER -> raise(nowMs)
            LiveBufferEvent.HEALTHY_TICK -> relax(nowMs)
        }
    }

    /** Start a fresh session (new channel): back to the session start cache, counters cleared. */
    fun reset() {
        _cacheMs.value = startMs
        raiseCount = 0
        suggestHdMode = false
        healthyTicks = 0
        lastRaiseAtMs = 0L
    }

    private fun raise(nowMs: Long) {
        if (raiseCount >= maxRaises) {
            suggestHdMode = true
            return
        }
        val room = (ceilingMs - _cacheMs.value).coerceAtLeast(0)
        if (room <= 0) {
            suggestHdMode = true
            return
        }
        _cacheMs.value = (_cacheMs.value + minOf(400, room)).coerceAtMost(ceilingMs)
        raiseCount++
        lastRaiseAtMs = nowMs
        healthyTicks = 0
    }

    private fun relax(nowMs: Long) {
        // Never relax during the first minute after a raise — the spike may not be over.
        if (nowMs - lastRaiseAtMs < 60_000L) return
        if (_cacheMs.value <= floorMs) return
        healthyTicks++
        // 90 healthy 2 s ticks ≈ 3 minutes of clean playback.
        if (healthyTicks >= 90) {
            _cacheMs.value = (_cacheMs.value - 200).coerceAtLeast(floorMs)
            healthyTicks = 0
        }
    }
}
