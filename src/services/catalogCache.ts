// ─── Category-scoped catalog cache ───────────────────────────────────────────
// Durable per-category cache (memory + Electron fs) with stale badges,
// title index for cross-category search, and incremental refresh support.

import { getLargeData, setLargeData } from './storage';
import type { XtreamChannel, XtreamMovie, XtreamSeries } from '../types/xtream';

export type CatalogKind = 'live' | 'vod' | 'series';

type CatalogItem = XtreamChannel | XtreamMovie | XtreamSeries;

export interface CatalogMeta {
  profileId: string;
  kind: CatalogKind;
  categoryId: string;
  fetchedAt: number;
  itemCount: number;
  /** Soft-stale: still usable, but UI should offer refresh. */
  stale: boolean;
  /** Hard-expired: treat as miss on next read. */
  expired: boolean;
}

interface CacheEntry<T extends CatalogItem = CatalogItem> {
  profileId: string;
  kind: CatalogKind;
  categoryId: string;
  fetchedAt: number;
  items: T[];
}

interface TitleIndexEntry {
  id: string;
  name: string;
  nameLower: string;
  categoryId: string;
  kind: CatalogKind;
}

interface TitleIndexFile {
  profileId: string;
  kind: CatalogKind;
  entries: TitleIndexEntry[];
  updatedAt: number;
}

interface CatalogManifest {
  profileId: string;
  categories: Array<{
    kind: CatalogKind;
    categoryId: string;
    fetchedAt: number;
    itemCount: number;
  }>;
  updatedAt: number;
}

const MEMORY_TTL_MS = 30 * 60 * 1000; // 30 min hot memory
const STALE_TTL_MS = 2 * 60 * 60 * 1000; // 2 h soft-stale badge
const DISK_TTL_MS = 6 * 60 * 60 * 1000; // 6 h hard expire
const memory = new Map<string, CacheEntry>();
/** In-memory title indexes keyed by profileId|kind */
const titleIndexes = new Map<string, TitleIndexEntry[]>();
const manifestCache = new Map<string, CatalogManifest>();

function cacheKey(profileId: string, kind: CatalogKind, categoryId: string): string {
  return `${profileId}|${kind}|${categoryId || '_all'}`;
}

function indexKey(profileId: string, kind: CatalogKind): string {
  return `${profileId}|${kind}`;
}

function diskFilename(profileId: string, kind: CatalogKind, categoryId: string): string {
  const safeProfile = profileId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 48);
  const safeCat = String(categoryId || 'all').replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 48);
  return `catalog-${kind}-${safeProfile}-${safeCat}.json`;
}

function titleIndexFilename(profileId: string, kind: CatalogKind): string {
  const safeProfile = profileId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 48);
  return `catalog-titles-${kind}-${safeProfile}.json`;
}

function manifestFilename(profileId: string): string {
  const safeProfile = profileId.replace(/[^a-zA-Z0-9_-]/g, '_').slice(0, 48);
  return `catalog-manifest-${safeProfile}.json`;
}

function itemId(item: CatalogItem): string {
  const streamId = (item as { stream_id?: number }).stream_id;
  const seriesId = (item as { series_id?: number }).series_id;
  return String(streamId ?? seriesId ?? '');
}

function itemName(item: CatalogItem): string {
  return String((item as { name?: string }).name ?? '');
}

function buildMeta(entry: CacheEntry): CatalogMeta {
  const age = Date.now() - entry.fetchedAt;
  return {
    profileId: entry.profileId,
    kind: entry.kind,
    categoryId: entry.categoryId,
    fetchedAt: entry.fetchedAt,
    itemCount: entry.items.length,
    stale: age >= STALE_TTL_MS,
    expired: age >= DISK_TTL_MS,
  };
}

function scoreMatch(nameLower: string, query: string): number {
  if (nameLower === query) return 100;
  if (nameLower.startsWith(query)) return 80;
  const tokens = nameLower.split(/[^a-z0-9]+/).filter(Boolean);
  if (tokens.some(t => t.startsWith(query))) return 60;
  if (nameLower.includes(query)) return 40;
  // Fuzzy: all query chars appear in order
  let qi = 0;
  for (let i = 0; i < nameLower.length && qi < query.length; i++) {
    if (nameLower[i] === query[qi]) qi++;
  }
  return qi === query.length ? 20 : 0;
}

