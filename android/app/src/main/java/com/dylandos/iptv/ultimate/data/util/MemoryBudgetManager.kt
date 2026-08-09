package com.dylandos.iptv.ultimate.data.util

import android.app.ActivityManager
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryBudgetManager — Dynamically allocates memory budgets for different subsystems.
 *
 * On Firestick (< 2.5 GB RAM) we must be aggressive to prevent OOM kills.
 * On mid-range (3–4 GB) and high-end (4+ GB) devices we can be generous.
 *
 * Budget allocation strategy:
 * ┌────────────────────────┬──────────┬───────────┬───────────┐
 * │ Subsystem              │ LOW_END  │ MID_RANGE │ HIGH_END  │
 * ├────────────────────────┼──────────┼───────────┼───────────┤
 * │ Image cache (Coil)     │  8 % heap│ 12 % heap │ 15 % heap │
 * │   — min/max clamp      │  16–32 MB│  32–64 MB │ 64–128 MB │
 * │ Disk image cache       │  50 MB   │  100 MB   │  200 MB   │
 * │ Engine buffers (VLC/M3)│ 15 % heap│ 20 % heap │ 25 % heap │
 * │   — min/max clamp      │  24–48 MB│  48–96 MB │ 96–192 MB │
 * │ Room/data layer        │   8 MB   │   16 MB   │   32 MB   │
 * └────────────────────────┴──────────┴───────────┴───────────┘
 *
 * These values are exposed as [budget] (computed once, lazily) and consumed by:
 * - DylandosApp.newImageLoader()   — Coil memory + disk cache sizes
 * - LibVlcFactory / LazyExoPlayerHost / PlayerScreen — buffer budgets
 * - Room MemoryCacheSize           — DB write-ahead log hint
 */
@Singleton
class MemoryBudgetManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** Tier classification for a device based on total physical RAM. */
    enum class DeviceTier {
        /** Firestick, Firestick Lite, low-cost Android TV boxes — < 2.5 GB RAM */
        LOW_END,
        /** Fire TV Cube Gen 1/2, mid-range Android TV — 2.5–4.5 GB RAM */
        MID_RANGE,
        /** Premium Android TV, Shield Pro, high-end phones — > 4.5 GB RAM */
        HIGH_END,
    }

    /**
     * Calculated memory budget for all subsystems.
     * All byte values are in MB.
     */
    data class MemoryBudget(
        /** JVM max heap size (Runtime.maxMemory()) in MB */
        val totalHeapMb: Int,
        /** Physical device RAM in MB (from ActivityManager) */
        val totalRamMb: Int,
        /** Coil in-memory bitmap cache size in MB */
        val imageCacheMb: Int,
        /** Coil disk cache size in MB */
        val imageDiskCacheMb: Int,
        /** LibVLC / Media3 internal buffer size in MB */
        val engineBufferMb: Int,
        /** Room DB memory budget in MB */
        val roomCacheMb: Int,
        /** True when the device is a Firestick-class low-end device */
        val isLowEndDevice: Boolean,
        /** Tier enum for switch logic in callers */
        val deviceTier: DeviceTier,
    )

    /**
     * The computed budget. Evaluated once on first access; result is cached.
     *
     * Callers should read this in Application.onCreate() (after Hilt injection)
     * so the lazy computation does not block the main thread later.
     */
    val budget: MemoryBudget by lazy { calculateBudget() }

    // ─────────────────────────────────────────────────────────────────────────

    private fun calculateBudget(): MemoryBudget {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        // JVM heap ceiling — what Android allocated to this process
        val maxHeapMb = (Runtime.getRuntime().maxMemory() / (1024L * 1024L)).toInt()

        // Total device RAM — used for tier classification only
        val totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt()

        val tier: DeviceTier = when {
            totalRamMb < 2500 -> DeviceTier.LOW_END
            totalRamMb < 4500 -> DeviceTier.MID_RANGE
            else              -> DeviceTier.HIGH_END
        }

        val computed: MemoryBudget = when (tier) {
            DeviceTier.LOW_END -> MemoryBudget(
                totalHeapMb     = maxHeapMb,
                totalRamMb      = totalRamMb,
                // 8 % heap for images, hard-clamped 16–32 MB
                imageCacheMb    = (maxHeapMb * 0.08).toInt().coerceIn(16, 32),
                imageDiskCacheMb = 50,
                // 15 % heap for the engine, hard-clamped 24–48 MB
                engineBufferMb  = (maxHeapMb * 0.15).toInt().coerceIn(24, 48),
                roomCacheMb     = 8,
                isLowEndDevice  = true,
                deviceTier      = tier,
            )
            DeviceTier.MID_RANGE -> MemoryBudget(
                totalHeapMb     = maxHeapMb,
                totalRamMb      = totalRamMb,
                imageCacheMb    = (maxHeapMb * 0.12).toInt().coerceIn(32, 64),
                imageDiskCacheMb = 100,
                engineBufferMb  = (maxHeapMb * 0.20).toInt().coerceIn(48, 96),
                roomCacheMb     = 16,
                isLowEndDevice  = false,
                deviceTier      = tier,
            )
            DeviceTier.HIGH_END -> MemoryBudget(
                totalHeapMb     = maxHeapMb,
                totalRamMb      = totalRamMb,
                imageCacheMb    = (maxHeapMb * 0.15).toInt().coerceIn(64, 128),
                imageDiskCacheMb = 200,
                engineBufferMb  = (maxHeapMb * 0.25).toInt().coerceIn(96, 192),
                roomCacheMb     = 32,
                isLowEndDevice  = false,
                deviceTier      = tier,
            )
        }

        Timber.d(
            "MemoryBudget: tier=$tier | RAM=${totalRamMb}MB | heap=${maxHeapMb}MB | " +
            "imgCache=${computed.imageCacheMb}MB | diskCache=${computed.imageDiskCacheMb}MB | " +
            "engine=${computed.engineBufferMb}MB"
        )

        return computed
    }
}
