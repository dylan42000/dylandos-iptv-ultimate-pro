// ─── MpvPlayer — Full-featured MPV OSD component ─────────────────────────────
// MPV renders video in its native layer. This component provides the entire
// custom OSD: top bar, seek bar, controls, EPG overlay, track menus, and
// keyboard shortcuts. It communicates with MPV via useMpv() → IPC.
// ──────────────────────────────────────────────────────────────────────────────

import React, { useRef, useEffect, useCallback, useState } from 'react';
import {
  Play,
  Pause,
  Square,
  Volume2,
  VolumeX,
  ChevronLeft,
  ChevronRight,
  Mic,
  Subtitles,
  Camera,
  WifiOff,
  RotateCcw,
  AlertCircle,
  Loader2,
  BarChart2,
  Circle,
} from 'lucide-react';
import { useMpv } from '../hooks/useMpv';
import { useSettings } from '../hooks/useSettings';
import { MpvStatsOverlay } from './MpvStatsOverlay';
import { RecordingButton } from './RecordingButton';
import type { XtreamChannel } from '../types/xtream';
import type { EPGProgram } from '../types/epg';

// ─── Props ────────────────────────────────────────────────────────────────────

export interface EpgProgram {
  title: string;
  startTime: number; // ms timestamp
  endTime: number;
}

interface MpvPlayerProps {
  streamUrl: string | null;
  streamType: 'live' | 'vod';
  streamTitle: string;
  onBack?: () => void;
  onEnded?: () => void;
  onChannelUp?: () => void;
  onChannelDown?: () => void;
  startPosition?: number;
  epgCurrentProgram?: EpgProgram | null;
  epgNextProgram?: { title: string; startTime: number } | null;
  channelLogo?: string;
  activeChannel?: XtreamChannel | null;
  currentProgram?: EPGProgram | null;
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '0:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0)
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function epgProgressPct(startTime: number, endTime: number): number {
  const now = Date.now();
  const total = endTime - startTime;
  if (total <= 0) return 0;
  return Math.min(100, Math.max(0, ((now - startTime) / total) * 100));
}

// Buffer % → quality label
function bufferQuality(pct: number): 'critical' | 'poor' | 'good' | 'excellent' {
  if (pct < 25) return 'critical';
  if (pct < 50) return 'poor';
  if (pct < 80) return 'good';
  return 'excellent';
}

const QUALITY_COLORS = {
  critical: '#ef4444',
  poor: '#eab308',
  good: '#00FFFF',
  excellent: '#22c55e',
} as const;

// ─── Component ────────────────────────────────────────────────────────────────

export const MpvPlayer: React.FC<MpvPlayerProps> = ({
  streamUrl,
  streamType,
  streamTitle,
  onBack,
  onEnded,
  onChannelUp,
  onChannelDown,
  startPosition = 0,
  epgCurrentProgram,
  epgNextProgram,
  channelLogo,
  activeChannel,
  currentProgram,
}) => {
  const { state, actions } = useMpv();
  const { settings } = useSettings();

  const [osdVisible, setOsdVisible]     = useState(true);
  const [showAudioMenu, setShowAudioMenu] = useState(false);
  const [showSubMenu, setShowSubMenu]   = useState(false);
  const [showStats, setShowStats]       = useState(false);

  const osdTimerRef  = useRef<ReturnType<typeof setTimeout> | null>(null);
  const zapDebounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);

  // ─── Signal the main process to sync the video canvas window ──────────────
  // mpvVideoWin is a dedicated BrowserWindow that lives behind the transparent
  // mainWin. Calling show-video-window re-asserts that mainWin stays on top so
  // the OSD overlay is always visible above MPV's native video rendering.
  useEffect(() => {
    const repaintTimers: Array<ReturnType<typeof setTimeout>> = [];
    let disposed = false;

    const showAndRepaint = async () => {
      try {
        await window.electronAPI?.invoke?.('mpv:show-video-window');
      } catch {
        // Non-fatal: stream load path will still attempt playback.
      }

      if (disposed) return;

      // Trigger multiple compositor re-paints to survive slow DWM map timing.
      [120, 420, 900].forEach((delay) => {
        const timer = setTimeout(() => {
          window.electronAPI?.invoke?.('window:force-repaint')?.catch?.(() => {});
        }, delay);
        repaintTimers.push(timer);
      });
    };

    void showAndRepaint();

    return () => {
      disposed = true;
      repaintTimers.forEach((timer) => clearTimeout(timer));
      window.electronAPI?.invoke?.('mpv:hide-video-window')?.catch?.(() => {});
      // Ensure no hidden MPV audio continues if engine falls back/unmounts.
      window.electronAPI?.invoke?.('mpv:stop')?.catch?.(() => {});
    };
  }, []);

