package com.dylandos.iptv.ultimate.data.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.io.File

/**
 * DYLANDOS IPTV ULTIMATE — StorageDetector
 *
 * Detects OTG/USB external storage using getExternalFilesDirs() — the key difference
 * from SAF is that the app already OWNS these directories and requires ZERO user
 * permission grants. This bypasses FireOS 8's broken ACTION_OPEN_DOCUMENT_TREE entirely.
 *
 * OTG path format on FireOS/Android 11+:
 *   /storage/XXXX-XXXX/Android/data/com.dylandos.iptv.ultimate.firestick/files
 *
 * These paths are writable by the app immediately without any permission dialogs.
 */
object StorageDetector {

    private const val TAG = "DVR_STORAGE"

    /**
     * Returns the first detected removable/OTG external storage directory that is
     * writable by the app. Returns null if no USB/OTG drive is mounted.
     *
     * This path does NOT require SAF or any runtime permission on Android 11+.
     * getExternalFilesDirs() returns app-private paths on every mounted volume.
     */
    fun findOtgPath(context: Context): File? {
        val result = writableExternalDirs(context)
            .filter { isRemovableCandidate(context, it) }
            .maxByOrNull { usableBytes(it) }
        if (result != null) {
            Timber.i("$TAG findOtgPath → ${result.absolutePath}")
        } else {
            Timber.d("$TAG findOtgPath → no writable OTG volume found")
        }
        return result
    }

    fun findOtgPublicDownloadPath(context: Context): File? {
        return writableExternalDirs(context)
            .filter { isRemovableCandidate(context, it) }
            .mapNotNull { removableStorageRoot(it) }
            .distinctBy { it.absolutePath }
            .flatMap { root -> listOf(File(root, "Download"), File(root, "Downloads")) }
            .filter { ensureWritableDirectory(it) }
            .maxByOrNull { usableBytes(it) }
            ?.also {
                Timber.i("$TAG findOtgPublicDownloadPath → ${it.absolutePath}")
            }
    }

