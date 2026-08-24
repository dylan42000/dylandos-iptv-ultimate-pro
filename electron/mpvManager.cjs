// --- DYLANDOS IPTV ULTIMATE — MPV Manager (node-mpv edition) ---
// Manages MPV subprocess lifecycle via the node-mpv package.
// node-mpv v1: constructor spawns the process immediately; all set/command
// methods are synchronous; getProperty() returns a Promise.
// ----------------------------------------------------------------------
'use strict';

const path = require('path');
const os   = require('os');
const fs   = require('fs');
const { app } = require('electron');

// node-mpv is listed in package.json dependencies.
// Required lazily inside initialize() so a missing package gives a clean error.

// --- Constants ---------------------------------------------------------------
const MPV_IPC_PIPE =
  process.platform === 'win32'
    ? `\\\\.\\pipe\\dylandos-mpv-ipc-${Date.now()}`
    : `/tmp/dylandos-mpv-${Date.now()}.sock`;

const DEFAULT_HTTP_USER_AGENT =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36';
const DEFAULT_LIVE_USER_AGENT = 'IPTVSmartersPro';

// --- Helpers -----------------------------------------------------------------
function hexToMpvColor(hex) {
  const clean = String(hex || '#ffffff').replace('#', '').trim();
  if (!/^[0-9a-f]{6}$/i.test(clean)) {
    return '1.000/1.000/1.000/1.0';
  }
  const r = parseInt(clean.slice(0, 2), 16) / 255;
  const g = parseInt(clean.slice(2, 4), 16) / 255;
  const b = parseInt(clean.slice(4, 6), 16) / 255;
  return `${r.toFixed(3)}/${g.toFixed(3)}/${b.toFixed(3)}/1.0`;
}

function clampNumber(value, fallback, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, n));
}

function resolveMpvBinary() {
  const candidates = app.isPackaged
    ? [
        path.join(process.resourcesPath, 'mpv', 'mpv.exe'),
        path.join(process.resourcesPath, 'mpv.exe'),
      ]
    : [
        path.join(process.cwd(), 'vendor', 'mpv', 'mpv.exe'),
        path.join(__dirname, '..', 'vendor', 'mpv', 'mpv.exe'),
        path.join(process.cwd(), 'resources', 'mpv', 'mpv.exe'),
        path.join(
          process.cwd(), 'release', 'win-unpacked', 'resources', 'mpv', 'mpv.exe'
        ),
      ];
  for (const c of candidates) {
    if (fs.existsSync(c)) {
      console.log('[MPVManager] Found MPV binary:', c);
      return c;
    }
  }
  console.warn('[MPVManager] MPV binary not found in any candidate path');
  return null;
}

// --- MPVManager --------------------------------------------------------------
class MPVManager {
  constructor() {
    this.mpv              = null;
    this._win             = null;
    this._settings        = {};
    this._hwnd            = 0;
    this.isInitialized    = false;
    this.currentStreamUrl  = null;
    this.currentStreamType = null;
    this._statusInterval   = null;
    this._reconnTimer      = null;
    this._reconnAttempts   = 0;
    this._maxReconnAttempts = 10;
    this._baseReconnDelay  = 2000;
    // Zap load generation — ignore stale async loads from fast channel switches
    this._loadGeneration   = 0;
    /** One soft hwdec→software retry per stream URL (HEVC/decode failures). */
    this._softFallbackTried = new Set();
    this._timeshiftEnabled = true;
  }

  attachWindow(win) { this._win = win; }

  _emit(channel, data) {
    if (this._win && !this._win.isDestroyed()) {
      this._win.webContents.send(channel, data);
    }
  }
  _sendToRenderer(channel, data) { this._emit(channel, data); }

