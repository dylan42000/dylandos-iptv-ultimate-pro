package com.dylandos.iptv.ultimate

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentCallbacks2
import android.os.Build
import android.os.Looper
import coil.imageLoader
import io.sentry.Sentry
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import coil.EventListener
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.dylandos.iptv.ultimate.data.util.AppStartupProfiler
import com.dylandos.iptv.ultimate.data.util.FieldTelemetry
import com.dylandos.iptv.ultimate.data.util.MemoryBudgetManager
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.util.TimeFormatter
import com.dylandos.iptv.ultimate.workers.WatchHistoryPruneWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE - Application Class
 * 
 * Main application entry point with:
 * - Hilt dependency injection
 * - Timber logging initialization
 * - Notification channels for recordings
 * - WorkManager configuration
 */
@HiltAndroidApp
class DylandosApp : Application(), Configuration.Provider, ImageLoaderFactory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var memoryBudgetManager: MemoryBudgetManager
    @Inject lateinit var startupProfiler: AppStartupProfiler
    @Inject lateinit var fieldTelemetry: FieldTelemetry
    // Injected so Coil uses the same OkHttp 5 client that already works for all API calls.
    // Without this, Coil 2.7.0 tries to instantiate its own OkHttpClient using OkHttp 5
    // alpha APIs which have breaking changes from OkHttp 4.x (MediaType.parse() removed,
    // ResponseBody constructor changes), causing ALL image loads to silently fail.
    @Inject lateinit var okHttpClient: OkHttpClient
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // T0: first line of onCreate — used as baseline for all startup timing.
        startupProfiler.recordMilestone("app_create_start")

        // Crash-safe startup: if ANYTHING in this method throws, write a marker
        // file so the next launch can show a "recovered from crash" message.
        // The uncaught exception handler below catches the rest.
        try {
            unsafeOnCreate()
        } catch (t: Throwable) {
            Timber.e(t, "FATAL: DylandosApp.onCreate() crashed")
            runCatching { fieldTelemetry.recordCrash("main", t) }
            throw t  // re-throw so Android shows the crash dialog
        }
    }

    private fun unsafeOnCreate() {
        // T0b: split from onCreate() so we can catch ALL init failures.
        
        // Initialize Timber logging — fast, no disk IO
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Global last-resort crash handler: anonymized telemetry + crash_log.txt, then system default.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.e(throwable, "Uncaught exception on thread ${thread.name}")
            runCatching { fieldTelemetry.recordCrash(thread.name, throwable) }

            // Keep the app alive when a background thread crashes.
            // Main-thread crashes still delegate to the Android default handler.
            if (thread == mainThread) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

        Timber.d("DYLANDOS IPTV ULTIMATE starting...")

        // Log device tier so we can verify budget allocation in logcat.
        val budget = memoryBudgetManager.budget
        Timber.d(
            "Device tier: ${budget.deviceTier} | RAM: ${budget.totalRamMb}MB | " +
            "heap: ${budget.totalHeapMb}MB | imgCache: ${budget.imageCacheMb}MB | " +
            "engineBuf: ${budget.engineBufferMb}MB"
        )
        startupProfiler.recordMilestone("memory_budget_ready")
        
        // Notification channels must be on main thread (system call, near-instant)
        createNotificationChannels()

        // Coil: budget-aware image loader (memory + disk cache sized per device tier).
        // initCoil() is called before WorkManager so the image loader is ready for any
        // coroutine that might fire from WatchHistoryPruneWorker on startup.
        initCoil()
        startupProfiler.recordMilestone("coil_ready")

        // WorkManager is initialized manually because Startup provider is removed in manifest.
        initializeWorkManagerSafely()
        startupProfiler.recordMilestone("work_manager_ready")

        // Defer maintenance scheduling to background and make it non-fatal.
        applicationScope.launch(Dispatchers.IO) {
            scheduleWatchHistoryPruneSafely()
        }

        // Fire OS occasionally exposes UTC as the process default despite a local system
        // clock. Load the user's render-only guide timezone before any EPG refresh. This
        // never alters programme epochs, catch-up URLs, or DVR schedule times.
        applicationScope.launch(Dispatchers.IO) {
            val zone = dataStore.data.first()[SettingsViewModel.KEY_EPG_DISPLAY_TIME_ZONE]
                ?: "America/Denver"
            TimeFormatter.setDisplayTimeZone(zone)
            Timber.i("Guide display timezone: ${TimeFormatter.displayTimeZone().id}")
        }

        // Start MainThreadWatchdog to detect UI thread stalls > 2s
        com.dylandos.iptv.ultimate.di.MainThreadWatchdog().start()

        // B3: guarded crash reporting — blank SENTRY_DSN (default) means the SDK is never
        // initialized and nothing leaves the device. Set SENTRY_DSN to enable remote crashes.
        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            runCatching {
                Sentry.init { options ->
                    options.dsn = BuildConfig.SENTRY_DSN
                    options.environment = if (BuildConfig.DEBUG) "debug" else "release"
                }
                Timber.i("Sentry crash reporting enabled")
            }.onFailure { Timber.w(it, "Sentry init failed — continuing without crash reporting") }
        }
    }

    /**
     * B4: shrink memory-hungry caches when the OS asks. Fire OS LMK is aggressive on
     * 2 GB sticks — trimming Coil caches here prevents the "overwhelmed" force-closes.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            runCatching { this.imageLoader.memoryCache?.clear() }
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            runCatching { this.imageLoader.diskCache?.clear() }
        }
    }

    // ── ImageLoaderFactory (Coil 2.7.0) ────────────────────────────────────
    // Budget-aware loader: sizes memory + disk cache dynamically per device tier.
    //
    // CRITICAL FIX: callFactory(okHttpClient) passes the app's shared and already
    // validated OkHttp client to Coil so poster/logo fetches use the same network
    // stack and headers as the rest of the app.
    override fun newImageLoader(): ImageLoader {
        val budget = memoryBudgetManager.budget

        // Cap concurrent Coil downloads so a 5-column poster grid cannot stampede
        // OkHttp / Firestick RAM while scrolling. Shared app client stays unlimited.
        val coilClient = okHttpClient.newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 6
                    maxRequestsPerHost = 3
                }
            )
            .build()

        return ImageLoader.Builder(this)
            // Force Coil to use a concurrency-capped OkHttp instance.
            .callFactory { coilClient }
            .memoryCache {
                MemoryCache.Builder(this)
                    // Sized in absolute bytes from the budget rather than a fixed %.
                    .maxSizeBytes(budget.imageCacheMb * 1024 * 1024)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_cache"))
                    .maxSizeBytes(budget.imageDiskCacheMb.toLong() * 1024L * 1024L)
                    .build()
            }
            // FireOS compatibility: software bitmaps are more stable under heavy
            // video memory pressure from LibVLC/Media3 playback.
            .allowHardware(false)
            .fetcherDispatcher(
                kotlinx.coroutines.Dispatchers.IO.limitedParallelism(
                    if (budget.isLowEndDevice) 3 else 6
                )
            )
            .decoderDispatcher(
                kotlinx.coroutines.Dispatchers.Default.limitedParallelism(
                    if (budget.isLowEndDevice) 2 else 4
                )
            )
            // Skip cache headers — IPTV logo servers often send no-cache headers.
            .respectCacheHeaders(false)
            // Log every failed image load to Logcat so we can diagnose broken URLs.
            .eventListener(object : EventListener {
                override fun onError(request: ImageRequest, result: ErrorResult) {
                    Timber.e(result.throwable, "Coil [onError] url=${request.data}")
                }
            })
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            // Guard against UninitializedPropertyAccessException if Hilt hasn't injected
            // workerFactory yet (can happen on cold-start in some emulator environments).
            .apply {
                if (::workerFactory.isInitialized) setWorkerFactory(workerFactory)
            }
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) android.util.Log.DEBUG else android.util.Log.ERROR)
            .build()

    private fun initCoil() {
        // No-op: configuration is done via newImageLoader() (ImageLoaderFactory) above.
        // Budget values are read from MemoryBudgetManager at call time.
        val b = memoryBudgetManager.budget
        Timber.d(
            "Coil image loader configured: memCache=${b.imageCacheMb}MB, " +
            "diskCache=${b.imageDiskCacheMb}MB, lowEnd=${b.isLowEndDevice}"
        )
    }

    private fun initializeWorkManagerSafely() {
        runCatching {
            WorkManager.initialize(this, workManagerConfiguration)
            Timber.d("WorkManager initialized manually")
        }.onFailure { e ->
            if (e is IllegalStateException) {
                Timber.d("WorkManager was already initialized")
            } else {
                Timber.e(e, "Failed to initialize WorkManager")
            }
        }
    }

    private fun scheduleWatchHistoryPruneSafely() {
        runCatching {
            WatchHistoryPruneWorker.schedule(this)
            Timber.d("WatchHistoryPruneWorker scheduled")
        }.onFailure { e ->
            Timber.e(e, "Failed to schedule WatchHistoryPruneWorker")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            
            // Recording channel
            val recordingChannel = NotificationChannel(
                CHANNEL_RECORDING,
                "DVR Recordings",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows ongoing DVR recording status"
                setShowBadge(false)
            }
            
            // Download channel
            val downloadChannel = NotificationChannel(
                CHANNEL_DOWNLOAD,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setShowBadge(false)
            }
            
            notificationManager.createNotificationChannel(recordingChannel)
            notificationManager.createNotificationChannel(downloadChannel)
        }
    }

    companion object {
        const val CHANNEL_RECORDING = "recording_channel"
        const val CHANNEL_DOWNLOAD = "download_channel"
    }
}
