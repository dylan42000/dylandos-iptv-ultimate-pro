// ─── Channel Number Jump Hook ────────────────────────────────────────────────

import { useState, useRef, useCallback } from 'react';
import { XtreamChannel } from '../types/xtream';

export const useChannelNumberJump = (
  channels: XtreamChannel[],
  onJump: (channel: XtreamChannel) => void
) => {
  const [buffer, setBuffer] = useState('');
  const [isActive, setIsActive] = useState(false);
  const [matchedChannel, setMatchedChannel] = useState<XtreamChannel | null>(
    null
  );
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const commit = useCallback(
    (buf: string) => {
      const num = parseInt(buf, 10);
      const ch = channels.find((c) => Number(c.num) === num);
      if (ch) onJump(ch);
      setBuffer('');
      setIsActive(false);
      setMatchedChannel(null);
    },
    [channels, onJump]
  );

  const handleDigit = useCallback(
    (digit: string) => {
      const newBuf = buffer + digit;
      setBuffer(newBuf);
      setIsActive(true);

      const num = parseInt(newBuf, 10);
      const ch = channels.find((c) => Number(c.num) === num);
      setMatchedChannel(ch ?? null);

      if (timerRef.current) clearTimeout(timerRef.current);

      if (newBuf.length >= 4) {
        timerRef.current = setTimeout(() => commit(newBuf), 200);
        return;
      }

      timerRef.current = setTimeout(() => commit(newBuf), 1500);
    },
    [buffer, channels, commit]
  );

  const cancel = useCallback(() => {
    if (timerRef.current) clearTimeout(timerRef.current);
    setBuffer('');
    setIsActive(false);
    setMatchedChannel(null);
  }, []);

  return { buffer, isActive, matchedChannel, handleDigit, cancel };
};
