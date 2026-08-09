package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.net.Uri
import java.io.File

/**
 * DYLANDOS IPTV ULTIMATE — DVR Storage Option
 *
 * Sealed class representing each tier of storage the user can choose
 * for DVR recordings. Shown in [DvrStoragePickerScreen].
 *
 * Tier 1 — OTG/USB via getExternalFilesDirs (no SAF needed)
 * Tier 2 — Internal app-private storage on the device
 * Tier 3 — SAF-picked custom folder (advanced, FireOS workaround required)
 * Tier 4 — SMB network share (future — placeholder shown but disabled)
 */
sealed class DvrStorageOption {

    /**
     * App-private internal storage (/data/data/<pkg>/files/DVR).
     * Always available, but limited space on Firestick (typically 8GB total).
     */
    data class AppPrivateInternal(
        val path: File,
        val freeSpaceBytes: Long
    ) : DvrStorageOption() {
        val freeSpaceGB: Float get() = freeSpaceBytes / 1_073_741_824f
    }

    /**
     * App-private path on an external/removable volume detected via
     * ContextCompat.getExternalFilesDirs(). This is the PRIMARY target for
     * OTG USB recording on FireOS 8 — no SAF picker required.
     *
     * Example path:
     *   /storage/ABCD-EF01/Android/data/com.dylandos.iptv.ultimate.firestick/files
     */
    data class AppPrivateExternal(
        val path: File,
        val label: String,
        val freeSpaceBytes: Long,
        val isOtg: Boolean = true
    ) : DvrStorageOption() {
        val freeSpaceGB: Float get() = freeSpaceBytes / 1_073_741_824f
    }

    /**
     * User picked a custom folder via ACTION_OPEN_DOCUMENT_TREE (SAF).
     * The [treeUri] persists across reboots via takePersistableUriPermission().
     * This is the fallback path for non-FireOS devices or advanced users.
     */
    data class SafPicked(
        val treeUri: Uri,
        val label: String
    ) : DvrStorageOption()

    /**
     * SMB/CIFS network share recording. Placeholder — shown in UI but
     * currently unavailable pending jcifs-ng integration.
     */
    data object NetworkSmb : DvrStorageOption()
}

fun DvrStorageOption.persistedStorageValue(): String? = when (this) {
    is DvrStorageOption.AppPrivateExternal -> path.absolutePath
    is DvrStorageOption.AppPrivateInternal -> path.absolutePath
    is DvrStorageOption.SafPicked -> treeUri.toString()
    is DvrStorageOption.NetworkSmb -> null
}
