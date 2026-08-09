package com.dylandos.iptv.ultimate.ui.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.dylandos.iptv.ultimate.BuildConfig
import java.io.File

/**
 * Fire TV: [OpenDocumentTree] with a null initial URI often never shows a usable picker.
 * We pass an initial **Download** tree on the right volume (e.g. `ABCD-EF00:Download` for USB)
 * so "Use this folder" is practical and FAT32/ExFAT volumes behave like internal storage in SAF.
 */
private const val EXT_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/**
 * `documentId` for [DocumentsContract.buildTreeDocumentUri] for …/Download on a volume.
 */
fun downloadDocumentIdForVolume(context: Context, vol: StorageVolume?): String? {
    if (vol == null) return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && vol.isPrimary) {
        return "primary:Download"
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val u = vol.uuid
        if (!u.isNullOrBlank()) {
            return "$u:Download"
        }
    }
    val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager ?: return null
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        for (dir in context.getExternalFilesDirs(null).filterNotNull()) {
            val v = sm.getStorageVolume(dir) ?: continue
            if (v == vol) {
                val m = Regex("/storage/([^/]+)/").find(dir.absolutePath) ?: continue
                val id = m.groupValues[1]
                if (id != "emulated" && id != "self") {
                    return "$id:Download"
                }
            }
        }
    }
    return null
}

fun buildDownloadTreeInitialUri(context: Context, vol: StorageVolume?): Uri? {
    val id = downloadDocumentIdForVolume(context, vol) ?: return null
    return runCatching { DocumentsContract.buildTreeDocumentUri(EXT_STORAGE_AUTHORITY, id) }.getOrNull()
}

/**
 * Prefer [StorageVolume.createOpenDocumentTreeIntent] and merge in [EXTRA_INITIAL_URI] to Download;
 * if unavailable, a plain [Intent.ACTION_OPEN_DOCUMENT_TREE] with the same initial URI.
 */
fun openTreeIntentForVolume(context: Context, vol: StorageVolume?): Intent {
    val initial = buildDownloadTreeInitialUri(context, vol)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vol != null) {
        val t = runCatching { vol.createOpenDocumentTreeIntent() }.getOrNull()
        if (t != null) {
            t.addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (initial != null) {
                    t.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
                }
            }
            return t
        }
    }
    return genericOpenTreeIntent(context, initial)
}

fun genericOpenTreeIntent(context: Context, initial: Uri? = null): Intent {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    )
    val toUse = initial ?: runCatching {
        DocumentsContract.buildTreeDocumentUri(EXT_STORAGE_AUTHORITY, "primary:Download")
    }.getOrNull()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && toUse != null) {
        i.putExtra(DocumentsContract.EXTRA_INITIAL_URI, toUse)
    }
    if (BuildConfig.DEBUG) {
        i.putExtra(Intent.EXTRA_LOCAL_ONLY, false)
    }
    return i
}

/**
 * Fire TV–safe open document tree intent.
 *
 * Does NOT include [DocumentsContract.EXTRA_INITIAL_URI].  On Fire TV (Fire OS 7 / Android 9)
 * the Amazon document picker crashes or shows a blank screen when EXTRA_INITIAL_URI resolves
 * to a path that doesn't exist on that device.  Launching the plain ACTION_OPEN_DOCUMENT_TREE
 * intent without any initial-URI hint is the most reliable approach across all Firestick models.
 */
fun fireTvSafeOpenTreeIntent(): Intent {
    val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
    i.addFlags(
        Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
    )
    return i
}
