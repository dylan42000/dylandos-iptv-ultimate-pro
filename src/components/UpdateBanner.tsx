// ─── Update Banner Component ─────────────────────────────────────────────────

import React, { useEffect, useState } from 'react';
import { Download, X, CheckCircle } from 'lucide-react';

interface UpdateInfo {
  version: string;
  releaseNotes?: string;
}

interface DownloadProgress {
  percent: number;
  bytesPerSecond: number;
}

export const UpdateBanner: React.FC = () => {
  const [state, setState] = useState<
    | { type: 'idle' }
    | { type: 'available'; info: UpdateInfo }
    | { type: 'downloading'; progress: DownloadProgress }
    | { type: 'ready'; info: UpdateInfo }
  >({ type: 'idle' });
  const [dismissed, setDismissed] = useState(false);

  useEffect(() => {
    const onAvailable = (data: unknown) => {
      setState({ type: 'available', info: data as UpdateInfo });
      setDismissed(false);
    };
    const onProgress = (data: unknown) => {
      setState({
        type: 'downloading',
        progress: data as DownloadProgress,
      });
    };
    const onDownloaded = (data: unknown) => {
      setState({ type: 'ready', info: data as UpdateInfo });
    };

    window.electronAPI?.on('update:available', onAvailable);
    window.electronAPI?.on('update:download-progress', onProgress);
    window.electronAPI?.on('update:downloaded', onDownloaded);

    return () => {
      window.electronAPI?.off('update:available', onAvailable);
      window.electronAPI?.off('update:download-progress', onProgress);
      window.electronAPI?.off('update:downloaded', onDownloaded);
    };
  }, []);

  if (dismissed || state.type === 'idle') return null;

  return (
    <div
      className="fixed top-10 left-1/2 -translate-x-1/2 z-[100]
        bg-surface-100 border border-white/15 rounded-2xl px-5 py-4
        shadow-2xl backdrop-blur-md flex items-center gap-4
        animate-in"
      style={{ minWidth: 360 }}
    >
      {state.type === 'available' && (
        <>
          <Download size={18} className="text-cyan-400 shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-white text-sm font-semibold">
              Update Available — v{state.info.version}
            </p>
            <p className="text-white/50 text-xs">
              Downloading in background...
            </p>
          </div>
          <button
            onClick={() => setDismissed(true)}
            className="text-white/30 hover:text-white"
          >
            <X size={14} />
          </button>
        </>
      )}

      {state.type === 'downloading' && (
        <>
          <div className="w-5 h-5 rounded-full border-2 border-cyan-500/30 border-t-cyan-500 animate-spin shrink-0" />
          <div className="flex-1 min-w-0">
            <div className="flex justify-between mb-1">
              <span className="text-white text-sm font-semibold">
                Downloading update...
              </span>
              <span className="text-cyan-400 text-sm font-mono">
                {state.progress.percent}%
              </span>
            </div>
            <div className="h-1 bg-white/10 rounded-full overflow-hidden">
              <div
                className="h-full bg-cyan-500 rounded-full transition-all duration-300"
                style={{ width: `${state.progress.percent}%` }}
              />
            </div>
          </div>
        </>
      )}

      {state.type === 'ready' && (
        <>
          <CheckCircle size={18} className="text-green-400 shrink-0" />
          <div className="flex-1 min-w-0">
            <p className="text-white text-sm font-semibold">
              v{state.info.version} ready to install
            </p>
            <p className="text-white/50 text-xs">
              Restart to apply the update
            </p>
          </div>
          <button
            onClick={() =>
              window.electronAPI?.invoke('update:install-and-restart')
            }
            className="px-3 py-1.5 bg-green-500 text-black text-xs font-bold rounded-xl
              hover:bg-green-400 transition-colors shrink-0"
          >
            Restart
          </button>
          <button
            onClick={() => setDismissed(true)}
            className="text-white/30 hover:text-white ml-1"
          >
            <X size={14} />
          </button>
        </>
      )}
    </div>
  );
};
