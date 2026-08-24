import React, { useState, useCallback, useEffect } from 'react';
import {
  Filter,
  Settings as SettingsIcon,
  Monitor,
  Tv,
  Film,
  Wifi,
  Palette,
  Keyboard,
  Info,
  Subtitles,
  Database,
  ChevronRight,
  RotateCcw,
  Save,
  Cpu,
  List,
  Shield,
  Download,
  Upload,
  Trash2,
  RefreshCw,
  Plus,
  Lock,
  Unlock,
} from 'lucide-react';
import { AppSettings, DEFAULT_SETTINGS } from '../types/settings';
import { useToast } from '../components/ToastProvider';

interface SettingsPageProps {
  settings: AppSettings;
  onUpdateSettings: (partial: Partial<AppSettings>) => void;
  shortcuts?: readonly { key: string; description: string; category: string }[];
  appVersion?: string;
}

type SettingsTab =
  | 'general'
  | 'playback'
  | 'epg'
  | 'categories'
  | 'filter'
  | 'appearance'
  | 'subtitles'
  | 'tmdb'
  | 'system'
  | 'mpv'
  | 'keyboard'
  | 'playlists'
  | 'parental'
  | 'about';

const TABS: { id: SettingsTab; label: string; icon: React.ReactNode }[] = [
  { id: 'general', label: 'General', icon: <Monitor size={16} /> },
  { id: 'playback', label: 'Playback', icon: <Tv size={16} /> },
  { id: 'epg', label: 'EPG Guide', icon: <Database size={16} /> },
  { id: 'categories', label: 'Category Priority', icon: <List size={16} /> },
  { id: 'filter', label: 'Whitelist / Blacklist', icon: <Filter size={16} /> },
  { id: 'appearance', label: 'Appearance', icon: <Palette size={16} /> },
  { id: 'subtitles', label: 'Subtitles', icon: <Subtitles size={16} /> },
  { id: 'tmdb', label: 'TMDB', icon: <Film size={16} /> },
  { id: 'playlists', label: 'Playlists', icon: <List size={16} /> },
  { id: 'parental', label: 'Parental', icon: <Shield size={16} /> },
  { id: 'system', label: 'System', icon: <Wifi size={16} /> },
  { id: 'mpv', label: 'MPV Engine', icon: <Cpu size={16} /> },
  { id: 'keyboard', label: 'Keyboard', icon: <Keyboard size={16} /> },
  { id: 'about', label: 'About', icon: <Info size={16} /> },
];

const Toggle: React.FC<{
  checked: boolean;
  onChange: (val: boolean) => void;
  label: string;
  description?: string;
}> = ({ checked, onChange, label, description }) => (
  <div className="flex items-center justify-between py-3 px-1">
    <div>
      <p className="text-white/80 text-sm">{label}</p>
      {description && <p className="text-white/30 text-xs mt-0.5">{description}</p>}
    </div>
    <button
      onClick={() => onChange(!checked)}
      className={`relative w-10 h-5.5 rounded-full transition-colors ${checked ? 'bg-cyan-500' : 'bg-white/15'
        }`}
    >
      <div
        className={`absolute top-0.5 w-4.5 h-4.5 rounded-full bg-white shadow transition-transform ${checked ? 'translate-x-5' : 'translate-x-0.5'
          }`}
        style={{ width: 18, height: 18, top: 2 }}
      />
    </button>
  </div>
);

const TokenListEditor: React.FC<{
  label: string;
  values: string[];
  onChange: (next: string[]) => void;
  placeholder?: string;
}> = ({ label, values, onChange, placeholder }) => {
  const [draft, setDraft] = useState('');
  const add = () => {
    const token = draft.trim().toUpperCase();
    if (!token) return;
    if (values.map(v => v.toUpperCase()).includes(token)) {
      setDraft('');
      return;
    }
    onChange([...values, token]);
    setDraft('');
  };
  return (
    <div className="py-2 px-1 space-y-2">
      <p className="text-white/70 text-sm">{label}</p>
      <div className="flex flex-wrap gap-1.5">
        {values.map((token) => (
          <button
            key={token}
            onClick={() => onChange(values.filter((v) => v !== token))}
            className="px-2 py-1 rounded-lg bg-cyan-500/15 text-cyan-200 text-xs border border-cyan-500/30 hover:bg-red-500/20 hover:border-red-400/40 hover:text-red-200"
            title="Remove"
          >
            {token} ×
          </button>
        ))}
        {values.length === 0 && (
          <span className="text-white/25 text-xs">None yet</span>
        )}
      </div>
      <div className="flex gap-2">
        <input
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') add(); }}
          placeholder={placeholder ?? 'PREFIX'}
          className="flex-1 px-3 py-2 bg-white/[0.04] border border-white/[0.08] rounded-xl text-white text-sm"
        />
        <button
          onClick={add}
          className="px-3 py-2 rounded-xl bg-cyan-500/20 text-cyan-200 text-sm border border-cyan-500/30"
        >
          Add
        </button>
      </div>
    </div>
  );
};

const NumberInput: React.FC<{
  value: number;
  onChange: (val: number) => void;
  label: string;
  description?: string;
  min?: number;
  max?: number;
  step?: number;
  suffix?: string;
}> = ({ value, onChange, label, description, min, max, step = 1, suffix }) => (
  <div className="flex items-center justify-between py-3 px-1">
    <div>
      <p className="text-white/80 text-sm">{label}</p>
      {description && <p className="text-white/30 text-xs mt-0.5">{description}</p>}
    </div>
    <div className="flex items-center gap-2">
      <input
        type="number"
        value={value}
        onChange={e => onChange(Number(e.target.value))}
        min={min}
        max={max}
        step={step}
        className="w-20 px-2 py-1.5 bg-white/[0.04] border border-white/[0.08] rounded-lg
          text-white text-sm text-right focus:outline-none focus:border-cyan-500/30"
      />
      {suffix && <span className="text-white/30 text-xs">{suffix}</span>}
    </div>
  </div>
);

const SelectInput: React.FC<{
  value: string;
  onChange: (val: string) => void;
  label: string;
  description?: string;
  options: { value: string; label: string }[];
}> = ({ value, onChange, label, description, options }) => (
  <div className="flex items-center justify-between py-3 px-1">
    <div>
      <p className="text-white/80 text-sm">{label}</p>
      {description && <p className="text-white/30 text-xs mt-0.5">{description}</p>}
    </div>
    <select
      value={value}
      onChange={e => onChange(e.target.value)}
      className="px-3 py-1.5 bg-white/[0.04] border border-white/[0.08] rounded-lg
        text-white text-sm focus:outline-none focus:border-cyan-500/30 cursor-pointer"
    >
      {options.map(o => (
        <option key={o.value} value={o.value} className="bg-slate-900 text-white">
          {o.label}
        </option>
      ))}
    </select>
  </div>
);

const TextInput: React.FC<{
  value: string;
  onChange: (val: string) => void;
  label: string;
  description?: string;
  placeholder?: string;
  type?: string;
}> = ({ value, onChange, label, description, placeholder, type = 'text' }) => (
  <div className="flex items-center justify-between py-3 px-1">
    <div className="flex-1 min-w-0 mr-4">
      <p className="text-white/80 text-sm">{label}</p>
      {description && <p className="text-white/30 text-xs mt-0.5">{description}</p>}
    </div>
    <input
      type={type}
      value={value}
      onChange={e => onChange(e.target.value)}
      placeholder={placeholder}
      className="w-56 px-3 py-1.5 bg-white/[0.04] border border-white/[0.08] rounded-lg
        text-white text-sm focus:outline-none focus:border-cyan-500/30"
    />
  </div>
);

