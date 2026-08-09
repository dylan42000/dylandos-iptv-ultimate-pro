// ─── PIN Entry Dialog ─────────────────────────────────────────────────────────
// 4-digit PIN pad for parental controls.
// Shows ●●●● masking. Auto-submits after 4th digit.
// Keyboard navigable (number keys 0-9, Backspace, Escape).

import React, { useCallback, useEffect, useState } from 'react';
import { X, Lock } from 'lucide-react';

interface PinEntryDialogProps {
  /** Called with the entered PIN when 4 digits are complete. */
  onPinEntered: (pin: string) => void;
  /** Called when user dismisses (Escape or X button). */
  onDismiss: () => void;
  /** Title shown above the PIN pad. */
  title?: string;
  /** Error message to display (e.g. "Incorrect PIN"). */
  error?: string;
  /** If true, shows a "Set New PIN" confirmation flow. */
  mode?: 'verify' | 'set';
}

const BUTTONS = [
  ['1', '2', '3'],
  ['4', '5', '6'],
  ['7', '8', '9'],
  ['', '0', '⌫'],
];

export const PinEntryDialog: React.FC<PinEntryDialogProps> = ({
  onPinEntered,
  onDismiss,
  title = 'Enter PIN',
  error,
  mode = 'verify',
}) => {
  const [pin, setPin] = useState('');
  const [shake, setShake] = useState(false);

  const handleDigit = useCallback((digit: string) => {
    if (digit === '⌫') {
      setPin(p => p.slice(0, -1));
      return;
    }
    if (!digit) return;
    setPin(prev => {
      const next = prev + digit;
      if (next.length === 4) {
        // Submit after a short visual delay
        setTimeout(() => onPinEntered(next), 80);
      }
      return next;
    });
  }, [onPinEntered]);

  // Keyboard support
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { onDismiss(); return; }
      if (e.key === 'Backspace') { handleDigit('⌫'); return; }
      if (/^[0-9]$/.test(e.key)) { handleDigit(e.key); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [handleDigit, onDismiss]);

  // Shake animation on error
  useEffect(() => {
    if (error) {
      setPin('');
      setShake(true);
      const t = setTimeout(() => setShake(false), 500);
      return () => clearTimeout(t);
    }
  }, [error]);

  return (
    <div
      className="fixed inset-0 z-[200] flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.85)', backdropFilter: 'blur(6px)' }}
      onClick={e => { if (e.target === e.currentTarget) onDismiss(); }}
    >
      <div
        className="relative rounded-2xl border shadow-2xl p-8 w-72 flex flex-col items-center gap-6"
        style={{
          background: 'var(--bg-card, #1a1a2e)',
          borderColor: 'var(--border-subtle, rgba(255,255,255,0.1))',
        }}
      >
        {/* Close button */}
        <button
          onClick={onDismiss}
          className="absolute top-3 right-3 p-1.5 rounded-lg hover:bg-white/10 transition-colors"
        >
          <X size={16} className="text-white/40" />
        </button>

        {/* Lock icon */}
        <div
          className="w-12 h-12 rounded-full flex items-center justify-center"
          style={{ background: 'var(--accent-surface, rgba(6,182,212,0.15))' }}
        >
          <Lock size={22} style={{ color: 'var(--accent, #06b6d4)' }} />
        </div>

        {/* Title */}
        <p className="text-white font-semibold text-base text-center">{title}</p>

        {/* PIN dots */}
        <div
          className={`flex gap-4 ${shake ? 'animate-[shake_0.4s_ease-in-out]' : ''}`}
        >
          {[0, 1, 2, 3].map(i => (
            <div
              key={i}
              className="w-4 h-4 rounded-full transition-all duration-150"
              style={{
                background: i < pin.length
                  ? 'var(--accent, #06b6d4)'
                  : 'var(--border-subtle, rgba(255,255,255,0.15))',
                boxShadow: i < pin.length
                  ? '0 0 8px var(--accent, #06b6d4)'
                  : 'none',
              }}
            />
          ))}
        </div>

        {/* Error message */}
        {error && (
          <p className="text-red-400 text-xs text-center -mt-3">{error}</p>
        )}

        {/* Numeric pad */}
        <div className="grid grid-cols-3 gap-2 w-full">
          {BUTTONS.flat().map((btn, idx) => (
            <button
              key={idx}
              onClick={() => handleDigit(btn)}
              disabled={!btn || pin.length >= 4}
              className={`
                h-12 rounded-xl text-white font-semibold text-lg transition-all
                ${btn
                  ? 'hover:brightness-125 active:scale-95'
                  : 'invisible'
                }
              `}
              style={{
                background: btn
                  ? 'var(--bg-elevated, rgba(255,255,255,0.07))'
                  : 'transparent',
                border: '1px solid var(--border-subtle, rgba(255,255,255,0.08))',
              }}
            >
              {btn}
            </button>
          ))}
        </div>

        {mode === 'set' && (
          <p className="text-white/30 text-xs text-center">
            Choose a 4-digit PIN to lock this content
          </p>
        )}
      </div>

      {/* Shake keyframe */}
      <style>{`
        @keyframes shake {
          0%, 100% { transform: translateX(0); }
          20% { transform: translateX(-8px); }
          40% { transform: translateX(8px); }
          60% { transform: translateX(-6px); }
          80% { transform: translateX(6px); }
        }
      `}</style>
    </div>
  );
};
