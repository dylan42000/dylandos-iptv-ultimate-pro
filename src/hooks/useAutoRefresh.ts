// ─── Auto Refresh Hook ───────────────────────────────────────────────────────
// Schedules periodic EPG and playlist refreshes using setInterval.
// Cleans up on unmount. Both intervals are independent.

import { useEffect, useRef } from 'react';

interface UseAutoRefreshOptions {
  /** Hours between EPG refreshes. 0 = disabled. */
  epgRefreshHours: number;
  /** Hours between playlist refreshes. 0 = disabled. */
  playlistRefreshHours: number;
  /** Called when EPG refresh interval fires. Should not throw. */
  onRefreshEpg: () => Promise<void> | void;
  /** Called when playlist refresh interval fires. Should not throw. */
  onRefreshPlaylists: () => Promise<void> | void;
}

export function useAutoRefresh({
  epgRefreshHours,
  playlistRefreshHours,
  onRefreshEpg,
  onRefreshPlaylists,
}: UseAutoRefreshOptions): void {
  // Use refs to always call the latest callback without restarting intervals
  const epgCbRef = useRef(onRefreshEpg);
  const playlistCbRef = useRef(onRefreshPlaylists);

  useEffect(() => { epgCbRef.current = onRefreshEpg; }, [onRefreshEpg]);
  useEffect(() => { playlistCbRef.current = onRefreshPlaylists; }, [onRefreshPlaylists]);

  useEffect(() => {
    if (epgRefreshHours <= 0) return;
    const ms = epgRefreshHours * 3_600_000;
    const timer = setInterval(async () => {
      try {
        await epgCbRef.current();
      } catch (err) {
        console.warn('[useAutoRefresh] EPG refresh error:', err);
      }
    }, ms);
    return () => clearInterval(timer);
  }, [epgRefreshHours]);

  useEffect(() => {
    if (playlistRefreshHours <= 0) return;
    const ms = playlistRefreshHours * 3_600_000;
    const timer = setInterval(async () => {
      try {
        await playlistCbRef.current();
      } catch (err) {
        console.warn('[useAutoRefresh] Playlist refresh error:', err);
      }
    }, ms);
    return () => clearInterval(timer);
  }, [playlistRefreshHours]);
}