  // --- Initialize ------------------------------------------------------------
  async initialize(browserWindowOrSettings, settings, hwnd = 0) {
    if (
      browserWindowOrSettings &&
      typeof browserWindowOrSettings === 'object' &&
      browserWindowOrSettings.webContents
    ) {
      this._win      = browserWindowOrSettings;
      this._settings = settings || {};
    } else {
      this._settings = browserWindowOrSettings || {};
    }
    this._hwnd = hwnd || 0;

    const mpvHwnd = typeof this._hwnd === 'bigint'
      ? this._hwnd.toString()
      : String(this._hwnd || '').trim();
    const hasEmbeddingTarget = mpvHwnd !== '' && mpvHwnd !== '0';

    const binary = resolveMpvBinary();
    if (!binary) {
      this._emit('mpv:unavailable', { reason: 'MPV binary not found' });
      return false;
    }

    let mpvAPI;
    try {
      mpvAPI = require('node-mpv');
    } catch (err) {
      console.error('[MPVManager] Failed to require node-mpv:', err.message);
      this._emit('mpv:unavailable', { reason: 'node-mpv package not installed' });
      return false;
    }

    // node-mpv adds --input-ipc-server, --idle, --really-quiet automatically.
    const extraArgs = [
      '--keep-open=yes',
      '--no-osc',
      '--no-input-default-bindings',
      '--hr-seek=yes',
      '--screenshot-format=png',
      `--screenshot-directory=${os.homedir()}`,
      '--tls-verify=no',
      `--user-agent=${DEFAULT_LIVE_USER_AGENT}`,
      `--http-header-fields=User-Agent: ${DEFAULT_LIVE_USER_AGENT}`,
      '--stream-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_delay_max=10,rw_timeout=30000000',
      '--demuxer-lavf-o=reconnect=1,reconnect_streamed=1,reconnect_delay_max=10',
      '--demuxer-lavf-probesize=10000000',
      '--demuxer-lavf-analyzeduration=10',
      '--ytdl=no',
      '--cache=yes',
      '--cache-pause-initial=no',
      '--referrer=',
      '--network-timeout=30',
      '--cache-pause=no',
      '--focus-on=never',
    ];

    if (hasEmbeddingTarget) {
      extraArgs.push(
        `--wid=${mpvHwnd}`,
        '--vo=gpu',             // gpu is compatible with DWM child HWND embedding
        '--hwdec=auto',         // auto hardware decode; safe for embedded surfaces
        '--gpu-api=d3d11',      // D3D11 backend
        // NOTE: --gpu-context is intentionally omitted here.
        // Specifying --gpu-context=d3d11 alongside --gpu-api=d3d11 causes MPV to
        // mis-select the D3D11 present mode which results in a black video surface
        // when the renderer is embedded via --wid on Windows.
        '--d3d11-exclusive-fs=no',
        '--force-window=no',
        '--no-border',
      );
    } else {
      extraArgs.push('--vo=gpu', '--hwdec=auto');
    }

    console.log('[MPVManager] Constructing node-mpv. HWND:', mpvHwnd || '(none)', 'Binary:', binary);

    // Wait for Windows DWM to physically map the new window surface so MPV
    // has a valid DirectX swapchain target to bind to.
    // 1500ms is required on cold system restarts; 800ms was insufficient.
    await new Promise(r => setTimeout(r, 1500));

    // node-mpv v1 spawns MPV immediately in the constructor.
    this.mpv = new mpvAPI(
      {
        binary,
        socket:      MPV_IPC_PIPE,
        audio_only:  false,
        time_update: 1,
        verbose:     false,
        debug:       false,
      },
      extraArgs
    );

    this._attachEventListeners();

    // Give MPV ~1.5 s to start and the IPC pipe to become reachable.
    await new Promise(resolve => setTimeout(resolve, 1500));

    // ── Re-assert critical video output properties via IPC immediately after spawn ──
    // MPV may internally negotiate VO alternatives during startup (e.g. falling
    // back from gpu-next to gpu). Explicitly setting these properties after the
    // IPC pipe is open ensures the correct VO stays active for the session and
    // will not revert on subsequent stream loads.
    if (hasEmbeddingTarget) {
      try {
        await this.mpv.setProperty('vo', 'gpu');
        await this.mpv.setProperty('gpu-api', 'd3d11');
        await this.mpv.setProperty('hwdec', 'auto');
        console.log('[MPVManager] Video output properties locked to: vo=gpu, gpu-api=d3d11, hwdec=auto');
      } catch (e) {
        // Non-fatal — startup args are still in effect even if runtime set fails
        console.warn('[MPVManager] Could not lock VO properties via IPC:', e.message);
      }
    }

    // Observe extra properties for StreamHealthIndicator / mpv:status events.
    const extraObs = [
      ['cache-buffering-state', 50],
      ['paused-for-cache',      51],
      ['video-bitrate',         52],
      ['audio-bitrate',         53],
      ['video-codec',           54],
      ['audio-codec',           55],
      ['hwdec-current',         56],
      ['track-list',            57],
    ];
    for (const [prop, id] of extraObs) {
      try { this.mpv.observeProperty(prop, id); } catch { /* non-fatal */ }
    }

    this.isInitialized = true;
    this._emit('mpv:ready', { initialized: true });
    console.log('[MPVManager] Ready (node-mpv)');
    return true;
  }

