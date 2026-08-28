// ─── Xtream Codes API Service ────────────────────────────────────────────────

import {
  XtreamProfile,
  XtreamAuth,
  XtreamCategory,
  XtreamChannel,
  XtreamMovie,
  XtreamSeries,
  XtreamSeriesInfo,
} from '../types/xtream';

export class XtreamApi {
  private serverUrl = '';
  private username = '';
  private password = '';
  private connected = false;

  get isConnected(): boolean {
    return this.connected;
  }

  connect(profile: XtreamProfile): void {
    this.serverUrl = profile.serverUrl.replace(/\/+$/, '');
    this.username = profile.username;
    this.password = profile.password;
    this.connected = true;
  }

  disconnect(): void {
    this.serverUrl = '';
    this.username = '';
    this.password = '';
    this.connected = false;
  }

  private buildUrl(action: string, params: Record<string, string> = {}): string {
    const url = new URL(`${this.serverUrl}/player_api.php`);
    url.searchParams.set('username', this.username);
    url.searchParams.set('password', this.password);
    url.searchParams.set('action', action);
    Object.entries(params).forEach(([k, v]) => url.searchParams.set(k, v));
    return url.toString();
  }

  private pickChannelName(channel: Partial<XtreamChannel> & Record<string, unknown>): string {
    const candidates = [
      channel.name,
      channel.stream_display_name,
      channel.display_name,
      channel.tvg_name,
      channel.channel_name,
    ];

    for (const candidate of candidates) {
      if (typeof candidate === 'string' && candidate.trim()) {
        return candidate.trim();
      }
    }

    if (channel.num !== undefined && channel.num !== null && String(channel.num).trim()) {
      return `Channel ${String(channel.num).trim()}`;
    }

    if (channel.stream_id !== undefined && channel.stream_id !== null && String(channel.stream_id).trim()) {
      return `Channel ${String(channel.stream_id).trim()}`;
    }

    return 'Untitled Channel';
  }

  private normalizeLiveChannel(raw: Partial<XtreamChannel> & Record<string, unknown>): XtreamChannel {
    const streamId = Number(raw.stream_id);
    const num = Number(raw.num);
    const categoryIds = Array.isArray(raw.category_ids)
      ? raw.category_ids
          .map((value) => Number(value))
          .filter((value) => Number.isFinite(value))
      : [];

    const epgIdCandidate = [
      raw.epg_channel_id,
      raw.tvg_id,
      raw.xmltv_id,
      raw.channel_id,
    ].find((value) => value !== null && value !== undefined && String(value).trim().length > 0);

    return {
      ...raw,
      num: Number.isFinite(num) ? num : 0,
      name: this.pickChannelName(raw),
      stream_type: typeof raw.stream_type === 'string' && raw.stream_type.trim() ? raw.stream_type : 'live',
      stream_id: Number.isFinite(streamId) ? streamId : 0,
      stream_icon: typeof raw.stream_icon === 'string' ? raw.stream_icon : '',
      epg_channel_id: epgIdCandidate === null || epgIdCandidate === undefined
        ? null
        : String(epgIdCandidate),
      added: typeof raw.added === 'string' ? raw.added : '',
      category_id: raw.category_id === null || raw.category_id === undefined
        ? ''
        : String(raw.category_id),
      category_ids: categoryIds,
      custom_sid: typeof raw.custom_sid === 'string' ? raw.custom_sid : '',
      tv_archive: Number(raw.tv_archive) === 1 ? 1 : 0,
      direct_source: typeof raw.direct_source === 'string' ? raw.direct_source : '',
      tv_archive_duration: Number.isFinite(Number(raw.tv_archive_duration))
        ? Number(raw.tv_archive_duration)
        : 0,
      stream_display_name:
        typeof raw.stream_display_name === 'string' ? raw.stream_display_name : undefined,
      display_name: typeof raw.display_name === 'string' ? raw.display_name : undefined,
      tvg_name: typeof raw.tvg_name === 'string' ? raw.tvg_name : undefined,
      channel_name: typeof raw.channel_name === 'string' ? raw.channel_name : undefined,
      tvg_id: raw.tvg_id === null || raw.tvg_id === undefined ? undefined : String(raw.tvg_id),
      xmltv_id: raw.xmltv_id === null || raw.xmltv_id === undefined ? undefined : String(raw.xmltv_id),
      channel_id:
        raw.channel_id === null || raw.channel_id === undefined ? undefined : raw.channel_id,
    };
  }

