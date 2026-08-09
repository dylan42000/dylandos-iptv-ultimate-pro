// ─── Auto-Play Next Episode Hook ─────────────────────────────────────────────

import { useState, useEffect, useRef, useCallback } from 'react';

interface AutoPlayNextState {
  visible: boolean;
  countdown: number;
  nextTitle: string;
  nextEpisodeInfo: string;
}

export const useAutoPlayNext = (
  position: number,
  duration: number,
  isVod: boolean,
  enabled: boolean,
  countdownSeconds: number,
  onPlayNext: (() => void) | null
) => {
  const [state, setState] = useState<AutoPlayNextState>({
    visible: false,
    countdown: countdownSeconds,
    nextTitle: '',
    nextEpisodeInfo: '',
  });
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  const hasTriggeredRef = useRef(false);
  const countdownRef = useRef(countdownSeconds);

  const cancel = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    setState((prev) => ({ ...prev, visible: false }));
    hasTriggeredRef.current = true;
  }, []);

  const reset = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    hasTriggeredRef.current = false;
    countdownRef.current = countdownSeconds;
    setState({
      visible: false,
      countdown: countdownSeconds,
      nextTitle: '',
      nextEpisodeInfo: '',
    });
  }, [countdownSeconds]);

  const setNextEpisodeInfo = useCallback(
    (title: string, info: string) => {
      setState((prev) => ({ ...prev, nextTitle: title, nextEpisodeInfo: info }));
    },
    []
  );

  const remainingSeconds = duration > 0 ? duration - position : Infinity;
  const shouldShow =
    isVod &&
    enabled &&
    !!onPlayNext &&
    remainingSeconds <= countdownSeconds + 5 &&
    remainingSeconds > 0;

  useEffect(() => {
    if (!shouldShow || hasTriggeredRef.current) return;

    setState((prev) => ({
      ...prev,
      visible: true,
      countdown: countdownSeconds,
    }));
    countdownRef.current = countdownSeconds;

    timerRef.current = setInterval(() => {
      countdownRef.current -= 1;
      setState((prev) => ({ ...prev, countdown: countdownRef.current }));

      if (countdownRef.current <= 0) {
        clearInterval(timerRef.current!);
        hasTriggeredRef.current = true;
        setState((prev) => ({ ...prev, visible: false }));
        onPlayNext?.();
      }
    }, 1000);

    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [shouldShow, countdownSeconds, onPlayNext]);

  return { state, cancel, reset, setNextEpisodeInfo };
};
