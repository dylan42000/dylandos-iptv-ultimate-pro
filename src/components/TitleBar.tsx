// ─── Custom Title Bar ────────────────────────────────────────────────────────

import React, { useState, useEffect } from 'react';
import { Minus, Square, X, Maximize2, Copy } from 'lucide-react';

interface TitleBarProps {
  title?: string;
}

export const TitleBar: React.FC<TitleBarProps> = ({
  title = 'DYLANDOS IPTV ULTIMATE',
}) => {
  const [isMaximized, setIsMaximized] = useState(false);

  useEffect(() => {
    window.electronAPI?.window.isMaximized().then(setIsMaximized);
    const handler = (maximized: boolean) => setIsMaximized(maximized);
    window.electronAPI?.on('window:maximize-change', handler);
    return () => {
      window.electronAPI?.off('window:maximize-change', handler);
    };
  }, []);

  return (
    <div
      className="flex items-center h-9 bg-black/40 border-b border-white/5 select-none shrink-0"
      style={{ WebkitAppRegion: 'drag' } as React.CSSProperties}
    >
      {/* App Icon + Title */}
      <div className="flex items-center gap-2 px-3 flex-1 min-w-0">
        <div className="w-4 h-4 rounded bg-gradient-to-br from-cyan-400 to-blue-600 shrink-0" />
        <span className="text-white/50 text-xs font-semibold tracking-wider truncate">
          {title}
        </span>
      </div>

      {/* Window Control Buttons */}
      <div
        className="flex items-center shrink-0"
        style={{ WebkitAppRegion: 'no-drag' } as React.CSSProperties}
      >
        <button
          onClick={() => window.electronAPI?.window.minimize()}
          className="flex items-center justify-center w-11 h-9 
            text-white/40 hover:text-white hover:bg-white/10 transition-colors"
          title="Minimize"
        >
          <Minus size={14} />
        </button>
        <button
          onClick={() => window.electronAPI?.window.maximize()}
          className="flex items-center justify-center w-11 h-9 
            text-white/40 hover:text-white hover:bg-white/10 transition-colors"
          title={isMaximized ? 'Restore' : 'Maximize'}
        >
          {isMaximized ? <Copy size={12} /> : <Square size={12} />}
        </button>
        <button
          onClick={() => window.electronAPI?.window.close()}
          className="flex items-center justify-center w-11 h-9 
            text-white/40 hover:text-white hover:bg-red-500/80 transition-colors"
          title="Close"
        >
          <X size={14} />
        </button>
      </div>
    </div>
  );
};