  private async fetchJson<T>(url: string, signal?: AbortSignal): Promise<T> {
    if (signal?.aborted) {
      throw new DOMException('The operation was aborted.', 'AbortError');
    }

    if (window.electronAPI?.net) {
      const requestId = `xtream_${Date.now()}_${Math.random().toString(36).slice(2, 9)}`;
      const onAbort = () => {
        void window.electronAPI?.net?.abort?.(requestId);
      };
      signal?.addEventListener('abort', onAbort, { once: true });
      try {
        return await window.electronAPI.net.fetchJson<T>(
          url,
          { Accept: 'application/json' },
          undefined,
          requestId
        );
      } finally {
        signal?.removeEventListener('abort', onAbort);
      }
    }

    const response = await fetch(url, {
      signal,
      headers: { Accept: 'application/json' },
      cache: 'no-store',
    });
    if (!response.ok) {
      throw new Error(`Xtream API error: ${response.status} ${response.statusText}`);
    }
    return response.json() as Promise<T>;
  }

  // ── Authentication ──────────────────────────────────────────────────────

  async authenticate(signal?: AbortSignal): Promise<XtreamAuth> {
    const url = this.buildUrl('get_live_categories'); // any action triggers auth
    const authUrl = `${this.serverUrl}/player_api.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}`;
    return this.fetchJson<XtreamAuth>(authUrl, signal);
  }

  // ── Live TV ─────────────────────────────────────────────────────────────

  async getLiveCategories(signal?: AbortSignal): Promise<XtreamCategory[]> {
    return this.fetchJson<XtreamCategory[]>(
      this.buildUrl('get_live_categories'),
      signal
    );
  }

  async getLiveStreams(categoryId?: string, signal?: AbortSignal): Promise<XtreamChannel[]> {
    const params: Record<string, string> = {};
    if (categoryId) params.category_id = categoryId;
    const streams = await this.fetchJson<Array<Partial<XtreamChannel> & Record<string, unknown>>>(
      this.buildUrl('get_live_streams', params),
      signal
    );

    if (!Array.isArray(streams)) {
      return [];
    }

    return streams.map((stream) => this.normalizeLiveChannel(stream));
  }

  getLiveStreamUrl(streamId: number, formatOverride?: 'ts' | 'm3u8'): string {
    const format = formatOverride ?? 'ts';
    if (format === 'm3u8') {
      return `${this.serverUrl}/live/${this.username}/${this.password}/${streamId}.m3u8`;
    }
    return `${this.serverUrl}/live/${this.username}/${this.password}/${streamId}.ts`;
  }

  // ── VOD / Movies ───────────────────────────────────────────────────────

  async getVodCategories(signal?: AbortSignal): Promise<XtreamCategory[]> {
    return this.fetchJson<XtreamCategory[]>(
      this.buildUrl('get_vod_categories'),
      signal
    );
  }

  async getVodStreams(categoryId?: string, signal?: AbortSignal): Promise<XtreamMovie[]> {
    const params: Record<string, string> = {};
    if (categoryId) params.category_id = categoryId;
    return this.fetchJson<XtreamMovie[]>(
      this.buildUrl('get_vod_streams', params),
      signal
    );
  }

  getVodStreamUrl(streamId: number, extension: string): string {
    return `${this.serverUrl}/movie/${this.username}/${this.password}/${streamId}.${extension}`;
  }

  // ── Series ──────────────────────────────────────────────────────────────

  async getSeriesCategories(signal?: AbortSignal): Promise<XtreamCategory[]> {
    return this.fetchJson<XtreamCategory[]>(
      this.buildUrl('get_series_categories'),
      signal
    );
  }

  async getSeries(categoryId?: string, signal?: AbortSignal): Promise<XtreamSeries[]> {
    const params: Record<string, string> = {};
    if (categoryId) params.category_id = categoryId;
    return this.fetchJson<XtreamSeries[]>(
      this.buildUrl('get_series', params),
      signal
    );
  }

