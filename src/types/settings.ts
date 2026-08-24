// ─── Application Settings Types ─────────────────────────────────────────────

export interface AppSettings {
  // ── Profile ──
  activeProfileId: string | null;

  // ── Playback ──
  volume: number;
  muted: boolean;
  liveFormat: 'ts' | 'm3u8' | 'auto';
  liveReconnectMaxAttempts: number;
  liveReconnectBaseDelayMs: number;
  hardwareAcceleration: boolean;
  hardwareDecode: boolean;
  liveBufferPreset: 'low' | 'medium' | 'high';
  vodAutoPlayNext: boolean;
  vodAutoPlayCountdown: number;
  vodResumePlayback: boolean;

  // ── EPG ──
  epgEnabled: boolean;
  epgSources: string[];
  epgRefreshHours: number;
  epgTimeFormat: '12h' | '24h';
  epgDaysToLoad: number;  // How many days of EPG data to display (1-14)

  // ── UI ──
  startFullscreen: boolean;
  theme: 'neon-cyan' | 'neon-purple' | 'neon-green' | 'dylandos-ultra' | 'dylandos-fire';
  accentColor: string;
  sidebarCollapsed: boolean;
  showStreamHealthOverlay: boolean;
  osdTimeoutMs: number;
  mediaPosterSize: 'standard' | 'large' | 'extra-large';

  // ── Subtitles ──
  subtitleFontSize: number;
  subtitleFontColor: string;
  subtitleOutlineWidth: number;
  subtitleBackgroundOpacity: number;

  // ── TMDB ──
  tmdbEnabled: boolean;
  tmdbApiKey: string;
  tmdbLanguage: string;

  // ── System ──
  minimizeToTray: boolean;
  startWithWindows: boolean;
  confirmOnExit: boolean;
  maxConcurrentStreams: number;

  // ── Audio ──
  audioOffsetMs: number;

  // ── MPV Engine ──
  preferredEngine: 'mpv' | 'hlsjs';
  mpvHwdecMode: 'auto-safe' | 'auto' | 'no';
  mpvVideoSync: 'audio' | 'display-resample' | 'display-tempo';
  mpvScaler: 'bilinear' | 'bicubic' | 'lanczos' | 'ewa_lanczossharp';
  mpvDeinterlace: boolean;
  mpvVoiceAmplify: number;   // 1.0–4.0
  mpvAudioSyncMs: number;     // -2000 to +2000 ms
  mpvScreenshotDir: string;
  mpvLogLevel: 'no' | 'error' | 'warn' | 'info' | 'debug';

  // ── DVR ──
  dvrOutputDir?: string;
  dvrFormat: 'mkv' | 'mp4' | 'ts';
  dvrPreBufferSecs: number;     // seconds to record before scheduled start
  dvrPostBufferSecs: number;    // seconds to record after scheduled end
  dvrMaxConcurrent?: number;    // 1 to 5 concurrent recordings

  // ── Timeshift ──
  liveTimeshiftBufferSize?: 'standard' | 'large' | 'max' | 'ultra';
  liveUserAgent?: string;

  // ── Playlists ──
  playlistRefreshHours: number; // 0 = manual, 6 | 12 | 24

  // ── Parental ──
  parentalControlsEnabled: boolean;
  parentalAutoLockAdult: boolean;

  // ── Content filter / category priority ──
  contentFilterEnabled: boolean;
  contentFilterMode: 'whitelist' | 'blacklist';
  contentFilterAllowed: string[];
  contentFilterBlocked: string[];
  contentFilterShowUntagged: boolean;
  categoryPriorityPrefixes: string[];
  epgUsePublicFallbacks: boolean;

  /**
   * Optional Windows reliability fallback: solid window instead of transparent
   * MPV --wid embed. Prefer env DYLANDOS_OPAQUE_WINDOW=1 for one-shot tests.
   * Restart required after changing.
   */
  opaqueWindow?: boolean;

  /** Local anonymized crash + playback failure telemetry (no credentials). */
  fieldTelemetryEnabled?: boolean;

  /**
   * Pause-live ring buffer via MPV demuxer back-cache.
   * When true, live streams keep a scrubbable back-buffer on pause.
   */
  liveTimeshiftEnabled?: boolean;
}

export const DEFAULT_SETTINGS: AppSettings = {
  activeProfileId: null,
  volume: 1.0,
  muted: false,
  liveFormat: 'auto',
  liveReconnectMaxAttempts: 5,
  liveReconnectBaseDelayMs: 2000,
  hardwareAcceleration: true,
  hardwareDecode: true,
  liveBufferPreset: 'medium',
  vodAutoPlayNext: true,
  vodAutoPlayCountdown: 10,
  vodResumePlayback: true,
  epgEnabled: true,
  epgSources: [],
  epgRefreshHours: 6,
  epgTimeFormat: '24h',
  epgDaysToLoad: 7,
  startFullscreen: false,
  theme: 'neon-cyan',
  accentColor: '#06b6d4',
  sidebarCollapsed: false,
  showStreamHealthOverlay: false,
  osdTimeoutMs: 5000,
  mediaPosterSize: 'large',
  subtitleFontSize: 24,
  subtitleFontColor: '#FFFFFF',
  subtitleOutlineWidth: 2,
  subtitleBackgroundOpacity: 0.5,
  tmdbEnabled: false,
  tmdbApiKey: '',
  tmdbLanguage: 'en-US',
  minimizeToTray: false,
  startWithWindows: false,
  confirmOnExit: false,
  maxConcurrentStreams: 1,
  audioOffsetMs: 0,
  // MPV engine defaults
  preferredEngine: 'mpv',
  mpvHwdecMode: 'auto-safe',
  mpvVideoSync: 'audio',
  mpvScaler: 'lanczos',
  mpvDeinterlace: false,
  mpvVoiceAmplify: 1.0,
  mpvAudioSyncMs: 0,
  mpvScreenshotDir: '',
  mpvLogLevel: 'error',
  // DVR defaults
  dvrOutputDir: '',
  dvrFormat: 'ts',
  dvrPreBufferSecs: 120,
  dvrPostBufferSecs: 120,
  dvrMaxConcurrent: 3,
  // Timeshift defaults
  liveTimeshiftBufferSize: 'large',
  liveUserAgent: 'IPTVSmartersPro',
  // Playlist refresh
  playlistRefreshHours: 24,
  // Parental
  parentalControlsEnabled: false,
  parentalAutoLockAdult: true,
  // Content filter
  contentFilterEnabled: false,
  contentFilterMode: 'whitelist',
  contentFilterAllowed: ['US', 'USA', 'EN', 'ENG', 'AM', 'NA', 'CA', 'UK', 'AU'],
  contentFilterBlocked: ['XXX', 'ADULT', '18', 'TEST', 'BACKUP'],
  contentFilterShowUntagged: true,
  categoryPriorityPrefixes: ['US', 'USA', 'EN', 'ENG', 'AM', 'NA', 'CA', 'UK'],
  epgUsePublicFallbacks: false,
  opaqueWindow: false,
  fieldTelemetryEnabled: true,
  liveTimeshiftEnabled: true,
};

export type AppPage =
  | 'home'
  | 'live'
  | 'guide'
  | 'movies'
  | 'series'
  | 'dvr'
  | 'search'
  | 'favorites'
  | 'settings'
  | 'downloads'
  | 'lists'
  | 'multiview';
