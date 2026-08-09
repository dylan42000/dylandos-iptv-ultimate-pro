import React, { useEffect, useState, useCallback } from 'react';
import {
  Download,
  Folder,
  Play,
  Trash2,
  X,
  CheckCircle,
  AlertCircle,
  Clock,
  Film,
} from 'lucide-react';
import type { VodDownload } from '../services/vodDownloadManager';

// ── Utility ──────────────────────────────────────────────────────────────────

function formatBytes(bytes?: number): string {
  if (!bytes || bytes <= 0) return '—';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) { value /= 1024; idx++; }
  return `${value.toFixed(1)} ${units[idx]}`;
}

function formatDate(ts?: number): string {
  if (!ts) return '';
  return new Date(ts).toLocaleDateString([], { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

// ── Status Badge ─────────────────────────────────────────────────────────────

const StatusBadge: React.FC<{ status: VodDownload['status'] }> = ({ status }) => {
  const map: Record<VodDownload['status'], { label: string; icon: React.ReactNode; className: string }> = {
    queued: {
      label: 'Queued',
      icon: <Clock size={11} />,
      className: 'border-white/10 bg-white/5 text-white/40',
    },
    downloading: {
      label: 'Downloading',
      icon: <Download size={11} className="animate-bounce" />,
      className: 'border-cyan-400/30 bg-cyan-400/10 text-cyan-300',
    },
    completed: {
      label: 'Completed',
      icon: <CheckCircle size={11} />,
      className: 'border-green-500/30 bg-green-500/10 text-green-300',
    },
    failed: {
      label: 'Failed',
      icon: <AlertCircle size={11} />,
      className: 'border-red-500/30 bg-red-500/10 text-red-300',
    },
    cancelled: {
      label: 'Cancelled',
      icon: <X size={11} />,
      className: 'border-white/8 bg-white/4 text-white/30',
    },
  };
  const { label, icon, className } = map[status];
  return (
    <span className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold uppercase tracking-wide ${className}`}>
      {icon}
      {label}
    </span>
  );
};

// ── Download Card ─────────────────────────────────────────────────────────────

const DownloadCard: React.FC<{
  item: VodDownload;
  onCancel: (id: string) => void;
  onRemove: (id: string) => void;
  onOpenFile: (id: string) => void;
  onOpenFolder: (id: string) => void;
}> = ({ item, onCancel, onRemove, onOpenFile, onOpenFolder }) => {
  const isActive = item.status === 'downloading' || item.status === 'queued';
  const isDone = item.status === 'completed';

  return (
    <div className="group rounded-2xl border border-white/[0.07] bg-white/[0.02] p-4 transition-all hover:border-white/[0.12] hover:bg-white/[0.04]">
      <div className="flex items-start gap-4">
        {/* Poster thumbnail */}
        <div className="relative h-16 w-12 shrink-0 overflow-hidden rounded-xl bg-white/[0.05]">
          {item.poster ? (
            <img src={item.poster} alt="" className="h-full w-full object-cover" />
          ) : (
            <div className="flex h-full w-full items-center justify-center">
              <Film size={18} className="text-white/20" />
            </div>
          )}
          {isDone && (
            <div className="absolute inset-0 flex items-center justify-center bg-black/40">
              <CheckCircle size={18} className="text-green-400" />
            </div>
          )}
        </div>

        {/* Info */}
        <div className="min-w-0 flex-1">
          <div className="flex items-start justify-between gap-3">
            <p className="text-sm font-semibold text-white/90 line-clamp-1">{item.title}</p>
            <div className="flex shrink-0 items-center gap-1.5 opacity-0 transition-opacity group-hover:opacity-100">
              {isDone && (
                <>
                  <button
                    onClick={() => onOpenFile(item.id)}
                    title="Open file"
                    className="rounded-lg border border-white/10 bg-white/5 p-1.5 text-white/50 hover:text-white"
                  >
                    <Play size={12} />
                  </button>
                  <button
                    onClick={() => onOpenFolder(item.id)}
                    title="Show in folder"
                    className="rounded-lg border border-white/10 bg-white/5 p-1.5 text-white/50 hover:text-white"
                  >
                    <Folder size={12} />
                  </button>
                </>
              )}
              {isActive && (
                <button
                  onClick={() => onCancel(item.id)}
                  title="Cancel"
                  className="rounded-lg border border-red-500/20 bg-red-500/10 p-1.5 text-red-300/70 hover:text-red-300"
                >
                  <X size={12} />
                </button>
              )}
              {!isActive && (
                <button
                  onClick={() => onRemove(item.id)}
                  title="Remove"
                  className="rounded-lg border border-white/10 bg-white/5 p-1.5 text-white/40 hover:text-red-300"
                >
                  <Trash2 size={12} />
                </button>
              )}
            </div>
          </div>

          <div className="mt-1.5 flex flex-wrap items-center gap-2">
            <StatusBadge status={item.status} />
            {item.sizeBytes && (
              <span className="text-[11px] text-white/30">
                {item.status === 'completed'
                  ? formatBytes(item.sizeBytes)
                  : `${formatBytes(item.downloadedBytes)} / ${formatBytes(item.sizeBytes)}`}
              </span>
            )}
            {item.completedAt && (
              <span className="text-[11px] text-white/25">{formatDate(item.completedAt)}</span>
            )}
          </div>

          {/* Progress bar for active downloads */}
          {(item.status === 'downloading' || item.status === 'queued') && (
            <div className="mt-3">
              <div className="mb-1 flex justify-between text-[10px] text-white/30">
                <span>{item.status === 'queued' ? 'Waiting…' : 'Downloading…'}</span>
                <span>{item.progress.toFixed(0)}%</span>
              </div>
              <div className="h-1.5 overflow-hidden rounded-full bg-white/[0.06]">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-cyan-500 to-blue-500 transition-all duration-500"
                  style={{ width: `${item.progress}%` }}
                />
              </div>
            </div>
          )}

          {item.error && (
            <p className="mt-2 text-[11px] text-red-300/70">{item.error}</p>
          )}
        </div>
      </div>
    </div>
  );
};

// ── Main Page ─────────────────────────────────────────────────────────────────

export const DownloadsPage: React.FC = () => {
  const [downloads, setDownloads] = useState<VodDownload[]>([]);

  useEffect(() => {
    let unsub: (() => void) | null = null;
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      unsub = vodDownloadManager.subscribe(setDownloads);
    });
    return () => { unsub?.(); };
  }, []);

  const handleCancel = useCallback((id: string) => {
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      vodDownloadManager.cancelDownload(id);
    });
  }, []);

  const handleRemove = useCallback((id: string) => {
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      vodDownloadManager.removeDownload(id);
    });
  }, []);

  const handleOpenFile = useCallback((id: string) => {
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      vodDownloadManager.openFile(id);
    });
  }, []);

  const handleOpenFolder = useCallback((id: string) => {
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      vodDownloadManager.openFolder(id);
    });
  }, []);

  const handleClearCompleted = useCallback(() => {
    import('../services/vodDownloadManager').then(({ vodDownloadManager }) => {
      vodDownloadManager.clearCompleted();
    });
  }, []);

  const active = downloads.filter(d => d.status === 'downloading' || d.status === 'queued');
  const done = downloads.filter(d => d.status === 'completed' || d.status === 'failed' || d.status === 'cancelled');

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#05070d]">
      {/* Header */}
      <div className="flex items-center gap-3 border-b border-white/[0.06] px-6 py-4">
        <Download size={18} className="text-cyan-300" />
        <div>
          <h2 className="text-lg font-bold text-white">Downloads</h2>
          <p className="text-xs text-white/35">
            {active.length} active · {done.length} completed
          </p>
        </div>
        <div className="flex-1" />
        {done.length > 0 && (
          <button
            onClick={handleClearCompleted}
            className="flex items-center gap-2 rounded-xl border border-white/[0.08] bg-white/[0.04] px-3 py-2 text-sm text-white/50 transition-colors hover:text-white"
          >
            <Trash2 size={14} />
            Clear Finished
          </button>
        )}
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6">
        {downloads.length === 0 ? (
          <div className="flex h-full flex-col items-center justify-center gap-4 text-center">
            <div className="flex h-20 w-20 items-center justify-center rounded-2xl border border-white/[0.06] bg-white/[0.02]">
              <Download size={32} className="text-white/15" />
            </div>
            <div>
              <p className="text-base font-semibold text-white/40">No downloads yet</p>
              <p className="mt-1 text-sm text-white/20">
                Click the download icon on any movie or episode to save it offline.
              </p>
            </div>
          </div>
        ) : (
          <div className="space-y-6">
            {active.length > 0 && (
              <section>
                <h3 className="mb-3 text-xs font-semibold uppercase tracking-[0.2em] text-white/30">
                  Active ({active.length})
                </h3>
                <div className="space-y-2">
                  {active.map(item => (
                    <DownloadCard
                      key={item.id}
                      item={item}
                      onCancel={handleCancel}
                      onRemove={handleRemove}
                      onOpenFile={handleOpenFile}
                      onOpenFolder={handleOpenFolder}
                    />
                  ))}
                </div>
              </section>
            )}

            {done.length > 0 && (
              <section>
                <h3 className="mb-3 text-xs font-semibold uppercase tracking-[0.2em] text-white/30">
                  Library ({done.length})
                </h3>
                <div className="space-y-2">
                  {done.map(item => (
                    <DownloadCard
                      key={item.id}
                      item={item}
                      onCancel={handleCancel}
                      onRemove={handleRemove}
                      onOpenFile={handleOpenFile}
                      onOpenFolder={handleOpenFolder}
                    />
                  ))}
                </div>
              </section>
            )}
          </div>
        )}
      </div>
    </div>
  );
};
