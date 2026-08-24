import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Circle,
  Square,
  Radio,
  Film,
  Trash2,
  Play,
  FolderOpen,
  HardDrive,
  RefreshCw,
  AlertCircle,
  Clock,
  CheckCircle2,
  History,
} from 'lucide-react';
import { XtreamChannel } from '../types/xtream';
import { xtreamApi as xtream } from '../services/xtreamApi';
import { RecordingButton } from '../components/RecordingButton';

interface RecordingMeta {
  id: string;
  channelName: string;
  programTitle: string;
  streamUrl: string;
  outputPath: string;
  filename: string;
  startTime: string;
  durationSeconds: number | null;
  status: 'recording' | 'completed' | 'error';
  size: number;
}

interface ActiveRecording extends RecordingMeta {
  elapsedSeconds: number;
}

interface DVRStatus {
  activeCount: number;
  maxConcurrent: number;
  ffmpegAvailable: boolean;
  outputDir: string;
}

interface DVRPageProps {
  channels: XtreamChannel[];
  onPlayRecording: (recordingPath: string) => void;
  onPlayCatchup?: (channel: XtreamChannel, program: any) => void;
}

const formatBytes = (bytes: number): string => {
  if (!isFinite(bytes) || bytes <= 0) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let idx = 0;
  while (value >= 1024 && idx < units.length - 1) {
    value /= 1024;
    idx++;
  }
  return `${value.toFixed(value >= 100 ? 0 : value >= 10 ? 1 : 2)} ${units[idx]}`;
};

const formatDuration = (seconds: number): string => {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  if (h > 0) return `${h}:${String(m).padStart(2, '0')}:${String(sec).padStart(2, '0')}`;
  return `${m}:${String(sec).padStart(2, '0')}`;
};

