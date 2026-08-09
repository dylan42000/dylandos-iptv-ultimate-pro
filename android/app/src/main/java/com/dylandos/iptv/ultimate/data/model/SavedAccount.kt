package com.dylandos.iptv.ultimate.data.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * DYLANDOS IPTV — Saved Xtream Account
 *
 * Up to 10 accounts stored as JSON in DataStore.
 * The "active" account is identified by its [id].
 *
 * [accountType] controls which parts of the app this account is used for:
 *   "live"  — Live TV only
 *   "dvr"   — DVR recording only  
 *   "both"  — All features (default)
 */
data class SavedAccount(
    val id: String,              // UUID — stable identifier
    val nickname: String,        // Display label (e.g. "Home Server", "Work")
    val serverUrl: String,
    val username: String,
    val password: String,
    val accountType: String = "both"  // "live" | "dvr" | "both"
)

object AccountSerializer {
    private val gson = Gson()
    private val listType = object : TypeToken<List<SavedAccount>>() {}.type

    fun toJson(accounts: List<SavedAccount>): String = gson.toJson(accounts)
    fun fromJson(json: String): List<SavedAccount> =
        try { gson.fromJson(json, listType) ?: emptyList() } catch (_: Exception) { emptyList() }
}
