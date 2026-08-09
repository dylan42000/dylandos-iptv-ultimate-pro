// ─── VideoPlayerEngine — Dual-engine smart router ────────────────────────────
// Windows has MPV + HLS.js only. Policy:
//   • Live TV AND VOD / DVR → MPV primary, HLS.js fallback
// ──────────────────────────────────────────────────────────────────────────────

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { MpvPlayer, EpgProgram } from './MpvPlayer';
import { VideoPlayer } from './VideoPlayer';
import { useSettings } from '../hooks/useSettings';
import { AppSettings } from '../types/settings';
import { XtreamChannel } from '../types/xtream';
import { EPGProgram } from '../types/epg';
import { StreamResilienceEngine } from '../services/streamResilience';

type PlayerEngine = 'mpv' | 'hlsjs';

export interface VideoPlayerEngineProps {
  streamUrl: string | null;
  streamType: 'live' | 'vod';
  streamTitle: string;
  settings?: AppSettings;
  activeChannel?: XtreamChannel | null;
  currentProgram?: EPGProgram | null;
  onBack?: () => void;
  onEnded?: () => void;
  onChannelUp?: () => void;
  onChannelDown?: () => void;
  startPosition?: number;
  epgCurrentProgram?: EpgProgram | null;
  epgNextProgram?: { title: string; startTime: number } | null;
  epgUpcomingPrograms?: { title: string; startTime: number }[];
  channelLogo?: string;
  fallbackStreamUrls?: string[];
  // These come through for the hls.js fallback
  onPositionUpdate?: (position: number, duration: number) => void;
  onError?: (error: string) => void;
}