async function loadManifest(profileId: string): Promise<CatalogManifest> {
  const cached = manifestCache.get(profileId);
  if (cached) return cached;
  const disk = await getLargeData<CatalogManifest | null>(manifestFilename(profileId), null);
  const manifest: CatalogManifest = disk?.profileId === profileId
    ? disk
    : { profileId, categories: [], updatedAt: 0 };
  manifestCache.set(profileId, manifest);
  return manifest;
}

async function saveManifest(manifest: CatalogManifest): Promise<void> {
  manifest.updatedAt = Date.now();
  manifestCache.set(manifest.profileId, manifest);
  await setLargeData(manifestFilename(manifest.profileId), manifest);
}

async function loadTitleIndex(profileId: string, kind: CatalogKind): Promise<TitleIndexEntry[]> {
  const key = indexKey(profileId, kind);
  const mem = titleIndexes.get(key);
  if (mem) return mem;
  const disk = await getLargeData<TitleIndexFile | null>(titleIndexFilename(profileId, kind), null);
  const entries = disk?.profileId === profileId && disk.kind === kind ? disk.entries : [];
  titleIndexes.set(key, entries);
  return entries;
}

async function saveTitleIndex(
  profileId: string,
  kind: CatalogKind,
  entries: TitleIndexEntry[]
): Promise<void> {
  const key = indexKey(profileId, kind);
  titleIndexes.set(key, entries);
  const file: TitleIndexFile = {
    profileId,
    kind,
    entries,
    updatedAt: Date.now(),
  };
  await setLargeData(titleIndexFilename(profileId, kind), file);
}

async function upsertTitleIndexForCategory(
  profileId: string,
  kind: CatalogKind,
  categoryId: string,
  items: CatalogItem[]
): Promise<void> {
  const existing = await loadTitleIndex(profileId, kind);
  const kept = existing.filter(e => e.categoryId !== categoryId);
  const next: TitleIndexEntry[] = [
    ...kept,
    ...items.map(item => {
      const name = itemName(item);
      return {
        id: itemId(item),
        name,
        nameLower: name.toLowerCase(),
        categoryId,
        kind,
      };
    }).filter(e => e.id),
  ];
  await saveTitleIndex(profileId, kind, next);
}

export async function getCatalogCategoryMeta(
  profileId: string,
  kind: CatalogKind,
  categoryId: string
): Promise<CatalogMeta | null> {
  const key = cacheKey(profileId, kind, categoryId);
  const mem = memory.get(key);
  if (mem && mem.profileId === profileId) return buildMeta(mem);

  const disk = await getLargeData<CacheEntry | null>(diskFilename(profileId, kind, categoryId), null);
  if (disk && disk.profileId === profileId) return buildMeta(disk);
  return null;
}

export async function getCatalogCategory<T extends CatalogItem>(
  profileId: string,
  kind: CatalogKind,
  categoryId: string,
  opts?: { allowStale?: boolean }
): Promise<T[] | null> {
  const allowStale = opts?.allowStale !== false;
  const key = cacheKey(profileId, kind, categoryId);
  const mem = memory.get(key);
  if (mem && Date.now() - mem.fetchedAt < MEMORY_TTL_MS) {
    return mem.items as T[];
  }

  const disk = await getLargeData<CacheEntry<T> | null>(diskFilename(profileId, kind, categoryId), null);
  if (disk && disk.profileId === profileId) {
    const age = Date.now() - disk.fetchedAt;
    if (age < DISK_TTL_MS || allowStale) {
      memory.set(key, disk);
      return disk.items;
    }
  }

  return null;
}

export async function setCatalogCategory<T extends CatalogItem>(
  profileId: string,
  kind: CatalogKind,
  categoryId: string,
  items: T[]
): Promise<CatalogMeta> {
  const entry: CacheEntry<T> = {
    profileId,
    kind,
    categoryId,
    fetchedAt: Date.now(),
    items,
  };
  memory.set(cacheKey(profileId, kind, categoryId), entry);
  await setLargeData(diskFilename(profileId, kind, categoryId), entry);

  const manifest = await loadManifest(profileId);
  const idx = manifest.categories.findIndex(
    c => c.kind === kind && c.categoryId === categoryId
  );
  const row = {
    kind,
    categoryId,
    fetchedAt: entry.fetchedAt,
    itemCount: items.length,
  };
  if (idx >= 0) manifest.categories[idx] = row;
  else manifest.categories.push(row);
  await saveManifest(manifest);

  await upsertTitleIndexForCategory(profileId, kind, categoryId, items);
  return buildMeta(entry);
}