  // --- Event Listeners -------------------------------------------------------
  _attachEventListeners() {
    this.mpv.on('started', () => {
      this._reconnAttempts = 0;
      this._emit('mpv:playback-started', {
        url:  this.currentStreamUrl,
        type: this.currentStreamType,
      });
      this._startStatusPolling();
    });

    this.mpv.on('stopped', () => {
      this._stopStatusPolling();
      this._emit('mpv:playback-stopped', {});
      if (this.currentStreamType === 'live' && this.currentStreamUrl) {
        this._handleLiveStreamEnd();
      } else {
        this._emit('mpv:vod-ended', {});
      }
    });

    this.mpv.on('paused',  () => { this._emit('mpv:paused',  {}); });
    this.mpv.on('resumed', () => { this._emit('mpv:resumed', {}); });

    this.mpv.on('crashed', () => {
      console.error('[MPVManager] MPV process crashed, node-mpv will auto-restart');
      this._emit('mpv:crashed', {});
      if (this.currentStreamUrl) {
        setTimeout(() => this._reloadCurrentStream(), 1500);
      }
    });

    this.mpv.on('statuschange', (status) => {
      this._emit('mpv:status', {
        bufferingPercent: status['cache-buffering-state'] ?? 0,
        pausedForCache:   status['paused-for-cache']      ?? false,
        videoBitrate:     status['video-bitrate']         ?? 0,
        audioBitrate:     status['audio-bitrate']         ?? 0,
        videoCodec:       status['video-codec']           ?? '',
        audioCodec:       status['audio-codec']           ?? '',
        hwdecActive:      status['hwdec-current']         ?? 'no',
        trackList:        status['track-list']            ?? [],
        duration:         status.duration                 ?? 0,
        position:         status.position                 ?? 0,
        pause:            status.pause                    ?? false,
        volume:           status.volume                   ?? 100,
        mute:             status.mute                     ?? false,
        timeshiftEnabled: this._timeshiftEnabled === true,
        streamType:       this.currentStreamType,
      });
    });
  }

