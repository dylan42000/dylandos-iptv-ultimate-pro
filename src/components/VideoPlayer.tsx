// ─── Video Player with HLS, OSD, Seek Preview, Health Indicator ──────────────

import React, {
  useRef,
  useEffect,
  useState,
  useCallback,
  useMemo,
} from 'react';
import Hls from 'hls.js';
import {
  Play,
  Pause,
  Volume2,
  VolumeX,
  Maximize,
  Minimize,
  PictureInPicture2,
  SkipBack,
  SkipForward,
  Loader2,
  AlertTriangle,
  X,
  Subtitles,
  Settings2,
  Gauge,
} from 'lucide-react';
import { StreamHealthIndicator } from './StreamHealthIndicator';
import { RecordingButton } from './RecordingButton';
import { useStreamHealth } from '../hooks/useStreamHealth';
import { useSeekPreview } from '../hooks/useSeekPreview';
import { AppSettings } from '../types/settings';
import { safeSetMuted } from '../utils/mediaUtils';
import { XtreamChannel } from '../types/xtream';
import { EPGProgram } from '../types/epg';

interface PlayerState {
  isPlaying: boolean;
  isLoading: boolean;
  error: string | null;
  position: number;
  duration: number;
  volume: number;
  muted: boolean;
  bufferedPercent: number;
  title: string;
  streamType: 'live' | 'vod';
}

interface VideoPlayerProps {
  streamUrl: string | null;
  streamTitle: string;
  streamType: 'live' | 'vod';
  settings: AppSettings;
  activeChannel?: XtreamChannel | null;
  currentProgram?: EPGProgram | null;
  onBack?: () => void;
  onPositionUpdate?: (position: number, duration: number) => void;
  onEnded?: () => void;
  onError?: (error: string) => void;
}

const formatTime = (secs: number): string => {
  if (!isFinite(secs) || secs < 0) return '0:00';
  const h = Math.floor(secs / 3600);
  const m = Math.floor((secs % 3600) / 60);
  const s = Math.floor(secs % 60);
  if (h > 0) return `${h}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`;
  return `${m}:${s.toString().padStart(2, '0')}`;
};

