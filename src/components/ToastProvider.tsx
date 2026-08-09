// ─── Toast Notification System ───────────────────────────────────────────────

import React, {
  createContext,
  useContext,
  useState,
  useCallback,
  useRef,
} from 'react';
import { CheckCircle2, XCircle, AlertTriangle, Info, X } from 'lucide-react';

type ToastType = 'success' | 'error' | 'warning' | 'info';

interface Toast {
  id: string;
  type: ToastType;
  title?: string;
  message: string;
  duration: number;
  action?: { label: string; onClick: () => void };
}

interface ToastContextValue {
  toast: (
    message: string,
    type?: ToastType,
    options?: Partial<Omit<Toast, 'id' | 'type' | 'message'>>
  ) => string;
  success: (message: string, title?: string) => string;
  error: (message: string, title?: string) => string;
  warning: (message: string, title?: string) => string;
  info: (message: string, title?: string) => string;
  dismiss: (id: string) => void;
  dismissAll: () => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

export const useToast = (): ToastContextValue => {
  const ctx = useContext(ToastContext);
  if (!ctx) throw new Error('useToast must be used within ToastProvider');
  return ctx;
};

const ICONS: Record<ToastType, React.ReactNode> = {
  success: <CheckCircle2 size={16} />,
  error: <XCircle size={16} />,
  warning: <AlertTriangle size={16} />,
  info: <Info size={16} />,
};

const STYLES: Record<ToastType, string> = {
  success: 'border-green-500/40 bg-green-500/10 text-green-400',
  error: 'border-red-500/40 bg-red-500/10 text-red-400',
  warning: 'border-yellow-500/40 bg-yellow-500/10 text-yellow-400',
  info: 'border-cyan-500/40 bg-cyan-500/10 text-cyan-400',
};

export const ToastProvider: React.FC<{
  children: React.ReactNode;
  position?: 'top-right' | 'top-center' | 'bottom-right';
  maxToasts?: number;
}> = ({ children, position = 'top-right', maxToasts = 5 }) => {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const counter = useRef(0);
  const timers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map());

  const dismiss = useCallback((id: string) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
    const timer = timers.current.get(id);
    if (timer) {
      clearTimeout(timer);
      timers.current.delete(id);
    }
  }, []);

  const dismissAll = useCallback(() => {
    timers.current.forEach((t) => clearTimeout(t));
    timers.current.clear();
    setToasts([]);
  }, []);

  const toast = useCallback(
    (
      message: string,
      type: ToastType = 'info',
      options: Partial<Omit<Toast, 'id' | 'type' | 'message'>> = {}
    ): string => {
      const id = `toast_${++counter.current}`;
      const duration = options.duration ?? (type === 'error' ? 6000 : 4000);

      const newToast: Toast = { id, type, message, duration, ...options };

      setToasts((prev) => {
        const next = [...prev, newToast];
        return next.slice(-maxToasts);
      });

      if (duration > 0) {
        const timer = setTimeout(() => dismiss(id), duration);
        timers.current.set(id, timer);
      }

      return id;
    },
    [dismiss, maxToasts]
  );

  const success = useCallback(
    (m: string, title?: string) => toast(m, 'success', { title }),
    [toast]
  );
  const error = useCallback(
    (m: string, title?: string) =>
      toast(m, 'error', { title, duration: 7000 }),
    [toast]
  );
  const warning = useCallback(
    (m: string, title?: string) => toast(m, 'warning', { title }),
    [toast]
  );
  const info = useCallback(
    (m: string, title?: string) => toast(m, 'info', { title }),
    [toast]
  );

  const positionClass = {
    'top-right': 'top-14 right-4',
    'top-center': 'top-14 left-1/2 -translate-x-1/2',
    'bottom-right': 'bottom-20 right-4',
  }[position];

  return (
    <ToastContext.Provider
      value={{ toast, success, error, warning, info, dismiss, dismissAll }}
    >
      {children}
      <div
        className={`fixed z-[9999] flex flex-col gap-2 pointer-events-none ${positionClass}`}
        style={{ minWidth: 280, maxWidth: 380 }}
      >
        {toasts.map((t) => (
          <div
            key={t.id}
            className={`
              flex items-start gap-3 px-4 py-3 rounded-xl border
              backdrop-blur-md shadow-2xl pointer-events-auto
              animate-in
              ${STYLES[t.type]}
            `}
          >
            <span className="shrink-0 mt-0.5">{ICONS[t.type]}</span>
            <div className="flex-1 min-w-0">
              {t.title && (
                <p className="text-white text-sm font-semibold mb-0.5">
                  {t.title}
                </p>
              )}
              <p className="text-white/80 text-sm leading-snug">{t.message}</p>
              {t.action && (
                <button
                  onClick={() => {
                    t.action!.onClick();
                    dismiss(t.id);
                  }}
                  className="mt-2 text-xs font-semibold underline underline-offset-2 hover:opacity-80"
                >
                  {t.action.label}
                </button>
              )}
            </div>
            <button
              onClick={() => dismiss(t.id)}
              className="shrink-0 opacity-50 hover:opacity-100 transition-opacity mt-0.5"
            >
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
};
