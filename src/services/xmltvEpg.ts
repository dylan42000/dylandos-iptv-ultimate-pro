// ─── XMLTV / EPG Service ─────────────────────────────────────────────────────

import { EPGData, EPGProgram, EPGChannel, XMLTVSource } from '../types/epg';
import { getLargeData, setLargeData } from './storage';

const EPG_CACHE_FILE = 'epg-cache.json';
// Bump whenever parsing semantics change so stale guides cannot keep old times.
// Mirrors Android's timestamp-reconciliation release: invalidate old parsed
// guide data so the Windows client always re-evaluates XMLTV in device time.
const EPG_CACHE_VERSION = 5;

interface EpgCacheEntry {
  sourceUrl: string;
  fetchedAt: number;
  expiresAt: number;
  channelCount: number;
  programCount: number;
  data: EPGData;
}

interface EpgCache {
  version: number;
  entries: Record<string, EpgCacheEntry>;
}

const hashString = (str: string): string => {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash = hash & hash;
  }
  return Math.abs(hash).toString(36);
};

// ── XML Parser ──────────────────────────────────────────────────────────────

function parseXmltvTimestamp(ts: string): string {
  // XMLTV: "20240115120000 +0000" → ISO 8601.
  // Some providers omit the offset and use their guide's local wall-clock time.
  // Leave that form timezone-less so Date renders it in the Windows device timezone.
  const match = ts.trim().match(/^(\d{4})(\d{2})(\d{2})(\d{2})(\d{2})(\d{2})?\s*(Z|[+-]\d{4})?$/i);
  if (!match) return ts;
  const [, year, month, day, hour, min, sec = '00', tz] = match;
  const tzFormatted = !tz ? '' : tz.toUpperCase() === 'Z' ? 'Z' : `${tz.slice(0, 3)}:${tz.slice(3)}`;
  return `${year}-${month}-${day}T${hour}:${min}:${sec}${tzFormatted}`;
}

function getTextContent(parent: Element, tagName: string): string {
  const el = parent.getElementsByTagName(tagName)[0];
  return el?.textContent?.trim() ?? '';
}

function getDisplayNames(parent: Element): string[] {
  const names = new Set<string>();
  const elements = parent.getElementsByTagName('display-name');
  for (let i = 0; i < elements.length; i++) {
    const name = elements[i]?.textContent?.trim();
    if (name) names.add(name);
  }
  return Array.from(names);
}

interface EpgWorkerResponse {
  type: 'parsed' | 'error';
  sourceId: string;
  data?: EPGData;
  error?: string;
}

export function parseXmltvXml(xmlText: string): EPGData {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xmlText, 'text/xml');
  const parseError = doc.getElementsByTagName('parsererror')[0];
  if (parseError) {
    throw new Error(`Invalid XMLTV document: ${parseError.textContent?.trim() || 'XML parse error'}`);
  }

  const channels: Record<string, EPGChannel> = {};
  const programs: Record<string, EPGProgram[]> = {};

  // Parse channels
  const channelEls = doc.getElementsByTagName('channel');
  for (let i = 0; i < channelEls.length; i++) {
    const el = channelEls[i];
    const id = el.getAttribute('id') ?? '';
    if (!id) continue;

    const aliases = getDisplayNames(el);
    channels[id] = {
      id,
      displayName: aliases[0] ?? id,
      aliases,
      icon: el.getElementsByTagName('icon')[0]?.getAttribute('src') ?? undefined,
    };
    programs[id] = [];
  }

  // Parse programmes
  const programEls = doc.getElementsByTagName('programme');
  for (let i = 0; i < programEls.length; i++) {
    const el = programEls[i];
    const channelId = el.getAttribute('channel') ?? '';
    const start = el.getAttribute('start') ?? '';
    const stop = el.getAttribute('stop') ?? '';

    if (!channelId || !start || !stop) continue;

    const program: EPGProgram = {
      channelId,
      title: getTextContent(el, 'title'),
      description: getTextContent(el, 'desc'),
      startTime: parseXmltvTimestamp(start),
      stopTime: parseXmltvTimestamp(stop),
      category: getTextContent(el, 'category') || undefined,
      subtitle: getTextContent(el, 'sub-title') || undefined,
      icon: el.getElementsByTagName('icon')[0]?.getAttribute('src') ?? undefined,
    };

    if (!programs[channelId]) programs[channelId] = [];
    // XMLTV permits programme entries even when the optional channel metadata
    // is omitted. Keep them matchable by their channel ID.
    if (!channels[channelId]) {
      channels[channelId] = { id: channelId, displayName: channelId, aliases: [channelId] };
    }
    programs[channelId].push(program);
  }

  // Sort programs by start time
  for (const chId of Object.keys(programs)) {
    programs[chId].sort(
      (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
    );
  }

  return { channels, programs };
}

