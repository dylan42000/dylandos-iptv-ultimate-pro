/**
 * DYLANDOS IPTV ULTIMATE — VOD Download Manager
 * Manages offline downloads for movies and series episodes.
 * Downloads are queued through Electron IPC (vod:download channel).
 */

export type DownloadStatus = 'queued' | 'downloading' | 'completed' | 'failed' | 'cancelled';

export interface VodDownload {
  id: string;
  title: string;
  poster?: string;
  url: string;
  filePath?: string;
  status: DownloadStatus;
  progress: number;         // 0–100
  sizeBytes?: number;
  downloadedBytes?: number;
  error?: string;
  addedAt: number;
  completedAt?: number;
}

type DownloadListener = (downloads: VodDownload[]) => void;

const STORAGE_KEY = 'dylandos:vod_downloads';

class VodDownloadManager {
  private downloads = new Map<string, VodDownload>();
  private listeners = new Set<DownloadListener>();

  constructor() {
    this.load();
    this.bindIpcProgress();
  }

  // ── Public API ──────────────────────────────────────────────────────────────

  getAll(): VodDownload[] {
    return Array.from(this.downloads.values()).sort((a, b) => b.addedAt - a.addedAt);
  }

  get(id: string): VodDownload | undefined {
    return this.downloads.get(id);
  }

  addDownload(opts: {
    title: string;
    url: string;
    poster?: string;
  }): VodDownload {
    const id = `dl_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    const item: VodDownload = {
      id,
      title: opts.title,
      url: opts.url,
      poster: opts.poster,
      status: 'queued',
      progress: 0,
      addedAt: Date.now(),
    };
    this.downloads.set(id, item);
    this.save();
    this.emit();
    this.startDownload(item);
    return item;
  }

  cancelDownload(id: string): void {
    const item = this.downloads.get(id);
    if (!item) return;
    if (item.status === 'downloading' || item.status === 'queued') {
      window.electronAPI?.invoke?.('vod:cancel', { id }).catch(() => {});
      this.update(id, { status: 'cancelled' });
    }
  }

  removeDownload(id: string): void {
    this.downloads.delete(id);
    this.save();
    this.emit();
  }

  clearCompleted(): void {
    for (const [id, dl] of this.downloads) {
      if (dl.status === 'completed' || dl.status === 'cancelled' || dl.status === 'failed') {
        this.downloads.delete(id);
      }
    }
    this.save();
    this.emit();
  }

  openFile(id: string): void {
    const item = this.downloads.get(id);
    if (item?.filePath) {
      window.electronAPI?.invoke?.('shell:openFile', { path: item.filePath }).catch(() => {});
    }
  }

  openFolder(id: string): void {
    const item = this.downloads.get(id);
    if (item?.filePath) {
      window.electronAPI?.invoke?.('shell:showItemInFolder', { path: item.filePath }).catch(() => {});
    }
  }

  subscribe(listener: DownloadListener): () => void {
    this.listeners.add(listener);
    listener(this.getAll());
    return () => this.listeners.delete(listener);
  }

  // ── Private ─────────────────────────────────────────────────────────────────

  private startDownload(item: VodDownload): void {
    window.electronAPI?.invoke?.('vod:download', {
      id: item.id,
      url: item.url,
      title: item.title,
    }).then((result: { filePath?: string; error?: string; cancelled?: boolean } | null) => {
      const current = this.downloads.get(item.id);
      if (current?.status === 'cancelled') return;
      if (result?.cancelled || result?.error === 'cancelled') {
        this.update(item.id, { status: 'cancelled' });
      } else if (result?.error) {
        this.update(item.id, { status: 'failed', error: result.error });
      } else if (result?.filePath) {
        this.update(item.id, {
          status: 'completed',
          progress: 100,
          filePath: result.filePath,
          completedAt: Date.now(),
        });
      }
    }).catch((err: Error) => {
      const current = this.downloads.get(item.id);
      if (current?.status === 'cancelled') return;
      this.update(item.id, { status: 'failed', error: err.message });
    });
    this.update(item.id, { status: 'downloading' });
  }

  private bindIpcProgress(): void {
    window.electronAPI?.on?.('vod:progress', (data: {
      id: string;
      progress: number;
      downloadedBytes?: number;
      sizeBytes?: number;
      cancelled?: boolean;
    }) => {
      if (data.cancelled) {
        this.update(data.id, { status: 'cancelled', progress: 0 });
        return;
      }
      this.update(data.id, {
        progress: data.progress,
        downloadedBytes: data.downloadedBytes,
        sizeBytes: data.sizeBytes,
      });
    });
  }

  private update(id: string, patch: Partial<VodDownload>): void {
    const existing = this.downloads.get(id);
    if (!existing) return;
    this.downloads.set(id, { ...existing, ...patch });
    this.save();
    this.emit();
  }

  private emit(): void {
    const all = this.getAll();
    this.listeners.forEach(fn => fn(all));
  }

  private save(): void {
    try {
      const arr = Array.from(this.downloads.values());
      localStorage.setItem(STORAGE_KEY, JSON.stringify(arr));
    } catch {
      // quota exceeded — ignore
    }
  }

  private load(): void {
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      if (!raw) return;
      const arr = JSON.parse(raw) as VodDownload[];
      for (const item of arr) {
        // Reset in-progress downloads to failed on cold start
        if (item.status === 'downloading' || item.status === 'queued') {
          item.status = 'failed';
          item.error = 'Interrupted by app restart';
        }
        this.downloads.set(item.id, item);
      }
    } catch {
      // corrupt storage — ignore
    }
  }
}

export const vodDownloadManager = new VodDownloadManager();
