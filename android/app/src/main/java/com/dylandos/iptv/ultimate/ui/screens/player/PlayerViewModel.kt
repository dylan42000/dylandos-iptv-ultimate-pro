package com.dylandos.iptv.ultimate.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.db.dao.EpgProgramDao
import com.dylandos.iptv.ultimate.data.db.dao.VodResumeDao
import com.dylandos.iptv.ultimate.data.db.dao.WatchHistoryDao
import com.dylandos.iptv.ultimate.data.db.entity.EpgProgramEntity
import com.dylandos.iptv.ultimate.data.db.entity.VodResumeEntity
import com.dylandos.iptv.ultimate.data.db.entity.WatchHistoryEntity
import com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram
import com.dylandos.iptv.ultimate.data.network.EpgChannelMatcher
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * DYLANDOS IPTV - Player ViewModel
 */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val xtreamRepository: XtreamRepository,
    private val watchHistoryDao: WatchHistoryDao,
    private val vodResumeDao: VodResumeDao,
    private val epgProgramDao: EpgProgramDao,
    val mpvWrapper: com.dylandos.iptv.ultimate.player.MpvPlayerWrapper
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Series episode context — set when a series episode is loaded
    private var seriesContext: SeriesEpisodeContext? = null
    // VOD context — set when a movie is loaded
    private var vodContext: VodContext? = null
    // Periodic watch-position save job
    private var watchSaveJob: Job? = null
    // Live zapping: debounce + coalesce many ups/downs so LibVLC is not torn down faster than it can init
    private var zapJob: Job? = null
    private var zapAccum: Int = 0
    private val zapDebounceMs = 220L
    private var lastChannelStreamId: Int? = null
    private var numberJumpJob: Job? = null
    private var catchupUrlCandidates: List<String> = emptyList()
    private var catchupUrlIndex: Int = 0

    /**
     * @param streamId  String — live/VOD use numeric IDs; series episodes can be non-numeric (#6 fix)
     * @param extension Container extension — use the actual file format, not always "mp4" (#7 fix)
     */
    fun loadStream(streamType: String, streamId: String, extension: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            try {
                val parsedTimeshift = if (streamType == "timeshift") parseTimeshiftToken(streamId) else null
                val streamUrl = when (streamType) {
                    "live"   -> xtreamRepository.getLiveStreamUrl(
                                    streamId.toIntOrNull() ?: 0,
                                    extension.ifEmpty { "ts" }
                                )
                    "vod"    -> xtreamRepository.getVodStreamUrl(
                                    streamId.toIntOrNull() ?: 0,
                                    extension.ifEmpty { "mp4" }
                                )
                    // Series: episode.id stays as a String — no silent Int cast (#6 fix)
                    "series" -> xtreamRepository.getSeriesStreamUrl(
                                    streamId,
                                    extension.ifEmpty { "mp4" }
                                )
                    "timeshift" -> {
                        val token = parsedTimeshift
                        if (token == null) {
                            _uiState.value = _uiState.value.copy(
                                error = "Invalid time-shift token.",
                                isLoading = false
                            )
                            return@launch
                        }
                        catchupUrlCandidates = xtreamRepository.getTimeshiftStreamUrlCandidates(
                            streamId = token.channelId,
                            startTimestampSec = token.startTimestampSec,
                            durationMinutes = token.durationMinutes,
                            preferredExtension = extension.ifEmpty { "ts" },
                            providerStartTimestampSec = token.providerStartTimestampSec,
                            providerStartWallClock = token.providerStartWallClock
                        )
                        catchupUrlIndex = 0
                        catchupUrlCandidates.firstOrNull() ?: throw IllegalStateException("No catch-up URL candidates")
                    }
                    // DVR: streamId IS the file URI (content:// or absolute path) — use directly
                    "dvr"    -> streamId
                    else -> {
                        _uiState.value = _uiState.value.copy(
                            error = "Unknown stream type: $streamType",
                            isLoading = false
                        )
                        return@launch
                    }
                }

                // Resolve a human-readable title and icon from the channel/VOD list when possible
                val channel = xtreamRepository.liveChannelList
                                  .find { it.streamId == (streamId.toIntOrNull() ?: 0) }
                val timeshiftChannel = parsedTimeshift?.let { token ->
                    xtreamRepository.liveChannelList.find { it.streamId == token.channelId }
                }
                val streamTitle = when (streamType) {
                    "live" -> channel?.name ?: "Channel $streamId"
                    "vod" -> {
                        // Use title set by MoviesViewModel before navigating, or fall back to
                        // the in-memory VOD cache lookup, or a simple non-numeric label.
                        val t = xtreamRepository.pendingStreamTitle
                            ?: xtreamRepository.getCachedVodTitle(streamId.toIntOrNull() ?: 0)
                            ?: "Movie"
                        xtreamRepository.pendingStreamTitle = null
                        t
                    }
                    "series" -> {
                        val t = xtreamRepository.pendingStreamTitle ?: "Episode $streamId"
                        xtreamRepository.pendingStreamTitle = null
                        t
                    }
                    "timeshift" -> {
                        val t = xtreamRepository.pendingStreamTitle
                            ?: timeshiftChannel?.name
                            ?: "Time Shift"
                        xtreamRepository.pendingStreamTitle = null
                        t
                    }
                    "dvr"    -> "DVR Recording"
                    else     -> "Stream $streamId"
                }
                val streamIconUrl = when (streamType) {
                    "live" -> channel?.streamIcon ?: ""
                    "timeshift" -> timeshiftChannel?.streamIcon ?: ""
                    else -> ""
                }

                // For series: store context so PlayerScreen can auto-save position
                if (streamType == "series") {
                    vodContext = null
                    watchSaveJob?.cancel()
                    seriesContext = null
                    xtreamRepository.pendingSeriesPlaybackContext
                        ?.takeIf { it.episodeId == streamId }
                        ?.let { ctx ->
                            setSeriesContext(
                                episodeId = ctx.episodeId,
                                seriesId = ctx.seriesId,
                                seriesName = ctx.seriesName,
                                seasonNumber = ctx.seasonNumber,
                                episodeNumber = ctx.episodeNumber,
                                episodeTitle = ctx.episodeTitle
                            )
                        }
                    xtreamRepository.pendingSeriesPlaybackContext = null
                } else if (streamType == "vod") {
                    seriesContext = null
                    watchSaveJob?.cancel()
                    // VOD context: populate immediately from the resolved title
                    val id = streamId.toIntOrNull() ?: 0
                    vodContext = VodContext(streamId = id, title = streamTitle)
                    // Pre-load any saved resume position so PlayerScreen can seek on first play
                    if (id > 0) {
                        val saved = vodResumeDao.getForMovie(id)
                        if (saved != null && saved.positionMs > 0L) {
                            _uiState.value = _uiState.value.copy(resumePositionMs = saved.positionMs)
                        }
                    }
                    startVodSaveLoop()
                } else {
                    seriesContext = null
                    vodContext = null
                    watchSaveJob?.cancel()
                }

                Timber.d("Stream URL: $streamUrl  title: $streamTitle")

                _uiState.value = _uiState.value.copy(
                    streamUrl = streamUrl,
                    streamTitle = streamTitle,
                    streamIconUrl = streamIconUrl,
                    currentLiveStreamId = if (streamType == "live") streamId.toIntOrNull() else null,
                    isCatchupPlayback = streamType == "timeshift",
                    isLoading = false
                )
                if (streamType == "live") {
                    val sid = streamId.toIntOrNull() ?: 0
                    if (sid > 0) refreshLiveEpg(sid)
                } else {
                    _uiState.value = _uiState.value.copy(
                        liveEpgTitle = null,
                        liveEpgTime = null,
                        upcomingPrograms = emptyList(),
                        neighborPeek = emptyList()
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to load stream")
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Unknown error",
                    isLoading = false
                )
            }
        }
    }

    /** Advance to the next provider archive URL and restart the entire player ladder. */
    fun retryCatchupWithNextUrl(): Boolean {
        if (!_uiState.value.isCatchupPlayback || catchupUrlIndex >= catchupUrlCandidates.lastIndex) return false
        catchupUrlIndex++
        val nextUrl = catchupUrlCandidates[catchupUrlIndex]
        Timber.w("Replay retry: provider URL candidate ${catchupUrlIndex + 1}/${catchupUrlCandidates.size}")
        _uiState.value = _uiState.value.copy(streamUrl = nextUrl, isLoading = false, error = null)
        return true
    }

    /** User-requested clean retry after every archive URL/engine combination has failed. */
    fun restartCatchupRecovery(): Boolean {
        if (!_uiState.value.isCatchupPlayback || catchupUrlCandidates.isEmpty()) return false
        catchupUrlIndex = 0
        val current = _uiState.value
        val firstUrl = catchupUrlCandidates.first()
        // Clear the URL momentarily so Compose creates a new playback request even when
        // the first archive candidate is the same string as the failed request.
        _uiState.value = current.copy(streamUrl = "", error = null, isLoading = true)
        viewModelScope.launch {
            delay(80L)
            _uiState.value = current.copy(streamUrl = firstUrl, error = null, isLoading = false)
        }
        Timber.i("Replay recovery restarted with ${catchupUrlCandidates.size} provider candidates")
        return true
    }

    /**
     * Zap to the next (+1) or previous (-1) channel in the live channel list.
     * Uses [XtreamRepository.liveChannelList] set by LiveTvViewModel.setActiveChannel().
     * Coalesces rapid zaps and applies after a short debounce to avoid LibVLC attach/detach races.
     */
    fun zapChannel(direction: Int): Boolean {
        val channels = xtreamRepository.liveChannelList
        if (channels.isEmpty()) return false
        val n = channels.size
        zapAccum += direction
        zapJob?.cancel()
        zapJob = viewModelScope.launch {
            delay(zapDebounceMs)
            val delta = zapAccum
            zapAccum = 0
            if (delta == 0) return@launch
            val prevId = _uiState.value.currentLiveStreamId
                ?: channels.getOrNull(xtreamRepository.liveChannelIndex)?.streamId
            val newIdx = ((xtreamRepository.liveChannelIndex + delta) % n + n) % n
            xtreamRepository.liveChannelIndex = newIdx
            val channel = channels[newIdx]
            if (prevId != null && prevId != channel.streamId) {
                lastChannelStreamId = prevId
            }
            applyLiveChannel(channel)
        }
        return true
    }

    /** Firestick muscle memory: jump back to the previous live channel. */
    fun zapLastChannel(): Boolean {
        val targetId = lastChannelStreamId ?: return false
        val channels = xtreamRepository.liveChannelList
        val idx = channels.indexOfFirst { it.streamId == targetId }
        if (idx < 0) return false
        val prevId = _uiState.value.currentLiveStreamId
            ?: channels.getOrNull(xtreamRepository.liveChannelIndex)?.streamId
        xtreamRepository.liveChannelIndex = idx
        if (prevId != null && prevId != targetId) lastChannelStreamId = prevId
        applyLiveChannel(channels[idx])
        return true
    }

    /** Remote number-pad channel zap while watching live. */
    fun onNumberKeyPressed(digit: Char) {
        if (_uiState.value.currentLiveStreamId == null && xtreamRepository.liveChannelList.isEmpty()) return
        val newInput = (_uiState.value.channelNumberInput + digit).takeLast(4)
        _uiState.value = _uiState.value.copy(
            channelNumberInput = newInput,
            showChannelJumpOverlay = true
        )
        numberJumpJob?.cancel()
        numberJumpJob = viewModelScope.launch {
            delay(1_400)
            jumpToChannelNumber(newInput.toIntOrNull() ?: return@launch)
            _uiState.value = _uiState.value.copy(
                channelNumberInput = "",
                showChannelJumpOverlay = false
            )
        }
    }

    private fun jumpToChannelNumber(num: Int) {
        val channels = xtreamRepository.liveChannelList
        if (channels.isEmpty()) return
        var targetIdx = channels.indexOfFirst { it.num == num }
        if (targetIdx < 0) {
            targetIdx = channels.indexOfFirst { it.streamId == num }
        }
        if (targetIdx < 0) {
            val closest = channels.minByOrNull { kotlin.math.abs(it.num - num) } ?: return
            targetIdx = channels.indexOf(closest)
        }
        if (targetIdx < 0) return
        val prevId = _uiState.value.currentLiveStreamId
            ?: channels.getOrNull(xtreamRepository.liveChannelIndex)?.streamId
        val channel = channels[targetIdx]
        if (prevId != null && prevId != channel.streamId) lastChannelStreamId = prevId
        xtreamRepository.liveChannelIndex = targetIdx
        applyLiveChannel(channel)
    }

    private fun applyLiveChannel(channel: com.dylandos.iptv.ultimate.data.model.XtreamChannel) {
        val url = xtreamRepository.getLiveStreamUrl(channel.streamId, "ts")
        _uiState.value = _uiState.value.copy(
            streamUrl = url,
            streamTitle = channel.name,
            streamIconUrl = channel.streamIcon ?: "",
            currentLiveStreamId = channel.streamId,
            isCatchupPlayback = false,
            neighborPeek = emptyList(),
            isLoading = false,
            error = null,
            channelNumberInput = "",
            showChannelJumpOverlay = false
        )
        refreshLiveEpg(channel.streamId)
    }

    private fun refreshLiveEpg(streamId: Int) {
        viewModelScope.launch {
            val shortEpg = runCatching {
                xtreamRepository.getShortEpg(streamId, 4).getOrNull()
            }.getOrNull()
            // When the provider's short-EPG API is empty/sparse, fall back to the locally
            // cached XMLTV guide in Room, matched with the same fuzzy logic as the Guide
            // (Module 3.1). Keeps the premium Zap OSD populated on sloppy providers.
            val epgRaw = if (!shortEpg.isNullOrEmpty()) shortEpg else roomEpgFallback(streamId)
            // v5.0 time fix: only provider panel listings (short EPG) go through the
            // conservative aligner; the Room XMLTV fallback is absolute-correct already.
            val epg = if (shortEpg.isNullOrEmpty()) {
                epgRaw
            } else {
                xtreamRepository.alignEpgListing(epgRaw)
            }
            val nowSec = System.currentTimeMillis() / 1000L
            val now = epg?.firstOrNull { it.nowPlaying == 1 }
                ?: epg?.firstOrNull { nowSec in it.startTimestamp until it.stopTimestamp }
                ?: epg?.minByOrNull {
                    kotlin.math.abs((it.startTimestamp + it.stopTimestamp) / 2L - nowSec)
                }
            if (now == null) {
                _uiState.value = _uiState.value.copy(
                    liveEpgTitle = null,
                    liveEpgTime = null,
                    liveEpgStartTimestampSec = 0L,
                    liveEpgStopTimestampSec = 0L,
                    canRestartCurrentProgram = false,
                    neighborPeek = buildNeighborPeek(streamId, null)
                )
                return@launch
            }
            val time = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                formatEpgWindow(now.startTimestamp, now.stopTimestamp)
            }

            // Extract the next 5 programs and past 15 catch-up programs
            val nowIdx = epg?.indexOf(now) ?: -1
            val upcoming = if (epg != null && nowIdx >= 0 && nowIdx + 1 < epg.size) {
                epg.subList(nowIdx + 1, epg.size).take(5)
            } else {
                emptyList()
            }
            val catchupList = epg?.filter { it.stopTimestamp <= nowSec }
                ?.sortedByDescending { it.startTimestamp }
                ?.take(20)
                .orEmpty()

            _uiState.value = _uiState.value.copy(
                liveEpgTitle = now.title,
                liveEpgTime = time,
                liveEpgProgress = calculateEpgProgress(now.startTimestamp, now.stopTimestamp),
                liveEpgStartTimestampSec = now.startTimestamp,
                liveEpgStopTimestampSec = now.stopTimestamp,
                canRestartCurrentProgram = xtreamRepository.liveChannelList
                    .firstOrNull { it.streamId == streamId }
                    ?.tvArchive == 1,
                upcomingPrograms = upcoming,
                catchupPrograms = catchupList,
                neighborPeek = buildNeighborPeek(streamId, now.title)
            )
        }
    }

    private suspend fun buildNeighborPeek(
        streamId: Int,
        currentProgramTitle: String?
    ): List<LiveNeighborPeek> = withContext(Dispatchers.IO) {
        val channels = xtreamRepository.liveChannelList
        val currentIndex = channels.indexOfFirst { it.streamId == streamId }
        if (currentIndex < 0 || channels.isEmpty()) return@withContext emptyList()
        (-2..2).mapNotNull { offset ->
            val index = currentIndex + offset
            val channel = channels.getOrNull(index) ?: return@mapNotNull null
            val programTitle = if (offset == 0) {
                currentProgramTitle
            } else {
                runCatching {
                    xtreamRepository.getShortEpg(channel.streamId, 1)
                        .getOrNull()
                        ?.firstOrNull()
                        ?.title
                }.getOrNull()
            }
            LiveNeighborPeek(
                streamId = channel.streamId,
                channelName = channel.name,
                streamIconUrl = channel.streamIcon.orEmpty(),
                programTitle = programTitle,
                positionLabel = when (offset) {
                    -2 -> "-2"
                    -1 -> "Prev"
                    0 -> "Now"
                    1 -> "Next"
                    else -> "+2"
                },
                isCurrent = offset == 0
            )
        }
    }

    /**
     * Build now+next EPG for the Zap OSD from the locally cached XMLTV guide (Room).
     * Used when the Xtream short-EPG API returns nothing. Resolves the channel's
     * epg_channel_id (and, failing that, its display name) against stored programs
     * using the shared [EpgChannelMatcher] sanitised + Levenshtein passes.
     */
    private suspend fun roomEpgFallback(streamId: Int): List<XtreamEpgProgram> {
        val channel = xtreamRepository.liveChannelList.firstOrNull { it.streamId == streamId }
        val now = System.currentTimeMillis()
        val windowStart = now - 6 * 3_600_000L
        val windowEnd = now + 24 * 3_600_000L

        suspend fun query(channelId: String): List<EpgProgramEntity> =
            runCatching { epgProgramDao.getProgramsForWindow(channelId, windowStart, windowEnd) }
                .getOrDefault(emptyList())

        // Pass 1: exact epg_channel_id.
        var rows = channel?.epgChannelId?.takeIf { it.isNotBlank() }?.let { query(it) } ?: emptyList()

        // Pass 2: sanitised + fuzzy name match against the stored channel ids.
        if (rows.isEmpty()) {
            val distinctIds = runCatching { epgProgramDao.getDistinctChannelIds() }.getOrDefault(emptyList())
            if (distinctIds.isNotEmpty()) {
                val index = EpgChannelMatcher.SanitizedIndex(distinctIds)
                val matchId = channel?.epgChannelId?.takeIf { it.isNotBlank() }
                    ?.let { index.exact(it) ?: index.closest(it) }
                    ?: channel?.name?.let { index.exact(it) ?: index.closest(it) }
                if (matchId != null) rows = query(matchId)
            }
        }
        if (rows.isEmpty()) return emptyList()

        val nowSec = now / 1000L
        return rows.sortedBy { it.startTime }.map { entity ->
            val startSec = entity.startTime / 1000L
            val stopSec = entity.endTime / 1000L
            XtreamEpgProgram(
                id = entity.id.toString(),
                epgId = entity.channelId,
                title = entity.title,
                lang = null,
                start = "",
                end = "",
                description = entity.description,
                channelId = entity.channelId,
                startTimestamp = startSec,
                stopTimestamp = stopSec,
                nowPlaying = if (nowSec in startSec until stopSec) 1 else 0,
                hasArchive = 0
            )
        }
    }

    fun restartCurrentProgram(resumeAtCurrentPosition: Boolean = false): Boolean {
        val state = _uiState.value
        val channelId = state.currentLiveStreamId ?: return false
        val startSec = state.liveEpgStartTimestampSec
        val stopSec = state.liveEpgStopTimestampSec
        if (!state.canRestartCurrentProgram || startSec <= 0L || stopSec <= startSec) return false

        val durationMinutes = (((stopSec - startSec) + 59L) / 60L).toInt().coerceAtLeast(1)
        _uiState.value = state.copy(
            streamUrl = xtreamRepository.getTimeshiftStreamUrl(
                streamId = channelId,
                startTimestampSec = startSec,
                durationMinutes = durationMinutes,
                extension = "ts"
            ),
            streamTitle = listOfNotNull(state.streamTitle, state.liveEpgTitle)
                .filter { it.isNotBlank() }
                .joinToString(" - "),
            isCatchupPlayback = true,
            catchupResumePositionMs = if (resumeAtCurrentPosition) {
                ((System.currentTimeMillis() / 1000L - startSec).coerceAtLeast(0L) * 1000L)
            } else {
                0L
            },
            error = null
        )
        return true
    }

    private fun calculateEpgProgress(startMs: Long, endMs: Long): Float {
        val now = System.currentTimeMillis() / 1000L
        if (now < startMs || endMs <= startMs) return 0f
        if (now > endMs) return 1f
        return (now - startMs).toFloat() / (endMs - startMs).toFloat()
    }

    /**
     * Retry: re-trigger the stream URL update so PlayerScreen's LaunchedEffect
     * re-runs and attempts to play the stream again.
     */
    fun retryStream() {
        val current = _uiState.value
        _uiState.value = current.copy(streamUrl = "", error = null)
        viewModelScope.launch {
            delay(50)
            _uiState.value = current.copy(error = null)
        }
    }

    /**
     * Called by PlayerScreen once the series episode metadata is known.
     * Starts a periodic 30-second save loop that writes resume position to Room.
     */
    fun setSeriesContext(
        episodeId: String,
        seriesId: Int,
        seriesName: String,
        seasonNumber: Int,
        episodeNumber: Int,
        episodeTitle: String
    ) {
        seriesContext = SeriesEpisodeContext(
            episodeId, seriesId, seriesName, seasonNumber, episodeNumber, episodeTitle
        )
        // Kick off periodic save every 30 seconds
        watchSaveJob?.cancel()
        watchSaveJob = viewModelScope.launch {
            while (true) {
                delay(30_000L)
                val pos  = _uiState.value.currentPositionMs
                val dur  = _uiState.value.durationMs
                val ctx  = seriesContext ?: break
                if (pos > 0L) {
                    watchHistoryDao.upsert(
                        WatchHistoryEntity(
                            episodeId     = ctx.episodeId,
                            seriesId      = ctx.seriesId,
                            seriesName    = ctx.seriesName,
                            seasonNumber  = ctx.seasonNumber,
                            episodeNumber = ctx.episodeNumber,
                            episodeTitle  = ctx.episodeTitle,
                            positionMs    = pos,
                            durationMs    = dur,
                            lastWatchedAt = System.currentTimeMillis()
                        )
                    )
                    Timber.d("WatchHistory saved: ep=${ctx.episodeId} pos=${pos}ms")
                }
            }
        }
    }

    /** Called by PlayerScreen on every time-changed event from the player. */
    fun updatePlaybackPosition(positionMs: Long, durationMs: Long) {
        _uiState.value = _uiState.value.copy(
            currentPositionMs = positionMs,
            durationMs = durationMs
        )
    }

    /** Final save on stop/destroy — captures the exact last position. */
    fun savePositionNow() {
        val pos = _uiState.value.currentPositionMs
        val dur = _uiState.value.durationMs
        // Save series
        val ctx = seriesContext
        if (ctx != null && pos > 0L) {
            viewModelScope.launch {
                watchHistoryDao.upsert(
                    WatchHistoryEntity(
                        episodeId     = ctx.episodeId,
                        seriesId      = ctx.seriesId,
                        seriesName    = ctx.seriesName,
                        seasonNumber  = ctx.seasonNumber,
                        episodeNumber = ctx.episodeNumber,
                        episodeTitle  = ctx.episodeTitle,
                        positionMs    = pos,
                        durationMs    = dur,
                        lastWatchedAt = System.currentTimeMillis()
                    )
                )
            }
        }
        // Save VOD
        val vCtx = vodContext
        if (vCtx != null && pos > 0L && vCtx.streamId > 0) {
            viewModelScope.launch {
                vodResumeDao.upsert(
                    VodResumeEntity(
                        streamId      = vCtx.streamId,
                        title         = vCtx.title,
                        positionMs    = pos,
                        durationMs    = dur,
                        lastWatchedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    private fun startVodSaveLoop() {
        watchSaveJob?.cancel()
        watchSaveJob = viewModelScope.launch {
            while (true) {
                delay(30_000L)
                val pos  = _uiState.value.currentPositionMs
                val dur  = _uiState.value.durationMs
                val vCtx = vodContext ?: break
                if (pos > 0L && vCtx.streamId > 0) {
                    vodResumeDao.upsert(
                        VodResumeEntity(
                            streamId      = vCtx.streamId,
                            title         = vCtx.title,
                            positionMs    = pos,
                            durationMs    = dur,
                            lastWatchedAt = System.currentTimeMillis()
                        )
                    )
                    Timber.d("VodResume saved: id=${vCtx.streamId} pos=${pos}ms")
                }
            }
        }
    }

    fun buildTimeShiftToken(streamId: Int, startMs: Long, endMs: Long): String {
        val safeStartSec = (startMs / 1000L).coerceAtLeast(0L)
        val durationMin = ((endMs - startMs + 59_999L) / 60_000L).coerceAtLeast(1L).toInt()
        return "${streamId}_${safeStartSec}_${durationMin}"
    }

    fun setPendingPlaybackTitle(title: String) {
        xtreamRepository.pendingStreamTitle = title
    }

    override fun onCleared() {
        zapJob?.cancel()
        savePositionNow()
        watchSaveJob?.cancel()
        super.onCleared()
    }
}

private fun formatEpgWindow(startMs: Long, endMs: Long): String {
    if (startMs <= 0L || endMs <= 0L) return ""
    return runCatching {
        val startStr = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatWindowLabel(startMs * 1000L)
        val endStr = com.dylandos.iptv.ultimate.ui.util.TimeFormatter.formatShortTime(endMs * 1000L)
        "$startStr · $endStr"
    }.getOrDefault("")
}

private data class SeriesEpisodeContext(
    val episodeId: String,
    val seriesId: Int,
    val seriesName: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String
)

private data class VodContext(
    val streamId: Int,
    val title: String
)

private data class TimeShiftToken(
    val channelId: Int,
    val startTimestampSec: Long,
    val durationMinutes: Int,
    val providerStartTimestampSec: Long = 0L,
    val providerStartWallClock: String? = null
)

private fun parseTimeshiftToken(token: String): TimeShiftToken? {
    val parts = token.split('_')
    if (parts.size < 3) return null
    val channelId = parts[0].toIntOrNull() ?: return null
    val startSec = parts[1].toLongOrNull() ?: return null
    val durationMin = parts[2].toIntOrNull() ?: return null
    if (channelId <= 0 || durationMin <= 0) return null
    val providerStart = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L } ?: 0L
    val wallDigits = parts.getOrNull(4)?.takeIf { it.length == 12 && it.all(Char::isDigit) }
    val wallClock = wallDigits?.let {
        "${it.substring(0, 4)}-${it.substring(4, 6)}-${it.substring(6, 8)}:${it.substring(8, 10)}-${it.substring(10, 12)}"
    }
    return TimeShiftToken(
        channelId = channelId,
        startTimestampSec = startSec,
        durationMinutes = durationMin,
        providerStartTimestampSec = providerStart,
        providerStartWallClock = wallClock
    )
}

data class PlayerUiState(
    val streamUrl: String = "",
    val streamTitle: String = "",
    val streamIconUrl: String = "",   // channel logo URL for OSD display
    /** Current / next program from short EPG for live zapping overlay. */
    val liveEpgTitle: String? = null,
    val liveEpgTime: String? = null,
    val liveEpgProgress: Float = 0f,
    val liveEpgStartTimestampSec: Long = 0L,
    val liveEpgStopTimestampSec: Long = 0L,
    val canRestartCurrentProgram: Boolean = false,
    val currentLiveStreamId: Int? = null,
    val isCatchupPlayback: Boolean = false,
    val catchupResumePositionMs: Long = 0L,
    val upcomingPrograms: List<com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram> = emptyList(),
    val catchupPrograms: List<com.dylandos.iptv.ultimate.data.model.XtreamEpgProgram> = emptyList(),
    val neighborPeek: List<LiveNeighborPeek> = emptyList(),
    val currentPositionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** For VOD: saved resume position to seek to after playback starts (0 = no resume). */
    val resumePositionMs: Long = 0L,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Digits typed on the remote while in the player (live channel jump). */
    val channelNumberInput: String = "",
    val showChannelJumpOverlay: Boolean = false
)

data class LiveNeighborPeek(
    val streamId: Int,
    val channelName: String,
    val streamIconUrl: String,
    val programTitle: String?,
    val positionLabel: String,
    val isCurrent: Boolean
)
