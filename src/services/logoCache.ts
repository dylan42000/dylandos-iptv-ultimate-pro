// ─── Logo URL Resolver ──────────────────────────────────────────────────────
// Many IPTV logo hosts block CORS on fetch(), which can cause long stalls if we
// try to prefetch every image. Keep this lightweight and let <img> handle loading.

import { useState, useEffect } from 'react';

const MEM_CACHE_MAX = 1200;
const memCache = new Map<string, string>();

const addToMemCache = (key: string, value: string): void => {
  if (memCache.size >= MEM_CACHE_MAX) {
    const first = memCache.keys().next().value;
    if (first) memCache.delete(first);
  }
  memCache.set(key, value);
};

const normalizeUrl = (input: string): string => {
  const url = (input || '').trim();
  if (!url) return '';
  if (/^(about:blank|null|undefined)$/i.test(url)) return '';
  if (url.startsWith('//')) return `https:${url}`;
  return url;
};

export const resolveLogoUrl = async (originalUrl: string): Promise<string> => {
  const normalized = normalizeUrl(originalUrl);
  if (!normalized) return '';
  if (memCache.has(normalized)) return memCache.get(normalized)!;

  addToMemCache(normalized, normalized);
  return normalized;
};

export const useLogoUrl = (originalUrl: string): string => {
  const [resolved, setResolved] = useState(() => normalizeUrl(originalUrl));

  useEffect(() => {
    let cancelled = false;
    resolveLogoUrl(originalUrl).then((url) => {
      if (!cancelled) setResolved(url);
    });
    return () => {
      cancelled = true;
    };
  }, [originalUrl]);

  return resolved;
};
