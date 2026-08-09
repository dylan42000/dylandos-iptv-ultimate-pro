// hooks/useEpg.ts — React hook that delegates XMLTV parsing to a Web Worker
// Keeps the main thread free while parsing large EPG files

import { useEffect, useRef, useCallback, useState } from 'react';
import { EPGData, EPGProgram, XMLTVSource } from '../types/epg';
import { fetchXmltvSource } from '../services/xmltvEpg';

export function useEpg(sources: XMLTVSource[]) {
  const workerRef = useRef<Worker | null>(null);
  const [epgData, setEpgData] = useState<EPGData>({ channels: {}, programs: {} });
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Spin up worker on mount using Vite's new Worker(URL) pattern (TypeScript-safe)
  useEffect(() => {
    try {
      workerRef.current = new Worker(
        new URL('../workers/epgParser.worker.ts', import.meta.url),
        { type: 'module' }
      );
    } catch {
      console.warn('[useEpg] Worker creation failed — falling back to main-thread parsing');
    }
    return () => {
      workerRef.current?.terminate();
      workerRef.current = null;
    };
  }, []);

  const loadEpg = useCallback(async () => {
    if (!sources || sources.length === 0) return;
    setLoading(true);
    setError(null);

    const mergedChannels: EPGData['channels'] = {};
    const mergedPrograms: EPGData['programs'] = {};

    const enabledSources = sources.filter(s => s.enabled);

    const results = await Promise.allSettled(
      enabledSources.map(async (src) => {
        try {
          const xmlText = await fetchXmltvSource(src);
          if (!xmlText) return null;

          if (workerRef.current) {
            // Parse off-thread
            return new Promise<EPGData>((resolve, reject) => {
              const w = workerRef.current!;
              const sourceId = src.url;

              const handler = (e: MessageEvent) => {
                if (e.data.sourceId !== sourceId) return;
                w.removeEventListener('message', handler);
                if (e.data.type === 'parsed') {
                  resolve(e.data.data);
                } else {
                  reject(new Error(e.data.error || 'Worker parse error'));
                }
              };

              w.addEventListener('message', handler);
              w.postMessage({ type: 'parse', xml: xmlText, sourceId });
            });
          } else {
            // Fallback: parse on main thread (imported dynamically to avoid bundling twice)
            const { parseXmltvXml } = await import('../services/xmltvEpg');
            return parseXmltvXml(xmlText);
          }
        } catch (err: any) {
          console.error(`[useEpg] Failed for ${src.url}:`, err?.message);
          return null;
        }
      })
    );

    for (const result of results) {
      if (result.status !== 'fulfilled' || !result.value) continue;
      const { channels, programs } = result.value;

      for (const [id, ch] of Object.entries(channels)) {
        if (!mergedChannels[id]) mergedChannels[id] = ch;
      }

      for (const [channelId, progs] of Object.entries(programs)) {
        if (!mergedPrograms[channelId]) mergedPrograms[channelId] = [];
        const existingKeys = new Set(
          mergedPrograms[channelId].map((p: EPGProgram) => `${p.startTime}|${p.stopTime}`)
        );
        const newProgs = progs.filter(
          (p: EPGProgram) => !existingKeys.has(`${p.startTime}|${p.stopTime}`)
        );
        mergedPrograms[channelId] = [...mergedPrograms[channelId], ...newProgs].sort(
          (a: EPGProgram, b: EPGProgram) =>
            new Date(a.startTime).getTime() - new Date(b.startTime).getTime()
        );
      }
    }

    setEpgData({ channels: mergedChannels, programs: mergedPrograms });
    setLoading(false);
  }, [sources]);

  return { epgData, loading, error, loadEpg };
}
