package com.dylandos.iptv.ultimate.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dylandos.iptv.ultimate.data.model.AccountSerializer
import com.dylandos.iptv.ultimate.data.model.SavedAccount
import com.dylandos.iptv.ultimate.data.model.XtreamCategory
import com.dylandos.iptv.ultimate.data.model.XtreamProfile
import com.dylandos.iptv.ultimate.data.network.XtreamRepository
import com.dylandos.iptv.ultimate.data.network.CategoryContentType
import com.dylandos.iptv.ultimate.data.network.EpgSourceDefaults
import com.dylandos.iptv.ultimate.data.util.sortCategories
import com.dylandos.iptv.ultimate.ui.screens.login.LoginViewModel
import com.dylandos.iptv.ultimate.ui.theme.AppTheme
import com.dylandos.iptv.ultimate.ui.theme.appThemeFromName
import com.dylandos.iptv.ultimate.data.filter.ContentFilterRepository
import com.dylandos.iptv.ultimate.data.filter.FilterSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

// ── DataStore singleton ───────────────────────────────────────────────────────
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// ── UI State ──────────────────────────────────────────────────────────────────
data class SettingsUiState(
    // Account
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val isConnected: Boolean = false,
    val isLoading: Boolean = false,
    val statusMessage: String? = null,
    val isError: Boolean = false,
    // ── Multi-account management ──────────────────────────────────────────────
    /** Up to 6 saved Xtream accounts. */
    val savedAccounts: List<SavedAccount> = emptyList(),
    /** ID of the currently active account (matches SavedAccount.id). */
    val activeAccountId: String = "",
    /** When non-null, the account editor dialog is open for this account. */
    val editingAccount: SavedAccount? = null,

    // Playback
    val streamFormat: String = "ts",            // "ts" or "m3u8"
    val maxReconnectAttempts: Int = 3,
    val reconnectDelayMs: Int = 2000,
    val autoPlayNextEpisode: Boolean = true,
    val autoPlayCountdown: Int = 5,             // seconds before auto-play
    val resumePlayback: Boolean = true,
    val hardwareAccelerated: Boolean = true,
    val liveBufferPreset: String = "Medium",    // "Low" | "Medium" | "High"
    val audioOffsetMs: Int = 0,

    // EPG
    val epgRefreshIntervalHours: Int = 4,
    val epgHoursToShow: Int = 4,
    val epgTimeFormat: String = "24h",          // "12h" or "24h"
    val epgShowDescriptions: Boolean = true,
    val epgCompactMode: Boolean = false,
    val epgThirdPartyEnabled: Boolean = true,
    val epgThirdPartyUrl: String = EpgSourceDefaults.DEFAULT_URL_BLOCK,
    val epgTimeOffsetHours: Int = 0,

    // Appearance
    val appTheme: AppTheme = AppTheme.NEON_PARADISE,
    val accentColorHex: String = "#06B6D4",     // Cyan — matches Color.kt
    val fontSizeScalePct: Int = 100,            // 75 – 150
    val posterAspectRatio: String = "2:3",      // "2:3" or "16:9"
    val gridColumnsOverride: Int = 5,
    val uiAnimationsEnabled: Boolean = true,

    // Subtitles
    val subtitleLanguage: String = "eng",
    val subtitleSizeSp: Int = 24,
    val subtitleColorHex: String = "#FFFFFF",
    val subtitleBgOpacity: Int = 60,            // 0-100
    val subtitleOutlineColorHex: String = "#000000",  // outline/border color for subtitle text

    // System / LibVLC
    val networkCacheMs: Int = 2000,
    val hwDecodingMode: String = "auto",        // "auto" | "disabled" | "force"
    val deinterlaceMode: String = "blend",
    val audioPassthrough: Boolean = false,

    // Performance
    val maxConcurrentStreams: Int = 1,
    val imageCacheMb: Int = 128,
    val epgCacheDurationHours: Int = 24,
    // Performance — Live TV
    val liveNetworkCacheMs: Int = 800,      // VLC --network-caching for live
    val liveAudioBufferMs: Int = 250,       // VLC --audio-desync compensation
    val liveClockJitter: Boolean = true,    // force clock-jitter=0 for live (smoother sync)
    // Performance — VOD
    val vodNetworkCacheMs: Int = 2000,      // VLC --network-caching for VOD
    val vodFileCacheMs: Int = 1500,         // VLC --file-caching for VOD
    val vodPreferHwDecode: Boolean = true,  // HW decode for movies/series
    // Performance — Memory
    val thumbnailCacheMb: Int = 64,         // Coil thumbnail memory cache
    val bitmapPoolMb: Int = 32,             // Coil bitmap pool size
    val responseCacheMb: Int = 50,          // OkHttp disk response cache
    // Performance — DVR
    val maxSimultaneousDvr: Int = 1,        // 1-3 concurrent recordings

    // ── DVR Settings ──────────────────────────────────────────────────────────
    val dvrPreBufferMinutes: Int = 2,
    val dvrPostBufferMinutes: Int = 5,
    val dvrAutoDeleteCompleted: Boolean = false,
    val dvrStorageQuotaGb: Int = 10,
    val dvrNotificationsEnabled: Boolean = true,
    val dvrDefaultQuality: String = "Original",    // "Original" | "720p" | "480p"
    /** SAF/OTG tree URI string — shared with DvrViewModel via the same DataStore key. */
    val dvrStoragePath: String? = null,
    /** Enables provider catch-up plus LibVLC/Media3 local live pause and rewind buffering. */
    val timeshiftEnabled: Boolean = false,

    // ── v5.0 Adaptive Buffer + Timeshift window ────────────────────────────────
    /** Adaptive live buffer: controller raises/lowers --network-caching automatically. */
    val adaptiveBuffer: Boolean = true,
    /** Adaptive floor in ms — the cache never goes below this during a live session. */
    val bufferFloorMs: Int = 600,
    /** Adaptive ceiling in ms — the cache never goes above this (was a hard 2000 clamp). */
    val bufferCeilingMs: Int = 4000,
    /** 0 = auto ring sizing; 30 min is the Firestick/DVR-friendly default. */
    val timeshiftWindowMinutes: Int = 30,

    // ── Guide Display Settings ─────────────────────────────────────────────────
    val guideHoursToShow: Int = 4,         // 2, 4, 6, 8 hours visible in guide window
    val guideRowHeightMode: String = "Normal", // "Compact" (48dp) | "Normal" (64dp) | "Large" (80dp)
    val guideEpgPreloadRows: Int = 80,     // how many rows to pre-load EPG for on category switch
    val guideShowChannelNumbers: Boolean = true,  // show ch# in guide left column
    val guideAutoScrollToNow: Boolean = true,      // auto-scroll to current time on open

    // ── Category Customization ────────────────────────────────────────────────
    val usEnFirstLive: Boolean    = true,
    val usEnFirstMovies: Boolean  = true,
    val usEnFirstSeries: Boolean  = true,
    val hiddenLiveCategories: Set<String>   = emptySet(),
    val hiddenMovieCategories: Set<String>  = emptySet(),
    val hiddenSeriesCategories: Set<String> = emptySet(),
    // Available categories loaded from server for the settings UI checkboxes
    val availableLiveCategories: List<XtreamCategory>   = emptyList(),
    val availableMovieCategories: List<XtreamCategory>  = emptyList(),
    val availableSeriesCategories: List<XtreamCategory> = emptyList(),
    val categoriesLoading: Boolean = false,
    // ── Parental Controls ─────────────────────────────────────────────────────
    val parentalEnabled: Boolean = false,
    val parentalPin: String = "",            // SHA-256 hashed; empty = not set
    val lockedLiveCategories: Set<String> = emptySet(),
    val lockedMovieCategories: Set<String> = emptySet(),
    val lockedSeriesCategories: Set<String> = emptySet(),
    /** Scopes custom lists / per-user prefs (Kids, Default, etc.). */
    val activeProfileId: String = "default",
    val profileIds: List<String> = listOf("default"),

    // ── LibVLC Engine Settings ─────────────────────────────────────────────────
    /** Audio output module: android_audiotrack (recommended for FireTV), opensles, auto */
    val vlcAudioOutput: String = "android_audiotrack",
    /** Audio resampler: soxr = best quality, speex = fast, ugly = minimal CPU */
    val vlcAudioResampler: String = "ugly",
    /** Android display chroma format: RV32 = widest compat, RV16 = low mem, RGBA, auto */
    val vlcChromaFormat: String = "RV16",
    /** Subtitle text encoding: auto, UTF-8, ISO-8859-1, windows-1252, KOI8-R, CP850 */
    val vlcSubtitleEncoding: String = "auto",
    /** Drop frames arriving late — reduces stuttering on slow networks (live) */
    val vlcDropLateFrames: Boolean = true,
    /** Skip frames to keep sync — aggressive; may cause visual artefacts */
    val vlcSkipFrames: Boolean = true,
    /** Enable hardware (GPU) subtitle rendering — needs driver support */
    val vlcHwSubtitles: Boolean = false,
    /** Clock jitter threshold in ms (0 = off; recommended 0 for IPTV live) */
    val vlcClockJitterMs: Int = 0,
    /** Network MTU (Maximum Transmission Unit) — 0 = auto */
    val vlcNetworkMtu: Int = 0,
    /** Enable LibVLC verbose debug logging in logcat */
    val vlcVerboseLog: Boolean = false,


    // ── Player Experience Customization ───────────────────────────────────────
    /** Seek skip interval in seconds for Rewind/FastForward buttons (5/10/15/30/60) */
    val skipIntervalSeconds: Int = 10,
    /** Seconds before player controls overlay auto-hides (3/5/8/10) */
    val controlsAutoHideSeconds: Int = 5,
    /** ISO 639-2 language code to auto-select audio track ("auto" = first available) */
    val preferredAudioLanguage: String = "auto",
    /** Duration in seconds the channel zap OSD is shown on Live TV channel change */
    val zappingOsdDurationSeconds: Int = 6,
    /** Player controls bar background opacity 40-100% */
    val playerOverlayOpacity: Int = 80,
    /** Show wall clock in player HUD title bar */
    val showClockInPlayer: Boolean = true,
    /** Acquire WakeLock + FLAG_KEEP_SCREEN_ON during playback (prevents Firestick sleep) */
    val keepScreenOnDuringPlayback: Boolean = true,
    /** Show Continue Watching row on home screen */
    val continueWatchingEnabled: Boolean = true,
    /** Thumbnail image loading quality: Low / Medium / High */
    val thumbnailQuality: String = "Medium",
    /** Show channel number in the player title bar for Live TV */
    val showChannelNumbersInPlayer: Boolean = false,
    /** Default duration in minutes for a new scheduled recording */
    val defaultRecordingDurationMin: Int = 60,
    /** Allow DVR recording to run in background after player is closed */
    val recordInBackground: Boolean = true
)

