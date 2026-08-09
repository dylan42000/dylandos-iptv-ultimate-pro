/**
 * Curated public XMLTV feeds — optional fallbacks when provider XMLTV
 * is empty or poorly matched (mirrors Android EpgSourceDefaults).
 */
export const EPG_SOURCE_DEFAULTS = [
  'https://epgshare01.online/epgshare01/epg_ripper_US2.xml.gz',
  'https://epgshare01.online/epgshare01/epg_ripper_US_LOCALS1.xml.gz',
  'https://epgshare01.online/epgshare01/epg_ripper_US_SPORTS1.xml.gz',
  'https://epgshare01.online/epgshare01/epg_ripper_CA2.xml.gz',
  'https://epgshare01.online/epgshare01/epg_ripper_UK1.xml.gz',
] as const;

export function parseEpgUrlBlock(raw?: string | null): string[] {
  const source = raw?.trim() ? raw : EPG_SOURCE_DEFAULTS.join('\n');
  return source
    .split(/[\n,;|]+/)
    .map((url) => url.trim())
    .filter((url) => /^https?:\/\//i.test(url))
    .filter((url, index, arr) => arr.indexOf(url) === index);
}
