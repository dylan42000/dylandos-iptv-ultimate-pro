package com.dylandos.iptv.ultimate.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import com.dylandos.iptv.ultimate.data.db.dao.DvrRecordingDao
import com.dylandos.iptv.ultimate.data.db.dao.ScheduledRecordingDao
import com.dylandos.iptv.ultimate.data.db.entity.DvrRecordingEntity
import com.dylandos.iptv.ultimate.data.db.entity.ScheduledRecordingEntity
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrEvent
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrRecording
import com.dylandos.iptv.ultimate.ui.screens.dvr.ScheduledRecording
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DYLANDOS IPTV ULTIMATE — DVR Recording Repository
 *
 * Application-scoped singleton that holds all DVR recording state.
 * Injects DvrRecordingDao to persist completed recordings across process death.
 *
 * On start: loads all Room rows into the in-memory list.
 * On stop:  Room row is updated to COMPLETED status.
 * On delete: Row is removed from Room.
 * Active (in-progress) recordings are memory-only during the session.
 */
@Singleton
class DvrRecordingRepository @Inject constructor(
    private val dvrDao: DvrRecordingDao,
    private val scheduledDao: ScheduledRecordingDao,
    @ApplicationContext private val context: Context
) {
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _recordings  = mutableListOf<DvrRecording>()
    private val _scheduled   = mutableListOf<ScheduledRecording>()

    /** Snapshot flow — emits a new snapshot whenever recordings change. */
    private val _recordingsFlow = MutableStateFlow<List<DvrRecording>>(emptyList())
    val recordingsFlow: StateFlow<List<DvrRecording>> = _recordingsFlow.asStateFlow()

    private val _scheduledFlow = MutableStateFlow<List<ScheduledRecording>>(emptyList())
    val scheduledFlow: StateFlow<List<ScheduledRecording>> = _scheduledFlow.asStateFlow()

    /**
     * One-shot DVR events (Module 1.3). The background [RecordingService] emits
     * failures/starts here; [DvrViewModel] re-exposes them to the UI for Toasts.
     * Buffered + DROP_OLDEST so an emit from the service thread never blocks even
     * if no collector is currently attached (e.g. user navigated away).
     */
    private val _events = MutableSharedFlow<DvrEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DvrEvent> = _events.asSharedFlow()

    /** Emit a DVR event from any thread (service or UI). Never throws. */
    fun emitEvent(event: DvrEvent) {
        _events.tryEmit(event)
    }

    init {
        // Restore completed recordings from Room on startup; sweep orphaned rows whose
        // files were deleted by the user outside the app (e.g. via a file manager or USB unplug).
        // An orphaned row left in Room causes a crash when the DVR screen tries to play it.
        repoScope.launch {
            val persisted = dvrDao.getAll()
            val orphanIds = mutableListOf<String>()
            persisted.forEach { entity ->
                // A foreground-service slot cannot survive process death. Android can
                // recreate the app with a persisted RECORDING row but without the
                // LibVLC/HTTP writer that owned it; leaving that row active makes the
                // DVR impossible to stop from the UI. Preserve any partial file and
                // finalize the row instead of presenting a ghost recording.
                val recoveredEntity = if (entity.status == "RECORDING") {
                    val recoveredSize = maxOf(entity.fileSizeBytes, dvrFileSize(entity.filePath))
                    val recoveredStatus = if (recoveredSize > 0L) "COMPLETED" else "FAILED"
                    dvrDao.updateStatus(entity.id, recoveredStatus, System.currentTimeMillis(), recoveredSize)
                    Timber.w("DVR recovery: finalized stale recording id=${entity.id} status=$recoveredStatus")
                    entity.copy(status = recoveredStatus, endTimeMs = System.currentTimeMillis(), fileSizeBytes = recoveredSize)
                } else entity
                val rec = recoveredEntity.toDvrRecording()
                if (!rec.isActive && rec.filePath.isNotBlank() && !dvrFileExists(rec.filePath)) {
                    Timber.w("DVR orphan sweep: file missing for id=${rec.id}, removing Room row")
                    orphanIds.add(rec.id)
                } else {
                    _recordings.add(rec)
                }
            }
            orphanIds.forEach { id -> dvrDao.deleteById(id) }
            if (orphanIds.isNotEmpty()) Timber.i("DVR orphan sweep removed ${orphanIds.size} stale rows")
            scheduledDao.deleteExpired(System.currentTimeMillis() - 24 * 3_600_000L)
            _scheduled.addAll(scheduledDao.getAll().map { it.toScheduledRecording() })
            emitSnapshot()
            emitScheduledSnapshot()
        }
    }

    /**
     * Returns true if the DVR output file at [filePath] physically exists on storage.
     * Handles content:// SAF URIs (OTG/USB), file:// URIs, and raw absolute paths.
     * Unknown schemes are treated as present (don't delete what we can't verify).
     */
    private fun dvrFileExists(filePath: String): Boolean = runCatching {
        when {
            filePath.startsWith("content://") -> {
                // SAF URI — attempt a minimal query; if it throws or returns 0 rows the file is gone
                context.contentResolver.query(
                    Uri.parse(filePath),
                    arrayOf(OpenableColumns.SIZE),
                    null, null, null
                )?.use { cursor -> cursor.moveToFirst() } ?: false
            }
            filePath.startsWith("file://") ->
                java.io.File(Uri.parse(filePath).path ?: return@runCatching true).exists()
            filePath.startsWith("/") ->
                java.io.File(filePath).exists()
            else -> true  // Unknown scheme — leave the row alone
        }
    }.getOrDefault(true)  // On any exception assume present (safer than deleting)

    private fun dvrFileSize(filePath: String): Long = runCatching {
        when {
            filePath.startsWith("content://") -> {
                context.contentResolver.query(
                    Uri.parse(filePath), arrayOf(OpenableColumns.SIZE), null, null, null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).coerceAtLeast(0L) else 0L
                } ?: 0L
            }
            filePath.startsWith("file://") -> java.io.File(Uri.parse(filePath).path.orEmpty()).length()
            filePath.startsWith("/") -> java.io.File(filePath).length()
            else -> 0L
        }
    }.getOrDefault(0L)

    // ── Completed/Active snapshots ────────────────────────────────────────────

    fun getActiveRecordings(): List<DvrRecording>    = _recordings.filter { it.isActive }
    fun getCompletedRecordings(): List<DvrRecording> = _recordings.filter { !it.isActive }.sortedByDescending { it.stopTimeMs }
    fun getScheduledRecordings(): List<ScheduledRecording> = _scheduled.sortedBy { it.scheduledStartMs }
    fun activeCount(): Int = _recordings.count { it.isActive }
    fun containsRecording(id: String): Boolean = _recordings.any { it.id == id }

    // ── Mutation helpers ──────────────────────────────────────────────────────

    fun addRecording(rec: DvrRecording) {
        _recordings.add(rec)
        emitSnapshot()
        // Persist immediately as RECORDING so crash recovery can detect orphans
        repoScope.launch { dvrDao.insert(rec.toEntity("RECORDING")) }
    }

    fun stopRecording(id: String): Boolean {
        val idx = _recordings.indexOfFirst { it.id == id && it.isActive }
        if (idx == -1) return false
        val stopTime = System.currentTimeMillis()
        val stopped  = _recordings[idx].copy(isActive = false, stopTimeMs = stopTime)
        _recordings[idx] = stopped
        emitSnapshot()
        // Update Room row to COMPLETED
        repoScope.launch {
            dvrDao.updateStatus(
                id           = id,
                status       = "COMPLETED",
                endTimeMs    = stopTime,
                fileSizeBytes= stopped.fileSizeBytes
            )
        }
        return true
    }

    /**
     * Finishes an active row when its service slot is gone or failed to answer a
     * stop request. This is deliberately idempotent so it is safe to call from a
     * service, ViewModel timeout, or process-recovery sweep.
     */
    fun finalizeStaleRecording(id: String, reason: String): Boolean {
        val idx = _recordings.indexOfFirst { it.id == id && it.isActive }
        if (idx == -1) return false
        val original = _recordings[idx]
        val finalSize = maxOf(original.fileSizeBytes, dvrFileSize(original.filePath))
        val stopTime = System.currentTimeMillis()
        _recordings[idx] = original.copy(
            isActive = false,
            stopTimeMs = stopTime,
            fileSizeBytes = finalSize
        )
        emitSnapshot()
        repoScope.launch {
            dvrDao.updateStatus(
                id = id,
                status = if (finalSize > 0L) "COMPLETED" else "FAILED",
                endTimeMs = stopTime,
                fileSizeBytes = finalSize
            )
        }
        if (finalSize > 0L) {
            emitEvent(DvrEvent.RecordingSaved(id, original.channelName, finalSize))
        } else {
            emitEvent(DvrEvent.RecordingFailed(id, original.channelName, reason))
        }
        Timber.w("DVR recovery: finalized $id ($reason, ${finalSize} bytes)")
        return true
    }

    fun deleteRecording(id: String) {
        _recordings.firstOrNull { it.id == id }
            ?.filePath
            ?.takeIf(String::isNotBlank)
            ?.let(::deleteRecordingFile)
        _recordings.removeAll { it.id == id }
        emitSnapshot()
        repoScope.launch { dvrDao.deleteById(id) }
    }

    private fun deleteRecordingFile(filePath: String) {
        repoScope.launch {
            runCatching {
                when {
                    filePath.startsWith("content://") ->
                        DocumentFile.fromSingleUri(context, Uri.parse(filePath))?.delete()
                    filePath.startsWith("file://") ->
                        java.io.File(Uri.parse(filePath).path.orEmpty()).delete()
                    filePath.startsWith("/") -> java.io.File(filePath).delete()
                    else -> false
                }
            }.onFailure { Timber.w(it, "DVR: failed to delete recording file $filePath") }
        }
    }

    /**
     * Called by RecordingService once the permanent output URI/path is known.
     * Stores the content:// URI (SAF/OTG) or absolute file path so DVR playback works.
     */
    fun updateFilePath(id: String, filePath: String) {
        val idx = _recordings.indexOfFirst { it.id == id }
        if (idx >= 0) {
            _recordings[idx] = _recordings[idx].copy(filePath = filePath)
            emitSnapshot()
        }
        repoScope.launch { dvrDao.updateFilePath(id, filePath) }
    }

    /**
     * Called by RecordingService every 30 s to update the live file size of an active recording.
     * Updates both the in-memory list and Room so the Completed tab shows accurate sizes.
     */
    fun updateFileSizeBytes(id: String, bytes: Long) {
        val idx = _recordings.indexOfFirst { it.id == id }
        if (idx >= 0 && bytes > 0) {
            _recordings[idx] = _recordings[idx].copy(fileSizeBytes = bytes)
            emitSnapshot()
        }
        repoScope.launch { dvrDao.updateFileSizeBytes(id, bytes) }
    }

    fun addScheduled(rec: ScheduledRecording) {
        _scheduled.removeAll { it.id == rec.id }
        _scheduled.add(rec)
        emitScheduledSnapshot()
        repoScope.launch { scheduledDao.upsert(rec.toEntity()) }
    }

    fun removeScheduled(id: String) {
        _scheduled.removeAll { it.id == id }
        emitScheduledSnapshot()
        repoScope.launch { scheduledDao.deleteById(id) }
    }

    private fun emitSnapshot() {
        _recordingsFlow.value = _recordings.toList()
    }

    private fun emitScheduledSnapshot() {
        _scheduledFlow.value = _scheduled.toList()
    }

    // ── Entity mapping ────────────────────────────────────────────────────────

    private fun DvrRecordingEntity.toDvrRecording() = DvrRecording(
        id           = id,
        channelName  = channelName,
        channelId    = channelId,
        streamUrl    = streamUrl,
        programTitle = programTitle,
        filePath     = filePath,
        startTimeMs  = startTimeMs,
        stopTimeMs   = if (endTimeMs > 0L) endTimeMs else null,
        fileSizeBytes= fileSizeBytes,
        isActive     = status == "RECORDING"
    )

    private fun DvrRecording.toEntity(statusStr: String) = DvrRecordingEntity(
        id           = id,
        channelName  = channelName,
        channelId    = channelId,
        streamUrl    = streamUrl,
        filePath     = filePath,
        startTimeMs  = startTimeMs,
        endTimeMs    = stopTimeMs ?: 0L,
        fileSizeBytes= fileSizeBytes,
        status       = statusStr,
        programTitle = programTitle
    )

    private fun ScheduledRecordingEntity.toScheduledRecording() = ScheduledRecording(
        id = id,
        channelName = channelName,
        channelId = channelId,
        streamUrl = streamUrl,
        programTitle = programTitle,
        scheduledStartMs = scheduledStartMs,
        scheduledEndMs = scheduledEndMs,
        preBufferMs = preBufferMs,
        postBufferMs = postBufferMs,
        recordingType = recordingType,
        matchKeyword = matchKeyword
    )

    private fun ScheduledRecording.toEntity() = ScheduledRecordingEntity(
        id = id,
        channelName = channelName,
        channelId = channelId,
        streamUrl = streamUrl,
        programTitle = programTitle,
        scheduledStartMs = scheduledStartMs,
        scheduledEndMs = scheduledEndMs,
        preBufferMs = preBufferMs,
        postBufferMs = postBufferMs,
        recordingType = recordingType,
        matchKeyword = matchKeyword
    )
}