  // --- Live TV Stream --------------------------------------------------------
  async loadLiveStream(url, settings = {}) {
    if (!this.isInitialized) {
      this._emit('mpv:unavailable', { reason: 'MPV not initialized' });
      return;
    }
    this._clearReconnTimer();
    this._reconnAttempts = 0;
    const loadGen = ++this._loadGeneration;
    this.currentStreamUrl  = url;
    this.currentStreamType = 'live';

    const merged  = { ...this._settings, ...settings };
    const preset  = merged.liveBufferPreset ?? 'medium';
    const timeshiftSize = merged.liveTimeshiftBufferSize ?? 'large';

    const timeshiftMap = {
      standard: { cacheSecs: 600,  maxBytes: '256MiB',  backBytes: '256MiB',  readaheadSecs: 30 },
      large:    { cacheSecs: 1800, maxBytes: '512MiB',  backBytes: '512MiB',  readaheadSecs: 60 },
      max:      { cacheSecs: 3600, maxBytes: '1024MiB', backBytes: '1024MiB', readaheadSecs: 120 },
      ultra:    { cacheSecs: 7200, maxBytes: '2048MiB', backBytes: '2048MiB', readaheadSecs: 240 },
    };

    const bufMap  = {
      low:    { cacheSecs: 15, maxBytes: '128MiB', backBytes: '64MiB',  readaheadSecs: 10 },
      medium: { cacheSecs: 30, maxBytes: '256MiB', backBytes: '128MiB', readaheadSecs: 20 },
      high:   { cacheSecs: 60, maxBytes: '512MiB', backBytes: '256MiB', readaheadSecs: 40 },
    };

    const timeshift = merged.liveTimeshiftEnabled !== false;
    this._timeshiftEnabled = timeshift;
    const buf = timeshift
      ? (timeshiftMap[timeshiftSize] ?? timeshiftMap.large)
      : (bufMap[preset] ?? bufMap.medium);

    const liveUA  = merged.liveUserAgent  || DEFAULT_LIVE_USER_AGENT;
    // Use auto (not auto-safe) — gpu-next + auto-safe are incompatible with DWM embedding.
    const hwdec   = merged.hardwareDecode !== false ? 'auto' : 'no';
    const adDelay = isFinite(merged.audioOffsetMs) ? merged.audioOffsetMs / 1000 : 0;

    console.log('[MPVManager] loadLiveStream', { url, preset, timeshiftSize, liveUA, hwdec, timeshift, buf });

    try {
      await this.mpv.setProperty('user-agent',             liveUA);
      await this.mpv.setProperty('http-header-fields',     `User-Agent: ${liveUA}`);
      await this.mpv.setProperty('stream-lavf-o',          'reconnect=1,reconnect_streamed=1,reconnect_delay_max=10,rw_timeout=30000000');
      await this.mpv.setProperty('demuxer-lavf-o',         'reconnect=1,reconnect_streamed=1,reconnect_delay_max=10');
      await this.mpv.setProperty('demuxer-lavf-probesize', '10000000');
      await this.mpv.setProperty('demuxer-lavf-analyzeduration', '10');
      await this.mpv.setProperty('ytdl',                   'no');
      await this.mpv.setProperty('cache',                  'yes');
      await this.mpv.setProperty('cache-secs',             buf.cacheSecs);
      await this.mpv.setProperty('cache-pause-initial',    'no');
      // Pause-live parity: keep demuxer back-buffer scrubbable while paused
      await this.mpv.setProperty('cache-pause',            timeshift ? 'yes' : 'no');
      await this.mpv.setProperty('cache-pause-wait',       0);
      await this.mpv.setProperty('demuxer-max-bytes',      buf.maxBytes);
      await this.mpv.setProperty('demuxer-max-back-bytes', buf.backBytes);
      await this.mpv.setProperty('demuxer-readahead-secs', buf.readaheadSecs);
      await this.mpv.setProperty('demuxer-seekable-cache', timeshift ? 'yes' : 'no');
      await this.mpv.setProperty('stream-buffer-size',     '16MiB');
      await this.mpv.setProperty('network-timeout',        30);
      await this.mpv.setProperty('hr-seek',                timeshift ? 'yes' : 'no');
      await this.mpv.setProperty('audio-delay',            adDelay);
      await this.mpv.setProperty('sid',                    'no');
      await this.applySubtitleSettings(merged);
      // NOTE: video-sync and framedrop intentionally omitted — these flags black
      // out the video pipeline when MPV is embedded in a child Windows HWND.
    } catch (err) {
      console.warn('[MPVManager] Live profile partial failure:', err.message);
    }

    // Fast zap: a newer load superseded this one while properties were applying.
    if (loadGen !== this._loadGeneration || this.currentStreamUrl !== url) {
      console.log('[MPVManager] Skipping stale live load', { loadGen, current: this._loadGeneration });
      return;
    }

    try {
      this.mpv.load(url, 'replace');
      this._emit('mpv:loading', { url, type: 'live', loadGeneration: loadGen });
    } catch (err) {
      console.error('[MPVManager] Failed to load live stream:', err);
      this._reportPlaybackError(err.message, 'live');
      this._handleLiveStreamEnd();
    }
  }

