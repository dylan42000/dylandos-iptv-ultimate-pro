package com.dylandos.iptv.ultimate.ui.screens.replay

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.util.isUsEnPriorityLabel
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Provider-hosted catch-up only. It deliberately never starts the local live-buffer timeshift. */
@HiltViewModel
class ReplayViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _state = MutableStateFlow(ReplayUiState())
    val state: StateFlow<ReplayUiState> = _state.asStateFlow()

    private var programmeLoadJob: Job? = null
    private val programmeCache = LinkedHashMap<Int, CachedProgrammes>()

    init { refreshChannels() }

    fun refreshChannels() = viewModelScope.launch {
        val prefs = context.dataStore.data.first()
        val enabled = prefs[SettingsViewModel.KEY_PROVIDER_REPLAY_ENABLED] ?: true
        if (!enabled) {
            _state.value = ReplayUiState(enabled = false)
            return@launch
        }
        _state.value = _state.value.copy(enabled = true, loadingChannels = true, error = null)
        val channels = xtreamRepository.getLiveStreams().getOrElse {
            _state.value = _state.value.copy(loadingChannels = false, error = "Could not load Replay channels: ${it.message ?: "network error"}")
            return@launch
        }.filter { it.tvArchive == 1 }
            .sortedWith(compareBy<XtreamChannel> { !isUsEnPriorityLabel(it.name) }.thenBy { it.name.lowercase() })
        _state.value = _state.value.copy(
            channels = channels,
            loadingChannels = false,
            extension = prefs[SettingsViewModel.KEY_STREAM_FORMAT] ?: "ts"
        )
        if (channels.isNotEmpty()) selectChannel(channels.first())
    }

    fun selectChannel(channel: XtreamChannel) {
        programmeLoadJob?.cancel()
        programmeLoadJob = viewModelScope.launch {
        _state.value = _state.value.copy(selectedChannel = channel, loadingPrograms = true, programs = emptyList(), error = null)
        programmeCache[channel.streamId]?.takeIf { !it.isExpired() }?.let { cached ->
            _state.value = _state.value.copy(loadingPrograms = false, programs = cached.programmes)
            return@launch
        }
        // The complete table contains historical rows; short EPG often only includes now/next.
        val allResult = xtreamRepository.getSimpleDataTable(channel.streamId)
        val all = allResult.getOrElse { fullTableError ->
            // A few panels deny the full table but still expose a limited short EPG. Keeping
            // this fallback prevents a failed full-table request from blanking the Replay UI.
            xtreamRepository.getShortEpg(channel.streamId, limit = 100).getOrElse {
                _state.value = _state.value.copy(
                    loadingPrograms = false,
                    error = "Replay listing unavailable for this channel: ${fullTableError.message ?: "provider did not respond"}"
                )
                return@launch
            }
        }
        val nowSeconds = System.currentTimeMillis() / 1000L
        val programmes = all.filter { it.stopTimestamp in 1 until nowSeconds }
                .sortedByDescending { it.startTimestamp }
                .take(MAX_PROGRAMS_PER_CHANNEL)
        programmeCache[channel.streamId] = CachedProgrammes(programmes)
        _state.value = _state.value.copy(loadingPrograms = false, programs = programmes)
        }
    }

    /**
     * Opaque route token.  The first three fields are the guide-corrected values used by
     * old routes; fields four/five preserve the provider's original archive clock.
     */
    fun buildReplayToken(program: XtreamEpgProgram): String {
        val durationMinutes = ((program.stopTimestamp - program.startTimestamp + 59L) / 60L).coerceAtLeast(1L)
        val streamId = _state.value.selectedChannel?.streamId ?: 0
        val archiveStart = program.archiveStartTimestamp.takeIf { it > 0L } ?: program.startTimestamp
        val archiveWallClock = program.archiveStartWallClock.orEmpty()
            .filter(Char::isDigit)
            .take(12)
            .takeIf { it.length == 12 }
            .orEmpty()
        return "${streamId}_${program.startTimestamp}_${durationMinutes}_${archiveStart}_${archiveWallClock}"
    }

    fun setPendingTitle(program: XtreamEpgProgram) {
        val channelName = _state.value.selectedChannel?.name ?: "Replay"
        xtreamRepository.pendingStreamTitle = "$channelName - ${program.title.ifBlank { "Programme" }}"
    }

    private companion object { const val MAX_PROGRAMS_PER_CHANNEL = 250 }
}

private data class CachedProgrammes(
    val programmes: List<XtreamEpgProgram>,
    val savedAtMs: Long = System.currentTimeMillis()
) {
    fun isExpired() = System.currentTimeMillis() - savedAtMs > 15 * 60 * 1_000L
}

data class ReplayUiState(
    val enabled: Boolean = true,
    val loadingChannels: Boolean = true,
    val loadingPrograms: Boolean = false,
    val channels: List<XtreamChannel> = emptyList(),
    val selectedChannel: XtreamChannel? = null,
    val programs: List<XtreamEpgProgram> = emptyList(),
    val extension: String = "ts",
    val error: String? = null
)