export const VideoPlayerEngine: React.FC<VideoPlayerEngineProps> = (props) => {
  const { settings: fallbackSettings } = useSettings();
  const settings = props.settings ?? fallbackSettings;
  const [mpvAvailable, setMpvAvailable] = useState(() => Boolean(window.electronAPI));
  const [forceHlsFallback, setForceHlsFallback] = useState(false);
  const [reconnectStatus, setReconnectStatus] = useState<{ attempt: number; max: number } | null>(null);
  const [retryKey, setRetryKey] = useState(0);
  const [showZapOverlay, setShowZapOverlay] = useState(false);
  const [playbackUrl, setPlaybackUrl] = useState<string | null>(props.streamUrl);
  const resilienceRef = useRef<StreamResilienceEngine | null>(null);
  const zapOverlayTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const showEngineBadge = true;
  // MPV main-process owns live reconnect. UI resilience only for HLS.js fallback.
  const mpvOwnsReconnect = Boolean(window.electronAPI);

  const playbackUrls = useMemo(() => {
    const urls = [props.streamUrl, ...(props.fallbackStreamUrls ?? [])]
      .filter((url): url is string => Boolean(url?.trim()));
    return Array.from(new Set(urls));
  }, [props.streamUrl, props.fallbackStreamUrls]);

  useEffect(() => {
    setPlaybackUrl(props.streamUrl);
  }, [props.streamUrl]);

  const tryNextVodFallback = useCallback(() => {
    if (props.streamType !== 'vod' || playbackUrls.length <= 1) {
      return false;
    }
    const currentIndex = Math.max(0, playbackUrls.indexOf(playbackUrl ?? ''));
    const nextUrl = playbackUrls[currentIndex + 1];
    if (!nextUrl) {
      return false;
    }
    setPlaybackUrl(nextUrl);
    setForceHlsFallback(false);
    setRetryKey(k => k + 1);
    return true;
  }, [playbackUrl, playbackUrls, props.streamType]);

  // ─── Stream Resilience — HLS.js fallback ONLY (MPV owns live reconnect) ──
  useEffect(() => {
    if (!playbackUrl || props.streamType !== 'live' || mpvOwnsReconnect) {
      resilienceRef.current?.dispose();
      resilienceRef.current = null;
      return;
    }

    const engine = new StreamResilienceEngine({
      maxRetries: settings.liveReconnectMaxAttempts ?? 5,
      baseDelayMs: settings.liveReconnectBaseDelayMs ?? 2000,
      onReconnecting: (attempt, max) => setReconnectStatus({ attempt, max }),
      onReconnected: () => { setReconnectStatus(null); setRetryKey(k => k + 1); },
      onGaveUp: () => {
        setReconnectStatus(null);
        setForceHlsFallback(true);
      },
      onRestart: async () => {
        setRetryKey(k => k + 1);
      },
    });

    resilienceRef.current?.dispose();
    resilienceRef.current = engine;

    return () => { engine.dispose(); resilienceRef.current = null; };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [playbackUrl, props.streamType, mpvOwnsReconnect]);

  // Surface MPV reconnect status in the UI banner (single owner = main process)
  useEffect(() => {
    const electronAPI = window.electronAPI;
    if (!electronAPI || !mpvOwnsReconnect) return;

    const onReconnecting = ({ attempt, maxAttempts }: { attempt?: number; maxAttempts?: number }) => {
      setReconnectStatus({
        attempt: attempt ?? 1,
        max: maxAttempts ?? settings.liveReconnectMaxAttempts ?? 10,
      });
    };
    const onStarted = () => setReconnectStatus(null);
    const onStopped = () => setReconnectStatus(null);

    electronAPI.on('mpv:reconnecting', onReconnecting);
    electronAPI.on('mpv:playback-started', onStarted);
    electronAPI.on('mpv:playback-stopped', onStopped);
    return () => {
      electronAPI.off('mpv:reconnecting', onReconnecting);
      electronAPI.off('mpv:playback-started', onStarted);
      electronAPI.off('mpv:playback-stopped', onStopped);
    };
  }, [mpvOwnsReconnect, settings.liveReconnectMaxAttempts]);

  // ─── Listen for MPV availability events ───────────────────────────────────
  useEffect(() => {
    const electronAPI = window.electronAPI;
    if (!electronAPI) {
      setMpvAvailable(false);
      return;
    }

    const handleUnavailable = () => {
      setMpvAvailable(false);
      setForceHlsFallback(true);
    };
    const handleReady = () => {
      setMpvAvailable(true);
    };

    electronAPI.on('mpv:unavailable', handleUnavailable);
    electronAPI.on('mpv:ready', handleReady);

    return () => {
      electronAPI.off('mpv:unavailable', handleUnavailable);
      electronAPI.off('mpv:ready', handleReady);
    };
  }, []);

  // ─── If MPV fails during playback, fail over to HLS engine ───────────────
  useEffect(() => {
    const electronAPI = window.electronAPI;
    if (!electronAPI) return;

    const consecutiveErrors = { count: 0 };

    const handleMpvError = ({ fatal }: { fatal?: boolean }) => {
      consecutiveErrors.count++;
      if (fatal || consecutiveErrors.count >= 3) {
        if (tryNextVodFallback()) {
          consecutiveErrors.count = 0;
          return;
        }
        // MPV owns live reconnect — only fall back to HLS after fatal exhaustion
        if (props.streamType === 'live' && mpvOwnsReconnect) {
          if (fatal) {
            setForceHlsFallback(true);
          }
          consecutiveErrors.count = 0;
          return;
        }
        if (resilienceRef.current && !forceHlsFallback) {
          resilienceRef.current.handleError();
        } else {
          setForceHlsFallback(true);
        }
        consecutiveErrors.count = 0;
      }
    };

    const handlePlaybackStarted = () => {
      consecutiveErrors.count = 0;
      resilienceRef.current?.onPlaybackResumed();
    };

    electronAPI.on('mpv:error', handleMpvError);
    electronAPI.on('mpv:playback-started', handlePlaybackStarted);
    return () => {
      electronAPI.off('mpv:error', handleMpvError);
      electronAPI.off('mpv:playback-started', handlePlaybackStarted);
    };
  }, [forceHlsFallback, props.streamType, tryNextVodFallback, mpvOwnsReconnect]);

  useEffect(() => {
    setForceHlsFallback(false);
  }, [playbackUrl]);

  useEffect(() => {
    if (props.streamType !== 'live' || !playbackUrl) {
      if (zapOverlayTimerRef.current) {
        clearTimeout(zapOverlayTimerRef.current);
        zapOverlayTimerRef.current = null;
      }
      setShowZapOverlay(false);
      return;
    }

    setShowZapOverlay(true);

    if (zapOverlayTimerRef.current) {
      clearTimeout(zapOverlayTimerRef.current);
    }

    zapOverlayTimerRef.current = setTimeout(() => {
      setShowZapOverlay(false);
      zapOverlayTimerRef.current = null;
    }, 2500);

    return () => {
      if (zapOverlayTimerRef.current) {
        clearTimeout(zapOverlayTimerRef.current);
        zapOverlayTimerRef.current = null;
      }
    };
  }, [props.streamType, playbackUrl, props.streamTitle]);

  const preferredEngine = settings.preferredEngine ?? 'mpv';
  const activeEngine = useMemo<PlayerEngine>(() => {
    if (preferredEngine === 'mpv' && mpvAvailable && !forceHlsFallback) {
      return 'mpv';
    }
    return 'hlsjs';
  }, [forceHlsFallback, mpvAvailable, preferredEngine]);

  const engineBadgeLabel = useMemo(() => {
    const kind = props.streamType === 'live' ? 'LIVE' : 'VOD';
    if (activeEngine === 'mpv') return `MPV • ${kind}`;
    return preferredEngine === 'mpv' ? `HLS.js fallback • ${kind}` : `HLS.js • ${kind}`;
  }, [activeEngine, preferredEngine, props.streamType]);

  return (
    <div className={`relative w-full h-full${activeEngine === 'hlsjs' ? ' bg-black' : ''}`}>
      {/* Engine badge */}
      {showEngineBadge && (
        <div className="absolute top-2 left-2 z-50 rounded-md border border-white/10 bg-black/75 px-2.5 py-1 text-[11px] font-semibold uppercase tracking-[0.14em] text-cyan-200 shadow-lg pointer-events-none">
          Player: {engineBadgeLabel}
        </div>
      )}

      {/* Reconnect banner */}
      {reconnectStatus && (
        <div className="absolute top-0 inset-x-0 z-50 flex items-center justify-center gap-3 py-2 bg-yellow-500/20 border-b border-yellow-500/30 backdrop-blur-sm pointer-events-none">
          <div className="w-3 h-3 rounded-full bg-yellow-400 animate-pulse" />
          <span className="text-yellow-300 text-sm font-medium">
            Reconnecting… attempt {reconnectStatus.attempt} of {reconnectStatus.max}
          </span>
        </div>
      )}

      {showZapOverlay && props.streamType === 'live' && (
        <div className="pointer-events-none absolute inset-0 z-40 flex items-center justify-center px-4">
          <div className="w-full max-w-4xl rounded-xl border border-white/15 bg-black/72 px-5 py-4 shadow-2xl backdrop-blur-xl">
            <div className="mb-3 flex items-center justify-between gap-4">
              <div className="min-w-0">
                <p className="text-[10px] font-semibold uppercase tracking-[0.22em] text-white/45">Channel</p>
                <p className="truncate text-2xl font-bold text-white">{props.streamTitle}</p>
              </div>
              <div className="rounded-md border border-cyan-300/25 bg-cyan-300/10 px-2.5 py-1 text-[10px] font-bold uppercase tracking-[0.14em] text-cyan-200">
                Live
              </div>
            </div>
            <div className="grid gap-3 md:grid-cols-4">
              <div className="rounded-lg border border-cyan-300/25 bg-cyan-300/10 p-3">
                <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-cyan-200">Now</p>
                <p className="mt-1 line-clamp-2 text-sm font-semibold text-white">
                  {props.currentProgram?.title || props.epgCurrentProgram?.title || 'No guide data available'}
                </p>
              </div>
              {(props.epgUpcomingPrograms?.length ? props.epgUpcomingPrograms : props.epgNextProgram ? [props.epgNextProgram] : []).slice(0, 3).map((program, index) => (
                <div key={`${program.startTime}-${index}`} className="rounded-lg border border-white/10 bg-white/[0.06] p-3">
                  <p className="text-[10px] font-bold uppercase tracking-[0.16em] text-white/45">
                    {index === 0 ? 'Next' : `Later ${index + 1}`}
                  </p>
                  <p className="mt-1 line-clamp-2 text-sm font-semibold text-white/85">{program.title}</p>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}

      {activeEngine === 'mpv' ? (
        <MpvPlayer
          key={retryKey}
          streamUrl={playbackUrl}
          streamType={props.streamType}
          streamTitle={props.streamTitle}
          onBack={props.onBack}
          onEnded={props.onEnded}
          onChannelUp={props.onChannelUp}
          onChannelDown={props.onChannelDown}
          startPosition={props.startPosition}
          epgCurrentProgram={props.epgCurrentProgram}
          epgNextProgram={props.epgNextProgram}
          channelLogo={props.channelLogo}
          activeChannel={props.activeChannel}
          currentProgram={props.currentProgram}
        />
      ) : (
        <VideoPlayer
          key={`${retryKey}-${playbackUrl ?? ''}`}
          streamUrl={playbackUrl}
          streamTitle={props.streamTitle}
          activeChannel={props.activeChannel}
          currentProgram={props.currentProgram}
          streamType={props.streamType}
          settings={settings}
          onBack={props.onBack}
          onPositionUpdate={props.onPositionUpdate}
          onEnded={props.onEnded}
          onError={(message) => {
            if (!tryNextVodFallback()) props.onError?.(message);
          }}
        />
      )}
    </div>
  );
};
