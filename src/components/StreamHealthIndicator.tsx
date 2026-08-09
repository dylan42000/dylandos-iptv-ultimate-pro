// ─── Stream Health Indicator ─────────────────────────────────────────────────

import React from 'react';
import { StreamHealth } from '../hooks/useStreamHealth';

export const StreamHealthIndicator: React.FC<{
  health: StreamHealth;
  visible: boolean;
}> = ({ health, visible }) => {
  if (!visible) return null;

  const colors = {
    excellent: '#22c55e',
    good: '#00FFFF',
    poor: '#eab308',
    critical: '#ef4444',
  };

  const barCount = {
    excellent: 4,
    good: 3,
    poor: 2,
    critical: 1,
  }[health.quality];
  const color = colors[health.quality];

  return (
    <div className="flex items-center gap-1.5 bg-black/50 rounded-lg px-2 py-1 backdrop-blur-sm">
      {/* Signal bars */}
      <div className="flex items-end gap-px h-3.5">
        {[1, 2, 3, 4].map((level) => (
          <div
            key={level}
            className="w-1 rounded-sm transition-all duration-500"
            style={{
              height: `${level * 3 + 1}px`,
              backgroundColor:
                level <= barCount ? color : 'rgba(255,255,255,0.15)',
            }}
          />
        ))}
      </div>

      {/* Buffer level */}
      <span className="text-[10px] font-mono" style={{ color }}>
        {health.bufferSeconds.toFixed(1)}s
      </span>

      {/* Bandwidth */}
      {health.bandwidthKbps != null && health.bandwidthKbps > 0 && (
        <span className="text-white/30 text-[10px] font-mono">
          {health.bandwidthKbps >= 1000
            ? `${(health.bandwidthKbps / 1000).toFixed(1)}M`
            : `${health.bandwidthKbps}K`}
        </span>
      )}

      {/* Buffering spinner */}
      {health.isBuffering && (
        <div
          className="w-2.5 h-2.5 rounded-full border border-t-transparent animate-spin"
          style={{
            borderColor: `${color}40`,
            borderTopColor: 'transparent',
          }}
        />
      )}
    </div>
  );
};
