package com.dylandos.iptv.ultimate.ui.screens.livetv

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.ChannelDao
import com.dylandos.iptv.ultimate.data.db.dao.EpgChannelAliasDao
import com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
import com.dylandos.iptv.ultimate.data.db.dao.FavoriteDao
import com.dylandos.iptv.ultimate.data.db.entity.ChannelEntity
import com.dylandos.iptv.ultimate.data.db.entity.EpgChannelAliasEntity
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import com.dylandos.iptv.ultimate.data.db.entity.FavoriteEntity
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamChannel
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.network.CategoryContentType
import com.dylandos.iptv.ultimate.data.network.EpgSourceDefaults
import com.dylandos.iptv.ultimate.data.network.XmltvParser
import com.dylandos.iptv.ultimate.data.util.sortCategories
import com.dylandos.iptv.ultimate.ui.screens.settings.dataStore
import com.dylandos.iptv.ultimate.ui.screens.guide.GuideViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * DYLANDOS IPTV ULTIMATE — Live TV ViewModel
 *
 * Loads categories, channels, and short EPG (now/next) for each channel.
 * Room DB persistence: channels cached to disk so app starts instantly even offline.
 * Background refresh: if cache is older than ROOM_CACHE_TTL_MS the network is hit
 *   while Room data is shown immediately — a subtle "Refreshing…" badge is shown.
 */
