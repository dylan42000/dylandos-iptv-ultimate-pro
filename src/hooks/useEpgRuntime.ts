import { useEffect, useState } from 'react';
import {
  getEpgRevision,
  getEpgRuntime,
  subscribeEpgRuntime,
} from '../services/epgRuntimeStore';
import type { EPGData } from '../types/epg';

/** Subscribe to module-level EPG without storing the full guide in App state. */
export function useEpgRuntime(): { epgData: EPGData | null; revision: number } {
  const [revision, setRevision] = useState(getEpgRevision);

  useEffect(() => subscribeEpgRuntime(setRevision), []);

  return {
    epgData: getEpgRuntime(),
    revision,
  };
}
