/**
 * DYLANDOS IPTV ULTIMATE — Logo Matcher Service
 * Fuzzy-matches channel names to logo URLs from community logo packs.
 * Results are cached in localStorage to avoid repeated lookups.
 *
 * Logo sources used (no API key required):
 *   - StreamedMP logo pack (community)
 *   - GitHub hosted channel-logos CDN
 */

const CACHE_KEY = 'dylandos:logo_cache_v2';
const CACHE_TTL_MS = 7 * 24 * 3600 * 1000; // 7 days

// Community CDN base URLs (tried in order)
const CDN_BASES = [
  'https://raw.githubusercontent.com/tv-logo/tv-logos/main/countries',
  'https://github.com/nicecapj/IPTV-Logo/raw/master/logos',
];

interface CacheEntry {
  url: string | null;
  ts: number;
}

function loadCache(): Map<string, CacheEntry> {
  try {
    const raw = localStorage.getItem(CACHE_KEY);
    if (!raw) return new Map();
    const obj = JSON.parse(raw) as Record<string, CacheEntry>;
    return new Map(Object.entries(obj));
  } catch {
    return new Map();
  }
}

function saveCache(map: Map<string, CacheEntry>): void {
  try {
    const obj: Record<string, CacheEntry> = {};
    for (const [k, v] of map) obj[k] = v;
    localStorage.setItem(CACHE_KEY, JSON.stringify(obj));
  } catch {
    // quota — ignore
  }
}

/**
 * Normalize a channel name for logo matching:
 * - Lowercase
 * - Remove region/quality suffixes like "HD", "FHD", "4K", "US", "UK", etc.
 * - Remove special characters
 * - Collapse whitespace
 */
function normalizeName(name: string): string {
  return name
    .toLowerCase()
    .replace(/\b(hd|fhd|uhd|4k|sd|us|uk|ca|au|fr|de|es|it|nl|pl|pt|ar|tr)\b/g, '')
    .replace(/[^a-z0-9 ]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

/**
 * Generate candidate slug variations for a channel name.
 * E.g. "Fox News HD" → ["fox-news", "fox_news", "foxnews"]
 */
function slugVariants(normalized: string): string[] {
  const parts = normalized.split(' ').filter(Boolean);
  const joined = parts.join('');
  const dashed = parts.join('-');
  const underscored = parts.join('_');
  return [...new Set([dashed, underscored, joined])];
}

/**
 * Test if a URL resolves (HEAD request).
 * Returns the URL if 200 OK, null otherwise.
 */
async function probeUrl(url: string): Promise<string | null> {
  try {
    const res = await fetch(url, { method: 'HEAD', signal: AbortSignal.timeout(3000) });
    return res.ok ? url : null;
  } catch {
    return null;
  }
}

const extensions = ['png', 'jpg', 'svg', 'webp'];

async function findLogoUrl(channelName: string): Promise<string | null> {
  const norm = normalizeName(channelName);
  if (!norm) return null;
  const variants = slugVariants(norm);

  for (const base of CDN_BASES) {
    for (const slug of variants) {
      for (const ext of extensions) {
        const url = `${base}/${slug}.${ext}`;
        const found = await probeUrl(url);
        if (found) return found;
      }
    }
  }
  return null;
}

class LogoMatcher {
  private cache = loadCache();

  /** Returns a logo URL for the channel name, or null if not found. Cached. */
  async getLogoUrl(channelName: string): Promise<string | null> {
    const key = normalizeName(channelName);
    if (!key) return null;

    const cached = this.cache.get(key);
    if (cached && Date.now() - cached.ts < CACHE_TTL_MS) {
      return cached.url;
    }

    const url = await findLogoUrl(channelName);
    this.cache.set(key, { url, ts: Date.now() });
    saveCache(this.cache);
    return url;
  }

  /** Bulk pre-warm the cache for a list of channel names (non-blocking). */
  prewarm(channelNames: string[]): void {
    const uncached = channelNames.filter(name => {
      const key = normalizeName(name);
      const entry = this.cache.get(key);
      return !entry || Date.now() - entry.ts >= CACHE_TTL_MS;
    });

    // Process in chunks of 5 with a 200ms delay between chunks
    let i = 0;
    const processChunk = () => {
      const chunk = uncached.slice(i, i + 5);
      if (chunk.length === 0) return;
      chunk.forEach(name => { this.getLogoUrl(name).catch(() => {}); });
      i += 5;
      if (i < uncached.length) setTimeout(processChunk, 300);
    };
    setTimeout(processChunk, 500);
  }

  clearCache(): void {
    this.cache.clear();
    try { localStorage.removeItem(CACHE_KEY); } catch { /* ignore */ }
  }
}

export const logoMatcher = new LogoMatcher();