async function parseXmltvXmlAsync(xmlText: string, sourceId: string): Promise<EPGData> {
  if (typeof Worker === 'undefined' || typeof window === 'undefined') {
    return parseXmltvXml(xmlText);
  }

  return new Promise<EPGData>((resolve, reject) => {
    let worker: Worker | null = null;

    const cleanup = () => {
      if (worker) {
        worker.terminate();
        worker = null;
      }
    };

    try {
      worker = new Worker(new URL('../workers/epgParser.worker.ts', import.meta.url), {
        type: 'module',
      });
    } catch {
      resolve(parseXmltvXml(xmlText));
      return;
    }

    worker.addEventListener('message', (event: MessageEvent<EpgWorkerResponse>) => {
      const message = event.data;
      if (message.sourceId !== sourceId) {
        return;
      }

      cleanup();

      if (message.type === 'parsed' && message.data) {
        resolve(message.data);
        return;
      }

      reject(new Error(message.error || 'Failed to parse XMLTV guide data'));
    });

    worker.addEventListener('error', (event) => {
      cleanup();
      reject(new Error(event.message || 'XMLTV worker crashed'));
    });

    worker.postMessage({ type: 'parse', xml: xmlText, sourceId });
  });
}

// ── Fetch XMLTV Source ──────────────────────────────────────────────────────

export async function fetchXmltvSource(
  source: XMLTVSource,
  signal?: AbortSignal
): Promise<string | null> {
  try {
    if (signal?.aborted) return null;

    if (window.electronAPI?.net) {
      return window.electronAPI.net.fetchText(source.url, {
        'Accept': 'application/xml, text/xml, application/gzip, */*',
        'Accept-Encoding': 'gzip, deflate, identity',
      }, 180000);  // 3-minute timeout for large / gzipped XMLTV feeds
    }

    const response = await fetch(source.url, {
      signal,
      headers: {
        'Accept': 'application/xml, text/xml, application/gzip, */*',
        'Accept-Encoding': 'gzip, deflate, identity',
      },
      cache: 'no-store',
    });
    if (!response.ok) throw new Error(`EPG fetch failed: ${response.status}`);

    // Browser fetch auto-unzips Content-Encoding, but not .gz URL bodies.
    const urlLooksGz = source.url.split('?')[0].toLowerCase().endsWith('.gz');
    if (urlLooksGz && typeof DecompressionStream !== 'undefined') {
      const ds = new DecompressionStream('gzip');
      const decompressed = response.body?.pipeThrough(ds);
      if (decompressed) {
        return new Response(decompressed).text();
      }
    }

    return response.text();
  } catch (err: any) {
    if (err.name === 'AbortError') return null;
    console.error('[EPG] Fetch error:', err);
    throw err;
  }
}

// ── Cached Fetch ────────────────────────────────────────────────────────────

