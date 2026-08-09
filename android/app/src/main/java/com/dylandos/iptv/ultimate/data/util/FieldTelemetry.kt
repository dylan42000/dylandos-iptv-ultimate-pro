package com.dylandos.iptv.ultimate.data.util

import android.content.Context
import android.os.Build
import com.dylandos.iptv.ultimate.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Anonymized field telemetry — crash + playback failure codes only.
 * Never writes credentials, stream URLs with user/pass, or account names.
 */
@Singleton
class FieldTelemetry @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val installId: String by lazy {
        val prefs = context.getSharedPreferences("field_telemetry", Context.MODE_PRIVATE)
        prefs.getString("install_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install_id", it).apply()
        }
    }

    private val logFile: File
        get() = File(context.filesDir, "field_telemetry.jsonl")

    fun recordCrash(threadName: String, throwable: Throwable) {
        append(
            kind = "crash",
            code = throwable.javaClass.simpleName,
            detail = (throwable.message ?: "").take(180).sanitizeSecrets()
        )
        try {
            val crashLog = File(context.filesDir, "crash_log.txt")
            val entry = "${System.currentTimeMillis()}: ${throwable.javaClass.simpleName}: ${throwable.message}\n"
            if (crashLog.exists() && crashLog.length() > 256 * 1024) {
                val lines = crashLog.readLines()
                crashLog.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
            }
            crashLog.appendText(entry)
        } catch (_: Exception) {
        }
    }

    fun recordPlaybackFailure(
        engine: String,
        errorCode: String,
        streamType: String,
        extra: String? = null
    ) {
        append(
            kind = "playback_fail",
            code = errorCode.take(64),
            detail = buildString {
                append(engine.take(24))
                append('|')
                append(streamType.take(16))
                if (!extra.isNullOrBlank()) {
                    append('|')
                    append(extra.take(120).sanitizeSecrets())
                }
            }
        )
    }

    fun recordDecodeFailure(engine: String, reason: String) {
        append(
            kind = "decode_fail",
            code = reason.take(64).sanitizeSecrets(),
            detail = engine.take(24)
        )
    }

    private fun append(kind: String, code: String, detail: String) {
        try {
            val obj = JSONObject()
                .put("ts", System.currentTimeMillis())
                .put("kind", kind)
                .put("code", code)
                .put("detail", detail)
                .put("ver", BuildConfig.VERSION_NAME)
                .put("vc", BuildConfig.VERSION_CODE)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
                .put("sdk", Build.VERSION.SDK_INT)
                .put("iid", installId.take(8))
            val file = logFile
            if (file.exists() && file.length() > 512 * 1024) {
                val lines = file.readLines()
                file.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
            }
            file.appendText(obj.toString() + "\n")
        } catch (e: Exception) {
            Timber.w(e, "FieldTelemetry append failed")
        }
    }
}

private fun String.sanitizeSecrets(): String =
    replace(Regex("""(?i)(user(name)?|pass(word)?|token|pwd)\s*[:=]\s*\S+"""), "$1=***")
        .replace(Regex("""://[^/@]+@"""), "://***@")
