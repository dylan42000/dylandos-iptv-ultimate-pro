// ─── Quick Recording Button Component ─────────────────────────────────────

import React, { useState, useCallback } from 'react';
import { Circle, CheckCircle2, AlertCircle, X, Clock } from 'lucide-react';
import { XtreamChannel } from '../types/xtream';
import { EPGProgram } from '../types/epg';
import { xtreamApi as xtream } from '../services/xtreamApi';

interface VodSource {
  streamUrl: string;
  title: string;
  durationMinutes?: number;
}

interface RecordingButtonProps {
  /** Live channel mode */
  channel?: XtreamChannel;
  program?: EPGProgram | null;
  /** VOD mode (movies / series episodes) — pass instead of channel */
  vodSource?: VodSource;
  size?: 'sm' | 'md' | 'lg';
  variant?: 'icon' | 'full';
  className?: string;
}

export const RecordingButton: React.FC<RecordingButtonProps> = ({
  channel,
  program,
  vodSource,
  size = 'md',
  variant = 'icon',
  className = '',
}) => {
  const [isRecording, setIsRecording] = useState(false);
  const [showDialog, setShowDialog] = useState(false);
  const [duration, setDuration] = useState(60);
  const [programTitle, setProgramTitle] = useState('');
  const [isStarting, setIsStarting] = useState(false);
  const [error, setError] = useState('');
  const [recordingId, setRecordingId] = useState<string | null>(null);

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

  const handleQuickRecord = useCallback(async () => {
    if (isRecording) {
      if (recordingId) {
        await window.electronAPI?.invoke?.('dvr:stop', recordingId);
      }
      setRecordingId(null);
      setIsRecording(false);
      return;
    }

    if (variant === 'icon') {
      setShowDialog(true);
      return;
    }

    // Direct recording for full variant
    startRecording();
  }, [isRecording, variant]);

  const startRecording = useCallback(async () => {
    const streamUrl = vodSource?.streamUrl
      ?? (channel?.direct_source?.trim()
        ? channel.direct_source
        : xtream.getLiveStreamUrl(channel!.stream_id, 'm3u8'));

    setIsStarting(true);
    setError('');

    try {
      const status = await window.electronAPI?.invoke?.('dvr:status');
      if (status && status.ffmpegAvailable === false) {
        throw new Error('FFmpeg not available — cannot start recording');
      }
      if (status && typeof status.activeCount === 'number' && typeof status.maxConcurrent === 'number'
        && status.activeCount >= status.maxConcurrent) {
        throw new Error(`Max concurrent recordings reached (${status.activeCount}/${status.maxConcurrent})`);
      }

      const title = programTitle.trim()
        || vodSource?.title
        || program?.title
        || `Recording ${channel?.name ?? 'Stream'}`;
      
      const resolvedDuration = vodSource?.durationMinutes ?? duration;
      const result = await window.electronAPI?.invoke?.('dvr:start', {
        streamUrl,
        channelName: channel?.name ?? vodSource?.title ?? 'Recording',
        programTitle: title,
        durationSeconds: resolvedDuration * 60,
      });

      if (!result?.success) {
        throw new Error(result?.error || 'Failed to start recording');
      }

      setIsRecording(true);
      setRecordingId(result.recordingId || null);
      setShowDialog(false);
    } catch (err: any) {
      setError(err.message || 'Failed to start recording');
    } finally {
      setIsStarting(false);
    }
  }, [channel, program, vodSource, programTitle, duration]);

  return (
    <>
      <button
        onClick={(e) => {
          e.stopPropagation();
          handleQuickRecord();
        }}
        className={`${sizeClasses[size]} flex items-center justify-center rounded-full border transition-all ${
          isRecording
            ? 'border-red-500/40 bg-red-500/20 text-red-400 animate-pulse'
            : 'border-white/[0.12] bg-white/[0.06] text-white/60 hover:border-red-500/30 hover:bg-red-500/10 hover:text-red-400'
        } ${className}`}
        title="Record this channel"
      >
        {isRecording ? (
          <Circle size={iconSizes[size]} fill="currentColor" />
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
                  <h3 className="text-lg font-bold text-white">Start Recording</h3>
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
                  Recording Name
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
                  className="w-full"
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
                  <p className="text-white/60">{program.title}</p>
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
                onClick={startRecording}
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
