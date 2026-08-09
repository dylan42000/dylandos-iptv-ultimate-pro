// ─── DYLANDOS IPTV ULTIMATE — Electron Main Process ─────────────────────────
const { app, BrowserWindow, ipcMain, Tray, Menu, nativeImage, shell, powerSaveBlocker, session, globalShortcut, dialog } = require('electron');
const path = require('path');
const fs = require('fs/promises');
const fsSync = require('fs');
const zlib = require('zlib');
const { spawn } = require('child_process');
// mpvManager.cjs exports a ready-made singleton — require directly
const mpvManager = require('./mpvManager.cjs');

// ── Bootstrap config: read settings BEFORE app ready for GPU flags ───────────────
// This runs synchronously before app.whenReady() so we can set commandLine flags.
let bootstrapSettings = {};
try {
  const fsSync = require('fs');
  const osModule = require('os');
  const bootstrapPath = path.join(
    osModule.homedir(), 'AppData', 'Roaming',
    'dylandos-iptv-ultimate', 'data', 'settings.json'
  );
  if (fsSync.existsSync(bootstrapPath)) {
    bootstrapSettings = JSON.parse(fsSync.readFileSync(bootstrapPath, 'utf8'));
  }
} catch { /* ignore — use defaults */ }

// ── GPU & Media Flags ────────────────────────────────────────────────────
app.commandLine.appendSwitch('enable-gpu-rasterization');
app.commandLine.appendSwitch('enable-zero-copy');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

// IMPORTANT: Only ONE call to appendSwitch per switch name is safe.
// Multiple calls to the same switch (e.g. 'disable-features') can overwrite
// each other in some Electron versions — the LAST call wins.
// Always combine all values for the same switch into a SINGLE call.
//
// We intentionally skip VaapiVideoDecoder/VaapiVideoEncoder here — those are
// Linux/Chrome GPU decode features irrelevant on Windows, and toggling them
// was causing a duplicate `disable-features` call that overwrote
// HardwareMediaKeyHandling on every restart.
app.commandLine.appendSwitch('disable-features', 'HardwareMediaKeyHandling');

// ── Transparent window + DWM compositor flags ────────────────────────────
// ALL REQUIRED for the MPV backing window (opaque, below mainWin) to show
// through the transparent Electron overlay on Windows.
// Without enable-transparent-visuals the DWM ignores the window transparency.
// Without disable-gpu-sandbox MPV's D3D11 surface may fail to share with Chromium.
// Without disable-color-correct-rendering color management can corrupt alpha.
//
// Reliability matrix (Windows):
//   Default  = transparent:true + #00000000  → MPV --wid embed (preferred)
//   Opaque   = DYLANDOS_OPAQUE_WINDOW=1 or settings.opaqueWindow → solid black
//              Chromium canvas; MPV still embeds via --wid but OSD may cover
//              video unless React leaves a transparent hole (not recommended).
//   GPU flags stay enabled either way for DWM stability.
const useOpaqueWindow =
  process.env.DYLANDOS_OPAQUE_WINDOW === '1' ||
  process.env.DYLANDOS_OPAQUE_WINDOW === 'true' ||
  bootstrapSettings.opaqueWindow === true;

app.commandLine.appendSwitch('enable-transparent-visuals');
app.commandLine.appendSwitch('disable-gpu-sandbox');
app.commandLine.appendSwitch('disable-color-correct-rendering');

// Prevent multiple instances
const gotLock = app.requestSingleInstanceLock();
if (!gotLock) { app.quit(); process.exit(0); }

let win = null;
// Single-window architecture: MPV embeds directly into mainWin via --wid (no secondary canvas window)
let tray = null;
let powerSaveId = null;
let appSettings = {
  minimizeToTray: false,
  startWithWindows: false,
  confirmOnExit: false,
  osdTimeoutMs: 5000,
};
const DEFAULT_REMOTE_FETCH_HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36',
  'Accept-Language': 'en-US,en;q=0.9',
  'Cache-Control': 'no-cache',
  'Pragma': 'no-cache',
};

// ── Settings persistence ──────────────────────────────────────────────────────

const SETTINGS_FILE = 'settings.json';
const DATA_DIR = path.join(app.getPath('userData'), 'data');
/** @type {Map<string, AbortController>} */
const activeNetFetches = new Map();
const DVR_LIBRARY_FILE = 'dvr-library.json';

// ── DVR runtime state ───────────────────────────────────────────────────────
const MAX_CONCURRENT_RECORDINGS = 3;
const recordings = new Map();
let dvrOutputDir = null;
let ffmpegPath = null;

function sanitizeFileName(input, fallback = 'Recording') {
  const cleaned = String(input || fallback)
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, '')
    .replace(/\s+/g, ' ')
    .trim();
  return (cleaned || fallback).slice(0, 80);
}

function getDvrDir() {
  if (dvrOutputDir) return dvrOutputDir;
  dvrOutputDir = path.join(app.getPath('videos'), 'DYLANDOS IPTV DVR');
  if (!fsSync.existsSync(dvrOutputDir)) {
    fsSync.mkdirSync(dvrOutputDir, { recursive: true });
  }
  return dvrOutputDir;
}


function findFFmpeg() {
  const bundledPaths = [
    path.join(process.resourcesPath || '', 'ffmpeg', 'ffmpeg.exe'),
    path.join(process.resourcesPath || '', 'bin', 'ffmpeg.exe'),
    path.join(__dirname, '..', 'vendor', 'ffmpeg', 'ffmpeg.exe'),
  ];

  for (const candidate of bundledPaths) {
    if (candidate && fsSync.existsSync(candidate)) {
      return candidate;
    }
  }

  return 'ffmpeg';
}

function getFFmpegPath() {
  if (!ffmpegPath) ffmpegPath = findFFmpeg();
  return ffmpegPath;
}