// ── Scheduled Tab ─────────────────────────────────────────────────────────────
const ScheduledTab: React.FC<{ channels: XtreamChannel[] }> = ({ channels }) => {
  const [scheduled, setScheduled] = useState<any[]>([]);
  const [channelId, setChannelId] = useState<number | ''>('');
  const [title, setTitle] = useState('');
  const [startAt, setStartAt] = useState('');
  const [durationMin, setDurationMin] = useState(60);
  const [adding, setAdding] = useState(false);
  const [scheduleError, setScheduleError] = useState('');
  const [conflictDialog, setConflictDialog] = useState<{
    draft: any;
    conflicting: any[];
  } | null>(null);
  const [keyword, setKeyword] = useState('');
  const [seriesKeyword, setSeriesKeyword] = useState('');
  const [recordingType, setRecordingType] = useState<'once' | 'series' | 'keyword'>('once');

  const reload = useCallback(async () => {
    const { dvrScheduler } = await import('../services/dvrScheduler');
    setScheduled([...dvrScheduler.getPending()]);
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const buildDraft = async () => {
    if (!channelId || !title || !startAt) return null;
    const ch = channels.find(c => c.stream_id === Number(channelId));
    if (!ch) return null;
    const { xtreamApi } = await import('../services/xtreamApi');
    const streamUrl = xtreamApi.getLiveStreamUrl(ch.stream_id, 'ts');
    const startTime = new Date(startAt).getTime();
    const endTime = startTime + durationMin * 60 * 1000;
    return {
      id: `sched_${Date.now()}`,
      channelId: String(ch.stream_id),
      channelName: ch.name,
      programTitle: title,
      streamUrl,
      startTimeMs: startTime,
      endTimeMs: endTime,
      preBufferSecs: 120,
      postBufferSecs: 120,
      recordingType,
      seriesKeyword: recordingType === 'series' ? (seriesKeyword || title) : '',
      skipDuplicates: recordingType !== 'once',
      keyword: recordingType === 'keyword' ? (keyword || title) : '',
      keywordChannelIds: recordingType === 'keyword' ? [String(ch.stream_id)] : [],
      priority: 5,
      conflictAction: 'skip' as const,
      status: 'pending' as const,
      createdAt: Date.now(),
    };
  };

  const handleSchedule = async () => {
    setScheduleError('');
    const draft = await buildDraft();
    if (!draft) {
      setScheduleError('Channel, title, and start time are required');
      return;
    }
    const { dvrScheduler } = await import('../services/dvrScheduler');
    const result = dvrScheduler.schedule(draft);
    if (result.type === 'past') {
      setScheduleError(result.reason);
      return;
    }
    if (result.type === 'conflict') {
      setConflictDialog({ draft, conflicting: result.conflicting });
      return;
    }
    setAdding(false);
    setTitle('');
    setStartAt('');
    setDurationMin(60);
    setKeyword('');
    setSeriesKeyword('');
    setRecordingType('once');
    await reload();
  };

  const resolveConflict = async (mode: 'cancel' | 'force' | 'lower') => {
    if (!conflictDialog) return;
    if (mode === 'cancel') {
      setConflictDialog(null);
      return;
    }
    const { dvrScheduler } = await import('../services/dvrScheduler');
    const result = dvrScheduler.scheduleForce(
      conflictDialog.draft,
      mode === 'lower' ? 'stop-lower-priority' : 'cancel-conflicts'
    );
    setConflictDialog(null);
    if (result.type === 'conflict') {
      setScheduleError('Higher-priority conflicts remain — raise priority or cancel them manually');
      return;
    }
    if (result.type === 'past') {
      setScheduleError(result.reason);
      return;
    }
    setAdding(false);
    setTitle('');
    setStartAt('');
    await reload();
  };

  const handleCancel = async (id: string) => {
    const { dvrScheduler } = await import('../services/dvrScheduler');
    dvrScheduler.cancel(id);
    await reload();
  };

  return (
    <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
      <div className="flex items-center justify-between mb-2">
        <p className="text-white/40 text-sm">{scheduled.length} scheduled recording{scheduled.length !== 1 ? 's' : ''}</p>
        <button
          onClick={() => setAdding(a => !a)}
          className="flex items-center gap-1.5 px-3 py-1.5 bg-cyan-500/10 border border-cyan-500/20
            text-cyan-400 text-xs rounded-xl hover:bg-cyan-500/20 transition-colors"
        >
          <Circle size={12} /> Schedule New
        </button>
      </div>

      {scheduleError && (
        <div className="flex items-center gap-2 rounded-xl border border-red-500/30 bg-red-500/10 p-3 text-sm text-red-300">
          <AlertCircle size={16} />
          <span>{scheduleError}</span>
        </div>
      )}

      {conflictDialog && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 space-y-3">
          <p className="text-amber-200 text-sm font-semibold flex items-center gap-2">
            <AlertCircle size={16} /> Scheduling conflict
          </p>
          <p className="text-white/50 text-xs">
            Overlaps with {conflictDialog.conflicting.length} existing recording
            {conflictDialog.conflicting.length !== 1 ? 's' : ''}:
          </p>
          <ul className="space-y-1">
            {conflictDialog.conflicting.map((c: any) => (
              <li key={c.id} className="text-white/70 text-xs">
                {c.programTitle} · {c.channelName} · {new Date(c.startTimeMs).toLocaleString()}
                <span className="text-white/30"> (priority {c.priority})</span>
              </li>
            ))}
          </ul>
          <div className="flex flex-wrap gap-2">
            <button
              onClick={() => void resolveConflict('force')}
              className="px-3 py-1.5 bg-amber-500 text-black text-xs font-semibold rounded-lg hover:bg-amber-400"
            >
              Cancel conflicts &amp; schedule
            </button>
            <button
              onClick={() => void resolveConflict('lower')}
              className="px-3 py-1.5 bg-white/10 text-white/80 text-xs rounded-lg hover:bg-white/15"
            >
              Stop lower-priority only
            </button>
            <button
              onClick={() => void resolveConflict('cancel')}
              className="px-3 py-1.5 text-white/40 text-xs hover:text-white/70"
            >
              Keep existing
            </button>
          </div>
        </div>
      )}

      {adding && (
        <div className="rounded-xl border border-white/[0.08] bg-white/[0.02] p-4 space-y-3">
          <p className="text-white/60 text-xs font-semibold uppercase tracking-wider">New Scheduled Recording</p>
          <div className="flex gap-2">
            {(['once', 'series', 'keyword'] as const).map(t => (
              <button
                key={t}
                onClick={() => setRecordingType(t)}
                className={`px-2.5 py-1 rounded-lg text-[11px] capitalize transition-colors ${
                  recordingType === t
                    ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30'
                    : 'text-white/40 border border-white/10 hover:text-white/70'
                }`}
              >
                {t}
              </button>
            ))}
          </div>
          <select
            value={channelId}
            onChange={e => setChannelId(Number(e.target.value))}
            className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm focus:outline-none"
          >
            <option value="">Select channel...</option>
            {channels.map(ch => (
              <option key={ch.stream_id} value={ch.stream_id}>{ch.name}</option>
            ))}
          </select>
          <input
            type="text"
            value={title}
            onChange={e => setTitle(e.target.value)}
            placeholder="Program title"
            className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm
              placeholder-white/20 focus:outline-none"
          />
          {recordingType === 'series' && (
            <input
              type="text"
              value={seriesKeyword}
              onChange={e => setSeriesKeyword(e.target.value)}
              placeholder="Series match keyword (defaults to title)"
              className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm
                placeholder-white/20 focus:outline-none"
            />
          )}
          {recordingType === 'keyword' && (
            <input
              type="text"
              value={keyword}
              onChange={e => setKeyword(e.target.value)}
              placeholder="Auto-record keyword across EPG"
              className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm
                placeholder-white/20 focus:outline-none"
            />
          )}
          <div className="grid grid-cols-2 gap-3">
            <div>
              <p className="text-white/40 text-[10px] mb-1">Start time</p>
              <input
                type="datetime-local"
                value={startAt}
                onChange={e => setStartAt(e.target.value)}
                className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm focus:outline-none"
              />
            </div>
            <div>
              <p className="text-white/40 text-[10px] mb-1">Duration (minutes)</p>
              <input
                type="number"
                value={durationMin}
                onChange={e => setDurationMin(Number(e.target.value))}
                min={5}
                max={480}
                className="w-full px-3 py-2 rounded-xl bg-white/5 border border-white/10 text-white text-sm focus:outline-none"
              />
            </div>
          </div>
          <div className="flex gap-2">
            <button
              onClick={() => void handleSchedule()}
              className="px-4 py-2 bg-cyan-500 text-black text-sm font-semibold rounded-xl hover:bg-cyan-400 transition-colors"
            >
              Schedule
            </button>
            <button
              onClick={() => setAdding(false)}
              className="px-4 py-2 bg-white/5 text-white/50 text-sm rounded-xl hover:bg-white/10 transition-colors"
            >
              Cancel
            </button>
          </div>
        </div>
      )}

      {scheduled.length === 0 && !adding ? (
        <div className="text-center py-12 text-white/30 text-sm">
          No scheduled recordings. Click &quot;Schedule New&quot; to add one.
        </div>
      ) : (
        <div className="space-y-2">
          {scheduled.map(rec => {
            const overlaps = scheduled.filter(
              (o: any) =>
                o.id !== rec.id &&
                o.startTimeMs < rec.endTimeMs &&
                rec.startTimeMs < o.endTimeMs
            );
            return (
              <div key={rec.id}
                className={`rounded-xl border p-3 flex items-center gap-3 ${
                  overlaps.length > 0
                    ? 'border-amber-500/30 bg-amber-500/5'
                    : 'border-white/[0.08] bg-white/[0.02]'
                }`}>
                <div className="w-9 h-9 rounded-lg bg-cyan-500/10 flex items-center justify-center shrink-0">
                  <Clock size={16} className="text-cyan-400" />
                </div>
                <div className="flex-1 min-w-0">
                  <p className="text-white/85 text-sm font-medium truncate">{rec.programTitle}</p>
                  <p className="text-white/40 text-xs">{rec.channelName}
                    {rec.recordingType && rec.recordingType !== 'once' ? ` · ${rec.recordingType}` : ''}
                  </p>
                  <p className="text-white/25 text-[11px] mt-0.5">
                    {new Date(rec.startTimeMs).toLocaleString()} · {Math.round((rec.endTimeMs - rec.startTimeMs) / 60000)} min
                  </p>
                  {overlaps.length > 0 && (
                    <p className="text-amber-300/80 text-[11px] mt-0.5">
                      Conflicts with {overlaps.length} other schedule{overlaps.length !== 1 ? 's' : ''}
                    </p>
                  )}
                </div>
                <button
                  onClick={() => handleCancel(rec.id)}
                  className="p-2 rounded-lg text-red-300/60 hover:text-red-300 hover:bg-red-500/10 transition-colors"
                  title="Cancel recording"
                >
                  <Trash2 size={14} />
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
};

// ── Catchup (Provider Timeshift Archive) Tab ─────────────────────────────────
const CatchupTab: React.FC<{
  channels: XtreamChannel[];
  onPlayCatchup?: (channel: XtreamChannel, program: any) => void;
}> = ({ channels, onPlayCatchup }) => {
  const archiveChannels = useMemo(() => {
    return channels.filter(c => c.tv_archive === 1);
  }, [channels]);

  const [selectedChannelId, setSelectedChannelId] = useState<number | null>(() => {
    return archiveChannels.length > 0 ? archiveChannels[0].stream_id : null;
  });
  const [search, setSearch] = useState('');
  const [programs, setPrograms] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const selectedChannel = useMemo(() => {
    return archiveChannels.find(c => c.stream_id === selectedChannelId) || null;
  }, [archiveChannels, selectedChannelId]);

  const filteredChannels = useMemo(() => {
    if (!search.trim()) return archiveChannels;
    const q = search.toLowerCase();
    return archiveChannels.filter(c => c.name.toLowerCase().includes(q));
  }, [archiveChannels, search]);

  useEffect(() => {
    if (!selectedChannelId) {
      setPrograms([]);
      return;
    }

    let mounted = true;
    setLoading(true);
    setError('');

    xtream.getShortEpg(selectedChannelId, 40)
      .then(res => {
        if (!mounted) return;
        if (res?.epg_listings && Array.isArray(res.epg_listings)) {
          setPrograms(res.epg_listings);
        } else {
          setPrograms([]);
        }
      })
      .catch(err => {
        if (!mounted) return;
        setError(err.message || 'Failed to load archive listings');
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });

    return () => {
      mounted = false;
    };
  }, [selectedChannelId]);

  if (archiveChannels.length === 0) {
    return (
      <div className="flex-1 flex flex-col items-center justify-center p-8 text-center text-white/40">
        <Clock size={48} className="mb-3 opacity-30 text-cyan-400" />
        <h3 className="text-white font-semibold text-base mb-1">No Provider Catch-up Channels</h3>
        <p className="text-xs max-w-md text-white/35">
          Your IPTV provider account does not currently advertise server-side archive (tv_archive: 1) on any active channels.
        </p>
      </div>
    );
  }

  return (
    <div className="flex-1 flex overflow-hidden">
      {/* Channels Sidebar */}
      <div className="w-80 border-r border-white/[0.06] flex flex-col bg-white/[0.01]">
        <div className="p-3 border-b border-white/[0.06]">
          <input
            type="text"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search archive channels..."
            className="w-full bg-white/[0.04] border border-white/[0.08] rounded-xl px-3 py-2 text-xs text-white placeholder:text-white/30 focus:outline-none focus:border-cyan-500/40"
          />
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {filteredChannels.map(ch => (
            <button
              key={ch.stream_id}
              onClick={() => setSelectedChannelId(ch.stream_id)}
              className={`w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-left text-xs transition-all ${
                selectedChannelId === ch.stream_id
                  ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30'
                  : 'text-white/70 hover:bg-white/[0.04] border border-transparent'
              }`}
            >
              <span className="truncate font-medium">{ch.name}</span>
              <span className="text-[10px] bg-white/[0.06] text-white/40 px-1.5 py-0.5 rounded ml-2 shrink-0">
                {ch.tv_archive_duration || 7}d
              </span>
            </button>
          ))}
        </div>
      </div>

      {/* Program Listings */}
      <div className="flex-1 flex flex-col overflow-hidden p-4">
        {selectedChannel && (
          <div className="flex items-center justify-between mb-4 pb-3 border-b border-white/[0.06]">
            <div>
              <h3 className="text-white font-bold text-base">{selectedChannel.name}</h3>
              <p className="text-white/40 text-xs">{selectedChannel.tv_archive_duration || 7} Days Provider Archive Available</p>
            </div>
          </div>
        )}

        {loading ? (
          <div className="flex-1 flex flex-col items-center justify-center text-white/40 text-xs gap-2">
            <RefreshCw size={24} className="animate-spin text-cyan-400" />
            <span>Loading broadcast archive...</span>
          </div>
        ) : error ? (
          <div className="flex-1 flex items-center justify-center text-red-300 text-xs p-4">
            <AlertCircle size={16} className="mr-2" />
            <span>{error}</span>
          </div>
        ) : programs.length === 0 ? (
          <div className="flex-1 flex flex-col items-center justify-center text-white/40 text-xs">
            <Clock size={36} className="mb-2 opacity-30 text-white" />
            <span>No past program listings available for this channel</span>
          </div>
        ) : (
          <div className="flex-1 overflow-y-auto space-y-2 pr-2">
            {programs.map((item, idx) => {
              const startTs = item.start_timestamp ? Number(item.start_timestamp) * 1000 : new Date(item.start).getTime();
              const stopTs = item.stop_timestamp ? Number(item.stop_timestamp) * 1000 : new Date(item.end).getTime();
              const durationMin = Math.max(5, Math.round((stopTs - startTs) / 60000));
              const isPast = stopTs < Date.now();

              return (
                <div
                  key={item.id || idx}
                  className="flex items-center justify-between p-3.5 rounded-xl bg-white/[0.02] border border-white/[0.06] hover:border-cyan-500/30 hover:bg-white/[0.04] transition-all"
                >
                  <div className="min-w-0 flex-1 mr-4">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-cyan-300 font-mono text-xs font-semibold">
                        {new Date(startTs).toLocaleDateString([], { month: 'short', day: 'numeric' })} • {new Date(startTs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })} – {new Date(stopTs).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                      </span>
                      <span className="text-white/30 text-xs">({durationMin} min)</span>
                      {isPast && (
                        <span className="text-[10px] bg-cyan-950 text-cyan-300 border border-cyan-800 px-1.5 py-0.2 rounded font-bold uppercase">
                          Archive
                        </span>
                      )}
                    </div>
                    <p className="text-white font-semibold text-sm truncate">{item.title}</p>
                    {item.description && (
                      <p className="text-white/40 text-xs line-clamp-1 mt-0.5">{item.description}</p>
                    )}
                  </div>

                  <div className="flex items-center gap-2 shrink-0">
                    <button
                      onClick={() => {
                        if (selectedChannel && onPlayCatchup) {
                          onPlayCatchup(selectedChannel, {
                            title: item.title,
                            startTime: new Date(startTs).toISOString(),
                            stopTime: new Date(stopTs).toISOString(),
                            description: item.description,
                          });
                        }
                      }}
                      className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-cyan-500 hover:bg-cyan-400 text-black font-bold text-xs shadow transition-all"
                    >
                      <Play size={14} fill="currentColor" /> Watch Replay
                    </button>
                    {selectedChannel && (
                      <RecordingButton
                        streamUrl={xtream.getCatchupStreamUrl(selectedChannel.stream_id, new Date(startTs), durationMin)}
                        channelName={selectedChannel.name}
                        programTitle={`[Catchup] ${item.title}`}
                        size="sm"
                        variant="icon"
                      />
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
};

export const DVRPage: React.FC<DVRPageProps> = ({
  channels,
  onPlayRecording,
  onPlayCatchup,
}) => {
  const [activeRecordings, setActiveRecordings] = useState<ActiveRecording[]>([]);
  const [library, setLibrary] = useState<RecordingMeta[]>([]);
  const [status, setStatus] = useState<DVRStatus | null>(null);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isStarting, setIsStarting] = useState(false);
  const [error, setError] = useState('');
  const [tab, setTab] = useState<'active' | 'library' | 'scheduled' | 'catchup'>('active');

  const [selectedChannelId, setSelectedChannelId] = useState<number | null>(null);
  const [programTitle, setProgramTitle] = useState('');
  const [durationMinutes, setDurationMinutes] = useState<number>(60);
  const [useDurationLimit, setUseDurationLimit] = useState(false);
  const [outputDirInput, setOutputDirInput] = useState('');
  // Searchable channel picker — avoids rendering 10k+ <option> nodes that freeze Chromium
  const [channelSearch, setChannelSearch] = useState('');
  const [showChannelList, setShowChannelList] = useState(false);
  const channelPickerRef = useRef<HTMLDivElement>(null);
  const lastStatusOutputDirRef = useRef('');

  const refreshStatus = useCallback(async () => {
    const result = await window.electronAPI?.invoke?.('dvr:status');
    if (result) setStatus(result as DVRStatus);
  }, []);

  const refreshActive = useCallback(async () => {
    const result = await window.electronAPI?.invoke?.('dvr:list-active');
    const recordings = (result?.recordings || []) as ActiveRecording[];
    setActiveRecordings(recordings);
  }, []);

  const refreshLibrary = useCallback(async () => {
    const list = await window.electronAPI?.invoke?.('dvr:list-library');
    setLibrary(Array.isArray(list) ? (list as RecordingMeta[]) : []);
  }, []);

  const refreshAll = useCallback(async () => {
    setIsRefreshing(true);
    setError('');
    try {
      await Promise.all([refreshStatus(), refreshActive(), refreshLibrary()]);
    } catch (err: any) {
      setError(err.message || 'Failed to refresh DVR status');
    } finally {
      setIsRefreshing(false);
    }
  }, [refreshActive, refreshLibrary, refreshStatus]);

  useEffect(() => {
    void refreshAll();

    const poll = setInterval(() => {
      void refreshActive();
      void refreshStatus();
    }, 2000);

    const onCompleted = () => {
      void refreshActive();
      void refreshLibrary();
      void refreshStatus();
    };

    window.electronAPI?.on?.('dvr:completed', onCompleted);

    return () => {
      clearInterval(poll);
      window.electronAPI?.off?.('dvr:completed', onCompleted);
    };
  }, [refreshActive, refreshAll, refreshLibrary, refreshStatus]);

  useEffect(() => {
    if (!status?.outputDir || status.outputDir === lastStatusOutputDirRef.current) {
      return;
    }

    lastStatusOutputDirRef.current = status.outputDir;
    setOutputDirInput(status.outputDir);
  }, [status?.outputDir]);

  const channelOptions = useMemo(
    () => channels.filter(ch => !!ch.stream_id && !!ch.name),
    [channels]
  );

  const selectedChannel = useMemo(
    () => channelOptions.find(ch => ch.stream_id === selectedChannelId) || null,
    [channelOptions, selectedChannelId]
  );

  // Filtered channels — capped at 60 results to keep dropdown fast
  const filteredChannels = useMemo(() => {
    const q = channelSearch.toLowerCase().trim();
    if (!q) return channelOptions.slice(0, 60);
    return channelOptions.filter(ch => ch.name.toLowerCase().includes(q)).slice(0, 60);
  }, [channelOptions, channelSearch]);

  const canStartRecording = !!selectedChannel && !!status && status.activeCount < status.maxConcurrent;

  const startRecording = useCallback(async () => {
    if (!selectedChannel || !status) return;

    const streamUrl = selectedChannel.direct_source?.trim()
      ? selectedChannel.direct_source
      : xtream.getLiveStreamUrl(selectedChannel.stream_id, 'ts');

    setIsStarting(true);
    setError('');

    try {
      const result = await window.electronAPI?.invoke?.('dvr:start', {
        streamUrl,
        channelName: selectedChannel.name,
        programTitle: programTitle.trim() || `Recording ${new Date().toLocaleTimeString()}`,
        durationSeconds: useDurationLimit ? Math.max(60, Math.floor(durationMinutes * 60)) : undefined,
      });

      if (!result?.success) {
        throw new Error(result?.error || 'Failed to start recording');
      }

      setProgramTitle('');
      setUseDurationLimit(false);
      setDurationMinutes(60);
      setSelectedChannelId(null);
      setChannelSearch('');
      await refreshAll();
      setTab('active');
    } catch (err: any) {
      setError(err.message || 'Failed to start recording');
    } finally {
      setIsStarting(false);
    }
  }, [selectedChannel, status, programTitle, useDurationLimit, durationMinutes, refreshAll]);

  const stopRecording = useCallback(async (id: string) => {
    await window.electronAPI?.invoke?.('dvr:stop', id);
    await refreshActive();
    await refreshStatus();
  }, [refreshActive, refreshStatus]);

  const stopAll = useCallback(async () => {
    await window.electronAPI?.invoke?.('dvr:stop-all');
    await refreshActive();
    await refreshStatus();
  }, [refreshActive, refreshStatus]);

  const deleteRecording = useCallback(async (id: string, deleteFile: boolean) => {
    await window.electronAPI?.invoke?.('dvr:delete', { recordingId: id, deleteFile });
    await refreshLibrary();
  }, [refreshLibrary]);

  const applyOutputDir = useCallback(async () => {
    const nextOutputDir = outputDirInput.trim();
    if (!nextOutputDir) {
      setError('Enter a recording folder path first');
      return;
    }

    setError('');
    const result = await window.electronAPI?.invoke?.('dvr:set-output-dir', nextOutputDir);
    if (!result?.success) {
      setError(result?.error || 'Failed to set recording folder');
      return;
    }

    setStatus(prev => prev ? { ...prev, outputDir: result.outputDir || nextOutputDir } : prev);
    await refreshStatus();
  }, [outputDirInput, refreshStatus]);

  const pickOutputDir = useCallback(async () => {
    try {
      const result = await window.electronAPI?.invoke?.('dialog:show-open', {
        properties: ['openDirectory', 'createDirectory'],
        title: 'Select DVR Recording Folder',
      });
      if (result && !result.canceled && Array.isArray(result.filePaths) && result.filePaths.length > 0) {
        const selectedDir = result.filePaths[0];
        setOutputDirInput(selectedDir);
        const res = await window.electronAPI?.invoke?.('dvr:set-output-dir', selectedDir);
        if (res?.success) {
          setStatus(prev => prev ? { ...prev, outputDir: res.outputDir || selectedDir } : prev);
          await refreshStatus();
        }
      }
    } catch (err: any) {
      setError(err.message || 'Failed to open directory picker');
    }
  }, [refreshStatus]);

  const openRecordingLocation = useCallback(async (outputPath: string) => {
    const folderPath = outputPath.replace(/[\\/][^\\/]+$/, '');
    await window.electronAPI?.invoke?.('shell:open-path', folderPath || outputPath);
  }, []);

  const openOutputDir = useCallback(async () => {
    const dir = status?.outputDir || outputDirInput.trim();
    if (!dir) return;
    await window.electronAPI?.invoke?.('shell:open-path', dir);
  }, [outputDirInput, status?.outputDir]);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06]">
        <h2 className="text-white font-semibold text-lg">DVR</h2>
        {status && (
          <span className="text-white/30 text-xs">
            {status.activeCount}/{status.maxConcurrent} active
          </span>
        )}

        <div className="flex-1" />

        <button
          onClick={() => setTab('active')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            tab === 'active' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/50 hover:text-white'
          }`}
        >
          Active
        </button>
        <button
          onClick={() => setTab('library')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            tab === 'library' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/50 hover:text-white'
          }`}
        >
          Library
        </button>
        <button
          onClick={() => setTab('scheduled')}
          className={`px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            tab === 'scheduled' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/50 hover:text-white'
          }`}
        >
          Scheduled
        </button>
        <button
          onClick={() => setTab('catchup')}
          className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-medium transition-colors ${
            tab === 'catchup' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/50 hover:text-white'
          }`}
        >
          <History size={13} />
          Provider Catch-up
        </button>

        <button
          onClick={() => void refreshAll()}
          className="p-2 rounded-lg text-white/50 hover:text-white hover:bg-white/[0.05] transition-colors"
          title="Refresh DVR"
        >
          <RefreshCw size={14} className={isRefreshing ? 'animate-spin' : ''} />
        </button>
      </div>

      <div className="px-4 py-3 border-b border-white/[0.06] grid grid-cols-1 lg:grid-cols-4 gap-3">
        <div className="rounded-xl border border-white/[0.08] bg-white/[0.02] p-3 lg:col-span-2">
          <div className="flex items-center gap-2 mb-2">
            <HardDrive size={16} className="text-cyan-300" />
            <div className="min-w-0">
              <p className="text-white/80 text-xs">Recording Folder</p>
              <p className="text-white/35 text-[11px]">Use your USB/media drive path here.</p>
            </div>
          </div>
          <div className="flex gap-2">
            <input
              value={outputDirInput}
              onChange={(e) => setOutputDirInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void applyOutputDir();
              }}
              placeholder={status?.outputDir || 'Example: E:\\DYLANDOS DVR'}
              className="min-w-0 flex-1 bg-white/[0.04] border border-white/[0.1] rounded-lg px-3 py-2 text-xs text-white placeholder:text-white/25"
            />
            <button
              onClick={() => void pickOutputDir()}
              className="px-3 py-2 rounded-lg bg-white/[0.08] border border-white/[0.12] text-white/80 text-xs font-semibold hover:bg-white/[0.15] hover:text-white transition-colors"
              title="Browse and select folder"
            >
              Browse...
            </button>
            <button
              onClick={() => void applyOutputDir()}
              className="px-3 py-2 rounded-lg bg-cyan-500/15 border border-cyan-500/25 text-cyan-200 text-xs font-semibold hover:bg-cyan-500/25 transition-colors"
            >
              Use Folder
            </button>
            <button
              onClick={() => void openOutputDir()}
              className="p-2 rounded-lg text-white/55 hover:text-white hover:bg-white/[0.06] transition-colors"
              title="Open recording folder in Explorer"
            >
              <FolderOpen size={14} />
            </button>
          </div>
        </div>

        <div className="rounded-xl border border-white/[0.08] bg-white/[0.02] p-3 flex items-center gap-3">
          <Radio size={16} className="text-cyan-300" />
          <div>
            <p className="text-white/80 text-xs">FFmpeg</p>
            <p className={`text-[11px] ${status?.ffmpegAvailable ? 'text-emerald-300' : 'text-red-300'}`}>
              {status?.ffmpegAvailable ? 'Available' : 'Not detected'}
            </p>
          </div>
        </div>

        <div className="rounded-xl border border-white/[0.08] bg-white/[0.02] p-3 flex items-center gap-3">
          <CheckCircle2 size={16} className="text-cyan-300" />
          <div>
            <p className="text-white/80 text-xs">Library Recordings</p>
            <p className="text-white/40 text-[11px]">{library.length}</p>
          </div>
        </div>
      </div>

      {status !== null && !status.ffmpegAvailable && (
        <div className="mx-4 mt-4 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2 flex items-center gap-2">
          <AlertCircle size={14} className="text-red-300 shrink-0" />
          <p className="text-red-200 text-xs">
            FFmpeg is not available. Install FFmpeg or bundle it to enable recording.
          </p>
        </div>
      )}

      {error && (
        <div className="mx-4 mt-4 rounded-xl border border-red-500/30 bg-red-500/10 px-3 py-2">
          <p className="text-red-200 text-xs">{error}</p>
        </div>
      )}

      <div className="px-4 py-4 border-b border-white/[0.06]">
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-5 gap-3">
          {/* Searchable channel picker — prevents 10k+ <option> DOM freeze */}
          <div ref={channelPickerRef} className="lg:col-span-2 relative">
            <div className="relative">
              <input
                type="text"
                placeholder={
                  channelOptions.length === 0
                    ? 'Load Live TV first…'
                    : `Search ${channelOptions.length.toLocaleString()} channels…`
                }
                value={channelSearch}
                onChange={(e) => {
                  setChannelSearch(e.target.value);
                  setShowChannelList(true);
                  setSelectedChannelId(null);
                }}
                onFocus={() => setShowChannelList(true)}
                onBlur={() => setTimeout(() => setShowChannelList(false), 160)}
                className="w-full bg-white/[0.04] border border-white/[0.1] rounded-xl px-3 py-2 pr-8 text-sm text-white placeholder:text-white/30 caret-cyan-300 focus:border-cyan-500/40 focus:outline-none transition-colors"
              />
              {selectedChannelId && (
                <button
                  type="button"
                  onMouseDown={() => { setSelectedChannelId(null); setChannelSearch(''); }}
                  className="absolute right-2.5 top-1/2 -translate-y-1/2 text-white/30 hover:text-white/80 text-base leading-none"
                  title="Clear"
                >
                  ×
                </button>
              )}
            </div>
            {showChannelList && filteredChannels.length > 0 && (
              <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-[#0c0c18] border border-white/[0.12] rounded-xl overflow-hidden shadow-2xl max-h-56 overflow-y-auto">
                {filteredChannels.map(ch => (
                  <button
                    key={ch.stream_id}
                    type="button"
                    onMouseDown={() => {
                      setSelectedChannelId(ch.stream_id);
                      setChannelSearch(ch.name);
                      setShowChannelList(false);
                    }}
                    className={`w-full px-3 py-2 text-left text-sm truncate transition-colors ${
                      ch.stream_id === selectedChannelId
                        ? 'bg-cyan-500/20 text-cyan-300'
                        : 'text-white/85 hover:bg-white/[0.06]'
                    }`}
                  >
                    {ch.num ? <span className="text-white/30 text-xs mr-1.5">{ch.num}</span> : null}
                    {ch.name}
                  </button>
                ))}
              </div>
            )}
            {showChannelList && channelOptions.length > 0 && filteredChannels.length === 0 && (
              <div className="absolute z-50 top-full left-0 right-0 mt-1 bg-[#0c0c18] border border-white/[0.12] rounded-xl px-3 py-3 text-white/30 text-sm shadow-2xl">
                No channels match &ldquo;{channelSearch}&rdquo;
              </div>
            )}
          </div>

          <input
            value={programTitle}
            onChange={(e) => setProgramTitle(e.target.value)}
            placeholder="Recording title"
            className="bg-white/[0.04] border border-white/[0.1] rounded-xl px-3 py-2 text-sm text-white placeholder:text-white/30"
          />

          <div className="flex items-center gap-2 rounded-xl border border-white/[0.1] bg-white/[0.03] px-3 py-2">
            <input
              id="limit-duration"
              type="checkbox"
              checked={useDurationLimit}
              onChange={(e) => setUseDurationLimit(e.target.checked)}
            />
            <label htmlFor="limit-duration" className="text-white/70 text-xs whitespace-nowrap">
              Auto-stop
            </label>
            <input
              type="number"
              min={1}
              value={durationMinutes}
              onChange={(e) => setDurationMinutes(Number(e.target.value) || 1)}
              disabled={!useDurationLimit}
              className="w-20 ml-auto bg-white/[0.04] border border-white/[0.1] rounded-lg px-2 py-1 text-xs text-white disabled:opacity-40"
            />
            <span className="text-white/40 text-xs">min</span>
          </div>

          <button
            onClick={() => void startRecording()}
            disabled={!canStartRecording || isStarting || !status?.ffmpegAvailable}
            className="neon-btn rounded-xl text-sm px-4 py-2 disabled:opacity-50 disabled:cursor-not-allowed flex items-center justify-center gap-2"
          >
            <Circle size={14} className={isStarting ? 'animate-pulse' : ''} />
            {isStarting ? 'Starting...' : 'Start Recording'}
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-4">
        {tab === 'active' && (
          <>
            {activeRecordings.length > 0 && (
              <div className="flex justify-end mb-3">
                <button
                  onClick={() => void stopAll()}
                  className="px-3 py-1.5 rounded-lg text-xs border border-red-500/30 text-red-300 hover:bg-red-500/10 transition-colors"
                >
                  Stop All
                </button>
              </div>
            )}

            {activeRecordings.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center">
                <Circle size={34} className="text-white/15 mb-3" />
                <p className="text-white/40 text-sm">No active recordings</p>
              </div>
            ) : (
              <div className="space-y-3">
                {activeRecordings.map(rec => (
                  <div key={rec.id} className="rounded-xl border border-red-500/20 bg-red-500/5 p-3 flex items-center gap-3">
                    <Circle size={12} className="text-red-400 animate-pulse shrink-0" />
                    <div className="flex-1 min-w-0">
                      <p className="text-white text-sm font-medium truncate">{rec.programTitle}</p>
                      <p className="text-white/50 text-xs truncate">{rec.channelName}</p>
                      <p className="text-white/30 text-[11px] mt-0.5 flex items-center gap-1">
                        <Clock size={11} /> {formatDuration(rec.elapsedSeconds)}
                      </p>
                    </div>
                    <button
                      onClick={() => void stopRecording(rec.id)}
                      className="px-3 py-1.5 rounded-lg text-xs border border-white/15 text-white/80 hover:bg-white/[0.06] transition-colors flex items-center gap-1"
                    >
                      <Square size={12} /> Stop
                    </button>
                  </div>
                ))}
              </div>
            )}
          </>
        )}

        {tab === 'library' && (
          <>
            {library.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-center">
                <Film size={34} className="text-white/15 mb-3" />
                <p className="text-white/40 text-sm">No recordings yet</p>
              </div>
            ) : (
              <div className="space-y-2">
                {library.map(rec => (
                  <div key={rec.id} className="rounded-xl border border-white/[0.08] bg-white/[0.02] p-3 flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-white/[0.05] flex items-center justify-center shrink-0">
                      <Film size={16} className="text-white/40" />
                    </div>
                    <div className="flex-1 min-w-0">
                      <p className="text-white/85 text-sm font-medium truncate">{rec.programTitle}</p>
                      <p className="text-white/40 text-xs truncate">{rec.channelName}</p>
                      <p className="text-white/25 text-[11px] mt-0.5">
                        {new Date(rec.startTime).toLocaleString()} · {formatBytes(rec.size)}
                      </p>
                    </div>

                    <button
                      onClick={() => onPlayRecording(rec.outputPath)}
                      className="p-2 rounded-lg text-white/60 hover:text-white hover:bg-white/[0.06] transition-colors"
                      title="Play recording"
                    >
                      <Play size={14} />
                    </button>

                    <button
                      onClick={() => void openRecordingLocation(rec.outputPath)}
                      className="p-2 rounded-lg text-white/60 hover:text-white hover:bg-white/[0.06] transition-colors"
                      title="Open folder"
                    >
                      <FolderOpen size={14} />
                    </button>

                    <button
                      onClick={() => void deleteRecording(rec.id, false)}
                      className="p-2 rounded-lg text-amber-300/70 hover:text-amber-300 hover:bg-amber-500/10 transition-colors"
                      title="Remove from library"
                    >
                      <Trash2 size={14} />
                    </button>

                    <button
                      onClick={() => void deleteRecording(rec.id, true)}
                      className="p-2 rounded-lg text-red-300/70 hover:text-red-300 hover:bg-red-500/10 transition-colors"
                      title="Delete recording file"
                    >
                      <Trash2 size={14} />
                    </button>
                  </div>
                ))}
              </div>
            )}
          </>
        )}
      </div>

      {tab === 'scheduled' && (
        <ScheduledTab channels={channels} />
      )}

      {tab === 'catchup' && (
        <CatchupTab channels={channels} onPlayCatchup={onPlayCatchup} />
      )}
    </div>
  );
};
