// ─── Stream Health Monitoring Hook ──────────────────────────────────────────

import { useState, useRef, useCallback, useEffect } from 'react';

export interface StreamHealth {
  bufferSeconds: number;
  droppedFrames: number;
  isBuffering: boolean;
  bandwidthKbps?: number;
  quality: 'excellent' | 'good' | 'poor' | 'critical';
}

export const useStreamHealth = (
  videoRef: React.RefObject<HTMLVideoElement | null>
) => {
  const [health, setHealth] = useState<StreamHealth>({
    bufferSeconds: 0,
    droppedFrames: 0,
    isBuffering: false,
    quality: 'good',
  });
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const startMonitoring = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
    intervalRef.current = setInterval(() => {
      const v = videoRef.current;
      if (!v) return;

      let bufSecs = 0;
      for (let i = 0; i < v.buffered.length; i++) {
        if (
          v.buffered.start(i) <= v.currentTime &&
          v.buffered.end(i) >= v.currentTime
        ) {
          bufSecs = v.buffered.end(i) - v.currentTime;
          break;
        }
      }

      const dropped =
        v.getVideoPlaybackQuality?.()?.droppedVideoFrames ?? 0;
      const quality: StreamHealth['quality'] =
        bufSecs < 1
          ? 'critical'
          : bufSecs < 3
          ? 'poor'
          : bufSecs < 8
          ? 'good'
          : 'excellent';

      setHealth({
        bufferSeconds: Math.round(bufSecs * 10) / 10,
        droppedFrames: dropped,
        isBuffering: v.readyState < HTMLMediaElement.HAVE_FUTURE_DATA,
        quality,
      });
    }, 1000);
  }, [videoRef]);

  const stopMonitoring = useCallback(() => {
    if (intervalRef.current) {
      clearInterval(intervalRef.current);
      intervalRef.current = null;
    }
  }, []);

  useEffect(() => () => stopMonitoring(), [stopMonitoring]);

  return { health, startMonitoring, stopMonitoring };
};
