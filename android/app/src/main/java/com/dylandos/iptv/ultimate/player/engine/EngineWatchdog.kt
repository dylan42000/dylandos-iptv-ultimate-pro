package com.dylandos.iptv.ultimate.player.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * Real-time watchdog for media playback engines (LibVLC, MPV, Media3).
 *
 * Monitors:
 * 1. First-frame delay: Flags stream as STALLED if no video frame arrives within [firstFrameTimeoutMs].
 * 2. Decoder freeze: Flags stream as STALLED if playback is reported active but media position
 *    fails to advance for [stallTimeoutMs].
 * 3. Sticky buffering: Flags stream as STALLED if player remains in buffering state for [bufferingTimeoutMs].
 */
class EngineWatchdog(
    private val firstFrameTimeoutMs: Long = 8_000L,
    private val stallTimeoutMs: Long = 6_000L,
    private val bufferingTimeoutMs: Long = 9_000L,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    enum class State {
        IDLE,
        ARMED,
        PLAYING,
        STALLED,
        RECOVERING
    }

    enum class StallReason {
        NONE,
        FIRST_FRAME_TIMEOUT,
        DECODER_FREEZE,
        STICKY_BUFFERING
    }

    data class StallEvent(
        val reason: StallReason,
        val elapsedMs: Long,
        val suggestedRecovery: RecoveryAction
    )

    enum class RecoveryAction {
        NONE,
        RETRY_SAME_ENGINE,
        SWITCH_FORMAT,
        SWITCH_ENGINE
    }

    private var state: State = State.IDLE
    private var stallReason: StallReason = StallReason.NONE

    private var armedAt: Long = 0L
    private var firstFrameAt: Long = 0L
    private var lastProgressAt: Long = 0L
    private var lastPositionSeenMs: Long = -1L
    private var bufferingStartedAt: Long = 0L
    private var isBuffering: Boolean = false

    private var consecutiveStallCount: Int = 0

    private val _stallEvents = MutableSharedFlow<StallEvent>(extraBufferCapacity = 16)
    val stallEvents: SharedFlow<StallEvent> = _stallEvents.asSharedFlow()

    fun currentState(): State = state
    fun currentStallReason(): StallReason = stallReason
    fun getConsecutiveStallCount(): Int = consecutiveStallCount

    /** Arm the watchdog when a stream begins loading or playing. */
    fun arm() {
        val now = clock()
        state = State.ARMED
        stallReason = StallReason.NONE
        armedAt = now
        firstFrameAt = 0L
        lastProgressAt = now
        lastPositionSeenMs = -1L
        bufferingStartedAt = 0L
        isBuffering = false
        Timber.d("EngineWatchdog armed at $now")
    }

    /** Disarm when stream is stopped or closed cleanly. */
    fun disarm() {
        state = State.IDLE
        stallReason = StallReason.NONE
        consecutiveStallCount = 0
        isBuffering = false
        Timber.d("EngineWatchdog disarmed")
    }

    /** Called when the first frame or Playing event is received. */
    fun onFirstFrameRendered() {
        val now = clock()
        firstFrameAt = now
        lastProgressAt = now
        if (state == State.ARMED || state == State.STALLED) {
            state = State.PLAYING
            stallReason = StallReason.NONE
            consecutiveStallCount = 0
            Timber.d("EngineWatchdog first frame confirmed at $now")
        }
    }

    /**
     * Called with the current media playback position in ms.
     * Only updates last progress timestamp when position actually moves forward.
     */
    fun onProgress(positionMs: Long) {
        val now = clock()
        if (positionMs > lastPositionSeenMs) {
            lastPositionSeenMs = positionMs
            lastProgressAt = now
            if (state == State.ARMED) {
                state = State.PLAYING
                stallReason = StallReason.NONE
            }
        }
    }

    /** Called when buffering status changes. */
    fun onBufferingChanged(buffering: Boolean) {
        val now = clock()
        isBuffering = buffering
        if (buffering) {
            if (bufferingStartedAt == 0L) {
                bufferingStartedAt = now
            }
        } else {
            bufferingStartedAt = 0L
            if (state == State.PLAYING) {
                lastProgressAt = now
            }
        }
    }

    /**
     * Periodic tick (called every 500ms - 1000ms from playback loop).
     * Checks all timeout conditions.
     */
    fun tick(): StallEvent? {
        if (state == State.IDLE || state == State.RECOVERING) return null
        val now = clock()

        // 1. First-frame check
        if (state == State.ARMED && firstFrameAt == 0L) {
            val waitTime = now - armedAt
            if (waitTime > firstFrameTimeoutMs) {
                return triggerStall(StallReason.FIRST_FRAME_TIMEOUT, waitTime)
            }
        }

        // 2. Sticky buffering check
        if (isBuffering && bufferingStartedAt > 0L) {
            val bufferTime = now - bufferingStartedAt
            if (bufferTime > bufferingTimeoutMs) {
                return triggerStall(StallReason.STICKY_BUFFERING, bufferTime)
            }
        }

        // 3. Decoder freeze check (only while active and not buffering)
        if (state == State.PLAYING && !isBuffering && lastProgressAt > 0L) {
            val stalledTime = now - lastProgressAt
            if (stalledTime > stallTimeoutMs) {
                return triggerStall(StallReason.DECODER_FREEZE, stalledTime)
            }
        }

        return null
    }

    private fun triggerStall(reason: StallReason, elapsedMs: Long): StallEvent {
        state = State.STALLED
        stallReason = reason
        consecutiveStallCount++

        val action = when {
            consecutiveStallCount == 1 -> RecoveryAction.RETRY_SAME_ENGINE
            consecutiveStallCount == 2 -> RecoveryAction.SWITCH_FORMAT
            else -> RecoveryAction.SWITCH_ENGINE
        }

        val event = StallEvent(reason, elapsedMs, action)
        Timber.w("EngineWatchdog STALL detected: reason=$reason, elapsed=${elapsedMs}ms, action=$action (stallCount=$consecutiveStallCount)")
        _stallEvents.tryEmit(event)
        return event
    }

    /** Signal that a recovery attempt is in progress. */
    fun markRecovering() {
        state = State.RECOVERING
        armedAt = clock()
        lastProgressAt = armedAt
    }
}
