// ─── Keyboard Shortcuts Hook ─────────────────────────────────────────────────

import { useEffect, useCallback, useRef } from 'react';

export interface ShortcutRef {
  key: string;
  description: string;
  category: 'playback' | 'volume' | 'navigation' | 'view' | 'app';
}

export interface ShortcutHandlers {
  playPause: () => void;
  stop: () => void;
  seekBack10: () => void;
  seekForward10: () => void;
  seekBack30: () => void;
  seekForward30: () => void;
  seekBack60: () => void;
  seekForward60: () => void;
  volumeUp: () => void;
  volumeDown: () => void;
  mute: () => void;
  volumeMax: () => void;
  nextChannel: () => void;
  prevChannel: () => void;
  lastChannel?: () => void;
  onDigit: (d: string) => void;
  fullscreen: () => void;
  pip: () => void;
  toggleMultiview?: () => void;
  toggleEpg: () => void;
  toggleSidebar: () => void;
  openSearch: () => void;
  goHome: () => void;
  goLiveTV: () => void;
  goMovies: () => void;
  escape: () => void;
}

let channelBuffer = '';
let channelBufferTimer: ReturnType<typeof setTimeout> | null = null;

const clearChannelBuffer = () => {
  channelBuffer = '';
  if (channelBufferTimer) clearTimeout(channelBufferTimer);
};

export const useKeyboardShortcuts = (
  handlers: ShortcutHandlers,
  enabled = true
) => {
  const handlersRef = useRef(handlers);
  useEffect(() => {
    handlersRef.current = handlers;
  });

  const handleKeyDown = useCallback(
    (e: KeyboardEvent) => {
      if (!enabled) return;

      const target = e.target as HTMLElement;
      if (
        target.tagName === 'INPUT' ||
        target.tagName === 'TEXTAREA' ||
        target.tagName === 'SELECT' ||
        target.isContentEditable
      )
        return;

      const h = handlersRef.current;
      const ctrl = e.ctrlKey || e.metaKey;
      const shift = e.shiftKey;

      // Channel number buffer
      if (/^Digit[0-9]$/.test(e.code) && !ctrl && !shift) {
        const digit = e.code.replace('Digit', '');
        channelBuffer += digit;
        h.onDigit(channelBuffer);
        if (channelBufferTimer) clearTimeout(channelBufferTimer);
        channelBufferTimer = setTimeout(clearChannelBuffer, 1500);
        e.preventDefault();
        return;
      }

      switch (e.code) {
        case 'Space':
        case 'KeyK':
          h.playPause();
          e.preventDefault();
          return;

        case 'KeyS':
          if (!ctrl) {
            h.stop();
            e.preventDefault();
            return;
          }
          break;

        case 'ArrowRight':
          if (!ctrl && !shift) h.seekForward10();
          else if (shift) h.seekForward30();
          else if (ctrl) h.seekForward60();
          e.preventDefault();
          return;

        case 'ArrowLeft':
          if (!ctrl && !shift) h.seekBack10();
          else if (shift) h.seekBack30();
          else if (ctrl) h.seekBack60();
          e.preventDefault();
          return;

        case 'ArrowUp':
          h.volumeUp();
          e.preventDefault();
          return;

        case 'ArrowDown':
          h.volumeDown();
          e.preventDefault();
          return;

        case 'KeyM':
          if (ctrl) {
            h.toggleMultiview?.();
            e.preventDefault();
            return;
          }
          h.mute();
          e.preventDefault();
          return;

        case 'Comma':
        case 'Backspace':
          if (!ctrl && !shift) {
            h.lastChannel?.();
            e.preventDefault();
            return;
          }
          break;

        case 'PageUp':
          h.prevChannel();
          e.preventDefault();
          return;

        case 'PageDown':
          h.nextChannel();
          e.preventDefault();
          return;

        case 'KeyF':
          if (!ctrl) {
            h.fullscreen();
            e.preventDefault();
            return;
          }
          if (ctrl) {
            h.openSearch();
            e.preventDefault();
            return;
          }
          break;

        case 'KeyP':
          if (!ctrl) {
            h.pip();
            e.preventDefault();
            return;
          }
          break;

        case 'KeyG':
          h.toggleEpg();
          e.preventDefault();
          return;

        case 'KeyB':
          h.toggleSidebar();
          e.preventDefault();
          return;

        case 'Slash':
          if (ctrl) {
            h.openSearch();
            e.preventDefault();
            return;
          }
          break;

        case 'Escape':
          h.escape();
          e.preventDefault();
          return;

        case 'Digit1':
          if (ctrl) {
            h.goHome();
            e.preventDefault();
            return;
          }
          break;
        case 'Digit2':
          if (ctrl) {
            h.goLiveTV();
            e.preventDefault();
            return;
          }
          break;
        case 'Digit3':
          if (ctrl) {
            h.goMovies();
            e.preventDefault();
            return;
          }
          break;
      }
    },
    [enabled]
  );

  useEffect(() => {
    window.addEventListener('keydown', handleKeyDown, { capture: true });
    return () => window.removeEventListener('keydown', handleKeyDown, { capture: true });
  }, [handleKeyDown]);

  return {
    shortcuts: [
      { key: 'Space / K', description: 'Play / Pause', category: 'playback' as const },
      { key: '← / →', description: 'Seek ±10s', category: 'playback' as const },
      { key: 'Shift + ← / →', description: 'Seek ±30s', category: 'playback' as const },
      { key: 'Ctrl + ← / →', description: 'Seek ±60s', category: 'playback' as const },
      { key: '↑ / ↓', description: 'Volume up / down', category: 'volume' as const },
      { key: 'M', description: 'Mute toggle', category: 'volume' as const },
      { key: 'Page Up / Down', description: 'Prev / Next channel', category: 'navigation' as const },
      { key: '0–9', description: 'Channel number (1.5s timeout)', category: 'navigation' as const },
      { key: ', / Backspace', description: 'Last channel', category: 'navigation' as const },
      { key: 'F', description: 'Toggle fullscreen', category: 'view' as const },
      { key: 'P', description: 'Toggle Picture-in-Picture', category: 'view' as const },
      { key: 'Ctrl + M', description: 'Multi-View', category: 'view' as const },
      { key: 'G', description: 'Toggle EPG Guide', category: 'view' as const },
      { key: 'B', description: 'Toggle sidebar', category: 'view' as const },
      { key: 'Ctrl + F', description: 'Search', category: 'app' as const },
      { key: 'Ctrl + 1/2/3', description: 'Home / Live TV / Movies', category: 'app' as const },
      { key: 'Esc', description: 'Exit fullscreen / Close dialogs', category: 'app' as const },
    ] as ShortcutRef[],
  };
};