  async getSeriesInfo(seriesId: number, signal?: AbortSignal): Promise<XtreamSeriesInfo> {
    return this.fetchJson<XtreamSeriesInfo>(
      this.buildUrl('get_series_info', { series_id: String(seriesId) }),
      signal
    );
  }

  getSeriesStreamUrl(streamId: string, extension: string): string {
    return `${this.serverUrl}/series/${this.username}/${this.password}/${streamId}.${extension}`;
  }

  // ── EPG ─────────────────────────────────────────────────────────────────

  async getShortEpg(streamId: number, limit = 4, signal?: AbortSignal): Promise<any> {
    return this.fetchJson(
      this.buildUrl('get_short_epg', {
        stream_id: String(streamId),
        limit: String(limit),
      }),
      signal
    );
  }

  /**
   * Full provider EPG table. Unlike get_short_epg, this commonly includes the
   * historical rows needed to label server-side catch-up recordings.
   */
  async getSimpleDataTable(streamId: number, signal?: AbortSignal): Promise<any> {
    return this.fetchJson(
      this.buildUrl('get_simple_data_table', { stream_id: String(streamId) }),
      signal
    );
  }

  getXmltvUrl(): string {
    return `${this.serverUrl}/xmltv.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}`;
  }

  // ── DVR / Catchup / Timeshift ─────────────────────────────────────────

  /**
   * Build a timeshift (catchup/DVR) stream URL for a past program.
   * Xtream Codes timeshift format:
   *   {serverUrl}/timeshift/{username}/{password}/{duration}/{start}/{stream_id}.ts
   *
   * @param streamId - The live stream ID of the channel
   * @param startTime - The start time of the program (Date or ISO string)
   * @param durationMinutes - Duration in minutes
   */
  getCatchupStreamUrl(streamId: number, startTime: Date | string, durationMinutes: number): string {
    const start = startTime instanceof Date ? startTime : new Date(startTime);
    // Format: YYYY-MM-DD:HH-MM
    const pad = (n: number) => n.toString().padStart(2, '0');
    const startStr = `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}:${pad(start.getHours())}-${pad(start.getMinutes())}`;
    return `${this.serverUrl}/timeshift/${this.username}/${this.password}/${durationMinutes}/${startStr}/${streamId}.ts`;
  }

  /**
   * Alternative catchup format used by some Xtream providers.
   * Streaming URL with start/end parameters.
   */
  getCatchupStreamUrlAlt(streamId: number, startTimestamp: number, endTimestamp: number): string {
    return `${this.serverUrl}/streaming/timeshift.php?username=${encodeURIComponent(this.username)}&password=${encodeURIComponent(this.password)}&stream=${streamId}&start=${startTimestamp}&end=${endTimestamp}`;
  }

  /** Ordered provider archive candidates: standard TS, HLS, then legacy Xtream PHP. */
  getCatchupStreamUrlCandidates(streamId: number, start: Date, durationMinutes: number): string[] {
    const duration = Math.max(1, Math.round(durationMinutes));
    const startTimestamp = Math.floor(start.getTime() / 1000);
    const endTimestamp = startTimestamp + duration * 60;
    // Keep the timestamp convention identical to the original catch-up URL.
    // Providers typically interpret this wall-clock value in the account/EPG
    // timezone, so converting it to UTC here can select the wrong programme.
    const pad = (n: number) => n.toString().padStart(2, '0');
    const startStr = `${start.getFullYear()}-${pad(start.getMonth() + 1)}-${pad(start.getDate())}:${pad(start.getHours())}-${pad(start.getMinutes())}`;
    const standard = (extension: 'ts' | 'm3u8') =>
      `${this.serverUrl}/timeshift/${this.username}/${this.password}/${duration}/${startStr}/${streamId}.${extension}`;
    return [...new Set([
      standard('ts'),
      standard('m3u8'),
      this.getCatchupStreamUrlAlt(streamId, startTimestamp, endTimestamp),
    ])];
  }
}

// Singleton
export const xtreamApi = new XtreamApi();
