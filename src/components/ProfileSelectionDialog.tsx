// ─── Profile Selection Dialog ────────────────────────────────────────────────

import React, { useState } from 'react';
import { Plus, Trash2, Check, Radio, X, Loader2, Link, Wifi, Settings, Users, ChevronLeft } from 'lucide-react';
import { XtreamProfile } from '../types/xtream';

type SourceTab = 'xtream' | 'm3u' | 'stalker';
type DialogView = 'list' | 'add' | 'manage';

interface ProfileSelectionDialogProps {
  profiles: XtreamProfile[];
  activeProfileId: string | null;
  onSelectProfile: (profile: XtreamProfile) => void;
  onAddProfile: (data: Omit<XtreamProfile, 'id'>) => void;
  onDeleteProfile: (id: string) => void;
  onUpdateProfile?: (id: string, partial: Partial<XtreamProfile>) => void;
  onAddM3USource?: (data: { name: string; url: string; refreshHours: number }) => void;
  onAddStalkerSource?: (data: { name: string; portalUrl: string; mac: string }) => void;
  isOpen: boolean;
  onClose: () => void;
}

export const ProfileSelectionDialog: React.FC<ProfileSelectionDialogProps> = ({
  profiles,
  activeProfileId,
  onSelectProfile,
  onAddProfile,
  onDeleteProfile,
  onUpdateProfile,
  onAddM3USource,
  onAddStalkerSource,
  isOpen,
  onClose,
}) => {
  const [view, setView] = useState<DialogView>(profiles.length === 0 ? 'add' : 'list');
  const [activeTab, setActiveTab] = useState<SourceTab>('xtream');
  // Xtream form
  const [name, setName] = useState('');
  const [serverUrl, setServerUrl] = useState('');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  // M3U form
  const [m3uName, setM3uName] = useState('');
  const [m3uUrl, setM3uUrl] = useState('');
  const [m3uRefreshHours, setM3uRefreshHours] = useState(24);
  // Stalker form
  const [stalkerName, setStalkerName] = useState('');
  const [stalkerPortalUrl, setStalkerPortalUrl] = useState('');
  const [stalkerMac, setStalkerMac] = useState('');

  const [isConnecting, setIsConnecting] = useState(false);
  const [error, setError] = useState('');

  if (!isOpen) return null;

  const handleAddXtream = async () => {
    if (!serverUrl.trim() || !username.trim() || !password.trim()) {
      setError('All fields are required');
      return;
    }

    setIsConnecting(true);
    setError('');

    try {
      const cleanUrl = serverUrl.replace(/\/+$/, '');

      // Validate by attempting authentication
      const authUrl = `${cleanUrl}/player_api.php?username=${encodeURIComponent(
        username
      )}&password=${encodeURIComponent(password)}`;
      const data = window.electronAPI?.net
        ? await window.electronAPI.net.fetchJson<any>(authUrl, {
          'Accept': 'application/json',
        }, 10000)
        : await (async () => {
          const response = await fetch(authUrl, {
            signal: AbortSignal.timeout(10000),
            cache: 'no-store',
          });
          if (!response.ok) throw new Error(`Server returned ${response.status}`);
          return response.json();
        })();

      if (data.user_info?.auth === 0) {
        throw new Error('Invalid credentials');
      }

      onAddProfile({
        name: name.trim() || username,
        serverUrl: cleanUrl,
        username: username.trim(),
        password: password.trim(),
        createdAt: Date.now(),
        enableDvr: true,
        enableRecording: true,
      });

      setName(''); setServerUrl(''); setUsername(''); setPassword('');
      setView('list');
    } catch (err: any) {
      setError(err.message || 'Connection failed');
    } finally {
      setIsConnecting(false);
    }
  };

  const handleAddM3U = async () => {
    if (!m3uUrl.trim()) { setError('M3U URL is required'); return; }
    setIsConnecting(true); setError('');
    try {
      // Quick HEAD check
      const controller = new AbortController();
      const tid = setTimeout(() => controller.abort(), 8000);
      await fetch(m3uUrl.trim(), { method: 'HEAD', signal: controller.signal }).catch(() => {});
      clearTimeout(tid);
      onAddM3USource?.({ name: m3uName.trim() || 'M3U Playlist', url: m3uUrl.trim(), refreshHours: m3uRefreshHours });
      setM3uName(''); setM3uUrl(''); setM3uRefreshHours(24);
      setView('list');
    } catch (err: any) {
      setError(err.message || 'Failed to reach M3U URL');
    } finally {
      setIsConnecting(false);
    }
  };

  const handleAddStalker = async () => {
    if (!stalkerPortalUrl.trim() || !stalkerMac.trim()) {
      setError('Portal URL and MAC address are required'); return;
    }
    setIsConnecting(true); setError('');
    try {
      // Dynamic import to keep bundle lean
      const { stalkerApi } = await import('../services/stalkerApi');
      const connected = await stalkerApi.connect(stalkerPortalUrl.trim(), stalkerMac.trim());
      if (!connected) throw new Error('Stalker handshake failed — check URL and MAC');
      onAddStalkerSource?.({ name: stalkerName.trim() || 'Stalker Portal', portalUrl: stalkerPortalUrl.trim(), mac: stalkerMac.trim() });
      setStalkerName(''); setStalkerPortalUrl(''); setStalkerMac('');
      setView('list');
    } catch (err: any) {
      setError(err.message || 'Connection failed');
    } finally {
      setIsConnecting(false);
    }
  };

  const tabs: { id: SourceTab; label: string; icon: React.ReactNode }[] = [
    { id: 'xtream', label: 'Xtream', icon: <Radio size={13} /> },
    { id: 'm3u', label: 'M3U', icon: <Link size={13} /> },
    { id: 'stalker', label: 'Stalker', icon: <Wifi size={13} /> },
  ];

  return (
    <div className="fixed inset-0 z-[100] flex items-center justify-center bg-black/80 backdrop-blur-sm">
      <div className="w-full max-w-md bg-surface-100 border border-white/10 rounded-2xl shadow-2xl overflow-hidden">
        {/* Header */}
        <div className="flex items-center justify-between px-6 py-4 border-b border-white/5">
          <div className="flex items-center gap-3">
            {(view === 'add' || view === 'manage') && profiles.length > 0 && (
              <button onClick={() => setView('list')} className="text-white/30 hover:text-white transition-colors p-1">
                <ChevronLeft size={16} />
              </button>
            )}
            <div>
              <h2 className="text-white text-lg font-bold">
                {view === 'add' ? 'Add Source' : view === 'manage' ? 'Account Management' : 'Select Profile'}
              </h2>
              <p className="text-white/40 text-xs mt-0.5">
                {view === 'add' ? 'Connect to your IPTV provider' : view === 'manage' ? `${profiles.length} account${profiles.length !== 1 ? 's' : ''} configured` : 'Choose a profile to connect'}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            {view === 'list' && profiles.length > 0 && (
              <button
                onClick={() => setView('manage')}
                className="text-white/30 hover:text-cyan-400 transition-colors p-1.5 rounded-lg hover:bg-white/5"
                title="Account Management"
              >
                <Users size={16} />
              </button>
            )}
            {profiles.length > 0 && (
              <button onClick={onClose} className="text-white/30 hover:text-white transition-colors p-1">
                <X size={18} />
              </button>
            )}
          </div>
        </div>

        {/* Content */}
        <div className="p-6">
          {view === 'add' ? (
            <div className="space-y-4">
              {/* Source type tabs */}
              <div className="flex gap-1 bg-white/5 rounded-xl p-1">
                {tabs.map(tab => (
                  <button
                    key={tab.id}
                    onClick={() => { setActiveTab(tab.id); setError(''); }}
                    className={`flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg text-xs font-medium transition-all ${
                      activeTab === tab.id
                        ? 'bg-cyan-500 text-black'
                        : 'text-white/40 hover:text-white/70'
                    }`}
                  >
                    {tab.icon}{tab.label}
                  </button>
                ))}
              </div>

              {/* Xtream form */}
              {activeTab === 'xtream' && (
                <>
                  <input type="text" placeholder="Profile Name (optional)" value={name}
                    onChange={e => setName(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="url" placeholder="Server URL (http://...)" value={serverUrl}
                    onChange={e => setServerUrl(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="text" placeholder="Username" value={username}
                    onChange={e => setUsername(e.target.value)} autoComplete="off"
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="password" placeholder="Password" value={password}
                    onChange={e => setPassword(e.target.value)} autoComplete="off"
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                </>
              )}

              {/* M3U form */}
              {activeTab === 'm3u' && (
                <>
                  <input type="text" placeholder="Playlist Name (optional)" value={m3uName}
                    onChange={e => setM3uName(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="url" placeholder="M3U URL (http://...)" value={m3uUrl}
                    onChange={e => setM3uUrl(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <div>
                    <label className="text-white/40 text-xs block mb-1.5">Auto-refresh interval</label>
                    <select value={m3uRefreshHours} onChange={e => setM3uRefreshHours(Number(e.target.value))}
                      className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm outline-none focus:border-cyan-500/50 transition-colors">
                      <option value={0}>Manual only</option>
                      <option value={6}>Every 6 hours</option>
                      <option value={12}>Every 12 hours</option>
                      <option value={24}>Every 24 hours</option>
                    </select>
                  </div>
                </>
              )}

              {/* Stalker Portal form */}
              {activeTab === 'stalker' && (
                <>
                  <input type="text" placeholder="Source Name (optional)" value={stalkerName}
                    onChange={e => setStalkerName(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="url" placeholder="Portal URL (http://...)" value={stalkerPortalUrl}
                    onChange={e => setStalkerPortalUrl(e.target.value)}
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 transition-colors" />
                  <input type="text" placeholder="MAC Address (00:1A:79:xx:xx:xx)" value={stalkerMac}
                    onChange={e => setStalkerMac(e.target.value)} autoComplete="off"
                    className="w-full px-4 py-2.5 bg-white/5 border border-white/10 rounded-xl text-white text-sm placeholder:text-white/25 outline-none focus:border-cyan-500/50 font-mono transition-colors" />
                  <p className="text-white/25 text-xs">
                    Stalker Portal uses your device MAC address for authentication.
                    Find your MAC in Settings → Device Info.
                  </p>
                </>
              )}

              {error && (
                <p className="text-red-400 text-xs bg-red-500/10 border border-red-500/20 rounded-lg px-3 py-2">
                  {error}
                </p>
              )}

              <div className="flex gap-3">
                {profiles.length > 0 && (
                  <button onClick={() => setView('list')}
                    className="flex-1 py-2.5 border border-white/10 text-white/50 text-sm rounded-xl hover:bg-white/5 transition-colors">
                    Cancel
                  </button>
                )}
                <button
                  onClick={activeTab === 'xtream' ? handleAddXtream : activeTab === 'm3u' ? handleAddM3U : handleAddStalker}
                  disabled={isConnecting}
                  className="flex-1 py-2.5 bg-cyan-500 text-black text-sm font-bold rounded-xl hover:bg-cyan-400 transition-colors disabled:opacity-50 flex items-center justify-center gap-2">
                  {isConnecting ? (
                    <><Loader2 size={14} className="animate-spin" />Connecting...</>
                  ) : (
                    <><Check size={14} />Connect</>
                  )}
                </button>
              </div>
            </div>
          ) : view === 'manage' ? (
            /* ── Account Management View ── */
            <div className="space-y-3">
              <p className="text-white/30 text-xs pb-1">
                Manage up to 10 accounts. Toggle DVR/Recording per account. Click a row to switch.
              </p>
              <div className="space-y-2 max-h-80 overflow-y-auto" style={{ scrollbarWidth: 'thin', scrollbarColor: 'rgba(255,255,255,0.15) transparent' }}>
                {profiles.map((profile) => {
                  const isActive = activeProfileId === profile.id;
                  return (
                    <div
                      key={profile.id}
                      className={`rounded-xl border transition-all ${
                        isActive
                          ? 'border-cyan-500/40 bg-cyan-500/8'
                          : 'border-white/[0.06] bg-white/[0.02] hover:border-white/15'
                      }`}
                    >
                      {/* Row header — click to switch account */}
                      <button
                        className="w-full flex items-center gap-3 px-4 py-3 text-left"
                        onClick={() => { onSelectProfile(profile); onClose(); }}
                      >
                        <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                          isActive ? 'bg-cyan-500/25' : 'bg-white/10'
                        }`}>
                          <Radio size={14} className={isActive ? 'text-cyan-400' : 'text-white/30'} />
                        </div>
                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2">
                            <p className="text-white text-sm font-medium truncate">{profile.name || profile.username}</p>
                            {isActive && (
                              <span className="px-1.5 py-0.5 bg-cyan-500/20 text-cyan-400 text-[9px] font-bold uppercase rounded-full border border-cyan-500/30 shrink-0">
                                Active
                              </span>
                            )}
                          </div>
                          <p className="text-white/30 text-xs truncate">{profile.serverUrl}</p>
                          <p className="text-white/20 text-[10px] truncate">User: {profile.username}</p>
                        </div>
                      </button>

                      {/* Per-account feature toggles */}
                      <div className="px-4 pb-3 flex items-center gap-4">
                        {/* DVR Toggle */}
                        <label className="flex items-center gap-2 cursor-pointer group">
                          <div className="relative">
                            <input type="checkbox" className="sr-only peer"
                              checked={profile.enableDvr !== false}
                              onChange={e => onUpdateProfile?.(profile.id, { enableDvr: e.target.checked })}
                            />
                            <div className="w-8 h-4 rounded-full transition-colors bg-white/10 peer-checked:bg-cyan-600" />
                            <div className="absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white shadow transition-transform peer-checked:translate-x-4" />
                          </div>
                          <span className="text-white/40 text-xs group-hover:text-white/60 transition-colors">DVR</span>
                        </label>

                        {/* Recording Toggle */}
                        <label className="flex items-center gap-2 cursor-pointer group">
                          <div className="relative">
                            <input type="checkbox" className="sr-only peer"
                              checked={profile.enableRecording !== false}
                              onChange={e => onUpdateProfile?.(profile.id, { enableRecording: e.target.checked })}
                            />
                            <div className="w-8 h-4 rounded-full transition-colors bg-white/10 peer-checked:bg-red-600" />
                            <div className="absolute top-0.5 left-0.5 w-3 h-3 rounded-full bg-white shadow transition-transform peer-checked:translate-x-4" />
                          </div>
                          <span className="text-white/40 text-xs group-hover:text-white/60 transition-colors">Record</span>
                        </label>

                        <div className="flex-1" />

                        {/* Delete */}
                        <button
                          onClick={() => {
                            if (confirm(`Delete account "${profile.name || profile.username}"?`)) {
                              onDeleteProfile(profile.id);
                            }
                          }}
                          className="text-white/20 hover:text-red-400 transition-colors p-1 rounded"
                          title="Remove account"
                        >
                          <Trash2 size={13} />
                        </button>
                      </div>
                    </div>
                  );
                })}
              </div>

              {profiles.length < 10 && (
                <button
                  onClick={() => setView('add')}
                  className="flex items-center justify-center gap-2 w-full py-3 border border-dashed border-white/15 rounded-xl text-white/40 text-sm hover:text-white hover:border-white/30 hover:bg-white/3 transition-all"
                >
                  <Plus size={14} />
                  Add New Account ({profiles.length}/10)
                </button>
              )}
            </div>
          ) : (
            /* ── Profile List View ── */
            <div className="space-y-2">
              {profiles.map((profile) => (
                <div
                  key={profile.id}
                  className={`flex items-center gap-3 px-4 py-3 rounded-xl border cursor-pointer transition-all group ${
                    activeProfileId === profile.id
                      ? 'border-cyan-500/40 bg-cyan-500/10'
                      : 'border-white/5 bg-white/3 hover:border-white/15 hover:bg-white/5'
                  }`}
                  onClick={() => onSelectProfile(profile)}
                >
                  <div className={`w-8 h-8 rounded-full flex items-center justify-center shrink-0 ${
                    activeProfileId === profile.id ? 'bg-cyan-500/20' : 'bg-white/10'
                  }`}>
                    <Radio size={14} className={activeProfileId === profile.id ? 'text-cyan-400' : 'text-white/30'} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <p className="text-white text-sm font-medium truncate">{profile.name || profile.username}</p>
                    <p className="text-white/30 text-xs truncate">{profile.serverUrl}</p>
                  </div>
                  {activeProfileId === profile.id && (
                    <span className="px-1.5 py-0.5 bg-cyan-500/20 text-cyan-400 text-[9px] font-bold uppercase rounded-full border border-cyan-500/30 shrink-0">
                      Active
                    </span>
                  )}
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      if (confirm(`Delete profile "${profile.name || profile.username}"?`)) {
                        onDeleteProfile(profile.id);
                      }
                    }}
                    className="opacity-0 group-hover:opacity-100 text-white/30 hover:text-red-400 transition-all p-1">
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}

              {profiles.length < 10 && (
                <button
                  onClick={() => setView('add')}
                  className="flex items-center justify-center gap-2 w-full py-3 border border-dashed border-white/15 rounded-xl text-white/40 text-sm hover:text-white hover:border-white/30 hover:bg-white/3 transition-all">
                  <Plus size={14} />
                  Add New Source
                </button>
              )}
            </div>
          )}
        </div>
      </div>
    </div>
  );
};


