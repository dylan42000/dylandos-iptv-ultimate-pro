import type { XtreamCategory, XtreamChannel, XtreamMovie, XtreamSeries } from '../types/xtream';

export type FilterMode = 'whitelist' | 'blacklist';

export interface ContentFilterSettings {
  enabled: boolean;
  mode: FilterMode;
  allowedPrefixes: string[];
  blockedPrefixes: string[];
  showUntagged: boolean;
  /** Preferred region prefixes pinned to the top of category lists (e.g. US, EN, AM). */
  categoryPriorityPrefixes: string[];
}

export const DEFAULT_EN_PREFIXES = [
  'US', 'USA', 'EN', 'ENG', 'ENGLISH', 'NA', 'NAM', 'AM', 'AMERICA',
  'CA', 'CAN', 'AU', 'AUS', 'UK', 'GB', 'IE',
];

export const DEFAULT_CLEANUP_BLOCKS = [
  'XXX', 'ADULT', '18', '18PLUS', 'TEST', 'BACKUP', 'RADIO', 'MUSIC',
];

export const DEFAULT_CONTENT_FILTER: ContentFilterSettings = {
  enabled: false,
  mode: 'whitelist',
  allowedPrefixes: [...DEFAULT_EN_PREFIXES],
  blockedPrefixes: [...DEFAULT_CLEANUP_BLOCKS],
  showUntagged: true,
  categoryPriorityPrefixes: ['US', 'USA', 'EN', 'ENG', 'AM', 'NA', 'CA', 'UK'],
};

function normalizeToken(raw: string): string {
  return raw.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

/** Extract leading region token from "US| CNN" / "UK: BBC" / "[US] ESPN". */
export function extractPrefix(name: string): string | null {
  if (!name?.trim()) return null;
  const patterns = [
    /^\s*\[([A-Za-z0-9]{1,8})\]/,
    /^\s*\(([A-Za-z0-9]{1,8})\)/,
    /^\s*([A-Za-z0-9]{1,8})\s*[|:\-–—]/,
  ];
  for (const pattern of patterns) {
    const match = name.match(pattern);
    if (match?.[1]) return normalizeToken(match[1]);
  }
  return null;
}

function allowsPrefix(prefix: string | null, settings: ContentFilterSettings): boolean {
  if (!settings.enabled) return true;
  const allowed = new Set(settings.allowedPrefixes.map(normalizeToken));
  const blocked = new Set(settings.blockedPrefixes.map(normalizeToken));

  if (!prefix) return settings.showUntagged;

  if (settings.mode === 'whitelist') {
    return allowed.has(prefix) && !blocked.has(prefix);
  }
  return !blocked.has(prefix);
}

export function filterByName<T extends { name?: string }>(
  items: T[],
  settings: ContentFilterSettings
): T[] {
  if (!settings.enabled) return items;
  return items.filter((item) => allowsPrefix(extractPrefix(item.name ?? ''), settings));
}

export function filterChannels(channels: XtreamChannel[], settings: ContentFilterSettings): XtreamChannel[] {
  return filterByName(channels, settings);
}

export function filterMovies(movies: XtreamMovie[], settings: ContentFilterSettings): XtreamMovie[] {
  return filterByName(movies, settings);
}

export function filterSeries(series: XtreamSeries[], settings: ContentFilterSettings): XtreamSeries[] {
  return filterByName(series, settings);
}

export function filterCategories(
  categories: XtreamCategory[],
  settings: ContentFilterSettings
): XtreamCategory[] {
  if (!settings.enabled) return categories;
  return categories.filter((cat) => allowsPrefix(extractPrefix(cat.category_name ?? ''), settings));
}

/** Pin preferred region categories to the top (US/EN/AM first). */
export function sortCategoriesByPriority(
  categories: XtreamCategory[],
  priorityPrefixes: string[]
): XtreamCategory[] {
  if (!priorityPrefixes?.length) return categories;
  const priority = priorityPrefixes.map(normalizeToken);

  const rank = (name: string): number => {
    const prefix = extractPrefix(name);
    if (!prefix) return priority.length + 10;
    const index = priority.indexOf(prefix);
    if (index >= 0) return index;
    // Also match if category name starts with token without separator.
    const upper = normalizeToken(name);
    const starts = priority.findIndex((p) => upper.startsWith(p));
    return starts >= 0 ? starts : priority.length + 5;
  };

  return [...categories].sort((a, b) => {
    const ra = rank(a.category_name ?? '');
    const rb = rank(b.category_name ?? '');
    if (ra !== rb) return ra - rb;
    return (a.category_name ?? '').localeCompare(b.category_name ?? '');
  });
}