  // --- VOD / Series Stream ---------------------------------------------------
  async loadVodStream(url, startPositionSeconds = 0, settings = {}) {
    if (!this.isInitialized) {
      this._emit('mpv:unavailable', { reason: 'MPV not initialized' });
      return;
    }
    this._clearReconnTimer();
    this.currentStreamUrl  = url;
    this.currentStreamType = 'vod';

    const merged  = { ...this._settings, ...settings };
    const vodUA   = merged.vodUserAgent    || DEFAULT_HTTP_USER_AGENT;
    // Use auto for VOD too — gpu-next + auto-safe are incompatible with DWM embedding.
    const hwdec   = merged.hardwareDecode !== false ? 'auto' : 'no';
    const adDelay = isFinite(merged.audioOffsetMs) ? merged.audioOffsetMs / 1000 : 0;

    console.log('[MPVManager] loadVodStream', { url, startPositionSeconds, vodUA, hwdec });

    try {
      await this.mpv.setProperty('user-agent',             vodUA);
      await this.mpv.setProperty('cache',                  'yes');
      await this.mpv.setProperty('cache-secs',             120);
      await this.mpv.setProperty('cache-pause',            'yes');
      await this.mpv.setProperty('demuxer-max-bytes',      '200MiB');
      await this.mpv.setProperty('demuxer-max-back-bytes', '128MiB');
      await this.mpv.setProperty('demuxer-readahead-secs', 60);
      await this.mpv.setProperty('stream-buffer-size',     '12MiB');
      // NOTE: hwdec and vo intentionally NOT re-set here — same reason as loadLiveStream.
      // Resetting vo triggers a swapchain reinit that races against the incoming loadfile.
      await this.mpv.setProperty('hr-seek',                'yes');
      await this.mpv.setProperty('hr-seek-framedrop',      'yes');
      await this.mpv.setProperty('audio-delay',            adDelay);
      await this.mpv.setProperty('sid',                    'no');
      await this.applySubtitleSettings(merged);
      // NOTE: video-sync:display-resample and framedrop:vo intentionally omitted —
      // these flags black out the video pipeline when MPV is embedded in a child
      // Windows HWND.
    } catch (err) {
      console.warn('[MPVManager] VOD profile partial failure:', err.message);
    }

    try {
      // node-mpv v1: .load() only accepts (url, mode) — no third argument.
      this.mpv.load(url, 'replace');
      this._emit('mpv:loading', { url, type: 'vod', startPosition: startPositionSeconds });
      // Apply seek after MPV has had time to open the file.
      if (startPositionSeconds > 0) {
        setTimeout(async () => {
          try { await this.mpv.goToPosition(startPositionSeconds); } catch {}
        }, 1000);
      }
    } catch (err) {
      console.error('[MPVManager] Failed to load VOD stream:', err);
      this._reportPlaybackError(err.message, 'vod');
    }
  }

  // --- Playback Controls -----------------------------------------------------
  async play()            { try { this.mpv.play();        } catch {} }
  async pause()           { try { this.mpv.pause();       } catch {} }
  async togglePlayPause() { try { this.mpv.togglePause(); } catch {} }

  async stop() {
    this._clearReconnTimer();
    this.currentStreamUrl  = null;
    this.currentStreamType = null;
    try { this.mpv.stop(); } catch {}
    this._emit('mpv:stopped', {});
  }

  async seek(seconds)      { try { this.mpv.goToPosition(seconds); } catch {} }
  async seekRelative(delta){ try { this.mpv.seek(delta);           } catch {} }

  async setVolume(level) {
    const safe = (level !== undefined && isFinite(Number(level)))
      ? Math.min(1.0, Math.max(0.0, Number(level))) : 1.0;
    try { this.mpv.volume(Math.round(safe * 100)); } catch {}
  }

  async setMute(muted) {
    try { this.mpv.setProperty('mute', Boolean(muted)); } catch {}
  }

  async setFullscreen(enabled) {
    try { this.mpv.setProperty('fullscreen', enabled); } catch {}
  }

  // --- Track Selection -------------------------------------------------------
  async setAudioTrack(trackId)    { try { this.mpv.setProperty('aid', trackId); } catch {} }
  async setSubtitleTrack(trackId) { try { this.mpv.setProperty('sid', trackId); } catch {} }
  async setVideoTrack(trackId)    { try { this.mpv.setProperty('vid', trackId); } catch {} }