@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository,
    private val channelDao: ChannelDao,
    private val epgProgramDao: EpgProgramDao,
    private val epgChannelAliasDao: EpgChannelAliasDao,
    private val favoriteDao: FavoriteDao,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val KEY_SELECTED_CATEGORY_ID = "live_selected_category_id"
        private const val KEY_FOCUSED_CHANNEL_INDEX = "live_focused_channel_index"
        private const val KEY_CHANNEL_LIST_INDEX = "live_channel_list_index"
        private const val KEY_CHANNEL_LIST_OFFSET = "live_channel_list_offset"
        private const val KEY_CATEGORY_LIST_INDEX = "live_category_list_index"
        private const val KEY_CATEGORY_LIST_OFFSET = "live_category_list_offset"
        const val MY_CHANNELS_CATEGORY_ID = "MY_CHANNELS"
        private val MY_CHANNELS_CATEGORY = XtreamCategory(
            MY_CHANNELS_CATEGORY_ID,
            "★ Favorites",
            0
        )
    }

    private fun normalizeCategoryId(raw: String?): String = raw?.trim().orEmpty()

    private fun categoryMatches(channelCategoryId: String?, selectedCategoryId: String): Boolean {
        return normalizeCategoryId(channelCategoryId) == normalizeCategoryId(selectedCategoryId)
    }

    private val KEY_US_FIRST_LIVE = booleanPreferencesKey("us_en_first_live")

    /** 30-minute Room cache TTL — short enough to stay fresh, long enough to skip network on re-entry */
    private val ROOM_CACHE_TTL_MS = 30 * 60 * 1_000L

    private val _uiState = MutableStateFlow(
        LiveTvUiState(
            selectedCategoryId = savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "ALL",
            focusedChannelIndex = (savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] ?: 0).coerceAtLeast(0),
            channelListFirstVisibleIndex = (savedStateHandle[KEY_CHANNEL_LIST_INDEX] ?: 0).coerceAtLeast(0),
            channelListFirstVisibleOffset = (savedStateHandle[KEY_CHANNEL_LIST_OFFSET] ?: 0).coerceAtLeast(0),
            categoryListFirstVisibleIndex = (savedStateHandle[KEY_CATEGORY_LIST_INDEX] ?: 0).coerceAtLeast(0),
            categoryListFirstVisibleOffset = (savedStateHandle[KEY_CATEGORY_LIST_OFFSET] ?: 0).coerceAtLeast(0)
        )
    )
    val uiState: StateFlow<LiveTvUiState> = _uiState.asStateFlow()

    // ── Category-name resolution via combined flows ───────────────────────────
    // _rawChannels: updated each time channels are loaded (Room or network)
    // _serverCats:  updated each time category names are fetched from the server
    // The combine collector rebuilds _uiState.categories whenever either changes,
    // guaranteeing real display names — never raw numeric IDs — on every entry.
    private val _rawChannels = MutableStateFlow<List<XtreamChannel>>(emptyList())
    private val _serverCats  = MutableStateFlow<List<XtreamCategory>>(emptyList())
    private var xmltvRefreshJob: Job? = null
    private var backgroundEpgJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                _rawChannels,
                _serverCats,
                xtreamRepository.observeHiddenCategoryIds(CategoryContentType.LIVE)
            ) { ch, cats, hiddenIds -> Triple(ch, cats, hiddenIds) }
                .collect { (channels, serverCats, hiddenIds) ->
                    if (channels.isEmpty()) return@collect
                    val prefs     = context.dataStore.data.first()
                    val usFirst   = prefs[KEY_US_FIRST_LIVE] ?: true
                    val updated   = buildCategoryList(channels, hiddenIds, usFirst, serverCats)
                    _uiState.update { state ->
                        val requestedCategory = normalizeCategoryId(state.selectedCategoryId)
                        val activeCategory = when {
                            requestedCategory.isBlank() -> "ALL"
                            requestedCategory == "ALL" -> "ALL"
                            requestedCategory == MY_CHANNELS_CATEGORY_ID -> MY_CHANNELS_CATEGORY_ID
                            updated.any { normalizeCategoryId(it.categoryId) == requestedCategory } -> requestedCategory
                            else -> "ALL"
                        }
                        val filtered = filterChannelsForCategory(
                            state.channels.ifEmpty { channels },
                            activeCategory,
                            state.favoriteChannelIds
                        )
                        state.copy(
                            categories = updated,
                            selectedCategoryId = activeCategory,
                            filteredChannels = filtered,
                            focusedChannelIndex = if (filtered.isEmpty()) 0
                                else state.focusedChannelIndex.coerceIn(0, filtered.lastIndex)
                        )
                    }
                }
        }
        viewModelScope.launch {
            favoriteDao.getAllFavorites()
                .map { favorites ->
                    favorites.asSequence()
                        .filter { it.streamType == "live" }
                        .map { it.streamId }
                        .toSet()
                }
                .distinctUntilChanged()
                .collect { favoriteIds ->
                    val state = _uiState.value
                    val filtered = if (state.selectedCategoryId == MY_CHANNELS_CATEGORY_ID) {
                        state.channels.filter { it.streamId in favoriteIds }
                    } else {
                        state.filteredChannels
                    }
                    _uiState.value = state.copy(
                        favoriteChannelIds = favoriteIds,
                        filteredChannels = filtered
                    )
                }
        }
    }

    private var epgJob: Job? = null
    private var viewportEpgJob: Job? = null

    // 220ms zap throttle — identical to PlayerViewModel.zapDebounceMs.
    // Without this, holding D-pad fires ~20 zaps/second, each updating StateFlow + calling
    // animateScrollToItem(), which queues coroutines faster than they can cancel each other.
    private var zapJob: Job? = null
    private val zapDebounceMs = 220L

    fun loadChannels(forceRefresh: Boolean = false) {
        // Skip if already loaded and data is fresh (in-memory cache + ViewModel not cleared)
        if (!forceRefresh && _uiState.value.channels.isNotEmpty() && !_uiState.value.isLoading) return
        if (!xtreamRepository.isConnected) {
            _uiState.value = _uiState.value.copy(
                error = "Not connected. Configure Xtream credentials in Settings."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val prefs = context.dataStore.data.first()
            val hiddenIds = xtreamRepository.observeHiddenCategoryIds(CategoryContentType.LIVE).first()
            val usFirst   = prefs[KEY_US_FIRST_LIVE] ?: true

            // Track whether we already have data (re-entry vs first load)
            val wasLoaded = _uiState.value.channels.isNotEmpty()
            val keepCatId = _uiState.value.selectedCategoryId
            val persistedCategoryId = normalizeCategoryId(savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: "")
            val persistedFocusedIndex = (savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] ?: 0).coerceAtLeast(0)

            // ── Step 1: show Room cached channels instantly ─────────────────────
            val lastFetchedAt = channelDao.getLastFetchedAt()
            val cachedRows    = channelDao.getAllChannelsOnce()
            val cacheIsStale  = System.currentTimeMillis() - lastFetchedAt > ROOM_CACHE_TTL_MS

            if (cachedRows.isNotEmpty()) {
                val cachedChannels = contentFilterRepository.filterChannels(cachedRows.map { it.toModel() })
                // Use _serverCats (populated on previous visits) for immediate correct names.
                // On first load _serverCats is empty -> raw IDs briefly -> corrected below.
                val categories = buildCategoryList(cachedChannels, hiddenIds, usFirst, _serverCats.value)
                // Preserve selected category if re-entering screen; only reset on very first load
                val requestedCat = when {
                    persistedCategoryId.isNotBlank() -> persistedCategoryId
                    wasLoaded -> normalizeCategoryId(keepCatId)
                    else -> "ALL"
                }
                val catId = when {
                    requestedCat.isBlank() -> "ALL"
                    requestedCat == "ALL" -> "ALL"
                    categories.any { normalizeCategoryId(it.categoryId) == requestedCat } -> requestedCat
                    else -> "ALL"
                }
                val filtered = filterChannelsForCategory(
                    cachedChannels,
                    catId,
                    _uiState.value.favoriteChannelIds
                )
                val boundedFocused = if (filtered.isEmpty()) 0
                    else {
                        val requestedIndex = if (wasLoaded) _uiState.value.focusedChannelIndex else persistedFocusedIndex
                        requestedIndex.coerceIn(0, filtered.lastIndex)
                    }
                _uiState.value = _uiState.value.copy(
                    categories = categories,
                    selectedCategoryId = catId,
                    channels = cachedChannels,
                    filteredChannels = filtered,
                    focusedChannelIndex = boundedFocused,
                    activeStreamId = currentActiveStreamId(),
                    isLoading = false,
                    isRefreshing = cacheIsStale
                )
                persistLiveSelection(categoryId = catId, focusedIndex = boundedFocused)
                // Emit to combine so the collector applies correct names once _serverCats arrives.
                _rawChannels.value = cachedChannels
                // Cap initial EPG at 40 channels — onVisibleChannelsChanged handles the rest
                beginEpgCoverage(filtered)
                refreshXmltvInBackground(cachedChannels)
            }

            // Always fetch category names — lightweight call, ensures combine has real names
            // even when the channel cache is fresh. _serverCats is reused on re-entry.
            try {
                val serverCats = contentFilterRepository
                    .filterCategories(xtreamRepository.getLiveCategories().getOrDefault(emptyList()))
                if (serverCats.isNotEmpty()) {
                    _serverCats.value = serverCats  // triggers combine -> _uiState.categories updated
                }
            } catch (e: Exception) {
                Timber.w("Category name refresh failed: ${e.message}")
            }

            if (!cacheIsStale && cachedRows.isNotEmpty()) return@launch
            

            // ── Step 2: fetch from network (cold start or stale cache) ──────────
            try {
                val freshChannels = contentFilterRepository
                    .filterChannels(xtreamRepository.getLiveStreams().getOrDefault(emptyList()))
                if (freshChannels.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    channelDao.insertAll(freshChannels.map { it.toEntity(now) })

                    // Emit to combine - _serverCats already populated above, so combine
                    // fires immediately with correct category names.
                    _rawChannels.value = freshChannels
                    // Preserve user's current category when refreshing stale cache
                    val categories = buildCategoryList(freshChannels, hiddenIds, usFirst, _serverCats.value)
                    val requestedCat = normalizeCategoryId(savedStateHandle[KEY_SELECTED_CATEGORY_ID] ?: _uiState.value.selectedCategoryId)
                    val catId = when {
                        requestedCat.isBlank() -> "ALL"
                        requestedCat == "ALL" -> "ALL"
                        categories.any { normalizeCategoryId(it.categoryId) == requestedCat } -> requestedCat
                        else -> "ALL"
                    }
                    val filtered = filterChannelsForCategory(
                        freshChannels,
                        catId,
                        _uiState.value.favoriteChannelIds
                    )
                    val boundedFocused = if (filtered.isEmpty()) 0
                        else (savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] ?: _uiState.value.focusedChannelIndex)
                            .coerceIn(0, filtered.lastIndex)
                    _uiState.value = _uiState.value.copy(
                        categories = categories,
                        selectedCategoryId = catId,
                        channels = freshChannels,
                        filteredChannels = filtered,
                        focusedChannelIndex = boundedFocused,
                        activeStreamId = currentActiveStreamId(),
                        isLoading = false,
                        isRefreshing = false
                    )
                    persistLiveSelection(categoryId = catId, focusedIndex = boundedFocused)
                    epgJob?.cancel()
                    // Cap fresh-network EPG load to first 40 channels; viewport scroll handles rest
                    beginEpgCoverage(filtered)
                    refreshXmltvInBackground(freshChannels)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to fetch live channels from network")
                // Only show error if we had no cached data at all
                if (cachedRows.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        error = e.message ?: "Unknown error",
                        isLoading = false,
                        isRefreshing = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isRefreshing = false)
                }
            }
        }
    }

    private fun buildCategoryList(
        channels: List<XtreamChannel>,
        hiddenIds: Set<String>,
        usFirst: Boolean,
        serverCategories: List<XtreamCategory> = emptyList()
    ): List<XtreamCategory> {
        // Use server-provided category names when available; fall back to categoryId as name.
        // IMPORTANT: Do NOT filter out numeric names — many providers use numeric category names.
        val nameMap = serverCategories.associate { normalizeCategoryId(it.categoryId) to it.categoryName }
        val rawCategories = channels
            .mapNotNull { ch ->
                val id = normalizeCategoryId(ch.categoryId).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                // Use server name if non-blank, otherwise display the raw category ID
                val name = nameMap[id]?.takeIf { it.isNotBlank() } ?: id
                XtreamCategory(id, name, 0)
            }
            .distinctBy { normalizeCategoryId(it.categoryId) }
        val allCat = XtreamCategory("ALL", "All Channels", 0)
        return listOf(allCat, MY_CHANNELS_CATEGORY) +
            sortCategories(rawCategories, hiddenIds, usFirst)
    }

    private fun filterChannelsForCategory(
        channels: List<XtreamChannel>,
        categoryId: String,
        favoriteIds: Set<Int>
    ): List<XtreamChannel> = when (categoryId) {
        "ALL" -> channels
        MY_CHANNELS_CATEGORY_ID -> channels.filter { it.streamId in favoriteIds }
        else -> channels.filter { categoryMatches(it.categoryId, categoryId) }
    }

    fun selectCategory(categoryId: String) {
        val state = _uiState.value
        val requestedCategoryId = normalizeCategoryId(categoryId)
        val activeCategoryId = when {
            requestedCategoryId.isBlank() -> "ALL"
            requestedCategoryId == "ALL" -> "ALL"
            state.categories.any { normalizeCategoryId(it.categoryId) == requestedCategoryId } -> requestedCategoryId
            else -> "ALL"
        }
        val filtered = filterChannelsForCategory(
            state.channels,
            activeCategoryId,
            state.favoriteChannelIds
        )
        _uiState.value = state.copy(
            selectedCategoryId = activeCategoryId,
            filteredChannels = filtered,
            focusedChannelIndex = 0,
            epgData = emptyMap()
        )
        persistLiveSelection(categoryId = activeCategoryId, focusedIndex = 0)
        epgJob?.cancel()
        // Cap initial EPG load to 40 channels — prevents sending 500+ concurrent requests
        // when switching to a large category. onVisibleChannelsChanged() handles the rest
        // incrementally as the user scrolls, keeping the server load manageable.
        beginEpgCoverage(filtered)
    }

    fun onVisibleChannelsChanged(firstVisible: Int, lastVisible: Int) {
        val state = _uiState.value
        val channels = state.filteredChannels
        if (channels.isEmpty()) return
        val from = (firstVisible - 18).coerceAtLeast(0)
        val to   = (lastVisible  + 18).coerceAtMost(channels.size - 1)
        val viewportChannels = channels.subList(from, to + 1)
        // Only fetch EPG for channels not already in the map
        val missing = viewportChannels.filter { !state.epgData.containsKey(it.streamId) }
        if (missing.isEmpty()) return
        viewportEpgJob?.cancel()
        viewportEpgJob = viewModelScope.launch {
            try {
                val epgMap = loadEpgMap(missing)
                val cur = _uiState.value
                val currentIds = cur.filteredChannels.asSequence().map { it.streamId }.toSet()
                val alignedEpg = epgMap.filterKeys { it in currentIds }
                if (alignedEpg.isNotEmpty()) {
                    _uiState.value = cur.copy(epgData = cur.epgData + alignedEpg)
                }
            } catch (e: Exception) {
                Timber.w("Viewport EPG load failed: ${e.message}")
            }
        }
    }

    private suspend fun loadEpgForChannels(channels: List<XtreamChannel>) {
        try {
            val existing = _uiState.value.epgData.keys
            val missing = channels.filterNot { it.streamId in existing }
            if (missing.isEmpty()) return
            val epgMap = loadEpgMap(missing)
            val current = _uiState.value
            val currentIds = current.filteredChannels.asSequence().map { it.streamId }.toSet()
            val alignedEpg = epgMap.filterKeys { it in currentIds }
            if (alignedEpg.isNotEmpty()) {
                _uiState.value = current.copy(epgData = current.epgData + alignedEpg)
            }
        } catch (e: Exception) {
            Timber.w("EPG batch load failed: ${e.message}")
        }
    }

    private fun beginEpgCoverage(channels: List<XtreamChannel>) {
        epgJob?.cancel()
        backgroundEpgJob?.cancel()
        epgJob = viewModelScope.launch { loadEpgForChannels(channels.take(90)) }
        backgroundEpgJob = viewModelScope.launch {
            channels.drop(90).take(1_200).chunked(40).forEach { chunk ->
                loadEpgForChannels(chunk)
                delay(300L)
            }
        }
    }

    private suspend fun loadEpgMap(
        channels: List<XtreamChannel>
    ): Map<Int, List<XtreamEpgProgram>> = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val windowStart = now - 30 * 60_000L
        val windowEnd = now + 8 * 3_600_000L
        val roomMap = mutableMapOf<Int, List<XtreamEpgProgram>>()
        val apiIds = mutableListOf<Int>()

        val unmatched = mutableListOf<XtreamChannel>()
        channels.forEach { channel ->
            val epgId = channel.epgChannelId
            var roomPrograms = if (epgId.isNullOrBlank()) {
                emptyList()
            } else {
                epgProgramDao.getProgramsForWindow(epgId, windowStart, windowEnd)
            }
            if (roomPrograms.isEmpty() && !epgId.isNullOrBlank()) {
                for (candidate in fuzzyEpgIds(epgId)) {
                    roomPrograms = epgProgramDao.getProgramsForWindow(candidate, windowStart, windowEnd)
                    if (roomPrograms.isNotEmpty()) break
                }
            }
            if (roomPrograms.isNotEmpty()) {
                // v5.0 time fix: Room XMLTV rows are absolute-correct from XmltvParser —
                // do NOT run the provider-panel alignEpgListing over them (its hour-snapping
                // corrupted correct grids by 4–9 h when "now" sat in a schedule gap).
                roomMap[channel.streamId] = roomPrograms.take(8).map { it.toXtreamProgram() }
            } else {
                unmatched += channel
            }
        }

        // Module 3.1 — sanitised + Levenshtein name match for channels the id passes missed.
        if (unmatched.isNotEmpty()) {
            val distinctIds = epgProgramDao.getDistinctChannelIds()
            if (distinctIds.isNotEmpty()) {
                val index = com.dylandos.iptv.ultimate.data.network.EpgChannelMatcher.SanitizedIndex(distinctIds)
                if (!index.isEmpty) {
                    val iterator = unmatched.iterator()
                    while (iterator.hasNext()) {
                        val channel = iterator.next()
                        val matchId = channel.epgChannelId
                            ?.takeIf { it.isNotBlank() }
                            ?.let { index.exact(it) ?: index.closest(it) }
                            ?: index.exact(channel.name)
                            ?: index.closest(channel.name)
                        if (matchId != null) {
                            val programs = epgProgramDao.getProgramsForWindow(matchId, windowStart, windowEnd)
                            if (programs.isNotEmpty()) {
                                roomMap[channel.streamId] = programs.take(8).map { it.toXtreamProgram() }
                                iterator.remove()
                            }
                        }
                    }
                }
            }
        }
        unmatched.forEach { apiIds += it.streamId }

        roomMap + if (apiIds.isEmpty()) emptyMap()
        else xtreamRepository.batchShortEpg(apiIds, limit = 6)
    }

    private fun refreshXmltvInBackground(channels: List<XtreamChannel>) {
        if (xmltvRefreshJob?.isActive == true || channels.isEmpty()) return
        xmltvRefreshJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val prefs = context.dataStore.data.first()
            val lastFetch = prefs[GuideViewModel.KEY_XMLTV_LAST_FETCH_MS] ?: 0L
            // v5.0: force one refetch when stored EPG semantics changed (TZ/parser fixes).
            val versionBump = (prefs[GuideViewModel.KEY_XMLTV_DATA_VERSION] ?: 0) <
                GuideViewModel.EPG_DATA_VERSION
            if (epgProgramDao.count() > 1_000 && !versionBump &&
                now - lastFetch < 4 * 3_600_000L
            ) return@launch

            xtreamRepository.refreshProviderTimezone()
            val acceptedIds = channels.mapNotNull {
                it.epgChannelId?.takeIf(String::isNotBlank)
            }.toSet()
            val providerParsed = xtreamRepository.fetchXmltvEpg(acceptedIds)
            val thirdPartyEnabled = prefs[GuideViewModel.KEY_EPG_THIRD_PARTY_ENABLED] ?: true
            var storedPrograms = 0
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
                    epgChannelAliasDao.insertAll(
                        parsed.aliasToCanonical.map { (alias, canonical) ->
                            EpgChannelAliasEntity(alias = alias, canonicalChannelId = canonical)
                        }
                    )
                }
            }

            storeFreshPrograms(providerParsed)
            if (thirdPartyEnabled) {
                var remainingExternalBudget = 180_000
                EpgSourceDefaults.parseUrlBlock(prefs[GuideViewModel.KEY_EPG_THIRD_PARTY_URL]).forEach { url ->
                    if (remainingExternalBudget <= 0) return@forEach
                    val limit = minOf(EpgSourceDefaults.maxProgramsFor(url), remainingExternalBudget)
                    val externalParsed = xtreamRepository.fetchExternalXmltvEpg(url, maxPrograms = limit)
                    remainingExternalBudget -= externalParsed.programs.size
                    storeFreshPrograms(externalParsed)
                    kotlinx.coroutines.yield()
                }
            }

            if (storedPrograms > 0) {
                context.dataStore.edit {
                    it[GuideViewModel.KEY_XMLTV_LAST_FETCH_MS] = System.currentTimeMillis()
                    it[GuideViewModel.KEY_XMLTV_DATA_VERSION] = GuideViewModel.EPG_DATA_VERSION
                }
                val visible = _uiState.value.filteredChannels
                _uiState.update { it.copy(epgData = emptyMap()) }
                beginEpgCoverage(visible)
            }
        }
    }

    private fun fuzzyEpgIds(epgId: String): List<String> {
        val lower = epgId.lowercase()
        val stem = lower.substringBefore(".").replace(" ", "")
        return listOf(
            lower,
            stem,
            "$stem.us",
            "$stem.uk",
            lower.replace(" ", ""),
            lower.replace(" ", "_")
        ).distinct()
    }

    fun toggleFavoriteChannel(channel: XtreamChannel) {
        viewModelScope.launch {
            if (channel.streamId in _uiState.value.favoriteChannelIds) {
                favoriteDao.deleteByStreamId(channel.streamId, "live")
            } else {
                favoriteDao.insert(
                    FavoriteEntity(channel.streamId, "live", System.currentTimeMillis())
                )
            }
        }
    }

    fun hideLiveCategory(categoryId: String) {
        if (categoryId == "ALL" || categoryId == MY_CHANNELS_CATEGORY_ID) return
        selectCategory("ALL")
        viewModelScope.launch {
            xtreamRepository.toggleCategoryVisibility(CategoryContentType.LIVE, categoryId)
        }
    }

    fun zapUp() {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val n = state.filteredChannels.size
        // Apply immediately to UI so the highlight tracks the finger, but coalesce
        // the StateFlow + scroll call behind a 220ms debounce to prevent coroutine storms.
        val newIndex = if (state.focusedChannelIndex <= 0) n - 1 else state.focusedChannelIndex - 1
        _uiState.value = state.copy(focusedChannelIndex = newIndex)
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(zapDebounceMs)
            persistLiveSelection(focusedIndex = _uiState.value.focusedChannelIndex)
        }
    }

    fun zapDown() {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val n = state.filteredChannels.size
        val newIndex = if (state.focusedChannelIndex >= n - 1) 0 else state.focusedChannelIndex + 1
        _uiState.value = state.copy(focusedChannelIndex = newIndex)
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(zapDebounceMs)
            persistLiveSelection(focusedIndex = _uiState.value.focusedChannelIndex)
        }
    }

    fun setFocusedChannel(index: Int) {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) {
            _uiState.value = state.copy(focusedChannelIndex = 0)
            persistLiveSelection(focusedIndex = 0)
            return
        }
        val bounded = index.coerceIn(0, state.filteredChannels.lastIndex)
        _uiState.value = state.copy(focusedChannelIndex = bounded)
        persistLiveSelection(focusedIndex = bounded)
    }

    /**
     * Called before navigating to PlayerScreen so the player can zap channels.
     * Stores the current filtered list and the selected index in XtreamRepository.
     */
    fun setActiveChannel(index: Int) {
        val state = _uiState.value
        if (state.filteredChannels.isEmpty()) return
        val bounded = index.coerceIn(0, state.filteredChannels.lastIndex)
        xtreamRepository.liveChannelList  = state.filteredChannels
        xtreamRepository.liveChannelIndex = bounded
        _uiState.value = state.copy(
            focusedChannelIndex = bounded,
            activeStreamId = state.filteredChannels[bounded].streamId
        )
        persistLiveSelection(focusedIndex = bounded)
    }

    /** Keep Live TV focus in sync with channels selected/zapped inside PlayerScreen. */
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
            _uiState.value = state.copy(
                focusedChannelIndex = idx,
                activeStreamId = activeStreamId
            )
            persistLiveSelection(focusedIndex = idx)
        } else if (state.activeStreamId != activeStreamId) {
            _uiState.value = state.copy(activeStreamId = activeStreamId)
        }
    }

    private fun currentActiveStreamId(): Int? =
        xtreamRepository.liveChannelList
            .getOrNull(xtreamRepository.liveChannelIndex)
            ?.streamId

    // ── Channel Number Jump ───────────────────────────────────────────────────

    private var numberJumpJob: Job? = null

    /**
     * Called when a numeric key (0-9) is pressed on the TV remote.
     * Accumulates digits (max 4) and shows the channel-number overlay.
     * After 1.5 s of no new input the channel list scrolls to the matching channel.
     */
    fun onNumberKeyPressed(digit: Char) {
        val newInput = (_uiState.value.channelNumberInput + digit).takeLast(4)
        _uiState.value = _uiState.value.copy(
            channelNumberInput = newInput,
            showChannelJumpOverlay = true
        )
        numberJumpJob?.cancel()
        numberJumpJob = viewModelScope.launch {
            delay(1_500)
            jumpToChannelNumber(newInput.toIntOrNull() ?: return@launch)
            _uiState.value = _uiState.value.copy(
                channelNumberInput = "",
                showChannelJumpOverlay = false
            )
        }
    }

    private fun jumpToChannelNumber(num: Int) {
        val state = _uiState.value
        // Exact match first
        var targetIdx = state.filteredChannels.indexOfFirst { it.num == num }
        if (targetIdx < 0) {
            // Fuzzy: nearest channel number
            val closest = state.filteredChannels.minByOrNull { kotlin.math.abs(it.num - num) }
            targetIdx = state.filteredChannels.indexOf(closest)
        }
        if (targetIdx >= 0) {
            _uiState.value = state.copy(focusedChannelIndex = targetIdx)
            persistLiveSelection(focusedIndex = targetIdx)
            Timber.d("Channel jump: typed $num → index $targetIdx (ch ${state.filteredChannels[targetIdx].num})")
        }
    }

    fun onChannelListScrollChanged(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val state = _uiState.value
        if (state.channelListFirstVisibleIndex == firstVisibleItemIndex &&
            state.channelListFirstVisibleOffset == firstVisibleItemScrollOffset
        ) return
        _uiState.value = state.copy(
            channelListFirstVisibleIndex = firstVisibleItemIndex,
            channelListFirstVisibleOffset = firstVisibleItemScrollOffset
        )
        savedStateHandle[KEY_CHANNEL_LIST_INDEX] = firstVisibleItemIndex
        savedStateHandle[KEY_CHANNEL_LIST_OFFSET] = firstVisibleItemScrollOffset
    }

    fun onCategoryListScrollChanged(firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        val state = _uiState.value
        if (state.categoryListFirstVisibleIndex == firstVisibleItemIndex &&
            state.categoryListFirstVisibleOffset == firstVisibleItemScrollOffset
        ) return
        _uiState.value = state.copy(
            categoryListFirstVisibleIndex = firstVisibleItemIndex,
            categoryListFirstVisibleOffset = firstVisibleItemScrollOffset
        )
        savedStateHandle[KEY_CATEGORY_LIST_INDEX] = firstVisibleItemIndex
        savedStateHandle[KEY_CATEGORY_LIST_OFFSET] = firstVisibleItemScrollOffset
    }

    private fun persistLiveSelection(
        categoryId: String = _uiState.value.selectedCategoryId,
        focusedIndex: Int = _uiState.value.focusedChannelIndex
    ) {
        savedStateHandle[KEY_SELECTED_CATEGORY_ID] = categoryId
        savedStateHandle[KEY_FOCUSED_CHANNEL_INDEX] = focusedIndex
    }
}

