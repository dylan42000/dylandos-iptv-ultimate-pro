// ─── Field telemetry (anonymized, local-only) ────────────────────────────────
// Crash + playback failure codes with no credentials / stream auth in payloads.

export type TelemetryEventType =
  | 'crash'
  | 'unhandled_rejection'
  | 'playback_failure'
  | 'mpv_crash'
  | 'hwdec_fallback'
  | 'pip_error'
  | 'dvr_failure';

export interface TelemetryEvent {
  id: string;
  type: TelemetryEventType;
  code: string;
  message: string;
  at: number;
  appVersion: string;
  platform: string;
  /** Safe metadata only — never passwords, tokens, or full stream URLs with auth. */
  meta?: Record<string, string | number | boolean | null>;
}

const STORAGE_KEY = 'dylandos:telemetry:events';
const MAX_EVENTS = 200;
const SENSITIVE_QUERY = /([?&](?:username|password|token|auth|user|pass|key)=)[^&]*/gi;
const CREDENTIAL_IN_PATH = /\/\/([^/@:]+):([^/@]+)@/g;

let installed = false;
let appVersion = 'unknown';
let enabled = true;

function sanitizeText(input: string): string {
  return String(input || '')
    .replace(CREDENTIAL_IN_PATH, '//***:***@')
    .replace(SENSITIVE_QUERY, '$1***')
    .replace(/https?:\/\/[^\s"']+/gi, (url) => {
      try {
        const u = new URL(url);
        return `${u.protocol}//${u.host}${u.pathname.split('/').slice(0, 3).join('/')}…`;
      } catch {
        return '[url]';
      }
    })
    .slice(0, 500);
}

function loadEvents(): TelemetryEvent[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function persist(events: TelemetryEvent[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(events.slice(-MAX_EVENTS)));
  } catch {
    // ignore quota
  }
  // Best-effort durable copy for support exports
  void window.electronAPI?.fs?.writeData?.(
    'telemetry-events.json',
    JSON.stringify(events.slice(-MAX_EVENTS), null, 2)
  ).catch?.(() => {});
}

export function setTelemetryEnabled(value: boolean): void {
  enabled = value;
}

export function setTelemetryAppVersion(version: string): void {
  appVersion = version || 'unknown';
}

export function recordTelemetry(
  type: TelemetryEventType,
  code: string,
  message: string,
  meta?: Record<string, string | number | boolean | null>
): void {
  if (!enabled) return;
  const event: TelemetryEvent = {
    id: crypto.randomUUID(),
    type,
    code: String(code || 'UNKNOWN').slice(0, 64),
    message: sanitizeText(message),
    at: Date.now(),
    appVersion,
    platform: typeof navigator !== 'undefined' ? navigator.platform : 'unknown',
    meta: meta
      ? Object.fromEntries(
          Object.entries(meta).map(([k, v]) => [
            k,
            typeof v === 'string' ? sanitizeText(v) : v,
          ])
        )
      : undefined,
  };
  const next = [...loadEvents(), event];
  persist(next);
  console.info('[telemetry]', event.type, event.code, event.message);
}

export function getTelemetryEvents(): TelemetryEvent[] {
  return loadEvents();
}

export function clearTelemetryEvents(): void {
  persist([]);
}

/** Classify common playback failures into stable codes (no PII). */
export function classifyPlaybackFailure(message: string): string {
  const m = String(message || '').toLowerCase();
  if (/hevc|h\.?265|h265/.test(m)) return 'DECODE_HEVC';
  if (/hwdec|d3d11|dxva|vaapi|nvdec/.test(m)) return 'DECODE_HW';
  if (/403|401|forbidden|unauthorized/.test(m)) return 'HTTP_AUTH';
  if (/404|not found/.test(m)) return 'HTTP_404';
  if (/timeout|timed out|ETIMEDOUT/.test(m)) return 'NET_TIMEOUT';
  if (/network|ENOTFOUND|ECONNREFUSED|failed to open/.test(m)) return 'NET_ERROR';
  if (/codec|decode|avcodec/.test(m)) return 'DECODE_SOFT';
  if (/buffer|demuxer/.test(m)) return 'BUFFER';
  return 'PLAYBACK_FAIL';
}

export function installFieldTelemetry(opts?: { appVersion?: string; enabled?: boolean }): void {
  if (opts?.appVersion) setTelemetryAppVersion(opts.appVersion);
  if (typeof opts?.enabled === 'boolean') setTelemetryEnabled(opts.enabled);
  if (installed) return;
  installed = true;

  window.addEventListener('error', (ev) => {
    recordTelemetry(
      'crash',
      'WINDOW_ERROR',
      sanitizeText(ev.message || String(ev.error || 'error')),
      { filename: sanitizeText(String(ev.filename || '')), lineno: ev.lineno ?? 0 }
    );
  });

  window.addEventListener('unhandledrejection', (ev) => {
    const reason = ev.reason;
    const msg = reason instanceof Error ? reason.message : String(reason ?? 'rejection');
    recordTelemetry('unhandled_rejection', 'UNHANDLED_REJECTION', msg);
  });
}
