import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { EPGData, EPGProgram } from '../types/epg';
import type { XtreamChannel } from '../types/xtream';
import { buildEpgProgramIndex, getProgramsForChannel as resolveXmltvPrograms } from '../utils/epgMatching';
import {
  fetchShortEpgForStream,
  getCachedShortEpg,
  hydrateShortEpgBatch,
} from '../services/shortEpgCache';

/**
 * Resolves guide programs for a channel:
 * 1) XMLTV match (full guide)
 * 2) Cached Xtream short EPG
 * 3) Triggers short-EPG fetch for visible channels
 */
export function useChannelEpg(
  epgData: EPGData | null,
  channels: XtreamChannel[],
  enabled = true
) {
  const [shortEpgVersion, setShortEpgVersion] = useState(0);
  const hydratedRef = useRef<Set<number>>(new Set());

  const xmltvIndex = useMemo(() => buildEpgProgramIndex(epgData), [epgData]);

  const getProgramsForChannel = useCallback(
    (channel: XtreamChannel): EPGProgram[] => {
      const xmltv = resolveXmltvPrograms(channel, xmltvIndex);
      if (xmltv.length > 0) return xmltv;

      const cached = getCachedShortEpg(channel.stream_id);
      if (cached && cached.length > 0) return cached;

      return cached ?? [];
    },
    [xmltvIndex, shortEpgVersion]
  );

  // Hydrate short EPG for channels that XMLTV didn't match.
  useEffect(() => {
    if (!enabled || channels.length === 0) return;

    let cancelled = false;

    const missing = channels
      .filter((ch) => {
        const xmltv = resolveXmltvPrograms(ch, xmltvIndex);
        if (xmltv.length > 0) return false;
        const cached = getCachedShortEpg(ch.stream_id);
        // Fresh cache hit (including fresh empty) — skip until TTL expires
        if (cached !== null) return false;
        return true;
      })
      .map((ch) => ch.stream_id)
      // Prioritize first ~120 visible/filtered channels for snappy Live TV.
      .slice(0, 120);

    if (missing.length === 0) return;

    void (async () => {
      await hydrateShortEpgBatch(missing, 10, 16);
      if (cancelled) return;
      for (const id of missing) {
        const cached = getCachedShortEpg(id);
        if (cached && cached.length > 0) {
          hydratedRef.current.add(id);
        } else {
          // Allow TTL-based retry for empty results
          hydratedRef.current.delete(id);
        }
      }
      setShortEpgVersion((v) => v + 1);
    })();

    return () => {
      cancelled = true;
    };
  }, [channels, enabled, xmltvIndex]);

  // Eagerly refresh active channel if it still has no listings.
  const ensureChannelEpg = useCallback(async (channel: XtreamChannel) => {
    const xmltv = resolveXmltvPrograms(channel, xmltvIndex);
    if (xmltv.length > 0) return xmltv;
    const programs = await fetchShortEpgForStream(channel.stream_id, 16);
    hydratedRef.current.add(channel.stream_id);
    setShortEpgVersion((v) => v + 1);
    return programs;
  }, [xmltvIndex]);

  const matchStats = useMemo(() => {
    let withGuide = 0;
    let sample = Math.min(channels.length, 80);
    for (let i = 0; i < sample; i++) {
      if (getProgramsForChannel(channels[i]).length > 0) withGuide++;
    }
    return { sample, withGuide };
  }, [channels, getProgramsForChannel]);

  return {
    getProgramsForChannel,
    ensureChannelEpg,
    matchStats,
    shortEpgVersion,
  };
}
