// ─── Stalker Portal (MAC-Based) API ──────────────────────────────────────────
// Implements the 4-step Stalker Portal authentication and data fetch protocol.
// Used by IPTV providers that use Ministra/Stalker Middleware.
//
// NOTE: Stalker requires Cookie-based auth. All requests must be routed through
// Electron's net module (window.electronAPI.net.fetchJson) to set Cookie headers
// without CORS issues. Falls back to a proxy-style fetch in browser context.

export interface StalkerProfile {
  id: string;
  name: string;
  portalUrl: string;
  mac: string;
}

export interface StalkerToken {
  token: string;
  random: string;
}

export interface StalkerUserProfile {
  id: string;
  mac: string;
  status: string;
  exp_date: string | null;
  max_connections: string;
}

export interface StalkerChannel {
  id: string;
  name: string;
  number: number;
  logo: string;
  cmd: string;          // encoded stream command like "ffmpeg http://..."
  tvArchive: number;    // 0 or 1
  tvArchiveDuration: number;  // days
  genres: string;
  xmltvId: string;
}

export interface StalkerProgram {
  id: string;
  name: string;
  start: number;  // Unix timestamp (seconds)
  stop: number;   // Unix timestamp (seconds)
  description: string;
}

type StalkerFetchFn = (url: string, headers: Record<string, string>) => Promise<unknown>;

class StalkerApiClass {
  private portalUrl = '';
  private mac = '';
  private token = '';
  private connected = false;

  get isConnected(): boolean {
    return this.connected;
  }

  // ── Network helper ─────────────────────────────────────────────────────────
  // Routes through Electron IPC if available (avoids CORS + allows cookies)

  private async fetchStalker<T>(action: string, extraParams: Record<string, string> = {}): Promise<T> {
    const params = new URLSearchParams({
      action,
      type: 'stb',
      JsHttpRequest: '1-xml',
      ...extraParams,
    });

    const url = `${this.portalUrl}/portal.php?${params.toString()}`;
    const headers: Record<string, string> = {
      'Cookie': `mac=${this.mac}; stb_lang=en; timezone=UTC`,
      'X-User-Agent': 'Model: MAG254; Link: WiFi',
      'Accept': 'application/json, text/javascript',
    };

    if (this.token) {
      headers['Authorization'] = `Bearer ${this.token}`;
    }

    // Use Electron net module if available (avoids CORS restrictions)
    if (window.electronAPI?.net?.fetchJson) {
      return window.electronAPI.net.fetchJson<T>(url, headers);
    }

    // Fallback: direct fetch (may fail due to CORS in browser context)
    const res = await fetch(url, { headers });
    if (!res.ok) throw new Error(`Stalker fetch failed: ${res.status} ${res.statusText}`);
    return res.json() as Promise<T>;
  }

  // ── Step 1: Handshake ──────────────────────────────────────────────────────
  // GET /portal.php?action=handshake&type=stb&token=
  // Returns: { js: { token: "...", random: "..." } }

  async handshake(portalUrl: string, mac: string): Promise<StalkerToken> {
    this.portalUrl = portalUrl.replace(/\/+$/, '');
    this.mac = mac;
    this.token = '';
    this.connected = false;

    const resp = await this.fetchStalker<{ js: StalkerToken }>('handshake');
    this.token = resp.js.token;
    return resp.js;
  }

  // ── Step 2: Get Profile ────────────────────────────────────────────────────
  // GET /portal.php?action=get_profile (with Bearer token)

  async getProfile(): Promise<StalkerUserProfile> {
    const resp = await this.fetchStalker<{ js: StalkerUserProfile }>('get_profile');
    this.connected = true;
    return resp.js;
  }

  // ── Step 3: Get All Channels ───────────────────────────────────────────────
  // GET /portal.php?action=get_all_channels&JsHttpRequest=1-xml

  async getChannels(): Promise<StalkerChannel[]> {
    const resp = await this.fetchStalker<{
      js: {
        data: Array<{
          id: string;
          name: string;
          number: number;
          logo: string;
          cmd: string;
          tv_archive: number;
          tv_archive_duration: number;
          genres_str: string;
          xmltv_id: string;
        }>;
      };
    }>('get_all_channels');

    return (resp.js.data || []).map(ch => ({
      id: ch.id,
      name: ch.name,
      number: ch.number,
      logo: ch.logo,
      cmd: ch.cmd,
      tvArchive: ch.tv_archive || 0,
      tvArchiveDuration: ch.tv_archive_duration || 0,
      genres: ch.genres_str || '',
      xmltvId: ch.xmltv_id || '',
    }));
  }

  // ── Step 4: Create Link ────────────────────────────────────────────────────
  // GET /portal.php?action=create_link&cmd={encoded_cmd}&series=&forced_storage=undefined
  // Returns the resolved playable stream URL

  async createLink(cmd: string): Promise<string> {
    const resp = await this.fetchStalker<{ js: { cmd: string; link?: string } }>(
      'create_link',
      { cmd: encodeURIComponent(cmd), series: '', forced_storage: 'undefined' }
    );

    // The resolved URL is usually in js.cmd or js.link
    const resolved = resp.js.link || resp.js.cmd || '';
    // Stalker often wraps URL like "ffmpeg http://..." — extract the URL
    const urlMatch = resolved.match(/https?:\/\/\S+/);
    return urlMatch ? urlMatch[0] : resolved;
  }

  // ── EPG ────────────────────────────────────────────────────────────────────
  // GET /portal.php?action=get_epg_info&period={days}&ch_id={id}

  async getEpg(channelId: string, days = 5): Promise<StalkerProgram[]> {
    const resp = await this.fetchStalker<{
      js: Array<{
        id: string;
        name: string;
        start_timestamp: number;
        stop_timestamp: number;
        descr: string;
      }>;
    }>('get_epg_info', { period: String(days), ch_id: channelId });

    return (resp.js || []).map(p => ({
      id: p.id,
      name: p.name,
      start: p.start_timestamp,
      stop: p.stop_timestamp,
      description: p.descr || '',
    }));
  }

  // ── Full connect flow ──────────────────────────────────────────────────────

  async connect(portalUrl: string, mac: string): Promise<StalkerUserProfile> {
    await this.handshake(portalUrl, mac);
    return this.getProfile();
  }

  disconnect(): void {
    this.portalUrl = '';
    this.mac = '';
    this.token = '';
    this.connected = false;
  }

  // ── Convert StalkerChannel to NormalizedChannel-compatible shape ───────────

  channelToNormalized(ch: StalkerChannel, resolvedUrl: string) {
    return {
      stream_id: ch.id,
      name: ch.name,
      stream_icon: ch.logo,
      category_id: ch.genres,
      category_name: ch.genres || 'Uncategorized',
      stream_url: resolvedUrl,
      tvg_id: ch.xmltvId,
      num: ch.number,
      is_stalker: true as const,
      tv_archive: ch.tvArchive,
      tv_archive_duration: ch.tvArchiveDuration,
    };
  }

  // ── Test connection (Step 1 + 2 only, no full channel load) ───────────────

  async testConnection(portalUrl: string, mac: string): Promise<{ success: boolean; status?: string; error?: string }> {
    try {
      await this.handshake(portalUrl, mac);
      const profile = await this.getProfile();
      return { success: true, status: profile.status };
    } catch (err) {
      return { success: false, error: (err as Error).message };
    }
  }
}

export const stalkerApi = new StalkerApiClass();
export { StalkerApiClass };
