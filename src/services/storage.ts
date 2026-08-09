// ─── Storage Service — localStorage + Electron IPC persistence ──────────────

import { AppSettings, DEFAULT_SETTINGS } from '../types/settings';
import { sanitizePlayerSettings } from '../utils/mediaUtils';
import { XtreamProfile, WatchProgress } from '../types/xtream';

// ── Safe localStorage wrappers ──────────────────────────────────────────────

const PREFIX = 'dylandos:';

function safeGet<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(PREFIX + key);
    return raw ? JSON.parse(raw) : fallback;
  } catch {
    return fallback;
  }
}

function safeSet<T>(key: string, value: T): void {
  try {
    localStorage.setItem(PREFIX + key, JSON.stringify(value));
  } catch (err) {
    console.warn('[storage] Failed to set:', key, err);
  }
}

function safeRemove(key: string): void {
  try {
    localStorage.removeItem(PREFIX + key);
  } catch {
    // ignore
  }
}

// ── Settings ────────────────────────────────────────────────────────────────

export const loadSettings = (): AppSettings => {
  const stored = safeGet<Partial<AppSettings>>('settings', {});
  // Sanitize every numeric field before merging — localStorage can store corrupt values
  const sanitized = sanitizePlayerSettings(stored);
  return { ...DEFAULT_SETTINGS, ...sanitized };
};

export const saveSettings = async (settings: AppSettings): Promise<void> => {
  safeSet('settings', settings);

  // Sync main-process-relevant settings via IPC
  if (window.electronAPI) {
    try {
      await window.electronAPI.settingsSync({
        minimizeToTray: settings.minimizeToTray,
        startWithWindows: settings.startWithWindows,
        confirmOnExit: settings.confirmOnExit,
        osdTimeoutMs: settings.osdTimeoutMs,
        opaqueWindow: settings.opaqueWindow === true,
        preferredEngine: settings.preferredEngine,
      });
    } catch (err) {
      console.warn('[storage] Failed to sync settings to main process:', err);
    }
  }
};

// ── Profiles ────────────────────────────────────────────────────────────────

export const loadProfiles = (): XtreamProfile[] => {
  return safeGet<XtreamProfile[]>('profiles', []);
};

export const saveProfiles = (profiles: XtreamProfile[]): void => {
  safeSet('profiles', profiles);
};

export const loadActiveProfileId = (): string | null => {
  return safeGet<string | null>('activeProfileId', null);
};

export const saveActiveProfileId = (id: string | null): void => {
  safeSet('activeProfileId', id);
};

// ── Watch Progress ──────────────────────────────────────────────────────────

export const getEpisodeProgressKey = (
  seriesId: number,
  season: number,
  episode: number
): string => `series_${seriesId}_s${season}_e${episode}`;

export const getMovieProgressKey = (streamId: number): string =>
  `movie_${streamId}`;

export const getLiveProgressKey = (streamId: number): string =>
  `live_${streamId}`;

export const loadWatchProgress = (key: string): WatchProgress | null => {
  return safeGet<WatchProgress | null>(`progress:${key}`, null);
};

export const saveWatchProgress = (progress: WatchProgress): void => {
  safeSet(`progress:${progress.key}`, progress);

  // Also update recent list
  const recent = loadRecentlyWatched();
  const filtered = recent.filter(r => r.key !== progress.key);
  filtered.unshift(progress);
  safeSet('recentlyWatched', filtered.slice(0, 50));
};

export const loadRecentlyWatched = (): WatchProgress[] => {
  return safeGet<WatchProgress[]>('recentlyWatched', []);
};

// ── Favorites ───────────────────────────────────────────────────────────────

export const loadFavorites = (): string[] => {
  return safeGet<string[]>('favorites', []);
};

export const saveFavorites = (keys: string[]): void => {
  safeSet('favorites', keys);
};

export const toggleFavorite = (key: string): boolean => {
  const current = loadFavorites();
  const idx = current.indexOf(key);
  if (idx >= 0) {
    current.splice(idx, 1);
    saveFavorites(current);
    return false;
  } else {
    current.push(key);
    saveFavorites(current);
    return true;
  }
};

export const isFavorite = (key: string): boolean => {
  return loadFavorites().includes(key);
};

// ── Last channel (zap recall) ───────────────────────────────────────────────

export interface LastChannelRef {
  streamId: string;
  name: string;
  previousStreamId?: string | null;
  updatedAt: number;
}

export const loadLastChannel = (): LastChannelRef | null => {
  return safeGet<LastChannelRef | null>('lastChannel', null);
};

export const saveLastChannel = (ref: LastChannelRef): void => {
  safeSet('lastChannel', ref);
};

/** Record a channel zap and return the previous stream id (for Back). */
export const pushLastChannel = (streamId: string, name: string): string | null => {
  const prev = loadLastChannel();
  const previousStreamId = prev?.streamId && prev.streamId !== String(streamId)
    ? prev.streamId
    : prev?.previousStreamId ?? null;
  saveLastChannel({
    streamId: String(streamId),
    name,
    previousStreamId,
    updatedAt: Date.now(),
  });
  return previousStreamId;
};

// ── Watchlist (Want to Watch) ───────────────────────────────────────────────

export const loadWatchlist = (): string[] => {
  return safeGet<string[]>('watchlist', []);
};

export const saveWatchlist = (keys: string[]): void => {
  safeSet('watchlist', keys);
};

export const toggleWatchlist = (key: string): boolean => {
  const current = loadWatchlist();
  const idx = current.indexOf(key);
  if (idx >= 0) {
    current.splice(idx, 1);
    saveWatchlist(current);
    return false;
  } else {
    current.push(key);
    saveWatchlist(current);
    return true;
  }
};

// ── Large Data (via Electron filesystem for > localStorage limits) ──────────

export const getLargeData = async <T>(
  filename: string,
  fallback: T
): Promise<T> => {
  if (window.electronAPI) {
    try {
      const raw = await window.electronAPI.fs.readData(filename);
      return JSON.parse(raw) as T;
    } catch {
      return fallback;
    }
  }
  return safeGet(filename, fallback);
};

export const setLargeData = async <T>(
  filename: string,
  data: T
): Promise<void> => {
  if (window.electronAPI) {
    try {
      await window.electronAPI.fs.writeData(filename, JSON.stringify(data));
    } catch (err) {
      console.warn('[storage] Failed to write large data:', err);
    }
  } else {
    safeSet(filename, data);
  }
};
