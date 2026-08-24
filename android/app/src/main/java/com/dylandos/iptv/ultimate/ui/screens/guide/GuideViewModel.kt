package com.dylandos.iptv.ultimate.ui.screens.guide

import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.db.dao.EpgChannelAliasDao
import com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
import com.dylandos.iptv.ultimate.data.db.entity.EpgChannelAliasEntity
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.data.network.EpgChannelMatcher
import com.dylandos.iptv.ultimate.data.network.EpgSourceDefaults
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.network.XmltvParser
import com.dylandos.iptv.ultimate.ui.screens.settings.SettingsViewModel
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.dylandos.iptv.ultimate.data.util.sortCategories
import timber.log.Timber
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — Guide (EPG) ViewModel
 *
 * Provides the data for the cable-style EPG grid:
 *   - Category list for the left rail
 *   - Channels + programs for the main grid
 *   - Current time window (starts at the top of the hour)
 *   - Window hours read from Settings DataStore (2/4/6/8h)
 *   - EPG row height mode read from Settings DataStore (Compact/Normal/Large)
 *   - EPG preloaded for first N rows (configurable, default 30)
 *   - D-pad channel navigation (zapUp / zapDown / setFocusedChannel)
 */
@HiltViewModel
class GuideViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val epgProgramDao: EpgProgramDao,
    private val epgChannelAliasDao: EpgChannelAliasDao,
    private val favoriteDao: FavoriteDao,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private fun normalizeCategoryId(raw: String?): String = raw?.trim().orEmpty()

    private fun filterChannelsForCategory(
        channels: List<XtreamChannel>,
        selectedCategoryId: String,
        favoriteIds: Set<Int>
    ): List<XtreamChannel> = when (normalizeCategoryId(selectedCategoryId)) {
        "ALL" -> channels
        FAVORITES_CATEGORY_ID -> channels.filter { it.streamId in favoriteIds }
        else -> channels.filter { normalizeCategoryId(it.categoryId) == normalizeCategoryId(selectedCategoryId) }
    }

    companion object {
        private const val KEY_SELECTED_CATEGORY_ID = "guide_selected_category_id"
        private const val KEY_FOCUSED_CHANNEL_INDEX = "guide_focused_channel_index"
        private const val KEY_VERTICAL_LIST_INDEX = "guide_vertical_list_index"
        private const val KEY_VERTICAL_LIST_OFFSET = "guide_vertical_list_offset"
        private const val KEY_HORIZONTAL_SCROLL_OFFSET = "guide_horizontal_scroll_offset"
        private const val LOAD_TIMEOUT_MS = 30_000L
        val KEY_XMLTV_LAST_FETCH_MS = longPreferencesKey("xmltv_last_fetch_ms")
        val KEY_EPG_THIRD_PARTY_ENABLED = booleanPreferencesKey("epg_third_party_enabled")
        val KEY_EPG_THIRD_PARTY_URL = stringPreferencesKey("epg_third_party_url")
        /**
         * Bump [EPG_DATA_VERSION] whenever stored EPG semantics change (timezone fixes,
         * parser changes) — Room rows parsed by older builds are then force-refreshed
         * once, so users never keep seeing stale shifted grids after an upgrade.
         * v5.0.4: v6 forces a clean re-import after restoring Xtream's UTC handling
         * for offset-less XMLTV/short-EPG values. Provider-zone parsing caused the
         * persistent six-hour shift on Firestick.
         */
        val KEY_XMLTV_DATA_VERSION = intPreferencesKey("xmltv_data_version")
        const val EPG_DATA_VERSION = 6
        val DEFAULT_THIRD_PARTY_EPG_URL: String = EpgSourceDefaults.DEFAULT_URL_BLOCK
        private const val FAVORITES_CATEGORY_ID = "GUIDE_FAVORITES"
        private val FAVORITES_CATEGORY = XtreamCategory(FAVORITES_CATEGORY_ID, "★ Favorites", 0)
    }

    private data class LoadedGuideResult(
        val categories: List<XtreamCategory>,
        val selectedCategoryId: String,
        val channels: List<XtreamChannel>,
        val filteredChannels: List<XtreamChannel>,
        val focusedChannelIndex: Int
    )

    private val _uiState = MutableStateFlow(
        GuideUiState(
            selectedCategoryId = savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "ALL",
            focusedChannelIndex = (savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] ?: 0).coerceAtLeast(0),
            verticalListFirstVisibleIndex = (savedStateHandle[KEY_VERTICAL_LIST_INDEX] ?: 0).coerceAtLeast(0),
            verticalListFirstVisibleOffset = (savedStateHandle[KEY_VERTICAL_LIST_OFFSET] ?: 0).coerceAtLeast(0),
            horizontalScrollOffset = (savedStateHandle[KEY_HORIZONTAL_SCROLL_OFFSET] ?: 0).coerceAtLeast(0)
        )
    )
    val uiState: StateFlow<GuideUiState> = _uiState.asStateFlow()

    private var epgLoadJob: Job? = null

    // ── D-pad channel navigation ──────────────────────────────────────────────

    fun setFocusedChannel(index: Int) {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) {
            _uiState.value = state.copy(focusedChannelIndex = 0)
            persistGuideSelection(focusedIndex = 0)
            return
        }
        val bounded = index.coerceIn(0, state.filteredChannels.lastIndex)
        _uiState.value = state.copy(focusedChannelIndex = bounded)
        persistGuideSelection(focusedIndex = bounded)
    }

    fun focusChannelByStreamId(streamId: Int) {
        val state = _uiState.value
        val idx = state.filteredChannels.indexOfFirst { it.streamId == streamId }
        if (idx >= 0) {
            _uiState.value = state.copy(focusedChannelIndex = idx)
            persistGuideSelection(focusedIndex = idx)
        }
    }

    fun zapUp() {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val newIndex = if (state.focusedChannelIndex <= 0)
            0 else state.focusedChannelIndex - 1
        _uiState.value = state.copy(focusedChannelIndex = newIndex)
        persistGuideSelection(focusedIndex = newIndex)
        onVisibleChannelsChanged(newIndex, (newIndex + 15).coerceAtMost(state.filteredChannels.size - 1))
    }

    fun zapDown() {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val newIndex = if (state.focusedChannelIndex >= state.filteredChannels.size - 1)
            state.filteredChannels.size - 1 else state.focusedChannelIndex + 1
        _uiState.value = state.copy(focusedChannelIndex = newIndex)
        persistGuideSelection(focusedIndex = newIndex)
        onVisibleChannelsChanged(newIndex, (newIndex + 15).coerceAtMost(state.filteredChannels.size - 1))
    }

    fun setActiveChannel(index: Int) {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val bounded = index.coerceIn(0, state.filteredChannels.lastIndex)
        xtreamRepository.liveChannelList = state.filteredChannels
        xtreamRepository.liveChannelIndex = bounded
        _uiState.value = state.copy(focusedChannelIndex = bounded)
        persistGuideSelection(focusedIndex = bounded)
    }

    fun setActiveChannelByStreamId(streamId: Int) {
        val state = _uiState.value
        val idx = state.filteredChannels.indexOfFirst { it.streamId == streamId }
        if (idx >= 0) {
            setActiveChannel(idx)
        }
    }

    /** Keep Guide focus aligned with channels selected/zapped from PlayerScreen. */
    fun syncFocusedChannelFromPlayback() {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val activeStreamId = xtreamRepository
            .liveChannelList
            .getOrNull(xtreamRepository.liveChannelIndex)
            ?.streamId
            ?: return
        val idx = state.filteredChannels.indexOfFirst { it.streamId == activeStreamId }
        if (idx >= 0 && idx != state.focusedChannelIndex) {
            _uiState.value = state.copy(focusedChannelIndex = idx)
            persistGuideSelection(focusedIndex = idx)
        }
    }

    fun onGuideVerticalScrollChanged(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val state = _uiState.value
        if (state.verticalListFirstVisibleIndex == firstVisibleItemIndex &&
            state.verticalListFirstVisibleOffset == firstVisibleItemScrollOffset
        ) return
        _uiState.value = state.copy(
            verticalListFirstVisibleIndex = firstVisibleItemIndex,
            verticalListFirstVisibleOffset = firstVisibleItemScrollOffset
        )
        savedStateHandle[KEY_VERTICAL_LIST_INDEX] = firstVisibleItemIndex
        savedStateHandle[KEY_VERTICAL_LIST_OFFSET] = firstVisibleItemScrollOffset
    }

    fun onGuideHorizontalScrollChanged(scrollOffset: Int) {
        val state = _uiState.value
        if (state.horizontalScrollOffset == scrollOffset) return
        _uiState.value = state.copy(horizontalScrollOffset = scrollOffset)
        savedStateHandle[KEY_HORIZONTAL_SCROLL_OFFSET] = scrollOffset
    }

    /** streamId_startSec_durationMin token for Screen.Player route. */
    fun buildTimeShiftToken(streamId: Int, startMs: Long, endMs: Long): String {
        val safeStartSec = (startMs / 1000L).coerceAtLeast(0L)
        val durationMin = ((endMs - startMs) / 60_000L).coerceAtLeast(1L).toInt()
        return "${streamId}_${safeStartSec}_${durationMin}"
    }

    fun setPendingPlaybackTitle(title: String) {
        xtreamRepository.pendingStreamTitle = title
    }

    /** Called by the Guide composable when the user scrolls. Loads EPG only for visible range. */
    fun onVisibleChannelsChanged(firstVisible: Int, lastVisible: Int) {
        val state = _uiState.value
        val channels = state.filteredChannels
        if (channels.isEmpty()) return
        // Expand viewport by 8 rows above and below for smooth pre-load
        val from = (firstVisible - 8).coerceAtLeast(0)
        val to   = (lastVisible  + 8).coerceAtMost(channels.size - 1)
        val viewportChannels = channels.subList(from, to + 1)
        // Only load EPG for channels we don't already have data for
        val missing = viewportChannels.filter { it.streamId !in state.epgData }
        if (missing.isEmpty()) return
        epgLoadJob?.cancel()
        epgLoadJob = viewModelScope.launch { loadEpg(missing) }
    }

    init {
        viewModelScope.launch {
            val prefs = context.dataStore.data.first()
            val hoursToShow = prefs[SettingsViewModel.KEY_GUIDE_HOURS] ?: 4
            val rowHeightMode = prefs[SettingsViewModel.KEY_GUIDE_ROW_HEIGHT] ?: "Normal"
            val epgPreload = prefs[SettingsViewModel.KEY_GUIDE_EPG_PRELOAD] ?: 80
            val showChNumbers = prefs[SettingsViewModel.KEY_GUIDE_SHOW_CH_NUM] ?: true
            val autoScroll = prefs[SettingsViewModel.KEY_GUIDE_AUTO_SCROLL] ?: true

            val now = System.currentTimeMillis()
            val hourStart = localHourFloorMs(now)
            _uiState.value = _uiState.value.copy(
                windowStartMs = hourStart,
                windowEndMs   = hourStart + hoursToShow * 3_600_000L,
                hoursToShow   = hoursToShow,
                rowHeightMode = rowHeightMode,
                epgPreloadRows = epgPreload,
                showChannelNumbers = showChNumbers,
                autoScrollToNow = autoScroll
            )
        }
        viewModelScope.launch {
            favoriteDao.getAllFavorites()
                .map { favorites ->
                    favorites.asSequence()
                        .filter { it.streamType == "live" }
                        .map { it.streamId }
                        .toSet()
                }
                .collect { favoriteIds ->
                    val state = _uiState.value
                    val filtered = filterChannelsForCategory(
                        state.channels,
                        state.selectedCategoryId,
                        favoriteIds
                    )
                    _uiState.value = state.copy(
                        favoriteChannelIds = favoriteIds,
                        filteredChannels = filtered,
                        focusedChannelIndex = if (filtered.isEmpty()) 0
                            else state.focusedChannelIndex.coerceIn(0, filtered.lastIndex)
                    )
                }
        }
    }

    /** Only loads if no channels are cached in memory. Preserves selected category on re-entry. */
    fun loadIfNeeded() {
        if (_uiState.value.channels.isNotEmpty() && !_uiState.value.isLoading) return
        load()
    }

    fun load() {
        if (!xtreamRepository.isConnected) {
            _uiState.value = _uiState.value.copy(
                error = "Not connected. Configure credentials in Settings."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val loaded = withTimeout(LOAD_TIMEOUT_MS) {
                    val liveCategories = xtreamRepository.getLiveCategories().getOrDefault(emptyList())
                    val liveChannels = xtreamRepository.getLiveStreams().getOrDefault(emptyList())

                    withContext(Dispatchers.Default) {
                        val categories = contentFilterRepository.filterCategories(liveCategories)
                        val allCat = XtreamCategory("ALL", "All Channels", 0)
                        val catList = listOf(allCat, FAVORITES_CATEGORY) + sortCategories(categories)

                        val channels = contentFilterRepository.filterChannels(liveChannels)
                        val favoriteIds = _uiState.value.favoriteChannelIds

                        // Preserve the user’s selected category when doing a background refresh.
                        // Only reset to ALL on the very first load (no channels loaded yet).
                        val wasLoaded = _uiState.value.channels.isNotEmpty()
                        val persistedCategoryId = normalizeCategoryId(savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "")
                        val requestedCat = when {
                            persistedCategoryId.isNotBlank() -> persistedCategoryId
                            wasLoaded -> _uiState.value.selectedCategoryId
                            else -> "ALL"
                        }
                        val normalizedRequested = normalizeCategoryId(requestedCat)
                        val keepCatId = when {
                            normalizedRequested.isBlank() -> "ALL"
                            normalizedRequested == "ALL" -> "ALL"
                            normalizedRequested == FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID
                            catList.any { normalizeCategoryId(it.categoryId) == normalizedRequested } -> normalizedRequested
                            else -> "ALL"
                        }
                        val filtered = filterChannelsForCategory(channels, keepCatId, favoriteIds)

                        val boundedFocusedIndex = if (filtered.isEmpty()) 0
                            else (savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] ?: _uiState.value.focusedChannelIndex)
                                .coerceIn(0, filtered.lastIndex)

                        LoadedGuideResult(
                            categories = catList,
                            selectedCategoryId = keepCatId,
                            channels = channels,
                            filteredChannels = filtered,
                            focusedChannelIndex = boundedFocusedIndex
                        )
                    }
                }

                _uiState.value = _uiState.value.copy(
                    categories = loaded.categories,
                    selectedCategoryId = loaded.selectedCategoryId,
                    channels = loaded.channels,
                    filteredChannels = loaded.filteredChannels,
                    focusedChannelIndex = loaded.focusedChannelIndex,
                    isLoading = false
                )
                persistGuideSelection(
                    categoryId = loaded.selectedCategoryId,
                    focusedIndex = loaded.focusedChannelIndex
                )

                // Pre-warm EPG for the first configurable batch (default 30 rows).
                val preload = _uiState.value.epgPreloadRows.coerceAtLeast(20)
                loadEpg(loaded.filteredChannels.take(preload))

                // Kick off XMLTV background refresh (fills Room for 7-day data)
                viewModelScope.launch(Dispatchers.IO) { refreshXmltvIfNeeded() }

            } catch (e: TimeoutCancellationException) {
                Timber.w(e, "Guide load timed out")
                _uiState.value = _uiState.value.copy(
                    error = "Guide took too long to load. Please retry.",
                    isLoading = false
                )

            } catch (e: Exception) {
                Timber.e(e, "Guide load failed")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false
                )
            }
        }
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        val requestedCategoryId = normalizeCategoryId(categoryId)
        val activeCategoryId = when {
            requestedCategoryId.isBlank() -> "ALL"
            requestedCategoryId == "ALL" -> "ALL"
            requestedCategoryId == FAVORITES_CATEGORY_ID -> FAVORITES_CATEGORY_ID
            state.categories.any { normalizeCategoryId(it.categoryId) == requestedCategoryId } -> requestedCategoryId
            else -> "ALL"
        }
        val filtered = filterChannelsForCategory(state.channels, activeCategoryId, state.favoriteChannelIds)
        _uiState.value = state.copy(
            selectedCategoryId = activeCategoryId,
            filteredChannels = filtered,
            focusedChannelIndex = 0,
            epgData = emptyMap()  // clear so viewport reload will fetch fresh data
        )
        persistGuideSelection(categoryId = activeCategoryId, focusedIndex = 0)
        epgLoadJob?.cancel()
        val preload = state.epgPreloadRows.coerceAtLeast(20)
        epgLoadJob = viewModelScope.launch { loadEpg(filtered.take(preload)) }
    }

    fun toggleFavoriteChannel(channel: XtreamChannel) {
        viewModelScope.launch {
            if (channel.streamId in _uiState.value.favoriteChannelIds) {
                favoriteDao.deleteByStreamId(channel.streamId, "live")
            } else {
                favoriteDao.insert(FavoriteEntity(channel.streamId, "live", System.currentTimeMillis()))
            }
        }
    }

    /** Shift guide window forward by N hours (based on hoursToShow). */
    fun shiftForward() {
        val state = _uiState.value
        val shift = (state.hoursToShow / 2).coerceAtLeast(2).toLong() * 3_600_000L
        val newStart = state.windowStartMs + shift
        _uiState.value = state.copy(
            windowStartMs = newStart,
            windowEndMs   = newStart + state.hoursToShow * 3_600_000L
        )
    }

    /** Shift guide window back (not before current hour). */
    fun shiftBack() {
        val now = System.currentTimeMillis()
        val hourStart = localHourFloorMs(now)
        val state = _uiState.value
        val shift = (state.hoursToShow / 2).coerceAtLeast(2).toLong() * 3_600_000L
        val newStart = maxOf(hourStart, state.windowStartMs - shift)
        _uiState.value = state.copy(
            windowStartMs = newStart,
            windowEndMs   = newStart + state.hoursToShow * 3_600_000L
        )
    }

    /** Jump directly to NOW in the time window. */
    fun jumpToNow() {
        val state = _uiState.value
        val now = System.currentTimeMillis()
        val hourStart = localHourFloorMs(now)
        _uiState.value = state.copy(
            windowStartMs = hourStart,
            windowEndMs   = hourStart + state.hoursToShow * 3_600_000L
        )
    }

    private suspend fun loadEpg(channels: List<XtreamChannel>) = withContext(Dispatchers.IO) {
        if (channels.isEmpty()) return@withContext
        try {
            val state       = _uiState.value
            val windowStart = state.windowStartMs
            val windowEnd   = state.windowEndMs

            // ── Try Room XMLTV data first (7-day, zero extra HTTP requests) ─────
            val roomMap     = mutableMapOf<Int, List<XtreamEpgProgram>>()

            // Pass 1 + 2: exact epg_channel_id, then light id variants.
            val unmatched = mutableListOf<XtreamChannel>()
            channels.forEach { channel ->
                val epgId = channel.epgChannelId
                if (!epgId.isNullOrBlank()) {
                    // Alias map first (display-name / normalised keys → canonical id)
                    val canonical = resolveEpgChannelId(epgId)
                    var roomPrograms = epgProgramDao.getProgramsForWindow(
                        canonical, windowStart, windowEnd
                    )

                    // Fuzzy fallback: try lowercase, try without domain suffix, try with .us suffix
                    if (roomPrograms.isEmpty()) {
                        val fuzzyIds = buildFuzzyEpgIds(epgId)
                        for (fuzzyId in fuzzyIds) {
                            val resolved = resolveEpgChannelId(fuzzyId)
                            roomPrograms = epgProgramDao.getProgramsForWindow(
                                resolved, windowStart, windowEnd
                            )
                            if (roomPrograms.isNotEmpty()) break
                        }
                    }

                    if (roomPrograms.isNotEmpty()) {
                        // v5.0 time fix: Room XMLTV rows are absolute-correct from
                        // XmltvParser — never run alignEpgListing over them (its old
                        // hour-snapping shifted correct grids by 4–9 h at schedule gaps).
                        roomMap[channel.streamId] = roomPrograms.map { it.toXtreamEpgProgram() }
                        return@forEach
                    }
                }
                unmatched.add(channel)
            }

            // Pass 3 + 4 (Module 3.1): sanitised exact + Levenshtein fuzzy name match.
            // Build the sanitised id index once, only when something actually went unmatched
            // and the EPG table has data to match against — keeps the hot path allocation-free.
            if (unmatched.isNotEmpty()) {
                val distinctIds = epgProgramDao.getDistinctChannelIds()
                if (distinctIds.isNotEmpty()) {
                    val index = EpgChannelMatcher.SanitizedIndex(distinctIds)
                    if (!index.isEmpty) {
                        val iterator = unmatched.iterator()
                        while (iterator.hasNext()) {
                            val channel = iterator.next()
                            // The epg_channel_id is often name-derived, so try it first, then
                            // fall back to the human display name.
                            val matchId = channel.epgChannelId
                                ?.takeIf { it.isNotBlank() }
                                ?.let { index.exact(it) ?: index.closest(it) }
                                ?: index.exact(channel.name)
                                ?: index.closest(channel.name)
                            if (matchId != null) {
                                val programs = epgProgramDao.getProgramsForWindow(matchId, windowStart, windowEnd)
                            if (programs.isNotEmpty()) {
                                roomMap[channel.streamId] = programs.map { it.toXtreamEpgProgram() }
                                iterator.remove()
                            }
                            }
                        }
                    }
                }
            }

            // The provider's short-EPG endpoint is keyed by the actual stream ID,
            // not a fuzzy XMLTV channel name. Prefer it whenever it has data: it
            // corrects wrong-name XMLTV matches while Room XMLTV still fills the
            // longer future timeline for channels the provider does not return.
            val providerIds = channels.map { it.streamId }.distinct()
            val apiMap = if (providerIds.isNotEmpty()) {
                val limit = (state.hoursToShow * 4).coerceIn(16, 32)
                xtreamRepository.batchShortEpg(providerIds, limit = limit)
            } else emptyMap()

            // short_epg is keyed by the exact stream ID, unlike fuzzy XMLTV channel
            // matching. It wins for overlapping slots; XMLTV keeps the later future.
            val combined = roomMap.toMutableMap().apply {
                apiMap.forEach { (streamId, providerPrograms) ->
                    this[streamId] = mergeProviderAndXmltv(providerPrograms, this[streamId].orEmpty())
                }
            }
            val current  = _uiState.value
            val mergedEpg = current.epgData + combined
            val matchedInBatch = channels.count { ch ->
                mergedEpg[ch.streamId].orEmpty().isNotEmpty()
            }
            _uiState.value = current.copy(
                epgData = mergedEpg,
                epgQueriedChannels = channels.size,
                epgMatchedChannels = matchedInBatch
            )
        } catch (e: Exception) {
            Timber.w("Guide EPG load failed: ${e.message}")
        }
    }

    /**
     * Resolve an Xtream / display-name id to the canonical XMLTV channel id used
     * as [EpgProgramEntity.channelId]. Falls back to the input when no alias exists.
     */
    private suspend fun resolveEpgChannelId(rawId: String): String {
        if (rawId.isBlank()) return rawId
        epgChannelAliasDao.resolve(rawId)?.let { return it }
        epgChannelAliasDao.resolve(rawId.lowercase(Locale.US))?.let { return it }
        return rawId
    }

    /**
     * Build fuzzy fallback EPG channel ID candidates for matching against XMLTV data.
     * Handles common mismatches: "CNN" vs "CNN.us", "BBC ONE" vs "bbcone.uk", etc.
     */
    private fun buildFuzzyEpgIds(epgId: String): List<String> {
        val lower = epgId.lowercase()
        val noSuffix = lower.substringBefore(".").substringBefore(" ")
        return listOf(
            lower,
            noSuffix,
            "$noSuffix.us",
            "$noSuffix.uk",
            epgId.replace(" ", "").lowercase(),
            epgId.replace(" ", "_").lowercase()
        ).distinct()
    }

    private fun mergeProviderAndXmltv(
        providerPrograms: List<XtreamEpgProgram>,
        xmltvPrograms: List<XtreamEpgProgram>
    ): List<XtreamEpgProgram> {
        if (providerPrograms.isEmpty()) return xmltvPrograms
        if (xmltvPrograms.isEmpty()) return providerPrograms.sortedBy { it.startTimestamp }
        val provider = providerPrograms.sortedBy { it.startTimestamp }
        return (provider + xmltvPrograms.filter { xmltv ->
            provider.none { api ->
                xmltv.startTimestamp < api.stopTimestamp && xmltv.stopTimestamp > api.startTimestamp
            }
        }).sortedBy { it.startTimestamp }
    }

    /**
     * Fetch full XMLTV EPG using a time-based freshness check (4-hour TTL).
     * Falls back to short EPG API for channels without Room data.
     * Runs entirely on IO dispatcher. Called after initial channel load.
     */
    private suspend fun refreshXmltvIfNeeded() {
        // Ensure provider timezone is known before parsing offset-less XMLTV.
        xtreamRepository.refreshProviderTimezone()
        val now = System.currentTimeMillis()

        // Prune programs that ended >1 hour ago to keep DB lean
        epgProgramDao.deleteOlderThan(now - 3_600_000L)

        // Time-based freshness: skip if we already have a large amount of fresh data
        // "fresh" = more than 1000 programs AND the oldest future program was inserted recently
        val prefs = context.dataStore.data.first()
        val programCount = epgProgramDao.count()
        val lastFetch = prefs[KEY_XMLTV_LAST_FETCH_MS] ?: 0L
        val fetchIsStale = (now - lastFetch) > 2 * 3_600_000L
        // v5.0: bump EPG_DATA_VERSION when parser/TZ semantics change — stored rows
        // from older builds are force-refetched once (see companion object).
        val versionBump = (prefs[KEY_XMLTV_DATA_VERSION] ?: 0) < EPG_DATA_VERSION
        // After DB wipe / EPG time-fix migration, Room is empty — always re-fetch.
        val mustRefetch = programCount < 100 || versionBump

        if (programCount > 1000 && !fetchIsStale && !mustRefetch) {
            Timber.d("XMLTV EPG: Room has $programCount fresh programs, skipping fetch (lastFetch=${lastFetch})")
            return
        }
        Timber.d("XMLTV EPG: fetching… (count=$programCount, stale=$fetchIsStale, mustRefetch=$mustRefetch, versionBump=$versionBump)")
        val acceptedEpgIds = _uiState.value.channels
            .mapNotNull { it.epgChannelId?.takeIf(String::isNotBlank) }
            .toSet()
        var storedPrograms = 0
        var providerProgramCount = 0
        var externalProgramCount = 0
        var clearedCurrentGuide = false

        suspend fun storeFreshPrograms(parsed: XmltvParser.ParseResult) {
            if (parsed.programs.isEmpty() && parsed.aliasToCanonical.isEmpty()) return
            if (!clearedCurrentGuide) {
                epgProgramDao.deleteAll()
                epgChannelAliasDao.deleteAll()
                clearedCurrentGuide = true
            }
            if (parsed.programs.isNotEmpty()) {
                epgProgramDao.insertAll(parsed.programs)
                storedPrograms += parsed.programs.size
            }
            if (parsed.aliasToCanonical.isNotEmpty()) {
                val aliasRows = parsed.aliasToCanonical.map { (alias, canonical) ->
                    EpgChannelAliasEntity(alias = alias, canonicalChannelId = canonical)
                }
                epgChannelAliasDao.insertAll(aliasRows)
            }
        }

        val timeOffset = prefs[androidx.datastore.preferences.core.intPreferencesKey("epg_time_offset_hours")] ?: 0
        val providerParsed = xtreamRepository.fetchXmltvEpg(acceptedEpgIds, timeOffset)
        providerProgramCount = providerParsed.programs.size
        storeFreshPrograms(providerParsed)

        val thirdPartyEnabled = prefs[KEY_EPG_THIRD_PARTY_ENABLED] ?: true
        if (thirdPartyEnabled) {
            var remainingExternalBudget = 260_000
            val thirdPartyUrls = EpgSourceDefaults.parseUrlBlock(prefs[KEY_EPG_THIRD_PARTY_URL])
            for (url in thirdPartyUrls) {
                if (remainingExternalBudget <= 0) break
                val limit = minOf(EpgSourceDefaults.maxProgramsFor(url), remainingExternalBudget)
                val externalParsed = xtreamRepository.fetchExternalXmltvEpg(url, maxPrograms = limit)
                externalProgramCount += externalParsed.programs.size
                remainingExternalBudget -= externalParsed.programs.size
                storeFreshPrograms(externalParsed)
                kotlinx.coroutines.yield()
            }
        }

        if (storedPrograms > 0) {
            // Record fetch timestamp + EPG data version in DataStore so the freshness
            // check works across restarts and stale-timezone rows self-heal once.
            context.dataStore.edit { prefs ->
                prefs[KEY_XMLTV_LAST_FETCH_MS] = System.currentTimeMillis()
                prefs[KEY_XMLTV_DATA_VERSION] = EPG_DATA_VERSION
            }
            Timber.i(
                "XMLTV EPG stored: $storedPrograms programs " +
                    "(provider=$providerProgramCount, external=$externalProgramCount)"
            )
            // Reload EPG for all visible channels with fresh Room data
            val state   = _uiState.value
            val preload = state.epgPreloadRows.coerceIn(40, 250)
            // Clear cached EPG data so fresh data is displayed
            _uiState.value = state.copy(epgData = emptyMap())
            loadEpg(state.filteredChannels.take(preload))
        } else {
            // XMLTV failed (large file timeout, server issue) — fall back to short EPG API
            // for a bounded set of channels to avoid huge burst loads on low-RAM Firestick devices.
            Timber.w("XMLTV EPG: fetch returned 0 programs, falling back to bounded batchShortEpg")
            val state = _uiState.value
            val maxFallbackChannels = state.epgPreloadRows
                .coerceAtLeast(80)
                .coerceAtMost(250)
            val allIds = state.filteredChannels
                .take(maxFallbackChannels)
                .map { it.streamId }
            val epgMap = xtreamRepository.batchShortEpg(allIds, limit = 8)
            val current = _uiState.value
            _uiState.value = current.copy(epgData = current.epgData + epgMap)
        }
    }

    /**
     * Force a full XMLTV EPG re-fetch (e.g. called from Guide toolbar refresh button).
     * Clears Room first so refreshXmltvIfNeeded() will always re-fetch.
     */
    fun refreshXmltvEpg() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                epgProgramDao.deleteOlderThan(Long.MAX_VALUE) // clear all
                epgChannelAliasDao.deleteAll()
                // Reset the freshness timestamp so the fetch is always triggered
                context.dataStore.edit { prefs -> prefs[KEY_XMLTV_LAST_FETCH_MS] = 0L }
                refreshXmltvIfNeeded()
            } catch (e: Exception) {
                Timber.e(e, "XMLTV EPG refresh failed")
            }
        }
    }

    private fun persistGuideSelection(
        categoryId: String = _uiState.value.selectedCategoryId,
        focusedIndex: Int = _uiState.value.focusedChannelIndex
    ) {
        savedStateHandle[KEY_SELECTED_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] = focusedIndex
    }
}

