/**
 * DYLANDOS IPTV ULTIMATE — Picture-in-Picture Hook
 * Creates a floating mini-player window via Electron IPC.
 * Falls back to native browser PiP when not in Electron context.
 */

import { useState, useCallback, useRef } from 'react';

export interface PipOptions {
  streamUrl: string;
  title: string;
  channelLogo?: string;
}

export interface UsePipReturn {
  isPipOpen: boolean;
  openPip: (opts: PipOptions) => Promise<void>;
  closePip: () => void;
}

export function usePip(): UsePipReturn {
  const [isPipOpen, setIsPipOpen] = useState(false);
  const videoRef = useRef<HTMLVideoElement | null>(null);

  const openPip = useCallback(async (opts: PipOptions) => {
    // ── Electron path: open a child always-on-top BrowserWindow ──────────
    if (window.electronAPI?.invoke) {
      try {
        await window.electronAPI.invoke('pip:open', {
          url: opts.streamUrl,
          title: opts.title,
          logo: opts.channelLogo ?? '',
        });
        setIsPipOpen(true);
        return;
      } catch (err) {
        console.warn('[PiP] Electron IPC failed, falling back to browser PiP:', err);
      }
    }

    // ── Browser fallback: use HTMLVideoElement.requestPictureInPicture ──
    try {
      if (!videoRef.current) {
        const video = document.createElement('video');
        video.src = opts.streamUrl;
        video.muted = false;
        video.autoplay = true;
        video.style.cssText = 'position:fixed;width:1px;height:1px;opacity:0.01;pointer-events:none';
        document.body.appendChild(video);
        videoRef.current = video;
        await video.play();
      }
      if (document.pictureInPictureEnabled && videoRef.current) {
        await videoRef.current.requestPictureInPicture();
        videoRef.current.addEventListener('leavepictureinpicture', () => {
          setIsPipOpen(false);
        }, { once: true });
        setIsPipOpen(true);
      }
    } catch (err) {
      console.warn('[PiP] Browser PiP not available:', err);
    }
  }, []);

  const closePip = useCallback(() => {
    // Close Electron pip window
    window.electronAPI?.invoke?.('pip:close', {}).catch(() => {});

    // Exit browser PiP if active
    if (document.pictureInPictureElement) {
      document.exitPictureInPicture().catch(() => {});
    }

    // Clean up video element
    if (videoRef.current) {
      videoRef.current.pause();
      videoRef.current.remove();
      videoRef.current = null;
    }

    setIsPipOpen(false);
  }, []);

  return { isPipOpen, openPip, closePip };
}
