package com.dylandos.iptv.ultimate.ui.screens.dvr

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.StatFs
import androidx.core.app.NotificationCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.DylandosApp
import com.dylandos.iptv.ultimate.data.model.AccountSerializer
import com.dylandos.iptv.ultimate.data.model.SavedAccount
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.repository.DvrRecordingRepository
import com.dylandos.iptv.ultimate.data.util.StorageDetector
import com.dylandos.iptv.ultimate.service.PlayerRecordingBridge
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.workers.ScheduledRecordingWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * DYLANDOS IPTV ULTIMATE — DVR ViewModel
 *
 * Manages recording state via [DvrRecordingRepository] and delegates start/stop
 * to [com.dylandos.iptv.ultimate.service.RecordingService].
 */
sealed interface DvrEvent {
    data class RecordingFailed(val id: String, val channelName: String, val reason: String) : DvrEvent
    data class RecordingStarted(val id: String, val channelName: String) : DvrEvent
    data class RecordingSaved(val id: String, val channelName: String, val sizeBytes: Long) : DvrEvent
}

data class ScheduledRecording(
    val id: String = UUID.randomUUID().toString(),
    val channelName: String,
    val channelId: Int,
    val streamUrl: String,
    val programTitle: String,
    val scheduledStartMs: Long,
    val scheduledEndMs: Long,
    val preBufferMs: Long = 2 * 60_000L,
    val postBufferMs: Long = 5 * 60_000L,
    /** once | series | keyword — series/keyword are rule rows that expand into one-shots. */
    val recordingType: String = "once",
    val matchKeyword: String = ""
) {
    fun formattedTime(): String {
        val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())
        return "${fmt.format(Date(scheduledStartMs))} – ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(scheduledEndMs))}"
    }
}

data class DvrRecording(
    val id: String = UUID.randomUUID().toString(),
    val channelName: String,
    val channelId: Int,
    val streamUrl: String,
    val startTimeMs: Long = System.currentTimeMillis(),
    val stopTimeMs: Long? = null,
    val filePath: String = "",
    val fileSizeBytes: Long = 0L,
    val isActive: Boolean = true
) {
    val durationMs: Long get() = (stopTimeMs ?: System.currentTimeMillis()) - startTimeMs

    fun formattedDuration(): String {
        val totalSeconds = durationMs / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%dh %02dm".format(h, m) else "%dm %02ds".format(m, s)
    }

    fun formattedDate(): String =
        SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault()).format(Date(startTimeMs))

    fun formattedSize(): String = when {
        fileSizeBytes < 1024 * 1024 -> "< 1 MB"
        fileSizeBytes < 1024 * 1024 * 1024 -> "%.1f MB".format(fileSizeBytes / 1_048_576.0)
        else -> "%.2f GB".format(fileSizeBytes / 1_073_741_824.0)
    }
}

data class DvrUiState(
    val scheduledRecordings: List<ScheduledRecording> = emptyList(),
    val activeRecordings: List<DvrRecording> = emptyList(),
    val completedRecordings: List<DvrRecording> = emptyList(),
    val recordingAccounts: List<SavedAccount> = emptyList(),
    val activeRecordingAccountId: String = "",
    val isStorageGranted: Boolean = false,
    val storagePath: String? = null,
    val storageLabel: String = "Checking storage...",
    val storageDetail: String = "",
    val storageFreeBytes: Long = -1L,
    val storageTotalBytes: Long = -1L,
    val storageIsExternalPreferred: Boolean = false,
    val statusMessage: String? = null,
    val stoppingRecordingIds: Set<String> = emptySet(),
    val storageWarning: String? = null
)