export const VideoPlayer: React.FC<VideoPlayerProps> = ({
  streamUrl,
  streamTitle,
  activeChannel = null,
  currentProgram = null,
  streamType,
  settings,
  onBack,
  onPositionUpdate,
  onEnded,
  onError,
}) => {
  const videoRef = useRef<HTMLVideoElement>(null);
  const hlsRef = useRef<Hls | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const osdTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectAttemptRef = useRef(0);
  const reconnectTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const audioCtxRef = useRef<AudioContext | null>(null);
  const audioSrcRef = useRef<MediaElementAudioSourceNode | null>(null);
  const audioDelayRef = useRef<DelayNode | null>(null);

  const [playerState, setPlayerState] = useState<PlayerState>({
    isPlaying: false,
    isLoading: false,
    error: null,
    position: 0,
    duration: 0,
    volume: typeof settings.volume === 'number' && isFinite(settings.volume) ? Math.max(0, Math.min(1, settings.volume)) : 1,
    muted: settings.muted,
    bufferedPercent: 0,
    title: streamTitle,
    streamType,
  });

  const [showOsd, setShowOsd] = useState(true);
  const [isFullscreen, setIsFullscreen] = useState(false);
  const [subtitleTracks, setSubtitleTracks] = useState<{ id: number; label: string; language: string }[]>([]);
  const [activeSubtitleId, setActiveSubtitleId] = useState<number>(-1);
  const [showSubtitleMenu, setShowSubtitleMenu] = useState(false);
  const [audioTracks, setAudioTracks] = useState<{ id: number; label: string; language: string }[]>([]);
  const [activeAudioId, setActiveAudioId] = useState<number>(0);
  const [showAudioMenu, setShowAudioMenu] = useState(false);

  const streamHealth = useStreamHealth(videoRef);
  const seekPreview = useSeekPreview();

  // ── OSD Auto-hide ──────────────────────────────────────────────────────

  const showOsdTemporarily = useCallback(() => {
    setShowOsd(true);
    if (osdTimerRef.current) clearTimeout(osdTimerRef.current);
    osdTimerRef.current = setTimeout(() => {
      if (playerState.isPlaying) setShowOsd(false);
    }, settings.osdTimeoutMs || 5000);
  }, [playerState.isPlaying, settings.osdTimeoutMs]);

  // ── Load stream ────────────────────────────────────────────────────────

  const destroyHls = useCallback(() => {
    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }
  }, []);

  // ── Audio offset / lip-sync via Web Audio API ──────────────────────────
  const applyAudioOffset = useCallback((video: HTMLVideoElement, offsetMs: number) => {
    try {
      // If offset is 0 and no chain exists, nothing to do
      if (offsetMs === 0 && !audioCtxRef.current) return;

      if (!audioCtxRef.current) {
        audioCtxRef.current = new AudioContext();
      }
      const ctx = audioCtxRef.current;

      // Always resume — AudioContext starts "suspended" in Chromium without a user gesture
      if (ctx.state === 'suspended') {
        ctx.resume().catch(() => {});
      }

      if (!audioSrcRef.current) {
        audioSrcRef.current = ctx.createMediaElementSource(video);
      }

      if (offsetMs === 0) {
        // Zero offset: connect source directly to destination (bypass delay node)
        if (audioDelayRef.current) {
          try { audioSrcRef.current.disconnect(audioDelayRef.current); } catch { /* ignore */ }
          try { audioDelayRef.current.disconnect(ctx.destination); } catch { /* ignore */ }
          audioDelayRef.current = null;
        }
        try { audioSrcRef.current.connect(ctx.destination); } catch { /* ignore - already connected */ }
        return;
      }

      if (!audioDelayRef.current) {
        // Disconnect any direct connection first
        try { audioSrcRef.current.disconnect(ctx.destination); } catch { /* ignore */ }
        audioDelayRef.current = ctx.createDelay(3.0);
        audioSrcRef.current.connect(audioDelayRef.current);
        audioDelayRef.current.connect(ctx.destination);
      }
      // Positive offset = audio later (video ahead); negative = audio earlier
      const delaySecs = Math.max(0, offsetMs / 1000);
      audioDelayRef.current.delayTime.value = delaySecs;
    } catch {
      // Web Audio API unavailable in this context — silent fallback
    }
  }, []);

  const destroyAudioContext = useCallback(() => {
    audioDelayRef.current = null;
    audioSrcRef.current = null;
    if (audioCtxRef.current) {
      audioCtxRef.current.close().catch(() => {});
      audioCtxRef.current = null;
    }
  }, []);

  const loadStream = useCallback(
    (url: string, title: string) => {
      const video = videoRef.current;
      if (!video) return;

      destroyHls();
      setSubtitleTracks([]);
      setActiveSubtitleId(-1);
      setShowSubtitleMenu(false);
      Array.from(video.textTracks).forEach(track => {
        track.mode = 'disabled';
      });
      setPlayerState((prev) => ({
        ...prev,
        isLoading: true,
        error: null,
        title,
      }));

      const loweredUrl = url.toLowerCase();
      const isHls =
        /\.m3u8($|\?)/i.test(loweredUrl) ||
        /[?&](format|output)=m3u8/i.test(loweredUrl);

      // ── Buffer preset from settings ──
      const bufferPresets = {
        low:    { maxBufferLength: 5,  maxMaxBufferLength: 15, backBufferLength: 15 },
        medium: { maxBufferLength: 10, maxMaxBufferLength: 30, backBufferLength: 30 },
        high:   { maxBufferLength: 20, maxMaxBufferLength: 60, backBufferLength: 60 },
      };
      const preset = (streamType === 'live' ? (settings.liveBufferPreset ?? 'medium') : 'medium') as keyof typeof bufferPresets;
      const buf = bufferPresets[preset];

      if (isHls && Hls.isSupported()) {
        const hls = new Hls({
          enableWorker: true,
          lowLatencyMode: streamType === 'live',
          backBufferLength: streamType === 'live' ? buf.backBufferLength : 90,
          maxBufferLength: streamType === 'live' ? buf.maxBufferLength : 30,
          maxMaxBufferLength: streamType === 'live' ? buf.maxMaxBufferLength : 120,
        });

        hls.loadSource(url);
        hls.attachMedia(video);
        hls.subtitleDisplay = false;
        hls.subtitleTrack = -1;

        hls.on(Hls.Events.MANIFEST_PARSED, () => {
          hls.subtitleDisplay = false;
          hls.subtitleTrack = -1;
          // Force unmute before play — overrides any stale DOM muted attribute
          // and any stale settings.muted value captured by this closure.
          // Chromium autoplay policy may silently mute; this DOM assertion wins.
          video.muted = false;
          video.play().catch((err) => {
            console.warn('[VideoPlayer] HLS play() rejected:', err);
          });
          reconnectAttemptRef.current = 0;
        });

        hls.on(Hls.Events.ERROR, (_event, data) => {
          if (data.fatal) {
            switch (data.type) {
              case Hls.ErrorTypes.NETWORK_ERROR:
                if (streamType === 'live') {
                  scheduleReconnect(url, title);
                } else {
                  hls.startLoad();
                }
                break;
              case Hls.ErrorTypes.MEDIA_ERROR:
                hls.recoverMediaError();
                break;
              default:
                setPlayerState((prev) => ({
                  ...prev,
                  isLoading: false,
                  error: `Playback error: ${data.details}`,
                }));
                onError?.(data.details);
                break;
            }
          }
        });

        hlsRef.current = hls;
      } else if (
        video.canPlayType('application/vnd.apple.mpegurl') ||
        !isHls
      ) {
        video.src = url;
        video.load();
        // Force unmute before play — same rationale as HLS path above.
        video.muted = false;
        video.play().catch((err) => {
          console.warn('[VideoPlayer] native play() rejected:', err);
        });
      }

      // Wire audio routing — always call even at 0 to ensure AudioContext is resumed
      // and any previous Web Audio chain is correctly wired or bypassed.
      applyAudioOffset(video, settings.audioOffsetMs ?? 0);

      streamHealth.startMonitoring();
      if (streamType === 'vod') {
        seekPreview.init(url);
      }
    },
    [streamType, destroyHls, streamHealth, seekPreview, onError, settings]
  );

  // ── Reconnect logic ──────────────────────────────────────────────────

  const scheduleReconnect = useCallback(
    (url: string, title: string) => {
      const MAX_ATTEMPTS = settings.liveReconnectMaxAttempts ?? 5;
      const BASE_DELAY = settings.liveReconnectBaseDelayMs ?? 2000;
      const MAX_DELAY = 30000;

      if (reconnectAttemptRef.current >= MAX_ATTEMPTS) {
        setPlayerState((prev) => ({
          ...prev,
          isLoading: false,
          error: `Stream unavailable after ${MAX_ATTEMPTS} reconnect attempts.`,
        }));
        reconnectAttemptRef.current = 0;
        return;
      }

      const attempt = ++reconnectAttemptRef.current;
      const delay = Math.min(
        BASE_DELAY * Math.pow(2, attempt - 1),
        MAX_DELAY
      );

      setPlayerState((prev) => ({
        ...prev,
        isLoading: true,
        error: null,
        title: `${title} — Reconnecting (${attempt}/${MAX_ATTEMPTS})...`,
      }));

      reconnectTimerRef.current = setTimeout(() => {
        loadStream(url, title);
      }, delay);
    },
    [
      settings.liveReconnectMaxAttempts,
      settings.liveReconnectBaseDelayMs,
      loadStream,
    ]
  );

  // ── Load stream when URL changes ──────────────────────────────────────

  useEffect(() => {
    if (streamUrl) {
      loadStream(streamUrl, streamTitle);
    }
    return () => {
      destroyHls();
      seekPreview.destroy();
      destroyAudioContext();
      streamHealth.stopMonitoring();
      if (reconnectTimerRef.current) clearTimeout(reconnectTimerRef.current);
    };
  }, [streamUrl]);

  // ── Sync audioOffsetMs changes in real-time ────────────────────────────
  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    applyAudioOffset(video, settings.audioOffsetMs ?? 0);
  }, [settings.audioOffsetMs, applyAudioOffset]);

  // ── Video event listeners ─────────────────────────────────────────────

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const onPlay = () =>
      setPlayerState((prev) => ({ ...prev, isPlaying: true, isLoading: false }));
    const onPause = () =>
      setPlayerState((prev) => ({ ...prev, isPlaying: false }));
    const onWaiting = () =>
      setPlayerState((prev) => ({ ...prev, isLoading: true }));
    const onCanPlay = () =>
      setPlayerState((prev) => ({ ...prev, isLoading: false }));

    const onTimeUpdate = () => {
      const pos = video.currentTime;
      const dur = video.duration || 0;
      setPlayerState((prev) => ({
        ...prev,
        position: pos,
        duration: dur,
      }));
      onPositionUpdate?.(pos, dur);

      // mediaSession position state
      if ('mediaSession' in navigator && dur > 0) {
        try {
          navigator.mediaSession.setPositionState({
            duration: dur,
            playbackRate: video.playbackRate,
            position: Math.min(pos, dur),
          });
        } catch {
          // Ignore
        }
      }
    };

    const onProgress = () => {
      if (video.buffered.length > 0 && video.duration > 0) {
        const buffered =
          (video.buffered.end(video.buffered.length - 1) /
            video.duration) *
          100;
        setPlayerState((prev) => ({
          ...prev,
          bufferedPercent: buffered,
        }));
      }
    };

    const onEnded_ = () => {
      setPlayerState((prev) => ({ ...prev, isPlaying: false }));
      onEnded?.();
    };

    const onError_ = () => {
      setPlayerState((prev) => ({
        ...prev,
        isLoading: false,
        error: 'Playback error occurred',
      }));
    };

    video.addEventListener('play', onPlay);
    video.addEventListener('pause', onPause);
    video.addEventListener('waiting', onWaiting);
    video.addEventListener('canplay', onCanPlay);
    video.addEventListener('timeupdate', onTimeUpdate);
    video.addEventListener('progress', onProgress);
    video.addEventListener('ended', onEnded_);
    video.addEventListener('error', onError_);

    return () => {
      video.removeEventListener('play', onPlay);
      video.removeEventListener('pause', onPause);
      video.removeEventListener('waiting', onWaiting);
      video.removeEventListener('canplay', onCanPlay);
      video.removeEventListener('timeupdate', onTimeUpdate);
      video.removeEventListener('progress', onProgress);
      video.removeEventListener('ended', onEnded_);
      video.removeEventListener('error', onError_);
    };
  }, [onPositionUpdate, onEnded]);

  // ── Controls ──────────────────────────────────────────────────────────

  const togglePlay = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    if (video.paused) video.play().catch(() => {});
    else video.pause();
  }, []);

  const seek = useCallback((delta: number) => {
    const video = videoRef.current;
    if (!video || !isFinite(video.duration)) return;
    video.currentTime = Math.max(
      0,
      Math.min(video.duration, video.currentTime + delta)
    );
  }, []);

  const setVolume = useCallback(
    (vol: number) => {
      const video = videoRef.current;
      if (!video) return;
      const safe = typeof vol === 'number' && isFinite(vol) ? vol : 1;
      const clamped = Math.max(0, Math.min(1, safe));
      video.volume = clamped;
      setPlayerState((prev) => ({ ...prev, volume: clamped, muted: false }));
      video.muted = false;
    },
    []
  );

  const toggleMute = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !video.muted;
    setPlayerState((prev) => ({ ...prev, muted: video!.muted }));
  }, []);

  const toggleFullscreen = useCallback(() => {
    if (!containerRef.current) return;
    if (document.fullscreenElement) {
      document.exitFullscreen();
      setIsFullscreen(false);
    } else {
      containerRef.current.requestFullscreen();
      setIsFullscreen(true);
    }
  }, []);

  const togglePip = useCallback(async () => {
    const video = videoRef.current;
    if (!video) return;
    try {
      if (document.pictureInPictureElement) {
        await document.exitPictureInPicture();
      } else {
        await video.requestPictureInPicture();
      }
    } catch {
      // PiP not supported
    }
  }, []);

  const handleBack = useCallback(() => {
    if (reconnectTimerRef.current) {
      clearTimeout(reconnectTimerRef.current);
      reconnectTimerRef.current = null;
    }

    destroyHls();

    const video = videoRef.current;
    if (video) {
      video.pause();
      video.removeAttribute('src');
      video.load();
    }

    onBack?.();
  }, [destroyHls, onBack]);

  const handleSeekClick = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      const video = videoRef.current;
      if (!video || !isFinite(video.duration)) return;
      const rect = e.currentTarget.getBoundingClientRect();
      const fraction = Math.max(
        0,
        Math.min(1, (e.clientX - rect.left) / rect.width)
      );
      video.currentTime = fraction * video.duration;
    },
    []
  );

  const handleSeekBarMouseMove = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (playerState.streamType !== 'vod' || playerState.duration <= 0) return;
      const rect = e.currentTarget.getBoundingClientRect();
      const fraction = Math.max(
        0,
        Math.min(1, (e.clientX - rect.left) / rect.width)
      );
      seekPreview.captureAt(fraction, playerState.duration, e.clientX - rect.left);
    },
    [playerState.duration, playerState.streamType, seekPreview]
  );

  // ── Apply volume from settings on mount ───────────────────────────────

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    const safeVol = typeof settings.volume === 'number' && isFinite(settings.volume)
      ? Math.max(0, Math.min(1, settings.volume))
      : 1;
    video.volume = safeVol;
    safeSetMuted(video, settings.muted);
  }, [settings.volume, settings.muted]);

  // ── Apply subtitle CSS variables ──────────────────────────────────────

  useEffect(() => {
    const root = containerRef.current ?? document.documentElement;
    const video = videoRef.current;
    root.style.setProperty('--subtitle-font-size', `${settings.subtitleFontSize}px`);
    root.style.setProperty('--subtitle-color', settings.subtitleFontColor);
    const subBg = settings.subtitleBackgroundOpacity ?? 0.6;
    root.style.setProperty('--subtitle-bg', `rgba(0,0,0,${subBg})`);
    const ow = settings.subtitleOutlineWidth;
    root.style.setProperty('--subtitle-outline', `${ow}px ${ow}px ${ow * 2}px rgba(0,0,0,0.8)`);
    video?.style.setProperty('--subtitle-font-size', `${settings.subtitleFontSize}px`);
    video?.style.setProperty('--subtitle-color', settings.subtitleFontColor);
    video?.style.setProperty('--subtitle-bg', `rgba(0,0,0,${subBg})`);
    video?.style.setProperty('--subtitle-outline', `${ow}px ${ow}px ${ow * 2}px rgba(0,0,0,0.8)`);
  }, [settings.subtitleFontSize, settings.subtitleFontColor, settings.subtitleOutlineWidth, settings.subtitleBackgroundOpacity]);

  // ── Detect embedded subtitle tracks from HLS ──────────────────────────

  useEffect(() => {
    const hls = hlsRef.current;
    if (!hls) return;

    const handleSubtitleTracks = () => {
      const tracks = hls.subtitleTracks.map((t, i) => ({
        id: i,
        label: t.name || t.lang || `Track ${i + 1}`,
        language: t.lang || 'unknown',
      }));
      setSubtitleTracks(tracks);
    };

    hls.on(Hls.Events.SUBTITLE_TRACKS_UPDATED, handleSubtitleTracks);
    // Also check on manifest parsed since some streams expose tracks there
    hls.on(Hls.Events.MANIFEST_PARSED, handleSubtitleTracks);

    return () => {
      hls.off(Hls.Events.SUBTITLE_TRACKS_UPDATED, handleSubtitleTracks);
      hls.off(Hls.Events.MANIFEST_PARSED, handleSubtitleTracks);
    };
  }, [streamUrl]);

  // ── Detect HLS audio rendition tracks ─────────────────────────────────

  useEffect(() => {
    const hls = hlsRef.current;
    if (!hls) return;

    const handleAudioTracks = () => {
      const tracks = hls.audioTracks.map((t, i) => ({
        id: i,
        label: t.name || t.lang || `Audio ${i + 1}`,
        language: t.lang || 'unknown',
      }));
      setAudioTracks(tracks);
      if (tracks.length > 0) setActiveAudioId(hls.audioTrack);
    };

    hls.on(Hls.Events.AUDIO_TRACKS_UPDATED, handleAudioTracks);
    hls.on(Hls.Events.MANIFEST_PARSED, handleAudioTracks);

    return () => {
      hls.off(Hls.Events.AUDIO_TRACKS_UPDATED, handleAudioTracks);
      hls.off(Hls.Events.MANIFEST_PARSED, handleAudioTracks);
    };
  }, [streamUrl]);

  // ── Also detect <track> elements from native video ─────────────────────

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;

    const checkTracks = () => {
      const textTracks = Array.from(video.textTracks);
      textTracks.forEach((t, i) => {
        t.mode = i === activeSubtitleId && activeSubtitleId >= 0 ? 'showing' : 'disabled';
      });
      if (textTracks.length > 0 && subtitleTracks.length === 0) {
        const tracks = textTracks.map((t, i) => ({
          id: i,
          label: t.label || t.language || `Track ${i + 1}`,
          language: t.language || 'unknown',
        }));
        setSubtitleTracks(tracks);
      }
    };

    video.textTracks.addEventListener('addtrack', checkTracks);
    return () => video.textTracks.removeEventListener('addtrack', checkTracks);
  }, [streamUrl, subtitleTracks.length, activeSubtitleId]);

  // ── Toggle subtitle track ─────────────────────────────────────────────

  const setSubtitleTrack = useCallback((trackId: number) => {
    const hls = hlsRef.current;
    const video = videoRef.current;

    if (hls && hls.subtitleTracks.length > 0) {
      hls.subtitleTrack = trackId;
      hls.subtitleDisplay = trackId >= 0;
    }

    // Also handle native text tracks
    if (video) {
      const textTracks = Array.from(video.textTracks);
      textTracks.forEach((t, i) => {
        t.mode = i === trackId ? 'showing' : 'hidden';
      });
    }

    setActiveSubtitleId(trackId);
    setShowSubtitleMenu(false);
  }, []);

  const setAudioTrack = useCallback((trackId: number) => {
    const hls = hlsRef.current;
    if (hls && hls.audioTracks.length > 0) {
      hls.audioTrack = trackId;
    }
    setActiveAudioId(trackId);
    setShowAudioMenu(false);
  }, []);

  // ── Media Session ─────────────────────────────────────────────────────

  useEffect(() => {
    if (!('mediaSession' in navigator)) return;

    navigator.mediaSession.metadata = new MediaMetadata({
      title: playerState.title,
      artist: 'DYLANDOS IPTV ULTIMATE',
    });

    navigator.mediaSession.setActionHandler('play', togglePlay);
    navigator.mediaSession.setActionHandler('pause', togglePlay);
    navigator.mediaSession.setActionHandler('seekforward', () => seek(10));
    navigator.mediaSession.setActionHandler('seekbackward', () => seek(-10));
  }, [playerState.title, togglePlay, seek]);

  // ── Render ────────────────────────────────────────────────────────────

  if (!streamUrl) {
    return (
      <div className="flex-1 flex items-center justify-center bg-black/60">
        <div className="text-center">
          <div className="w-20 h-20 mx-auto mb-4 rounded-2xl bg-white/5 flex items-center justify-center">
            <Play size={32} className="text-white/20 ml-1" />
          </div>
          <p className="text-white/30 text-sm">
            Select a channel or movie to start watching
          </p>
        </div>
      </div>
    );
  }

  return (
    <div
      ref={containerRef}
      className="relative w-full h-full bg-black overflow-hidden group"
      onMouseMove={showOsdTemporarily}
      onClick={togglePlay}
    >
      {/* Video Element */}
      <video
        ref={videoRef}
        className="absolute inset-0 w-full h-full object-contain"
        playsInline
      />

      {/* Loading Spinner */}
      {playerState.isLoading && (
        <div className="absolute inset-0 flex items-center justify-center z-20 pointer-events-none">
          <div className="flex flex-col items-center gap-3">
            <Loader2 size={40} className="animate-spin" style={{ color: 'var(--accent)' }} />
            <p className="text-white/50 text-sm">{playerState.title}</p>
          </div>
        </div>
      )}

      {/* Error State */}
      {playerState.error && (
        <div className="absolute inset-0 flex items-center justify-center z-20 bg-black/80">
          <div className="flex flex-col items-center gap-3 max-w-sm text-center px-6">
            <AlertTriangle size={40} className="text-red-400" />
            <p className="text-white/80 text-sm">{playerState.error}</p>
            <button
              onClick={(e) => {
                e.stopPropagation();
                if (streamUrl) loadStream(streamUrl, streamTitle);
              }}
              className="neon-btn px-4 py-2 text-sm rounded-xl"
            >
              Retry
            </button>
          </div>
        </div>
      )}

      {/* Top Bar — Stream Health + Title */}
      <div
        className={`absolute top-0 left-0 right-0 z-10 p-3 
          bg-gradient-to-b from-black/80 to-transparent
          transition-opacity duration-300 ${showOsd ? 'opacity-100' : 'opacity-0'}`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-center justify-between">
          <div className="flex min-w-0 items-center gap-3">
            {onBack && (
              <button
                onClick={handleBack}
                className="rounded-full border border-white/[0.08] bg-black/30 p-2 text-white/55 transition-colors hover:text-white"
              >
                <X size={14} />
              </button>
            )}
            <p className="truncate text-sm font-semibold text-white">
              {playerState.title}
            </p>
          </div>
          <StreamHealthIndicator
            health={streamHealth.health}
            visible={settings.showStreamHealthOverlay}
          />
        </div>
      </div>

      {/* Bottom OSD Controls */}
      <div
        className={`absolute bottom-0 left-0 right-0 z-10 p-4
          bg-gradient-to-t from-black/90 to-transparent
          transition-opacity duration-300 ${showOsd ? 'opacity-100' : 'opacity-0'}`}
        onClick={(e) => e.stopPropagation()}
      >
        {/* Seek Bar (VOD only) */}
        {playerState.streamType === 'vod' && playerState.duration > 0 && (
          <div className="relative mb-3">
            {/* Seek thumbnail preview */}
            {seekPreview.preview.visible && seekPreview.preview.dataUrl && (
              <div
                className="absolute bottom-6 pointer-events-none z-20"
                style={{
                  left: seekPreview.preview.x,
                  transform: 'translateX(-50%)',
                }}
              >
                <div className="flex flex-col items-center gap-1">
                  <img
                    src={seekPreview.preview.dataUrl}
                    className="rounded-lg border border-white/20 shadow-2xl object-cover"
                    style={{ width: 192, height: 108 }}
                    alt="preview"
                  />
                  <span className="text-white/80 text-xs font-mono bg-black/80 px-2 py-0.5 rounded">
                    {formatTime(seekPreview.preview.time)}
                  </span>
                </div>
              </div>
            )}

            {/* Seek bar track */}
            <div
              className="relative h-1 bg-white/15 rounded-full cursor-pointer
                hover:h-2 transition-all duration-100 group/seekbar"
              onMouseMove={handleSeekBarMouseMove}
              onMouseLeave={seekPreview.hidePreview}
              onClick={handleSeekClick}
            >
              {/* Buffered */}
              <div
                className="absolute inset-y-0 left-0 bg-white/25 rounded-full pointer-events-none"
                style={{ width: `${playerState.bufferedPercent}%` }}
              />
              {/* Played */}
              <div
                className="absolute inset-y-0 left-0 rounded-full pointer-events-none"
                style={{
                  background: 'var(--accent)',
                  width: `${
                    (playerState.position / playerState.duration) * 100
                  }%`,
                }}
              />
              {/* Scrubber thumb */}
              <div
                className="absolute top-1/2 -translate-y-1/2 -translate-x-1/2
                  w-3.5 h-3.5 rounded-full shadow-lg theme-accent-bg
                  opacity-0 group-hover/seekbar:opacity-100 transition-opacity pointer-events-none"
                style={{
                  left: `${
                    (playerState.position / playerState.duration) * 100
                  }%`,
                }}
              />
            </div>
          </div>
        )}

        {/* Controls Row */}
        <div className="flex items-center gap-3">
          {/* Play/Pause */}
          <button
            onClick={togglePlay}
            className="text-white hover:theme-text-accent transition-colors"
          >
            {playerState.isPlaying ? (
              <Pause size={22} />
            ) : (
              <Play size={22} className="ml-0.5" />
            )}
          </button>

          {/* Seek buttons (VOD) */}
          {playerState.streamType === 'vod' && (
            <>
              <button
                onClick={() => seek(-10)}
                className="text-white/60 hover:text-white transition-colors"
              >
                <SkipBack size={18} />
              </button>
              <button
                onClick={() => seek(10)}
                className="text-white/60 hover:text-white transition-colors"
              >
                <SkipForward size={18} />
              </button>
            </>
          )}

          {/* Time display */}
          <span className="text-white/50 text-xs font-mono min-w-[80px]">
            {formatTime(playerState.position)}
            {playerState.duration > 0 &&
              ` / ${formatTime(playerState.duration)}`}
          </span>

          <div className="flex-1" />

          {/* Volume */}
          <div className="flex items-center gap-2 group/vol">
            <button
              onClick={toggleMute}
              className="text-white/60 hover:text-white transition-colors"
            >
              {playerState.muted || playerState.volume === 0 ? (
                <VolumeX size={18} />
              ) : (
                <Volume2 size={18} />
              )}
            </button>
            <input
              type="range"
              min="0"
              max="1"
              step="0.05"
              value={playerState.muted ? 0 : playerState.volume}
              onChange={(e) => setVolume(parseFloat(e.target.value))}
              className="w-20 h-1 bg-white/20 rounded-full appearance-none cursor-pointer
                opacity-0 group-hover/vol:opacity-100 transition-opacity
                [&::-webkit-slider-thumb]:appearance-none [&::-webkit-slider-thumb]:w-3 
                [&::-webkit-slider-thumb]:h-3 [&::-webkit-slider-thumb]:bg-white 
                [&::-webkit-slider-thumb]:rounded-full"
            />
          </div>

          {/* Audio Tracks */}
          <div className="relative">
            <button
              onClick={(e) => { e.stopPropagation(); setShowAudioMenu(!showAudioMenu); setShowSubtitleMenu(false); }}
              className={`transition-colors flex items-center gap-1 text-xs ${
                audioTracks.length > 1 ? 'text-white/60 hover:text-white' : 'text-white/20 cursor-default'
              }`}
              title={audioTracks.length > 1 ? 'Audio Tracks' : 'Audio Tracks (unavailable)'}
              disabled={audioTracks.length <= 1}
            >
              <Volume2 size={16} />
              {audioTracks.length > 1 && <span>{audioTracks.length}</span>}
            </button>
            {showAudioMenu && audioTracks.length > 1 && (
              <div className="absolute bottom-full mb-2 right-0 min-w-[160px] py-1.5 rounded-xl shadow-2xl z-30"
                style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border-default)' }}
                onClick={e => e.stopPropagation()}>
                <p className="px-3 py-1 text-white/30 text-[10px] uppercase tracking-wider font-semibold">Audio Track</p>
                {audioTracks.map(t => (
                  <button
                    key={t.id}
                    onClick={() => setAudioTrack(t.id)}
                    className={`w-full text-left px-3 py-1.5 text-xs transition-colors ${
                      activeAudioId === t.id ? 'font-semibold' : 'text-white/60'
                    }`}
                    style={activeAudioId === t.id ? { color: 'var(--accent)' } : undefined}
                  >
                    {t.label} <span className="text-white/30">({t.language})</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {/* Subtitles */}
          <div className="relative">
            <button
              onClick={(e) => { e.stopPropagation(); setShowSubtitleMenu(!showSubtitleMenu); setShowAudioMenu(false); }}
              className={`transition-colors ${
                activeSubtitleId >= 0 ? 'text-cyan-400' : 'text-white/60 hover:text-white'
              }`}
              style={activeSubtitleId >= 0 ? { color: 'var(--accent)' } : undefined}
              title="Subtitles / CC"
            >
              <Subtitles size={18} />
            </button>
            {showSubtitleMenu && (
              <div className="absolute bottom-full mb-2 right-0 min-w-[160px] py-1.5 rounded-xl shadow-2xl z-30"
                style={{ background: 'var(--bg-elevated)', border: '1px solid var(--border-default)' }}
                onClick={e => e.stopPropagation()}>
                <p className="px-3 py-1 text-white/30 text-[10px] uppercase tracking-wider font-semibold">Subtitles / CC</p>
                <button
                  onClick={() => setSubtitleTrack(-1)}
                  className={`w-full text-left px-3 py-1.5 text-xs transition-colors ${
                    activeSubtitleId < 0 ? 'font-semibold' : 'text-white/60'
                  }`}
                  style={activeSubtitleId < 0 ? { color: 'var(--accent)' } : undefined}
                >
                  Off
                </button>
                {subtitleTracks.map(t => (
                  <button
                    key={t.id}
                    onClick={() => setSubtitleTrack(t.id)}
                    className={`w-full text-left px-3 py-1.5 text-xs transition-colors ${
                      activeSubtitleId === t.id ? 'font-semibold' : 'text-white/60'
                    }`}
                    style={activeSubtitleId === t.id ? { color: 'var(--accent)' } : undefined}
                  >
                    {t.label} <span className="text-white/30">({t.language})</span>
                  </button>
                ))}
                {subtitleTracks.length === 0 && (
                  <p className="px-3 py-1.5 text-white/30 text-xs italic">No subtitles available</p>
                )}
              </div>
            )}
          </div>

          {/* PiP */}
          <button
            onClick={togglePip}
            className="text-white/60 hover:text-white transition-colors"
            title="Picture in Picture"
          >
            <PictureInPicture2 size={18} />
          </button>

          {/* Record Button — Live TV shows channel record; VOD shows stream record */}
          {streamType === 'live' && activeChannel ? (
            <RecordingButton
              channel={activeChannel}
              program={currentProgram}
              size="sm"
              variant="icon"
            />
          ) : streamType === 'vod' && streamUrl ? (
            <RecordingButton
              vodSource={{ streamUrl, title: playerState.title }}
              size="sm"
              variant="icon"
            />
          ) : null}

          {/* Fullscreen */}
          <button
            onClick={toggleFullscreen}
            className="text-white/60 hover:text-white transition-colors"
          >
            {isFullscreen ? (
              <Minimize size={18} />
            ) : (
              <Maximize size={18} />
            )}
          </button>
        </div>
      </div>
    </div>
  );
};
