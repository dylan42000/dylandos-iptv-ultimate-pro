// ─── Keyboard Shortcut Mapper ─────────────────────────────────────────────────
// Manages user-defined key bindings. Falls back to defaults when no custom key set.
// Persists to localStorage. Used by useKeyboardShortcuts hook.

const STORAGE_KEY = 'dylandos:keybindings';

export type ShortcutAction =
  | 'toggle-play-pause'
  | 'toggle-fullscreen'
  | 'toggle-mute'
  | 'volume-up'
  | 'volume-down'
  | 'seek-forward-10'
  | 'seek-back-10'
  | 'seek-forward-30'
  | 'seek-back-30'
  | 'next-channel'
  | 'prev-channel'
  | 'toggle-guide'
  | 'toggle-search'
  | 'toggle-favorites'
  | 'toggle-pip'
  | 'start-recording'
  | 'stop-recording'
  | 'open-dvr'
  | 'screenshot'
  | 'toggle-subtitles'
  | 'toggle-audio-tracks'
  | 'toggle-stats'
  | 'toggle-multiview'
  | 'open-settings'
  | 'escape'
  | 'zoom-in'
  | 'zoom-out';

export interface KeyBinding {
  action: ShortcutAction;
  label: string;
  defaultKey: string;    // e.g. "Space", "f", "ArrowRight", "ctrl+s"
  userKey: string | null; // null = use default
  category: 'Playback' | 'Navigation' | 'DVR' | 'UI' | 'Audio';
}

export const DEFAULT_BINDINGS: KeyBinding[] = [
  // Playback
  { action: 'toggle-play-pause', label: 'Play / Pause', defaultKey: ' ', userKey: null, category: 'Playback' },
  { action: 'seek-forward-10', label: 'Skip Forward 10s', defaultKey: 'ArrowRight', userKey: null, category: 'Playback' },
  { action: 'seek-back-10', label: 'Skip Back 10s', defaultKey: 'ArrowLeft', userKey: null, category: 'Playback' },
  { action: 'seek-forward-30', label: 'Skip Forward 30s', defaultKey: 'l', userKey: null, category: 'Playback' },
  { action: 'seek-back-30', label: 'Skip Back 30s', defaultKey: 'j', userKey: null, category: 'Playback' },
  { action: 'toggle-fullscreen', label: 'Toggle Fullscreen', defaultKey: 'f', userKey: null, category: 'Playback' },
  { action: 'screenshot', label: 'Screenshot', defaultKey: 'ctrl+s', userKey: null, category: 'Playback' },
  // Audio
  { action: 'toggle-mute', label: 'Toggle Mute', defaultKey: 'm', userKey: null, category: 'Audio' },
  { action: 'volume-up', label: 'Volume Up', defaultKey: 'ArrowUp', userKey: null, category: 'Audio' },
  { action: 'volume-down', label: 'Volume Down', defaultKey: 'ArrowDown', userKey: null, category: 'Audio' },
  { action: 'toggle-subtitles', label: 'Toggle Subtitles', defaultKey: 's', userKey: null, category: 'Audio' },
  { action: 'toggle-audio-tracks', label: 'Audio Track', defaultKey: 'a', userKey: null, category: 'Audio' },
  // Navigation
  { action: 'next-channel', label: 'Next Channel', defaultKey: 'PageDown', userKey: null, category: 'Navigation' },
  { action: 'prev-channel', label: 'Previous Channel', defaultKey: 'PageUp', userKey: null, category: 'Navigation' },
  { action: 'toggle-guide', label: 'Open EPG Guide', defaultKey: 'g', userKey: null, category: 'Navigation' },
  { action: 'toggle-search', label: 'Open Search', defaultKey: '/', userKey: null, category: 'Navigation' },
  { action: 'toggle-favorites', label: 'Open Favorites', defaultKey: 'F5', userKey: null, category: 'Navigation' },
  { action: 'open-settings', label: 'Open Settings', defaultKey: ',', userKey: null, category: 'Navigation' },
  { action: 'escape', label: 'Close / Back', defaultKey: 'Escape', userKey: null, category: 'Navigation' },
  // DVR
  { action: 'start-recording', label: 'Start Recording', defaultKey: 'r', userKey: null, category: 'DVR' },
  { action: 'stop-recording', label: 'Stop Recording', defaultKey: 'ctrl+r', userKey: null, category: 'DVR' },
  { action: 'open-dvr', label: 'Open DVR Manager', defaultKey: 'd', userKey: null, category: 'DVR' },
  // UI
  { action: 'toggle-stats', label: 'Toggle Stats Overlay', defaultKey: 'i', userKey: null, category: 'UI' },
  { action: 'toggle-pip', label: 'Picture-in-Picture', defaultKey: 'p', userKey: null, category: 'UI' },
  { action: 'toggle-multiview', label: 'Multi-View', defaultKey: 'ctrl+m', userKey: null, category: 'UI' },
  { action: 'zoom-in', label: 'Zoom In', defaultKey: '=', userKey: null, category: 'UI' },
  { action: 'zoom-out', label: 'Zoom Out', defaultKey: '-', userKey: null, category: 'UI' },
];

