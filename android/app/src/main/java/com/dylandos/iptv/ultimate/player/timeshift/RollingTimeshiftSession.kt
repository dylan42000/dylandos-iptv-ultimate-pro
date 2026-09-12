package com.dylandos.iptv.ultimate.player.timeshift

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/** One provider connection, bounded disk segments, and a loopback-only HLS DVR window. */
class RollingTimeshiftSession(
    parent: File,
    private val sourceUrl: String,
    private val maxBytes: Long,
    private val windowMinutes: Int,
    private val userAgent: String = "VLC/3.0.18 LibVLC/3.0.18",
) : AutoCloseable {
    private data class Segment(val sequence: Long, val file: File, val seconds: Double, val discontinuity: Boolean)
    private val directory = File(parent, "live-ring-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    private val token = UUID.randomUUID().toString()
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val clients = java.util.concurrent.ThreadPoolExecutor(2, 4, 15, TimeUnit.SECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(16), java.util.concurrent.ThreadPoolExecutor.AbortPolicy())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val segments = ArrayDeque<Segment>()
    private val client = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).build()
    @Volatile private var call: Call? = null
    @Volatile private var closed = false
    @Volatile private var failure: String? = null
    private var sequence = 0L
    private var discardedDiscontinuities = 0L
    private var captureJob: Job? = null

    suspend fun start(): String {
        scope.launch {
            while (isActive && !closed) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                try { clients.execute { serve(socket) } } catch (_: Exception) { socket.close() }
            }
        }
        captureJob = scope.launch { capture() }
        try {
            withTimeout(45_000) {
                while (synchronized(lock) { segments.size < 3 }) {
                    failure?.let { error(it) }
                    delay(100)
                }
            }
        } catch (e: Exception) { close(); throw e }
        return "http://127.0.0.1:${server.localPort}/$token/index.m3u8"
    }

    private suspend fun capture() {
        var attempts = 0
        try {
            while (!closed && currentCoroutineContext().isActive) {
                try {
                    val request = Request.Builder().url(sourceUrl).header("User-Agent", userAgent).build()
                    call = client.newCall(request)
                    call!!.execute().use { response ->
                        check(response.isSuccessful) { "Provider returned HTTP ${response.code}" }
                        val input = response.body?.byteStream()?.buffered(64 * 1024) ?: error("Empty live stream")
                        input.use {
                            val packet = ByteArray(188)
                            var output: java.io.FileOutputStream? = null
                            var file: File? = null
                            var startPcr: Long? = null
                            var lastPcr: Long? = null
                            var startWall = System.nanoTime()
                            var discontinuity = sequence > 0
                            var skippedBytes = 0
                            try {
                                while (!closed && currentCoroutineContext().isActive) {
                                    // Resynchronize on a TS packet boundary; reject playlists / HTML early.
                                    val first = input.read()
                                    if (first < 0) break
                                    if (first != 0x47) {
                                        skippedBytes++
                                        check(skippedBytes < 188 * 50) { "Timeshift requires an MPEG-TS live stream" }
                                        continue
                                    }
                                    packet[0] = 0x47
                                    skippedBytes = 0
                                    var read = 1
                                    while (read < 188) {
                                        val n = input.read(packet, read, 188 - read)
                                        if (n < 0) error("Live stream disconnected")
                                        read += n
                                    }
                                    val pcr = TsClock.pcrMs(packet)
                                    if (pcr != null) {
                                        if (startPcr == null) startPcr = pcr
                                        lastPcr = pcr
                                    }
                                    val clockSeconds = if (startPcr != null && lastPcr != null) (lastPcr!! - startPcr!!) / 1000.0 else -1.0
                                    val seconds = clockSeconds.takeIf { it in 0.0..120.0 }
                                        ?: (System.nanoTime() - startWall) / 1_000_000_000.0
                                    if (output != null && seconds >= 4.0 && (TsClock.isPat(packet) || seconds >= 8.0)) {
                                        output.close()
                                        publish(file!!, seconds, discontinuity)
                                        output = null
                                        discontinuity = false
                                        startPcr = pcr ?: lastPcr
                                        startWall = System.nanoTime()
                                        attempts = 0
                                    }
                                    if (output == null) {
                                        check(directory.usableSpace == 0L || directory.usableSpace > 16L * 1024 * 1024) { "Timeshift storage is full" }
                                        file = File(directory, "segment-${sequence}.ts")
                                        output = file!!.outputStream()
                                    }
                                    output.write(packet)
                                }
                            } finally { output?.close(); file?.takeIf { f -> synchronized(lock) { segments.none { it.file == f } } }?.delete() }
                        }
                    }
                    if (!closed) delay(1000)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (++attempts > 5 || e.message?.contains("storage") == true) {
                        failure = e.message ?: "Timeshift stream could not be buffered"
                        break
                    }
                    delay(1000L * attempts.coerceAtMost(3))
                }
            }
        } finally {
            if (closed) directory.deleteRecursively()
        }
    }

    private fun publish(file: File, seconds: Double, discontinuity: Boolean) = synchronized(lock) {
        segments.addLast(Segment(sequence++, file, seconds.coerceIn(0.1, 120.0), discontinuity))
        val maxSeconds = (windowMinutes.takeIf { it > 0 } ?: 480).coerceIn(1, 480) * 60
        var bytes = segments.sumOf { it.file.length() }
        var duration = segments.sumOf { it.seconds }
        while (segments.size > 3 && (bytes > maxBytes || duration > maxSeconds)) {
            val old = segments.removeFirst()
            bytes -= old.file.length(); duration -= old.seconds
            if (old.discontinuity) discardedDiscontinuities++
            old.file.delete()
        }
    }

    private fun playlist(): ByteArray = synchronized(lock) {
        buildString {
            append("#EXTM3U\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:${ceil(segments.maxOfOrNull { it.seconds } ?: 8.0).toInt()}\n")
            append("#EXT-X-MEDIA-SEQUENCE:${segments.firstOrNull()?.sequence ?: 0}\n#EXT-X-DISCONTINUITY-SEQUENCE:$discardedDiscontinuities\n")
            segments.forEach {
                if (it.discontinuity) append("#EXT-X-DISCONTINUITY\n")
                append("#EXTINF:${String.format(java.util.Locale.US, "%.3f", it.seconds)},\n${it.file.name}\n")
            }
            if (failure != null) append("#EXT-X-ENDLIST\n")
        }.toByteArray(Charsets.UTF_8)
    }

    private fun serve(socket: Socket) {
        try { socket.use {
            it.soTimeout = 5000
            val input = it.getInputStream().bufferedReader(Charsets.US_ASCII)
            val request = input.readLine()?.take(2048)?.split(' ') ?: return
            val route = request.getOrNull(1)?.substringBefore('?') ?: return
            var lines = 0
            while (lines++ < 40) { if (input.readLine().isNullOrEmpty()) break }
            val name = route.removePrefix("/$token/")
            val bytes = if (route == "/$token/index.m3u8") playlist() else null
            val fileInput = if (route.startsWith("/$token/") && name.matches(Regex("segment-[0-9]+\\.ts"))) {
                synchronized(lock) { segments.firstOrNull { s -> s.file.name == name }?.file?.inputStream() }
            } else null
            val out = it.getOutputStream().buffered()
            val size = bytes?.size?.toLong() ?: fileInput?.channel?.size()
            if (size == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return }
            val type = if (bytes != null) "application/vnd.apple.mpegurl" else "video/mp2t"
            out.write("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: $size\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
            if (bytes != null) out.write(bytes) else fileInput!!.use { f -> f.copyTo(out, 32 * 1024) }
            out.flush()
        } } catch (_: Exception) { runCatching { socket.close() } }
    }

    override fun close() {
        if (closed) return
        closed = true
        call?.cancel()
        runCatching { server.close() }
        clients.shutdownNow()
        scope.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            captureJob?.join()
            directory.deleteRecursively()
        }
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}