/** List categories currently on disk for a profile (for incremental refresh UI). */
export async function listCachedCategories(
  profileId: string,
  kind?: CatalogKind
): Promise<CatalogMeta[]> {
  const manifest = await loadManifest(profileId);
  return manifest.categories
    .filter(c => !kind || c.kind === kind)
    .map(c => {
      const age = Date.now() - c.fetchedAt;
      return {
        profileId,
        kind: c.kind,
        categoryId: c.categoryId,
        fetchedAt: c.fetchedAt,
        itemCount: c.itemCount,
        stale: age >= STALE_TTL_MS,
        expired: age >= DISK_TTL_MS,
      };
    });
}

export function clearCatalogMemory(profileId?: string): void {
  if (!profileId) {
    memory.clear();
    titleIndexes.clear();
    manifestCache.clear();
    return;
  }
  for (const key of Array.from(memory.keys())) {
    if (key.startsWith(`${profileId}|`)) memory.delete(key);
  }
  for (const key of Array.from(titleIndexes.keys())) {
    if (key.startsWith(`${profileId}|`)) titleIndexes.delete(key);
  }
  manifestCache.delete(profileId);
}

/** Sync search across in-memory cached categories only. */
export function searchCachedCatalog(
  profileId: string,
  kind: CatalogKind,
  query: string,
  limit = 50
): CatalogItem[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];

  const scored: Array<{ item: CatalogItem; score: number }> = [];
  for (const entry of memory.values()) {
    if (entry.profileId !== profileId || entry.kind !== kind) continue;
    for (const item of entry.items) {
      const score = scoreMatch(itemName(item).toLowerCase(), q);
      if (score > 0) scored.push({ item, score });
    }
  }
  scored.sort((a, b) => b.score - a.score);
  return scored.slice(0, limit).map(s => s.item);
}

export interface CatalogSearchHit {
  id: string;
  name: string;
  categoryId: string;
  kind: CatalogKind;
  score: number;
}

/**
 * Indexed full-text search over durable title indexes (all cached categories),
 * not just the currently loaded React category window.
 */
export async function searchCatalogIndexed(
  profileId: string,
  query: string,
  opts?: { kinds?: CatalogKind[]; limit?: number }
): Promise<CatalogSearchHit[]> {
  const q = query.trim().toLowerCase();
  if (!q || !profileId) return [];

  const kinds = opts?.kinds ?? (['live', 'vod', 'series'] as CatalogKind[]);
  const limit = opts?.limit ?? 50;
  const hits: CatalogSearchHit[] = [];

  for (const kind of kinds) {
    const entries = await loadTitleIndex(profileId, kind);
    for (const entry of entries) {
      const score = scoreMatch(entry.nameLower, q);
      if (score > 0) {
        hits.push({
          id: entry.id,
          name: entry.name,
          categoryId: entry.categoryId,
          kind: entry.kind,
          score,
        });
      }
    }
  }

  hits.sort((a, b) => b.score - a.score || a.name.localeCompare(b.name));
  return hits.slice(0, limit);
}

/**
 * Resolve full catalog items for search hits by loading their category caches.
 */
export async function hydrateSearchHits<T extends CatalogItem>(
  profileId: string,
  hits: CatalogSearchHit[]
): Promise<T[]> {
  const byCategory = new Map<string, CatalogSearchHit[]>();
  for (const hit of hits) {
    const key = `${hit.kind}|${hit.categoryId}`;
    const list = byCategory.get(key) ?? [];
    list.push(hit);
    byCategory.set(key, list);
  }

  const results: T[] = [];
  const seen = new Set<string>();

  for (const [key, group] of byCategory) {
    const [kind, categoryId] = key.split('|') as [CatalogKind, string];
    const items = await getCatalogCategory<T>(profileId, kind, categoryId, { allowStale: true });
    if (!items) continue;
    const wanted = new Set(group.map(h => h.id));
    for (const item of items) {
      const id = itemId(item);
      if (!wanted.has(id) || seen.has(`${kind}:${id}`)) continue;
      seen.add(`${kind}:${id}`);
      results.push(item);
    }
  }

  return results;
}

export function findCachedById(
  profileId: string,
  kind: CatalogKind,
  id: string
): CatalogItem | null {
  for (const entry of memory.values()) {
    if (entry.profileId !== profileId || entry.kind !== kind) continue;
    for (const item of entry.items) {
      if (itemId(item) === id) return item;
    }
  }
  return null;
}