    fun findPrimaryPublicDownloadPath(): File? {
        val candidates = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File(Environment.getExternalStorageDirectory(), "Download"),
            File(Environment.getExternalStorageDirectory(), "Downloads")
        )
        return candidates.firstOrNull { dir ->
            ensureWritableDirectory(dir)
        }?.also {
            Timber.i("$TAG findPrimaryPublicDownloadPath → ${it.absolutePath}")
        }
    }

    /**
     * Returns the primary external storage directory (emulated internal SD or
     * the built-in "external" storage on Firestick). This is always present
     * on devices with external storage and does not require permissions on API 29+.
     */
    fun findPrimaryExternalPath(context: Context): File? {
        return context.getExternalFilesDir(null)?.takeIf { ensureWritableDirectory(it) }
    }

    fun findWritableAbsolutePath(path: String?): File? {
        if (path.isNullOrBlank() || !path.startsWith("/")) return null
        return runCatching {
            File(path).takeIf { ensureWritableDirectory(it) }
        }.getOrNull()
    }

    fun isProbablyRemovablePath(dir: File): Boolean {
        val path = dir.absolutePath.replace('\\', '/')
        return path.startsWith("/storage/") &&
            !path.contains("/emulated/", ignoreCase = true) &&
            !path.startsWith("/storage/self", ignoreCase = true)
    }

    fun findPreferredDvrBasePath(context: Context, persistedPath: String? = null): File? {
        // Honor an explicit user-selected absolute path first so free-space UI and
        // recordings both target the drive the user just picked (internal OR OTG).
        val persisted = findWritableAbsolutePath(persistedPath)
        if (persisted != null) return persisted

        val publicDownload = findOtgPublicDownloadPath(context)
        if (publicDownload != null) return publicDownload

        val otg = findOtgPath(context)
        if (otg != null) return otg

        val largestExternal = findLargestWritableExternalPath(context)
        if (largestExternal != null) return largestExternal

        val primaryDownload = findPrimaryPublicDownloadPath()
        if (primaryDownload != null) return primaryDownload

        return findPrimaryExternalPath(context)
    }

    fun findPreferredTimeshiftBasePath(context: Context, persistedPath: String? = null): File? {
        val persisted = findWritableAbsolutePath(persistedPath)
        if (persisted != null && isProbablyRemovablePath(persisted)) return persisted

        val publicDownload = findOtgPublicDownloadPath(context)
        if (publicDownload != null) return publicDownload

        val otg = findOtgPath(context)
        if (otg != null) return otg

        if (persisted != null) return persisted

        val largestExternal = findLargestWritableExternalPath(context)
        if (largestExternal != null) return largestExternal

        val primaryDownload = findPrimaryPublicDownloadPath()
        if (primaryDownload != null) return primaryDownload

        return findPrimaryExternalPath(context)
    }

    /**
     * Returns ALL detected external volumes with metadata for the storage picker UI.
     * Ordered: removable (OTG) first, then primary external.
     */
    fun listAllWritableExternalDirs(context: Context): List<ExternalDirInfo> {
        return writableExternalDirs(context)
            .map { dir ->
                ExternalDirInfo(
                    dir       = dir,
                    label     = resolveVolumeLabel(context, dir),
                    freeBytes = usableBytes(dir),
                    isOtg     = isRemovableCandidate(context, dir)
                )
            }
            .sortedWith(compareByDescending<ExternalDirInfo> { it.isOtg }.thenByDescending { it.freeBytes })
    }

    /**
     * Returns all StorageVolume entries (system-level) for debugging or
     * displaying volume UUIDs in the settings screen.
     */
    fun listAllStorageVolumes(context: Context): List<StorageVolume> {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return emptyList()
        return sm.storageVolumes
    }

    /**
     * Logs all volume states and external file dirs to Timber — useful during
     * support sessions or QA runs.
     */
    fun debugLogVolumes(context: Context) {
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            sm.storageVolumes.forEachIndexed { i, vol ->
                Timber.d(
                    "$TAG Volume[$i]: desc=${vol.getDescription(context)}, " +
                    "removable=${vol.isRemovable}, state=${vol.state}, " +
                    "primary=${vol.isPrimary}, uuid=${vol.uuid ?: "N/A"}"
                )
            }
        } else {
            Timber.d("$TAG StorageVolume metadata unavailable below API 24")
        }
        ContextCompat.getExternalFilesDirs(context, null)
            ?.forEachIndexed { i, f ->
                Timber.d(
                    "$TAG ExternalFilesDir[$i]: ${f?.absolutePath}, " +
                    "exists=${f?.exists()}, canWrite=${f?.canWrite()}, " +
                    "freeGB=${"%.2f".format((f?.freeSpace ?: 0L) / 1_073_741_824f)}"
                )
            }
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Returns true if the given directory resides on a removable volume.
     * On API 24+ we can cross-reference via StorageManager.getStorageVolume().
     * On older APIs we fall back to the path heuristic (no "/emulated/").
     */
    private fun isRemovableVolume(context: Context, dir: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val vol = runCatching { sm.getStorageVolume(dir) }.getOrNull()
            if (vol != null) return vol.isRemovable
        }
        // Heuristic for older APIs: emulated storage path contains "/emulated/"
        return !dir.absolutePath.contains("/emulated/")
    }

    private fun isRemovableCandidate(context: Context, dir: File): Boolean =
        isRemovableVolume(context, dir) || isProbablyRemovablePath(dir)

    fun findLargestWritableExternalPath(context: Context): File? =
        writableExternalDirs(context)
            .filter { !it.absolutePath.contains("/cache/", ignoreCase = true) }
            .maxByOrNull { usableBytes(it) }

    private fun writableExternalDirs(context: Context): List<File> =
        (ContextCompat.getExternalFilesDirs(context, null) ?: emptyArray<File?>())
            .filterNotNull()
            .filter { ensureWritableDirectory(it) }

    private fun usableBytes(dir: File): Long =
        maxOf(dir.usableSpace, dir.freeSpace, 0L)

    private fun removableStorageRoot(dir: File): File? {
        val parts = dir.absolutePath.replace('\\', '/').split('/').filter { it.isNotBlank() }
        val storageIndex = parts.indexOf("storage")
        if (storageIndex < 0 || parts.size <= storageIndex + 1) return null
        val volume = parts[storageIndex + 1]
        if (volume.equals("emulated", ignoreCase = true) || volume.equals("self", ignoreCase = true)) {
            return null
        }
        return File("/storage/$volume")
    }

    private fun ensureWritableDirectory(dir: File): Boolean {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching false
            if (!dir.isDirectory || !dir.canWrite()) return@runCatching false
            val probe = File(dir, ".dylandos_write_test")
            probe.writeText("ok")
            probe.delete()
            true
        }.getOrDefault(false)
    }

    /**
     * Resolves a human-readable label for the given external directory.
     * Uses StorageVolume.getDescription() when possible, falls back to "USB Drive".
     */
    private fun resolveVolumeLabel(context: Context, dir: File): String {
        return try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val vol = sm.getStorageVolume(dir)
                if (vol != null) return vol.getDescription(context)
            }
            // Fallback: extract the volume ID token from the path
            val regex = Regex("/storage/([^/]+)/")
            val match = regex.find(dir.absolutePath)
            if (match != null && match.groupValues[1] != "emulated") {
                "USB Drive (${match.groupValues[1]})"
            } else {
                "External Storage"
            }
        } catch (e: Exception) {
            "USB Drive"
        }
    }

    data class ExternalDirInfo(
        val dir: File,
        val label: String,
        val freeBytes: Long,
        val isOtg: Boolean
    ) {
        val freeGB: Float get() = freeBytes / 1_073_741_824f
    }
}
