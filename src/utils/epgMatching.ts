import type { EPGData, EPGProgram } from '../types/epg';
import type { XtreamChannel } from '../types/xtream';

/**
 * Windows EPG ↔ channel matcher — mirrors Android EpgChannelMatcher.
 * Providers rarely ship clean 1:1 epg_channel_id matches; multi-pass
 * sanitised + fuzzy matching is required for Guide / Live TV.
 */

export interface EpgProgramIndex {
  lookup: Map<string, EPGProgram[]>;
  sanitizedLookup: Map<string, EPGProgram[]>;
  wordIndex: Array<{ words: string[]; programs: EPGProgram[] }>;
  sanitizedKeys: string[];
}

const NOISE_TOKENS = new Set([
  'hd', 'fhd', 'uhd', 'sd', '4k', '8k', 'hevc', 'h265', 'h264', '265', '264',
  'fullhd', 'ultrahd', 'hq', 'lq', 'vip', 'raw', 'backup', 'alt', 'feed',
  'channel', 'tv', 'ch', 'the',
]);

const COUNTRY_TOKENS = new Set([
  'us', 'usa', 'uk', 'ca', 'gb', 'au', 'nz', 'ie', 'in', 'pk', 'de', 'fr', 'es',
  'it', 'nl', 'pt', 'br', 'mx', 'ar', 'tr', 'ru', 'pl', 'ro', 'gr', 'sa',
  'ae', 'qa', 'eu', 'latino', 'latin', 'english', 'arabic', 'spanish',
]);

export function sanitizeEpgKey(raw: string): string {
  if (!raw?.trim()) return '';
  let s = raw.toLowerCase();
  s = s.replace(/[([{][^)\]}]*[)\]}]/g, ' ');
  const tokens = s.split(/[^a-z0-9]+/).filter(Boolean);
  if (tokens.length === 0) return '';

  const kept = tokens.filter((token, index) => {
    const isEdge = index === 0 || index === tokens.length - 1;
    if (NOISE_TOKENS.has(token)) return false;
    if (isEdge && COUNTRY_TOKENS.has(token)) return false;
    return true;
  });

  const source = kept.length === 0 ? tokens : kept;
  return source.join('');
}

function normalizeKey(value: string | number | null | undefined): string {
  return String(value ?? '').trim().toLowerCase();
}

function stripChannelPrefix(name: string): string {
  return name.replace(/^[a-z]{2,4}[|:]\s*/i, '').trim().toLowerCase();
}

function toSlug(value: string): string {
  return value.replace(/[^a-z0-9]/gi, '').toLowerCase();
}

function extractWords(name: string): string[] {
  return name
    .replace(/[^a-z0-9\s]/gi, ' ')
    .toLowerCase()
    .split(/\s+/)
    .filter((word) => word.length >= 3 && !NOISE_TOKENS.has(word) && !COUNTRY_TOKENS.has(word));
}

function wordOverlapScore(wordsA: string[], wordsB: string[]): number {
  if (wordsA.length === 0 || wordsB.length === 0) return 0;
  const setB = new Set(wordsB);
  const matches = wordsA.filter((word) => setB.has(word)).length;
  return matches / Math.max(wordsA.length, wordsB.length);
}

function levenshtein(a: string, b: string, max = 4): number {
  if (a === b) return 0;
  if (!a.length) return b.length;
  if (!b.length) return a.length;
  if (Math.abs(a.length - b.length) > max) return max + 1;

  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  let curr = new Array<number>(b.length + 1);

  for (let i = 1; i <= a.length; i++) {
    curr[0] = i;
    let rowMin = curr[0];
    const ca = a.charCodeAt(i - 1);
    for (let j = 1; j <= b.length; j++) {
      const cost = ca === b.charCodeAt(j - 1) ? 0 : 1;
      curr[j] = Math.min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
      if (curr[j] < rowMin) rowMin = curr[j];
    }
    if (rowMin > max) return max + 1;
    [prev, curr] = [curr, prev];
  }
  return prev[b.length];
}

function uniqueStrings(values: Array<string | number | null | undefined>): string[] {
  const out = new Set<string>();
  for (const value of values) {
    if (value === null || value === undefined) continue;
    const text = String(value).trim();
    if (!text) continue;
    out.add(text);
  }
  return Array.from(out);
}

function getChannelNameCandidates(channel: XtreamChannel): string[] {
  const raw = channel as XtreamChannel & Record<string, unknown>;
  return uniqueStrings([
    channel.name,
    raw.stream_display_name as string | undefined,
    raw.display_name as string | undefined,
    raw.tvg_name as string | undefined,
    raw.channel_name as string | undefined,
  ]);
}

function getChannelIdCandidates(channel: XtreamChannel): string[] {
  const raw = channel as XtreamChannel & Record<string, unknown>;
  return uniqueStrings([
    channel.epg_channel_id,
    channel.stream_id,
    raw.tvg_id as string | number | undefined,
    raw.xmltv_id as string | number | undefined,
    raw.channel_id as string | number | undefined,
  ]);
}

function channelKeys(value: string): string[] {
  const normalized = normalizeKey(value).replace(/[\s_]+/g, '');
  const withoutSuffix = normalized.includes('.') ? normalized.split('.')[0] : normalized;
  const slug = toSlug(value);
  const sanitized = sanitizeEpgKey(value);
  return uniqueStrings([normalized, withoutSuffix, slug, sanitized]);
}