const Section: React.FC<{
  title: string;
  children: React.ReactNode;
}> = ({ title, children }) => (
  <div className="mb-6">
    <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-2 px-1">{title}</h3>
    <div className="bg-white/[0.02] border border-white/[0.05] rounded-xl divide-y divide-white/[0.04] px-3">
      {children}
    </div>
  </div>
);

// ── Playlists Tab ─────────────────────────────────────────────────────────────
const PlaylistsTab: React.FC<{
  onSuccess: (msg: string) => void;
  onError: (msg: string) => void;
}> = ({ onSuccess, onError }) => {
  const [sources, setSources] = useState<any[]>([]);
  const [refreshing, setRefreshing] = useState<string | null>(null);

  const reload = useCallback(async () => {
    const { playlistManager } = await import('../services/playlistManager');
    setSources(playlistManager.getSources());
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const handleRefresh = async (id: string) => {
    setRefreshing(id);
    try {
      const { playlistManager } = await import('../services/playlistManager');
      const src = playlistManager.getSources().find(s => s.id === id);
      if (src?.type === 'm3u' && src) {
        await playlistManager.refreshM3USource(src);
        onSuccess('Playlist refreshed');
      } else {
        onSuccess('Xtream/Stalker playlists refresh via reconnect');
      }
      await reload();
    } catch (e: any) {
      onError(`Refresh failed: ${e.message}`);
    } finally {
      setRefreshing(null);
    }
  };

  const handleRemove = async (id: string) => {
    const { playlistManager } = await import('../services/playlistManager');
    playlistManager.removeSource(id);
    await reload();
    onSuccess('Source removed');
  };

  const typeBadge = (type: string) => {
    const colors: Record<string, string> = {
      xtream: 'bg-cyan-500/20 text-cyan-400 border-cyan-500/20',
      m3u: 'bg-purple-500/20 text-purple-400 border-purple-500/20',
      stalker: 'bg-amber-500/20 text-amber-400 border-amber-500/20',
    };
    return (
      <span className={`px-2 py-0.5 rounded-full text-[10px] font-semibold border ${colors[type] ?? 'bg-white/10 text-white/50 border-white/10'}`}>
        {type.toUpperCase()}
      </span>
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <p className="text-white/50 text-sm">{sources.length} playlist source{sources.length !== 1 ? 's' : ''}</p>
      </div>
      {sources.length === 0 ? (
        <div className="text-center py-12 text-white/30 text-sm">
          No playlist sources configured. Add one via the Profile dialog.
        </div>
      ) : (
        <div className="space-y-2">
          {sources.map(src => (
            <div key={src.id}
              className="flex items-center gap-3 px-4 py-3 rounded-xl bg-white/[0.03] border border-white/[0.06]">
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 mb-0.5">
                  <p className="text-white/80 text-sm font-medium truncate">{src.name}</p>
                  {typeBadge(src.type)}
                </div>
                <div className="flex items-center gap-3 text-white/30 text-[11px]">
                  {src.channelCount > 0 && <span>{src.channelCount} channels</span>}
                  {src.lastRefreshedAt && (
                    <span>Refreshed {new Date(src.lastRefreshedAt).toLocaleDateString()}</span>
                  )}
                  {src.error && <span className="text-red-400 truncate">{src.error}</span>}
                </div>
              </div>
              <div className="flex items-center gap-2 shrink-0">
                <button
                  onClick={() => handleRefresh(src.id)}
                  disabled={refreshing === src.id}
                  className="p-1.5 rounded-lg text-white/40 hover:text-cyan-400 hover:bg-cyan-500/10 transition-colors"
                  title="Refresh"
                >
                  <RefreshCw size={14} className={refreshing === src.id ? 'animate-spin' : ''} />
                </button>
                <button
                  onClick={() => handleRemove(src.id)}
                  className="p-1.5 rounded-lg text-white/40 hover:text-red-400 hover:bg-red-500/10 transition-colors"
                  title="Remove"
                >
                  <Trash2 size={14} />
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
};

// ── EPG Sources Section ───────────────────────────────────────────────────────
const EpgSourcesSection: React.FC<{
  settings: AppSettings;
  onUpdate: (partial: Partial<AppSettings>) => void;
  onSuccess: (msg: string) => void;
  onError: (msg: string) => void;
}> = ({ settings, onUpdate, onSuccess, onError }) => {
  const [newUrl, setNewUrl] = useState('');
  const [testing, setTesting] = useState(false);
  const [testResults, setTestResults] = useState<{ url: string; status: 'ok' | 'error'; count?: number; error?: string }[]>([]);
  const [refreshing, setRefreshing] = useState(false);

  const sources: string[] = settings.epgSources || [];

  const addSource = () => {
    const trimmed = newUrl.trim();
    if (!trimmed) return;
    if (sources.includes(trimmed)) { onError('URL already added'); return; }
    onUpdate({ epgSources: [...sources, trimmed] });
    setNewUrl('');
    onSuccess('EPG source added');
  };

  const removeSource = (url: string) => {
    onUpdate({ epgSources: sources.filter(s => s !== url) });
  };

  const testSources = async () => {
    setTesting(true);
    setTestResults([]);
    const results: typeof testResults = [];
    for (const url of sources) {
      try {
        const resp = await fetch(url, { signal: AbortSignal.timeout(10000) });
        if (!resp.ok) { results.push({ url, status: 'error', error: `HTTP ${resp.status}` }); continue; }
        const text = await resp.text();
        const channels = (text.match(/<channel /g) || []).length;
        const programs = (text.match(/<programme /g) || []).length;
        results.push({ url, status: 'ok', count: channels > 0 ? channels : programs });
      } catch (e: any) {
        results.push({ url, status: 'error', error: e.message || 'Network error' });
      }
    }
    setTestResults(results);
    setTesting(false);
    onSuccess(`Tested ${sources.length} source(s)`);
  };

  const refreshNow = async () => {
    setRefreshing(true);
    try {
      const { loadEpgFromAllSources } = await import('../services/xmltvEpg');
      const xmltvSources = sources.map(url => ({ url, enabled: true }));
      await loadEpgFromAllSources(xmltvSources, 0);
      onSuccess('EPG refreshed from all sources');
    } catch (e: any) {
      onError(`EPG refresh failed: ${e.message}`);
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <Section title="EPG Sources">
      <div className="space-y-3">
        <p className="text-white/30 text-xs">
          Add external XMLTV sources (.xml or .xml.gz). The Xtream built-in EPG URL is always loaded.
          If this list is empty, curated public US/UK/CA feeds are used automatically (same as Firestick).
        </p>

        {/* Source list */}
        {sources.length > 0 && (
          <div className="space-y-1.5">
            {sources.map((url, i) => {
              const result = testResults.find(r => r.url === url);
              return (
                <div key={i} className="flex items-center gap-2 px-3 py-2.5 rounded-xl bg-white/[0.03] border border-white/[0.06]">
                  <div className="flex-1 min-w-0">
                    <p className="text-white/70 text-xs font-mono truncate">{url}</p>
                    {result && (
                      <p className={`text-[10px] mt-0.5 ${result.status === 'ok' ? 'text-green-400' : 'text-red-400'}`}>
                        {result.status === 'ok'
                          ? `✓ Reachable — ${result.count ?? 0} ${result.count === 1 ? 'entry' : 'entries'}`
                          : `✗ ${result.error}`}
                      </p>
                    )}
                  </div>
                  <button
                    onClick={() => removeSource(url)}
                    className="p-1 rounded-lg text-white/20 hover:text-red-400 transition-colors shrink-0"
                  >
                    <Trash2 size={13} />
                  </button>
                </div>
              );
            })}
          </div>
        )}

        {sources.length === 0 && (
          <div className="text-center py-5 text-white/20 text-xs">
            No external EPG sources. Xtream built-in EPG is used.
          </div>
        )}

        {/* Add new URL */}
        <div className="flex gap-2">
          <input
            type="url"
            placeholder="https://example.com/epg.xml.gz"
            value={newUrl}
            onChange={e => setNewUrl(e.target.value)}
            onKeyDown={e => e.key === 'Enter' && addSource()}
            className="flex-1 px-3 py-2 bg-white/5 border border-white/10 rounded-xl text-white text-xs placeholder:text-white/20 outline-none focus:border-cyan-500/50 transition-colors font-mono"
          />
          <button
            onClick={addSource}
            disabled={!newUrl.trim()}
            className="px-3 py-2 bg-cyan-500/20 text-cyan-400 border border-cyan-500/30 rounded-xl text-xs font-semibold hover:bg-cyan-500/30 transition-colors disabled:opacity-30"
          >
            <Plus size={14} />
          </button>
        </div>

        {/* Action buttons */}
        {sources.length > 0 && (
          <div className="flex gap-2">
            <button
              onClick={testSources}
              disabled={testing}
              className="flex items-center gap-2 px-4 py-2 bg-white/5 border border-white/10 rounded-xl text-white/60 text-xs hover:text-white hover:bg-white/8 transition-colors disabled:opacity-40"
            >
              <Database size={13} className={testing ? 'animate-pulse' : ''} />
              {testing ? 'Testing…' : 'Test All Sources'}
            </button>
            <button
              onClick={refreshNow}
              disabled={refreshing}
              className="flex items-center gap-2 px-4 py-2 bg-white/5 border border-white/10 rounded-xl text-white/60 text-xs hover:text-white hover:bg-white/8 transition-colors disabled:opacity-40"
            >
              <RefreshCw size={13} className={refreshing ? 'animate-spin' : ''} />
              {refreshing ? 'Refreshing…' : 'Refresh Now'}
            </button>
          </div>
        )}
      </div>
    </Section>
  );
};

// ── Parental Tab ──────────────────────────────────────────────────────────────
const ParentalTab: React.FC<{
  settings: AppSettings;
  onUpdate: (partial: Partial<AppSettings>) => void;
  onSuccess: (msg: string) => void;
  onError: (msg: string) => void;
}> = ({ settings, onUpdate, onSuccess, onError }) => {
  const [lockedCategories, setLockedCategories] = useState<string[]>([]);
  const [pinInput, setPinInput] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [pinMode, setPinMode] = useState<'none' | 'set' | 'change'>('none');
  const [hasPin, setHasPin] = useState(false);

  const reload = useCallback(async () => {
    const { parentalControls } = await import('../services/parentalControls');
    setLockedCategories([...parentalControls.getLockedCategories()]);
    setHasPin(parentalControls.isPinSet());
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const handleSetPin = async () => {
    if (pinInput.length !== 4) { onError('PIN must be 4 digits'); return; }
    if (pinInput !== confirmPin) { onError('PINs do not match'); return; }
    const { parentalControls } = await import('../services/parentalControls');
    await parentalControls.setPin(pinInput);
    setPinInput('');
    setConfirmPin('');
    setPinMode('none');
    setHasPin(true);
    onSuccess('Parental PIN set');
  };

  const handleClearPin = async () => {
    const { parentalControls } = await import('../services/parentalControls');
    parentalControls.clearPin();
    setHasPin(false);
    onSuccess('Parental PIN cleared');
  };

  const handleUnlockCategory = async (cat: string) => {
    const { parentalControls } = await import('../services/parentalControls');
    parentalControls.unlockCategory(cat);
    await reload();
  };

  return (
    <div className="space-y-6">
      {/* Toggles */}
      <div className="space-y-1">
        <div className="flex items-center justify-between py-3 px-1">
          <div>
            <p className="text-white/80 text-sm">Enable Parental Controls</p>
            <p className="text-white/30 text-xs mt-0.5">Require PIN to access locked content</p>
          </div>
          <button
            onClick={() => {
              onUpdate({ parentalControlsEnabled: !settings.parentalControlsEnabled });
              void import('../services/parentalControls').then(({ parentalControls }) => {
                parentalControls.setEnabled(!settings.parentalControlsEnabled);
              });
            }}
            className={`relative w-10 rounded-full transition-colors ${settings.parentalControlsEnabled ? 'bg-cyan-500' : 'bg-white/15'}`}
            style={{ height: 22 }}
          >
            <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform`}
              style={{ width: 18, height: 18, top: 2, transform: settings.parentalControlsEnabled ? 'translateX(20px)' : 'translateX(2px)' }} />
          </button>
        </div>
        <div className="flex items-center justify-between py-3 px-1">
          <div>
            <p className="text-white/80 text-sm">Auto-lock Adult Categories</p>
            <p className="text-white/30 text-xs mt-0.5">Automatically lock categories containing adult keywords</p>
          </div>
          <button
            onClick={() => onUpdate({ parentalAutoLockAdult: !settings.parentalAutoLockAdult })}
            className={`relative w-10 rounded-full transition-colors ${settings.parentalAutoLockAdult ? 'bg-cyan-500' : 'bg-white/15'}`}
            style={{ height: 22 }}
          >
            <div className="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow transition-transform"
              style={{ width: 18, height: 18, top: 2, transform: settings.parentalAutoLockAdult ? 'translateX(20px)' : 'translateX(2px)' }} />
          </button>
        </div>
      </div>

      {/* PIN management */}
      <div>
        <p className="text-white/40 text-[10px] font-semibold uppercase tracking-wider mb-3">PIN</p>
        {pinMode === 'none' ? (
          <div className="flex gap-3">
            <button
              onClick={() => setPinMode(hasPin ? 'change' : 'set')}
              className="flex items-center gap-2 px-4 py-2 bg-cyan-500/10 border border-cyan-500/20
                text-cyan-400 text-sm rounded-xl hover:bg-cyan-500/20 transition-colors"
            >
              <Lock size={14} /> {hasPin ? 'Change PIN' : 'Set PIN'}
            </button>
            {hasPin && (
              <button
                onClick={handleClearPin}
                className="flex items-center gap-2 px-4 py-2 bg-red-500/10 border border-red-500/20
                  text-red-400 text-sm rounded-xl hover:bg-red-500/20 transition-colors"
              >
                <Unlock size={14} /> Clear PIN
              </button>
            )}
          </div>
        ) : (
          <div className="space-y-3 max-w-xs">
            <input
              type="password"
              value={pinInput}
              onChange={e => setPinInput(e.target.value.replace(/\D/g, '').slice(0, 4))}
              placeholder="New 4-digit PIN"
              maxLength={4}
              className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm
                placeholder-white/20 focus:outline-none focus:border-cyan-500/40"
            />
            <input
              type="password"
              value={confirmPin}
              onChange={e => setConfirmPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
              placeholder="Confirm PIN"
              maxLength={4}
              className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm
                placeholder-white/20 focus:outline-none focus:border-cyan-500/40"
            />
            <div className="flex gap-2">
              <button onClick={handleSetPin}
                className="px-4 py-2 bg-cyan-500 text-black text-sm font-semibold rounded-xl hover:bg-cyan-400 transition-colors">
                Save
              </button>
              <button onClick={() => { setPinMode('none'); setPinInput(''); setConfirmPin(''); }}
                className="px-4 py-2 bg-white/5 text-white/50 text-sm rounded-xl hover:bg-white/10 transition-colors">
                Cancel
              </button>
            </div>
          </div>
        )}
      </div>

      {/* Locked categories */}
      {lockedCategories.length > 0 && (
        <div>
          <p className="text-white/40 text-[10px] font-semibold uppercase tracking-wider mb-3">
            Locked Categories ({lockedCategories.length})
          </p>
          <div className="space-y-2">
            {lockedCategories.map(cat => (
              <div key={cat} className="flex items-center justify-between px-3 py-2
                rounded-xl bg-white/[0.03] border border-white/[0.06]">
                <div className="flex items-center gap-2">
                  <Lock size={12} className="text-red-400" />
                  <span className="text-white/70 text-sm">{cat}</span>
                </div>
                <button
                  onClick={() => handleUnlockCategory(cat)}
                  className="flex items-center gap-1 px-2 py-1 text-white/40 text-xs
                    hover:text-cyan-400 hover:bg-cyan-500/10 rounded-lg transition-colors"
                >
                  <Unlock size={11} /> Unlock
                </button>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
};

// ── Keyboard Tab ──────────────────────────────────────────────────────────────
const KeyboardTab: React.FC<{
  shortcuts: readonly { key: string; description: string; category: string }[];
  onSuccess: (msg: string) => void;
  onError: (msg: string) => void;
}> = ({ shortcuts, onSuccess }) => {
  const [bindings, setBindings] = useState<any[]>([]);
  const [remapping, setRemapping] = useState<string | null>(null);
  const [conflict, setConflict] = useState<string | null>(null);

  const reload = useCallback(async () => {
    const { keyboardMapper } = await import('../services/keyboardMapper');
    keyboardMapper.load();
    setBindings([...keyboardMapper.getBindings()]);
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  useEffect(() => {
    if (!remapping) return;
    const handler = (e: KeyboardEvent) => {
      e.preventDefault();
      e.stopPropagation();
      if (e.key === 'Escape') { setRemapping(null); setConflict(null); return; }
      const key = e.key;
      void (async () => {
        const { keyboardMapper } = await import('../services/keyboardMapper');
        const c = keyboardMapper.getConflict(key, remapping as any);
        if (c) {
          setConflict(`Conflicts with "${c}"`);
          return;
        }
        keyboardMapper.setKey(remapping as any, key);
        keyboardMapper.save();
        setRemapping(null);
        setConflict(null);
        await reload();
        onSuccess(`Remapped to ${key}`);
      })();
    };
    window.addEventListener('keydown', handler, true);
    return () => window.removeEventListener('keydown', handler, true);
  }, [remapping, reload, onSuccess]);

  const handleReset = async () => {
    const { keyboardMapper } = await import('../services/keyboardMapper');
    keyboardMapper.resetAll();
    keyboardMapper.save();
    await reload();
    onSuccess('Keyboard shortcuts reset to defaults');
  };

  // Fallback to prop shortcuts if keyboardMapper not loaded yet
  const displayBindings = bindings.length > 0 ? bindings : shortcuts.map(s => ({
    action: s.description,
    label: s.description,
    category: s.category,
    defaultKey: s.key,
    userKey: null,
  }));

  const categories = [...new Set(displayBindings.map((b: any) => b.category))];

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between mb-2">
        <p className="text-white/40 text-xs">{remapping ? 'Press any key to remap...' : 'Click Remap to change a shortcut'}</p>
        <button
          onClick={handleReset}
          className="flex items-center gap-1.5 px-3 py-1.5 text-white/40 text-xs hover:text-white/70
            hover:bg-white/5 rounded-lg transition-colors"
        >
          <RotateCcw size={12} /> Reset All
        </button>
      </div>
      {conflict && (
        <div className="px-3 py-2 rounded-xl bg-red-500/10 border border-red-500/20 text-red-400 text-xs">
          {conflict} — press a different key or Escape to cancel
        </div>
      )}
      {categories.map(cat => {
        const catBindings = displayBindings.filter((b: any) => b.category === cat);
        return (
          <div key={cat}>
            <p className="text-white/40 text-[10px] font-semibold uppercase tracking-wider py-2">{cat}</p>
            {catBindings.map((b: any) => (
              <div key={b.action} className="flex items-center justify-between py-2 px-1">
                <span className="text-white/70 text-sm">{b.label || b.description}</span>
                <div className="flex items-center gap-2">
                  <kbd className={`px-2 py-1 rounded text-xs font-mono transition-colors ${
                    remapping === b.action
                      ? 'bg-cyan-500/20 border border-cyan-500/40 text-cyan-300 animate-pulse'
                      : 'bg-white/[0.06] border border-white/10 text-white/50'
                  }`}>
                    {b.userKey || b.defaultKey || b.key || '—'}
                  </kbd>
                  <button
                    onClick={() => { setRemapping(b.action); setConflict(null); }}
                    className="px-2 py-1 text-[10px] text-white/30 hover:text-cyan-400 hover:bg-cyan-500/10
                      rounded-lg transition-colors border border-white/5"
                  >
                    Remap
                  </button>
                </div>
              </div>
            ))}
          </div>
        );
      })}
    </div>
  );
};

export const SettingsPage: React.FC<SettingsPageProps> = ({
  settings,
  onUpdateSettings,
  shortcuts = [],
  appVersion = '1.0.0',
}) => {
  const [activeTab, setActiveTab] = useState<SettingsTab>('general');
  const { success, error: toastError } = useToast();
  const localSettings = settings;
  const setLocalSettings = useCallback((updater: (prev: AppSettings) => AppSettings) => {
    onUpdateSettings(updater(localSettings));
  }, [localSettings, onUpdateSettings]);

  const update = useCallback(
    (partial: Partial<AppSettings>) => {
      onUpdateSettings(partial);
    },
    [onUpdateSettings]
  );

  const resetToDefaults = useCallback(() => {
    onUpdateSettings(DEFAULT_SETTINGS);
    success('Settings reset to defaults');
  }, [onUpdateSettings, success]);

  const renderContent = () => {
    switch (activeTab) {
      case 'general':
        return (
          <>
            <Section title="Window">
              <Toggle
                checked={settings.startFullscreen}
                onChange={v => update({ startFullscreen: v })}
                label="Start in fullscreen"
              />
              <Toggle
                checked={settings.minimizeToTray}
                onChange={v => update({ minimizeToTray: v })}
                label="Minimize to system tray"
              />
              <Toggle
                checked={settings.startWithWindows}
                onChange={v => update({ startWithWindows: v })}
                label="Start with Windows"
              />
              <Toggle
                checked={settings.confirmOnExit}
                onChange={v => update({ confirmOnExit: v })}
                label="Confirm before exit"
              />
              <Toggle
                checked={settings.opaqueWindow === true}
                onChange={v => update({ opaqueWindow: v })}
                label="Opaque window (MPV reliability)"
                description="Solid black window instead of transparent HWND embed. Restart required after changing. Env DYLANDOS_OPAQUE_WINDOW=1 also works."
              />
            </Section>
            <Section title="OSD">
              <NumberInput
                value={settings.osdTimeoutMs}
                onChange={v => update({ osdTimeoutMs: v })}
                label="OSD timeout"
                suffix="ms"
                min={1000}
                max={30000}
                step={500}
              />
            </Section>
          </>
        );

      case 'playback':
        return (
          <>
            <Section title="Live Streams">
              <SelectInput
                value={settings.liveFormat}
                onChange={v => update({ liveFormat: v as 'ts' | 'm3u8' })}
                label="Stream format"
                options={[
                  { value: 'm3u8', label: 'HLS (.m3u8)' },
                  { value: 'ts', label: 'MPEG-TS (.ts)' },
                ]}
              />
              <NumberInput
                value={settings.liveReconnectMaxAttempts}
                onChange={v => update({ liveReconnectMaxAttempts: v })}
                label="Max reconnect attempts"
                min={1}
                max={20}
              />
              <NumberInput
                value={settings.liveReconnectBaseDelayMs}
                onChange={v => update({ liveReconnectBaseDelayMs: v })}
                label="Reconnect base delay"
                suffix="ms"
                min={500}
                max={10000}
                step={500}
              />
            </Section>
            <Section title="VOD">
              <Toggle
                checked={settings.vodAutoPlayNext}
                onChange={v => update({ vodAutoPlayNext: v })}
                label="Auto-play next episode"
              />
              <NumberInput
                value={settings.vodAutoPlayCountdown}
                onChange={v => update({ vodAutoPlayCountdown: v })}
                label="Countdown duration"
                suffix="sec"
                min={3}
                max={30}
              />
              <Toggle
                checked={settings.vodResumePlayback}
                onChange={v => update({ vodResumePlayback: v })}
                label="Resume playback"
                description="Continue from where you left off"
              />
            </Section>
            <Section title="Performance & Player Engine">
              <SelectInput
                value={settings.preferredEngine || 'mpv'}
                onChange={v => update({ preferredEngine: v as 'mpv' | 'hlsjs' })}
                label="Video Player Engine"
                description="Engine used to render live and on-demand video streams"
                options={[
                  { value: 'mpv', label: 'MPV Native Engine (DirectX 11 GPU, Timeshift & Hardware Decode) - Recommended' },
                  { value: 'hlsjs', label: 'Built-in HTML5 / HLS.js Engine (Standard Web Media Engine)' },
                ]}
              />
              <SelectInput
                value={settings.hardwareAcceleration ? 'on' : 'off'}
                onChange={v => update({ hardwareAcceleration: v === 'on' })}
                label="Hardware acceleration"
                description="Use GPU for rendering"
                options={[
                  { value: 'on', label: 'Enabled' },
                  { value: 'off', label: 'Disabled' },
                ]}
              />
              <Toggle
                checked={settings.hardwareDecode}
                onChange={v => update({ hardwareDecode: v })}
                label="Hardware decoding"
                description="Use GPU for video decoding"
              />
              <SelectInput
                value={settings.liveBufferPreset}
                onChange={v => update({ liveBufferPreset: v as 'low' | 'medium' | 'high' })}
                label="Live buffer preset"
                description="Higher = smoother but more latency"
                options={[
                  { value: 'low', label: 'Low Latency' },
                  { value: 'medium', label: 'Balanced' },
                  { value: 'high', label: 'High Buffer' },
                ]}
              />
              <Toggle
                checked={settings.showStreamHealthOverlay}
                onChange={v => update({ showStreamHealthOverlay: v })}
                label="Stream health overlay"
                description="Show buffer and quality indicator"
              />
              <NumberInput
                value={settings.audioOffsetMs}
                onChange={v => update({ audioOffsetMs: v })}
                label="Audio offset"
                description="Positive = audio ahead"
                suffix="ms"
                min={-5000}
                max={5000}
                step={50}
              />
            </Section>
            <Section title="Live Timeshift (Pause / Rewind Live TV)">
              <Toggle
                checked={settings.liveTimeshiftEnabled !== false}
                onChange={v => update({ liveTimeshiftEnabled: v })}
                label="Enable Live Timeshift Buffer"
                description="Allows pausing, rewinding, and scrubbing live television streams seamlessly"
              />
              <SelectInput
                value={settings.liveTimeshiftBufferSize || 'large'}
                onChange={v => update({ liveTimeshiftBufferSize: v as 'standard' | 'large' | 'max' | 'ultra' })}
                label="Timeshift Buffer Size"
                description="Amount of RAM dedicated to backward live scrub buffer"
                options={[
                  { value: 'standard', label: 'Standard (256 MB / ~10 minutes)' },
                  { value: 'large', label: 'Large (512 MB / ~30 minutes) - Recommended' },
                  { value: 'max', label: 'Maximum (1024 MB / ~1 hour)' },
                  { value: 'ultra', label: 'Ultra (2048 MB / ~2 hours)' },
                ]}
              />
            </Section>
            <Section title="DVR & Recording Engine">
              <div className="py-2">
                <div className="flex items-center justify-between mb-1.5">
                  <div>
                    <p className="text-white/80 text-sm font-medium">Default DVR Recording Folder</p>
                    <p className="text-white/30 text-xs mt-0.5">Location where recorded TV streams are saved</p>
                  </div>
                  <button
                    onClick={async () => {
                      try {
                        const result = await window.electronAPI?.invoke?.('dialog:show-open', {
                          properties: ['openDirectory', 'createDirectory'],
                          title: 'Select Default DVR Recording Folder',
                        });
                        if (result && !result.canceled && Array.isArray(result.filePaths) && result.filePaths.length > 0) {
                          const dir = result.filePaths[0];
                          update({ dvrOutputDir: dir });
                          await window.electronAPI?.invoke?.('dvr:set-output-dir', dir);
                          success('DVR recording folder updated');
                        }
                      } catch {}
                    }}
                    className="px-3 py-1.5 bg-white/10 hover:bg-white/20 text-white text-xs font-semibold rounded-lg transition-colors"
                  >
                    Browse...
                  </button>
                </div>
                <input
                  type="text"
                  value={settings.dvrOutputDir || ''}
                  onChange={e => update({ dvrOutputDir: e.target.value })}
                  placeholder="Default: Videos\DYLANDOS IPTV DVR"
                  className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-3 py-2 text-sm text-white placeholder:text-white/25 focus:outline-none focus:border-cyan-500/40"
                />
              </div>
              <SelectInput
                value={settings.dvrFormat || 'ts'}
                onChange={v => update({ dvrFormat: v as 'ts' | 'mkv' | 'mp4' })}
                label="Recording File Format"
                description="MPEG-TS is the safest format against stream interruptions"
                options={[
                  { value: 'ts', label: 'MPEG-TS (.ts) - Best reliability' },
                  { value: 'mkv', label: 'Matroska (.mkv)' },
                  { value: 'mp4', label: 'MP4 (.mp4 - Fragmented)' },
                ]}
              />
              <NumberInput
                value={settings.dvrMaxConcurrent || 3}
                onChange={v => update({ dvrMaxConcurrent: Math.max(1, Math.min(10, v)) })}
                label="Max Concurrent Recordings"
                description="Simultaneous stream recordings allowed"
                min={1}
                max={10}
              />
              <TextInput
                value={settings.liveUserAgent || 'IPTVSmartersPro'}
                onChange={v => update({ liveUserAgent: v })}
                label="IPTV User-Agent"
                description="User-Agent sent to IPTV server to prevent anti-restream disconnection (default: IPTVSmartersPro)"
                placeholder="IPTVSmartersPro"
              />
            </Section>
          </>
        );

      case 'epg':
        return (
          <>
            <Section title="EPG Settings">
              <Toggle
                checked={settings.epgEnabled}
                onChange={v => update({ epgEnabled: v })}
                label="Enable EPG"
                description="Electronic Program Guide"
              />
              <NumberInput
                value={settings.epgRefreshHours}
                onChange={v => update({ epgRefreshHours: v })}
                label="Refresh interval"
                suffix="hours"
                min={1}
                max={72}
              />
              <NumberInput
                value={settings.epgDaysToLoad || 7}
                onChange={v => update({ epgDaysToLoad: Math.max(1, Math.min(14, v)) })}
                label="Days to load"
                description="How many days of guide data to display"
                suffix="days"
                min={1}
                max={14}
              />
              <SelectInput
                value={settings.epgTimeFormat}
                onChange={v => update({ epgTimeFormat: v as '12h' | '24h' })}
                label="Time format"
                options={[
                  { value: '24h', label: '24-hour' },
                  { value: '12h', label: '12-hour' },
                ]}
              />
              <Toggle
                checked={settings.epgUsePublicFallbacks ?? false}
                onChange={v => update({ epgUsePublicFallbacks: v })}
                label="Use public XMLTV fallbacks"
                description="Optional US/UK/CA mega-guides. Live/Guide already use Xtream short EPG when XMLTV misses."
              />
            </Section>

            {/* EPG Sources */}
            <EpgSourcesSection settings={settings} onUpdate={update} onSuccess={success} onError={toastError} />
          </>
        );

      case 'categories':
        return (
          <Section title="Category Priority (pin to top)">
            <p className="text-white/40 text-xs px-1 mb-2">
              Region prefixes listed first appear at the top of Live / Movies / Series category rails
              (e.g. US, EN, AM for United States users).
            </p>
            <TokenListEditor
              label="Priority prefixes"
              values={settings.categoryPriorityPrefixes ?? []}
              onChange={(categoryPriorityPrefixes) => update({ categoryPriorityPrefixes })}
              placeholder="US"
            />
          </Section>
        );

      case 'filter':
        return (
          <>
            <Section title="Content Filter">
              <Toggle
                checked={settings.contentFilterEnabled ?? false}
                onChange={v => update({ contentFilterEnabled: v })}
                label="Enable whitelist / blacklist"
                description="Filter channels, movies, series, and categories by name prefix (US|, UK:, EN|, etc.)"
              />
              <SelectInput
                value={settings.contentFilterMode ?? 'whitelist'}
                onChange={v => update({ contentFilterMode: v as 'whitelist' | 'blacklist' })}
                label="Filter mode"
                options={[
                  { value: 'whitelist', label: 'Whitelist (only show these)' },
                  { value: 'blacklist', label: 'Blacklist (hide these)' },
                ]}
              />
              <Toggle
                checked={settings.contentFilterShowUntagged ?? true}
                onChange={v => update({ contentFilterShowUntagged: v })}
                label="Show untagged items"
                description="Keep titles/categories with no detectable region prefix"
              />
            </Section>
            <Section title="Allowed prefixes (whitelist)">
              <TokenListEditor
                label="Allow"
                values={settings.contentFilterAllowed ?? []}
                onChange={(contentFilterAllowed) => update({ contentFilterAllowed })}
                placeholder="US"
              />
            </Section>
            <Section title="Blocked prefixes">
              <TokenListEditor
                label="Block"
                values={settings.contentFilterBlocked ?? []}
                onChange={(contentFilterBlocked) => update({ contentFilterBlocked })}
                placeholder="XXX"
              />
            </Section>
          </>
        );

      case 'appearance':
        return (
          <>
            <Section title="Theme">
              <div className="py-4 px-1 space-y-3">
                <p className="text-white/80 text-sm">Neon Theme</p>
                <p className="text-white/30 text-xs -mt-2">Choose your neon color scheme</p>
                <div className="grid grid-cols-3 gap-3 mt-3">
                  {([
                    { value: 'neon-cyan', label: 'Neon Cyan', color: '#06b6d4', glow: 'rgba(6, 182, 212, 0.3)' },
                    { value: 'neon-purple', label: 'Neon Purple', color: '#a855f7', glow: 'rgba(168, 85, 247, 0.3)' },
                    { value: 'neon-green', label: 'Neon Green', color: '#22c55e', glow: 'rgba(34, 197, 94, 0.3)' },
                  ] as const).map(t => (
                    <button
                      key={t.value}
                      onClick={() => update({ theme: t.value, accentColor: t.color })}
                      className={`relative flex flex-col items-center gap-2 p-4 rounded-xl border transition-all ${settings.theme === t.value
                        ? 'border-opacity-60'
                        : 'border-white/[0.08] bg-white/[0.02] hover:bg-white/[0.04]'
                        }`}
                      style={settings.theme === t.value ? {
                        borderColor: t.color,
                        background: `${t.glow.replace('0.3', '0.08')}`,
                        boxShadow: `0 0 20px ${t.glow.replace('0.3', '0.15')}, 0 0 60px ${t.glow.replace('0.3', '0.05')}`,
                      } : {}}
                    >
                      <div className="w-10 h-10 rounded-full" style={{
                        background: `linear-gradient(135deg, ${t.color}, ${t.color}dd)`,
                        boxShadow: settings.theme === t.value ? `0 0 15px ${t.glow}` : 'none',
                      }} />
                      <span className="text-xs font-semibold" style={{
                        color: settings.theme === t.value ? t.color : 'rgba(255,255,255,0.5)',
                      }}>{t.label}</span>
                      {settings.theme === t.value && (
                        <div className="absolute top-2 right-2 w-2 h-2 rounded-full" style={{ background: t.color }} />
                      )}
                    </button>
                  ))}
                </div>
                
                <div className="h-px bg-white/[0.06] my-4" />
                
                <p className="text-white/80 text-sm">🔥 DYLANDOS EXTREME EDITION</p>
                <p className="text-white/30 text-xs -mt-2">Wild ultra neon themes</p>
                <div className="grid grid-cols-2 gap-3 mt-3">
                  {([
                    { value: 'dylandos-ultra', label: 'Dylandos Ultra', color: '#ffeb00', glow: 'rgba(255, 235, 0, 0.4)' },
                    { value: 'dylandos-fire', label: 'Dylandos Fire', color: '#ff6b00', glow: 'rgba(255, 107, 0, 0.4)' },
                  ] as const).map(t => (
                    <button
                      key={t.value}
                      onClick={() => update({ theme: t.value, accentColor: t.color })}
                      className={`relative flex flex-col items-center gap-2 p-4 rounded-xl border transition-all ${settings.theme === t.value
                        ? 'border-opacity-80'
                        : 'border-white/[0.08] bg-white/[0.02] hover:bg-white/[0.04]'
                        }`}
                      style={settings.theme === t.value ? {
                        borderColor: t.color,
                        background: `${t.glow.replace('0.4', '0.1')}`,
                        boxShadow: `0 0 25px ${t.glow.replace('0.4', '0.2')}, 0 0 70px ${t.glow.replace('0.4', '0.08')}`,
                      } : {}}
                    >
                      <div className="w-10 h-10 rounded-full" style={{
                        background: `linear-gradient(135deg, ${t.color}, ${t.color}dd)`,
                        boxShadow: settings.theme === t.value ? `0 0 20px ${t.glow}` : 'none',
                      }} />
                      <span className="text-xs font-semibold" style={{
                        color: settings.theme === t.value ? t.color : 'rgba(255,255,255,0.5)',
                      }}>{t.label}</span>
                      {settings.theme === t.value && (
                        <div className="absolute top-2 right-2 w-2.5 h-2.5 rounded-full animate-pulse" style={{ background: t.color }} />
                      )}
                    </button>
                  ))}
                </div>
              </div>
            </Section>
            <Section title="Sidebar">
              <Toggle
                checked={settings.sidebarCollapsed}
                onChange={v => update({ sidebarCollapsed: v })}
                label="Collapse sidebar by default"
              />
            </Section>
            <Section title="Movies & Series">
              <SelectInput
                value={settings.mediaPosterSize ?? 'large'}
                onChange={v => update({ mediaPosterSize: v as AppSettings['mediaPosterSize'] })}
                label="Poster size"
                description="Larger cards reduce each row for easier couch viewing"
                options={[
                  { value: 'standard', label: 'Standard' },
                  { value: 'large', label: 'Large' },
                  { value: 'extra-large', label: 'Extra Large' },
                ]}
              />
            </Section>
          </>
        );

      case 'subtitles':
        return (
          <>
            <Section title="Subtitle Display">
              <NumberInput
                value={settings.subtitleFontSize}
                onChange={v => update({ subtitleFontSize: v })}
                label="Font size"
                suffix="px"
                min={12}
                max={72}
              />
              <TextInput
                value={settings.subtitleFontColor}
                onChange={v => update({ subtitleFontColor: v })}
                label="Font color"
                placeholder="#ffffff"
              />
              <NumberInput
                value={settings.subtitleOutlineWidth}
                onChange={v => update({ subtitleOutlineWidth: Math.max(0, Math.min(8, v)) })}
                label="Text outline"
                description="0 disables the outline"
                suffix="px"
                min={0}
                max={8}
              />
              <NumberInput
                value={Math.round(settings.subtitleBackgroundOpacity * 100)}
                onChange={v => update({ subtitleBackgroundOpacity: Math.max(0, Math.min(100, v)) / 100 })}
                label="Background opacity"
                description="Subtitle background transparency"
                suffix="%"
                min={0}
                max={100}
                step={5}
              />
            </Section>
            <Section title="Preview">
              <div className="py-4 flex justify-center">
                <div className="relative rounded-xl overflow-hidden bg-black/60 w-full max-w-md aspect-video flex items-end justify-center pb-6">
                  <div className="absolute inset-0 bg-gradient-to-b from-white/5 to-transparent" />
                  <span
                    className="relative z-10 px-3 py-1 rounded font-medium"
                    style={{
                      fontSize: settings.subtitleFontSize,
                      color: settings.subtitleFontColor,
                      backgroundColor: `rgba(0, 0, 0, ${settings.subtitleBackgroundOpacity})`,
                      textShadow: settings.subtitleOutlineWidth > 0
                        ? `${settings.subtitleOutlineWidth}px ${settings.subtitleOutlineWidth}px ${settings.subtitleOutlineWidth * 2}px rgba(0,0,0,0.8)`
                        : 'none',
                    }}
                  >
                    Sample subtitle text
                  </span>
                </div>
              </div>
            </Section>
          </>
        );

      case 'tmdb':
        return (
          <>
            <Section title="TMDB Integration">
              <Toggle
                checked={settings.tmdbEnabled}
                onChange={v => update({ tmdbEnabled: v })}
                label="Enable TMDB enrichment"
                description="Fetch movie metadata, posters, and backdrops"
              />
              <TextInput
                value={settings.tmdbApiKey}
                onChange={v => update({ tmdbApiKey: v })}
                label="TMDB API Key"
                placeholder="Enter your TMDB API key"
              />
              <TextInput
                value={settings.tmdbLanguage}
                onChange={v => update({ tmdbLanguage: v })}
                label="Language"
                placeholder="en-US"
              />
            </Section>
          </>
        );

      case 'system':
        return (
          <>
            <Section title="Performance">
              <NumberInput
                value={settings.maxConcurrentStreams || 1}
                onChange={v => update({ maxConcurrentStreams: v })}
                label="Max concurrent streams"
                min={1}
                max={4}
              />
              <Toggle
                checked={settings.liveTimeshiftEnabled !== false}
                onChange={v => update({ liveTimeshiftEnabled: v })}
                label="Pause-live timeshift buffer"
                description="Keep a scrubbable MPV demuxer back-buffer when pausing live TV"
              />
              <Toggle
                checked={settings.fieldTelemetryEnabled !== false}
                onChange={v => update({ fieldTelemetryEnabled: v })}
                label="Field telemetry (local)"
                description="Anonymized crash + playback failure codes saved locally — no credentials"
              />
            </Section>
            <Section title="Backup & Restore">
              <div className="flex gap-3 py-2">
                <button
                  onClick={async () => {
                    try {
                      const { exportBackup } = await import('../services/cloudSync');
                      await exportBackup();
                      success('Backup exported successfully');
                    } catch (e: any) {
                      toastError(`Export failed: ${e.message}`);
                    }
                  }}
                  className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5
                    bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 text-sm rounded-xl
                    hover:bg-cyan-500/20 transition-colors"
                >
                  <Upload size={14} /> Export Backup
                </button>
                <button
                  onClick={async () => {
                    try {
                      const { importBackup } = await import('../services/cloudSync');
                      const result = await importBackup();
                      if (result.success) {
                        const profileCount = result.payload?.profiles?.length ?? 0;
                        const sourceCount = result.payload?.playlists?.length ?? 0;
                        success(`Backup restored: ${profileCount} profiles, ${sourceCount} sources`);
                      } else if (result.error) {
                        toastError(`Import failed: ${result.error}`);
                      }
                    } catch (e: any) {
                      toastError(`Import failed: ${e.message}`);
                    }
                  }}
                  className="flex-1 flex items-center justify-center gap-2 px-4 py-2.5
                    bg-white/5 border border-white/10 text-white/60 text-sm rounded-xl
                    hover:bg-white/10 hover:text-white/80 transition-colors"
                >
                  <Download size={14} /> Import Backup
                </button>
              </div>
            </Section>
            <Section title="Data">
              <button
                onClick={() => {
                  localStorage.clear();
                  success('Cache cleared');
                }}
                className="w-full py-3 px-1 text-left text-red-400 text-sm hover:text-red-300 transition-colors"
              >
                Clear all cached data
              </button>
            </Section>
          </>
        );

      case 'playlists':
        return <PlaylistsTab onSuccess={success} onError={toastError} />;

      case 'parental':
        return <ParentalTab settings={settings} onUpdate={update} onSuccess={success} onError={toastError} />;

      case 'mpv':
        return (
          <>
            <Section title="Video Engine">
              <SelectInput
                value={localSettings.preferredEngine ?? 'mpv'}
                onChange={v => setLocalSettings(s => ({ ...s, preferredEngine: v as 'mpv' | 'hlsjs' }))}
                label="Preferred Engine"
                description="MPV is primary for Live TV and Movies/Series. HLS.js is the automatic fallback if MPV fails."
                options={[
                  { value: 'mpv', label: 'MPV (Recommended)' },
                  { value: 'hlsjs', label: 'HLS.js only' },
                ]}
              />
              <SelectInput
                value={localSettings.mpvHwdecMode ?? 'auto-safe'}
                onChange={v => setLocalSettings(s => ({ ...s, mpvHwdecMode: v as AppSettings['mpvHwdecMode'] }))}
                label="Hardware Decode Mode"
                description="auto-safe uses D3D11VA with fallback. auto is aggressive. Software disables HW decode."
                options={[
                  { value: 'auto-safe', label: 'Auto Safe (D3D11VA)' },
                  { value: 'auto', label: 'Auto (Aggressive)' },
                  { value: 'no', label: 'Software Only' },
                ]}
              />
              <SelectInput
                value={localSettings.mpvScaler ?? 'lanczos'}
                onChange={v => setLocalSettings(s => ({ ...s, mpvScaler: v as AppSettings['mpvScaler'] }))}
                label="Video Scaler"
                description="Higher quality scalers require more GPU. Lanczos is a good balance."
                options={[
                  { value: 'bilinear', label: 'Bilinear (Fast)' },
                  { value: 'bicubic', label: 'Bicubic' },
                  { value: 'lanczos', label: 'Lanczos (Recommended)' },
                  { value: 'ewa_lanczossharp', label: 'EWA Lanczos Sharp (Best)' },
                ]}
              />
              <SelectInput
                value={localSettings.mpvVideoSync ?? 'audio'}
                onChange={v => setLocalSettings(s => ({ ...s, mpvVideoSync: v as AppSettings['mpvVideoSync'] }))}
                label="Video Sync Mode"
                description="display-resample reduces judder on high-refresh displays. audio is the safest default."
                options={[
                  { value: 'audio', label: 'Audio (Default)' },
                  { value: 'display-resample', label: 'Display Resample' },
                  { value: 'display-tempo', label: 'Display Tempo' },
                ]}
              />
            </Section>
            <Section title="Display">
              <Toggle
                checked={localSettings.mpvDeinterlace ?? false}
                onChange={v => setLocalSettings(s => ({ ...s, mpvDeinterlace: v }))}
                label="Deinterlace"
                description="Enable deinterlacing for interlaced broadcast streams."
              />
              <Toggle
                checked={localSettings.showStreamHealthOverlay ?? true}
                onChange={v => setLocalSettings(s => ({ ...s, showStreamHealthOverlay: v }))}
                label="Stream Health Overlay"
                description="Show buffer health indicators in the player."
              />
            </Section>
            <Section title="Audio">
              <div className="flex items-center justify-between py-3 px-1">
                <div>
                  <p className="text-white/80 text-sm">Volume Boost</p>
                  <p className="text-white/30 text-xs mt-0.5">
                    Amplify volume beyond 100%. Current: {Math.round((localSettings.mpvVoiceAmplify ?? 1.0) * 100)}%
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min={1.0}
                    max={4.0}
                    step={0.1}
                    value={localSettings.mpvVoiceAmplify ?? 1.0}
                    onChange={e => setLocalSettings(s => ({ ...s, mpvVoiceAmplify: parseFloat(e.target.value) }))}
                    className="w-32 accent-cyan-500"
                  />
                  <span className="text-white/50 text-xs w-10 text-right">
                    {Math.round((localSettings.mpvVoiceAmplify ?? 1.0) * 100)}%
                  </span>
                </div>
              </div>
              <div className="flex items-center justify-between py-3 px-1">
                <div>
                  <p className="text-white/80 text-sm">Audio Sync Offset</p>
                  <p className="text-white/30 text-xs mt-0.5">
                    Adjust audio delay relative to video. Updates live while playing.
                  </p>
                </div>
                <div className="flex items-center gap-3">
                  <input
                    type="range"
                    min={-2000}
                    max={2000}
                    step={50}
                    value={localSettings.mpvAudioSyncMs ?? 0}
                    onChange={e => {
                      const offsetMs = parseInt(e.target.value, 10);
                      setLocalSettings(s => ({ ...s, mpvAudioSyncMs: offsetMs }));
                      window.electronAPI?.invoke?.('mpv:set-audio-delay', { offsetMs });
                    }}
                    className="w-32 accent-cyan-500"
                  />
                  <span className="text-white/50 text-xs w-14 text-right">
                    {localSettings.mpvAudioSyncMs ?? 0} ms
                  </span>
                </div>
              </div>
            </Section>
            <Section title="Screenshot">
              <TextInput
                value={localSettings.mpvScreenshotDir ?? ''}
                onChange={v => setLocalSettings(s => ({ ...s, mpvScreenshotDir: v }))}
                label="Screenshot Folder"
                description="Leave blank to use your Pictures folder."
                placeholder="C:\Users\...\Pictures"
              />
            </Section>
            <Section title="Developer">
              <SelectInput
                value={localSettings.mpvLogLevel ?? 'error'}
                onChange={v => setLocalSettings(s => ({ ...s, mpvLogLevel: v as AppSettings['mpvLogLevel'] }))}
                label="MPV Log Level"
                description="Controls verbosity of MPV logs in the main process console."
                options={[
                  { value: 'no', label: 'Disabled' },
                  { value: 'error', label: 'Errors Only (Default)' },
                  { value: 'warn', label: 'Warnings' },
                  { value: 'info', label: 'Info' },
                  { value: 'debug', label: 'Debug (Verbose)' },
                ]}
              />
            </Section>
          </>
        );

      case 'keyboard':
        return <KeyboardTab onSuccess={success} onError={toastError} shortcuts={shortcuts} />;

      case 'about':
        return (
          <div className="text-center py-12">
            <div className="w-20 h-20 mx-auto mb-4 rounded-2xl bg-gradient-to-br from-cyan-500/20 to-purple-500/20
              border border-white/10 flex items-center justify-center">
              <span className="text-3xl font-bold text-cyan-400">D</span>
            </div>
            <h2 className="text-white text-xl font-bold mb-1">DYLANDOS IPTV ULTIMATE</h2>
            <p className="text-white/40 text-sm mb-4">Version {appVersion}</p>
            <p className="text-white/30 text-xs max-w-sm mx-auto">
              Premium IPTV player for Windows. Built with Electron, React, and TypeScript.
            </p>
            <button
              onClick={() => window.electronAPI?.invoke?.('update:check')}
              className="mt-6 px-4 py-2 bg-cyan-500/10 border border-cyan-500/20 text-cyan-400 text-sm
                rounded-xl hover:bg-cyan-500/20 transition-colors"
            >
              Check for Updates
            </button>
          </div>
        );

      default:
        return null;
    }
  };

  return (
    <div className="flex-1 flex overflow-hidden">
      {/* Sidebar */}
      <div className="w-56 border-r border-white/[0.06] py-4 overflow-y-auto">
        <div className="px-4 mb-4">
          <h2 className="text-white font-semibold text-lg flex items-center gap-2">
            <SettingsIcon size={18} />
            Settings
          </h2>
        </div>
        <nav className="space-y-0.5 px-2">
          {TABS.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition-all ${activeTab === tab.id
                ? 'theme-accent-surface theme-text-accent font-medium'
                : 'text-white/50 hover:text-white/70 hover:bg-white/[0.03]'
                }`}
            >
              {tab.icon}
              {tab.label}
            </button>
          ))}
        </nav>

        <div className="mt-6 px-3">
          <button
            onClick={resetToDefaults}
            className="w-full flex items-center gap-2 justify-center px-3 py-2 text-red-400/60 text-xs
              hover:text-red-400 hover:bg-red-500/5 rounded-xl transition-colors"
          >
            <RotateCcw size={12} />
            Reset to Defaults
          </button>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-y-auto p-6">
        {renderContent()}
      </div>
    </div>
  );
};
