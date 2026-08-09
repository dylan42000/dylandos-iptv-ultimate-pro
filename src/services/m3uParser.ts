// ─── M3U / M3U8 Playlist Parser ──────────────────────────────────────────────
// Supports both URL-fetched and locally-opened M3U playlists.
// Extracts all standard IPTV attributes from #EXTINF lines including
// tvg-id, tvg-name, tvg-logo, tvg-chno, group-title, and catch-up URLs.

export interface M3UEntry {
  url: string;
  name: string;
  groupTitle: string;
  tvgId: string;
  tvgName: string;
  tvgLogo: string;
  tvgChno: string;
  duration: number;         // -1 for live streams
  catchupDays: number;      // 0 if not available
  catchupSource: string;    // ''  or URL template
  isLive: boolean;
  isRadio: boolean;
  raw: string;              // original #EXTINF line for debugging
}

// ── Attribute extractor ───────────────────────────────────────────────────────

function extractAttr(line: string, attr: string): string {
  const re = new RegExp(`${attr}="([^"]*)"`, 'i');
  return line.match(re)?.[1]?.trim() ?? '';
}

function extractAttrSingle(line: string, attr: string): string {
  // Handles both quoted and unquoted single-word values
  const re = new RegExp(`${attr}=([^\\s,]+)`, 'i');
  return line.match(re)?.[1]?.replace(/^["']|["']$/g, '').trim() ?? '';
}

// ── Core Parser ───────────────────────────────────────────────────────────────

export function parseM3UText(text: string): M3UEntry[] {
  const lines = text.split(/\r?\n/);
  const entries: M3UEntry[] = [];

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim();
    if (!line.startsWith('#EXTINF:')) continue;

    // Find the next non-empty, non-comment line — that's the stream URL
    let url = '';
    for (let j = i + 1; j < lines.length; j++) {
      const candidate = lines[j].trim();
      if (candidate && !candidate.startsWith('#')) {
        url = candidate;
        break;
      }
      // #EXTVLCOPT or #EXTHTTP lines between EXTINF and URL are allowed — skip them
      if (candidate.startsWith('#EXT') && !candidate.startsWith('#EXTINF:')) {
        continue;
      }
    }

    if (!url) continue;

    const durationMatch = line.match(/#EXTINF:(-?\d+(?:\.\d+)?)/);
    const duration = durationMatch ? parseFloat(durationMatch[1]) : -1;

    // Channel name: everything after the final comma on the #EXTINF line
    const nameMatch = line.match(/,([^,]*)$/);
    const name = nameMatch?.[1]?.trim() ?? 'Unknown';

    const groupTitle = extractAttr(line, 'group-title');
    const tvgId      = extractAttr(line, 'tvg-id');
    const tvgName    = extractAttr(line, 'tvg-name') || extractAttrSingle(line, 'tvg-name');
    const tvgLogo    = extractAttr(line, 'tvg-logo');
    const tvgChno    = extractAttr(line, 'tvg-chno') || extractAttrSingle(line, 'tvg-chno');
    const catchupDays   = parseInt(extractAttr(line, 'catchup-days') || extractAttr(line, 'tvg-archive-days') || '0', 10);
    const catchupSource = extractAttr(line, 'catchup-source') || extractAttr(line, 'url-tvg');

    const isLive  = duration < 0;
    const isRadio = groupTitle.toLowerCase().includes('radio') ||
                    (tvgName || name).toLowerCase().includes('radio');

    entries.push({
      url,
      name: tvgName || name,
      groupTitle: groupTitle || 'Uncategorized',
      tvgId,
      tvgName,
      tvgLogo,
      tvgChno,
      duration,
      catchupDays: isNaN(catchupDays) ? 0 : catchupDays,
      catchupSource,
      isLive,
      isRadio,
      raw: line,
    });
  }

  return entries;
}

// ── Fetch from URL ────────────────────────────────────────────────────────────

export async function parseM3UFromUrl(
  url: string,
  signal?: AbortSignal
): Promise<M3UEntry[]> {
  const response = await fetch(url, {
    signal,
    headers: { 'Accept': 'application/x-mpegurl, audio/mpegurl, */*' },
  });
  if (!response.ok) {
    throw new Error(`M3U fetch failed: ${response.status} ${response.statusText}`);
  }
  const text = await response.text();
  return parseM3UText(text);
}

// ── Parse from File object (drag-and-drop / file picker) ─────────────────────

export async function parseM3UFromFile(file: File): Promise<M3UEntry[]> {
  const text = await file.text();
  return parseM3UText(text);
}

// ── Group entries by group-title ─────────────────────────────────────────────

export function groupM3UEntries(
  entries: M3UEntry[]
): Record<string, M3UEntry[]> {
  return entries.reduce<Record<string, M3UEntry[]>>((acc, entry) => {
    const key = entry.groupTitle || 'Uncategorized';
    if (!acc[key]) acc[key] = [];
    acc[key].push(entry);
    return acc;
  }, {});
}

// ── Convert M3UEntry → minimal XtreamChannel-shaped object ──────────────────
// Allows M3U channels to be used in existing channel list components unchanged.

export interface NormalizedChannel {
  stream_id: string;
  name: string;
  stream_icon: string;
  category_id: string;
  category_name: string;
  stream_url: string;
  tvg_id: string;
  num: number;
  is_m3u: true;
  catchup_days: number;
  catchup_source: string;
}

export function normalizeM3UEntries(entries: M3UEntry[]): NormalizedChannel[] {
  return entries.map((entry, index) => ({
    stream_id: entry.tvgId || `m3u-${index}`,
    name: entry.name,
    stream_icon: entry.tvgLogo,
    category_id: entry.groupTitle,
    category_name: entry.groupTitle,
    stream_url: entry.url,
    tvg_id: entry.tvgId,
    num: parseInt(entry.tvgChno, 10) || index + 1,
    is_m3u: true as const,
    catchup_days: entry.catchupDays,
    catchup_source: entry.catchupSource,
  }));
}
