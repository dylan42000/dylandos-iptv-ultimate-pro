// ─── Quick Recording Button Component ─────────────────────────────────────

import React, { useState, useCallback, useEffect, useRef } from 'react';
import { Circle, Square, CheckCircle2, AlertCircle, X, Clock, HardDrive, Play, Settings } from 'lucide-react';
import { XtreamChannel } from '../types/xtream';
import { EPGProgram } from '../types/epg';
import { xtreamApi as xtream } from '../services/xtreamApi';
import { useToast } from './ToastProvider';

interface VodSource {
  streamUrl: string;
  title: string;
  durationMinutes?: number;
}

interface RecordingButtonProps {
  /** Direct stream URL (highest priority) */
  streamUrl?: string;
  channelName?: string;
  programTitle?: string;
  /** Live channel mode */
  channel?: XtreamChannel;
  program?: EPGProgram | null;
  /** VOD mode (movies / series episodes) — pass instead of channel */
  vodSource?: VodSource;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'icon' | 'full';
  className?: string;
}

const formatBytes = (bytes: number): string => {
  if (!isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx++;
  }
  return `${value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2)} ${units[idx]}`;
};

const formatSeconds = (sec: number): string => {
  const s = Math.max(0, Math.floor(sec));
  const m = Math.floor(s / 60);
  const remSec = s % 60;
  return `${m}:${String(remSec).padStart(2, '0')}`;
};