async function loadRecordingLibrary() {
  try {
    const filePath = path.join(DATA_DIR, DVR_LIBRARY_FILE);
    const raw = await fs.readFile(filePath, 'utf8');
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

async function saveRecordingLibrary(library) {
  await ensureDataDir();
  const filePath = path.join(DATA_DIR, DVR_LIBRARY_FILE);
  await fs.writeFile(filePath, JSON.stringify(library, null, 2), 'utf8');
}

async function saveRecordingToLibrary(meta) {
  const library = await loadRecordingLibrary();
  const idx = library.findIndex((item) => item.id === meta.id);
  if (idx >= 0) {
    library[idx] = meta;
  } else {
    library.unshift(meta);
  }
  await saveRecordingLibrary(library.slice(0, 2000));
}

async function ensureDataDir() {
  await fs.mkdir(DATA_DIR, { recursive: true });
}

function sanitizeDataFilename(filename) {
  return path.basename(String(filename || '').trim());
}

function normalizeRemoteUrl(inputUrl) {
  const parsed = new URL(String(inputUrl || ''));
  if (!/^https?:$/.test(parsed.protocol)) {
    throw new Error('Only http and https URLs are allowed');
  }
  return parsed.toString();
}

async function fetchRemoteResource(url, {
  headers = {},
  timeoutMs,
  responseType = 'text',
  signal,
} = {}) {
  const safeUrl = normalizeRemoteUrl(url);
  try {
    const controllers = [];
    if (signal) controllers.push(signal);
    if (typeof timeoutMs === 'number' && timeoutMs > 0) {
      controllers.push(AbortSignal.timeout(timeoutMs));
    }
    const combinedSignal = controllers.length === 0
      ? undefined
      : controllers.length === 1
        ? controllers[0]
        : AbortSignal.any(controllers);

    const response = await fetch(safeUrl, {
      method: 'GET',
      headers: {
        ...DEFAULT_REMOTE_FETCH_HEADERS,
        ...headers,
      },
      signal: combinedSignal,
      cache: 'no-store',
      redirect: 'follow',
    });

    if (!response.ok) {
      throw new Error(`Remote fetch failed for ${safeUrl}: ${response.status} ${response.statusText}`);
    }

    if (responseType === 'json') {
      return response.json();
    }

    // XMLTV public feeds are often .xml.gz. Node fetch only auto-unzips
    // Content-Encoding:gzip — not URL-suffixed gzip payloads.
    const buffer = Buffer.from(await response.arrayBuffer());
    const encoding = String(response.headers.get('content-encoding') || '').toLowerCase();
    const urlLooksGz = safeUrl.split('?')[0].toLowerCase().endsWith('.gz');
    const magicGz = buffer.length >= 2 && buffer[0] === 0x1f && buffer[1] === 0x8b;
    // Only gunzip when the payload is actually gzip. Node may already inflate
    // Content-Encoding:gzip bodies while leaving the header set.
    const needsGunzip = magicGz || (urlLooksGz && !buffer.toString('utf8', 0, 64).includes('<'));

    let text;
    if (needsGunzip) {
      try {
        text = zlib.gunzipSync(buffer).toString('utf8');
      } catch {
        // Some servers already decompressed but still set Content-Encoding.
        text = buffer.toString('utf8');
      }
    } else {
      text = buffer.toString('utf8');
    }

    return text;
  } catch (err) {
    if (err?.name === 'TimeoutError' || err?.name === 'AbortError') {
      if (signal?.aborted) {
        throw err;
      }
      const suffix = typeof timeoutMs === 'number' && timeoutMs > 0
        ? ` after ${timeoutMs}ms`
        : '';
      throw new Error(`Remote fetch timed out for ${safeUrl}${suffix}`);
    }
    throw err;
  }
}

async function loadMainSettings() {
  try {
    const filePath = path.join(DATA_DIR, SETTINGS_FILE);
    const raw = await fs.readFile(filePath, 'utf8');
    appSettings = { ...appSettings, ...JSON.parse(raw) };
  } catch {
    // Use defaults on first run
  }
}

// ── Window Creation ──────────────────────────────────────────────────────────

function createWindow() {
  const opaque = useOpaqueWindow || appSettings.opaqueWindow === true;
  win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 900,
    minHeight: 600,
    frame: false,
    titleBarStyle: 'hidden',
    // Transparent mode lets MPV --wid child surface show through React OSD.
    // Opaque fallback (env DYLANDOS_OPAQUE_WINDOW=1) reduces HWND/DWM fragility
    // on some GPU drivers at the cost of embed visibility unless UI leaves holes.
    backgroundColor: opaque ? '#0a0e18' : '#00000000',
    show: false,
    transparent: !opaque,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  });

  // Re-assert transparent backdrop at runtime; some Windows/DWM paths ignore
  // constructor-only backgroundColor after wake/restart cycles.
  if (!opaque) {
    win.setBackgroundColor('#00000000');
  }

  // Load app
  const isDev = !app.isPackaged;
  if (isDev) {
    win.loadURL('http://localhost:5173');
    win.webContents.openDevTools({ mode: 'detach' });
  } else {
    win.loadFile(path.join(__dirname, '..', 'dist', 'index.html'));
  }

  win.once('ready-to-show', async () => {
    win.show();

    // Apply startFullscreen setting
    if (appSettings.startFullscreen) {
      win.setFullScreen(true);
    }

    // Extract mainWin HWND for MPV --wid. MPV creates a Win32 child window
    // inside mainWin's client area — no DXGI swap-chain conflict with Chromium.
    let mpvHwnd = '0';
    if (process.platform === 'win32') {
      try {
        const hwndBuf = win.getNativeWindowHandle();
        const lo = hwndBuf.readUInt32LE(0);
        const hi = hwndBuf.length >= 8 ? hwndBuf.readUInt32LE(4) : 0;
        if (hi === 0 || hi === 0xffffffff) {
          mpvHwnd = lo.toString();
        } else {
          mpvHwnd = ((BigInt(hi) << 32n) + BigInt(lo)).toString();
        }
        console.log('[Main] mainWin HWND for MPV --wid (hex):', hwndBuf.toString('hex'),
          '| decimal:', mpvHwnd,
          '| hi:', hi,
          '| valid:', mpvHwnd !== '0');
      } catch (err) {
        console.warn('[Main] Could not read mainWin HWND:', err.message);
      }
    }

    // Initialize MPV embedded into mainWin
    try {
      await mpvManager.initialize(win, appSettings, mpvHwnd);
    } catch (err) {
      console.error('[Main] MPV failed to initialize:', err.message);
      win.webContents.send('mpv:unavailable', { reason: err.message });
    }
  });

  // Maximize state tracking
  win.on('maximize', () => {
    win.webContents.send('window:maximize-change', true);
  });
  win.on('unmaximize', () => {
    win.webContents.send('window:maximize-change', false);
  });

  // Close behavior — minimize to tray or confirm
  win.on('close', (e) => {
    if (appSettings.minimizeToTray && !app.isQuitting) {
      e.preventDefault();
      win.hide();
      if (!tray) setupTray();
    } else if (appSettings.confirmOnExit && !app.isQuitting) {
      e.preventDefault();
      win.webContents.send('window:before-close');
    }
  });

  // Handle deep links from Jump List
  const deepLinkPage = process.argv.find(a => a.startsWith('--goto='));
  if (deepLinkPage) {
    win.webContents.once('did-finish-load', () => {
      win.webContents.send('app:deep-link', deepLinkPage.replace('--goto=', ''));
    });
  }
}

