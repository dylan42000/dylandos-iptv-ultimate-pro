// ─── DYLANDOS IPTV ULTIMATE — Electron Preload Script ───────────────────────
const { contextBridge, ipcRenderer } = require('electron');

// Set-based whitelist for O(1) lookup instead of O(n) array.includes()
const validReceiveChannels = new Set([
  'window:maximize-change',
  'window:before-close',
  'app:deep-link',
  'tray:play-pause',
  'update:checking',
  'update:available',
  'update:not-available',
  'update:download-progress',
  'update:downloaded',
  'update:error',
  // ── DVR events ─────────────────────────────────────────────────────────────
  'dvr:started',
  'dvr:progress',
  'dvr:completed',
  // ── MPV events ──────────────────────────────────────────────────────────────
  'mpv:ready',
  'mpv:unavailable',
  'mpv:loading',
  'mpv:playback-started',
  'mpv:playback-stopped',
  'mpv:paused',
  'mpv:resumed',
  'mpv:timeposition',
  'mpv:status',
  'mpv:poll',
  'mpv:error',
  'mpv:crashed',
  'mpv:reconnecting',
  'mpv:vod-ended',
  'mpv:stopped',
  'mpv:hwdec-fallback',
  'mpv:screenshot-taken',
  // ── Media key events ────────────────────────────────────────────────────────
  'media-key:play-pause',
  'media-key:stop',
  'media-key:next-channel',
  'media-key:prev-channel',
]);

// Set-based whitelist for invoke channels
const validInvokeChannels = new Set([
  'window:minimize',
  'window:maximize',
  'window:close',
  'window:is-maximized',
  'window:force-repaint',
  'fs:read-data',
  'fs:write-data',
  'net:fetch-json',
  'net:fetch-text',
  'settings:sync',
  'taskbar:set-progress',
  'taskbar:flash',
  'power:prevent-sleep',
  'power:allow-sleep',
  'shell:open-external',
  'shell:open-path',
  'shell:openFile',
  'shell:showItemInFolder',
  'update:check',
  'update:install-and-restart',
  // ── DVR controls ───────────────────────────────────────────────────────────
  'dvr:start',
  'dvr:stop',
  'dvr:stop-all',
  'dvr:list-active',
  'dvr:list-library',
  'dvr:delete',
  'dvr:set-output-dir',
  'dvr:status',
  'dvr:check-ffmpeg',
  // ── MPV controls ────────────────────────────────────────────────────────────
  'mpv:load-live',
  'mpv:load-vod',
  'mpv:play',
  'mpv:pause',
  'mpv:toggle-pause',
  'mpv:stop',
  'mpv:seek',
  'mpv:seek-relative',
  'mpv:jump-to-live',
  'mpv:set-volume',
  'mpv:set-mute',
  'mpv:set-fullscreen',
  'mpv:set-audio-track',
  'mpv:set-sub-track',
  'mpv:set-audio-delay',
  'mpv:apply-subtitle-settings',
  'mpv:screenshot',
  'mpv:get-state',
  'mpv:send-raw-command',
  // ── MPV video canvas window control ───────────────────────────────────
  'mpv:show-video-window',
  'mpv:hide-video-window',
  // ── Network abort ─────────────────────────────────────────────────────
  'net:abort',
  // ── PiP / VOD (Phase A: whitelist so invoke isn't blocked if UI uses them)
  'pip:open',
  'pip:close',
  'vod:download',
  'vod:cancel',
  // ── Cloud backup dialogs ──────────────────────────────────────────────
  'dialog:show-save',
  'dialog:show-open',
  'fs:write-file',
  'fs:read-file',
]);

// Allowlisted receive channels for PiP/VOD progress (optional UI)
validReceiveChannels.add('pip:closed');
validReceiveChannels.add('vod:progress');
validReceiveChannels.add('mpv:hwdec-fallback');

const listeners = new Map();

contextBridge.exposeInMainWorld('electronAPI', {
  platform: process.platform,

  // Receive events from main process
  on: (channel, callback) => {
    if (!validReceiveChannels.has(channel)) {
      console.warn(`[preload] Blocked receive on invalid channel: ${channel}`);
      return;
    }
    const wrappedCallback = (_event, ...args) => callback(...args);
    const existing = listeners.get(callback) || [];
    existing.push({ channel, wrappedCallback });
    listeners.set(callback, existing);
    ipcRenderer.on(channel, wrappedCallback);
  },

  off: (channel, callback) => {
    if (!validReceiveChannels.has(channel)) return;
    const existing = listeners.get(callback);
    if (existing) {
      const match = existing.find(e => e.channel === channel);
      if (match) {
        ipcRenderer.removeListener(channel, match.wrappedCallback);
      }
    }
  },

  // Invoke main process handlers (request/response)
  invoke: (channel, ...args) => {
    if (!validInvokeChannels.has(channel)) {
      console.warn(`[preload] Blocked invoke on invalid channel: ${channel}`);
      return Promise.reject(new Error(`Invalid channel: ${channel}`));
    }
    return ipcRenderer.invoke(channel, ...args);
  },

  // Window controls
  window: {
    minimize: () => ipcRenderer.invoke('window:minimize'),
    maximize: () => ipcRenderer.invoke('window:maximize'),
    close: () => ipcRenderer.invoke('window:close'),
    isMaximized: () => ipcRenderer.invoke('window:is-maximized'),
  },

  // File system (scoped to app data)
  fs: {
    readData: (filename) => ipcRenderer.invoke('fs:read-data', filename),
    writeData: (filename, data) => ipcRenderer.invoke('fs:write-data', filename, data),
  },

  net: {
    fetchJson: (url, headers, timeoutMs, requestId) =>
      ipcRenderer.invoke('net:fetch-json', { url, headers, timeoutMs, requestId }),
    fetchText: (url, headers, timeoutMs, requestId) =>
      ipcRenderer.invoke('net:fetch-text', { url, headers, timeoutMs, requestId }),
    abort: (requestId) => ipcRenderer.invoke('net:abort', { requestId }),
  },

  // DVR facade — matches src/types/electron.ts + dvrScheduler expectations
  dvr: {
    startRecording: (opts) => ipcRenderer.invoke('dvr:start', opts),
    stopRecording: (recordingId) => ipcRenderer.invoke('dvr:stop', recordingId),
    getRecordings: () => ipcRenderer.invoke('dvr:list-library'),
    getStatus: () => ipcRenderer.invoke('dvr:status'),
    setOutputDirectory: (dir) => ipcRenderer.invoke('dvr:set-output-dir', dir),
  },

  // Settings sync to main process
  settingsSync: (patch) => ipcRenderer.invoke('settings:sync', patch),

  // Cloud backup / restore file dialogs
  showSaveDialog: (options) => ipcRenderer.invoke('dialog:show-save', options),
  showOpenDialog: (options) => ipcRenderer.invoke('dialog:show-open', options),
  writeFile: (filePath, data) => ipcRenderer.invoke('fs:write-file', { path: filePath, data }),
  readFile: (filePath) => ipcRenderer.invoke('fs:read-file', { path: filePath }),
});