export const RecordingButton: React.FC<RecordingButtonProps> = ({
  streamUrl: directStreamUrl,
  channelName: directChannelName,
  programTitle: directProgramTitle,
  channel,
  program,
  vodSource,
  size = 'md',
  variant = 'icon',
  className = '',
}) => {
  const { success, error: toastError, info } = useToast();
  const [isRecording, setIsRecording] = useState(false);
  const [showDialog, setShowDialog] = useState(false);
  const [duration, setDuration] = useState(60);
  const [programTitle, setProgramTitle] = useState('');
  const [isStarting, setIsStarting] = useState(false);
  const [error, setError] = useState('');
  const [recordingId, setRecordingId] = useState<string | null>(null);
  const [bytesWritten, setBytesWritten] = useState(0);
  const [elapsedSeconds, setElapsedSeconds] = useState(0);

  const recordingIdRef = useRef<string | null>(null);
  recordingIdRef.current = recordingId;

  const currentStreamUrl = directStreamUrl
    ?? vodSource?.streamUrl
    ?? (channel?.direct_source?.trim()
      ? channel.direct_source
      : channel?.stream_id
        ? xtream.getLiveStreamUrl(channel.stream_id, 'ts')
        : '');

  // ── Sync with active DVR recordings on mount and listen to events ──────────
  useEffect(() => {
    const api = window.electronAPI;
    if (!api) return;

    let mounted = true;

    const checkActive = async () => {
      try {
        const result = await api.invoke?.('dvr:list-active');
        if (!mounted || !result?.recordings) return;
        const list = result.recordings as any[];
        const match = list.find(r =>
          (channel && r.channelName === channel.name) ||
          (directChannelName && r.channelName === directChannelName) ||
          (currentStreamUrl && r.streamUrl === currentStreamUrl)
        );
        if (match) {
          setIsRecording(true);
          setRecordingId(match.id);
          setBytesWritten(match.size || 0);
          setElapsedSeconds(match.elapsedSeconds || 0);
        }
      } catch {}
    };

    void checkActive();

    const onStarted = (data: any) => {
      if (!mounted) return;
      if (
        (channel && data.channelName === channel.name) ||
        (directChannelName && data.channelName === directChannelName) ||
        (data.recordingId && data.recordingId === recordingIdRef.current)
      ) {
        setIsRecording(true);
        if (data.recordingId) setRecordingId(data.recordingId);
      }
    };

    const onProgress = (data: any) => {
      if (!mounted) return;
      if (data.recordingId && data.recordingId === recordingIdRef.current) {
        if (typeof data.bytesWritten === 'number') setBytesWritten(data.bytesWritten);
        if (typeof data.elapsedSeconds === 'number') setElapsedSeconds(data.elapsedSeconds);
      }
    };

    const onCompleted = (data: any) => {
      if (!mounted) return;
      if (data.recordingId && data.recordingId === recordingIdRef.current) {
        setIsRecording(false);
        setRecordingId(null);
        setBytesWritten(0);
        setElapsedSeconds(0);
        if (data.success === false) toastError(data.error || data.meta?.error || 'Recording failed — see DVR library');
      }
    };

    api.on?.('dvr:started', onStarted);
    api.on?.('dvr:progress', onProgress);
    api.on?.('dvr:completed', onCompleted);

    return () => {
      mounted = false;
      api.off?.('dvr:started', onStarted);
      api.off?.('dvr:progress', onProgress);
      api.off?.('dvr:completed', onCompleted);
    };
  }, [channel, directChannelName, currentStreamUrl]);

  const sizeClasses = {
    sm: 'w-7 h-7',
    md: 'w-9 h-9',
    lg: 'w-11 h-11',
  };

  const iconSizes = {
    sm: 14,
    md: 16,
    lg: 18,
  };

  const startRecording = useCallback(async (customTitle?: string, customDurationMin?: number) => {
    const streamUrl = currentStreamUrl;
    if (!streamUrl) {
      const msg = 'Cannot record: Invalid stream URL';
      setError(msg);
      toastError(msg);
      return;
    }

    setIsStarting(true);
    setError('');

    try {
      const status = await window.electronAPI?.invoke?.('dvr:status');
      if (status && status.ffmpegAvailable === false) {
        throw new Error('FFmpeg binary not detected on system');
      }
      if (status && typeof status.activeCount === 'number' && typeof status.maxConcurrent === 'number'
        && status.activeCount >= status.maxConcurrent) {
        throw new Error(`Max concurrent recordings reached (${status.activeCount}/${status.maxConcurrent})`);
      }

      const title = (customTitle || programTitle).trim()
        || directProgramTitle
        || vodSource?.title
        || program?.title
        || `Recording ${directChannelName ?? channel?.name ?? 'Stream'}`;
      
      const resolvedDuration = customDurationMin ?? vodSource?.durationMinutes ?? duration;
      const result = await window.electronAPI?.invoke?.('dvr:start', {
        streamUrl,
        channelName: directChannelName ?? channel?.name ?? vodSource?.title ?? 'Recording',
        programTitle: title,
        durationSeconds: resolvedDuration * 60,
      });

      if (!result?.success) {
        throw new Error(result?.error || 'Failed to start recording');
      }

      setIsRecording(!result.completed);
      recordingIdRef.current = result.completed ? null : result.recordingId || null;
      setRecordingId(recordingIdRef.current);
      setShowDialog(false);
      success(`Recording ${result.completed ? 'saved' : 'started'}: ${title}`);
    } catch (err: any) {
      const msg = err.message || 'Failed to start recording';
      setError(msg);
      toastError(msg);
    } finally {
      setIsStarting(false);
    }
  }, [currentStreamUrl, programTitle, directProgramTitle, vodSource, program, directChannelName, channel, duration, success, toastError]);

  const handleQuickRecord = useCallback(async () => {
    if (isRecording) {
      info('Stopping recording...');
      if (recordingId) {
        await window.electronAPI?.invoke?.('dvr:stop', recordingId);
      }
      setRecordingId(null);
      setIsRecording(false);
      return;
    }

    // Instant 1-Touch Recording!
    startRecording();
  }, [isRecording, recordingId, startRecording, info]);

  return (
    <>
      <button
        disabled={isStarting}
        onClick={(e) => {
          e.stopPropagation();
          handleQuickRecord();
        }}
        onContextMenu={(e) => {
          e.preventDefault();
          e.stopPropagation();
          setShowDialog(true);
        }}
        className={`${sizeClasses[size]} flex items-center justify-center rounded-full border transition-all ${
          isRecording
            ? 'border-red-500 bg-red-500/25 text-red-400 animate-pulse shadow-[0_0_12px_rgba(239,68,68,0.5)]'
            : 'border-white/[0.12] bg-white/[0.06] text-white/60 hover:border-red-500/30 hover:bg-red-500/10 hover:text-red-400'
        } ${className}`}
        title={isRecording ? `Recording in progress (${formatSeconds(elapsedSeconds)} • ${formatBytes(bytesWritten)}) — Click to stop` : 'Record stream (Click to record now, Right-click for options)'}
      >
        {isRecording ? (
          <Square size={iconSizes[size] - 2} fill="currentColor" />
        ) : (
          <Circle size={iconSizes[size]} />
        )}
      </button>

      {showDialog && (
        <div
          className="fixed inset-0 z-[200] flex items-center justify-center bg-black/70 backdrop-blur-sm"
          onClick={() => setShowDialog(false)}
        >
          <div
            className="w-full max-w-md rounded-2xl border border-white/[0.12] bg-[#0a0e18]/98 backdrop-blur-xl p-6 shadow-[0_25px_70px_rgba(0,0,0,0.6)]"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="mb-6 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="flex h-10 w-10 items-center justify-center rounded-full bg-red-500/15 text-red-400">
                  <Circle size={20} />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-white">Start DVR Recording</h3>
                  <p className="text-xs text-white/40">{channel?.name ?? vodSource?.title ?? 'Stream'}</p>
                </div>
              </div>
              <button
                onClick={() => setShowDialog(false)}
                className="rounded-full p-2 text-white/40 transition-colors hover:bg-white/[0.08] hover:text-white"
              >
                <X size={18} />
              </button>
            </div>

            {error && (
              <div className="mb-4 flex items-center gap-2 rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-300">
                <AlertCircle size={16} />
                <span>{error}</span>
              </div>
            )}

            <div className="space-y-4">
              <div>
                <label className="mb-2 block text-xs font-semibold text-white/60">
                  Recording Title
                </label>
                <input
                  type="text"
                  value={programTitle}
                  onChange={(e) => setProgramTitle(e.target.value)}
                  placeholder={program?.title || `Recording ${channel?.name ?? vodSource?.title ?? 'Stream'}`}
                  className="w-full rounded-xl border border-white/[0.08] bg-white/[0.04] px-3 py-2.5 text-sm text-white placeholder:text-white/20 focus:border-cyan-400/30 focus:outline-none"
                />
              </div>

              <div>
                <label className="mb-2 flex items-center justify-between text-xs font-semibold text-white/60">
                  <span>Duration</span>
                  <span className="font-mono text-cyan-300">{duration} min</span>
                </label>
                <input
                  type="range"
                  min="5"
                  max="300"
                  step="5"
                  value={duration}
                  onChange={(e) => setDuration(Number(e.target.value))}
                  className="w-full accent-cyan-400"
                />
                <div className="mt-1 flex justify-between text-[10px] text-white/30">
                  <span>5 min</span>
                  <span>1 hour</span>
                  <span>3 hours</span>
                  <span>5 hours</span>
                </div>
              </div>

              {program && (
                <div className="rounded-xl border border-cyan-400/15 bg-cyan-400/6 p-3 text-xs">
                  <div className="mb-1 flex items-center gap-2 font-semibold text-cyan-300">
                    <Clock size={12} />
                    <span>Current Program</span>
                  </div>
                  <p className="text-white/80 font-medium">{program.title}</p>
                  {program.description && (
                    <p className="mt-1 line-clamp-2 text-white/40">{program.description}</p>
                  )}
                </div>
              )}
            </div>

            <div className="mt-6 flex gap-3">
              <button
                onClick={() => setShowDialog(false)}
                disabled={isStarting}
                className="flex-1 rounded-xl border border-white/[0.08] bg-white/[0.04] py-2.5 text-sm font-semibold text-white/70 transition-colors hover:bg-white/[0.08] disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                onClick={() => startRecording()}
                disabled={isStarting}
                className="flex flex-1 items-center justify-center gap-2 rounded-xl bg-gradient-to-r from-red-500 to-red-600 py-2.5 text-sm font-bold text-white transition-all hover:from-red-600 hover:to-red-700 disabled:opacity-50"
              >
                {isStarting ? (
                  <>
                    <Circle size={14} className="animate-spin" />
                    Starting...
                  </>
                ) : (
                  <>
                    <Circle size={14} fill="currentColor" />
                    Start Recording
                  </>
                )}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
};
