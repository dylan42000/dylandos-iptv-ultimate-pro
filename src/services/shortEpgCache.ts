import type { EPGProgram } from '../types/epg';
import { xtreamApi } from './xtreamApi';

/**
 * Xtream short-EPG cache — primary path for Live TV / Guide "now playing"
 * when full XMLTV matching fails (very common on panels).
 */

interface CacheEntry {
  programs: EPGProgram[];
  fetchedAt: number;
}

const EMPTY_TTL_MS = 2 * 60 * 1000; // retry empty results after 2 minutes
const HIT_TTL_MS = 30 * 60 * 1000; // nonempty listings stay for 30 minutes

const cache = new Map<number, CacheEntry>();
const inflight = new Map<number, Promise<EPGProgram[]>>();

function decodeMaybeBase64(value: string): string {
  const raw = value?.trim() ?? '';
  if (!raw) return '';
  try {
    if (/^[A-Za-z0-9+/=\s]+$/.test(raw) && raw.replace(/\s/g, '').length % 4 === 0) {
      const decoded = atob(raw.replace(/\s/g, ''));
      // Prefer decoded when it looks like readable text.
      if (/[\x20-\x7E]{3,}/.test(decoded)) return decoded.trim();
    }
  } catch {
    /* keep raw */
  }
  return raw;
}

function toMs(value: unknown): number {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return NaN;
  // Seconds vs milliseconds
  return n > 1e12 ? n : n * 1000;
}

function isEntryFresh(entry: CacheEntry): boolean {
  const age = Date.now() - entry.fetchedAt;
  const ttl = entry.programs.length === 0 ? EMPTY_TTL_MS : HIT_TTL_MS;
  return age < ttl;
}

export function parseShortEpgListings(
  raw: unknown,
  channelId: string
): EPGProgram[] {
  const listings = Array.isArray(raw)
    ? raw
    : Array.isArray((raw as { epg_listings?: unknown[] })?.epg_listings)
      ? (raw as { epg_listings: unknown[] }).epg_listings
      : [];

  const programs: EPGProgram[] = [];

  for (const item of listings) {
    if (!item || typeof item !== 'object') continue;
    const row = item as Record<string, unknown>;

    let startMs = toMs(row.start_timestamp ?? row.start);
    let endMs = toMs(row.stop_timestamp ?? row.end ?? row.stop);

    // Some panels return XMLTV-style strings in start/end.
    if (!Number.isFinite(startMs) && typeof row.start === 'string') {
      const parsed = Date.parse(row.start.includes('T') ? row.start : row.start.replace(' ', 'T'));
      if (Number.isFinite(parsed)) startMs = parsed;
    }
    if (!Number.isFinite(endMs) && typeof row.end === 'string') {
      const parsed = Date.parse(row.end.includes('T') ? row.end : row.end.replace(' ', 'T'));
      if (Number.isFinite(parsed)) endMs = parsed;
    }
    if (!Number.isFinite(endMs) && typeof row.stop === 'string') {
      const parsed = Date.parse(row.stop.includes('T') ? row.stop : row.stop.replace(' ', 'T'));
      if (Number.isFinite(parsed)) endMs = parsed;
    }

    if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) continue;

    const title = decodeMaybeBase64(String(row.title ?? row.name ?? 'Program'));
    const description = decodeMaybeBase64(String(row.description ?? row.desc ?? ''));

    programs.push({
      channelId,
      title: title || 'Program',
      description,
      startTime: new Date(startMs).toISOString(),
      stopTime: new Date(endMs).toISOString(),
      category: typeof row.category === 'string' ? row.category : undefined,
    });
  }

  return programs.sort(
    (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
  );
}

export function getCachedShortEpg(streamId: number): EPGProgram[] | null {
  const entry = cache.get(streamId);
  if (!entry) return null;
  if (!isEntryFresh(entry)) {
    cache.delete(streamId);
    return null;
  }
  return entry.programs;
}

export async function fetchShortEpgForStream(
  streamId: number,
  limit = 16
): Promise<EPGProgram[]> {
  const cached = getCachedShortEpg(streamId);
  if (cached) return cached;

  const existing = inflight.get(streamId);
  if (existing) return existing;

  const task = (async () => {
    try {
      const raw = await xtreamApi.getShortEpg(streamId, limit);
      const programs = parseShortEpgListings(raw, String(streamId));
      cache.set(streamId, { programs, fetchedAt: Date.now() });
      return programs;
    } catch (err) {
      console.warn('[ShortEPG] Failed for stream', streamId, err);
      // Empty failures also expire via EMPTY_TTL_MS so we retry later.
      cache.set(streamId, { programs: [], fetchedAt: Date.now() });
      return [];
    } finally {
      inflight.delete(streamId);
    }
  })();

  inflight.set(streamId, task);
  return task;
}

/** Hydrate short EPG for many channels with limited concurrency. */
export async function hydrateShortEpgBatch(
  streamIds: number[],
  concurrency = 8,
  limit = 16
): Promise<Map<number, EPGProgram[]>> {
  const unique = Array.from(new Set(streamIds.filter((id) => Number.isFinite(id) && id > 0)));
  const result = new Map<number, EPGProgram[]>();
  let cursor = 0;

  async function worker() {
    while (cursor < unique.length) {
      const index = cursor++;
      const id = unique[index];
      const programs = await fetchShortEpgForStream(id, limit);
      result.set(id, programs);
    }
  }

  const workers = Array.from({ length: Math.min(concurrency, unique.length) }, () => worker());
  await Promise.all(workers);
  return result;
}

export function clearShortEpgCache(): void {
  cache.clear();
  inflight.clear();
}