  async setAudioOffsetMs(offsetMs) {
    try { this.mpv.setProperty('audio-delay', isFinite(offsetMs) ? offsetMs / 1000 : 0); } catch {}
  }

  // --- Subtitle Styling ------------------------------------------------------
  async applySubtitleSettings(s) {
    const fontSize = clampNumber(s.subtitleFontSize, 24, 12, 72);
    const outline = clampNumber(s.subtitleOutlineWidth, 2, 0, 8);
    const backgroundOpacity = clampNumber(s.subtitleBackgroundOpacity, 0.5, 0, 1);
    try {
      await this.mpv.setProperty('sub-font-size', fontSize);
      await this.mpv.setProperty('sub-color', hexToMpvColor(s.subtitleFontColor ?? '#FFFFFF'));
      await this.mpv.setProperty('sub-border-size', outline);
      await this.mpv.setProperty('sub-back-color', `0.0/0.0/0.0/${backgroundOpacity}`);
      await this.mpv.setProperty('sub-shadow-offset', outline > 0 ? 2 : 0);
    } catch (err) {
      console.warn('[MPVManager] applySubtitleSettings failed:', err.message);
    }
  }

  // --- Screenshot ------------------------------------------------------------
  async takeScreenshot() {
    try {
      this.mpv.command('screenshot', ['video']);
      this._emit('mpv:screenshot-taken', { path: os.homedir() });
    } catch (err) {
      console.error('[MPVManager] Screenshot failed:', err.message);
    }
  }

  // --- Playback State --------------------------------------------------------
  async getPlaybackState() {
    if (!this.isInitialized || !this.mpv) {
      return { position: 0, duration: 0, pause: false, volume: 1, mute: false, cacheDuration: 0, cacheState: null };
    }
    try {
      const [position, duration, pause, volume, mute, cacheDuration, cacheState] = await Promise.all([
        this.mpv.getProperty('time-pos').catch(() => 0),
        this.mpv.getProperty('duration').catch(() => 0),
        this.mpv.getProperty('pause').catch(() => false),
        this.mpv.getProperty('volume').catch(() => 100),
        this.mpv.getProperty('mute').catch(() => false),
        this.mpv.getProperty('demuxer-cache-duration').catch(() => 0),
        this.mpv.getProperty('demuxer-cache-state').catch(() => null),
      ]);
      return {
        position: isFinite(position) ? position : 0,
        duration: isFinite(duration) ? duration : 0,
        pause:    Boolean(pause),
        volume:   isFinite(volume) ? volume / 100 : 1,
        mute:     Boolean(mute),
        cacheDuration: isFinite(cacheDuration) ? cacheDuration : 0,
        cacheState: typeof cacheState === 'object' ? cacheState : null,
      };
    } catch {
      return { position: 0, duration: 0, pause: false, volume: 1, mute: false, cacheDuration: 0, cacheState: null };
    }
  }

  async jumpToLive() {
    if (!this.isInitialized || !this.mpv) return;
    try {
      // In MPV for live streams, seeking forward to 999999 or 0 relative with eof flag jumps to real-time live edge
      await this.mpv.command('seek', [999999, 'absolute']);
      await this.mpv.play();
    } catch (err) {
      console.warn('[MPVManager] jumpToLive failed:', err.message);
    }
  }

  // --- Raw Command (mpv:send-command handler) --------------------------------
  async sendRawCommand(command, args = []) {
    try { return this.mpv.command(command, args); } catch (err) {
      console.warn('[MPVManager] sendRawCommand failed:', err.message);
      return null;
    }
  }

  // --- Status Polling --------------------------------------------------------
  _startStatusPolling() {
    this._stopStatusPolling();
    this._statusInterval = setInterval(async () => {
      if (!this.isInitialized) return;
      try {
        const state = await this.getPlaybackState();
        this._emit('mpv:poll', state);
      } catch { /* ignore during transitions */ }
    }, 500);
  }

