// ─── Xtream Codes API Types ─────────────────────────────────────────────────

export interface XtreamProfile {
  id: string;
  name: string;
  username: string;
  password: string;
  serverUrl: string;
  createdAt?: number;
  // Per-account preferences
  enableDvr?: boolean;
  enableRecording?: boolean;
}

export interface XtreamAuth {
  user_info: {
    username: string;
    password: string;
    auth: number;
    status: string;
    exp_date: string;
    is_trial: string;
    active_cons: string;
    created_at: string;
    max_connections: string;
    allowed_output_formats: string[];
  };
  server_info: {
    url: string;
    port: string;
    https_port: string;
    server_protocol: string;
    rtmp_port: string;
    timezone: string;
    timestamp_now: number;
    time_now: string;
  };
}

export interface XtreamCategory {
  category_id: string;
  category_name: string;
  parent_id: number;
}

export interface XtreamChannel {
  num: number;
  name: string;
  stream_display_name?: string;
  display_name?: string;
  tvg_name?: string;
  channel_name?: string;
  stream_type: string;
  stream_id: number;
  stream_icon: string;
  epg_channel_id: string | null;
  tvg_id?: string | null;
  xmltv_id?: string | null;
  channel_id?: string | number | null;
  added: string;
  category_id: string;
  category_ids: number[];
  custom_sid: string;
  tv_archive: number;
  direct_source: string;
  tv_archive_duration: number;
}

export interface XtreamMovie {
  num: number;
  name: string;
  stream_type: string;
  stream_id: number;
  stream_icon: string;
  rating: string;
  rating_5based: number;
  added: string;
  category_id: string;
  category_ids: number[];
  container_extension: string;
  custom_sid: string;
  direct_source: string;
  plot?: string;
  cast?: string;
  director?: string;
  genre?: string;
  release_date?: string;
  duration?: string;
  duration_secs?: number;
  tmdb_id?: number;
}

export interface XtreamSeries {
  num: number;
  name: string;
  series_id: number;
  cover: string;
  plot: string;
  cast: string;
  director: string;
  genre: string;
  release_date: string;
  last_modified: string;
  rating: string;
  rating_5based: number;
  backdrop_path: string[];
  youtube_trailer: string;
  episode_run_time: string;
  category_id: string;
  category_ids: number[];
}

export interface XtreamSeriesInfo {
  seasons: XtreamSeason[];
  info: XtreamSeries;
  episodes: Record<string, XtreamEpisode[]>;
}

export interface XtreamSeason {
  air_date: string;
  episode_count: number;
  id: number;
  name: string;
  overview: string;
  season_number: number;
  cover: string;
  cover_big: string;
}

export interface XtreamEpisode {
  id: string;
  episode_num: number;
  title: string;
  container_extension: string;
  info: {
    movie_image?: string;
    plot?: string;
    duration_secs?: number;
    duration?: string;
    rating?: number;
    name?: string;
    season?: number;
  };
  custom_sid: string;
  added: string;
  season: number;
  direct_source: string;
}

// Full user_info from Xtream Codes authentication response
export interface XtreamUserInfo {
  username: string;
  password: string;
  auth: number;                      // 1 = authenticated
  status: string;                    // 'Active' | 'Expired' | 'Banned'
  exp_date: string | null;           // Unix timestamp string, or null for lifetime
  is_trial: string;                  // '0' or '1'
  active_cons: string;
  created_at: string;
  max_connections: string;
  allowed_output_formats: string[];  // ['m3u8', 'ts', 'rtmpe']
  message?: string;
}

export type StreamType = 'live' | 'movie' | 'series';

export interface WatchProgress {
  key: string;
  streamId?: string;
  position: number;
  duration: number;
  percentage: number;
  lastWatched: number;
  title?: string;
  logo?: string;
  streamType: StreamType;
}
