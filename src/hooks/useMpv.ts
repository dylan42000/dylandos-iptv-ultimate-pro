// ─── useMpv — React hook for MPV player state & controls ─────────────────────
// Listens to all IPC events from the MPV manager in main process and exposes
// a clean actions interface. Used by MpvPlayer.tsx.
// ──────────────────────────────────────────────────────────────────────────────

import { useState, useEffect, useCallback, useRef } from 'react';
import { useSettings } from './useSettings';

// ─── Types ────────────────────────────────────────────────────────────────────

export interface MpvTrack {
  id: number;
  title: string;
  lang: string;
  selected: boolean;
  type: 'audio' | 'sub' | 'video';
  codec: string;
}

export interface MpvPlayerState {
  isPlaying: boolean;
  isLoading: boolean;
  isPaused: boolean;
  isBuffering: boolean;
  error: string | null;
  position: number;
  duration: number;
  volume: number;       // 0–1
  muted: boolean;
  bufferingPercent: number;
  videoBitrate: number;
  audioBitrate: number;
  videoCodec: string;
  audioCodec: string;
  hwdecActive: string;  // 'no' or the codec name e.g. 'd3d11va'
  audioTracks: MpvTrack[];
  subtitleTracks: MpvTrack[];
  activeAudioTrack: number;
  activeSubtitleTrack: number | 'no';
  reconnectAttempt: number;
  reconnectMaxAttempts: number;
  reconnectDelayMs: number;
  streamType: 'live' | 'vod' | null;
  isAvailable: boolean;
  cacheDuration: number;
  cacheState: any;
  timeshiftOffset: number;
}

const INITIAL_STATE: MpvPlayerState = {
  isPlaying: false,
  isLoading: false,
  isPaused: false,
  isBuffering: false,
  error: null,
  position: 0,
  duration: 0,
  volume: 1,
  muted: false,
  bufferingPercent: 0,
  videoBitrate: 0,
  audioBitrate: 0,
  videoCodec: '',
  audioCodec: '',
  hwdecActive: 'no',
  audioTracks: [],
  subtitleTracks: [],
  activeAudioTrack: 1,
  activeSubtitleTrack: 'no',
  reconnectAttempt: 0,
  reconnectMaxAttempts: 10,
  reconnectDelayMs: 0,
  streamType: null,
  isAvailable: true,
  cacheDuration: 0,
  cacheState: null,
  timeshiftOffset: 0,
};

// ─── Helper: parse track-list from MPV ───────────────────────────────────────

function parseTrackList(rawList: any[]): { audio: MpvTrack[]; subtitles: MpvTrack[] } {
  const audio: MpvTrack[] = [];
  const subtitles: MpvTrack[] = [];

  (rawList ?? []).forEach((track: any) => {
    const t: MpvTrack = {
      id: track.id,
      title: track.title ?? track.lang ?? `Track ${track.id}`,
      lang: track.lang ?? 'und',
      selected: track.selected ?? false,
      type: track.type,
      codec: track.codec ?? '',
    };
    if (track.type === 'audio') audio.push(t);
    if (track.type === 'sub')   subtitles.push(t);
  });

  return { audio, subtitles };
}

// ─── Hook ─────────────────────────────────────────────────────────────────────