/** Floor [epochMs] to the start of the current local hour (device timezone). */
private fun localHourFloorMs(epochMs: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = epochMs
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

// ── Extension: EpgProgramEntity → XtreamEpgProgram (compatible format for UI) ──

private fun EpgProgramEntity.toXtreamEpgProgram() = XtreamEpgProgram(
    id             = this.id.toString(),
    epgId          = this.channelId,
    title          = this.title,
    lang           = null,
    start          = "",
    end            = "",
    description    = this.description,
    channelId      = this.channelId,
    startTimestamp = this.startTime / 1000L,
    stopTimestamp  = this.endTime  / 1000L,
    nowPlaying     = 0,
    hasArchive     = 0
)

data class GuideUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String = "ALL",
    val channels: List<XtreamChannel> = emptyList(),
    val filteredChannels: List<XtreamChannel> = emptyList(),
    val epgData: Map<Int, List<XtreamEpgProgram>> = emptyMap(),
    val favoriteChannelIds: Set<Int> = emptySet(),
    val windowStartMs: Long = 0L,
    val windowEndMs: Long = 0L,
    /** Currently D-pad-focused channel row index (0-based into filteredChannels). */
    val focusedChannelIndex: Int = 0,
    val verticalListFirstVisibleIndex: Int = 0,
    val verticalListFirstVisibleOffset: Int = 0,
    val horizontalScrollOffset: Int = 0,
    // Settings-driven display config
    val hoursToShow: Int = 4,
    val rowHeightMode: String = "Normal",        // "Compact" | "Normal" | "Large"
    val epgPreloadRows: Int = 80,
    val showChannelNumbers: Boolean = true,
    val autoScrollToNow: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Low-cost match diagnostics for Guide polish. */
    val epgMatchedChannels: Int = 0,
    val epgQueriedChannels: Int = 0
)
