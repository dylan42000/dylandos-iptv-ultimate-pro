package com.dylandos.iptv.ultimate.ui.screens.login

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.model.XtreamProfile
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// Login mode
enum class LoginMode { XTREAM, M3U }

data class LoginUiState(
    val mode: LoginMode = LoginMode.XTREAM,
    // Xtream fields
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    // M3U field
    val m3uUrl: String = "",
    // Status
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val hasExistingCredentials: Boolean = false
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xtreamRepository: XtreamRepository,
    private val database: com.dylandos.iptv.ultimate.data.db.AppDatabase
) : ViewModel() {

    companion object {
        val SERVER_URL_KEY  = stringPreferencesKey("server_url")
        val USERNAME_KEY    = stringPreferencesKey("username")
        val PASSWORD_KEY    = stringPreferencesKey("password")
        val M3U_URL_KEY     = stringPreferencesKey("m3u_url")
        val LOGIN_MODE_KEY  = stringPreferencesKey("login_mode")
    }

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { checkExistingCredentials() }
    }

    /** Check DataStore; if credentials exist, auto-connect and mark isLoggedIn. */
    private suspend fun checkExistingCredentials() {
        val prefs = context.dataStore.data.first()
        val mode  = if (prefs[LOGIN_MODE_KEY] == "M3U") LoginMode.M3U else LoginMode.XTREAM
        val server = prefs[SERVER_URL_KEY] ?: ""
        val user   = prefs[USERNAME_KEY]   ?: ""
        val pass   = prefs[PASSWORD_KEY]   ?: ""
        val m3u    = prefs[M3U_URL_KEY]    ?: ""

        if (mode == LoginMode.XTREAM && server.isNotEmpty() && user.isNotEmpty()) {
            // Reconnect repository silently; no network call — just restores session
            xtreamRepository.connect(XtreamProfile("default", "Default", server, user, pass, true))
            runCatching { xtreamRepository.refreshProviderTimezone() }
            _state.value = _state.value.copy(
                mode = mode, serverUrl = server, username = user, password = pass,
                hasExistingCredentials = true, isLoggedIn = true
            )
        } else if (mode == LoginMode.M3U && m3u.isNotEmpty()) {
            _state.value = _state.value.copy(
                mode = mode, m3uUrl = m3u,
                hasExistingCredentials = true, isLoggedIn = true
            )
        } else {
            _state.value = _state.value.copy(mode = mode)
        }
    }

    fun setMode(mode: LoginMode) { _state.value = _state.value.copy(mode = mode, error = null) }
    fun setServerUrl(v: String)  { _state.value = _state.value.copy(serverUrl = v, error = null) }
    fun setUsername(v: String)   { _state.value = _state.value.copy(username = v, error = null) }
    fun setPassword(v: String)   { _state.value = _state.value.copy(password = v, error = null) }
    fun setM3uUrl(v: String)     { _state.value = _state.value.copy(m3uUrl = v, error = null) }

    /** Connect (Xtream): test credentials then save to DataStore. */
    fun connectXtream() {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(error = "Please fill in all fields")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val (ok, msg) = xtreamRepository.testConnection(s.serverUrl, s.username, s.password)
            if (ok) {
                // Clear the database to fix the Ghost Account leak
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    database.clearAllTables()
                }

                // Save to DataStore
                context.dataStore.edit { prefs ->
                    prefs[SERVER_URL_KEY] = s.serverUrl.trimEnd('/')
                    prefs[USERNAME_KEY]   = s.username
                    prefs[PASSWORD_KEY]   = s.password
                    prefs[LOGIN_MODE_KEY] = "XTREAM"
                }
                // Connect repository
                xtreamRepository.connect(XtreamProfile("default", "Default",
                    s.serverUrl, s.username, s.password, true))
                runCatching { xtreamRepository.refreshProviderTimezone() }
                _state.value = _state.value.copy(isLoading = false, isLoggedIn = true)
                Timber.i("Xtream login successful: ${s.serverUrl}")
            } else {
                _state.value = _state.value.copy(isLoading = false, error = msg)
            }
        }
    }

    /** Connect (M3U): validate URL format then save. */
    fun connectM3U() {
        val m3u = _state.value.m3uUrl.trim()
        if (m3u.isBlank()) { _state.value = _state.value.copy(error = "Please enter an M3U URL"); return }
        if (!m3u.startsWith("http://") && !m3u.startsWith("https://")) {
            _state.value = _state.value.copy(error = "M3U URL must start with http:// or https://")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                context.dataStore.edit { prefs ->
                    prefs[M3U_URL_KEY]    = m3u
                    prefs[LOGIN_MODE_KEY] = "M3U"
                }
                _state.value = _state.value.copy(isLoading = false, isLoggedIn = true)
                Timber.i("M3U login saved: $m3u")
            } catch (e: Exception) {
                _state.value = _state.value.copy(isLoading = false, error = "Failed to save: ${e.message}")
            }
        }
    }

    /** Sign out: clear DataStore and disconnect repository. */
    fun signOut() {
        viewModelScope.launch {
            context.dataStore.edit { it.clear() }
            xtreamRepository.disconnect()
            _state.value = LoginUiState()
        }
    }
}
