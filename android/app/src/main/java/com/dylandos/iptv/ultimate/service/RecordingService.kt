package com.dylandos.iptv.ultimate.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import com.dylandos.iptv.ultimate.data.repository.DvrRecordingRepository
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrEvent
import com.dylandos.iptv.ultimate.ui.screens.dvr.DvrRecording
import com.dylandos.iptv.ultimate.data.util.StorageDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — DVR Recording Service
 *
 * Foreground service supporting two recording backends:
 *
 *   LibVLC :sout (stream-copy) — used for all DIRECT recordings.
 *     :sout=#std{access=file,mux=ts,dst=<path>} writes MPEG-TS with no re-encoding
 *     (equivalent to ffmpeg -c copy). Output path resolved via resolveOutputFile():
 *       1. OTG/USB via StorageDetector.findOtgPath()  [No SAF needed!]
 *       2. Primary external getExternalFilesDir(DVR)
 *       3. Internal filesDir/DVR  (Firestick last resort)
 *
 *   Direct HTTP stream-copy — preferred for Firestick live TS/direct HTTP streams.
 *     This avoids coupling the recorder to the foreground player lifecycle.
 *
 * Supports up to 3 simultaneous recordings (slots 1-3).
 *
 * Actions:
 *   START_RECORDING  — extras: recording_id, stream_url, channel_name, slot (1-3)
 *   STOP_RECORDING   — extras: recording_id
 *   STOP_ALL         — stops all active recordings and the service
 */
@AndroidEntryPoint
class RecordingService : Service() {

    @Inject lateinit var dvrRepo: DvrRecordingRepository

    companion object {
        // Must match DylandosApp.CHANNEL_RECORDING — foreground service requires exact ID match on API 26+
        const val CHANNEL_ID       = "recording_channel"
        const val NOTIFICATION_ID  = 9001

        const val ACTION_START     = "START_RECORDING"
        const val ACTION_STOP      = "STOP_RECORDING"
        const val ACTION_STOP_ALL  = "STOP_ALL"

        const val EXTRA_RECORDING_ID  = "recording_id"
        const val EXTRA_STREAM_URL    = "stream_url"
        const val EXTRA_CHANNEL_NAME  = "channel_name"
        const val EXTRA_STORAGE_URI   = "storage_uri"
        const val EXTRA_SLOT          = "slot"           // 1, 2, or 3
        const val EXTRA_PROVIDER_NAME = "provider_name" // server hostname — used for per-provider subfolders
        const val EXTRA_STOP_AT_MS     = "stop_at_ms"

        const val MAX_CONCURRENT_RECORDINGS = 3
        // Identity used for every recording connection (LibVLC :sout and raw HTTP copy).
        // Kept in lockstep with the player so providers that whitelist by User-Agent don't
        // reject the recorder's connection with a 403/empty body.
        const val RECORDING_USER_AGENT = "VLC/3.0.18 LibVLC/3.0.18"
        private const val RECORD_BUFFER_BYTES = 512 * 1024
        private const val MIN_FREE_BYTES_TO_START = 8L * 1024L * 1024L
        private const val ENABLE_TEE_RECORDING = true
        private const val MAX_START_REPAIR_ATTEMPTS = 2
    }

    // Each slot holds a LibVLC instance + MediaPlayer for one recording.
    // In TEE mode (isTeeMode=true) the player itself owns the VLC instance, so
    // libVLC and mediaPlayer are null — the slot tracks metadata + file only.
    private data class RecordingSlot(
        val recordingId: String,
        val channelName: String,
        val startTimeMs: Long,
        val outputPath: String,          // absolute file path (OTG or internal)
        val permanentUri: String,        // same as outputPath for non-SAF recordings
        val libVLC: LibVLC?,
        val mediaPlayer: MediaPlayer?,
        val pfd: ParcelFileDescriptor? = null,   // kept for SAF TEE compat
        val sizeJob: Job? = null,                // coroutine polling file size every 30s
        val downloadJob: Job? = null,
        val isTeeMode: Boolean = false,          // true = player owns the VLC + :sout
        val streamUrl: String = ""               // original stream URL (used by stale watchdog to restart)
    )

    private val slots = mutableMapOf<String, RecordingSlot>()  // recordingId → slot
    private val autoStopJobs = mutableMapOf<String, Job>()
    private val startRepairAttempts = mutableMapOf<String, Int>()
    /** Service-scoped coroutine scope — cancelled in onDestroy() to prevent leaks. */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val id           = intent.getStringExtra(EXTRA_RECORDING_ID) ?: return START_NOT_STICKY
                val url          = intent.getStringExtra(EXTRA_STREAM_URL)   ?: return START_NOT_STICKY
                val channelName  = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: "Channel"
                val storageUri   = intent.getStringExtra(EXTRA_STORAGE_URI)  // nullable — OTG/SAF path
                val providerName = intent.getStringExtra(EXTRA_PROVIDER_NAME)  // nullable — server hostname
                val stopAtMs     = intent.getLongExtra(EXTRA_STOP_AT_MS, 0L)

                if (slots.containsKey(id)) {
                    Timber.i("DVR: duplicate START ignored for existing recording id=$id")
                    scheduleAutoStop(id, stopAtMs)
                    updateNotification()
                    return START_STICKY
                }