// ── ViewModel ─────────────────────────────────────────────────────────────────
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val xtreamRepository: XtreamRepository,
    private val contentFilterRepository: ContentFilterRepository
) : ViewModel() {

    /** Live content filter settings — used by ContentFilterTab in SettingsScreen. */
    val filterSettings: StateFlow<FilterSettings> = contentFilterRepository.filterSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FilterSettings())

    suspend fun saveFilterSettings(settings: FilterSettings) =
        contentFilterRepository.saveSettings(settings)

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    // ── DataStore preference keys ──────────────────────────────────────────────
    companion object {
        // Playback
        val KEY_STREAM_FORMAT        = stringPreferencesKey("stream_format")
        val KEY_MAX_RECONNECTS       = intPreferencesKey("max_reconnect_attempts")
        // Multi-account
        val KEY_SAVED_ACCOUNTS       = stringPreferencesKey("saved_accounts_json")
        val KEY_ACTIVE_ACCOUNT_ID    = stringPreferencesKey("active_account_id")
        val KEY_RECONNECT_DELAY      = intPreferencesKey("reconnect_delay_ms")
        val KEY_AUTO_PLAY_NEXT       = booleanPreferencesKey("auto_play_next")
        val KEY_AUTO_PLAY_COUNTDOWN  = intPreferencesKey("auto_play_countdown")
        val KEY_RESUME_PLAYBACK      = booleanPreferencesKey("resume_playback")
        val KEY_HW_ACCELERATED       = booleanPreferencesKey("hw_accelerated")
        val KEY_LIVE_BUFFER_PRESET   = stringPreferencesKey("live_buffer_preset")
        val KEY_AUDIO_OFFSET_MS      = intPreferencesKey("audio_offset_ms")
        // EPG
        val KEY_EPG_REFRESH_HOURS    = intPreferencesKey("epg_refresh_interval_hours")
        val KEY_EPG_TIME_OFFSET      = intPreferencesKey("epg_time_offset_hours")
        val KEY_EPG_HOURS_TO_SHOW    = intPreferencesKey("epg_hours_to_show")
        val KEY_EPG_TIME_FORMAT      = stringPreferencesKey("epg_time_format")
        val KEY_EPG_DESCRIPTIONS     = booleanPreferencesKey("epg_show_descriptions")
        val KEY_EPG_COMPACT          = booleanPreferencesKey("epg_compact_mode")
        val KEY_EPG_THIRD_PARTY_ENABLED = booleanPreferencesKey("epg_third_party_enabled")
        val KEY_EPG_THIRD_PARTY_URL  = stringPreferencesKey("epg_third_party_url")
        val DEFAULT_THIRD_PARTY_EPG_URL: String = EpgSourceDefaults.DEFAULT_URL_BLOCK
        // Appearance
        val KEY_APP_THEME            = stringPreferencesKey("app_theme")
        val KEY_ACCENT_COLOR         = stringPreferencesKey("accent_color_hex")
        val KEY_FONT_SCALE           = intPreferencesKey("font_size_scale_pct")
        val KEY_POSTER_ASPECT        = stringPreferencesKey("poster_aspect_ratio")
        val KEY_GRID_COLS            = intPreferencesKey("grid_columns_override")
        val KEY_UI_ANIMATIONS        = booleanPreferencesKey("ui_animations_enabled")
        // Subtitles
        val KEY_SUBTITLE_LANG        = stringPreferencesKey("subtitle_language")
        val KEY_SUBTITLE_SIZE        = intPreferencesKey("subtitle_size_sp")
        val KEY_SUBTITLE_COLOR       = stringPreferencesKey("subtitle_color_hex")
        val KEY_SUBTITLE_BG_OPACITY  = intPreferencesKey("subtitle_bg_opacity")
        val KEY_SUBTITLE_OUTLINE_COLOR = stringPreferencesKey("subtitle_outline_color_hex")
        // System
        val KEY_NETWORK_CACHE        = intPreferencesKey("network_cache_ms")
        val KEY_HW_DECODING_MODE     = stringPreferencesKey("hw_decoding_mode")
        val KEY_DEINTERLACE          = stringPreferencesKey("deinterlace_mode")
        val KEY_AUDIO_PASSTHROUGH    = booleanPreferencesKey("audio_passthrough")
        // Performance
        val KEY_MAX_STREAMS          = intPreferencesKey("max_concurrent_streams")
        val KEY_IMAGE_CACHE_MB       = intPreferencesKey("image_cache_mb")
        val KEY_EPG_CACHE_HOURS      = intPreferencesKey("epg_cache_duration_hours")
        // Performance — Live TV
        val KEY_LIVE_NET_CACHE       = intPreferencesKey("live_network_cache_ms")
        val KEY_LIVE_AUDIO_BUFFER    = intPreferencesKey("live_audio_buffer_ms")
        val KEY_LIVE_CLOCK_JITTER    = booleanPreferencesKey("live_clock_jitter")
        // Performance — VOD
        val KEY_VOD_NET_CACHE        = intPreferencesKey("vod_network_cache_ms")
        val KEY_VOD_FILE_CACHE       = intPreferencesKey("vod_file_cache_ms")
        val KEY_VOD_PREFER_HW        = booleanPreferencesKey("vod_prefer_hw_decode")
        // Performance — Memory
        val KEY_THUMBNAIL_CACHE_MB   = intPreferencesKey("thumbnail_cache_mb")
        val KEY_BITMAP_POOL_MB       = intPreferencesKey("bitmap_pool_mb")
        val KEY_RESPONSE_CACHE_MB    = intPreferencesKey("response_cache_mb")
        // Performance — DVR
        val KEY_MAX_SIMULTANEOUS_DVR = intPreferencesKey("max_simultaneous_dvr")
        // DVR Settings
        val KEY_DVR_PRE_BUFFER     = intPreferencesKey("dvr_pre_buffer_minutes")
        val KEY_DVR_POST_BUFFER    = intPreferencesKey("dvr_post_buffer_minutes")
        val KEY_DVR_AUTO_DELETE    = booleanPreferencesKey("dvr_auto_delete_completed")
        val KEY_DVR_QUOTA_GB       = intPreferencesKey("dvr_storage_quota_gb")
        val KEY_DVR_NOTIFICATIONS  = booleanPreferencesKey("dvr_notifications_enabled")
        val KEY_DVR_DEFAULT_QUALITY= stringPreferencesKey("dvr_default_quality")
        /** Shared with DvrViewModel — same DataStore key "dvr_storage_path" */
        val KEY_DVR_STORAGE_PATH   = stringPreferencesKey("dvr_storage_path")
        val KEY_TIMESHIFT_ENABLED  = booleanPreferencesKey("timeshift_enabled_v2")
        // v5.0 adaptive buffer + timeshift window
        val KEY_ADAPTIVE_BUFFER    = booleanPreferencesKey("adaptive_buffer_v2")
        val KEY_BUFFER_FLOOR_MS    = intPreferencesKey("buffer_floor_ms")
        val KEY_BUFFER_CEILING_MS  = intPreferencesKey("buffer_ceiling_ms")
        val KEY_TIMESHIFT_WINDOW_MIN = intPreferencesKey("timeshift_window_minutes")
        // Guide display
        val KEY_GUIDE_HOURS          = intPreferencesKey("guide_hours_to_show")
        val KEY_GUIDE_ROW_HEIGHT     = stringPreferencesKey("guide_row_height_mode")
        val KEY_GUIDE_EPG_PRELOAD    = intPreferencesKey("guide_epg_preload_rows")
        val KEY_GUIDE_SHOW_CH_NUM    = booleanPreferencesKey("guide_show_channel_numbers")
        val KEY_GUIDE_AUTO_SCROLL    = booleanPreferencesKey("guide_auto_scroll_to_now")
        // Category customization
        val KEY_US_EN_FIRST_LIVE   = booleanPreferencesKey("us_en_first_live")
        val KEY_US_EN_FIRST_MOVIES = booleanPreferencesKey("us_en_first_movies")
        val KEY_US_EN_FIRST_SERIES = booleanPreferencesKey("us_en_first_series")
        val KEY_HIDDEN_LIVE_CATS   = stringSetPreferencesKey("hidden_live_categories")
        val KEY_HIDDEN_MOVIE_CATS  = stringSetPreferencesKey("hidden_movie_categories")
        val KEY_HIDDEN_SERIES_CATS = stringSetPreferencesKey("hidden_series_categories")
        // Parental controls
        val KEY_PARENTAL_ENABLED   = booleanPreferencesKey("parental_enabled")
        val KEY_PARENTAL_PIN       = stringPreferencesKey("parental_pin")
        val KEY_LOCKED_LIVE_CATS   = stringSetPreferencesKey("locked_live_categories")
        val KEY_LOCKED_MOVIE_CATS  = stringSetPreferencesKey("locked_movie_categories")
        val KEY_LOCKED_SERIES_CATS = stringSetPreferencesKey("locked_series_categories")
        val KEY_ACTIVE_PROFILE_ID  = stringPreferencesKey("active_profile_id")
        val KEY_PROFILE_IDS        = stringSetPreferencesKey("profile_ids")
        // LibVLC engine options
        val KEY_VLC_AUDIO_OUTPUT      = stringPreferencesKey("vlc_audio_output")
        val KEY_VLC_AUDIO_RESAMPLER   = stringPreferencesKey("vlc_audio_resampler")
        val KEY_VLC_CHROMA_FORMAT     = stringPreferencesKey("vlc_chroma_format")
        val KEY_VLC_SUB_ENCODING      = stringPreferencesKey("vlc_subtitle_encoding")
        val KEY_VLC_DROP_LATE_FRAMES  = booleanPreferencesKey("vlc_drop_late_frames")
        val KEY_VLC_SKIP_FRAMES       = booleanPreferencesKey("vlc_skip_frames")
        val KEY_VLC_HW_SUBTITLES      = booleanPreferencesKey("vlc_hw_subtitles")
        val KEY_VLC_CLOCK_JITTER_MS   = intPreferencesKey("vlc_clock_jitter_ms")
        val KEY_VLC_NETWORK_MTU       = intPreferencesKey("vlc_network_mtu")
        val KEY_VLC_VERBOSE_LOG       = booleanPreferencesKey("vlc_verbose_log")
        // Player Experience
        val KEY_SKIP_INTERVAL          = intPreferencesKey("skip_interval_seconds")
        val KEY_CONTROLS_AUTOHIDE      = intPreferencesKey("controls_auto_hide_seconds")
        val KEY_PREFERRED_AUDIO_LANG   = stringPreferencesKey("preferred_audio_language")
        val KEY_ZAPPING_OSD_DURATION   = intPreferencesKey("zapping_osd_duration_seconds")
        val KEY_PLAYER_OVERLAY_OPACITY = intPreferencesKey("player_overlay_opacity")
        val KEY_SHOW_CLOCK_IN_PLAYER   = booleanPreferencesKey("show_clock_in_player")
        val KEY_KEEP_SCREEN_ON         = booleanPreferencesKey("keep_screen_on_during_playback")
        val KEY_CONTINUE_WATCHING      = booleanPreferencesKey("continue_watching_enabled")
        val KEY_THUMBNAIL_QUALITY      = stringPreferencesKey("thumbnail_quality")
        val KEY_SHOW_CH_NUM_PLAYER     = booleanPreferencesKey("show_channel_numbers_in_player")
        val KEY_DEFAULT_REC_DURATION   = intPreferencesKey("default_recording_duration_min")
        val KEY_RECORD_IN_BACKGROUND   = booleanPreferencesKey("record_in_background")
    }

    init {
        viewModelScope.launch { loadAll() }
        viewModelScope.launch {
            xtreamRepository.observeHiddenCategoryIds(CategoryContentType.LIVE).collect {
                _state.value = _state.value.copy(hiddenLiveCategories = it)
            }
        }
        viewModelScope.launch {
            xtreamRepository.observeHiddenCategoryIds(CategoryContentType.MOVIES).collect {
                _state.value = _state.value.copy(hiddenMovieCategories = it)
            }
        }
        viewModelScope.launch {
            xtreamRepository.observeHiddenCategoryIds(CategoryContentType.SERIES).collect {
                _state.value = _state.value.copy(hiddenSeriesCategories = it)
            }
        }
    }

    private suspend fun loadAll() {
        val prefs = context.dataStore.data.first()
        _state.value = _state.value.copy(
            // Account
            serverUrl = prefs[LoginViewModel.SERVER_URL_KEY] ?: "",
            username  = prefs[LoginViewModel.USERNAME_KEY]   ?: "",
            password  = prefs[LoginViewModel.PASSWORD_KEY]   ?: "",
            isConnected = xtreamRepository.isConnected,
            // Multi-account
            savedAccounts   = AccountSerializer.fromJson(prefs[KEY_SAVED_ACCOUNTS] ?: "[]"),
            activeAccountId = prefs[KEY_ACTIVE_ACCOUNT_ID] ?: "",
            // Playback
            streamFormat           = prefs[KEY_STREAM_FORMAT]        ?: "ts",
            maxReconnectAttempts   = prefs[KEY_MAX_RECONNECTS]       ?: 3,
            reconnectDelayMs       = prefs[KEY_RECONNECT_DELAY]      ?: 2000,
            autoPlayNextEpisode    = prefs[KEY_AUTO_PLAY_NEXT]       ?: true,
            autoPlayCountdown      = prefs[KEY_AUTO_PLAY_COUNTDOWN]  ?: 5,
            resumePlayback         = prefs[KEY_RESUME_PLAYBACK]      ?: true,
            hardwareAccelerated    = prefs[KEY_HW_ACCELERATED]       ?: true,
            liveBufferPreset       = prefs[KEY_LIVE_BUFFER_PRESET]   ?: "Medium",
            audioOffsetMs          = prefs[KEY_AUDIO_OFFSET_MS]      ?: 0,
            // EPG
            epgRefreshIntervalHours= prefs[KEY_EPG_REFRESH_HOURS]    ?: 4,
            epgTimeOffsetHours     = prefs[KEY_EPG_TIME_OFFSET]      ?: 0,
            epgHoursToShow         = prefs[KEY_EPG_HOURS_TO_SHOW]    ?: 4,
            epgTimeFormat          = prefs[KEY_EPG_TIME_FORMAT]       ?: "24h",
            epgShowDescriptions    = prefs[KEY_EPG_DESCRIPTIONS]     ?: true,
            epgCompactMode         = prefs[KEY_EPG_COMPACT]          ?: false,
            epgThirdPartyEnabled   = prefs[KEY_EPG_THIRD_PARTY_ENABLED] ?: true,
            epgThirdPartyUrl       = prefs[KEY_EPG_THIRD_PARTY_URL]   ?: DEFAULT_THIRD_PARTY_EPG_URL,
            // Appearance
            appTheme               = appThemeFromName(prefs[KEY_APP_THEME]),
            accentColorHex         = prefs[KEY_ACCENT_COLOR]         ?: "#06B6D4",
            fontSizeScalePct       = prefs[KEY_FONT_SCALE]           ?: 100,
            posterAspectRatio      = prefs[KEY_POSTER_ASPECT]        ?: "2:3",
            gridColumnsOverride    = prefs[KEY_GRID_COLS]            ?: 5,
            uiAnimationsEnabled    = prefs[KEY_UI_ANIMATIONS]        ?: true,
            // Subtitles
            subtitleLanguage       = prefs[KEY_SUBTITLE_LANG]        ?: "eng",
            subtitleSizeSp         = (prefs[KEY_SUBTITLE_SIZE]       ?: 24).coerceIn(10, 72),
            subtitleColorHex       = prefs[KEY_SUBTITLE_COLOR]       ?: "#FFFFFF",
            subtitleBgOpacity      = prefs[KEY_SUBTITLE_BG_OPACITY]  ?: 60,
            subtitleOutlineColorHex = prefs[KEY_SUBTITLE_OUTLINE_COLOR] ?: "#000000",
            // System
            networkCacheMs         = prefs[KEY_NETWORK_CACHE]        ?: 2000,
            hwDecodingMode         = prefs[KEY_HW_DECODING_MODE]     ?: "auto",
            deinterlaceMode        = prefs[KEY_DEINTERLACE]          ?: "blend",
            audioPassthrough       = prefs[KEY_AUDIO_PASSTHROUGH]    ?: false,
            // Performance
            maxConcurrentStreams   = prefs[KEY_MAX_STREAMS]          ?: 1,
            imageCacheMb           = prefs[KEY_IMAGE_CACHE_MB]       ?: 128,
            epgCacheDurationHours  = prefs[KEY_EPG_CACHE_HOURS]      ?: 24,
            // Performance — Live TV
            liveNetworkCacheMs     = prefs[KEY_LIVE_NET_CACHE]       ?: 800,
            liveAudioBufferMs      = prefs[KEY_LIVE_AUDIO_BUFFER]    ?: 250,
            liveClockJitter        = prefs[KEY_LIVE_CLOCK_JITTER]    ?: true,
            // Performance — VOD
            vodNetworkCacheMs      = prefs[KEY_VOD_NET_CACHE]        ?: 2000,
            vodFileCacheMs         = prefs[KEY_VOD_FILE_CACHE]       ?: 1500,
            vodPreferHwDecode      = prefs[KEY_VOD_PREFER_HW]        ?: true,
            // Performance — Memory
            thumbnailCacheMb       = prefs[KEY_THUMBNAIL_CACHE_MB]   ?: 64,
            bitmapPoolMb           = prefs[KEY_BITMAP_POOL_MB]       ?: 32,
            responseCacheMb        = prefs[KEY_RESPONSE_CACHE_MB]    ?: 50,
            // Performance — DVR
            maxSimultaneousDvr     = prefs[KEY_MAX_SIMULTANEOUS_DVR] ?: 1,
            // DVR settings
            dvrPreBufferMinutes    = prefs[KEY_DVR_PRE_BUFFER]        ?: 2,
            dvrPostBufferMinutes   = prefs[KEY_DVR_POST_BUFFER]       ?: 5,
            dvrAutoDeleteCompleted = prefs[KEY_DVR_AUTO_DELETE]       ?: false,
            dvrStorageQuotaGb      = prefs[KEY_DVR_QUOTA_GB]          ?: 10,
            dvrNotificationsEnabled = prefs[KEY_DVR_NOTIFICATIONS]    ?: true,
            dvrDefaultQuality      = prefs[KEY_DVR_DEFAULT_QUALITY]   ?: "Original",
            dvrStoragePath         = prefs[KEY_DVR_STORAGE_PATH],
            timeshiftEnabled       = prefs[KEY_TIMESHIFT_ENABLED]     ?: false,
            adaptiveBuffer         = prefs[KEY_ADAPTIVE_BUFFER]       ?: true,
            bufferFloorMs          = (prefs[KEY_BUFFER_FLOOR_MS]      ?: 600).coerceIn(300, 2_000),
            bufferCeilingMs        = (prefs[KEY_BUFFER_CEILING_MS]    ?: 4000).coerceIn(600, 8_000),
            timeshiftWindowMinutes = (prefs[KEY_TIMESHIFT_WINDOW_MIN] ?: 30).coerceIn(0, 480),
            // Guide display
            guideHoursToShow       = prefs[KEY_GUIDE_HOURS]          ?: 4,
            guideRowHeightMode     = prefs[KEY_GUIDE_ROW_HEIGHT]      ?: "Normal",
            guideEpgPreloadRows    = prefs[KEY_GUIDE_EPG_PRELOAD]     ?: 80,
            guideShowChannelNumbers = prefs[KEY_GUIDE_SHOW_CH_NUM]   ?: true,
            guideAutoScrollToNow   = prefs[KEY_GUIDE_AUTO_SCROLL]     ?: true,
            // Category customization
            usEnFirstLive          = prefs[KEY_US_EN_FIRST_LIVE]      ?: true,
            usEnFirstMovies        = prefs[KEY_US_EN_FIRST_MOVIES]    ?: true,
            usEnFirstSeries        = prefs[KEY_US_EN_FIRST_SERIES]    ?: true,
            hiddenLiveCategories   = prefs[KEY_HIDDEN_LIVE_CATS]      ?: emptySet(),
            hiddenMovieCategories  = prefs[KEY_HIDDEN_MOVIE_CATS]     ?: emptySet(),
            hiddenSeriesCategories = prefs[KEY_HIDDEN_SERIES_CATS]    ?: emptySet(),
            // Parental controls
            parentalEnabled        = prefs[KEY_PARENTAL_ENABLED]      ?: false,
            parentalPin            = prefs[KEY_PARENTAL_PIN]          ?: "",
            lockedLiveCategories   = prefs[KEY_LOCKED_LIVE_CATS]      ?: emptySet(),
            lockedMovieCategories  = prefs[KEY_LOCKED_MOVIE_CATS]     ?: emptySet(),
            lockedSeriesCategories = prefs[KEY_LOCKED_SERIES_CATS]    ?: emptySet(),
            activeProfileId        = prefs[KEY_ACTIVE_PROFILE_ID]     ?: "default",
            profileIds             = (prefs[KEY_PROFILE_IDS] ?: setOf("default")).toList().sorted(),
            // LibVLC engine options
            vlcAudioOutput         = prefs[KEY_VLC_AUDIO_OUTPUT]      ?: "android_audiotrack",
            vlcAudioResampler      = prefs[KEY_VLC_AUDIO_RESAMPLER]   ?: "ugly",
            vlcChromaFormat        = prefs[KEY_VLC_CHROMA_FORMAT]     ?: "RV16",
            vlcSubtitleEncoding    = prefs[KEY_VLC_SUB_ENCODING]      ?: "auto",
            vlcDropLateFrames      = prefs[KEY_VLC_DROP_LATE_FRAMES]  ?: true,
            vlcSkipFrames          = prefs[KEY_VLC_SKIP_FRAMES]       ?: true,
            vlcHwSubtitles         = prefs[KEY_VLC_HW_SUBTITLES]      ?: false,
            vlcClockJitterMs       = prefs[KEY_VLC_CLOCK_JITTER_MS]   ?: 0,
            vlcNetworkMtu          = prefs[KEY_VLC_NETWORK_MTU]       ?: 0,
            vlcVerboseLog          = prefs[KEY_VLC_VERBOSE_LOG]       ?: false,
            // Player Experience
            skipIntervalSeconds        = prefs[KEY_SKIP_INTERVAL]          ?: 10,
            controlsAutoHideSeconds    = prefs[KEY_CONTROLS_AUTOHIDE]      ?: 5,
            preferredAudioLanguage     = prefs[KEY_PREFERRED_AUDIO_LANG]   ?: "auto",
            zappingOsdDurationSeconds  = prefs[KEY_ZAPPING_OSD_DURATION]   ?: 6,
            playerOverlayOpacity       = prefs[KEY_PLAYER_OVERLAY_OPACITY] ?: 80,
            showClockInPlayer          = prefs[KEY_SHOW_CLOCK_IN_PLAYER]   ?: true,
            keepScreenOnDuringPlayback = prefs[KEY_KEEP_SCREEN_ON]         ?: true,
            continueWatchingEnabled    = prefs[KEY_CONTINUE_WATCHING]      ?: true,
            thumbnailQuality           = prefs[KEY_THUMBNAIL_QUALITY]      ?: "Medium",
            showChannelNumbersInPlayer = prefs[KEY_SHOW_CH_NUM_PLAYER]     ?: false,
            defaultRecordingDurationMin= prefs[KEY_DEFAULT_REC_DURATION]   ?: 60,
            recordInBackground         = prefs[KEY_RECORD_IN_BACKGROUND]   ?: true
        )
        xtreamRepository.importHiddenCategoriesIfEmpty(
            CategoryContentType.LIVE,
            prefs[KEY_HIDDEN_LIVE_CATS] ?: emptySet()
        )
        xtreamRepository.importHiddenCategoriesIfEmpty(
            CategoryContentType.MOVIES,
            prefs[KEY_HIDDEN_MOVIE_CATS] ?: emptySet()
        )
        xtreamRepository.importHiddenCategoriesIfEmpty(
            CategoryContentType.SERIES,
            prefs[KEY_HIDDEN_SERIES_CATS] ?: emptySet()
        )
        autoRegisterCurrentLoginIfNeeded(prefs)
    }

    /**
     * If the user is connected but their credentials are not yet in [savedAccounts]
     * (e.g. they logged in before the multi-account feature existed), create a
     * [SavedAccount] automatically so the Account tab always shows a signed-in server.
     */
    private suspend fun autoRegisterCurrentLoginIfNeeded(
        prefs: androidx.datastore.preferences.core.Preferences
    ) {
        val serverUrl = prefs[LoginViewModel.SERVER_URL_KEY] ?: ""
        val username  = prefs[LoginViewModel.USERNAME_KEY]   ?: ""
        val password  = prefs[LoginViewModel.PASSWORD_KEY]   ?: ""
        if (!xtreamRepository.isConnected || serverUrl.isBlank() || username.isBlank()) return

        val current  = _state.value.savedAccounts
        val activeId = _state.value.activeAccountId

        val alreadySaved = current.any {
            it.serverUrl.trimEnd('/') == serverUrl.trimEnd('/') && it.username == username
        }
        if (!alreadySaved) {
            // Auto-create a SavedAccount so the Account tab reflects the current login
            val autoAccount = SavedAccount(
                id          = java.util.UUID.randomUUID().toString(),
                nickname    = serverUrl.removePrefix("http://").removePrefix("https://")
                                  .substringBefore(":").substringBefore("/").take(24),
                serverUrl   = serverUrl,
                username    = username,
                password    = password,
                accountType = "both"
            )
            val updated = (current + autoAccount).takeLast(10)
            context.dataStore.edit { p ->
                p[KEY_SAVED_ACCOUNTS]    = AccountSerializer.toJson(updated)
                p[KEY_ACTIVE_ACCOUNT_ID] = autoAccount.id
            }
            _state.value = _state.value.copy(
                savedAccounts   = updated,
                activeAccountId = autoAccount.id
            )
        } else if (activeId.isBlank()) {
            // Account already saved but active ID not set — fix it
            val match = current.firstOrNull {
                it.serverUrl.trimEnd('/') == serverUrl.trimEnd('/') && it.username == username
            }
            if (match != null) {
                context.dataStore.edit { p -> p[KEY_ACTIVE_ACCOUNT_ID] = match.id }
                _state.value = _state.value.copy(activeAccountId = match.id)
            }
        }
    }

    /** Persist and apply the selected app theme. */
    fun setAppTheme(theme: AppTheme) {
        _state.value = _state.value.copy(appTheme = theme)
        viewModelScope.launch {
            context.dataStore.edit { it[KEY_APP_THEME] = theme.name }
        }
    }

    /** Load categories from server for the Settings > Categories tab UI. */
    fun loadCategoriesForSettings() {
        if (!xtreamRepository.isConnected || _state.value.categoriesLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(categoriesLoading = true)
            try {
                val live   = xtreamRepository.getLiveCategories().getOrDefault(emptyList())
                val movies = xtreamRepository.getVodCategories().getOrDefault(emptyList())
                val series = xtreamRepository.getSeriesCategories().getOrDefault(emptyList())
                _state.value = _state.value.copy(
                    availableLiveCategories   = sortCategories(live,   usEnFirst = _state.value.usEnFirstLive),
                    availableMovieCategories  = sortCategories(movies, usEnFirst = _state.value.usEnFirstMovies),
                    availableSeriesCategories = sortCategories(series, usEnFirst = _state.value.usEnFirstSeries),
                    categoriesLoading = false
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to load categories for settings")
                _state.value = _state.value.copy(categoriesLoading = false)
            }
        }
    }

    // ── Account setters ───────────────────────────────────────────────────────
    fun setServerUrl(v: String) { _state.value = _state.value.copy(serverUrl = v, statusMessage = null) }
    fun setUsername(v: String)  { _state.value = _state.value.copy(username = v,  statusMessage = null) }
    fun setPassword(v: String)  { _state.value = _state.value.copy(password = v,  statusMessage = null) }

    // ── Multi-account management ──────────────────────────────────────────────

    /** Open the add-new-account editor (blank). */
    fun openAddAccount() {
        _state.value = _state.value.copy(
            editingAccount = SavedAccount(
                id = java.util.UUID.randomUUID().toString(),
                nickname = "", serverUrl = "", username = "", password = ""
            )
        )
    }

    /** Open the editor for an existing account. */
    fun openEditAccount(account: SavedAccount) {
        _state.value = _state.value.copy(editingAccount = account)
    }

    /** Close the editor without saving. */
    fun cancelEditAccount() {
        _state.value = _state.value.copy(editingAccount = null)
    }

    /** Save (add or update) the account currently in the editor. */
    fun saveEditingAccount(updated: SavedAccount) {
        val current = _state.value.savedAccounts.toMutableList()
        val idx = current.indexOfFirst { it.id == updated.id }
        if (idx >= 0) current[idx] = updated else current.add(updated)
        // Hard cap at 10 accounts
        val trimmed = current.takeLast(10)
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[KEY_SAVED_ACCOUNTS] = AccountSerializer.toJson(trimmed)
            }
        }
        _state.value = _state.value.copy(savedAccounts = trimmed, editingAccount = null)
    }

    /** Delete a saved account by ID. If it was active, clear active state too. */
    fun deleteAccount(accountId: String) {
        val updated = _state.value.savedAccounts.filter { it.id != accountId }
        val newActiveId = if (_state.value.activeAccountId == accountId) "" else _state.value.activeAccountId
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                prefs[KEY_SAVED_ACCOUNTS]    = AccountSerializer.toJson(updated)
                prefs[KEY_ACTIVE_ACCOUNT_ID] = newActiveId
            }
        }
        _state.value = _state.value.copy(savedAccounts = updated, activeAccountId = newActiveId)
    }

    /**
     * Switch to a saved account — loads its credentials into the connection fields,
     * saves them to DataStore, and reconnects.
     */
    fun switchToAccount(account: SavedAccount, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _state.value = _state.value.copy(
                isLoading = true,
                statusMessage = "Connecting to ${account.nickname.ifBlank { account.serverUrl }}...",
                isError = false
            )
            val (ok, msg) = xtreamRepository.testConnection(account.serverUrl, account.username, account.password)
            if (ok) {
                context.dataStore.edit { prefs ->
                    prefs[LoginViewModel.SERVER_URL_KEY] = account.serverUrl.trimEnd('/')
                    prefs[LoginViewModel.USERNAME_KEY] = account.username
                    prefs[LoginViewModel.PASSWORD_KEY] = account.password
                    prefs[LoginViewModel.LOGIN_MODE_KEY] = "XTREAM"
                    prefs[KEY_ACTIVE_ACCOUNT_ID] = account.id
                }
                // Module 3.2 — cascade-clear the previous account's cached channels/EPG
                // before the new provider goes live so data can't leak across accounts.
                xtreamRepository.switchAccount(
                    XtreamProfile(
                        id = account.id,
                        name = account.nickname.ifBlank { "Provider" },
                        serverUrl = account.serverUrl,
                        username = account.username,
                        password = account.password,
                        isActive = true
                    )
                )
            }
            _state.value = _state.value.copy(
                serverUrl = if (ok) account.serverUrl else _state.value.serverUrl,
                username = if (ok) account.username else _state.value.username,
                password = if (ok) account.password else _state.value.password,
                activeAccountId = if (ok) account.id else _state.value.activeAccountId,
                availableLiveCategories = if (ok) emptyList() else _state.value.availableLiveCategories,
                availableMovieCategories = if (ok) emptyList() else _state.value.availableMovieCategories,
                availableSeriesCategories = if (ok) emptyList() else _state.value.availableSeriesCategories,
                isLoading = false,
                isConnected = ok || _state.value.isConnected,
                statusMessage = if (ok) {
                    "Switched to ${account.nickname.ifBlank { "provider" }}. Content is refreshing."
                } else {
                    "Switch failed: $msg"
                },
                isError = !ok
            )
            onComplete(ok)
        }
    }

    fun saveAndConnect() {
        val s = _state.value
        if (s.serverUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) {
            _state.value = s.copy(statusMessage = "Please fill in all fields", isError = true)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, statusMessage = null)
            val (ok, msg) = xtreamRepository.testConnection(s.serverUrl, s.username, s.password)
            if (ok) {
                context.dataStore.edit { prefs ->
                    prefs[LoginViewModel.SERVER_URL_KEY] = s.serverUrl.trimEnd('/')
                    prefs[LoginViewModel.USERNAME_KEY]   = s.username
                    prefs[LoginViewModel.PASSWORD_KEY]   = s.password
                    prefs[LoginViewModel.LOGIN_MODE_KEY] = "XTREAM"
                }
                // Applying new/edited credentials may point at a different provider —
                // clear the old account's cached catalog first (Module 3.2).
                xtreamRepository.switchAccount(XtreamProfile("default", "Default",
                    s.serverUrl, s.username, s.password, true))
                _state.value = _state.value.copy(
                    isLoading = false, isConnected = true,
                    statusMessage = "Connected successfully", isError = false
                )
                Timber.i("Settings: connected to ${s.serverUrl}")
            } else {
                _state.value = _state.value.copy(isLoading = false, statusMessage = msg, isError = true)
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            context.dataStore.edit { it.clear() }
            xtreamRepository.disconnect()
            _state.value = SettingsUiState()
        }
    }

    // ── Playback setters ───────────────────────────────────────────────────────
    fun setStreamFormat(v: String)         { save { it[KEY_STREAM_FORMAT]       = v }; _state.value = _state.value.copy(streamFormat = v) }
    fun setMaxReconnects(v: Int)           { save { it[KEY_MAX_RECONNECTS]      = v }; _state.value = _state.value.copy(maxReconnectAttempts = v) }
    fun setReconnectDelay(v: Int)          { save { it[KEY_RECONNECT_DELAY]     = v }; _state.value = _state.value.copy(reconnectDelayMs = v) }
    fun setAutoPlayNext(v: Boolean)        { save { it[KEY_AUTO_PLAY_NEXT]      = v }; _state.value = _state.value.copy(autoPlayNextEpisode = v) }
    fun setAutoPlayCountdown(v: Int)       { save { it[KEY_AUTO_PLAY_COUNTDOWN] = v }; _state.value = _state.value.copy(autoPlayCountdown = v) }
    fun setResumePlayback(v: Boolean)      { save { it[KEY_RESUME_PLAYBACK]     = v }; _state.value = _state.value.copy(resumePlayback = v) }
    fun setHwAccelerated(v: Boolean)       { save { it[KEY_HW_ACCELERATED]      = v }; _state.value = _state.value.copy(hardwareAccelerated = v) }
    fun setLiveBufferPreset(v: String)     { save { it[KEY_LIVE_BUFFER_PRESET]  = v }; _state.value = _state.value.copy(liveBufferPreset = v) }
    fun setAudioOffset(v: Int)             { save { it[KEY_AUDIO_OFFSET_MS]     = v }; _state.value = _state.value.copy(audioOffsetMs = v) }

    // ── EPG setters ────────────────────────────────────────────────────────────
    fun setEpgRefreshHours(v: Int)         { save { it[KEY_EPG_REFRESH_HOURS]   = v }; _state.value = _state.value.copy(epgRefreshIntervalHours = v) }
    fun setEpgTimeOffsetHours(v: Int)      {
        save {
            it[KEY_EPG_TIME_OFFSET] = v
            // Force XMLTV re-fetch with new offset on next Guide view
            it[stringPreferencesKey("xmltv_last_fetch_ms")] = "0"
        }
        xtreamRepository.clearEpgMemoryCache()
        _state.value = _state.value.copy(epgTimeOffsetHours = v)
    }
    fun setEpgHoursToShow(v: Int)          { save { it[KEY_EPG_HOURS_TO_SHOW]   = v }; _state.value = _state.value.copy(epgHoursToShow = v) }
    fun setEpgTimeFormat(v: String)        {
        save { it[KEY_EPG_TIME_FORMAT] = v }
        _state.value = _state.value.copy(epgTimeFormat = v)
    }
    fun setEpgShowDescriptions(v: Boolean) { save { it[KEY_EPG_DESCRIPTIONS]    = v }; _state.value = _state.value.copy(epgShowDescriptions = v) }
    fun setEpgCompactMode(v: Boolean)      { save { it[KEY_EPG_COMPACT]         = v }; _state.value = _state.value.copy(epgCompactMode = v) }
    fun setEpgThirdPartyEnabled(v: Boolean) { save { it[KEY_EPG_THIRD_PARTY_ENABLED] = v }; _state.value = _state.value.copy(epgThirdPartyEnabled = v) }
    fun setEpgThirdPartyUrl(v: String) {
        val clean = v.trim()
        save { it[KEY_EPG_THIRD_PARTY_URL] = clean }
        _state.value = _state.value.copy(epgThirdPartyUrl = clean)
    }
    fun resetEpgThirdPartyUrl() = setEpgThirdPartyUrl(DEFAULT_THIRD_PARTY_EPG_URL)

    // ── Appearance setters ─────────────────────────────────────────────────────
    fun setAccentColor(v: String)          { save { it[KEY_ACCENT_COLOR]        = v }; _state.value = _state.value.copy(accentColorHex = v) }
    fun setFontScale(v: Int)               { save { it[KEY_FONT_SCALE]          = v }; _state.value = _state.value.copy(fontSizeScalePct = v) }
    fun setPosterAspect(v: String)         { save { it[KEY_POSTER_ASPECT]       = v }; _state.value = _state.value.copy(posterAspectRatio = v) }
    fun setGridCols(v: Int)                { save { it[KEY_GRID_COLS]           = v }; _state.value = _state.value.copy(gridColumnsOverride = v) }
    fun setUiAnimations(v: Boolean)        { save { it[KEY_UI_ANIMATIONS]       = v }; _state.value = _state.value.copy(uiAnimationsEnabled = v) }

    // ── Subtitles setters ──────────────────────────────────────────────────────
    fun setSubtitleLanguage(v: String)     { save { it[KEY_SUBTITLE_LANG]       = v }; _state.value = _state.value.copy(subtitleLanguage = v) }
    fun setSubtitleSize(v: Int)            {
        val clamped = v.coerceIn(10, 72)
        save { it[KEY_SUBTITLE_SIZE] = clamped }
        _state.value = _state.value.copy(subtitleSizeSp = clamped)
    }
    fun setSubtitleColor(v: String)        { save { it[KEY_SUBTITLE_COLOR]      = v }; _state.value = _state.value.copy(subtitleColorHex = v) }
    fun setSubtitleBgOpacity(v: Int)       { save { it[KEY_SUBTITLE_BG_OPACITY] = v }; _state.value = _state.value.copy(subtitleBgOpacity = v) }
    fun setSubtitleOutlineColor(v: String) { save { it[KEY_SUBTITLE_OUTLINE_COLOR] = v }; _state.value = _state.value.copy(subtitleOutlineColorHex = v) }

    // ── System setters ─────────────────────────────────────────────────────────
    fun setNetworkCache(v: Int)            { save { it[KEY_NETWORK_CACHE]       = v }; _state.value = _state.value.copy(networkCacheMs = v) }
    fun setHwDecodingMode(v: String)       { save { it[KEY_HW_DECODING_MODE]    = v }; _state.value = _state.value.copy(hwDecodingMode = v) }
    fun setDeinterlaceMode(v: String)      { save { it[KEY_DEINTERLACE]         = v }; _state.value = _state.value.copy(deinterlaceMode = v) }
    fun setAudioPassthrough(v: Boolean)    { save { it[KEY_AUDIO_PASSTHROUGH]   = v }; _state.value = _state.value.copy(audioPassthrough = v) }

    // ── Performance setters ────────────────────────────────────────────────────
    fun setMaxConcurrentStreams(v: Int)    { save { it[KEY_MAX_STREAMS]         = v }; _state.value = _state.value.copy(maxConcurrentStreams = v) }
    fun setImageCacheMb(v: Int)           { save { it[KEY_IMAGE_CACHE_MB]      = v }; _state.value = _state.value.copy(imageCacheMb = v) }
    fun setEpgCacheHours(v: Int)          { save { it[KEY_EPG_CACHE_HOURS]     = v }; _state.value = _state.value.copy(epgCacheDurationHours = v) }
    // Live TV performance
    fun setLiveNetworkCache(v: Int)       { save { it[KEY_LIVE_NET_CACHE]      = v }; _state.value = _state.value.copy(liveNetworkCacheMs = v) }
    fun setLiveAudioBuffer(v: Int)        { save { it[KEY_LIVE_AUDIO_BUFFER]   = v }; _state.value = _state.value.copy(liveAudioBufferMs = v) }
    fun setLiveClockJitter(v: Boolean)    { save { it[KEY_LIVE_CLOCK_JITTER]   = v }; _state.value = _state.value.copy(liveClockJitter = v) }
    // VOD performance
    fun setVodNetworkCache(v: Int)        { save { it[KEY_VOD_NET_CACHE]       = v }; _state.value = _state.value.copy(vodNetworkCacheMs = v) }
    fun setVodFileCache(v: Int)           { save { it[KEY_VOD_FILE_CACHE]      = v }; _state.value = _state.value.copy(vodFileCacheMs = v) }
    fun setVodPreferHw(v: Boolean)        { save { it[KEY_VOD_PREFER_HW]       = v }; _state.value = _state.value.copy(vodPreferHwDecode = v) }
    // Memory performance
    fun setThumbnailCacheMb(v: Int)       { save { it[KEY_THUMBNAIL_CACHE_MB]  = v }; _state.value = _state.value.copy(thumbnailCacheMb = v) }
    fun setBitmapPoolMb(v: Int)           { save { it[KEY_BITMAP_POOL_MB]      = v }; _state.value = _state.value.copy(bitmapPoolMb = v) }
    fun setResponseCacheMb(v: Int)        { save { it[KEY_RESPONSE_CACHE_MB]   = v }; _state.value = _state.value.copy(responseCacheMb = v) }
    // DVR performance
    fun setMaxSimultaneousDvr(v: Int)     { save { it[KEY_MAX_SIMULTANEOUS_DVR]= v }; _state.value = _state.value.copy(maxSimultaneousDvr = v) }

    // ── DVR Settings setters ───────────────────────────────────────────────────
    fun setDvrPreBuffer(v: Int)           { save { it[KEY_DVR_PRE_BUFFER]       = v }; _state.value = _state.value.copy(dvrPreBufferMinutes = v) }
    fun setDvrPostBuffer(v: Int)          { save { it[KEY_DVR_POST_BUFFER]      = v }; _state.value = _state.value.copy(dvrPostBufferMinutes = v) }
    fun setDvrAutoDelete(v: Boolean)      { save { it[KEY_DVR_AUTO_DELETE]      = v }; _state.value = _state.value.copy(dvrAutoDeleteCompleted = v) }
    fun setDvrStorageQuota(v: Int)        { save { it[KEY_DVR_QUOTA_GB]         = v }; _state.value = _state.value.copy(dvrStorageQuotaGb = v) }
    fun setDvrNotifications(v: Boolean)   { save { it[KEY_DVR_NOTIFICATIONS]    = v }; _state.value = _state.value.copy(dvrNotificationsEnabled = v) }
    fun setDvrDefaultQuality(v: String)   { save { it[KEY_DVR_DEFAULT_QUALITY]  = v }; _state.value = _state.value.copy(dvrDefaultQuality = v) }
    /** Persists the SAF/OTG tree URI to the shared DataStore key used by DvrViewModel. */
    fun setDvrStoragePath(uriString: String?) {
        viewModelScope.launch {
            context.dataStore.edit { prefs ->
                if (uriString != null) prefs[KEY_DVR_STORAGE_PATH] = uriString
                else prefs.remove(KEY_DVR_STORAGE_PATH)
            }
        }
        _state.value = _state.value.copy(dvrStoragePath = uriString)
    }
    /** Clears the custom DVR storage path — resets to internal app files dir. */
    fun clearDvrStoragePath() = setDvrStoragePath(null)
    /** Enable/disable the live-TV timeshift ring buffer. */
    fun setTimeshiftEnabled(v: Boolean) {
        save { it[KEY_TIMESHIFT_ENABLED] = v }
        _state.value = _state.value.copy(timeshiftEnabled = v)
    }

    // ── v5.0 Adaptive Buffer + timeshift window setters ────────────────────────
    fun setAdaptiveBuffer(v: Boolean) {
        save { it[KEY_ADAPTIVE_BUFFER] = v }
        _state.value = _state.value.copy(adaptiveBuffer = v)
    }
    fun setBufferFloor(v: Int) {
        val clamped = v.coerceIn(300, 2_000)
        save { it[KEY_BUFFER_FLOOR_MS] = clamped }
        _state.value = _state.value.copy(bufferFloorMs = clamped)
    }
    fun setBufferCeiling(v: Int) {
        val clamped = v.coerceIn(600, 8_000)
        save { it[KEY_BUFFER_CEILING_MS] = clamped }
        _state.value = _state.value.copy(bufferCeilingMs = clamped)
    }
    fun setTimeshiftWindowMinutes(v: Int) {
        val clamped = v.coerceIn(0, 480)
        save { it[KEY_TIMESHIFT_WINDOW_MIN] = clamped }
        _state.value = _state.value.copy(timeshiftWindowMinutes = clamped)
    }
    // ── Guide display setters ─────────────────────────────────────────────────────
    fun setGuideHoursToShow(v: Int)        { save { it[KEY_GUIDE_HOURS]         = v }; _state.value = _state.value.copy(guideHoursToShow = v) }
    fun setGuideRowHeightMode(v: String)   { save { it[KEY_GUIDE_ROW_HEIGHT]    = v }; _state.value = _state.value.copy(guideRowHeightMode = v) }
    fun setGuideEpgPreloadRows(v: Int)     { save { it[KEY_GUIDE_EPG_PRELOAD]   = v }; _state.value = _state.value.copy(guideEpgPreloadRows = v) }
    fun setGuideShowChannelNumbers(v: Boolean) { save { it[KEY_GUIDE_SHOW_CH_NUM] = v }; _state.value = _state.value.copy(guideShowChannelNumbers = v) }
    fun setGuideAutoScrollToNow(v: Boolean)    { save { it[KEY_GUIDE_AUTO_SCROLL] = v }; _state.value = _state.value.copy(guideAutoScrollToNow = v) }
    // ── Category customization setters ─────────────────────────────────────────
    fun setUsEnFirstLive(v: Boolean) {
        save { it[KEY_US_EN_FIRST_LIVE] = v }
        _state.value = _state.value.copy(
            usEnFirstLive = v,
            availableLiveCategories = sortCategories(_state.value.availableLiveCategories, usEnFirst = v)
        )
    }
    fun setUsEnFirstMovies(v: Boolean) {
        save { it[KEY_US_EN_FIRST_MOVIES] = v }
        _state.value = _state.value.copy(
            usEnFirstMovies = v,
            availableMovieCategories = sortCategories(_state.value.availableMovieCategories, usEnFirst = v)
        )
    }
    fun setUsEnFirstSeries(v: Boolean) {
        save { it[KEY_US_EN_FIRST_SERIES] = v }
        _state.value = _state.value.copy(
            usEnFirstSeries = v,
            availableSeriesCategories = sortCategories(_state.value.availableSeriesCategories, usEnFirst = v)
        )
    }

    fun toggleHiddenLiveCategory(categoryId: String) {
        viewModelScope.launch {
            xtreamRepository.toggleCategoryVisibility(CategoryContentType.LIVE, categoryId)
        }
    }
    fun toggleHiddenMovieCategory(categoryId: String) {
        viewModelScope.launch {
            xtreamRepository.toggleCategoryVisibility(CategoryContentType.MOVIES, categoryId)
        }
    }
    fun toggleHiddenSeriesCategory(categoryId: String) {
        viewModelScope.launch {
            xtreamRepository.toggleCategoryVisibility(CategoryContentType.SERIES, categoryId)
        }
    }
    fun resetHiddenLiveCategories() = showAllCategories(CategoryContentType.LIVE)
    fun resetHiddenMovieCategories() = showAllCategories(CategoryContentType.MOVIES)
    fun resetHiddenSeriesCategories() = showAllCategories(CategoryContentType.SERIES)

    private fun showAllCategories(contentType: String) {
        viewModelScope.launch { xtreamRepository.showAllCategories(contentType) }
    }

    // ── Parental Controls ─────────────────────────────────────────────────────

    fun setParentalEnabled(enabled: Boolean) {
        save { it[KEY_PARENTAL_ENABLED] = enabled }
        _state.value = _state.value.copy(parentalEnabled = enabled)
    }

    /** Store a 4-digit PIN (raw; callers should validate length). */
    fun setParentalPin(rawPin: String) {
        val hashed = hashPin(rawPin)
        save { it[KEY_PARENTAL_PIN] = hashed }
        _state.value = _state.value.copy(parentalPin = hashed, parentalEnabled = true)
        save { it[KEY_PARENTAL_ENABLED] = true }
    }

    fun clearParentalPin() {
        save { it[KEY_PARENTAL_PIN] = ""; it[KEY_PARENTAL_ENABLED] = false }
        _state.value = _state.value.copy(parentalPin = "", parentalEnabled = false)
    }

    fun verifyParentalPin(rawPin: String): Boolean =
        _state.value.parentalPin.isNotEmpty() && hashPin(rawPin) == _state.value.parentalPin

    fun toggleLockedLiveCategory(categoryId: String) {
        val next = _state.value.lockedLiveCategories.toMutableSet().also { set ->
            if (!set.add(categoryId)) set.remove(categoryId)
        }
        _state.value = _state.value.copy(lockedLiveCategories = next)
        save { it[KEY_LOCKED_LIVE_CATS] = next }
    }

    fun toggleLockedMovieCategory(categoryId: String) {
        val next = _state.value.lockedMovieCategories.toMutableSet().also { set ->
            if (!set.add(categoryId)) set.remove(categoryId)
        }
        _state.value = _state.value.copy(lockedMovieCategories = next)
        save { it[KEY_LOCKED_MOVIE_CATS] = next }
    }

    fun toggleLockedSeriesCategory(categoryId: String) {
        val next = _state.value.lockedSeriesCategories.toMutableSet().also { set ->
            if (!set.add(categoryId)) set.remove(categoryId)
        }
        _state.value = _state.value.copy(lockedSeriesCategories = next)
        save { it[KEY_LOCKED_SERIES_CATS] = next }
    }

    fun isLiveCategoryLocked(categoryId: String): Boolean =
        _state.value.parentalEnabled &&
            _state.value.parentalPin.isNotEmpty() &&
            categoryId in _state.value.lockedLiveCategories

    fun isMovieCategoryLocked(categoryId: String): Boolean =
        _state.value.parentalEnabled &&
            _state.value.parentalPin.isNotEmpty() &&
            categoryId in _state.value.lockedMovieCategories

    fun isSeriesCategoryLocked(categoryId: String): Boolean =
        _state.value.parentalEnabled &&
            _state.value.parentalPin.isNotEmpty() &&
            categoryId in _state.value.lockedSeriesCategories

    fun setActiveProfile(profileId: String) {
        val id = profileId.trim().ifBlank { "default" }
        val ids = (_state.value.profileIds + id).distinct().sorted()
        _state.value = _state.value.copy(activeProfileId = id, profileIds = ids)
        save {
            it[KEY_ACTIVE_PROFILE_ID] = id
            it[KEY_PROFILE_IDS] = ids.toSet()
        }
    }

    fun addProfile(name: String) {
        val id = name.trim().lowercase().replace(Regex("[^a-z0-9_-]+"), "_").ifBlank { return }
        setActiveProfile(id)
    }

    private fun hashPin(raw: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return digest.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    // ── Legacy (backward-compat) ───────────────────────────────────────────────
    fun saveCredentials(serverUrl: String, username: String, password: String) {
        _state.value = _state.value.copy(serverUrl = serverUrl, username = username, password = password)
        saveAndConnect()
    }

    // ── LibVLC engine setters ─────────────────────────────────────────────────
    fun setVlcAudioOutput(v: String)       { save { it[KEY_VLC_AUDIO_OUTPUT]     = v }; _state.value = _state.value.copy(vlcAudioOutput = v) }
    fun setVlcAudioResampler(v: String)    { save { it[KEY_VLC_AUDIO_RESAMPLER]  = v }; _state.value = _state.value.copy(vlcAudioResampler = v) }
    fun setVlcChromaFormat(v: String)      { save { it[KEY_VLC_CHROMA_FORMAT]    = v }; _state.value = _state.value.copy(vlcChromaFormat = v) }
    fun setVlcSubtitleEncoding(v: String)  { save { it[KEY_VLC_SUB_ENCODING]     = v }; _state.value = _state.value.copy(vlcSubtitleEncoding = v) }
    fun setVlcDropLateFrames(v: Boolean)   { save { it[KEY_VLC_DROP_LATE_FRAMES] = v }; _state.value = _state.value.copy(vlcDropLateFrames = v) }
    fun setVlcSkipFrames(v: Boolean)       { save { it[KEY_VLC_SKIP_FRAMES]      = v }; _state.value = _state.value.copy(vlcSkipFrames = v) }
    fun setVlcHwSubtitles(v: Boolean)      { save { it[KEY_VLC_HW_SUBTITLES]     = v }; _state.value = _state.value.copy(vlcHwSubtitles = v) }
    fun setVlcClockJitterMs(v: Int)        { save { it[KEY_VLC_CLOCK_JITTER_MS]  = v }; _state.value = _state.value.copy(vlcClockJitterMs = v) }
    fun setVlcNetworkMtu(v: Int)           { save { it[KEY_VLC_NETWORK_MTU]      = v }; _state.value = _state.value.copy(vlcNetworkMtu = v) }
    fun setVlcVerboseLog(v: Boolean)       { save { it[KEY_VLC_VERBOSE_LOG]      = v }; _state.value = _state.value.copy(vlcVerboseLog = v) }
    // ── Player Experience setters ─────────────────────────────────────────────
    fun setSkipInterval(v: Int)            { save { it[KEY_SKIP_INTERVAL]          = v }; _state.value = _state.value.copy(skipIntervalSeconds = v) }
    fun setControlsAutoHide(v: Int)        { save { it[KEY_CONTROLS_AUTOHIDE]      = v }; _state.value = _state.value.copy(controlsAutoHideSeconds = v) }
    fun setPreferredAudioLanguage(v: String) { save { it[KEY_PREFERRED_AUDIO_LANG] = v }; _state.value = _state.value.copy(preferredAudioLanguage = v) }
    fun setZappingOsdDuration(v: Int)      { save { it[KEY_ZAPPING_OSD_DURATION]   = v }; _state.value = _state.value.copy(zappingOsdDurationSeconds = v) }
    fun setPlayerOverlayOpacity(v: Int)    { save { it[KEY_PLAYER_OVERLAY_OPACITY] = v }; _state.value = _state.value.copy(playerOverlayOpacity = v) }
    fun setShowClockInPlayer(v: Boolean)   { save { it[KEY_SHOW_CLOCK_IN_PLAYER]   = v }; _state.value = _state.value.copy(showClockInPlayer = v) }
    fun setKeepScreenOn(v: Boolean)        { save { it[KEY_KEEP_SCREEN_ON]         = v }; _state.value = _state.value.copy(keepScreenOnDuringPlayback = v) }
    fun setContinueWatching(v: Boolean)    { save { it[KEY_CONTINUE_WATCHING]      = v }; _state.value = _state.value.copy(continueWatchingEnabled = v) }
    fun setThumbnailQuality(v: String)     { save { it[KEY_THUMBNAIL_QUALITY]      = v }; _state.value = _state.value.copy(thumbnailQuality = v) }
    fun setShowChannelNumInPlayer(v: Boolean) { save { it[KEY_SHOW_CH_NUM_PLAYER]  = v }; _state.value = _state.value.copy(showChannelNumbersInPlayer = v) }
    fun setDefaultRecordingDuration(v: Int){ save { it[KEY_DEFAULT_REC_DURATION]   = v }; _state.value = _state.value.copy(defaultRecordingDurationMin = v) }
    fun setRecordInBackground(v: Boolean)  { save { it[KEY_RECORD_IN_BACKGROUND]   = v }; _state.value = _state.value.copy(recordInBackground = v) }

    /**
     * Returns the LibVLC init option list for LIVE streams based on current settings.
     * Called by PlayerScreen before creating LibVLC to apply user preferences.
     */
    fun getLibVlcLiveArgs(): List<String> {
        val s = _state.value
        return buildList {
            add("--network-caching=${s.liveNetworkCacheMs}")
            add("--live-caching=${s.liveNetworkCacheMs}")
            add("--clock-jitter=${s.vlcClockJitterMs}")
            add("--clock-synchro=0")
            if (s.vlcDropLateFrames) add("--drop-late-frames") else add("--no-drop-late-frames")
            if (s.vlcSkipFrames) add("--skip-frames") else add("--no-skip-frames")
            add("--aout=${s.vlcAudioOutput}")
            add("--audio-resampler=${s.vlcAudioResampler}")
            add("--android-display-chroma=${s.vlcChromaFormat}")
            if (s.vlcSubtitleEncoding != "auto") add("--sub-text-encoding=${s.vlcSubtitleEncoding}")
            if (s.vlcNetworkMtu > 0) add("--mtu=${s.vlcNetworkMtu}")
            if (s.vlcVerboseLog) add("--verbose=2") else add("--no-stats")
        }
    }

    /**
     * Returns the LibVLC init option list for VOD/Series streams based on current settings.
     */
    fun getLibVlcVodArgs(): List<String> {
        val s = _state.value
        return buildList {
            add("--network-caching=${s.vodNetworkCacheMs}")
            add("--file-caching=${s.vodFileCacheMs}")
            add("--disc-caching=${s.vodFileCacheMs}")
            add("--clock-jitter=${s.vlcClockJitterMs}")
            add("--clock-synchro=0")
            add("--no-drop-late-frames")
            add("--no-skip-frames")
            add("--aout=${s.vlcAudioOutput}")
            add("--audio-resampler=${s.vlcAudioResampler}")
            add("--android-display-chroma=${s.vlcChromaFormat}")
            if (s.vlcSubtitleEncoding != "auto") add("--sub-text-encoding=${s.vlcSubtitleEncoding}")
            if (s.vlcNetworkMtu > 0) add("--mtu=${s.vlcNetworkMtu}")
            if (s.vlcVerboseLog) add("--verbose=2") else add("--no-stats")
        }
    }

    // ── Internal helper ────────────────────────────────────────────────────────
    private fun save(block: (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            context.dataStore.edit { block(it) }
        }
    }
}
