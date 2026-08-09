// ─── Cloud Backup & Restore ───────────────────────────────────────────────────
// File-based JSON export/import via Electron's dialog API.
// No external cloud dependency — user controls their backup file.

import { loadSettings, saveSettings, loadProfiles, saveProfiles, loadFavorites, loadRecentlyWatched } from './storage';
import { AppSettings } from '../types/settings';
import { XtreamProfile, WatchProgress } from '../types/xtream';
import { playlistManager, PlaylistSource } from './playlistManager';
import { dvrScheduler, ScheduledRecording } from './dvrScheduler';
import { parentalControls } from './parentalControls';

export interface BackupPayload {
  version: 3;
  exportedAt: number;
  appVersion: string;
  profiles: XtreamProfile[];
  playlists: PlaylistSource[];
  favorites: string[];
  watchProgress: WatchProgress[];
  settings: AppSettings;
  scheduledRecordings: ScheduledRecording[];
  parentalLockedCategories: string[];
}

function getAppVersion(): string {
  return (window as any).electronAPI?.version ?? '1.0.0';
}

// ── Export ────────────────────────────────────────────────────────────────────

export async function exportBackup(): Promise<{ success: boolean; filePath?: string; error?: string }> {
  const payload: BackupPayload = {
    version: 3,
    exportedAt: Date.now(),
    appVersion: getAppVersion(),
    profiles: loadProfiles(),
    playlists: playlistManager.getSources(),
    favorites: loadFavorites(),
    watchProgress: loadRecentlyWatched(),
    settings: loadSettings(),
    scheduledRecordings: dvrScheduler.schedules,
    parentalLockedCategories: [...parentalControls.getLockedCategories()],
  };

  if (!window.electronAPI?.showSaveDialog) {
    // Fallback: trigger browser download
    const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `dylandos-backup-${new Date().toISOString().split('T')[0]}.json`;
    a.click();
    URL.revokeObjectURL(url);
    return { success: true };
  }

  try {
    const filePath = await window.electronAPI.showSaveDialog({
      defaultPath: `dylandos-backup-${new Date().toISOString().split('T')[0]}.json`,
      filters: [{ name: 'DYLANDOS Backup', extensions: ['json'] }],
    });

    if (!filePath) return { success: false }; // User cancelled

    await window.electronAPI.writeFile!(filePath, JSON.stringify(payload, null, 2));
    return { success: true, filePath };
  } catch (err) {
    return { success: false, error: (err as Error).message };
  }
}

// ── Import ────────────────────────────────────────────────────────────────────

export async function importBackup(): Promise<{ success: boolean; payload?: BackupPayload; error?: string }> {
  let content: string;

  if (!window.electronAPI?.showOpenDialog) {
    // Fallback: file input
    return new Promise(resolve => {
      const input = document.createElement('input');
      input.type = 'file';
      input.accept = '.json';
      input.onchange = async () => {
        const file = input.files?.[0];
        if (!file) { resolve({ success: false }); return; }
        const text = await file.text();
        resolve(applyBackup(text));
      };
      input.click();
    });
  }

  try {
    const filePath = await window.electronAPI.showOpenDialog({
      filters: [{ name: 'DYLANDOS Backup', extensions: ['json'] }],
      properties: ['openFile'],
    });

    if (!filePath) return { success: false }; // User cancelled

    content = await window.electronAPI.readFile!(filePath);
    return applyBackup(content);
  } catch (err) {
    return { success: false, error: (err as Error).message };
  }
}

function applyBackup(jsonContent: string): { success: boolean; payload?: BackupPayload; error?: string } {
  try {
    const payload = JSON.parse(jsonContent) as BackupPayload;

    // Validate
    if (!payload.version || payload.version !== 3) {
      // Try to handle v1/v2 backups gracefully
      console.warn('[cloudSync] Backup version mismatch, attempting partial restore');
    }

    // Restore profiles
    if (Array.isArray(payload.profiles)) {
      saveProfiles(payload.profiles);
    }

    // Restore playlist sources
    if (Array.isArray(payload.playlists)) {
      payload.playlists.forEach(p => playlistManager.addSource(p));
    }

    // Restore settings (merge with defaults)
    if (payload.settings && typeof payload.settings === 'object') {
      saveSettings(payload.settings);
    }

    // Restore parental locked categories
    if (Array.isArray(payload.parentalLockedCategories)) {
      parentalControls.setLockedCategories(payload.parentalLockedCategories);
    }

    // Note: favorites and watch progress are restored in App.tsx
    // after reload, since those hooks manage their own state.

    return { success: true, payload };
  } catch (err) {
    return { success: false, error: `Invalid backup file: ${(err as Error).message}` };
  }
}