                val duplicateSlot = slots.values.firstOrNull { slot ->
                    PlayerRecordingBridge.urlsMatch(slot.streamUrl, url)
                }
                if (duplicateSlot != null) {
                    Timber.w("DVR: duplicate stream start rejected for $channelName; already recording ${duplicateSlot.channelName}")
                    finishRecordingAsFailed(
                        id = id,
                        channelName = channelName,
                        streamUrl = url,
                        outputPath = duplicateSlot.outputPath,
                        reason = "This channel is already recording"
                    )
                    return START_NOT_STICKY
                }

                if (slots.size >= MAX_CONCURRENT_RECORDINGS) {
                    Timber.w("DVR: max $MAX_CONCURRENT_RECORDINGS concurrent recordings reached, ignoring $id")
                    finishRecordingAsFailed(
                        id = id,
                        channelName = channelName,
                        streamUrl = url,
                        outputPath = null,
                        reason = "Maximum concurrent recording limit reached"
                    )
                    return START_NOT_STICKY
                }

                startForeground(NOTIFICATION_ID, buildNotification())
                dvrRepo.removeScheduled(id)
                if (!dvrRepo.containsRecording(id)) {
                    dvrRepo.addRecording(
                        DvrRecording(
                            id = id,
                            channelName = channelName,
                            channelId = extractChannelId(url),
                            streamUrl = url
                        )
                    )
                }
                doStartRecording(id, url, channelName, storageUri, providerName)
                if (slots.containsKey(id)) scheduleAutoStop(id, stopAtMs)
            }

            ACTION_STOP -> {
                val id = intent.getStringExtra(EXTRA_RECORDING_ID) ?: return START_NOT_STICKY
                if (!doStopRecording(id)) {
                    // The persisted row may outlive a killed/recreated service. Do
                    // not leave a DVR card permanently active just because there is
                    // no in-memory slot left to stop.
                    dvrRepo.finalizeStaleRecording(id, "Recording service was no longer running")
                }
                if (slots.isEmpty()) stopSelf()
            }

            ACTION_STOP_ALL -> {
                doStopAll()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        doStopAll()
        serviceScope.cancel()   // cancel all size-polling coroutines
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Recording logic ───────────────────────────────────────────────────────

    private fun createRecordingLibVlc(): LibVLC {
        val preferred = arrayListOf(
            "--network-caching=3000",
            "--live-caching=3000",
            "--clock-jitter=0",
            "--clock-synchro=0",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--aout=android_audiotrack",
            "--no-stats"
        )
        val fallback = arrayListOf(
            "--network-caching=3000",
            "--live-caching=3000",
            "--aout=dummy",
            "--no-stats"
        )
        return runCatching { LibVLC(this, preferred) }
            .onFailure { Timber.e(it, "DVR LibVLC init failed with tuned options; retrying safe defaults") }
            .getOrElse { LibVLC(this, fallback) }
    }

    private fun doStartRecording(id: String, url: String, channelName: String, storageUriString: String?, providerName: String? = null) {
        try {
            // Resolve the best available output directory. USB public Download is
            // preferred because Firestick users can verify the file directly there.
            val outputDir = resolveOutputFile(channelName, storageUriString, providerName)
            if (outputDir == null) {
                Timber.e("DVR: could not resolve any output path — aborting recording $id")
                finishRecordingAsFailed(id, channelName, url, null, "No writable DVR output path")
                return
            }

            // Sanitize filename
            val safe = channelName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)
            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "${safe}_${date}.ts"
            val outputAbsPath = "${outputDir.absolutePath}/$fileName"

            // Firesticks often run tight on internal space. Do not auto-fail at
            // the old 64 MB cliff; only block when the target cannot hold even a
            // short useful clip.
            val freeBytes = usableBytesOrUnknown(outputDir)
            if (freeBytes < 0L) {
                Timber.e("DVR: output path is not writable — ${outputDir.absolutePath}")
                finishRecordingAsFailed(id, channelName, url, null, "DVR output folder is not writable")
                return
            }
            if (freeBytes in 1 until MIN_FREE_BYTES_TO_START) {
                Timber.w("DVR: low storage reported — ${freeBytes / 1024 / 1024}MB free on ${outputDir.absolutePath}; attempting recording after writable probe")
            } else if (freeBytes <= 0L) {
                Timber.w("DVR: storage stats unknown for ${outputDir.absolutePath}; probe passed, attempting recording")
            }

            Timber.i("DVR START: $channelName → $outputAbsPath  (freeGB=${"%.2f".format(freeBytes / 1_073_741_824f)})")
            dvrRepo.updateFilePath(id, outputAbsPath)

            // TEE is the preferred path when recording the same live channel currently
            // playing on screen. It keeps one provider connection open and duplicates
            // that stream to both display and file output.
            if (ENABLE_TEE_RECORDING && PlayerRecordingBridge.isPlayingUrl(url)) {
                Timber.i("DVR TEE: $channelName — player already streaming, requesting #duplicate")
                PlayerRecordingBridge.postTeeRequest(
                    PlayerRecordingBridge.TeeRequest(
                        recordingId = id,
                        outputPath  = outputAbsPath,
                        pfd         = null
                    )
                )
                val slot = RecordingSlot(
                    recordingId  = id,
                    channelName  = channelName,
                    startTimeMs  = System.currentTimeMillis(),
                    outputPath   = outputAbsPath,
                    permanentUri = outputAbsPath,
                    libVLC       = null,
                    mediaPlayer  = null,
                    isTeeMode    = true,
                    streamUrl    = url
                )
                slots[id] = slot
                slots[id] = slot.copy(sizeJob = startSizePolling(id, outputAbsPath))
                verifyRecordingStart(id)
                serviceScope.launch {
                    while (isActive && slots.containsKey(id)) {
                        delay(3_000L)
                        if (!slots.containsKey(id)) break
                        if (PlayerRecordingBridge.currentPlaybackUrl == null) {
                            // Player exited while TEE recording was active.
                            // Instead of stopping the recording, promote it to a DIRECT
                            // LibVLC :sout recording so it continues in the background.
                            val teeSlot = slots[id] ?: break
                            Timber.i("DVR TEE watchdog: player exited — promoting $id to DIRECT mode")
                            promoteTeeToDirect(id, teeSlot, url)
                            break
                        }
                    }
                }
                updateNotification()
                return
            }

            if (shouldUseRawHttpCopy(url)) {
                startHttpStreamRecording(id, url, channelName, outputAbsPath)
                return
            }

            // ── DIRECT recording via LibVLC :sout (stream-copy, no re-encode) ──────────
            // :sout=#std{access=file,mux=ts} writes MPEG-TS — equivalent to ffmpeg -c copy.
            // No "display" sink — this is a headless background service (no video surface).
            val vlc = createRecordingLibVlc()

            val mp = MediaPlayer(vlc)

            val media = Media(vlc, Uri.parse(url)).apply {
                addOption(":network-caching=3000")
                addOption(":live-caching=3000")
                addOption(":clock-jitter=0")
                addOption(":clock-synchro=0")
                addOption(":http-user-agent=$RECORDING_USER_AGENT")
                addOption(":http-reconnect")
                addOption(buildStdSout(outputAbsPath))
                addOption(":sout-keep")
            }

            mp.media = media
            media.release()
            mp.play()

            val slot = RecordingSlot(
                recordingId  = id,
                channelName  = channelName,
                startTimeMs  = System.currentTimeMillis(),
                outputPath   = outputAbsPath,
                permanentUri = outputAbsPath,
                libVLC       = vlc,
                mediaPlayer  = mp,
                streamUrl    = url
            )
            slots[id] = slot
            slots[id] = slot.copy(sizeJob = startSizePolling(id, outputAbsPath))

            // Start stale watchdog — restarts recording if file stops growing for 60 s
            startStaleWatchdog(id)
            verifyRecordingStart(id)

            updateNotification()
            Timber.i("DVR: LibVLC :sout recording started, active slots = ${slots.size}")

        } catch (e: Exception) {
            Timber.e(e, "DVR: failed to start recording for $channelName")
            finishRecordingAsFailed(id, channelName, url, null, e.message ?: "Recording service start failed")
        }
    }

    private fun doStopRecording(id: String): Boolean {
        autoStopJobs.remove(id)?.cancel()
        startRepairAttempts.remove(id)
        val slot = slots.remove(id) ?: return false
        slot.sizeJob?.cancel()   // stop the 30s polling loop
        slot.downloadJob?.cancel()
        stopStaleWatchdog(id)    // cancel stale watchdog

        when {
            slot.isTeeMode -> {
                // TEE mode: PlayerScreen owns VLC. Signal it to reload without :sout.
                PlayerRecordingBridge.postTeeStop(id)
                slot.pfd?.let { pfd ->
                    try { pfd.close() } catch (e: Exception) { Timber.w(e, "DVR TEE: error closing SAF pfd") }
                }
                Timber.i("DVR TEE STOP: ${slot.channelName}")
            }
            else -> {
                // Direct LibVLC :sout recording
                try {
                    slot.mediaPlayer?.stop()
                    slot.mediaPlayer?.release()
                    slot.libVLC?.release()
                    slot.pfd?.let { pfd ->
                        try { pfd.close() } catch (e: Exception) { Timber.w(e, "DVR: error closing pfd") }
                    }
                    Timber.i("DVR VLC STOP: ${slot.channelName}, file: ${slot.outputPath}")
                } catch (e: Exception) {
                    Timber.e(e, "DVR: error stopping slot $id")
                }
            }
        }
        val finalSize = runCatching { File(slot.outputPath).length() }.getOrDefault(0L)
        if (finalSize > 0L) {
            dvrRepo.updateFileSizeBytes(id, finalSize)
            dvrRepo.stopRecording(id)
            dvrRepo.emitEvent(DvrEvent.RecordingSaved(id, slot.channelName, finalSize))
        } else if (!slot.isTeeMode) {
            Timber.e("DVR STOP: ${slot.channelName} produced an empty file; keeping failed DVR row visible")
            runCatching { File(slot.outputPath).delete() }
            dvrRepo.updateFilePath(id, slot.outputPath)
            dvrRepo.stopRecording(id)
            dvrRepo.emitEvent(
                DvrEvent.RecordingFailed(id, slot.channelName, "Recording produced an empty file — provider may have blocked the stream")
            )
        } else {
            // TEE stop with no measurable file size — the player owned the sout; treat as a
            // user-initiated stop rather than a failure (size may settle after VLC flushes).
            dvrRepo.stopRecording(id)
        }
        if (slots.isNotEmpty()) updateNotification()
        return true
    }

    private fun startHttpStreamRecording(
        id: String,
        url: String,
        channelName: String,
        outputAbsPath: String
    ) {
        val slot = RecordingSlot(
            recordingId = id,
            channelName = channelName,
            startTimeMs = System.currentTimeMillis(),
            outputPath = outputAbsPath,
            permanentUri = outputAbsPath,
            libVLC = null,
            mediaPlayer = null,
            streamUrl = url
        )
        slots[id] = slot
        val downloadJob = serviceScope.launch { recordHttpLoop(id, url, outputAbsPath) }
        slots[id] = slot.copy(
            downloadJob = downloadJob,
            sizeJob = startSizePolling(id, outputAbsPath)
        )
        startStaleWatchdog(id)
        verifyRecordingStart(id)
        updateNotification()
        Timber.i("DVR HTTP recorder started: $channelName → $outputAbsPath")
    }

    private suspend fun recordHttpLoop(id: String, streamUrl: String, outputAbsPath: String) {
        var reconnectDelayMs = 1_000L
        var zeroByteAttempts = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive && slots.containsKey(id)) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(streamUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    // Match the player's identity — many IPTV panels whitelist this UA and a
                    // mismatch returns 403/empty body, which is one cause of the empty-file bug.
                    setRequestProperty("User-Agent", RECORDING_USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                    setRequestProperty("Icy-MetaData", "0")
                    setRequestProperty("Connection", "keep-alive")
                }
                val response = conn.responseCode
                // A 4xx is a hard provider rejection (auth/connection-limit/geo) — retrying
                // just produces an empty file forever. Fail loudly instead.
                if (response in 400..499) {
                    val reason = when (response) {
                        401, 403 -> "Provider blocked recording (HTTP $response — auth or connection limit)"
                        404      -> "Stream URL not found (HTTP 404) — channel may be offline"
                        else     -> "Provider rejected recording (HTTP $response)"
                    }
                    Timber.e("DVR HTTP recorder [$id]: $reason")
                    val slot = slots[id]
                    if (slot != null) failRecording(id, slot, reason)
                    return
                }
                if (response !in 200..299) throw IllegalStateException("HTTP $response")

                BufferedInputStream(conn.inputStream, RECORD_BUFFER_BYTES).use { input ->
                    BufferedOutputStream(FileOutputStream(outputAbsPath, true), RECORD_BUFFER_BYTES).use { output ->
                        val buffer = ByteArray(RECORD_BUFFER_BYTES)
                        while (kotlinx.coroutines.currentCoroutineContext().isActive && slots.containsKey(id)) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                        }
                        output.flush()
                    }
                }
                reconnectDelayMs = 1_000L
            } catch (e: Exception) {
                if (!slots.containsKey(id)) break
                Timber.w(e, "DVR HTTP recorder reconnecting id=$id")
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(10_000L)
            } finally {
                runCatching { conn?.disconnect() }
                val bytes = runCatching { File(outputAbsPath).length() }.getOrDefault(0L)
                if (bytes > 0L) {
                    dvrRepo.updateFileSizeBytes(id, bytes)
                    zeroByteAttempts = 0
                } else {
                    zeroByteAttempts++
                }
            }
            // If, after several connect cycles, not a single byte has been written, the
            // provider is silently dropping the duplicate connection. Stop and report.
            if (zeroByteAttempts >= 5 && runCatching { File(outputAbsPath).length() }.getOrDefault(0L) <= 0L) {
                val slot = slots[id] ?: break
                Timber.e("DVR HTTP recorder [$id]: no data after $zeroByteAttempts attempts — giving up")
                failRecording(id, slot, "Stream URL missing or provider blocked recording")
                return
            }
        }
    }

    private fun doStopAll() {
        slots.keys.toList().forEach { doStopRecording(it) }
        autoStopJobs.values.forEach(Job::cancel)
        autoStopJobs.clear()
    }

    private fun scheduleAutoStop(id: String, stopAtMs: Long) {
        if (stopAtMs <= System.currentTimeMillis()) return
        autoStopJobs.remove(id)?.cancel()
        autoStopJobs[id] = serviceScope.launch {
            delay((stopAtMs - System.currentTimeMillis()).coerceAtLeast(1_000L))
            if (slots.containsKey(id)) {
                Timber.i("DVR AUTO STOP: recording=$id")
                doStopRecording(id)
                if (slots.isEmpty()) stopSelf()
            }
        }
    }

    private fun shouldUseRawHttpCopy(streamUrl: String): Boolean {
        if (!streamUrl.startsWith("http", ignoreCase = true)) return false
        val clean = streamUrl.substringBefore('?').lowercase(Locale.ROOT)
        if (clean.endsWith(".m3u8") || clean.endsWith(".mpd")) return false
        return true
    }

    private fun extractChannelId(streamUrl: String): Int =
        streamUrl.substringBefore('?')
            .substringAfterLast('/')
            .substringBefore('.')
            .toIntOrNull()
            ?: 0

    /**
     * Promotes a TEE-mode recording slot to a fully independent DIRECT LibVLC recording.
     * Called by the TEE watchdog when it detects the player has exited.
     *
     * The TEE recording's :sout chain was owned by the player's MediaPlayer; once the
     * player calls mp.stop() the chain halts. This method opens a fresh LibVLC instance
     * inside RecordingService so the recording continues uninterrupted in the background,
     * writing a new segment file (suffix _cont.ts).
     *
     * Up to 3 simultaneous recordings (including the promoted slot) remain supported.
     */
    private fun promoteTeeToDirect(id: String, slot: RecordingSlot, streamUrl: String) {
        try {
            slot.sizeJob?.cancel()
            // Derive a continuation filename alongside the original segment
            val outputDir = java.io.File(slot.outputPath).parentFile
            if (outputDir == null || (!outputDir.exists() && !outputDir.mkdirs())) {
                Timber.e("DVR promote: cannot access output dir for $id — stopping")
                slots.remove(id)
                dvrRepo.updateFilePath(id, slot.outputPath)
                dvrRepo.stopRecording(id)
                if (slots.isEmpty()) stopSelf()
                return
            }
            val safe    = slot.channelName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)
            val stamp   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val newPath = "${outputDir.absolutePath}/${safe}_${stamp}_cont.ts"
            runCatching { File(slot.outputPath).takeIf { it.length() <= 0L }?.delete() }

            if (shouldUseRawHttpCopy(streamUrl)) {
                val directSlot = slot.copy(
                    outputPath   = newPath,
                    permanentUri = newPath,
                    libVLC       = null,
                    mediaPlayer  = null,
                    sizeJob      = null,
                    downloadJob  = null,
                    isTeeMode    = false
                )
                slots[id] = directSlot
                val downloadJob = serviceScope.launch { recordHttpLoop(id, streamUrl, newPath) }
                slots[id] = directSlot.copy(
                    downloadJob = downloadJob,
                    sizeJob = startSizePolling(id, newPath)
                )
                dvrRepo.updateFilePath(id, newPath)
                startStaleWatchdog(id)
                verifyRecordingStart(id)
                updateNotification()
                Timber.i("DVR promoted TEE→HTTP recorder for '${slot.channelName}': $newPath")
                return
            }

            // Start a new headless LibVLC :sout recording — no display sink needed
            val vlc = createRecordingLibVlc()
            val mp    = MediaPlayer(vlc)
            val media = Media(vlc, Uri.parse(streamUrl)).apply {
                addOption(":network-caching=3000")
                addOption(":live-caching=3000")
                addOption(":http-user-agent=$RECORDING_USER_AGENT")
                addOption(":http-reconnect")
                addOption(buildStdSout(newPath))
                addOption(":sout-keep")
            }
            mp.media = media
            media.release()
            mp.play()

            // Replace the dead TEE slot with the new DIRECT slot
            val newSlot = slot.copy(
                outputPath   = newPath,
                permanentUri = newPath,
                libVLC       = vlc,
                mediaPlayer  = mp,
                sizeJob      = null,
                isTeeMode    = false
            )
            slots[id] = newSlot
            slots[id] = newSlot.copy(sizeJob = startSizePolling(id, newPath))
            dvrRepo.updateFilePath(id, newPath)
            startStaleWatchdog(id)
            verifyRecordingStart(id)
            updateNotification()
            Timber.i("DVR promoted TEE→DIRECT for '${ slot.channelName }': $newPath")
        } catch (e: Exception) {
            Timber.e(e, "DVR: promoteTeeToDirect failed for $id")
            // Fallback: clean stop so slot doesn't stay zombie
            slots.remove(id)
            dvrRepo.updateFilePath(id, slot.outputPath)
            dvrRepo.stopRecording(id)
            if (slots.isEmpty()) stopSelf()
        }
    }

    // ── File size polling ─────────────────────────────────────────────────────

    /**
     * Launches a coroutine that polls the recording file size every 30 seconds.
     * Updates DvrRecordingRepository so the Active/Completed tabs show live size.
     * Works for both FFmpegKit direct recordings and LibVLC TEE recordings
     * since both write to absolute file paths (no SAF PFDs required for OTG recordings
     * when using the new resolveOutputFile() / StorageDetector approach).
     *
     * Runs until the slot is removed from [slots] or the service is destroyed.
     * The returned Job is stored in RecordingSlot.sizeJob and cancelled on stop.
     */
    private fun startSizePolling(
        id: String,
        outputPath: String
    ): Job = serviceScope.launch {
        delay(6_000L)
        while (isActive && slots.containsKey(id)) {
            if (!isActive || !slots.containsKey(id)) break
            try {
                val bytes = File(outputPath).length()
                if (bytes > 0L) {
                    dvrRepo.updateFileSizeBytes(id, bytes)
                    Timber.v("DVR size poll: $id = ${bytes / 1_048_576}MB")
                }
            } catch (e: Exception) {
                Timber.w("DVR: size poll failed for $id: ${e.message}")
            }
            delay(15_000L)
        }
    }

    // ── Stale recording watchdog ──────────────────────────────────────────────

    /**
     * Monitors a recording every 10 seconds and checks whether the output file
     * is still growing. If the file size hasn't changed for 60 seconds the stream
     * has stalled — the watchdog stops the current VLC instance, waits 3 seconds,
     * then restarts the recording into a new continuation segment file.
     *
     * This fixes the DVR freeze described in the deep-dive guide where recordings
     * silently stop writing data after ~1 minute without error feedback.
     */
    private val watchdogJobs = mutableMapOf<String, kotlinx.coroutines.Job>()

    private fun startStaleWatchdog(id: String) {
        watchdogJobs[id]?.cancel()
        watchdogJobs[id] = serviceScope.launch {
            var lastSize  = -1L
            var staleCount = 0

            while (isActive && slots.containsKey(id)) {
                delay(8_000L)
                if (!isActive || !slots.containsKey(id)) break

                val slot = slots[id] ?: break
                if (slot.downloadJob != null) {
                    try {
                        val currentSize = File(slot.outputPath).length()
                        if (currentSize == lastSize) {
                            staleCount++
                            Timber.w("DVR HTTP watchdog [$id]: file not growing (stale=$staleCount/5) — ${slot.channelName}")
                            if (staleCount >= 5) {
                                restartHttpRecording(id, slot)
                                staleCount = 0
                            }
                        } else {
                            staleCount = 0
                            lastSize = currentSize
                        }
                    } catch (e: Exception) {
                        Timber.w("DVR HTTP watchdog [$id]: size check error — ${e.message}")
                    }
                    continue
                }
                if (slot.isTeeMode) {
                    // TEE mode: file written by player — not our responsibility to restart
                    continue
                }

                try {
                    val currentSize = File(slot.outputPath).length()
                    if (currentSize == lastSize) {
                        staleCount++
                        Timber.w("DVR watchdog [$id]: file not growing (stale=$staleCount/5) — ${slot.channelName}")
                        if (staleCount >= 5) {
                            // ~40 seconds with no new bytes → restart
                            Timber.e("DVR watchdog [$id]: stream stalled — restarting recording for ${slot.channelName}")
                            restartStaledRecording(id, slot)
                            staleCount = 0
                        }
                    } else {
                        staleCount = 0
                        lastSize = currentSize
                    }
                } catch (e: Exception) {
                    Timber.w("DVR watchdog [$id]: size check error — ${e.message}")
                }
            }

            watchdogJobs.remove(id)
        }
    }

    private fun stopStaleWatchdog(id: String) {
        watchdogJobs.remove(id)?.cancel()
    }

    private fun verifyRecordingStart(id: String) {
        serviceScope.launch {
            delay(if (slots[id]?.downloadJob != null) 14_000L else 18_000L)
            val slot = slots[id] ?: return@launch
            val bytes = runCatching { File(slot.outputPath).length() }.getOrDefault(0L)
            if (bytes > 0L) {
                startRepairAttempts.remove(id)
                dvrRepo.updateFileSizeBytes(id, bytes)
                dvrRepo.emitEvent(DvrEvent.RecordingStarted(id, slot.channelName))
                return@launch
            }

            Timber.w("DVR verify: ${slot.channelName} has not written bytes yet; repairing path")
            val repairAttempt = (startRepairAttempts[id] ?: 0) + 1
            startRepairAttempts[id] = repairAttempt
            if (repairAttempt > MAX_START_REPAIR_ATTEMPTS) {
                failRecording(id, slot, "DVR could not write stream bytes after repair")
                return@launch
            }
            if (slot.isTeeMode) {
                // TEE shares the player's single provider connection, so we never open
                // stream #2 until the player-owned path proves it is dead. If no bytes
                // are written, detach the player's :sout and promote to an independent
                // recorder before surfacing a failure.
                Timber.w("DVR TEE verify: ${slot.channelName} wrote zero bytes — promoting to independent recorder")
                PlayerRecordingBridge.postTeeStop(id)
                promoteTeeToDirect(id, slot, slot.streamUrl)
                return@launch
            }
            if (slot.downloadJob != null) {
                restartHttpRecording(id, slot)
            } else {
                restartStaledRecording(id, slot)
            }
        }
    }

    private fun restartHttpRecording(id: String, slot: RecordingSlot) {
        slot.downloadJob?.cancel()
        slot.sizeJob?.cancel()
        val outputDir = File(slot.outputPath).parentFile ?: return
        val safe = slot.channelName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val newPath = "${outputDir.absolutePath}/${safe}_${stamp}_http_restart.ts"
        val newJob = serviceScope.launch { recordHttpLoop(id, slot.streamUrl, newPath) }
        val newSlot = slot.copy(
            outputPath = newPath,
            permanentUri = newPath,
            downloadJob = newJob,
            sizeJob = startSizePolling(id, newPath)
        )
        slots[id] = newSlot
        dvrRepo.updateFilePath(id, newPath)
        verifyRecordingStart(id)
        Timber.i("DVR HTTP watchdog [$id]: restarted → $newPath")
    }

    private fun failRecording(id: String, slot: RecordingSlot, reason: String) {
        Timber.e("DVR failed [$id]: $reason (${slot.channelName})")
        startRepairAttempts.remove(id)
        slots.remove(id)?.let { removed ->
            removed.sizeJob?.cancel()
            removed.downloadJob?.cancel()
        }
        stopStaleWatchdog(id)
        runCatching { File(slot.outputPath).takeIf { it.length() <= 0L }?.delete() }
        finishRecordingAsFailed(id, slot.channelName, slot.streamUrl, slot.outputPath, reason)
        if (slots.isEmpty()) stopSelf() else updateNotification()
    }

    private fun finishRecordingAsFailed(
        id: String,
        channelName: String,
        streamUrl: String,
        outputPath: String?,
        reason: String
    ) {
        if (!dvrRepo.containsRecording(id)) {
            dvrRepo.addRecording(
                DvrRecording(
                    id = id,
                    channelName = channelName,
                    channelId = extractChannelId(streamUrl),
                    streamUrl = streamUrl,
                    filePath = outputPath.orEmpty()
                )
            )
        } else if (!outputPath.isNullOrBlank()) {
            dvrRepo.updateFilePath(id, outputPath)
        }
        val bytes = outputPath
            ?.let { runCatching { File(it).length() }.getOrDefault(0L) }
            ?: 0L
        if (bytes > 0L) dvrRepo.updateFileSizeBytes(id, bytes)
        dvrRepo.stopRecording(id)
        // Module 1.3 — surface the failure to the UI instead of reverting silently.
        dvrRepo.emitEvent(DvrEvent.RecordingFailed(id, channelName, reason))
        Timber.e("DVR visible failure [$id]: $reason")
    }

    /**
     * Restarts a stalled recording into a new continuation file.
     * The original slot is stopped cleanly; a new direct VLC :sout session begins.
     */
    private fun restartStaledRecording(id: String, slot: RecordingSlot) {
        try {
            // Stop the frozen VLC instance
            slot.sizeJob?.cancel()
            runCatching { slot.mediaPlayer?.stop() }
            runCatching { slot.mediaPlayer?.release() }
            runCatching { slot.libVLC?.release() }
            slots.remove(id)

            val streamUrl = slot.streamUrl
            val outputDir = File(slot.outputPath).parentFile ?: run {
                Timber.e("DVR watchdog: cannot find parent dir for ${slot.outputPath}")
                return
            }

            // Brief pause to let the server reset
            serviceScope.launch {
                delay(3_000L)

                val safe  = slot.channelName.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(40)
                val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault()).format(java.util.Date())
                val newPath = "${outputDir.absolutePath}/${safe}_${stamp}_restart.ts"

                val vlc = createRecordingLibVlc()

                val mp    = MediaPlayer(vlc)
                val media = Media(vlc, android.net.Uri.parse(streamUrl)).apply {
                    addOption(":network-caching=3000")
                    addOption(":live-caching=3000")
                    addOption(":http-user-agent=$RECORDING_USER_AGENT")
                    addOption(":http-reconnect")
                    addOption(buildStdSout(newPath))
                    addOption(":sout-keep")
                }
                mp.media = media
                media.release()
                mp.play()

                val newSlot = slot.copy(
                    outputPath   = newPath,
                    permanentUri = newPath,
                    libVLC       = vlc,
                    mediaPlayer  = mp,
                    sizeJob      = null,
                    isTeeMode    = false
                )
                slots[id] = newSlot
                slots[id] = newSlot.copy(sizeJob = startSizePolling(id, newPath))
                dvrRepo.updateFilePath(id, newPath)
                startStaleWatchdog(id)
                verifyRecordingStart(id)
                updateNotification()
                Timber.i("DVR watchdog [$id]: restarted → $newPath")
            }
        } catch (e: Exception) {
            Timber.e(e, "DVR watchdog: restart failed for $id")
        }
    }

    // ── Output path resolution ─────────────────────────────────────────────────

    /**
     * Resolves the best available output DIRECTORY for recording using a 4-tier
     * priority chain. Returns null only if ALL paths are unavailable (extremely rare).
     *
     * Priority:
     *   1. USB public Download/Downloads folder
     *   2. OTG/removable app-private folder
     *   3. Saved app-private USB/external path from the storage picker
     *   4. Primary external app dir via getExternalFilesDir("DVR")
     *   5. SAF content URI marker (not writable by LibVLC/HTTP copy directly)
     *   6. Internal filesDir/DVR (last resort — very limited space on Firestick)
     *
     * The returned File is the PARENT DIRECTORY. Callers append the filename.
     * [storageUriString] is the value passed in the intent (SAF tree URI or absolute path).
     * OTG is always attempted first; the saved picker path is honored next.
     */
    private fun resolveOutputFile(channelName: String, storageUriString: String?, providerName: String? = null): File? {
        // Sanitize provider name for use as a subfolder: strip protocol/port, keep hostname only,
        // replace any non-alphanumeric chars with underscores, cap at 32 chars.
        val safeProvider = providerName
            ?.removePrefix("https://")?.removePrefix("http://")
            ?.substringBefore(":")?.substringBefore("/")
            ?.replace(Regex("[^A-Za-z0-9._\\-]"), "_")
            ?.take(32)
            ?.takeIf { it.isNotBlank() }

        // Adds DVR/<provider> subfolder under a base directory (provider subfolder only when present).
        fun File.dvrSubDir(): File {
            val base = File(this, "DVR")
            return if (safeProvider != null) File(base, safeProvider) else base
        }

        // ── Priority 1: USB public Download/Downloads folder ────────────────────
        val usbDownloadDir = StorageDetector.findOtgPublicDownloadPath(this)
        if (usbDownloadDir != null) {
            val dvrDir = usbDownloadDir.dvrSubDir()
            if (dvrDir.exists() || dvrDir.mkdirs()) {
                Timber.i("DVR resolveOutputFile → USB Download: ${dvrDir.absolutePath}")
                return dvrDir
            }
        }

        // ── Priority 2: OTG / removable external via StorageDetector ─────────────
        // This is the Firestick DVR OTG Fix — bypasses broken SAF entirely.
        // The app already owns /storage/XXXX/Android/data/<pkg>/files — no dialog needed.
        val otgDir = StorageDetector.findOtgPath(this)
        if (otgDir != null) {
            val dvrDir = otgDir.dvrSubDir()
            if (dvrDir.exists() || dvrDir.mkdirs()) {
                Timber.i("DVR resolveOutputFile → OTG: ${dvrDir.absolutePath}")
                return dvrDir
            }
        }

        // ── Priority 3: saved app-private absolute path from the USB picker ───────
        if (!storageUriString.isNullOrBlank() && storageUriString.startsWith("/")) {
            val pickedBase = StorageDetector.findWritableAbsolutePath(storageUriString)
            if (pickedBase != null) {
                val dvrDir = pickedBase.dvrSubDir()
                if (dvrDir.exists() || dvrDir.mkdirs()) {
                    Timber.i("DVR resolveOutputFile → saved picker path: ${dvrDir.absolutePath}")
                    return dvrDir
                }
            }
        }

        // ── Priority 4: Public Downloads on primary storage ───────────────────────
        val primaryDownload = StorageDetector.findPrimaryPublicDownloadPath()
        if (primaryDownload != null) {
            val dvrDir = primaryDownload.dvrSubDir()
            if (dvrDir.exists() || dvrDir.mkdirs()) {
                Timber.i("DVR resolveOutputFile → Public Downloads: ${dvrDir.absolutePath}")
                return dvrDir
            }
        }

        // ── Priority 5: Primary external getExternalFilesDir ──────────────────────
        val primaryExternal = getExternalFilesDir(null)
        if (primaryExternal != null) {
            val dvrDir = primaryExternal.dvrSubDir()
            if (dvrDir.exists() || dvrDir.mkdirs()) {
                Timber.i("DVR resolveOutputFile → Primary external: ${dvrDir.absolutePath}")
                return dvrDir
            }
        }

        // ── Priority 6: SAF / previously selected content URI ─────────────────────
        // Only used if user explicitly chose a custom folder and OTG is not available.
        if (!storageUriString.isNullOrBlank()) {
            try {
                if (storageUriString.startsWith("content://")) {
                    // SAF content URI — fall back to internal temp (FFmpeg can't write to SAF URIs)
                    Timber.w("DVR: SAF content:// URI cannot be used directly with FFmpeg — using internal fallback")
                }
            } catch (e: Exception) {
                Timber.w(e, "DVR: SAF path resolution failed, using internal fallback")
            }
        }

        // ── Priority 7: Internal app files (very limited on Firestick — 8GB total) ─
        val internalDvr = filesDir.dvrSubDir()
        if (internalDvr.exists() || internalDvr.mkdirs()) {
            Timber.w("DVR resolveOutputFile → Internal (last resort): ${internalDvr.absolutePath}")
            return internalDvr
        }

        Timber.e("DVR resolveOutputFile: ALL paths failed — cannot record!")
        return null
    }

    private fun buildStdSout(path: String): String =
        ":sout=#std{access=file,mux=ts,dst=${vlcSoutQuoted(path)}}"

    private fun vlcSoutQuoted(path: String): String =
        "'${path.replace("'", "_")}'"

    private fun usableBytesOrUnknown(dir: File): Long {
        return runCatching {
            if (!dir.exists() && !dir.mkdirs()) return@runCatching -1L
            val probe = File(dir, ".dylandos_dvr_probe")
            probe.outputStream().use { it.write(1) }
            runCatching { probe.delete() }
            val usable = dir.usableSpace
            if (usable > 0L) usable else dir.freeSpace
        }.getOrDefault(-1L)
    }

    // ── SAF helpers (kept for PlayerRecordingBridge TEE mode compat) ──────────

    private fun createSafRecordingFile(parent: DocumentFile?, fileName: String): DocumentFile? {
        if (parent == null) return null
        parent.findFile(fileName)?.let { existing -> runCatching { existing.delete() } }
        val mimeCandidates = listOf("video/mp2t", "video/mp2ts", "application/octet-stream")
        for (mime in mimeCandidates) {
            val created = runCatching { parent.createFile(mime, fileName) }.getOrNull()
            if (created != null) return created
        }
        return null
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun buildNotification(): Notification {
        val stopAllIntent = PendingIntent.getService(
            this, 0,
            Intent(this, RecordingService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val activeCount  = slots.size
        val namesPreview = slots.values.take(2).joinToString(", ") { it.channelName }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("DYLANDOS DVR — $activeCount recording${if (activeCount != 1) "s" else ""} active")
            .setContentText(namesPreview.ifEmpty { "Starting…" })
            .addAction(android.R.drawable.ic_media_pause, "Stop All", stopAllIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // The "recording_channel" is already created in DylandosApp.createNotificationChannels().
        // This is a safety net in case the service is started before Application.onCreate().
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return   // already exists — do not recreate

        val channel = NotificationChannel(
            CHANNEL_ID,
            "DVR Recordings",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "DYLANDOS IPTV DVR active recording notifications"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }
}