data class LiveTvUiState(
    val categories: List<XtreamCategory> = emptyList(),
    val selectedCategoryId: String = "ALL",
    val channels: List<XtreamChannel> = emptyList(),
    val filteredChannels: List<XtreamChannel> = emptyList(),
    val epgData: Map<Int, List<XtreamEpgProgram>> = emptyMap(),
    val favoriteChannelIds: Set<Int> = emptySet(),
    val activeStreamId: Int? = null,
    val focusedChannelIndex: Int = 0,
    val channelListFirstVisibleIndex: Int = 0,
    val channelListFirstVisibleOffset: Int = 0,
    val categoryListFirstVisibleIndex: Int = 0,
    val categoryListFirstVisibleOffset: Int = 0,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,  // true when Room cache is showing but network refresh is running
    val error: String? = null,
    /** Digits typed on the remote number pad — shown in the channel-jump overlay. */
    val channelNumberInput: String = "",
    /** True while the user is typing a channel number (overlay visible). */
    val showChannelJumpOverlay: Boolean = false
)

private fun EpgProgramEntity.toXtreamProgram() = XtreamEpgProgram(
    id = id.toString(),
    epgId = channelId,
    title = title,
    lang = null,
    start = "",
    end = "",
    description = description,
    channelId = channelId,
    startTimestamp = startTime / 1000L,
    stopTimestamp = endTime / 1000L,
    nowPlaying = 0,
    hasArchive = 0
)

// ── Extension helpers ─────────────────────────────────────────────────────────

private fun ChannelEntity.toModel() = XtreamChannel(
    num          = streamId,
    name         = name,
    streamType   = streamType,
    streamId     = streamId,
    streamIcon   = icon,
    epgChannelId = epgChannelId,
    added        = null,
    categoryId   = categoryId,
    customSid    = null,
    tvArchive    = if (hasArchive) 1 else 0,
    directSource = null
)

private fun XtreamChannel.toEntity(fetchedAt: Long) = ChannelEntity(
    streamId      = streamId,
    name          = name,
    categoryId    = categoryId ?: "ALL",
    icon          = streamIcon,
    epgChannelId  = epgChannelId,
    streamType    = streamType,
    hasArchive    = tvArchive > 0,
    lastFetchedAt = fetchedAt
)
