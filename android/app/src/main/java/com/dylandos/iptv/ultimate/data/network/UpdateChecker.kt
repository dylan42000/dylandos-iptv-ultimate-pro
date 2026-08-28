package com.dylandos.iptv.ultimate.data.network

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.dylandos.iptv.ultimate.data.model.UpdateInfo
import com.dylandos.iptv.ultimate.BuildConfig
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DYLANDOS IPTV — OTA Update Checker
 *
 * Fetches the GitHub Gist JSON and compares the remote versionCode to the
 * current app build.  If remote versionCode > current, returns UpdateInfo.
 *
 * The Gist URL is baked into BuildConfig.GIST_UPDATE_URL so you change only
 * the Gist contents to ship a new update — no app code change needed.
 *
 * To ship an update:
 *   1. Upload the new APK to Dropbox (or any direct-download link).
 *   2. Edit your Gist: bump versionCode, set apkUrl to the new link, update releaseNotes.
 *   3. The app will detect the new versionCode on next launch and prompt the user.
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val gson: Gson
) {
    data class DownloadSnapshot(
        val status: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    )

    internal data class ParsedUpdate(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val apkSize: Long?,
        val sha256: String?,
        val changelog: List<String>,
        val mandatory: Boolean,
        val minRequiredVersionCode: Int
    )

    // Dedicated short-timeout HTTP client — update check should be fast but reliable.
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Fetch remote update metadata and return UpdateInfo when remote versionCode is newer.
     *
     * Supports:
     * 1) GitHub Gist API payloads with any JSON file name.
     * 2) Direct raw JSON endpoints.
     * 3) Legacy schemas using versionName + top-level apkUrl/buildVariants.
     */
    suspend fun checkForUpdate(): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val candidates = fetchUpdateJsonCandidates(BuildConfig.GIST_UPDATE_URL)
            if (candidates.isEmpty()) {
                Timber.w("OTA: no JSON candidates found at ${BuildConfig.GIST_UPDATE_URL}")
                return@withContext null
            }

            val parsed = candidates.mapNotNull { (name, content) ->
                parseUpdateJson(content)?.also {
                    Timber.d("OTA: parsed update candidate '$name' v${it.versionCode} (${it.versionName})")
                }
            }

            val best = parsed.maxByOrNull { it.versionCode } ?: run {
                Timber.w("OTA: no valid update payload could be parsed")
                return@withContext null
            }

            Timber.d("OTA: remote v${best.versionCode} (${best.versionName}), local v${BuildConfig.VERSION_CODE}")
            if (!isNewerVersion(best.versionCode, BuildConfig.VERSION_CODE)) {
                Timber.i(
                    "OTA: no update offered because remote versionCode=${best.versionCode} " +
                        "is not newer than local=${BuildConfig.VERSION_CODE}"
                )
                return@withContext null
            }

            val changelog = best.changelog
            val releaseNotes = if (changelog.isNotEmpty()) {
                changelog.joinToString("\n• ", "• ")
            } else {
                "Version ${best.versionName}"
            }

            UpdateInfo(
                versionCode = best.versionCode,
                versionName = best.versionName,
                apkUrl = best.apkUrl,
                apkSize = best.apkSize,
                sha256 = best.sha256,
                releaseNotes = releaseNotes,
                changelog = changelog,
                mandatory = best.mandatory || (BuildConfig.VERSION_CODE < best.minRequiredVersionCode)
            )
        } catch (e: Exception) {
            Timber.w(e, "OTA check error — ignoring")
            null
        }
    }

    private fun fetchUpdateJsonCandidates(updateUrl: String): List<Pair<String, String>> {
        val resolvedUrl = normalizeUpdateEndpoint(updateUrl)
        val body = httpGet(resolvedUrl) ?: return emptyList()
        val root = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull()
        if (root == null) {
            return if (body.trim().startsWith("{")) listOf("direct" to body) else emptyList()
        }

        val filesObj = root.getAsJsonObjectOrNull("files")
        if (filesObj == null) {
            return if (body.trim().startsWith("{")) listOf("direct" to body) else emptyList()
        }

        val candidates = mutableListOf<Pair<String, String>>()
        filesObj.entrySet().forEach { (fileName, fileElement) ->
            val fileObj = fileElement.asJsonObjectOrNull() ?: return@forEach
            var content = fileObj.getAsStringOrNull("content")
            val truncated = fileObj.getAsBooleanOrNull("truncated") ?: false
            if ((content.isNullOrBlank() || truncated)) {
                val rawUrl = fileObj.getAsStringOrNull("raw_url")
                if (!rawUrl.isNullOrBlank()) {
                    content = httpGet(rawUrl)
                }
            }
            if (!content.isNullOrBlank() && content.trim().startsWith("{")) {
                candidates += fileName to content
            }
        }

        return prioritizeJsonCandidates(candidates)
    }

    private fun normalizeUpdateEndpoint(url: String): String {
        val trimmed = url.trim()
        if (trimmed.contains("api.github.com/gists/")) return trimmed

        val gistId = Regex("https?://gist\\.github\\.com/[^/]+/([a-fA-F0-9]+)")
            .find(trimmed)
            ?.groupValues
            ?.getOrNull(1)

        return if (!gistId.isNullOrBlank()) {
            "https://api.github.com/gists/$gistId"
        } else {
            trimmed
        }
    }

    private fun prioritizeJsonCandidates(candidates: List<Pair<String, String>>): List<Pair<String, String>> {
        return candidates.sortedBy { (name, _) ->
            val lower = name.lowercase()
            when {
                lower == "update.json" -> 0
                lower.endsWith("update.json") -> 1
                lower.contains("ota") && lower.endsWith(".json") -> 2
                lower.endsWith(".json") -> 3
                else -> 4
            }
        }
    }

    internal fun parseUpdateJson(rawJson: String): ParsedUpdate? {
        val json = runCatching { gson.fromJson(rawJson, JsonObject::class.java) }.getOrNull() ?: return null

        val versionCode = json.getAsIntOrNull("versionCode") ?: return null
        val versionName = json.getAsStringOrNull("version")
            ?: json.getAsStringOrNull("versionName")
            ?: versionCode.toString()
        val mandatory = json.getAsBooleanOrNull("mandatory") ?: false
        val minRequiredVersionCode = json.getAsIntOrNull("minRequiredVersionCode") ?: 0
        val changelog = extractChangelog(json)

        val flavor = BuildConfig.FLAVOR
        val variant = resolveVariant(json, flavor) ?: return null
        val apkUrl = normalizeApkUrl(variant.apkUrl)
        if (apkUrl.isBlank()) return null

        // SECURITY (fail closed): never offer an update that cannot be integrity-
        // checked. A blank SHA-256 (e.g. the legacy firestick gist payload) means
        // we cannot prove the APK is what we published — refuse it. Updates with
        // real hashes are generated automatically by the release pipeline.
        if (variant.sha256.isNullOrBlank()) {
            Timber.w("OTA: update payload for flavor '$flavor' has no SHA-256 — refusing unverified APK")
            return null
        }

        return ParsedUpdate(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            apkSize = variant.apkSize,
            sha256 = variant.sha256,
            changelog = changelog,
            mandatory = mandatory,
            minRequiredVersionCode = minRequiredVersionCode
        )
    }

    /** Kept separate so the strict OTA version gate has a regression test. */
    internal fun isNewerVersion(remoteVersionCode: Int, localVersionCode: Int): Boolean =
        remoteVersionCode > localVersionCode

    private data class VariantAsset(
        val apkUrl: String,
        val apkSize: Long?,
        val sha256: String?
    )

    private fun resolveVariant(root: JsonObject, flavor: String): VariantAsset? {
        extractVariantFromMap(root.getAsJsonObjectOrNull("flavors"), flavor)?.let { return it }
        extractVariantFromMap(root.getAsJsonObjectOrNull("buildVariants"), flavor)?.let { return it }

        val topLevelUrl = root.getAsStringOrNull("apkUrl")
        if (!topLevelUrl.isNullOrBlank()) {
            return VariantAsset(
                apkUrl = topLevelUrl,
                apkSize = root.getAsLongOrNull("apkSize"),
                sha256 = root.getAsStringOrNull("sha256")
            )
        }
        return null
    }

    private fun extractVariantFromMap(variants: JsonObject?, flavor: String): VariantAsset? {
        if (variants == null || variants.entrySet().isEmpty()) return null

        val exact = variants.entrySet().firstOrNull { it.key.equals(flavor, ignoreCase = true) }
        val fallback = variants.entrySet().firstOrNull { entry ->
            entry.value.asJsonObjectOrNull()?.getAsStringOrNull("apkUrl").isNullOrBlank().not()
        }
        val chosen = exact ?: fallback ?: return null
        val obj = chosen.value.asJsonObjectOrNull() ?: return null
        val url = obj.getAsStringOrNull("apkUrl") ?: return null

        return VariantAsset(
            apkUrl = url,
            apkSize = obj.getAsLongOrNull("apkSize"),
            sha256 = obj.getAsStringOrNull("sha256")
        )
    }

    private fun extractChangelog(json: JsonObject): List<String> {
        val raw = json.get("changelog")
        val fromArray = raw.asJsonArrayOrNull()
            ?.mapNotNull { it.asStringOrNull() }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (fromArray.isNotEmpty()) return fromArray

        val fromString = raw.asStringOrNull()
            ?.split("\n", "•")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (fromString.isNotEmpty()) return fromString

        val notes = json.getAsStringOrNull("releaseNotes")
            ?.split("\n", "•")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        return notes
    }

    private fun normalizeApkUrl(url: String): String {
        val normalized = url.trim()
        if (!normalized.contains("dropbox.com", ignoreCase = true)) return normalized

        return when {
            normalized.contains("dl=1") -> normalized
            normalized.contains("dl=0") -> normalized.replace("dl=0", "dl=1")
            normalized.contains("?") -> "$normalized&dl=1"
            else -> "$normalized?dl=1"
        }
    }

    private fun httpGet(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "DYLANDOS-IPTV-Android/${BuildConfig.VERSION_NAME}")
            .header("Cache-Control", "no-cache")
            .build()

        return http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Timber.w("OTA fetch failed: HTTP ${resp.code} for $url")
                return@use null
            }
            resp.body?.string()
        }
    }

    /**
     * Enqueue the APK download via Android DownloadManager.
     * Returns the DownloadManager download ID (needed to track completion).
     * The APK will be saved to the app's external Downloads folder.
     */
    fun enqueueDownload(context: Context, apkUrl: String, versionName: String): Long {
        val safeVersionName = versionName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val fileName = "dylandos-iptv-${safeVersionName}.apk"
        val request = DownloadManager.Request(Uri.parse(apkUrl)).apply {
            setTitle("DYLANDOS IPTV Update")
            setDescription("Downloading v$versionName...")
            setMimeType("application/vnd.android.package-archive")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(context, "Downloads", fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
        }
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(request)
    }

    /**
     * Query the DownloadManager for the current status of a download.
     * Returns current status, downloaded bytes, and total bytes.
     */
    fun queryDownload(context: Context, downloadId: Long): DownloadSnapshot? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
        return cursor.use { c ->
            if (!c.moveToFirst()) return null
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val downloaded = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            DownloadSnapshot(status, downloaded, total)
        }
    }

    /**
     * Get the local file URI for a completed download so it can be installed.
     */
    fun getDownloadUri(context: Context, downloadId: Long): Uri? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.getUriForDownloadedFile(downloadId)
    }

    private fun JsonElement?.asJsonObjectOrNull(): JsonObject? {
        return if (this != null && isJsonObject) asJsonObject else null
    }

    private fun JsonElement?.asJsonArrayOrNull(): JsonArray? {
        return if (this != null && isJsonArray) asJsonArray else null
    }

    private fun JsonElement?.asStringOrNull(): String? {
        return if (this != null && isJsonPrimitive) asString else null
    }

    private fun JsonObject.getAsJsonObjectOrNull(name: String): JsonObject? {
        return get(name).asJsonObjectOrNull()
    }

    private fun JsonObject.getAsStringOrNull(name: String): String? {
        return get(name).asStringOrNull()
    }

    private fun JsonObject.getAsBooleanOrNull(name: String): Boolean? {
        val value = get(name) ?: return null
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isBoolean -> value.asBoolean
            value.isJsonPrimitive -> value.asString.toBooleanStrictOrNull()
            else -> null
        }
    }

    private fun JsonObject.getAsIntOrNull(name: String): Int? {
        val value = get(name) ?: return null
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asInt
            value.isJsonPrimitive -> value.asString.toIntOrNull()
            else -> null
        }
    }

    private fun JsonObject.getAsLongOrNull(name: String): Long? {
        val value = get(name) ?: return null
        return when {
            value.isJsonPrimitive && value.asJsonPrimitive.isNumber -> value.asLong
            value.isJsonPrimitive -> value.asString.toLongOrNull()
            else -> null
        }
    }
}
