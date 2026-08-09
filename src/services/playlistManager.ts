// ─── Multi-Source Playlist Manager ───────────────────────────────────────────
// Manages Xtream Codes, M3U, and Stalker Portal playlist sources.
// Persists to localStorage. Provides unified channel merge for all active sources.

import { parseM3UFromUrl, NormalizedChannel } from './m3uParser';

export type PlaylistSourceType = 'xtream' | 'm3u' | 'stalker';

export interface PlaylistSource {
  id: string;
  name: string;
  type: PlaylistSourceType;
  // Xtream fields
  serverUrl?: string;
  username?: string;
  password?: string;
  // M3U fields
  m3uUrl?: string;
  m3uLocalPath?: string;
  // Stalker fields
  stalkerPortalUrl?: string;
  stalkerMac?: string;
  // Shared
  lastRefreshedAt: number;
  autoRefreshHours: number;   // 0 = manual only
  isActive: boolean;
  channelCount: number;
  createdAt: number;
  error?: string;             // Last fetch error message
}

export type PlaylistRefreshStatus = {
  sourceId: string;
  status: 'idle' | 'refreshing' | 'done' | 'error';
  progress: number;           // 0-100
  error?: string;
};

const STORAGE_KEY = 'dylandos:playlists';

class PlaylistManagerClass {
  private sources: PlaylistSource[] = [];
  private loaded = false;

  // ── Persistence ────────────────────────────────────────────────────────────

  load(): PlaylistSource[] {
    if (this.loaded) return this.sources;
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      this.sources = raw ? JSON.parse(raw) : [];
    } catch {
      this.sources = [];
    }
    this.loaded = true;
    return this.sources;
  }

  save(): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this.sources));
    } catch (err) {
      console.warn('[playlistManager] Failed to save:', err);
    }
  }

  // ── CRUD ───────────────────────────────────────────────────────────────────

  getSources(): PlaylistSource[] {
    this.load();
    return [...this.sources];
  }

  getActiveSources(): PlaylistSource[] {
    return this.load().filter(s => s.isActive);
  }

  addSource(source: PlaylistSource): void {
    this.load();
    this.sources.push(source);
    this.save();
  }

  updateSource(id: string, updates: Partial<PlaylistSource>): void {
    this.load();
    const idx = this.sources.findIndex(s => s.id === id);
    if (idx !== -1) {
      this.sources[idx] = { ...this.sources[idx], ...updates };
      this.save();
    }
  }

  removeSource(id: string): void {
    this.load();
    this.sources = this.sources.filter(s => s.id !== id);
    this.save();
  }

  // ── Refresh ────────────────────────────────────────────────────────────────

  async refreshM3USource(
    source: PlaylistSource,
    onProgress?: (pct: number) => void
  ): Promise<NormalizedChannel[]> {
    if (source.type !== 'm3u') throw new Error('Not an M3U source');

    const url = source.m3uUrl;
    if (!url) throw new Error('No M3U URL configured');

    onProgress?.(10);

    let entries: Awaited<ReturnType<typeof parseM3UFromUrl>>;
    try {
      entries = await parseM3UFromUrl(url);
    } catch (err) {
      throw new Error(`Failed to fetch M3U: ${(err as Error).message}`);
    }

    onProgress?.(90);

    // Import NormalizedChannel builder from m3uParser
    const { normalizeM3UEntries } = await import('./m3uParser');
    const channels = normalizeM3UEntries(entries);

    this.updateSource(source.id, {
      lastRefreshedAt: Date.now(),
      channelCount: channels.length,
      error: undefined,
    });

    onProgress?.(100);
    return channels;
  }

  async refreshAll(
    onProgress?: (sourceId: string, pct: number) => void
  ): Promise<void> {
    const active = this.getActiveSources();
    for (const source of active) {
      try {
        if (source.type === 'm3u') {
          await this.refreshM3USource(source, pct => onProgress?.(source.id, pct));
        }
        // Xtream and Stalker are refreshed by their own API classes
      } catch (err) {
        this.updateSource(source.id, { error: (err as Error).message });
      }
    }
  }

  // ── Auto-refresh check ─────────────────────────────────────────────────────

  isDueForRefresh(source: PlaylistSource): boolean {
    if (source.autoRefreshHours <= 0) return false;
    const intervalMs = source.autoRefreshHours * 3_600_000;
    return Date.now() - source.lastRefreshedAt > intervalMs;
  }

  // ── Factory helpers ────────────────────────────────────────────────────────

  static createXtreamSource(
    name: string,
    serverUrl: string,
    username: string,
    password: string
  ): PlaylistSource {
    return {
      id: crypto.randomUUID(),
      name,
      type: 'xtream',
      serverUrl: serverUrl.replace(/\/+$/, ''),
      username,
      password,
      lastRefreshedAt: 0,
      autoRefreshHours: 24,
      isActive: true,
      channelCount: 0,
      createdAt: Date.now(),
    };
  }

  static createM3USource(
    name: string,
    m3uUrl: string,
    autoRefreshHours = 24
  ): PlaylistSource {
    return {
      id: crypto.randomUUID(),
      name,
      type: 'm3u',
      m3uUrl,
      lastRefreshedAt: 0,
      autoRefreshHours,
      isActive: true,
      channelCount: 0,
      createdAt: Date.now(),
    };
  }

  static createStalkerSource(
    name: string,
    portalUrl: string,
    mac: string
  ): PlaylistSource {
    return {
      id: crypto.randomUUID(),
      name,
      type: 'stalker',
      stalkerPortalUrl: portalUrl.replace(/\/+$/, ''),
      stalkerMac: mac,
      lastRefreshedAt: 0,
      autoRefreshHours: 24,
      isActive: true,
      channelCount: 0,
      createdAt: Date.now(),
    };
  }
}

export const playlistManager = new PlaylistManagerClass();
export { PlaylistManagerClass };