@HiltViewModel
class DvrViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xtreamRepository: XtreamRepository,
    private val dvrRepo: DvrRecordingRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DvrUiState())
    val uiState: StateFlow<DvrUiState> = _uiState.asStateFlow()

    val recordingEvents: kotlinx.coroutines.flow.SharedFlow<DvrEvent> = dvrRepo.events

    val isRecording: StateFlow<Set<String>> = dvrRepo.recordingsFlow
        .map { list -> list.filter { it.isActive }.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    init {
        dvrRepo.recordingsFlow.onEach { rebuildState() }.launchIn(viewModelScope)
        dvrRepo.scheduledFlow.onEach { rebuildState() }.launchIn(viewModelScope)
        rebuildState()
        refreshStorageSummary()
        autoPersistUsbStorageIfMounted()
        context.dataStore.data
            .map { prefs -> prefs[DVR_STORAGE_PATH_KEY] }
            .distinctUntilChanged()
            .onEach { path -> applyPersistedStoragePathFromStore(path) }
            .launchIn(viewModelScope)
        context.dataStore.data
            .map { prefs ->
                val all = AccountSerializer.fromJson(
                    prefs[SettingsViewModel.KEY_SAVED_ACCOUNTS] ?: "[]"
                )
                val dvrCapable = all.filter { it.accountType == "both" || it.accountType == "dvr" }
                val activeId = prefs[SettingsViewModel.KEY_ACTIVE_ACCOUNT_ID] ?: ""
                dvrCapable to activeId
            }
            .distinctUntilChanged()
            .onEach { (accounts, activeId) ->
                _uiState.value = _uiState.value.copy(
                    recordingAccounts = accounts,
                    activeRecordingAccountId = activeId
                )
            }
            .launchIn(viewModelScope)
    }

    private fun applyPersistedStoragePathFromStore(path: String?) {
        try {
            when {
                path.isNullOrBlank() -> {
                    _uiState.value = _uiState.value.copy(storagePath = null, isStorageGranted = false)
                }
                hasPersistedWritePermission(path) -> {
                    _uiState.value = _uiState.value.copy(storagePath = path, isStorageGranted = true)
                    Timber.i("DVR: storage path active: $path")
                }
                else -> {
                    // Keep filesystem preferences when USB is unmounted / not writable so
                    // the picker can rematch and UI does not fall back to "internal" forever.
                    // Only clear SAF content:// trees when write permission is truly gone.
                    if (path.startsWith("/")) {
                        _uiState.value = _uiState.value.copy(
                            storagePath = path,
                            isStorageGranted = false
                        )
                        Timber.w("DVR: stored path not writable (may be unmounted) — keeping preference: $path")
                    } else {
                        viewModelScope.launch {
                            runCatching { context.dataStore.edit { it.remove(DVR_STORAGE_PATH_KEY) } }
                        }
                        _uiState.value = _uiState.value.copy(storagePath = null, isStorageGranted = false)
                        Timber.w("DVR: SAF permission missing for stored URI, cleared")
                    }
                }
            }
            refreshStorageSummary()
        } catch (e: Exception) {
            Timber.w(e, "DVR: could not apply storage path from store")
        }
    }

    private fun refreshStorageSummary() {
        val preferred = StorageDetector.findPreferredDvrBasePath(context, _uiState.value.storagePath)
        val otg = StorageDetector.findOtgPath(context)
        val primary = StorageDetector.findPrimaryExternalPath(context)
        val persisted = _uiState.value.storagePath
        val persistedFile = persisted
            ?.takeIf { it.startsWith("/") }
            ?.let { File(it) }
            ?.takeIf { it.exists() || it.mkdirs() }

        val target = preferred ?: otg ?: persistedFile ?: primary ?: context.filesDir
        val stats = storageStatsFor(target)
        val isUsb = StorageDetector.isProbablyRemovablePath(target)
        val label = when {
            isUsb -> "USB / thumb drive preferred"
            persisted?.startsWith("content://") == true -> "Custom SAF folder selected"
            persistedFile != null -> "Custom external folder selected"
            primary != null && target.absolutePath == primary.absolutePath -> "Firestick app storage fallback"
            else -> "Internal storage fallback"
        }
        val detail = when {
            persisted?.startsWith("content://") == true ->
                "SAF folder is still saved. DVR will use the app-owned USB path first when mounted."
            else -> target.absolutePath
        }
        _uiState.value = _uiState.value.copy(
            storageLabel = label,
            storageDetail = detail,
            storageFreeBytes = stats?.first ?: -1L,
            storageTotalBytes = stats?.second ?: -1L,
            storageIsExternalPreferred = isUsb || persistedFile != null || persisted?.startsWith("content://") == true
        )
    }

    private fun autoPersistUsbStorageIfMounted() {
        viewModelScope.launch {
            val usb = StorageDetector.findOtgPath(context) ?: return@launch
            val usbPath = usb.absolutePath
            val current = _uiState.value.storagePath
            if (current == usbPath) return@launch
            if (!current.isNullOrBlank() && current.startsWith("/") &&
                StorageDetector.isProbablyRemovablePath(File(current))
            ) {
                return@launch
            }
            // Only auto-select USB when nothing is persisted yet.
            if (!current.isNullOrBlank()) return@launch
            runCatching {
                context.dataStore.edit { prefs ->
                    prefs[DVR_STORAGE_PATH_KEY] = usbPath
                }
            }.onSuccess {
                Timber.i("DVR: auto-selected mounted USB storage: $usbPath")
            }.onFailure {
                Timber.w(it, "DVR: failed to auto-select USB storage")
            }
        }
    }

    private fun rebuildState() {
        val active = dvrRepo.getActiveRecordings()
        _uiState.value = _uiState.value.copy(
            scheduledRecordings = dvrRepo.getScheduledRecordings(),
            activeRecordings = active,
            completedRecordings = dvrRepo.getCompletedRecordings(),
            stoppingRecordingIds = _uiState.value.stoppingRecordingIds.intersect(active.map { it.id }.toSet())
        )
    }

    fun scheduleRecording(
        channelName: String,
        channelId: Int,
        streamUrl: String,
        programTitle: String,
        startTimeMs: Long,
        endTimeMs: Long,
        preBufferMinutes: Int = 2,
        postBufferMinutes: Int = 5,
        recordingType: String = "once",
        matchKeyword: String = ""
    ) {
        val rec = ScheduledRecording(
            channelName = channelName,
            channelId = channelId,
            streamUrl = streamUrl,
            programTitle = programTitle,
            scheduledStartMs = startTimeMs,
            scheduledEndMs = endTimeMs,
            preBufferMs = preBufferMinutes * 60_000L,
            postBufferMs = postBufferMinutes * 60_000L,
            recordingType = recordingType,
            matchKeyword = matchKeyword
        )
        dvrRepo.addScheduled(rec)

        if (recordingType != "once") {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Saved $recordingType rule: ${matchKeyword.ifBlank { programTitle }}"
            )
            return
        }

        val triggerAtMs = (startTimeMs - rec.preBufferMs).coerceAtLeast(System.currentTimeMillis() + 1000)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

        val intent = Intent(context, com.dylandos.iptv.ultimate.service.RecordingService::class.java).apply {
            action = com.dylandos.iptv.ultimate.service.RecordingService.ACTION_START
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_CHANNEL_NAME, channelName)
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_RECORDING_ID, rec.id)
            putExtra(
                com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_STOP_AT_MS,
                endTimeMs + rec.postBufferMs
            )
            _uiState.value.storagePath?.let { path ->
                putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_STORAGE_URI, path)
            }
            val providerHost = xtreamRepository.serverUrl
                .removePrefix("https://").removePrefix("http://")
                .substringBefore(":").substringBefore("/")
                .takeIf { it.isNotBlank() }
            if (providerHost != null) {
                putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_PROVIDER_NAME, providerHost)
            }
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getService(context, rec.id.hashCode(), intent, flags)
        val stopIntent = Intent(
            context,
            com.dylandos.iptv.ultimate.service.RecordingService::class.java
        ).apply {
            action = com.dylandos.iptv.ultimate.service.RecordingService.ACTION_STOP
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_RECORDING_ID, rec.id)
        }
        val stopPi = PendingIntent.getService(
            context,
            rec.id.hashCode() xor Int.MIN_VALUE,
            stopIntent,
            flags
        )
        val stopAtMs = endTimeMs + rec.postBufferMs

        try {
            if (alarmManager == null) {
                Timber.w("DVR: AlarmManager unavailable; using durable scheduler backup")
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAtMs, stopPi)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, stopAtMs, stopPi)
            }
            _uiState.value = _uiState.value.copy(statusMessage = "Scheduled: $programTitle on $channelName")
            Timber.i("DVR: scheduled recording '$programTitle' on '$channelName' at ${Date(triggerAtMs)}")
        } catch (e: SecurityException) {
            alarmManager?.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            alarmManager?.set(AlarmManager.RTC_WAKEUP, stopAtMs, stopPi)
            Timber.w("DVR: using inexact alarm (no SCHEDULE_EXACT_ALARM permission)")
            _uiState.value = _uiState.value.copy(statusMessage = "Scheduled (approx): $programTitle on $channelName")
        }

        val providerHost = xtreamRepository.serverUrl
            .removePrefix("https://").removePrefix("http://")
            .substringBefore(":").substringBefore("/")
            .takeIf { it.isNotBlank() }
        ScheduledRecordingWorker.schedule(
            context = context,
            recordingId = rec.id,
            streamUrl = streamUrl,
            channelName = channelName,
            channelId = channelId,
            triggerAtMs = triggerAtMs,
            stopAtMs = stopAtMs,
            storageUri = _uiState.value.storagePath,
            providerName = providerHost
        )
    }

    fun cancelScheduled(id: String) {
        val rec = dvrRepo.getScheduledRecordings().find { it.id == id } ?: return
        dvrRepo.removeScheduled(id)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val intent = Intent(context, com.dylandos.iptv.ultimate.service.RecordingService::class.java)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pi = PendingIntent.getService(context, rec.id.hashCode(), intent, flags)
        alarmManager?.cancel(pi)
        val stopIntent = Intent(context, com.dylandos.iptv.ultimate.service.RecordingService::class.java)
        val stopPi = PendingIntent.getService(
            context,
            rec.id.hashCode() xor Int.MIN_VALUE,
            stopIntent,
            flags
        )
        alarmManager?.cancel(stopPi)
        ScheduledRecordingWorker.cancel(context, id)
        _uiState.value = _uiState.value.copy(statusMessage = "Scheduled recording cancelled")
    }

    fun scheduleRecordingByChannelId(
        channelName: String,
        channelId: Int,
        programTitle: String,
        startTimeMs: Long,
        endTimeMs: Long,
        recordingType: String = "once",
        matchKeyword: String = ""
    ) {
        val streamUrl = xtreamRepository.getLiveStreamUrl(channelId, "ts")
        scheduleRecording(
            channelName = channelName,
            channelId = channelId,
            streamUrl = streamUrl,
            programTitle = programTitle,
            startTimeMs = startTimeMs,
            endTimeMs = endTimeMs,
            recordingType = recordingType,
            matchKeyword = matchKeyword
        )
    }

    /**
     * Called by the UI after the user selects an OTG/SAF folder.
     * Persists immediately and refreshes free-space UI for the selected path.
     */
    fun setStoragePath(treeUri: String) {
        _uiState.value = _uiState.value.copy(storagePath = treeUri, isStorageGranted = true)
        Timber.i("DVR storage set: $treeUri")
        if (treeUri.startsWith("content://")) try {
            val uri = android.net.Uri.parse(treeUri)
            val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
            Timber.i("DVR: persistable URI permission granted for $treeUri")
        } catch (e: Exception) {
            Timber.w("DVR: takePersistableUriPermission failed (non-fatal): ${e.message}")
        }
        refreshStorageSummary()
        viewModelScope.launch {
            try {
                context.dataStore.edit { prefs ->
                    prefs[DVR_STORAGE_PATH_KEY] = treeUri
                }
            } catch (e: Exception) {
                Timber.w("DVR: failed to persist storage path: ${e.message}")
            }
        }
    }

    /** Overlapping scheduled windows including pre/post buffers. */
    fun findOverlappingSchedules(
        startTimeMs: Long,
        endTimeMs: Long,
        preBufferMinutes: Int = 2,
        postBufferMinutes: Int = 5,
        excludeId: String? = null
    ): List<ScheduledRecording> {
        val newStart = startTimeMs - preBufferMinutes * 60_000L
        val newEnd = endTimeMs + postBufferMinutes * 60_000L
        return dvrRepo.getScheduledRecordings().filter { existing ->
            if (excludeId != null && existing.id == excludeId) return@filter false
            // Series/keyword rule rows are markers — they do not occupy a tuner window.
            if (existing.recordingType != "once") return@filter false
            val eStart = existing.scheduledStartMs - existing.preBufferMs
            val eEnd = existing.scheduledEndMs + existing.postBufferMs
            eStart < newEnd && newStart < eEnd
        }
    }

    fun hasScheduleConflict(rec: ScheduledRecording): Boolean =
        findOverlappingSchedules(
            startTimeMs = rec.scheduledStartMs,
            endTimeMs = rec.scheduledEndMs,
            preBufferMinutes = (rec.preBufferMs / 60_000L).toInt().coerceAtLeast(0),
            postBufferMinutes = (rec.postBufferMs / 60_000L).toInt().coerceAtLeast(0),
            excludeId = rec.id
        ).isNotEmpty()

    /**
     * Series/keyword rule conflict path: each prospective EPG match window is checked
     * against existing schedules (Guide one-shot overlap alone is not enough).
     */
    fun findRuleConflicts(
        matches: List<Triple<Long, Long, String>>
    ): List<Pair<String, List<ScheduledRecording>>> =
        matches.mapNotNull { (start, end, title) ->
            val conflicts = findOverlappingSchedules(start, end)
            if (conflicts.isEmpty()) null else title to conflicts
        }

    fun updateFileSizeForActive(recordingId: String, bytes: Long) {
        dvrRepo.updateFileSizeBytes(recordingId, bytes)
    }

    fun recordLiveChannel(channelName: String, streamId: Int, accountId: String? = null) {
        if (!xtreamRepository.isConnected) {
            _uiState.value = _uiState.value.copy(statusMessage = "Not connected — cannot record")
            return
        }
        val selectedAccount = resolveRecordingAccount(accountId)
        val url = if (selectedAccount != null) {
            buildLiveStreamUrlForAccount(selectedAccount, streamId)
        } else {
            xtreamRepository.getLiveStreamUrl(streamId, "ts")
        }
        startRecording(
            channelName = channelName,
            channelId = streamId,
            streamUrl = url,
            providerName = selectedAccount?.serverUrl
                ?.removePrefix("https://")
                ?.removePrefix("http://")
                ?.substringBefore(":")
                ?.substringBefore("/")
                ?.takeIf { it.isNotBlank() }
        )
    }

    private fun resolveRecordingAccount(accountId: String?): SavedAccount? {
        val accounts = _uiState.value.recordingAccounts
        if (accounts.isEmpty()) return null
        accountId?.let { explicit ->
            val match = accounts.firstOrNull { it.id == explicit }
            if (match != null) return match
        }
        val activeId = _uiState.value.activeRecordingAccountId
        return accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()
    }

    private fun buildLiveStreamUrlForAccount(account: SavedAccount, streamId: Int): String {
        val base = account.serverUrl.trimEnd('/')
        return "$base/live/${account.username}/${account.password}/$streamId.ts"
    }

    fun startRecording(
        channelName: String,
        channelId: Int,
        streamUrl: String,
        providerName: String? = null
    ): String {
        dvrRepo.getActiveRecordings()
            .firstOrNull { PlayerRecordingBridge.urlsMatch(it.streamUrl, streamUrl) }
            ?.let { existing ->
                _uiState.value = _uiState.value.copy(statusMessage = "Already recording: ${existing.channelName}")
                return existing.id
            }

        if (dvrRepo.activeCount() >= 3) {
            _uiState.value = _uiState.value.copy(statusMessage = "Max 3 simultaneous recordings reached")
            return ""
        }

        checkStorageQuota()

        val rec = DvrRecording(
            channelName = channelName,
            channelId = channelId,
            streamUrl = streamUrl
        )
        dvrRepo.addRecording(rec)

        val intent = Intent(context, com.dylandos.iptv.ultimate.service.RecordingService::class.java).apply {
            action = com.dylandos.iptv.ultimate.service.RecordingService.ACTION_START
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_RECORDING_ID, rec.id)
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_STREAM_URL, streamUrl)
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_CHANNEL_NAME, channelName)
            _uiState.value.storagePath?.let { path ->
                putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_STORAGE_URI, path)
            }
            val providerHost = providerName ?: xtreamRepository.serverUrl
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore(":")
                .substringBefore("/")
                .takeIf { it.isNotBlank() }
            if (providerHost != null) {
                putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_PROVIDER_NAME, providerHost)
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Timber.i("DVR: started recording '$channelName' id=${rec.id}")
        } catch (e: Exception) {
            Timber.e(e, "DVR: failed to start RecordingService")
            dvrRepo.deleteRecording(rec.id)
            _uiState.value = _uiState.value.copy(
                statusMessage = "Recording failed to start: ${e.message ?: "service error"}"
            )
            return ""
        }

        _uiState.value = _uiState.value.copy(statusMessage = "Recording started: $channelName")
        return rec.id
    }

    fun stopRecording(recordingId: String) {
        if (dvrRepo.getActiveRecordings().none { it.id == recordingId }) return
        if (recordingId in _uiState.value.stoppingRecordingIds) return

        val intent = Intent(context, com.dylandos.iptv.ultimate.service.RecordingService::class.java).apply {
            action = com.dylandos.iptv.ultimate.service.RecordingService.ACTION_STOP
            putExtra(com.dylandos.iptv.ultimate.service.RecordingService.EXTRA_RECORDING_ID, recordingId)
        }
        try {
            context.startService(intent)
        } catch (e: Exception) {
            Timber.w("DVR: could not send STOP to RecordingService: ${e.message}")
        }

        _uiState.value = _uiState.value.copy(
            statusMessage = "Stopping recording...",
            stoppingRecordingIds = _uiState.value.stoppingRecordingIds + recordingId
        )

        viewModelScope.launch {
            kotlinx.coroutines.delay(6_000L)
            if (dvrRepo.getActiveRecordings().any { it.id == recordingId }) {
                dvrRepo.finalizeStaleRecording(recordingId, "Stop request timed out; recovered DVR state")
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Recording stopped and DVR state recovered"
                )
            }
        }
    }

    fun deleteRecording(recordingId: String) {
        dvrRepo.deleteRecording(recordingId)
        _uiState.value = _uiState.value.copy(statusMessage = "Recording deleted")
    }

    fun clearStatus() {
        _uiState.value = _uiState.value.copy(statusMessage = null, storageWarning = null)
    }

    fun checkStorageQuota() {
        try {
            val dir = StorageDetector.findPreferredDvrBasePath(context, _uiState.value.storagePath)
                ?: context.getExternalFilesDir(null)
                ?: context.filesDir
            val stats = storageStatsFor(dir)
            if (stats == null) {
                Timber.w("DVR: storage quota unknown for ${dir.absolutePath}")
                _uiState.value = _uiState.value.copy(storageWarning = null)
                return
            }
            val free = stats.first
            val total = stats.second
            val freePct = if (total > 0) (free * 100 / total).toInt() else 100
            val lowMb = free < 500 * 1024 * 1024
            val lowPct = freePct < 10
            if (lowMb || lowPct) {
                val freeMb = free / 1_048_576
                val warning =
                    "Low DVR storage: ${freeMb}MB (${freePct}% free) on ${dir.name}. Recording may fail near capacity."
                Timber.w("DVR: $warning")
                _uiState.value = _uiState.value.copy(storageWarning = warning)
                postLowStorageNotification(freeMb, freePct)
            } else {
                _uiState.value = _uiState.value.copy(storageWarning = null)
            }
        } catch (e: Exception) {
            Timber.w("DVR: storage check failed: ${e.message}")
        }
    }

    private fun storageStatsFor(dir: File): Pair<Long, Long>? {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching null
            val probe = File(dir, ".dylandos_dvr_quota_probe")
            probe.outputStream().use { it.write(1) }
            runCatching { probe.delete() }
            val statFs = StatFs(dir.absolutePath)
            val free = maxOf(
                statFs.availableBlocksLong * statFs.blockSizeLong,
                dir.usableSpace,
                dir.freeSpace
            )
            val total = maxOf(
                statFs.blockCountLong * statFs.blockSizeLong,
                dir.totalSpace
            )
            if (free <= 0L && total <= 0L) null else free to total
        }.getOrNull()
    }

    private fun postLowStorageNotification(freeMb: Long, freePct: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notification = NotificationCompat.Builder(context, DylandosApp.CHANNEL_RECORDING)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("DVR Storage Warning")
            .setContentText("Only ${freeMb}MB (${freePct}%) free. Recording may fail at capacity.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        nm.notify(DVR_STORAGE_NOTIF_ID, notification)
    }

    companion object {
        private const val DVR_STORAGE_NOTIF_ID = 9901
        val DVR_STORAGE_PATH_KEY = stringPreferencesKey("dvr_storage_path")
    }

    private fun hasPersistedWritePermission(path: String): Boolean {
        if (path.startsWith("/")) {
            return runCatching {
                val dir = File(path)
                (dir.exists() || dir.mkdirs()) && dir.canWrite()
            }.getOrDefault(false)
        }
        if (!path.startsWith("content://")) return false

        val uri = runCatching { android.net.Uri.parse(path) }.getOrNull() ?: return false
        val cr = context.contentResolver
        val a = uri.toString().trimEnd('/')
        val fromPersisted = cr.persistedUriPermissions.any { perm ->
            if (!perm.isWritePermission) return@any false
            val p = perm.uri.toString().trimEnd('/')
            p == a || p.startsWith(a) || a.startsWith(p)
        }
        if (fromPersisted) return true
        return runCatching {
            val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
            tree != null && tree.canWrite()
        }.getOrDefault(false)
    }
}
