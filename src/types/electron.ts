// ─── Electron API types exposed via preload ─────────────────────────────────

export interface ElectronAPI {
  platform: string;
  on: (channel: string, listener: (...args: any[]) => void) => void;
  off: (channel: string, listener: (...args: any[]) => void) => void;
  invoke: (channel: string, ...args: any[]) => Promise<any>;
  send: (channel: string, ...args: any[]) => void;
  window: {
    minimize: () => void;
    maximize: () => void;
    close: () => void;
    isMaximized: () => Promise<boolean>;
  };
  fs: {
    readData: (filename: string) => Promise<string>;
    writeData: (filename: string, data: string) => Promise<void>;
  };
  net: {
    fetchJson: <T = unknown>(
      url: string,
      headers?: Record<string, string>,
      timeoutMs?: number,
      requestId?: string
    ) => Promise<T>;
    fetchText: (
      url: string,
      headers?: Record<string, string>,
      timeoutMs?: number,
      requestId?: string
    ) => Promise<string>;
    abort?: (requestId: string) => Promise<{ aborted: boolean }>;
  };
  settingsSync: (patch: Record<string, unknown>) => Promise<void>;
  version?: string;
  // ── File dialogs (for cloud backup/restore) ────────────────────────────
  showSaveDialog?: (options: { defaultPath?: string; filters?: Array<{ name: string; extensions: string[] }> }) => Promise<string | null>;
  showOpenDialog?: (options: { filters?: Array<{ name: string; extensions: string[] }>; properties?: string[] }) => Promise<string | null>;
  writeFile?: (filePath: string, data: string) => Promise<void>;
  readFile?: (filePath: string) => Promise<string>;
  // ── DVR control ────────────────────────────────────────────────────────
  dvr?: {
    startRecording: (opts: {
      streamUrl: string;
      outputPath?: string;
      channelName?: string;
      programTitle?: string;
      recordingId?: string;
      durationSeconds?: number;
      customOutputDir?: string;
    }) => Promise<{ success: boolean; recordingId?: string; duplicate?: boolean; error?: string }>;
    stopRecording: (recordingId: string) => Promise<{ success: boolean }>;
    getRecordings: () => Promise<any[]>;
    getStatus: () => Promise<any>;
    setOutputDirectory: (dir: string) => Promise<void>;
  };
}

declare global {
  interface Window {
    electronAPI?: ElectronAPI;
  }
}

export {};