export function buildEpgProgramIndex(epgData: EPGData | null | undefined): EpgProgramIndex {
  const lookup = new Map<string, EPGProgram[]>();
  const sanitizedLookup = new Map<string, EPGProgram[]>();

  if (!epgData) {
    return { lookup, sanitizedLookup, wordIndex: [], sanitizedKeys: [] };
  }

  for (const [channelId, programs] of Object.entries(epgData.programs)) {
    if (!programs?.length) continue;

    const channelMeta = epgData.channels[channelId];
    const names = uniqueStrings([
      channelMeta?.displayName,
      ...(channelMeta?.aliases ?? []),
      channelId,
    ]);

    const idKeys = channelKeys(channelId);
    const nameKeys = names.flatMap((name) => {
      const normalized = normalizeKey(name);
      const stripped = stripChannelPrefix(normalized);
      return uniqueStrings([
        ...channelKeys(name),
        normalized,
        stripped,
        toSlug(stripped),
        sanitizeEpgKey(stripped),
      ]);
    });

    for (const key of [...idKeys, ...nameKeys]) {
      if (!key || lookup.has(key)) continue;
      lookup.set(key, programs);
    }

    for (const name of names) {
      const sanitized = sanitizeEpgKey(name);
      if (sanitized && !sanitizedLookup.has(sanitized)) {
        sanitizedLookup.set(sanitized, programs);
      }
    }
    const idSanitized = sanitizeEpgKey(channelId);
    if (idSanitized && !sanitizedLookup.has(idSanitized)) {
      sanitizedLookup.set(idSanitized, programs);
    }
  }

  const wordIndex: Array<{ words: string[]; programs: EPGProgram[] }> = [];
  for (const [channelId, programs] of Object.entries(epgData.programs)) {
    if (!programs?.length) continue;
    const displayName = epgData.channels[channelId]?.displayName ?? channelId;
    const words = extractWords(stripChannelPrefix(displayName));
    if (words.length > 0) {
      wordIndex.push({ words, programs });
    }
  }

  return {
    lookup,
    sanitizedLookup,
    wordIndex,
    sanitizedKeys: Array.from(sanitizedLookup.keys()),
  };
}

export function getProgramsForChannel(
  channel: XtreamChannel,
  index: EpgProgramIndex
): EPGProgram[] {
  const nameCandidates = getChannelNameCandidates(channel);
  const idCandidates = getChannelIdCandidates(channel);

  const tier1Keys = new Set<string>();
  for (const id of idCandidates) {
    for (const key of channelKeys(String(id))) tier1Keys.add(key);
  }
  for (const name of nameCandidates) {
    const normalized = normalizeKey(name);
    const stripped = stripChannelPrefix(normalized);
    for (const key of channelKeys(name)) tier1Keys.add(key);
    if (normalized) tier1Keys.add(normalized);
    if (stripped) tier1Keys.add(stripped);
    const slugStripped = toSlug(stripped);
    if (slugStripped) tier1Keys.add(slugStripped);
  }

  for (const key of tier1Keys) {
    const programs = index.lookup.get(key);
    if (programs?.length) return programs;
  }

  // Android-style sanitised exact match (strips HD/FHD/UK noise)
  for (const candidate of [...idCandidates.map(String), ...nameCandidates]) {
    const sanitized = sanitizeEpgKey(candidate);
    if (!sanitized) continue;
    const programs = index.sanitizedLookup.get(sanitized);
    if (programs?.length) return programs;
  }

  // Levenshtein fuzzy on sanitised keys (cap distance by length)
  for (const candidate of nameCandidates) {
    const sanitized = sanitizeEpgKey(candidate);
    if (sanitized.length < 4) continue;
    const maxDist = sanitized.length <= 6 ? 1 : sanitized.length <= 10 ? 2 : 3;
    let bestDist = maxDist + 1;
    let bestPrograms: EPGProgram[] | null = null;

    for (const key of index.sanitizedKeys) {
      if (Math.abs(key.length - sanitized.length) > maxDist) continue;
      const dist = levenshtein(sanitized, key, maxDist);
      if (dist < bestDist) {
        bestDist = dist;
        bestPrograms = index.sanitizedLookup.get(key) ?? null;
      }
    }
    if (bestPrograms?.length && bestDist <= maxDist) {
      return bestPrograms;
    }
  }

  // Slug containment
  const channelSlugs = nameCandidates
    .map((name) => sanitizeEpgKey(name) || toSlug(name))
    .filter((slug) => slug.length >= 4);

  if (channelSlugs.length > 0) {
    for (const [key, programs] of index.lookup) {
      if (!programs?.length) continue;
      const keySlug = sanitizeEpgKey(key) || toSlug(key);
      if (keySlug.length < 4) continue;
      const hasMatch = channelSlugs.some(
        (channelSlug) => keySlug.includes(channelSlug) || channelSlug.includes(keySlug)
      );
      if (hasMatch) return programs;
    }
  }

  // Word-overlap fallback
  const channelWordSets = nameCandidates
    .map((name) => extractWords(stripChannelPrefix(name)))
    .filter((words) => words.length > 0);

  let bestScore = 0;
  let bestPrograms: EPGProgram[] | null = null;
  for (const candidateWords of channelWordSets) {
    for (const entry of index.wordIndex) {
      const score = wordOverlapScore(candidateWords, entry.words);
      if (score > bestScore) {
        bestScore = score;
        bestPrograms = entry.programs;
      }
    }
  }

  if (bestScore >= 0.55 && bestPrograms) {
    return bestPrograms;
  }

  return [];
}
