// ─── EPG / XMLTV Types ──────────────────────────────────────────────────────

export interface EPGProgram {
  channelId: string;
  title: string;
  description: string;
  startTime: string;   // ISO 8601
  stopTime: string;     // ISO 8601
  category?: string;
  icon?: string;
  subtitle?: string;
  rating?: string;
}

export interface EPGChannel {
  id: string;
  displayName: string;
  /** XMLTV feeds may provide several display-name aliases for one channel. */
  aliases?: string[];
  icon?: string;
}

export interface EPGData {
  channels: Record<string, EPGChannel>;
  programs: Record<string, EPGProgram[]>; // keyed by channel ID
}

export interface XMLTVSource {
  url: string;
  name?: string;
  enabled: boolean;
}
