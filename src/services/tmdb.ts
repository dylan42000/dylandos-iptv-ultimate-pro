// ─── TMDB Enrichment Service ─────────────────────────────────────────────────

import { getLargeData, setLargeData } from './storage';

const TMDB_BASE = 'https://api.themoviedb.org/3';
const IMG_BASE = 'https://image.tmdb.org/t/p';
const CACHE_TTL_MS = 30 * 24 * 60 * 60 * 1000; // 30 days

export interface TMDBMovieDetail {
  id: number;
  title: string;
  overview: string;
  tagline: string;
  poster_path: string | null;
  backdrop_path: string | null;
  release_date: string;
  vote_average: number;
  vote_count: number;
  runtime: number;
  genres: { id: number; name: string }[];
  credits?: {
    cast: { id: number; name: string; character: string; profile_path: string | null }[];
    crew: { id: number; name: string; job: string }[];
  };
  videos?: {
    results: { key: string; site: string; type: string; official: boolean }[];
  };
  similar?: { results: TMDBMovieDetail[] };
}

interface TMDBCache {
  version: number;
  entries: Record<number, { data: TMDBMovieDetail; cachedAt: number }>;
}

export class TMDBService {
  private apiKey: string;
  private language: string;
  private memCache = new Map<number, TMDBMovieDetail>();
  private cache: TMDBCache = { version: 1, entries: {} };
  private cacheLoaded = false;
  private pendingRequests = new Map<string, Promise<unknown>>();

  constructor(apiKey: string, language = 'en-US') {
    this.apiKey = apiKey;
    this.language = language;
  }

  async init(): Promise<void> {
    if (this.cacheLoaded) return;
    try {
      const saved = await getLargeData<TMDBCache>('tmdb-cache.json', {
        version: 1,
        entries: {},
      });
      this.cache = saved;
      const now = Date.now();
      for (const [id, entry] of Object.entries(this.cache.entries)) {
        if (now - entry.cachedAt < CACHE_TTL_MS) {
          this.memCache.set(Number(id), entry.data);
        }
      }
    } catch {
      /* Start fresh */
    }
    this.cacheLoaded = true;
  }

  private async fetch<T>(
    endpoint: string,
    params: Record<string, string> = {}
  ): Promise<T> {
    const cacheKey = `${endpoint}:${JSON.stringify(params)}`;
    if (this.pendingRequests.has(cacheKey)) {
      return this.pendingRequests.get(cacheKey) as Promise<T>;
    }

    const url = new URL(`${TMDB_BASE}${endpoint}`);
    url.searchParams.set('api_key', this.apiKey);
    url.searchParams.set('language', this.language);
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));

    const request = fetch(url.toString(), { signal: AbortSignal.timeout(8000) })
      .then((r) => {
        if (!r.ok) throw new Error(`TMDB ${r.status}`);
        return r.json() as T;
      })
      .finally(() => this.pendingRequests.delete(cacheKey));

    this.pendingRequests.set(cacheKey, request);
    return request;
  }

  private cleanTitle(name: string): { title: string; year: number | null } {
    let title = name
      .replace(/\b(4K|2160p|1080p|720p|480p|360p)\b/gi, '')
      .replace(/\b(HDR10?|HLG|SDR|Dolby[\s.]+Vision)\b/gi, '')
      .replace(/\b(BluRay|BDRip|WEBRip|WEB-DL|HDTV|DVDRip|CAM|TS)\b/gi, '')
      .replace(/\b(x264|x265|H\.264|H\.265|HEVC|AVC|AV1|VP9)\b/gi, '')
      .replace(/\b(AAC|AC3|DTS|MP3|FLAC|TrueHD|Atmos)\b/gi, '')
      .replace(
        /\b(REMUX|PROPER|REPACK|EXTENDED|THEATRICAL|DIRECTORS?\.?CUT)\b/gi,
        ''
      )
      .replace(/[\[\](){}]/g, ' ')
      .replace(/\s+/g, ' ')
      .trim();

    const yearMatch = title.match(/\b(19|20)\d{2}\b/);
    const year = yearMatch ? parseInt(yearMatch[0]) : null;
    title = title.replace(/\b(19|20)\d{2}\b/, '').trim();

    return { title, year };
  }

  async enrichMovie(
    streamId: number,
    name: string
  ): Promise<TMDBMovieDetail | null> {
    if (this.memCache.has(streamId)) return this.memCache.get(streamId)!;

    const diskEntry = this.cache.entries[streamId];
    if (diskEntry && Date.now() - diskEntry.cachedAt < CACHE_TTL_MS) {
      this.memCache.set(streamId, diskEntry.data);
      return diskEntry.data;
    }

    if (!this.apiKey) return null;

    try {
      const { title, year } = this.cleanTitle(name);
      const params: Record<string, string> = { query: title };
      if (year) params.year = String(year);

      const searchRes = await this.fetch<{ results: { id: number }[] }>(
        '/search/movie',
        params
      );
      if (!searchRes.results.length) return null;

      const detail = await this.fetch<TMDBMovieDetail>(
        `/movie/${searchRes.results[0].id}`,
        { append_to_response: 'credits,videos,similar' }
      );

      this.memCache.set(streamId, detail);
      this.cache.entries[streamId] = { data: detail, cachedAt: Date.now() };
      void setLargeData('tmdb-cache.json', this.cache);

      return detail;
    } catch (err) {
      console.warn(`[TMDB] Failed to enrich "${name}":`, err);
      return null;
    }
  }

  async enrichBatch(
    items: Array<{ streamId: number; name: string }>,
    onProgress: (done: number, total: number) => void,
    signal?: AbortSignal
  ): Promise<Map<number, TMDBMovieDetail>> {
    await this.init();
    const results = new Map<number, TMDBMovieDetail>();
    let done = 0;
    const CHUNK = 5;
    const DELAY = 300;

    for (let i = 0; i < items.length; i += CHUNK) {
      if (signal?.aborted) break;
      const chunk = items.slice(i, i + CHUNK);

      await Promise.allSettled(
        chunk.map(async (item) => {
          if (signal?.aborted) return;
          const detail = await this.enrichMovie(item.streamId, item.name);
          if (detail) results.set(item.streamId, detail);
          onProgress(++done, items.length);
        })
      );

      if (i + CHUNK < items.length) {
        await new Promise((r) => setTimeout(r, DELAY));
      }
    }
    return results;
  }

  poster(
    path: string | null,
    size: 'w200' | 'w342' | 'w500' | 'original' = 'w342'
  ): string {
    return path ? `${IMG_BASE}/${size}${path}` : '';
  }

  backdrop(
    path: string | null,
    size: 'w780' | 'w1280' | 'original' = 'w1280'
  ): string {
    return path ? `${IMG_BASE}/${size}${path}` : '';
  }

  trailer(detail: TMDBMovieDetail): string | null {
    const vid = detail.videos?.results.find(
      (v) => v.site === 'YouTube' && v.type === 'Trailer' && v.official
    );
    return vid ? `https://www.youtube.com/watch?v=${vid.key}` : null;
  }
}

let tmdbInstance: TMDBService | null = null;
export const getTMDB = (
  apiKey: string,
  language?: string
): TMDBService => {
  if (!tmdbInstance) {
    tmdbInstance = new TMDBService(apiKey, language);
  }
  return tmdbInstance;
};