// ── System Tray ──────────────────────────────────────────────────────────────

function setupTray() {
  const iconPath = path.join(__dirname, '..', 'resources', 'icon.png');
  let trayIcon;
  try {
    trayIcon = nativeImage.createFromPath(iconPath);
  } catch {
    trayIcon = nativeImage.createEmpty();
  }

  tray = new Tray(trayIcon.resize({ width: 16, height: 16 }));

  const contextMenu = Menu.buildFromTemplate([
    {
      label: 'Show DYLANDOS IPTV',
      click: () => {
        win?.show();
        win?.focus();
      },
    },
    { type: 'separator' },
    {
      label: 'Live TV',
      click: () => {
        win?.show();
        win?.webContents.send('app:deep-link', 'live');
      },
    },
    {
      label: 'TV Guide',
      click: () => {
        win?.show();
        win?.webContents.send('app:deep-link', 'guide');
      },
    },
    {
      label: 'Movies',
      click: () => {
        win?.show();
        win?.webContents.send('app:deep-link', 'movies');
      },
    },
    {
      label: 'DVR',
      click: () => {
        win?.show();
        win?.webContents.send('app:deep-link', 'dvr');
      },
    },
    { type: 'separator' },
    {
      label: 'Quit',
      click: () => {
        app.isQuitting = true;
        app.quit();
      },
    },
  ]);

  tray.setToolTip('DYLANDOS IPTV ULTIMATE');
  tray.setContextMenu(contextMenu);

  tray.on('double-click', () => {
    win?.show();
    win?.focus();
  });
}

// ── Windows Jump List ────────────────────────────────────────────────────────

function setupJumpList() {
  try {
    app.setJumpList([
      {
        type: 'tasks',
        items: [
          {
            type: 'task',
            title: 'Live TV',
            description: 'Open Live TV',
            program: process.execPath,
            args: '--goto=live',
            iconPath: process.execPath,
            iconIndex: 0,
          },
          {
            type: 'task',
            title: 'TV Guide',
            description: 'Open TV Guide',
            program: process.execPath,
            args: '--goto=guide',
            iconPath: process.execPath,
            iconIndex: 0,
          },
          {
            type: 'task',
            title: 'Movies',
            description: 'Browse Movies',
            program: process.execPath,
            args: '--goto=movies',
            iconPath: process.execPath,
            iconIndex: 0,
          },
          {
            type: 'task',
            title: 'Search',
            description: 'Search channels and content',
            program: process.execPath,
            args: '--goto=search',
            iconPath: process.execPath,
            iconIndex: 0,
          },
          {
            type: 'task',
            title: 'DVR',
            description: 'Open DVR recordings',
            program: process.execPath,
            args: '--goto=dvr',
            iconPath: process.execPath,
            iconIndex: 0,
          },
        ],
      },
    ]);
  } catch (err) {
    console.warn('[JumpList] Failed to set:', err.message);
  }
}

// ── Auto Updater ─────────────────────────────────────────────────────────────

function setupAutoUpdater() {
  try {
    // Skip silent update checks for unpackaged/dev builds — avoids noisy errors
    // and accidental GitHub API hits without a published channel.
    if (!app.isPackaged) {
      console.log('[AutoUpdater] Skipped (unpackaged / electron:dev)');
      ipcMain.handle('update:check', async () => ({ skipped: true, reason: 'unpackaged' }));
      ipcMain.handle('update:install-and-restart', () => ({ skipped: true }));
      return;
    }

    const { autoUpdater } = require('electron-updater');

    autoUpdater.autoDownload = true;
    autoUpdater.autoInstallOnAppQuit = true;
    autoUpdater.allowDowngrade = false;
    // Unsigned / SmartScreen builds: still allow updates; signing is optional
    // via CSC_* env (see docs/WINDOWS_CODE_SIGNING.md). Do not invent certs.
    autoUpdater.allowPrerelease = false;

    autoUpdater.on('checking-for-update', () => {
      win?.webContents.send('update:checking');
    });

    autoUpdater.on('update-available', (info) => {
      win?.webContents.send('update:available', {
        version: info.version,
        releaseNotes: info.releaseNotes,
        releaseDate: info.releaseDate,
      });
    });

    autoUpdater.on('update-not-available', () => {
      win?.webContents.send('update:not-available');
    });

    autoUpdater.on('download-progress', (progress) => {
      win?.webContents.send('update:download-progress', {
        percent: Math.round(progress.percent),
        transferred: progress.transferred,
        total: progress.total,
        bytesPerSecond: progress.bytesPerSecond,
      });
      win?.setProgressBar(progress.percent / 100);
    });

    autoUpdater.on('update-downloaded', (info) => {
      win?.setProgressBar(-1);
      win?.webContents.send('update:downloaded', { version: info.version });
    });

    autoUpdater.on('error', (err) => {
      console.error('[AutoUpdater] Error:', err);
      win?.setProgressBar(-1);
      win?.webContents.send('update:error', { message: err.message || String(err) });
    });

    // IPC handlers
    ipcMain.handle('update:check', async () => {
      try {
        return await autoUpdater.checkForUpdates();
      } catch (err) {
        const message = err?.message || String(err);
        win?.webContents.send('update:error', { message });
        return { error: message };
      }
    });

    ipcMain.handle('update:install-and-restart', () => {
      try {
        app.isQuitting = true;
        autoUpdater.quitAndInstall(true, true);
        return { ok: true };
      } catch (err) {
        return { error: err?.message || String(err) };
      }
    });

    // Check on startup (5 second delay — give window/network time)
    setTimeout(() => {
      autoUpdater.checkForUpdates().catch((err) => {
        console.warn('[AutoUpdater] Check failed:', err.message);
        win?.webContents.send('update:error', { message: err.message });
      });
    }, 5000);

    // Periodic check every 4 hours
    setInterval(() => {
      autoUpdater.checkForUpdates().catch(() => { });
    }, 4 * 60 * 60 * 1000);

  } catch (err) {
    console.warn('[AutoUpdater] Not available:', err.message);
    try {
      ipcMain.handle('update:check', async () => ({ error: err.message }));
      ipcMain.handle('update:install-and-restart', () => ({ error: err.message }));
    } catch { /* handlers may already exist */ }
  }
}

// ── Media Keys + Taskbar Thumbnail Toolbar ───────────────────────────────────