export async function fetchXmltvSourceCached(
  source: XMLTVSource,
  refreshHours: number,
  signal?: AbortSignal
): Promise<EPGData | null> {
  const cache = await getLargeData<EpgCache>(EPG_CACHE_FILE, {
    version: EPG_CACHE_VERSION,
    entries: {},
  });

  const cacheKey = hashString(source.url);
  const entry = cache.entries[cacheKey];

  // Cache hit
  if (entry && Date.now() < entry.expiresAt && cache.version === EPG_CACHE_VERSION) {
    console.log(`[EPG] Cache hit for ${source.url} (${entry.programCount} programs)`);
    return entry.data;
  }

  // Cache miss — fetch fresh
  console.log(`[EPG] Cache miss for ${source.url} — fetching fresh`);
  try {
    const rawData = await fetchXmltvSource(source, signal);
    if (!rawData) return null;

    const parsed = await parseXmltvXmlAsync(rawData, cacheKey);

    // Update cache
    cache.entries[cacheKey] = {
      sourceUrl: source.url,
      fetchedAt: Date.now(),
      expiresAt: Date.now() + refreshHours * 60 * 60 * 1000,
      channelCount: Object.keys(parsed.channels).length,
      programCount: Object.values(parsed.programs).reduce((s, p) => s + p.length, 0),
      data: parsed,
    };
    cache.version = EPG_CACHE_VERSION;

    void setLargeData(EPG_CACHE_FILE, cache);

    return parsed;
  } catch (err) {
    // On fetch error, return stale cache if available
    if (entry) {
      console.warn('[EPG] Fetch failed — using stale cache');
      return entry.data;
    }
    throw err;
  }
}

// ── EPG Helpers ─────────────────────────────────────────────────────────────

export function getNowPlaying(
  programs: EPGProgram[],
  now = new Date()
): EPGProgram | null {
  const nowMs = now.getTime();
  return programs.find(p => {
    const start = new Date(p.startTime).getTime();
    const stop = new Date(p.stopTime).getTime();
    return nowMs >= start && nowMs < stop;
  }) ?? null;
}

export function getUpcoming(
  programs: EPGProgram[],
  now = new Date(),
  limit = 5
): EPGProgram[] {
  const nowMs = now.getTime();
  return programs
    .filter(p => new Date(p.startTime).getTime() > nowMs)
    .slice(0, limit);
}

// ── Multi-source EPG Merge ──────────────────────────────────────────────────
// Fetches all configured EPG sources in parallel and merges them.
// Strategy: first-write-wins for channel metadata; programs are merged by
// channel, de-duplicated by (startTime, stopTime), then sorted.

export async function loadEpgFromAllSources(
  sources: XMLTVSource[],
  refreshHours: number,
  signal?: AbortSignal
): Promise<EPGData> {
  if (!sources || sources.length === 0) {
    return { channels: {}, programs: {} };
  }

  // Fetch all sources concurrently, tolerating individual failures
  const results = await Promise.allSettled(
    sources.map(src => fetchXmltvSourceCached(src, refreshHours, signal))
  );

  const mergedChannels: Record<string, EPGChannel> = {};
  const mergedPrograms: Record<string, EPGProgram[]> = {};

  for (const result of results) {
    if (result.status !== 'fulfilled' || !result.value) continue;
    const { channels, programs } = result.value;

    // Channels: first source wins for metadata
    for (const [id, ch] of Object.entries(channels)) {
      if (!mergedChannels[id]) {
        mergedChannels[id] = ch;
      }
    }

    // Programs: merge per channel, de-duplicate by startTime+stopTime key
    for (const [channelId, progs] of Object.entries(programs)) {
      if (!mergedPrograms[channelId]) {
        mergedPrograms[channelId] = [];
      }
      const existingKeys = new Set(
        mergedPrograms[channelId].map(p => `${p.startTime}|${p.stopTime}`)
      );
      const newProgs = progs.filter(
        p => !existingKeys.has(`${p.startTime}|${p.stopTime}`)
      );
      mergedPrograms[channelId] = [
        ...mergedPrograms[channelId],
        ...newProgs,
      ].sort(
        (a, b) => new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
      );
    }
  }

  return { channels: mergedChannels, programs: mergedPrograms };
}
