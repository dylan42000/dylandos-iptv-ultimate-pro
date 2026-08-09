package com.dylandos.iptv.ultimate.data.model

import com.google.gson.annotations.SerializedName

/**
 * DYLANDOS IPTV — OTA Update Info
 *
 * Flat resolved model used throughout the app (UpdateViewModel, UpdateDialog).
 * Populated by UpdateChecker which parses the Gist API response and picks the
 * correct flavor (firestick / premium) APK URL automatically.
 *
 * The Gist JSON uses a nested "flavors" structure — UpdateChecker resolves it
 * into these flat fields so callers don't need to know the device type.
 */
data class UpdateInfo(
    val versionCode: Int = 0,
    val versionName: String = "",
    /** APK download URL — already resolved to the correct flavor by UpdateChecker. */
    val apkUrl: String = "",
    /** Optional remote APK size in bytes (if provided by update JSON). */
    val apkSize: Long? = null,
    /** Optional SHA-256 checksum for integrity verification. */
    val sha256: String? = null,
    /** Human-readable bullet list from changelog array. */
    val releaseNotes: String = "",
    val changelog: List<String> = emptyList(),
    val mandatory: Boolean = false
)

// ── Internal model for parsing the GitHub Gist API response ──────────────────

/** Represents one flavor entry inside the Gist JSON. */
internal data class GistFlavorInfo(
    @SerializedName("apkUrl")    val apkUrl: String = "",
    @SerializedName("apkSize")   val apkSize: Long = 0L,
    @SerializedName("sha256")    val sha256: String = ""
)

/**
 * Full structure of the update.json Gist file.
 * Parsed by UpdateChecker, then converted to [UpdateInfo].
 */
internal data class GistUpdateJson(
    @SerializedName("versionCode")             val versionCode: Int = 0,
    @SerializedName("version")                 val version: String = "",
    @SerializedName("releaseDate")             val releaseDate: String = "",
    @SerializedName("mandatory")               val mandatory: Boolean = false,
    @SerializedName("minRequiredVersionCode")   val minRequiredVersionCode: Int = 0,
    @SerializedName("changelog")               val changelog: List<String> = emptyList(),
    @SerializedName("flavors")                 val flavors: Map<String, GistFlavorInfo> = emptyMap()
)