function setupMediaKeys() {
  // Register OS media keys as global shortcuts (replaces disabled HardwareMediaKeyHandling)
  const mediaBindings = [
    ['MediaPlayPause', 'media-key:play-pause'],
    ['MediaStop', 'media-key:stop'],
    ['MediaNextTrack', 'media-key:next-channel'],
    ['MediaPreviousTrack', 'media-key:prev-channel'],
  ];

  for (const [key, channel] of mediaBindings) {
    try {
      globalShortcut.register(key, () => {
        win?.webContents.send(channel);
      });
    } catch {
      // Key may already be registered or not supported
    }
  }

  // Windows Taskbar Thumbnail Toolbar buttons
  if (process.platform === 'win32' && win) {
    const empty = nativeImage.createEmpty();
    try {
      win.setThumbarButtons([
        {
          icon: empty,
          tooltip: 'Previous Channel',
          click: () => win?.webContents.send('media-key:prev-channel'),
        },
        {
          icon: empty,
          tooltip: 'Play / Pause',
          click: () => win?.webContents.send('media-key:play-pause'),
        },
        {
          icon: empty,
          tooltip: 'Next Channel',
          click: () => win?.webContents.send('media-key:next-channel'),
        },
      ]);
    } catch {
      // Thumbnail toolbar not supported on this system
    }
  }
}

// ── IPC Handlers ─────────────────────────────────────────────────────────────