  _stopStatusPolling() {
    if (this._statusInterval) {
      clearInterval(this._statusInterval);
      this._statusInterval = null;
    }
  }

  // --- Live Reconnect --------------------------------------------------------
  /**
   * Soft-fallback: on HEVC / hwdec decode failures, force software decode once
   * per stream URL, then reload. Emits mpv:hwdec-fallback for telemetry/UI.
   */
  async _tryHwdecSoftFallback(reason) {
    const url = this.currentStreamUrl;
    if (!url || !this.mpv) return false;
    const msg = String(reason || '').toLowerCase();
    const looksDecode =
      /hevc|h\.?265|h265|hwdec|d3d11|dxva|nvdec|decode|avcodec|vd-lavc/.test(msg);
    if (!looksDecode) return false;
    if (this._softFallbackTried.has(url)) return false;
    this._softFallbackTried.add(url);
    console.warn('[MPVManager] HEVC/hwdec soft-fallback → hwdec=no', reason);
    try {
      await this.mpv.setProperty('hwdec', 'no');
    } catch (err) {
      console.warn('[MPVManager] Failed to set hwdec=no:', err.message);
    }
    this._emit('mpv:hwdec-fallback', {
      reason: String(reason || '').slice(0, 200),
      codecHint: /hevc|h\.?265|h265/.test(msg) ? 'hevc' : 'unknown',
      urlHost: (() => {
        try { return new URL(url).host; } catch { return ''; }
      })(),
    });
    setTimeout(() => {
      void this._reloadCurrentStream();
    }, 400);
    return true;
  }

  _reportPlaybackError(message, type, fatal = false) {
    const msg = String(message || 'Playback error');
    void this._tryHwdecSoftFallback(msg).then((retried) => {
      if (retried) {
        this._emit('mpv:error', {
          message: `Decode fallback (software): ${msg}`,
          type,
          fatal: false,
          softFallback: true,
        });
        return;
      }
      this._emit('mpv:error', { message: msg, type, fatal });
    });
  }

  _handleLiveStreamEnd() {
    if (this._reconnAttempts >= this._maxReconnAttempts) {
      this._reportPlaybackError(
        `Reconnect failed after ${this._maxReconnAttempts} attempts`,
        'live',
        true
      );
      this._reconnAttempts = 0;
      return;
    }
    this._reconnAttempts++;
    const delay = Math.min(
      this._baseReconnDelay * Math.pow(2, this._reconnAttempts - 1),
      30000
    );
    this._emit('mpv:reconnecting', {
      attempt:     this._reconnAttempts,
      maxAttempts: this._maxReconnAttempts,
      delayMs:     delay,
      // Back-compat for any listener still reading `delay`
      delay,
    });
    console.log(`[MPVManager] Reconnecting in ${delay}ms (attempt ${this._reconnAttempts}/${this._maxReconnAttempts})`);
    this._reconnTimer = setTimeout(() => {
      if (this.currentStreamUrl && this.currentStreamType === 'live') {
        try { this.mpv.load(this.currentStreamUrl, 'replace'); }
        catch { this._handleLiveStreamEnd(); }
      }
    }, delay);
  }

  async _reloadCurrentStream() {
    if (!this.currentStreamUrl) return;
    if (this.currentStreamType === 'live') {
      await this.loadLiveStream(this.currentStreamUrl, this._settings);
    } else {
      await this.loadVodStream(this.currentStreamUrl, 0, this._settings);
    }
  }

  _clearReconnTimer() {
    if (this._reconnTimer) { clearTimeout(this._reconnTimer); this._reconnTimer = null; }
  }

  // --- Cleanup ---------------------------------------------------------------
  async destroy() {
    this._stopStatusPolling();
    this._clearReconnTimer();
    this.isInitialized = false;
    if (this.mpv) {
      try { this.mpv.quit(); } catch {}
      this.mpv = null;
    }
  }

  // --- Compat ----------------------------------------------------------------
  get isReady() { return this.isInitialized; }
  async goToPosition(seconds) { await this.seek(seconds); }
}

// Export singleton — main.cjs requires this file directly.
module.exports = new MPVManager();