  // ─── Load stream when URL changes (debounced zap for live) ────────────────
  useEffect(() => {
    if (!streamUrl) return;

    if (zapDebounceRef.current) {
      clearTimeout(zapDebounceRef.current);
      zapDebounceRef.current = null;
    }

    const delayMs = streamType === 'live' ? 180 : 0;
    zapDebounceRef.current = setTimeout(() => {
      zapDebounceRef.current = null;
      if (streamType === 'live') {
        actions.loadLiveStream(streamUrl);
      } else {
        actions.loadVodStream(streamUrl, startPosition);
      }
    }, delayMs);

    return () => {
      if (zapDebounceRef.current) {
        clearTimeout(zapDebounceRef.current);
        zapDebounceRef.current = null;
      }
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [streamUrl, streamType]);

  // ─── VOD ended callback ───────────────────────────────────────────────────
  useEffect(() => {
    const handler = () => onEnded?.();
    window.electronAPI?.on('mpv:vod-ended', handler);
    return () => window.electronAPI?.off('mpv:vod-ended', handler);
  }, [onEnded]);

  // ─── OSD auto-hide ────────────────────────────────────────────────────────
  const revealOsd = useCallback(() => {
    setOsdVisible(true);
    if (osdTimerRef.current) clearTimeout(osdTimerRef.current);
    osdTimerRef.current = setTimeout(() => {
      if (state.isPlaying) {
        setOsdVisible(false);
        setShowAudioMenu(false);
        setShowSubMenu(false);
      }
    }, settings.osdTimeoutMs ?? 5000);
  }, [state.isPlaying, settings.osdTimeoutMs]);

  // ─── Keyboard shortcuts ───────────────────────────────────────────────────
  useEffect(() => {
    const handleKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement).tagName;
      if (['INPUT', 'TEXTAREA', 'SELECT'].includes(tag)) return;

      revealOsd();

      switch (e.key) {
        case ' ':
        case 'k':
          e.preventDefault();
          actions.togglePlayPause();
          break;
        case 'ArrowRight':
          e.preventDefault();
          if (streamType === 'vod') {
            actions.seekRelative(e.shiftKey ? 30 : e.ctrlKey ? 60 : 10);
          } else {
            onChannelUp?.();
          }
          break;
        case 'ArrowLeft':
          e.preventDefault();
          if (streamType === 'vod') {
            actions.seekRelative(e.shiftKey ? -30 : e.ctrlKey ? -60 : -10);
          } else {
            onChannelDown?.();
          }
          break;
        case 'ArrowUp':
          e.preventDefault();
          actions.setVolume(Math.min(1, state.volume + 0.05));
          break;
        case 'ArrowDown':
          e.preventDefault();
          actions.setVolume(Math.max(0, state.volume - 0.05));
          break;
        case 'm':
          actions.setMute(!state.muted);
          break;
        case 's':
          actions.stop();
          onBack?.();
          break;
        case 'i':
        case 'I':
          setShowStats(p => !p);
          break;
        case 'p':
        case 'F12':
          actions.takeScreenshot();
          break;
        case 'Escape':
          onBack?.();
          break;
      }
    };

    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [
    state.volume,
    state.muted,
    streamType,
    actions,
    onBack,
    onChannelUp,
    onChannelDown,
    revealOsd,
  ]);

  // ─── Seek bar click ───────────────────────────────────────────────────────
  const handleSeekClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (streamType === 'live' || state.duration === 0) return;
      const bar = e.currentTarget.getBoundingClientRect();
      const pct = (e.clientX - bar.left) / bar.width;
      actions.seek(pct * state.duration);
    },
    [streamType, state.duration, actions]
  );

  const progressPct =
    state.duration > 0 ? (state.position / state.duration) * 100 : 0;

  const quality = bufferQuality(state.bufferingPercent);

  // ─── Render ───────────────────────────────────────────────────────────────
  return (
    <div
      ref={containerRef}
      className="relative w-full h-full select-none"
      onMouseMove={revealOsd}
      onClick={revealOsd}
      style={{ cursor: osdVisible ? 'default' : 'none' }}
    >
      {/* Video placeholder — MPV renders natively beneath this layer */}
      <div className="absolute inset-0 bg-transparent" />

      {/* ── Stats overlay (toggle with I) ──────────────────────────────────── */}
      <MpvStatsOverlay visible={showStats} state={state} />

      {/* ── Loading state ──────────────────────────────────────────────────── */}
      {state.isLoading && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/70 z-10">
          <Loader2 className="w-12 h-12 animate-spin text-cyan-400 mb-4" />
          {state.reconnectAttempt > 0 ? (
            <div className="text-center">
              <p className="text-white text-lg font-semibold">Reconnecting...</p>
              <p className="text-cyan-400 text-sm mt-1">
                Attempt {state.reconnectAttempt} of {state.reconnectMaxAttempts}
                {state.reconnectDelayMs > 0 &&
                  ` • Retry in ${Math.round(state.reconnectDelayMs / 1000)}s`}
              </p>
            </div>
          ) : (
            <p className="text-white text-lg">Loading stream...</p>
          )}
        </div>
      )}

      {/* ── Error state ────────────────────────────────────────────────────── */}
      {state.error && !state.isLoading && (
        <div className="absolute inset-0 flex flex-col items-center justify-center bg-black/80 z-10">
          <AlertCircle className="w-12 h-12 text-red-400 mb-4" />
          <p className="text-white text-lg font-semibold mb-2">Playback Error</p>
          <p className="text-red-300 text-sm text-center max-w-md px-4">
            {state.error}
          </p>
          <button
            onClick={() =>
              streamUrl &&
              (streamType === 'live'
                ? actions.loadLiveStream(streamUrl)
                : actions.loadVodStream(streamUrl, state.position))
            }
            className="mt-4 px-6 py-2 bg-cyan-600 hover:bg-cyan-500 text-white rounded-lg transition-colors flex items-center gap-2"
          >
            <RotateCcw className="w-4 h-4" /> Retry
          </button>
        </div>
      )}

      {/* ── MPV unavailable fallback notice ────────────────────────────────── */}
      {!state.isAvailable && (
        <div className="absolute bottom-16 left-1/2 -translate-x-1/2 z-10 flex items-center gap-2 bg-yellow-900/80 border border-yellow-600 rounded-lg px-4 py-2">
          <WifiOff className="w-4 h-4 text-yellow-400" />
          <span className="text-yellow-200 text-sm">
            MPV engine unavailable — using HLS fallback
          </span>
        </div>
      )}

      {/* ── OSD overlay ────────────────────────────────────────────────────── */}
      <div
        className={`absolute inset-0 z-20 flex flex-col justify-between transition-opacity duration-300 ${
          osdVisible ? 'opacity-100' : 'opacity-0 pointer-events-none'
        }`}
      >
        {/* ── TOP BAR ─────────────────────────────────────────────────────── */}
        <div className="flex items-center justify-between px-4 pt-4 pb-8 bg-gradient-to-b from-black/80 to-transparent">
          <div className="flex items-center gap-3">
            {onBack && (
              <button
                onClick={() => { actions.stop(); onBack(); }}
                className="text-white hover:text-cyan-400 transition-colors p-2 rounded-lg hover:bg-white/10"
              >
                <ChevronLeft className="w-6 h-6" />
              </button>
            )}
            {channelLogo && (
              <img src={channelLogo} alt="" className="h-8 object-contain" />
            )}
            <div>
              <p className="text-white font-bold text-lg leading-tight">
                {streamTitle}
              </p>
              {epgCurrentProgram && (
                <p className="text-cyan-300 text-sm">
                  {epgCurrentProgram.title} •{' '}
                  {new Date(epgCurrentProgram.startTime).toLocaleTimeString([], {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                  –
                  {new Date(epgCurrentProgram.endTime).toLocaleTimeString([], {
                    hour: '2-digit',
                    minute: '2-digit',
                  })}
                </p>
              )}
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Stream health bars */}
            {settings.showStreamHealthOverlay && streamType === 'live' && (
              <div className="flex items-end gap-0.5 bg-black/60 rounded-lg px-3 py-1.5">
                {([1, 2, 3, 4] as const).map(bar => (
                  <div
                    key={bar}
                    className="w-1.5 rounded-sm transition-all duration-300"
                    style={{
                      height: `${bar * 4 + 4}px`,
                      backgroundColor:
                        bar <=
                        (['critical', 'poor', 'good', 'excellent'].indexOf(quality) + 1)
                          ? QUALITY_COLORS[quality]
                          : '#374151',
                    }}
                  />
                ))}
              </div>
            )}

            {/* Hardware decode badge */}
            {state.hwdecActive && state.hwdecActive !== 'no' && (
              <span className="text-xs bg-cyan-900/80 text-cyan-300 px-2 py-1 rounded font-mono">
                HW: {state.hwdecActive.toUpperCase()}
              </span>
            )}

            {/* LIVE badge */}
            {streamType === 'live' && (
              <div className="flex items-center gap-1.5 bg-red-600 px-3 py-1 rounded-full">
                <div className="w-2 h-2 rounded-full bg-white animate-pulse" />
                <span className="text-white text-xs font-bold">LIVE</span>
              </div>
            )}

            {/* Stats toggle */}
            <button
              onClick={() => setShowStats(p => !p)}
              className={`text-white/60 hover:text-white transition-colors p-2 rounded-lg hover:bg-white/10 ${
                showStats ? 'text-cyan-400 bg-cyan-900/40' : ''
              }`}
              title="Stream stats (I)"
            >
              <BarChart2 className="w-4 h-4" />
            </button>
          </div>
        </div>

        {/* ── CENTER: Channel nav arrows (live TV) ────────────────────────── */}
        {streamType === 'live' && (
          <div className="flex items-center justify-between px-4 pointer-events-none">
            {onChannelDown && (
              <button
                className="pointer-events-auto text-white/50 hover:text-white p-3 rounded-full hover:bg-white/10 transition-all"
                onClick={onChannelDown}
                title="Previous Channel (←)"
              >
                <ChevronLeft className="w-8 h-8" />
              </button>
            )}
            <div />
            {onChannelUp && (
              <button
                className="pointer-events-auto text-white/50 hover:text-white p-3 rounded-full hover:bg-white/10 transition-all"
                onClick={onChannelUp}
                title="Next Channel (→)"
              >
                <ChevronRight className="w-8 h-8" />
              </button>
            )}
          </div>
        )}

        {/* ── BOTTOM CONTROLS ─────────────────────────────────────────────── */}
        <div className="bg-gradient-to-t from-black/90 to-transparent px-4 pb-4 pt-16">

          {/* EPG progress bar (Live TV) */}
          {streamType === 'live' && epgCurrentProgram && (
            <div className="mb-3">
              <div className="flex justify-between text-xs text-gray-300 mb-1">
                <span>{epgCurrentProgram.title}</span>
                {epgNextProgram && (
                  <span className="text-gray-400">
                    Next: {epgNextProgram.title} •{' '}
                    {new Date(epgNextProgram.startTime).toLocaleTimeString([], {
                      hour: '2-digit',
                      minute: '2-digit',
                    })}
                  </span>
                )}
              </div>
              <div className="h-1 bg-white/20 rounded-full overflow-hidden">
                <div
                  className="h-full bg-cyan-400 rounded-full transition-all duration-1000"
                  style={{
                    width: `${epgProgressPct(
                      epgCurrentProgram.startTime,
                      epgCurrentProgram.endTime
                    )}%`,
                  }}
                />
              </div>
            </div>
          )}

          {/* VOD seek bar */}
          {streamType === 'vod' && (
            <div className="mb-3 group">
              <div className="flex justify-between text-xs text-gray-300 mb-1">
                <span>{formatTime(state.position)}</span>
                <span>{formatTime(state.duration)}</span>
              </div>
              <div
                className="relative h-1.5 bg-white/20 rounded-full cursor-pointer group-hover:h-2.5 transition-all duration-150"
                onClick={handleSeekClick}
              >
                {/* Buffer fill */}
                <div
                  className="absolute inset-y-0 left-0 bg-white/20 rounded-full"
                  style={{ width: `${state.bufferingPercent}%` }}
                />
                {/* Playback fill */}
                <div
                  className="absolute inset-y-0 left-0 bg-cyan-400 rounded-full"
                  style={{ width: `${progressPct}%` }}
                >
                  {/* Seek handle */}
                  <div className="absolute right-0 top-1/2 -translate-y-1/2 w-4 h-4 bg-white rounded-full shadow-lg opacity-0 group-hover:opacity-100 transition-opacity" />
                </div>
              </div>
            </div>
          )}

          {/* Controls row */}
          <div className="flex items-center justify-between">
            {/* Left: play/pause, seek, volume */}
            <div className="flex items-center gap-2">
              <button
                onClick={actions.togglePlayPause}
                className="text-white hover:text-cyan-400 transition-colors p-2 rounded-lg hover:bg-white/10"
              >
                {state.isPaused ? (
                  <Play className="w-6 h-6" />
                ) : (
                  <Pause className="w-6 h-6" />
                )}
              </button>

              {streamType === 'vod' && (
                <>
                  <button
                    onClick={() => actions.seekRelative(-10)}
                    className="text-white/80 hover:text-white transition-colors px-2 py-1.5 rounded-lg hover:bg-white/10 text-xs flex items-center gap-0.5"
                  >
                    <ChevronLeft className="w-3.5 h-3.5" />10s
                  </button>
                  <button
                    onClick={() => actions.seekRelative(10)}
                    className="text-white/80 hover:text-white transition-colors px-2 py-1.5 rounded-lg hover:bg-white/10 text-xs flex items-center gap-0.5"
                  >
                    10s<ChevronRight className="w-3.5 h-3.5" />
                  </button>
                </>
              )}

              {/* Volume */}
              <div className="flex items-center gap-2">
                <button
                  onClick={() => actions.setMute(!state.muted)}
                  className="text-white/80 hover:text-white transition-colors p-2 rounded-lg hover:bg-white/10"
                >
                  {state.muted ? (
                    <VolumeX className="w-5 h-5" />
                  ) : (
                    <Volume2 className="w-5 h-5" />
                  )}
                </button>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.01}
                  value={state.muted ? 0 : state.volume}
                  onChange={e => actions.setVolume(parseFloat(e.target.value))}
                  className="w-20 accent-cyan-400 cursor-pointer"
                />
                <span className="text-white/50 text-xs w-8 text-right">
                  {Math.round((state.muted ? 0 : state.volume) * 100)}%
                </span>
              </div>
            </div>

            {/* Right: audio tracks, subtitles, screenshot, record, stop */}
            <div className="flex items-center gap-2">
              {/* Audio tracks — always visible; dimmed if only 1 track */}
              <div className="relative">
                <button
                  onClick={() => {
                    if (state.audioTracks.length > 0) {
                      setShowAudioMenu(p => !p);
                      setShowSubMenu(false);
                    }
                  }}
                  className={`transition-colors p-2 rounded-lg flex items-center gap-1 text-xs ${
                    state.audioTracks.length > 1
                      ? 'text-white/80 hover:text-white hover:bg-white/10'
                      : 'text-white/25 cursor-default'
                  }`}
                  title={state.audioTracks.length > 1 ? 'Audio Tracks' : 'Audio Tracks (unavailable)'}
                >
                  <Mic className="w-4 h-4" />
                  {state.audioTracks.length > 0 ? `Audio (${state.audioTracks.length})` : 'Audio'}
                </button>
                {showAudioMenu && state.audioTracks.length > 1 && (
                  <div className="absolute bottom-full mb-2 right-0 bg-gray-900 border border-gray-700 rounded-lg py-1 min-w-48 z-30 shadow-xl">
                    <p className="px-4 py-1 text-white/30 text-[10px] uppercase tracking-wider font-semibold">Audio Track</p>
                    {state.audioTracks.map(track => (
                      <button
                        key={track.id}
                        onClick={() => {
                          actions.setAudioTrack(track.id);
                          setShowAudioMenu(false);
                        }}
                        className={`w-full text-left px-4 py-2 text-sm transition-colors ${
                          track.selected
                            ? 'text-cyan-400 bg-cyan-950'
                            : 'text-gray-200 hover:bg-gray-800'
                        }`}
                      >
                        {track.title || `Audio ${track.id}`}
                        {track.lang && track.lang !== 'und' && (
                          <span className="text-gray-500 ml-2 uppercase text-xs">
                            [{track.lang}]
                          </span>
                        )}
                        {track.codec && (
                          <span className="text-gray-500 ml-2 text-xs font-mono">
                            {track.codec}
                          </span>
                        )}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Subtitle tracks — CC button always visible */}
              <div className="relative">
                <button
                  onClick={() => {
                    setShowSubMenu(p => !p);
                    setShowAudioMenu(false);
                  }}
                  className={`transition-colors p-2 rounded-lg flex items-center gap-1 text-xs ${
                    state.activeSubtitleTrack !== 'no'
                      ? 'text-cyan-400 hover:bg-cyan-900/30'
                      : 'text-white/80 hover:text-white hover:bg-white/10'
                  }`}
                  title="Subtitles / CC"
                >
                  <Subtitles className="w-4 h-4" />
                  CC
                </button>
                {showSubMenu && (
                  <div className="absolute bottom-full mb-2 right-0 bg-gray-900 border border-gray-700 rounded-lg py-1 min-w-48 z-30 shadow-xl">
                    <p className="px-4 py-1 text-white/30 text-[10px] uppercase tracking-wider font-semibold">Subtitles / CC</p>
                    <button
                      onClick={() => {
                        actions.setSubtitleTrack('no');
                        setShowSubMenu(false);
                      }}
                      className={`w-full text-left px-4 py-2 text-sm transition-colors ${
                        state.activeSubtitleTrack === 'no'
                          ? 'text-cyan-400 bg-cyan-950'
                          : 'text-gray-200 hover:bg-gray-800'
                      }`}
                    >
                      Off
                    </button>
                    {state.subtitleTracks.map(track => (
                      <button
                        key={track.id}
                        onClick={() => {
                          actions.setSubtitleTrack(track.id);
                          setShowSubMenu(false);
                        }}
                        className={`w-full text-left px-4 py-2 text-sm transition-colors ${
                          track.selected
                            ? 'text-cyan-400 bg-cyan-950'
                            : 'text-gray-200 hover:bg-gray-800'
                        }`}
                      >
                        {track.title || `Sub ${track.id}`}
                        {track.lang && track.lang !== 'und' && (
                          <span className="text-gray-500 ml-2 uppercase text-xs">
                            [{track.lang}]
                          </span>
                        )}
                      </button>
                    ))}
                  </div>
                )}
              </div>

              {/* Screenshot */}
              <button
                onClick={actions.takeScreenshot}
                className="text-white/80 hover:text-white transition-colors p-2 rounded-lg hover:bg-white/10"
                title="Screenshot (P)"
              >
                <Camera className="w-4 h-4" />
              </button>

              {/* Record Button */}
              {streamType === 'live' && activeChannel ? (
                <RecordingButton
                  channel={activeChannel}
                  program={currentProgram}
                  size="sm"
                  variant="icon"
                />
              ) : streamType === 'vod' && streamUrl ? (
                <RecordingButton
                  vodSource={{ streamUrl, title: streamTitle }}
                  size="sm"
                  variant="icon"
                />
              ) : null}

              {/* Stop */}
              <button
                onClick={() => { actions.stop(); onBack?.(); }}
                className="text-white/80 hover:text-white transition-colors p-2 rounded-lg hover:bg-white/10"
                title="Stop (S)"
              >
                <Square className="w-5 h-5" />
              </button>
            </div>
          </div>
        </div>
      </div>

      {/* ── Buffering spinner (non-blocking) ──────────────────────────────── */}
      {state.isBuffering && !state.isLoading && (
        <div className="absolute top-4 right-16 z-30 flex items-center gap-2 bg-black/70 rounded-lg px-3 py-1.5">
          <Loader2 className="w-4 h-4 animate-spin text-cyan-400" />
          <span className="text-white/80 text-xs">
            Buffering {state.bufferingPercent}%
          </span>
        </div>
      )}
    </div>
  );
};
