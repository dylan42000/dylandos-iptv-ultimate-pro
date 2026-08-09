// ─── Module-level EPG store ──────────────────────────────────────────────────
// Keeps full XMLTV EPG out of React useState so Guide/Live/App don't retain
// giant program maps in component state. Consumers subscribe via revision.

import type { EPGData, EPGProgram } from '../types/epg';

type Listener = (revision: number) => void;

let epgData: EPGData | null = null;
let revision = 0;
const listeners = new Set<Listener>();

export function getEpgRuntime(): EPGData | null {
  return epgData;
}

export function getEpgRevision(): number {
  return revision;
}

export function setEpgRuntime(data: EPGData | null): void {
  epgData = data;
  revision += 1;
  for (const listener of listeners) {
    try {
      listener(revision);
    } catch {
      /* ignore subscriber errors */
    }
  }
}

export function clearEpgRuntime(): void {
  setEpgRuntime(null);
}

export function getProgramsSlice(channelId: string): EPGProgram[] {
  if (!epgData) return [];
  return epgData.programs[channelId] ?? [];
}

export function subscribeEpgRuntime(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
