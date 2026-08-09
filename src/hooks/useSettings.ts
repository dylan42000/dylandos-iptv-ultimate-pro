// ─── Settings Hook — Auto-sync to main process ─────────────────────────────

import { useState, useCallback, useRef, useEffect } from 'react';
import { AppSettings, DEFAULT_SETTINGS } from '../types/settings';
import { loadSettings, saveSettings } from '../services/storage';

export const useSettings = () => {
  const [settings, setSettings] = useState<AppSettings>(() => loadSettings());
  const settingsRef = useRef(settings);

  useEffect(() => {
    settingsRef.current = settings;
  }, [settings]);

  const updateSettings = useCallback(async (patch: Partial<AppSettings>) => {
    const newSettings = { ...settingsRef.current, ...patch };
    settingsRef.current = newSettings;
    setSettings(newSettings);
    await saveSettings(newSettings);
  }, []);

  const resetSettings = useCallback(async () => {
    settingsRef.current = DEFAULT_SETTINGS;
    setSettings(DEFAULT_SETTINGS);
    await saveSettings(DEFAULT_SETTINGS);
  }, []);

  return { settings, updateSettings, resetSettings };
};