function setupIPC() {
  const ensureMpvReady = () => {
    if (mpvManager.isInitialized) return true;
    win?.webContents.send('mpv:unavailable', {
      reason: 'MPV is still initializing',
    });
    return false;
  };

  // Window controls
  ipcMain.handle('window:minimize', () => win?.minimize());
  ipcMain.handle('window:maximize', () => {
    if (win?.isMaximized()) { win.unmaximize(); } else { win?.maximize(); }
  });
  ipcMain.handle('window:close', () => {
    app.isQuitting = true;
    win?.close();
  });
  ipcMain.handle('window:is-maximized', () => win?.isMaximized() ?? false);
  ipcMain.handle('fs:read-data', async (_e, filename) => {
    const safeName = sanitizeDataFilename(filename);
    if (!safeName) {
      throw new Error('Invalid filename');
    }
    const filePath = path.join(DATA_DIR, safeName);
    return fs.readFile(filePath, 'utf8');
  });
  ipcMain.handle('fs:write-data', async (_e, filename, data = '') => {
    const safeName = sanitizeDataFilename(filename);
    if (!safeName) {
      throw new Error('Invalid filename');
    }
    await ensureDataDir();
    const filePath = path.join(DATA_DIR, safeName);
    await fs.writeFile(filePath, String(data), 'utf8');
    return true;
  });

  // ── Cloud backup dialogs (file-based export/import) ─────────────────────
  ipcMain.handle('dialog:show-save', async (_e, options = {}) => {
    if (!win) return null;
    const result = await dialog.showSaveDialog(win, {
      defaultPath: options.defaultPath,
      filters: options.filters || [{ name: 'JSON', extensions: ['json'] }],
    });
    return result.canceled ? null : result.filePath;
  });
  ipcMain.handle('dialog:show-open', async (_e, options = {}) => {
    if (!win) return null;
    const result = await dialog.showOpenDialog(win, {
      filters: options.filters || [{ name: 'JSON', extensions: ['json'] }],
      properties: options.properties || ['openFile'],
    });
    if (result.canceled || !result.filePaths?.length) return null;
    return result.filePaths[0];
  });
  ipcMain.handle('fs:write-file', async (_e, { path: filePath, data } = {}) => {
    if (!filePath || typeof filePath !== 'string') throw new Error('Invalid path');
    await fs.writeFile(filePath, String(data ?? ''), 'utf8');
    return true;
  });
  ipcMain.handle('fs:read-file', async (_e, { path: filePath } = {}) => {
    if (!filePath || typeof filePath !== 'string') throw new Error('Invalid path');
    return fs.readFile(filePath, 'utf8');
  });
  ipcMain.handle('net:fetch-json', async (_e, { url, headers, timeoutMs, requestId } = {}) => {
    const controller = new AbortController();
    if (requestId) {
      activeNetFetches.set(String(requestId), controller);
    }
    try {
      return await fetchRemoteResource(url, {
        headers,
        timeoutMs,
        responseType: 'json',
        signal: controller.signal,
      });
    } finally {
      if (requestId) activeNetFetches.delete(String(requestId));
    }
  });
  ipcMain.handle('net:fetch-text', async (_e, { url, headers, timeoutMs, requestId } = {}) => {
    const controller = new AbortController();
    if (requestId) {
      activeNetFetches.set(String(requestId), controller);
    }
    try {
      return await fetchRemoteResource(url, {
        headers,
        timeoutMs,
        responseType: 'text',
        signal: controller.signal,
      });
    } finally {
      if (requestId) activeNetFetches.delete(String(requestId));
    }
  });
  ipcMain.handle('net:abort', async (_e, { requestId } = {}) => {
    const controller = activeNetFetches.get(String(requestId || ''));
    if (controller) {
      controller.abort();
      activeNetFetches.delete(String(requestId));
      return { aborted: true };
    }
    return { aborted: false };
  });

  // ─── MPV Video Canvas Handlers ───────────────────────────────────────────
  // Single-window: MPV is embedded inside mainWin via --wid.
  // show-video-window just re-asserts DWM transparency and focus.
  ipcMain.handle('mpv:show-video-window', () => {
    setImmediate(() => {
      if (win && !win.isDestroyed()) {
        win.setBackgroundColor('#00000000');
        win.setOpacity(1);
        win.focus();
      }
    });
    return { shown: true };
  });

  ipcMain.handle('mpv:hide-video-window', () => {
    return { hidden: true };
  });

  // Settings migration: Force MPV engine if not explicitly set to HLS by user
  ipcMain.handle('settings:sync', async (_e, patch) => {
    if (patch.preferredEngine === undefined && appSettings.preferredEngine === undefined) {
      patch.preferredEngine = 'mpv';
    }
    appSettings = { ...appSettings, ...patch };

    if ('startWithWindows' in patch) {
      app.setLoginItemSettings({
        openAtLogin: patch.startWithWindows,
        name: 'DYLANDOS IPTV ULTIMATE',
      });
    }

    await ensureDataDir();
    const filePath = path.join(DATA_DIR, SETTINGS_FILE);
    await fs.writeFile(filePath, JSON.stringify(appSettings, null, 2));
  });

  // Taskbar progress
  ipcMain.handle('taskbar:set-progress', (_e, progress) => {
    if (!win) return;
    if (progress < 0) {
      win.setProgressBar(-1);
    } else {
      win.setProgressBar(progress / 100, { mode: 'normal' });
    }
  });

  // Taskbar flash
  ipcMain.handle('taskbar:flash', (_e, count = 3) => {
    win?.flashFrame(true);
    setTimeout(() => win?.flashFrame(false), count * 1000);
  });

  // Power save blocker (prevents sleep during playback)
  ipcMain.handle('power:prevent-sleep', () => {
    if (powerSaveId !== null) return powerSaveId;
    powerSaveId = powerSaveBlocker.start('prevent-display-sleep');
    return powerSaveId;
  });

  ipcMain.handle('power:allow-sleep', () => {
    if (powerSaveId !== null && powerSaveBlocker.isStarted(powerSaveId)) {
      powerSaveBlocker.stop(powerSaveId);
    }
    powerSaveId = null;
  });

  // Open external links in system browser
  ipcMain.handle('shell:open-external', (_e, url) => {
    // Only allow http/https URLs
    if (typeof url === 'string' && /^https?:\/\//i.test(url)) {
      shell.openExternal(url);
    }
  });

  ipcMain.handle('shell:open-path', async (_e, inputPath) => {
    if (typeof inputPath !== 'string' || !inputPath.trim()) {
      return { success: false, error: 'Invalid path' };
    }
    try {
      const err = await shell.openPath(inputPath);
      return err ? { success: false, error: err } : { success: true };
    } catch (err) {
      return { success: false, error: err.message };
    }
  });

  // ── DVR IPC Handlers ────────────────────────────────────────────────────
  async function startRecording(payload = {}) {
    const {
      streamUrl,
      channelName = 'Channel',
      programTitle = 'Recording',
      durationSeconds,
      customOutputDir,
      recordingId: requestedRecordingId,
    } = payload;

    if (typeof streamUrl !== 'string' || !streamUrl.trim()) {
      return { success: false, error: 'Invalid stream URL' };
    }

    const outputDir = typeof customOutputDir === 'string' && customOutputDir.trim()
      ? customOutputDir.trim()
      : getDvrDir();

    try {
      fsSync.mkdirSync(outputDir, { recursive: true });
      const probePath = path.join(outputDir, `.dylandos_write_probe_${process.pid}_${Date.now()}`);
      fsSync.writeFileSync(probePath, 'ok');
      fsSync.unlinkSync(probePath);
    } catch (err) {
      return { success: false, error: `Recording folder is not writable: ${err.message}` };
    }

    const recordingId = typeof requestedRecordingId === 'string' && /^[A-Za-z0-9_-]{3,120}$/.test(requestedRecordingId)
      ? requestedRecordingId
      : `rec_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`;

    if (recordings.has(recordingId)) {
      return { success: true, recordingId, duplicate: true, activeCount: recordings.size, maxConcurrent: MAX_CONCURRENT_RECORDINGS };
    }

    for (const [id, active] of recordings.entries()) {
      if (active.meta.streamUrl === streamUrl) {
        return { success: true, recordingId: id, duplicate: true, activeCount: recordings.size, maxConcurrent: MAX_CONCURRENT_RECORDINGS };
      }
    }

    if (recordings.size >= MAX_CONCURRENT_RECORDINGS) {
      return {
        success: false,
        error: `Maximum concurrent recordings reached (${MAX_CONCURRENT_RECORDINGS})`,
        maxConcurrent: MAX_CONCURRENT_RECORDINGS,
      };
    }

    const safeChannel = sanitizeFileName(channelName, 'Channel');
    const safeProgram = sanitizeFileName(programTitle, 'Recording');
    const stamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
    const filename = `${safeChannel} - ${safeProgram} - ${stamp}.ts`;
    const outputPath = path.join(outputDir, filename);

    const args = [
      '-hide_banner',
      '-loglevel', 'warning',
      '-y',
      // Reconnect flags — keep recording alive through network drops
      '-reconnect', '1',
      '-reconnect_streamed', '1',
      '-reconnect_delay_max', '5',
      '-timeout', '15000000',
      '-i', streamUrl,
      '-map', '0:v:0', '-map', '0:a:0',
      '-c', 'copy',
    ];

    if (typeof durationSeconds === 'number' && durationSeconds > 0) {
      args.push('-t', String(durationSeconds));
    }

    args.push('-f', 'mpegts', outputPath);

    let ffmpegProc;

    try {
      ffmpegProc = spawn(getFFmpegPath(), args, {
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch (err) {
      return {
        success: false,
        error: `Failed to start FFmpeg: ${err.message}`,
      };
    }

    const startedAt = Date.now();
    const meta = {
      id: recordingId,
      channelName: String(channelName || 'Channel'),
      programTitle: String(programTitle || 'Recording'),
      streamUrl,
      outputPath,
      filename,
      startTime: new Date(startedAt).toISOString(),
      durationSeconds: typeof durationSeconds === 'number' && durationSeconds > 0 ? durationSeconds : null,
      status: 'recording',
      size: 0,
    };

    recordings.set(recordingId, {
      process: ffmpegProc,
      meta,
      startMs: startedAt,
      outputPath,
      stopRequested: false,
      lastBytes: 0,
      progressTimer: null,
    });

    win?.webContents.send('dvr:started', {
      recordingId,
      channelName: meta.channelName,
      outputPath,
      startTime: meta.startTime,
    });

    ffmpegProc.on('close', async (code) => {
      const active = recordings.get(recordingId);
      if (!active) return;

      recordings.delete(recordingId);
      if (active.progressTimer) clearInterval(active.progressTimer);

      let size = 0;
      try {
        const stat = await fs.stat(outputPath);
        size = stat.size;
      } catch {
        size = 0;
      }

      const elapsedSeconds = Math.max(1, Math.round((Date.now() - active.startMs) / 1000));
      const completedMeta = {
        ...active.meta,
        // A user stop may produce a non-zero FFmpeg exit on some providers even
        // though a valid partial transport stream was flushed to disk.
        status: size > 0 ? 'completed' : 'error',
        size,
        durationSeconds: active.meta.durationSeconds ?? elapsedSeconds,
        stopReason: active.stopRequested ? 'user' : (code === 0 ? 'finished' : 'ffmpeg-error'),
      };

      await saveRecordingToLibrary(completedMeta);

      win?.webContents.send('dvr:completed', {
        recordingId,
        success: size > 0,
        code,
        meta: completedMeta,
      });
    });
    ffmpegProc.stderr?.on('data', (chunk) => {
      const text = String(chunk || '').trim();
      if (!text) return;
      win?.webContents.send('dvr:progress', {
        recordingId,
        message: text,
      });
    });

    const progressTimer = setInterval(async () => {
      const active = recordings.get(recordingId);
      if (!active) return;
      let bytes = active.lastBytes || 0;
      try { bytes = (await fs.stat(outputPath)).size; } catch { /* writer may not have opened yet */ }
      active.lastBytes = bytes;
      active.meta.size = bytes;
      win?.webContents.send('dvr:progress', {
        recordingId,
        bytesWritten: bytes,
        elapsedSeconds: Math.max(0, Math.floor((Date.now() - active.startMs) / 1000)),
      });
    }, 2000);
    const activeSlot = recordings.get(recordingId);
    if (activeSlot) activeSlot.progressTimer = progressTimer;

    return {
      success: true,
      recordingId,
      meta,
      activeCount: recordings.size,
      maxConcurrent: MAX_CONCURRENT_RECORDINGS,
    };
  }

  ipcMain.handle('dvr:start', async (_e, payload = {}) => startRecording(payload));

  ipcMain.handle('dvr:stop', async (_e, recordingId) => {
    const active = recordings.get(recordingId);
    if (!active) {
      return { success: true, alreadyStopped: true };
    }

    active.stopRequested = true;

    try {
      active.process.stdin?.write('q\n');
    } catch {
      try { active.process.kill('SIGTERM'); } catch { }
    }

    setTimeout(() => {
      const stillActive = recordings.get(recordingId);
      if (stillActive) {
        try { stillActive.process.kill('SIGKILL'); } catch { }
      }
    }, 3500);

    return { success: true };
  });

  ipcMain.handle('dvr:stop-all', async () => {
    const ids = [...recordings.keys()];
    for (const id of ids) {
      const active = recordings.get(id);
      if (!active) continue;
      active.stopRequested = true;
      try {
        active.process.stdin?.write('q\n');
      } catch {
        try { active.process.kill('SIGTERM'); } catch { }
      }
    }
    return { success: true, stoppedCount: ids.length };
  });

  ipcMain.handle('dvr:list-active', async () => {
    const list = [];
    for (const [id, active] of recordings.entries()) {
      list.push({
        ...active.meta,
        id,
        elapsedSeconds: Math.max(0, Math.floor((Date.now() - active.startMs) / 1000)),
      });
    }
    return {
      recordings: list,
      maxConcurrent: MAX_CONCURRENT_RECORDINGS,
    };
  });

  ipcMain.handle('dvr:list-library', async () => {
    const library = await loadRecordingLibrary();
    return library;
  });

  ipcMain.handle('dvr:delete', async (_e, { recordingId, deleteFile = false } = {}) => {
    if (!recordingId) {
      return { success: false, error: 'recordingId is required' };
    }

    const library = await loadRecordingLibrary();
    const item = library.find((r) => r.id === recordingId);
    const updated = library.filter((r) => r.id !== recordingId);
    await saveRecordingLibrary(updated);

    if (deleteFile && item?.outputPath) {
      try {
        await fs.unlink(item.outputPath);
      } catch {
        // Ignore missing file errors.
      }
    }

    return { success: true };
  });

  ipcMain.handle('dvr:set-output-dir', async (_e, dir) => {
    if (typeof dir !== 'string' || !dir.trim()) {
      return { success: false, error: 'Invalid directory' };
    }
    const candidate = path.resolve(dir.trim());
    try {
      fsSync.mkdirSync(candidate, { recursive: true });
      const probePath = path.join(candidate, `.dylandos_write_probe_${process.pid}_${Date.now()}`);
      fsSync.writeFileSync(probePath, 'ok');
      fsSync.unlinkSync(probePath);
      dvrOutputDir = candidate;
      return { success: true, outputDir: dvrOutputDir };
    } catch (err) {
      return { success: false, error: `Folder is not writable: ${err.message}` };
    }
  });

  ipcMain.handle('dvr:status', async () => ({
    activeCount: recordings.size,
    maxConcurrent: MAX_CONCURRENT_RECORDINGS,
    ffmpegAvailable: (() => {
      const ff = getFFmpegPath();
      if (ff.toLowerCase() === 'ffmpeg') return true;
      return fsSync.existsSync(ff);
    })(),
    outputDir: getDvrDir(),
  }));

  ipcMain.handle('dvr:check-ffmpeg', async () => {
    return await new Promise((resolve) => {
      let settled = false;
      const finish = (result) => {
        if (settled) return;
        settled = true;
        resolve(result);
      };

      let child;
      try {
        child = spawn(getFFmpegPath(), ['-version'], {
          windowsHide: true,
          stdio: ['ignore', 'pipe', 'pipe'],
        });
      } catch (err) {
        finish({ available: false, error: err.message });
        return;
      }

      const timer = setTimeout(() => {
        try { child.kill('SIGKILL'); } catch { }
        finish({ available: false, error: 'FFmpeg probe timed out' });
      }, 4000);

      child.on('close', (code) => {
        clearTimeout(timer);
        finish({ available: code === 0, code });
      });

      child.on('error', (err) => {
        clearTimeout(timer);
        finish({ available: false, error: err.message });
      });
    });
  });

  // ── MPV IPC Handlers ──────────────────────────────────────────────────────
  ipcMain.handle('mpv:load-live', async (_e, { url, settings }) => {
    if (!ensureMpvReady()) {
      return { ok: false, reason: 'mpv-not-ready', fallback: 'hlsjs' };
    }
    // Ensure mainWin stays above the video canvas window
    try { win.focus(); } catch { }
    await mpvManager.loadLiveStream(url, settings);
    return { ok: true };
  });

  ipcMain.handle('mpv:load-vod', async (_e, { url, startPosition, settings }) => {
    if (!ensureMpvReady()) {
      return { ok: false, reason: 'mpv-not-ready', fallback: 'hlsjs' };
    }
    try { win.focus(); } catch { }
    await mpvManager.loadVodStream(url, startPosition ?? 0, settings);
    return { ok: true };
  });

  ipcMain.handle('mpv:play', async () => ensureMpvReady() ? mpvManager.play() : null);
  ipcMain.handle('mpv:pause', async () => ensureMpvReady() ? mpvManager.pause() : null);
  ipcMain.handle('mpv:toggle-pause', async () => ensureMpvReady() ? mpvManager.togglePlayPause() : null);
  ipcMain.handle('mpv:stop', async () => {
    return ensureMpvReady() ? mpvManager.stop() : null;
  });

  // ── Video canvas window control ─────────────────────────────────────────
  // Note: These handlers were consolidated earlier in the file to sync with window lifecycle.


  ipcMain.handle('mpv:seek',
    async (_e, { seconds }) => ensureMpvReady() ? mpvManager.seek(seconds) : null);
  ipcMain.handle('mpv:seek-relative', async (_e, { delta }) => ensureMpvReady() ? mpvManager.seekRelative(delta) : null);

  ipcMain.handle('mpv:set-volume', async (_e, { level }) => ensureMpvReady() ? mpvManager.setVolume(level) : null);
  ipcMain.handle('mpv:set-mute', async (_e, { muted }) => ensureMpvReady() ? mpvManager.setMute(muted) : null);
  ipcMain.handle('mpv:set-fullscreen', async (_e, { enabled }) => ensureMpvReady() ? mpvManager.setFullscreen(enabled) : null);

  ipcMain.handle('mpv:set-audio-track', async (_e, { trackId }) => ensureMpvReady() ? mpvManager.setAudioTrack(trackId) : null);
  ipcMain.handle('mpv:set-sub-track', async (_e, { trackId }) => ensureMpvReady() ? mpvManager.setSubtitleTrack(trackId) : null);
  ipcMain.handle('mpv:set-audio-delay', async (_e, { offsetMs }) => ensureMpvReady() ? mpvManager.setAudioOffsetMs(offsetMs) : null);

  ipcMain.handle('mpv:apply-subtitle-settings', async (_e, settings) =>
    ensureMpvReady() ? mpvManager.applySubtitleSettings(settings) : null
  );

  ipcMain.handle('mpv:screenshot', async () => ensureMpvReady() ? mpvManager.takeScreenshot() : null);
  ipcMain.handle('mpv:get-state', async () => ensureMpvReady() ? mpvManager.getPlaybackState() : null);

  ipcMain.handle('mpv:send-raw-command', async (_e, { command, args } = {}) => {
    const allowed = new Set([
      'show-text',
      'show-progress',
      'osd-msg',
      'osd-msg-bar',
      'cycle',
      'cycle-values',
      'add',
      'multiply',
      'set',
      'seek',
      'revert-seek',
      'frame-step',
      'frame-back-step',
      'screenshot',
      'screenshot-to-file',
      'playlist-next',
      'playlist-prev',
      'playlist-play-index',
      'stop',
      'quit', // allowed but rarely used from UI; still safer than run/loadfile
    ]);
    const cmd = String(command || '').trim().toLowerCase();
    if (!allowed.has(cmd)) {
      console.warn('[main] Blocked mpv:send-raw-command:', command);
      return { ok: false, error: `Command not allowlisted: ${command}` };
    }
    // Block dangerous `set`/`run`-style payloads that escape the allowlist via args
    if (cmd === 'set') {
      const prop = String(args?.[0] ?? '').toLowerCase();
      const blockedProps = new Set([
        'script', 'scripts', 'input-commands', 'input-ipc-server',
        'ytdl-path', 'audio-file', 'sub-file', 'vo', 'ao',
      ]);
      if (blockedProps.has(prop)) {
        return { ok: false, error: `Property not allowlisted: ${prop}` };
      }
    }
    return ensureMpvReady() ? mpvManager.sendRawCommand(command, args ?? []) : null;
  });


  // Force Windows DWM to re-composite the transparent main window.
  // Called from MpvPlayer after the backing window is shown — fixes the case where
  // the compositor has cached the opaque backdrop and hasn't yet punched the hole
  // for the MPV video layer.
  ipcMain.handle('window:force-repaint', () => {
    if (!win || win.isDestroyed()) return;
    const bounds = win.getBounds();
    // Resize by 1px and back — one DWM frame is enough to re-composite:
    win.setBounds({ ...bounds, width: bounds.width + 1 });
    setTimeout(() => {
      if (win && !win.isDestroyed()) {
        win.setBounds(bounds);
        win.setBackgroundColor('#00000000');
      }
    }, 16);
  });

  // ── PiP (Picture-in-Picture) Handlers ────────────────────────────────────
  let pipWindow = null;

  ipcMain.handle('pip:open', async (_e, { url, title, logo } = {}) => {
    if (pipWindow && !pipWindow.isDestroyed()) {
      pipWindow.focus();
      return { ok: true };
    }
    pipWindow = new BrowserWindow({
      width: 480,
      height: 270,
      alwaysOnTop: true,
      frame: false,
      transparent: false,
      resizable: true,
      minimizable: false,
      maximizable: false,
      skipTaskbar: true,
      title: title || 'DYLANDOS PiP',
      webPreferences: {
        nodeIntegration: false,
        contextIsolation: true,
        sandbox: true,
      },
    });

    // Simple pip HTML that plays the stream via hls.js
    const pipHtml = `<!DOCTYPE html>
<html style="margin:0;padding:0;background:#000;overflow:hidden">
<head><title>${String(title || 'PiP').replace(/</g,'&lt;')}</title>
<style>
  #bar{position:fixed;top:0;left:0;right:0;height:28px;display:flex;align-items:center;gap:8px;
    padding:0 8px;background:rgba(0,0,0,.7);color:#fff;font:12px system-ui;z-index:2;
    -webkit-app-region:drag;cursor:move}
  #bar button{-webkit-app-region:no-drag;background:transparent;border:0;color:#9ca3af;cursor:pointer;font-size:14px}
  #bar button:hover{color:#fff}
  #v{width:100%;height:100vh;object-fit:contain;background:#000}
</style>
</head>
<body style="margin:0;padding:0;background:#000">
  <div id="bar"><span style="flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${String(title || 'PiP').replace(/</g,'&lt;')}</span>
    <button id="mute" title="Mute">🔊</button>
    <button id="close" title="Close">✕</button></div>
  <video id="v" style="width:100%;height:100vh;object-fit:contain" autoplay></video>
  <script src="https://cdn.jsdelivr.net/npm/hls.js@latest/dist/hls.min.js"></script>
  <script>
    var v = document.getElementById('v');
    var src = ${JSON.stringify(url || '')};
    var muted = false;
    v.muted = false;
    if (window.Hls && Hls.isSupported()) {
      var hls = new Hls({ enableWorker: true, lowLatencyMode: true });
      hls.loadSource(src); hls.attachMedia(v);
      hls.on(Hls.Events.MANIFEST_PARSED, function(){ v.play().catch(function(){}); });
    } else if (v.canPlayType('application/vnd.apple.mpegurl')) {
      v.src = src; v.play().catch(function(){});
    } else {
      v.src = src; v.play().catch(function(){});
    }
    document.getElementById('close').onclick = function(){ window.close(); };
    document.getElementById('mute').onclick = function(){
      muted = !muted; v.muted = muted;
      this.textContent = muted ? '🔇' : '🔊';
    };
    v.addEventListener('dblclick', function() { window.close(); });
  </script>
</body></html>`;

    pipWindow.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(pipHtml));

    pipWindow.on('closed', () => {
      pipWindow = null;
      win?.webContents.send('pip:closed');
    });

    // Position at bottom-right of main screen
    const { screen } = require('electron');
    const display = screen.getPrimaryDisplay();
    const { width: sw, height: sh } = display.workAreaSize;
    pipWindow.setPosition(sw - 490, sh - 280);

    return { ok: true };
  });

  ipcMain.handle('pip:close', async () => {
    if (pipWindow && !pipWindow.isDestroyed()) {
      pipWindow.close();
    }
    pipWindow = null;
    return { ok: true };
  });

  // ── Shell helpers for VOD downloads ──────────────────────────────────────
  ipcMain.handle('shell:openFile', async (_e, { path: filePath } = {}) => {
    if (typeof filePath === 'string') {
      const err = await shell.openPath(filePath);
      return err ? { success: false, error: err } : { success: true };
    }
    return { success: false, error: 'Invalid path' };
  });

  ipcMain.handle('shell:showItemInFolder', (_e, { path: filePath } = {}) => {
    if (typeof filePath === 'string') shell.showItemInFolder(filePath);
    return { success: true };
  });

  // ── VOD Download via FFmpeg ───────────────────────────────────────────────
  /** @type {Map<string, import('child_process').ChildProcess>} */
  const vodDownloadProcs = new Map();

  ipcMain.handle('vod:download', async (_e, { id, url, title } = {}) => {
    if (!url || !id) return { error: 'Missing url or id' };
    if (vodDownloadProcs.has(String(id))) {
      return { error: 'Download already in progress for this id' };
    }
    const safeTitle = (title || 'download').replace(/[^a-z0-9_\-. ]/gi, '_').slice(0, 80);
    const { app: electronApp } = require('electron');
    const downloadsDir = electronApp.getPath('downloads');
    const outFile = path.join(downloadsDir, `${safeTitle}_${id}.mp4`);

    return new Promise((resolve) => {
      const args = ['-i', url, '-c', 'copy', '-y', outFile];
      let child;
      try {
        child = spawn(getFFmpegPath(), args, { windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'] });
      } catch (err) {
        resolve({ error: err.message });
        return;
      }

      vodDownloadProcs.set(String(id), child);
      let cancelled = false;

      // Parse progress from FFmpeg stderr
      let duration = 0;
      child.stderr.on('data', (chunk) => {
        const text = chunk.toString();
        const durMatch = text.match(/Duration:\s*(\d+):(\d+):(\d+)/);
        if (durMatch) duration = (+durMatch[1]) * 3600 + (+durMatch[2]) * 60 + (+durMatch[3]);
        const timeMatch = text.match(/time=(\d+):(\d+):(\d+)/);
        if (timeMatch && duration > 0) {
          const elapsed = (+timeMatch[1]) * 3600 + (+timeMatch[2]) * 60 + (+timeMatch[3]);
          const progress = Math.min(99, Math.round((elapsed / duration) * 100));
          win?.webContents.send('vod:progress', { id, progress });
        }
      });

      child.on('close', (code) => {
        vodDownloadProcs.delete(String(id));
        if (cancelled) {
          resolve({ error: 'cancelled', cancelled: true });
          return;
        }
        if (code === 0) resolve({ filePath: outFile });
        else resolve({ error: `FFmpeg exited with code ${code}` });
      });

      child.on('error', (err) => {
        vodDownloadProcs.delete(String(id));
        resolve({ error: err.message });
      });

      // Expose cancel flag via process map metadata
      child._dylandosCancel = () => {
        cancelled = true;
        try {
          if (process.platform === 'win32') {
            spawn('taskkill', ['/pid', String(child.pid), '/f', '/t'], { windowsHide: true });
          } else {
            child.kill('SIGTERM');
          }
        } catch {
          try { child.kill(); } catch { /* ignore */ }
        }
      };
    });
  });

  ipcMain.handle('vod:cancel', (_e, { id } = {}) => {
    const child = vodDownloadProcs.get(String(id || ''));
    if (!child) return { ok: false, error: 'No active download for id' };
    if (typeof child._dylandosCancel === 'function') {
      child._dylandosCancel();
    } else {
      try {
        if (process.platform === 'win32' && child.pid) {
          spawn('taskkill', ['/pid', String(child.pid), '/f', '/t'], { windowsHide: true });
        } else {
          child.kill('SIGTERM');
        }
      } catch {
        try { child.kill(); } catch { /* ignore */ }
      }
    }
    vodDownloadProcs.delete(String(id));
    win?.webContents.send('vod:progress', { id, progress: 0, cancelled: true });
    return { ok: true };
  });
}

// ── App Lifecycle ────────────────────────────────────────────────────────────

app.whenReady().then(async () => {
  await ensureDataDir();
  await loadMainSettings();
  setupIPC();
  setupJumpList();
  createWindow();
  setupAutoUpdater();
  setupMediaKeys();

  // (Instrumentation auto-start removed) No auto DVR will be started at app launch

  // Set CSP headers for production security
  session.defaultSession.webRequest.onHeadersReceived((details, callback) => {
    callback({
      responseHeaders: {
        ...details.responseHeaders,
        'Content-Security-Policy': [
          "default-src 'self' 'unsafe-inline' 'unsafe-eval' data: blob:; " +
          "media-src * blob: data:; " +
          "img-src * data: blob:; " +
          "connect-src * ws: wss:; " +
          "font-src 'self' data:;"
        ],
      },
    });
  });
});

app.on('second-instance', (_event, argv) => {
  if (win) {
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  }
  // Handle --goto from second instance
  const gotoArg = argv.find(a => a.startsWith('--goto='));
  if (gotoArg && win) {
    win.webContents.send('app:deep-link', gotoArg.replace('--goto=', ''));
  }
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});

app.on('will-quit', () => {
  globalShortcut.unregisterAll();
});

app.on('before-quit', async () => {
  app.isQuitting = true;
  // Fill mainWin with opaque black BEFORE closing to prevent DWM ghost-region artifact
  try {
    if (win && !win.isDestroyed()) win.setBackgroundColor('#000000');
  } catch { /* ignore */ }
  await mpvManager.destroy();
});