class KeyboardMapperClass {
  private bindings: KeyBinding[] = JSON.parse(JSON.stringify(DEFAULT_BINDINGS));
  private loaded = false;

  load(): void {
    if (this.loaded) return;
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (raw) {
        const saved: Partial<Record<ShortcutAction, string>> = JSON.parse(raw);
        this.bindings = this.bindings.map(b => ({
          ...b,
          userKey: saved[b.action] ?? null,
        }));
      }
    } catch {
      // Use defaults
    }
    this.loaded = true;
  }

  save(): void {
    const overrides: Partial<Record<ShortcutAction, string>> = {};
    this.bindings.forEach(b => {
      if (b.userKey !== null) overrides[b.action] = b.userKey;
    });
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(overrides));
    } catch {
      // ignore
    }
  }

  getBindings(): KeyBinding[] {
    this.load();
    return [...this.bindings];
  }

  getKey(action: ShortcutAction): string {
    this.load();
    const b = this.bindings.find(b => b.action === action);
    return b?.userKey ?? b?.defaultKey ?? '';
  }

  setKey(action: ShortcutAction, key: string): void {
    this.load();
    const b = this.bindings.find(b => b.action === action);
    if (b) {
      b.userKey = key;
      this.save();
    }
  }

  clearKey(action: ShortcutAction): void {
    this.load();
    const b = this.bindings.find(b => b.action === action);
    if (b) {
      b.userKey = null;
      this.save();
    }
  }

  resetAll(): void {
    this.bindings = JSON.parse(JSON.stringify(DEFAULT_BINDINGS));
    localStorage.removeItem(STORAGE_KEY);
  }

  // Check if a key is already bound to another action
  getConflict(key: string, excludeAction?: ShortcutAction): ShortcutAction | null {
    this.load();
    const conflict = this.bindings.find(b =>
      b.action !== excludeAction &&
      (b.userKey === key || (!b.userKey && b.defaultKey === key))
    );
    return conflict?.action ?? null;
  }

  // Get the label for display (e.g. 'Space' → '␣', 'ArrowRight' → '→')
  formatKey(key: string): string {
    const map: Record<string, string> = {
      ' ': 'Space', 'ArrowRight': '→', 'ArrowLeft': '←',
      'ArrowUp': '↑', 'ArrowDown': '↓', 'Escape': 'Esc',
      'Enter': '↵', 'PageUp': 'PgUp', 'PageDown': 'PgDn',
      'Delete': 'Del', 'Backspace': '⌫', 'Tab': '↹',
    };
    return map[key] ?? key.toUpperCase();
  }
}

export const keyboardMapper = new KeyboardMapperClass();
export { KeyboardMapperClass };
