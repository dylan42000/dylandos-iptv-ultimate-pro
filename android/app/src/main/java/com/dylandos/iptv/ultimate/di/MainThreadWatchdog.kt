package com.dylandos.iptv.ultimate.di

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MainThreadWatchdog — Detects and logs main thread UI stalls exceeding [stallThresholdMs].
 *
 * Runs on a dedicated background supervisor to post heartbeat tasks to the Main Looper.
 * If a heartbeat isn't serviced before the timeout, it logs the main thread's stack trace
 * to assist in identifying blocking calls without crashing the app.
 */
class MainThreadWatchdog(
    private val stallThresholdMs: Long = 2_000L,
    private val checkIntervalMs: Long = 1_500L
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Timber.i("MainThreadWatchdog started (threshold=${stallThresholdMs}ms)")

        scope.launch {
            while (running.get() && isActive) {
                val responded = AtomicBoolean(false)
                val postTime = System.currentTimeMillis()

                mainHandler.post {
                    responded.set(true)
                }

                delay(stallThresholdMs)

                if (!responded.get()) {
                    val mainThread = Looper.getMainLooper().thread
                    val stackTrace = mainThread.stackTrace.joinToString("\n\tat ") { it.toString() }
                    val blockedDuration = System.currentTimeMillis() - postTime
                    Timber.w("MAIN THREAD STALL DETECTED: blocked for ~${blockedDuration}ms\nMain Thread Stack:\n\tat $stackTrace")
                }

                delay(checkIntervalMs)
            }
        }
    }

    fun stop() {
        running.set(false)
        scope.cancel()
        Timber.i("MainThreadWatchdog stopped")
    }
}
