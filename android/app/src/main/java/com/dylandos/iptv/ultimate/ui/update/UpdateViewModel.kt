package com.dylandos.iptv.ultimate.ui.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.model.UpdateInfo
import com.dylandos.iptv.ultimate.data.network.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * DYLANDOS IPTV — OTA Update ViewModel
 *
 * Called from MainActivity on launch.  Manages the full update lifecycle:
 *   CHECK → AVAILABLE → DOWNLOADING (with progress) → INSTALL
 *
 * Update flow:
 *   1. App starts → checkForUpdate() runs in background
 *   2. If remote versionCode > local → UpdateState.AVAILABLE shown to user
 *   3. User taps "Update Now" → APK downloaded via DownloadManager
 *   4. Progress tracked via polling loop
 *   5. On completion → Android package installer launched
 */
sealed interface UpdateState {
    object Idle       : UpdateState
    object Checking   : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progressPct: Int) : UpdateState
    data class ReadyToInstall(val info: UpdateInfo, val localUri: Uri) : UpdateState
    data class Error(val message: String) : UpdateState
}

@HiltViewModel
class UpdateViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val updateChecker: UpdateChecker
) : ViewModel() {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var downloadId: Long = -1L
    private var downloadReceiver: BroadcastReceiver? = null
    private var lastCheckElapsedMs: Long = 0L
    // AtomicBoolean ensures only one coroutine wins the race to handle download completion
    // (both the polling loop and the BroadcastReceiver can fire at almost the same time).
    private val completionHandled = AtomicBoolean(false)

    companion object {
        private const val CHECK_COOLDOWN_MS = 15 * 60 * 1000L
    }

    /** Called from MainActivity on start/resume — runs a silent background check. */
    fun checkOnLaunch(force: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheckElapsedMs < CHECK_COOLDOWN_MS) return
        lastCheckElapsedMs = now

        viewModelScope.launch {
            _state.value = UpdateState.Checking
            val info = updateChecker.checkForUpdate()
            _state.value = if (info != null) {
                UpdateState.Available(info)
            } else {
                UpdateState.Idle
            }
        }
    }

    fun checkNow() = checkOnLaunch(force = true)

    /** User tapped "Update Now" — start the APK download. */
    fun startDownload(info: UpdateInfo) {
        unregisterReceiver()
        completionHandled.set(false)
        _state.value = UpdateState.Downloading(info, 0)
        downloadId = updateChecker.enqueueDownload(context, info.apkUrl, info.versionName)
        registerCompletionReceiver(info)
        // Poll progress every second until done
        viewModelScope.launch {
            while (_state.value is UpdateState.Downloading) {
                delay(1000)
                val snapshot = updateChecker.queryDownload(context, downloadId)
                if (snapshot != null) {
                    val pct = if (snapshot.totalBytes > 0) {
                        ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    when (snapshot.status) {
                        DownloadManager.STATUS_FAILED -> {
                            postError("Download failed. Check your connection and retry.")
                            unregisterReceiver()
                        }
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            // Fail-safe: handle completion from polling too, not only broadcast receiver.
                            _state.value = (_state.value as? UpdateState.Downloading)?.copy(progressPct = 100)
                                ?: _state.value
                            // SHA-256 of a 50 MB APK must NOT run on the Main thread — dispatch to IO.
                            viewModelScope.launch(Dispatchers.IO) {
                                if (!tryHandleDownloadCompletion(info)) {
                                    Timber.w("OTA: completion pending after STATUS_SUCCESSFUL (uri not ready yet)")
                                }
                            }
                        }
                        else -> {
                            _state.value = UpdateState.Downloading(info, pct)
                        }
                    }
                }
            }
        }
    }

    /** User dismissed the update dialog. */
    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    /** Trigger the system installer for the downloaded APK. */
    fun installApk(context: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                val permissionIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(permissionIntent)
                postError("Allow 'Install unknown apps' for DYLANDOS IPTV, then tap Install Now again.")
                return
            }

            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("update_apk", uri)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val started = runCatching {
                context.startActivity(intent)
            }
            if (started.isFailure) {
                // Fire OS fallback: some devices respond better to ACTION_VIEW install intents.
                val fallback = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    clipData = ClipData.newRawUri("update_apk", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(fallback)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch installer")
            postError("Could not launch installer: ${e.message}")
        }
    }

    private fun registerCompletionReceiver(info: UpdateInfo) {
        downloadReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != downloadId) return
                // BroadcastReceiver.onReceive() runs on the main thread.
                // SHA-256 verification reads 50 MB+ — MUST be on an IO coroutine.
                viewModelScope.launch(Dispatchers.IO) {
                    if (!tryHandleDownloadCompletion(info)) {
                        Timber.w("OTA: receiver fired but local download URI/file not ready yet")
                    }
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        downloadReceiver?.let { receiver ->
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    private fun getDownloadedFile(versionName: String): File? {
        val dir = context.getExternalFilesDir("Downloads") ?: return null
        val safeVersionName = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val file = File(dir, "dylandos-iptv-${safeVersionName}.apk")
        return if (file.exists()) file else null
    }

    private fun tryHandleDownloadCompletion(info: UpdateInfo): Boolean {
        // compareAndSet(false, true) returns true only once across all concurrent callers.
        if (!completionHandled.compareAndSet(false, true)) return true

        val uri = updateChecker.getDownloadUri(context, downloadId)
        val apkFile = getDownloadedFile(info.versionName)
        if (uri == null && apkFile == null) {
            // Reset to false so the background loop can try again in 1 second
            completionHandled.set(false) 
            return false
        }
        
        // (We removed the broken `completionHandled = true` line because the 
        // compareAndSet check at the top of the function already sets it to true!)

        if (apkFile != null) {
            if (!verifyFileSizeIfProvided(apkFile, info.apkSize)) {
                postError("Downloaded APK size mismatch. Please retry update.")
                unregisterReceiver()
                return true
            }
            if (!verifySha256(apkFile, info.sha256)) {
                postError("Update blocked: APK integrity check failed (SHA-256 mismatch or missing checksum). Please retry update.")
                unregisterReceiver()
                return true
            }
        }

        val installUri = when {
            apkFile != null -> {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    apkFile
                )
            }
            uri != null -> uri
            else -> {
                postError("Download completed but installer file could not be resolved.")
                unregisterReceiver()
                return true
            }
        }

        _state.value = UpdateState.ReadyToInstall(info, installUri)
        unregisterReceiver()
        return true
    }

    private fun verifyFileSizeIfProvided(file: File, expectedSizeBytes: Long?): Boolean {
        if (expectedSizeBytes == null || expectedSizeBytes <= 0L) return true
        val actual = file.length()
        val matches = actual == expectedSizeBytes
        if (!matches) {
            Timber.e("OTA size mismatch: expected=%d actual=%d", expectedSizeBytes, actual)
        }
        return matches
    }

    private fun verifySha256(file: File, expectedSha256: String?): Boolean {
        // MANDATORY: a missing checksum is a hard failure, never a skip. The OTA
        // metadata layer (UpdateChecker.parseUpdateJson) already refuses payloads
        // without SHA-256; this is the last line of defense so an unverified APK
        // can never reach the installer.
        if (expectedSha256.isNullOrBlank()) {
            Timber.e("OTA: APK has no SHA-256 checksum to verify against — refusing install")
            return false
        }
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = actual.equals(expectedSha256.trim(), ignoreCase = true)
            if (!matches) {
                Timber.e("OTA checksum mismatch: expected=%s actual=%s", expectedSha256, actual)
            }
            matches
        } catch (e: Exception) {
            Timber.e(e, "Failed to verify OTA checksum")
            false
        }
    }

    private fun unregisterReceiver() {
        downloadReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            downloadReceiver = null
        }
    }

    private fun postError(message: String) {
        _state.value = UpdateState.Error(message)
        viewModelScope.launch(Dispatchers.Main.immediate) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterReceiver()
    }
}
