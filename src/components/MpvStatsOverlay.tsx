// ─── MpvStatsOverlay — Live stream statistics panel ───────────────────────────
// Toggle with the "I" key or the stats button in MpvPlayer OSD.
// Mirrors MPV's built-in stats display (what you'd normally get from pressing i).
// ──────────────────────────────────────────────────────────────────────────────

import React from 'react';
import { MpvPlayerState } from '../hooks/useMpv';

function formatTime(seconds: number): string {
  if (!isFinite(seconds) || seconds < 0) return '0:00';
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = Math.floor(seconds % 60);
  if (h > 0)
    return `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  return `${m}:${String(s).padStart(2, '0')}`;
}

function formatBitrate(bps: number): string {
  if (!bps || bps <= 0) return 'N/A';
  if (bps >= 1_000_000) return `${(bps / 1_000_000).toFixed(1)} Mbps`;
  return `${(bps / 1000).toFixed(0)} kbps`;
}

interface Props {
  visible: boolean;
  state: MpvPlayerState;
}

export const MpvStatsOverlay: React.FC<Props> = ({ visible, state }) => {
  if (!visible) return null;

  const hwStatus =
    state.hwdecActive && state.hwdecActive !== 'no'
      ? `✓ ${state.hwdecActive}`
      : '✗ Software';

  return (
    <div className="absolute top-16 left-4 z-40 bg-black/85 border border-white/10 rounded-xl px-5 py-4 font-mono text-xs text-green-400 space-y-1.5 pointer-events-none min-w-56 backdrop-blur-sm">
      <p className="text-green-300 font-bold text-sm mb-3">▶ Stream Statistics</p>

      <StatRow label="Video Codec" value={state.videoCodec || 'N/A'} />
      <StatRow label="Audio Codec" value={state.audioCodec || 'N/A'} />
      <StatRow label="Video Bitrate" value={formatBitrate(state.videoBitrate)} />
      <StatRow label="Audio Bitrate" value={formatBitrate(state.audioBitrate)} />
      <StatRow
        label="Hardware Decode"
        value={hwStatus}
        color={state.hwdecActive !== 'no' ? '#22d3ee' : '#facc15'}
      />
      <StatRow
        label="Buffer"
        value={`${state.bufferingPercent.toFixed(0)}%`}
        color={state.isBuffering ? '#ef4444' : '#4ade80'}
      />

      {state.streamType === 'vod' && state.duration > 0 && (
        <StatRow
          label="Position"
          value={`${formatTime(state.position)} / ${formatTime(state.duration)}`}
        />
      )}

      {state.audioTracks.length > 0 && (
        <StatRow
          label="Audio Tracks"
          value={String(state.audioTracks.length)}
        />
      )}
      {state.subtitleTracks.length > 0 && (
        <StatRow
          label="Sub Tracks"
          value={String(state.subtitleTracks.length)}
        />
      )}

      <p className="text-white/25 text-[10px] pt-2 border-t border-white/10">
        Press I to toggle
      </p>
    </div>
  );
};

const StatRow: React.FC<{
  label: string;
  value: string;
  color?: string;
}> = ({ label, value, color }) => (
  <div className="flex justify-between gap-6">
    <span className="text-green-600">{label}:</span>
    <span className="text-white" style={color ? { color } : undefined}>
      {value}
    </span>
  </div>
);