/** Resolve favorite/recent IDs from memory + disk title indexes + category caches. */
export async function resolveCachedByIds(
  profileId: string,
  ids: string[]
): Promise<{
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
}> {
  const wanted = new Set(ids.map(String));
  const channels: XtreamChannel[] = [];
  const movies: XtreamMovie[] = [];
  const series: XtreamSeries[] = [];
  const found = new Set<string>();

  const takeFromMemory = () => {
    for (const entry of memory.values()) {
      if (entry.profileId !== profileId) continue;
      for (const item of entry.items) {
        const id = itemId(item);
        if (!wanted.has(id) || found.has(id)) continue;
        found.add(id);
        switch (entry.kind) {
          case 'live':
            channels.push(item as XtreamChannel);
            break;
          case 'vod':
            movies.push(item as XtreamMovie);
            break;
          case 'series':
            series.push(item as XtreamSeries);
            break;
          default: {
            const _exhaustive: never = entry.kind;
            void _exhaustive;
          }
        }
      }
    }
  };

  takeFromMemory();
  if (found.size >= wanted.size) {
    return { channels, movies, series };
  }

  // Use title indexes to discover which categories hold remaining IDs
  for (const kind of ['live', 'vod', 'series'] as CatalogKind[]) {
    const entries = await loadTitleIndex(profileId, kind);
    const catsNeeded = new Set<string>();
    for (const e of entries) {
      if (wanted.has(e.id) && !found.has(e.id)) catsNeeded.add(e.categoryId);
    }
    for (const categoryId of catsNeeded) {
      await getCatalogCategory(profileId, kind, categoryId, { allowStale: true });
    }
  }

  takeFromMemory();
  return { channels, movies, series };
}

export const CATALOG_STALE_TTL_MS = STALE_TTL_MS;
export const CATALOG_DISK_TTL_MS = DISK_TTL_MS;

export interface WarmCatalogProgress {
  kind: CatalogKind;
  done: number;
  total: number;
  skipped: number;
  failed: number;
}

/**
 * Background-warm all category caches + title indexes so Search/Home
 * are not limited to the currently browsed React category window.
 * Skips categories whose disk cache is still fresh (not soft-stale).
 */
export async function warmCatalogIndex<T extends CatalogItem>(
  profileId: string,
  kind: CatalogKind,
  categoryIds: string[],
  fetchCategory: (categoryId: string, signal?: AbortSignal) => Promise<T[]>,
  opts?: {
    concurrency?: number;
    signal?: AbortSignal;
    forceRefresh?: boolean;
    onProgress?: (p: WarmCatalogProgress) => void;
  }
): Promise<WarmCatalogProgress> {
  const concurrency = Math.max(1, Math.min(4, opts?.concurrency ?? 2));
  const signal = opts?.signal;
  const forceRefresh = opts?.forceRefresh === true;
  const ids = categoryIds.map(String).filter(Boolean);
  const progress: WarmCatalogProgress = {
    kind,
    done: 0,
    total: ids.length,
    skipped: 0,
    failed: 0,
  };

  let cursor = 0;
  const workers = Array.from({ length: Math.min(concurrency, ids.length) }, async () => {
    while (cursor < ids.length) {
      if (signal?.aborted) return;
      const idx = cursor++;
      const categoryId = ids[idx];
      try {
        if (!forceRefresh) {
          const meta = await getCatalogCategoryMeta(profileId, kind, categoryId);
          if (meta && !meta.stale && !meta.expired) {
            // Ensure title index has this category even if only meta is warm
            const existing = await getCatalogCategory(profileId, kind, categoryId, { allowStale: true });
            if (existing) {
              progress.skipped++;
              progress.done++;
              opts?.onProgress?.(progress);
              continue;
            }
          }
        }
        const items = await fetchCategory(categoryId, signal);
        if (signal?.aborted) return;
        await setCatalogCategory(profileId, kind, categoryId, items);
        progress.done++;
        opts?.onProgress?.(progress);
      } catch (err: unknown) {
        if (signal?.aborted) return;
        const name = err && typeof err === 'object' && 'name' in err ? String((err as { name?: string }).name) : '';
        if (name === 'AbortError') return;
        progress.failed++;
        progress.done++;
        opts?.onProgress?.(progress);
      }
    }
  });

  await Promise.all(workers);
  return progress;
}