export function useMpv() {
  const [state, setState] = useState<MpvPlayerState>(INITIAL_STATE);
  const { settings } = useSettings();
  // Stable ref so async event handlers don't close over stale state
  const stateRef = useRef(state);
  stateRef.current = state;
  // Cancels stale live loads from rapid channel zaps
  const loadGenRef = useRef(0);

  // ─── Listen to MPV events from main process ────────────────────────────────
  useEffect(() => {
    const electronAPI = window.electronAPI;
    if (!electronAPI) return;

    const handlers: Record<string, (data: any) => void> = {
      'mpv:ready': () => {
        setState(prev => ({ ...prev, isAvailable: true }));
      },

      'mpv:unavailable': ({ reason }: any) => {
        console.warn('[useMpv] MPV unavailable:', reason);
        setState(prev => ({ ...prev, isAvailable: false }));
      },

      'mpv:loading': ({ type }: any) => {
        setState(prev => ({
          ...prev,
          isLoading: true,
          error: null,
          streamType: type,
          position: 0,
          duration: 0,
          audioTracks: [],
          subtitleTracks: [],
        }));
      },

      'mpv:playback-started': () => {
        setState(prev => ({
          ...prev,
          isLoading: false,
          isPlaying: true,
          isPaused: false,
          error: null,
          reconnectAttempt: 0,
        }));
      },

      'mpv:playback-stopped': () => {
        setState(prev => ({
          ...prev,
          isPlaying: false,
          isPaused: false,
          isLoading: false,
        }));
      },

      'mpv:paused': () => {
        setState(prev => ({ ...prev, isPaused: true, isPlaying: false }));
      },

      'mpv:resumed': () => {
        setState(prev => ({ ...prev, isPaused: false, isPlaying: true }));
      },

      'mpv:status': (status: any) => {
        const { audio, subtitles } = parseTrackList(status.trackList);
        setState(prev => ({
          ...prev,
          isBuffering: status.pausedForCache,
          bufferingPercent: status.bufferingPercent,
          videoBitrate: status.videoBitrate,
          audioBitrate: status.audioBitrate,
          videoCodec: status.videoCodec,
          audioCodec: status.audioCodec,
          hwdecActive: status.hwdecActive,
          audioTracks: audio.length ? audio : prev.audioTracks,
          subtitleTracks: subtitles.length ? subtitles : prev.subtitleTracks,
          duration: status.duration || prev.duration,
          volume: typeof status.volume === 'number' ? status.volume / 100 : prev.volume,
          muted: status.mute ?? prev.muted,
        }));
      },

      'mpv:poll': ({ position, duration, pause, volume, mute, cacheDuration, cacheState }: any) => {
        let timeshiftOffset = 0;
        if (cacheState && typeof cacheState['cache-end'] === 'number') {
          timeshiftOffset = Math.max(0, cacheState['cache-end']);
        }
        setState(prev => ({
          ...prev,
          position,
          duration: duration || prev.duration,
          isPaused: pause,
          // Trust MPV's actual pause state: if unpaused → playing; if paused → keep previous isPlaying
          isPlaying: !pause ? true : prev.isPlaying,
          // Fallback: if isLoading is still true but MPV is actively playing
          // (mpv:playback-started event was missed or arrived out of order),
          // clear the loading overlay so the OSD becomes visible above the video.
          isLoading: prev.isLoading && !pause ? false : prev.isLoading,
          error: prev.isLoading && !pause ? null : prev.error,
          volume: typeof volume === 'number' ? volume : prev.volume,
          muted: mute ?? prev.muted,
          cacheDuration: typeof cacheDuration === 'number' ? cacheDuration : prev.cacheDuration,
          cacheState: cacheState ?? prev.cacheState,
          timeshiftOffset,
        }));
      },

      'mpv:timeposition': ({ seconds }: any) => {
        setState(prev => ({ ...prev, position: seconds }));
      },

      'mpv:error': ({ message, fatal }: any) => {
        setState(prev => ({
          ...prev,
          error: message,
          isLoading: false,
          isPlaying: fatal ? false : prev.isPlaying,
        }));
      },

      'mpv:crashed': () => {
        setState(prev => ({
          ...prev,
          error: 'MPV crashed — attempting restart...',
          isPlaying: false,
          isLoading: true,
        }));
      },

      'mpv:reconnecting': ({ attempt, maxAttempts, delayMs }: any) => {
        setState(prev => ({
          ...prev,
          isLoading: true,
          isPlaying: false,
          reconnectAttempt: attempt,
          reconnectMaxAttempts: maxAttempts,
          reconnectDelayMs: delayMs,
        }));
      },

      'mpv:vod-ended': () => {
        setState(prev => ({ ...prev, isPlaying: false, isPaused: false }));
      },

      'mpv:stopped': () => {
        setState(prev => ({
          ...prev,
          isPlaying: false,
          isPaused: false,
          isLoading: false,
          position: 0,
        }));
      },
    };

    // Register all handlers
    Object.entries(handlers).forEach(([channel, handler]) => {
      electronAPI.on(channel, handler);
    });

    return () => {
      Object.entries(handlers).forEach(([channel, handler]) => {
        electronAPI.off(channel, handler);
      });
    };
  }, []);

  // ─── Sync subtitle settings when they change ──────────────────────────────
  useEffect(() => {
    if (!state.isAvailable || !window.electronAPI) return;
    window.electronAPI.invoke('mpv:apply-subtitle-settings', {
      subtitleFontSize: settings.subtitleFontSize,
      subtitleFontColor: settings.subtitleFontColor,
      subtitleOutlineWidth: settings.subtitleOutlineWidth,
      subtitleBackgroundOpacity: settings.subtitleBackgroundOpacity,
    }).catch(() => {});
  }, [
    settings.subtitleFontSize,
    settings.subtitleFontColor,
    settings.subtitleOutlineWidth,
    settings.subtitleBackgroundOpacity,
    state.isAvailable,
  ]);

  // ─── Actions ──────────────────────────────────────────────────────────────

  const loadLiveStream = useCallback(async (url: string) => {
    if (!window.electronAPI) return;
    const loadGen = ++loadGenRef.current;
    try {
      await window.electronAPI.invoke('mpv:load-live', {
        url,
        settings: {
          liveBufferPreset: settings.liveBufferPreset,
          liveTimeshiftBufferSize: settings.liveTimeshiftBufferSize || 'large',
          hardwareDecode: settings.hardwareDecode,
          liveTimeshiftEnabled: settings.liveTimeshiftEnabled !== false,
          audioOffsetMs: settings.audioOffsetMs,
          subtitleFontSize: settings.subtitleFontSize,
          subtitleFontColor: settings.subtitleFontColor,
          subtitleOutlineWidth: settings.subtitleOutlineWidth,
          subtitleBackgroundOpacity: settings.subtitleBackgroundOpacity,
          mpvHwdecMode: (settings as any).mpvHwdecMode,
          mpvScaler: (settings as any).mpvScaler,
          mpvDeinterlace: (settings as any).mpvDeinterlace,
          mpvVoiceAmplify: (settings as any).mpvVoiceAmplify,
        },
      });
      // Stale zap — a newer loadLiveStream won the race
      if (loadGen !== loadGenRef.current) return;
    } catch (err: any) {
      if (loadGen !== loadGenRef.current) return;
      const message = err?.message || 'Failed to load live stream in MPV';
      setState(prev => ({
        ...prev,
        isLoading: false,
        isPlaying: false,
        isAvailable: !/not initialized/i.test(message),
        error: /not initialized/i.test(message) ? null : message,
      }));
    }
  }, [settings]);

  const loadVodStream = useCallback(async (url: string, startPosition = 0) => {
    if (!window.electronAPI) return;
    try {
      await window.electronAPI.invoke('mpv:load-vod', {
        url,
        startPosition,
        settings: {
          hardwareDecode: settings.hardwareDecode,
          audioOffsetMs: settings.audioOffsetMs,
          subtitleFontSize: settings.subtitleFontSize,
          subtitleFontColor: settings.subtitleFontColor,
          subtitleOutlineWidth: settings.subtitleOutlineWidth,
          subtitleBackgroundOpacity: settings.subtitleBackgroundOpacity,
          mpvHwdecMode: (settings as any).mpvHwdecMode,
          mpvScaler: (settings as any).mpvScaler,
          mpvDeinterlace: (settings as any).mpvDeinterlace,
          mpvVoiceAmplify: (settings as any).mpvVoiceAmplify,
        },
      });
    } catch (err: any) {
      const message = err?.message || 'Failed to load VOD stream in MPV';
      setState(prev => ({
        ...prev,
        isLoading: false,
        isPlaying: false,
        isAvailable: !/not initialized/i.test(message),
        error: /not initialized/i.test(message) ? null : message,
      }));
    }
  }, [settings]);

  const play            = useCallback(() => window.electronAPI?.invoke('mpv:play'), []);
  const pause           = useCallback(() => window.electronAPI?.invoke('mpv:pause'), []);
  const togglePlayPause = useCallback(() => window.electronAPI?.invoke('mpv:toggle-pause'), []);
  const stop            = useCallback(() => window.electronAPI?.invoke('mpv:stop'), []);

  const seek = useCallback((seconds: number) =>
    window.electronAPI?.invoke('mpv:seek', { seconds }), []);

  const seekRelative = useCallback((delta: number) =>
    window.electronAPI?.invoke('mpv:seek-relative', { delta }), []);

  const jumpToLive = useCallback(() =>
    window.electronAPI?.invoke('mpv:jump-to-live'), []);

  const setVolume = useCallback((level: number) =>
    window.electronAPI?.invoke('mpv:set-volume', { level }), []);

  const setMute = useCallback((muted: boolean) =>
    window.electronAPI?.invoke('mpv:set-mute', { muted }), []);

  const setAudioTrack = useCallback((trackId: number) =>
    window.electronAPI?.invoke('mpv:set-audio-track', { trackId }), []);

  const setSubtitleTrack = useCallback((trackId: number | string) =>
    window.electronAPI?.invoke('mpv:set-sub-track', { trackId }), []);

  const setAudioDelay = useCallback((offsetMs: number) =>
    window.electronAPI?.invoke('mpv:set-audio-delay', { offsetMs }), []);

  const takeScreenshot = useCallback(() =>
    window.electronAPI?.invoke('mpv:screenshot'), []);

  const sendRawCommand = useCallback((command: string, args: any[] = []) =>
    window.electronAPI?.invoke('mpv:send-raw-command', { command, args }), []);

  return {
    state,
    actions: {
      loadLiveStream,
      loadVodStream,
      play,
      pause,
      togglePlayPause,
      stop,
      seek,
      seekRelative,
      jumpToLive,
      setVolume,
      setMute,
      setAudioTrack,
      setSubtitleTrack,
      setAudioDelay,
      takeScreenshot,
      sendRawCommand,
    },
  };
}
