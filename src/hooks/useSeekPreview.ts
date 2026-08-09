// ─── Seek Preview / Thumbnail Scrubbing Hook ────────────────────────────────

import { useRef, useCallback, useState } from 'react';

export interface SeekPreview {
  visible: boolean;
  x: number;
  time: number;
  dataUrl: string;
}

export const useSeekPreview = () => {
  const [preview, setPreview] = useState<SeekPreview>({
    visible: false,
    x: 0,
    time: 0,
    dataUrl: '',
  });

  const scratchRef = useRef<HTMLVideoElement | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const lastTimeRef = useRef(-999);
  const captureQueueRef = useRef(false);

  const init = useCallback((url: string) => {
    if (scratchRef.current) {
      scratchRef.current.src = '';
      scratchRef.current.load();
      try {
        document.body.removeChild(scratchRef.current);
      } catch {
        // already removed
      }
    }

    const video = document.createElement('video');
    video.src = url;
    video.muted = true;
    video.preload = 'metadata';
    video.crossOrigin = 'anonymous';
    video.style.cssText =
      'position:absolute;width:1px;height:1px;opacity:0;pointer-events:none';
    document.body.appendChild(video);
    scratchRef.current = video;

    if (!canvasRef.current) {
      const canvas = document.createElement('canvas');
      canvas.width = 192;
      canvas.height = 108;
      canvasRef.current = canvas;
    }
  }, []);

  const destroy = useCallback(() => {
    if (scratchRef.current) {
      // Fully release the media resource before removing the element
      scratchRef.current.pause();
      scratchRef.current.removeAttribute('src');
      scratchRef.current.load();
      try {
        document.body.removeChild(scratchRef.current);
      } catch {
        // already removed
      }
      scratchRef.current = null;
    }
    lastTimeRef.current = -999;
    captureQueueRef.current = false;
  }, []);

  const captureAt = useCallback(
    (seekFraction: number, duration: number, barX: number) => {
      const targetTime = seekFraction * duration;

      if (Math.abs(targetTime - lastTimeRef.current) < 1.0) {
        setPreview((prev) => ({
          ...prev,
          x: barX,
          visible: true,
          time: targetTime,
        }));
        return;
      }

      if (captureQueueRef.current) return;
      captureQueueRef.current = true;
      lastTimeRef.current = targetTime;

      const scratch = scratchRef.current;
      const canvas = canvasRef.current;
      if (!scratch || !canvas) {
        captureQueueRef.current = false;
        return;
      }

      const ctx = canvas.getContext('2d');
      if (!ctx) {
        captureQueueRef.current = false;
        return;
      }

      const onSeeked = () => {
        try {
          ctx.drawImage(scratch, 0, 0, 192, 108);
          const dataUrl = canvas.toDataURL('image/jpeg', 0.65);
          setPreview({
            visible: true,
            x: barX,
            time: targetTime,
            dataUrl,
          });
        } catch {
          /* CORS or decode error */
        }
        captureQueueRef.current = false;
        scratch.removeEventListener('seeked', onSeeked);
        scratch.removeEventListener('error', onError);
      };

      const onError = () => {
        captureQueueRef.current = false;
        scratch.removeEventListener('seeked', onSeeked);
        scratch.removeEventListener('error', onError);
      };

      scratch.addEventListener('seeked', onSeeked);
      scratch.addEventListener('error', onError);
      scratch.currentTime = targetTime;
    },
    []
  );

  const hidePreview = useCallback(() => {
    setPreview((prev) => ({ ...prev, visible: false }));
  }, []);

  return { preview, init, destroy, captureAt, hidePreview };
};
