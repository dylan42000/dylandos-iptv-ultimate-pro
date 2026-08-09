// ─── Media Utilities — Volume safety & sanitization ─────────────────────────
// Prevents "Failed to set the 'volume' property on 'HTMLMediaElement':
// The provided double value is non-finite" DOMException that kills playback.
// ─────────────────────────────────────────────────────────────────────────────

import type { AppSettings } from '../types/settings';

/**
 * Clamps and sanitizes any value before it touches HTMLMediaElement.volume.
 * NaN, Infinity, -Infinity, undefined, null → all become 1.0 (safe default).
 */
export function sanitizeVolume(raw: unknown): number {
  const n = Number(raw);
  if (!isFinite(n)) return 1.0;
  return Math.min(1.0, Math.max(0.0, n));
}

/**
 * Safely set volume on any HTMLMediaElement — will NEVER throw non-finite error.
 */
export function safeSetVolume(el: HTMLMediaElement | null, raw: unknown): void {
  if (!el) return;
  try {
    el.volume = sanitizeVolume(raw);
  } catch (err) {
    console.warn('[safeSetVolume] Caught volume error, defaulting to 1.0', err);
    try { el.volume = 1.0; } catch { /* element dead */ }
  }
}

/**
 * Safely set muted on any HTMLMediaElement.
 */
export function safeSetMuted(el: HTMLMediaElement | null, muted: boolean): void {
  if (!el) return;
  try { el.muted = Boolean(muted); } catch { /* ignore */ }
}

/**
 * Sanitize a complete AppSettings object coming from localStorage.
 * Storage can return corrupt or partial data — always sanitize on load.
 */
export function sanitizePlayerSettings(raw: Partial<AppSettings>): Partial<AppSettings> {
  const out = { ...raw };

  if (out.volume !== undefined) {
    out.volume = sanitizeVolume(out.volume);
  }

  // Always coerce muted to a proper boolean — localStorage can store "true"/1/etc.
  if ('muted' in out) {
    out.muted = Boolean(out.muted);
  }

  if (out.osdTimeoutMs !== undefined) {
    const n = Number(out.osdTimeoutMs);
    out.osdTimeoutMs = isFinite(n) && n > 0 ? n : 5000;
  }

  if (out.audioOffsetMs !== undefined) {
    const n = Number(out.audioOffsetMs);
    out.audioOffsetMs = isFinite(n) ? n : 0;
  }

  if (out.subtitleFontSize !== undefined) {
    const n = Number(out.subtitleFontSize);
    out.subtitleFontSize = isFinite(n) && n > 0 ? n : 24;
  }

  if (out.subtitleOutlineWidth !== undefined) {
    const n = Number(out.subtitleOutlineWidth);
    out.subtitleOutlineWidth = isFinite(n) && n >= 0 ? n : 2;
  }

  if (out.subtitleBackgroundOpacity !== undefined) {
    const n = Number(out.subtitleBackgroundOpacity);
    out.subtitleBackgroundOpacity = isFinite(n) ? Math.min(1, Math.max(0, n)) : 0.5;
  }

  if (out.liveReconnectMaxAttempts !== undefined) {
    const n = Number(out.liveReconnectMaxAttempts);
    out.liveReconnectMaxAttempts = isFinite(n) && n > 0 ? n : 5;
  }

  if (out.liveReconnectBaseDelayMs !== undefined) {
    const n = Number(out.liveReconnectBaseDelayMs);
    out.liveReconnectBaseDelayMs = isFinite(n) && n > 0 ? n : 2000;
  }

  if (out.vodAutoPlayCountdown !== undefined) {
    const n = Number(out.vodAutoPlayCountdown);
    out.vodAutoPlayCountdown = isFinite(n) && n >= 0 ? n : 10;
  }

  if (out.epgRefreshHours !== undefined) {
    const n = Number(out.epgRefreshHours);
    out.epgRefreshHours = isFinite(n) && n > 0 ? n : 6;
  }

  if (out.maxConcurrentStreams !== undefined) {
    const n = Number(out.maxConcurrentStreams);
    out.maxConcurrentStreams = isFinite(n) && n > 0 ? n : 1;
  }

  return out;
}
