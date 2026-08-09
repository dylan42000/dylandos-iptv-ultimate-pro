// ─── DVR Scheduler Service ───────────────────────────────────────────────────
// Manages scheduled, series, and keyword-based DVR recordings.
// Persists to localStorage. Fires recordings via Electron IPC (electronAPI.dvr).

export type RecordingType = 'once' | 'series' | 'keyword';
export type RecordingStatus = 'pending' | 'recording' | 'completed' | 'failed' | 'cancelled';
export type ConflictAction = 'skip' | 'stop-lower-priority';

export interface ScheduledRecording {
  id: string;
  channelId: string;
  channelName: string;
  programTitle: string;
  streamUrl: string;
  startTimeMs: number;
  endTimeMs: number;
  preBufferSecs: number;    // default 120
  postBufferSecs: number;   // default 120
  recordingType: RecordingType;
  // Series recording
  seriesKeyword: string;    // match title keyword across EPG
  skipDuplicates: boolean;
  // Keyword recording
  keyword: string;          // auto-record any program containing this word
  keywordChannelIds: string[];  // [] = all channels
  // Priority
  priority: number;         // 1 (low) to 10 (high)
  conflictAction: ConflictAction;
  // Status
  status: RecordingStatus;
  createdAt: number;
  outputPath?: string;      // set after recording completes
}

export type ConflictResolution =
  | { type: 'scheduled' }
  | { type: 'conflict'; conflicting: ScheduledRecording[] }
  | { type: 'past'; reason: string };

export interface EPGProgramRef {
  channelId: string;
  channelName: string;
  programTitle: string;
  streamUrl: string;
  startTimeMs: number;
  endTimeMs: number;
}

const STORAGE_KEY = 'dylandos:dvr:scheduled';

class DvrSchedulerClass {
  private _schedules: ScheduledRecording[] = [];
  private timers: Map<string, ReturnType<typeof setTimeout>> = new Map();
  private loaded = false;
  private eventsBound = false;

  // ── Persistence ────────────────────────────────────────────────────────────

  load(): void {
    if (this.loaded) return;
    try {
      const raw = localStorage.getItem(STORAGE_KEY);
      this._schedules = raw ? JSON.parse(raw) : [];
    } catch {
      this._schedules = [];
    }
    this.loaded = true;
    if (!this.eventsBound && window.electronAPI?.on) {
      this.eventsBound = true;
      window.electronAPI.on('dvr:completed', (event: any) => {
        const id = event?.recordingId;
        if (!id) return;
        this.updateStatus(id, event?.success ? 'completed' : 'failed', event?.meta?.outputPath);
      });
    }
    // Re-arm timers for pending recordings
    this._schedules
      .filter(s => s.status === 'pending' && s.startTimeMs > Date.now())
      .forEach(s => this.armTimer(s));
  }

  save(): void {
    try {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(this._schedules));
    } catch (err) {
      console.warn('[dvrScheduler] Failed to save:', err);
    }
  }

  get schedules(): ScheduledRecording[] {
    this.load();
    return [...this._schedules];
  }

  // ── Schedule a recording ─────────────────────────────────────────────────

  schedule(recording: ScheduledRecording): ConflictResolution {
    this.load();

    // Reject if in the past
    if (recording.endTimeMs < Date.now()) {
      return { type: 'past', reason: 'Program has already ended' };
    }

    // Detect conflicts with other pending recordings
    const conflicts = this.detectConflicts(recording);
    if (conflicts.length > 0) {
      if (recording.conflictAction === 'skip') {
        return { type: 'conflict', conflicting: conflicts };
      }
      // Stop lower-priority conflicting recordings
      conflicts
        .filter(c => c.priority < recording.priority)
        .forEach(c => this.cancel(c.id));
    }

    this._schedules.push(recording);
    this.save();
    this.armTimer(recording);
    return { type: 'scheduled' };
  }

  // ── Cancel a recording ────────────────────────────────────────────────────

  cancel(id: string): void {
    this.load();
    const recording = this._schedules.find(s => s.id === id);
    if (!recording) return;

    // Clear timer if pending
    const timer = this.timers.get(id);
    if (timer) {
      clearTimeout(timer);
      this.timers.delete(id);
    }

    // Stop active recording via IPC
    if (recording.status === 'recording') {
      window.electronAPI?.dvr?.stopRecording?.(id).catch(() => {});
    }

    recording.status = 'cancelled';
    this.save();
  }

  // ── Delete a recording ────────────────────────────────────────────────────

  remove(id: string): void {
    this.load();
    this.cancel(id);
    this._schedules = this._schedules.filter(s => s.id !== id);
    this.save();
  }

  // ── Update status (called by IPC events from Electron) ───────────────────

  updateStatus(id: string, status: RecordingStatus, outputPath?: string): void {
    this.load();
    const rec = this._schedules.find(s => s.id === id);
    if (rec) {
      rec.status = status;
      if (outputPath) rec.outputPath = outputPath;
      this.save();
    }
  }

  // ── Conflict detection ────────────────────────────────────────────────────

  /** Public preview for UI before committing a schedule. */
  previewConflicts(incoming: Pick<ScheduledRecording, 'id' | 'startTimeMs' | 'endTimeMs'>): ScheduledRecording[] {
    this.load();
    return this.detectConflicts(incoming);
  }

  private detectConflicts(
    incoming: Pick<ScheduledRecording, 'id' | 'startTimeMs' | 'endTimeMs'>
  ): ScheduledRecording[] {
    return this._schedules.filter(s =>
      (s.status === 'pending' || s.status === 'recording') &&
      s.id !== incoming.id &&
      s.startTimeMs < incoming.endTimeMs &&
      incoming.startTimeMs < s.endTimeMs
    );
  }

  /** Force-schedule after user resolves conflicts (cancels lower-priority or all conflicting). */
  scheduleForce(
    recording: ScheduledRecording,
    mode: 'cancel-conflicts' | 'stop-lower-priority' = 'cancel-conflicts'
  ): ConflictResolution {
    this.load();
    if (recording.endTimeMs < Date.now()) {
      return { type: 'past', reason: 'Program has already ended' };
    }

    const conflicts = this.detectConflicts(recording);
    if (mode === 'stop-lower-priority') {
      conflicts
        .filter(c => c.priority < recording.priority)
        .forEach(c => this.cancel(c.id));
      const remaining = this.detectConflicts(recording);
      if (remaining.length > 0) {
        return { type: 'conflict', conflicting: remaining };
      }
    } else {
      conflicts.forEach(c => this.cancel(c.id));
    }

    this._schedules.push(recording);
    this.save();
    this.armTimer(recording);
    return { type: 'scheduled' };
  }

  // ── Timer management ─────────────────────────────────────────────────────

  private armTimer(recording: ScheduledRecording): void {
    const msUntilStart = recording.startTimeMs - Date.now() - (recording.preBufferSecs * 1000);
    if (msUntilStart <= 0) {
      // Should start immediately (or was due while app was closed)
      this.startRecording(recording);
      return;
    }

    const timer = setTimeout(() => {
      this.startRecording(recording);
    }, msUntilStart);

    this.timers.set(recording.id, timer);
  }

  private async startRecording(recording: ScheduledRecording): Promise<void> {
    // Re-check conflicts at fire time (another recording may have started)
    const liveConflicts = this.detectConflicts(recording).filter(c => c.status === 'recording' || c.status === 'pending');
    if (liveConflicts.length > 0) {
      if (recording.conflictAction === 'skip') {
        console.warn('[dvrScheduler] Skipping due to conflict at start:', recording.id);
        this.updateStatus(recording.id, 'cancelled');
        return;
      }
      liveConflicts
        .filter(c => c.priority < recording.priority)
        .forEach(c => this.cancel(c.id));
      const still = this.detectConflicts(recording).filter(c => c.status === 'recording');
      if (still.length > 0 && recording.priority <= Math.max(...still.map(c => c.priority))) {
        this.updateStatus(recording.id, 'cancelled');
        return;
      }
    }

    const api = window.electronAPI;
    const startViaFacade = api?.dvr?.startRecording?.bind(api.dvr);
    const startViaInvoke = api?.invoke
      ? (opts: {
          recordingId: string;
          streamUrl: string;
          channelName: string;
          programTitle: string;
          durationSeconds: number;
        }) => api.invoke('dvr:start', opts)
      : null;

    if (!startViaFacade && !startViaInvoke) {
      console.warn('[dvrScheduler] Electron DVR IPC not available');
      this.updateStatus(recording.id, 'failed');
      return;
    }

    try {
      // Prefer status check so we respect FFmpeg concurrency limits
      const status = await (api?.dvr?.getStatus?.() ?? api?.invoke?.('dvr:status'));
      if (status && typeof status.activeCount === 'number' && typeof status.maxConcurrent === 'number') {
        if (status.activeCount >= status.maxConcurrent) {
          console.warn('[dvrScheduler] Max concurrent recordings reached');
          this.updateStatus(recording.id, 'failed');
          return;
        }
        if (status.ffmpegAvailable === false) {
          this.updateStatus(recording.id, 'failed');
          return;
        }
      }

      const durationSecs =
        (recording.endTimeMs - recording.startTimeMs) / 1000
        + recording.preBufferSecs
        + recording.postBufferSecs;

      const payload = {
        recordingId: recording.id,
        streamUrl: recording.streamUrl,
        channelName: recording.channelName,
        programTitle: recording.programTitle,
        durationSeconds: durationSecs,
      };

      const result = startViaFacade
        ? await startViaFacade(payload)
        : await startViaInvoke!(payload);

      if (result && result.success === false) {
        throw new Error(result.error || 'dvr:start failed');
      }

      this.updateStatus(recording.id, 'recording');
    } catch (err) {
      console.error('[dvrScheduler] Failed to start recording:', err);
      this.updateStatus(recording.id, 'failed');
    }
  }

  // ── Keyword scan ─────────────────────────────────────────────────────────
  // Call after every EPG refresh to auto-schedule keyword-matched programs.

  scanForKeywords(
    programs: EPGProgramRef[],
    onScheduled?: (recording: ScheduledRecording) => void
  ): number {
    this.load();
    const keywordRules = this._schedules.filter(s => s.recordingType === 'keyword');
    let count = 0;

    for (const rule of keywordRules) {
      const kw = rule.keyword.toLowerCase();
      const matchingPrograms = programs.filter(p => {
        const titleMatch = p.programTitle.toLowerCase().includes(kw);
        const channelMatch =
          rule.keywordChannelIds.length === 0 ||
          rule.keywordChannelIds.includes(p.channelId);
        const isFuture = p.startTimeMs > Date.now();
        return titleMatch && channelMatch && isFuture;
      });

      for (const program of matchingPrograms) {
        if (this.alreadyScheduled(program.programTitle, program.startTimeMs)) continue;

        const newRec: ScheduledRecording = {
          id: crypto.randomUUID(),
          channelId: program.channelId,
          channelName: program.channelName,
          programTitle: program.programTitle,
          streamUrl: program.streamUrl,
          startTimeMs: program.startTimeMs,
          endTimeMs: program.endTimeMs,
          preBufferSecs: 120,
          postBufferSecs: 120,
          recordingType: 'keyword',
          seriesKeyword: '',
          skipDuplicates: true,
          keyword: rule.keyword,
          keywordChannelIds: rule.keywordChannelIds,
          priority: rule.priority,
          conflictAction: rule.conflictAction,
          status: 'pending',
          createdAt: Date.now(),
        };

        const result = this.schedule(newRec);
        if (result.type === 'scheduled') {
          count++;
          onScheduled?.(newRec);
        }
      }
    }

    return count;
  }

  /**
   * Expand series rules beyond raw keyword includes():
   * - Normalize titles (strip SxxExx / NxNN / Ep.N / quality tags)
   * - Prefer same channel as the original series rule
   * - Score exact/normalized/fuzzy matches; skip duplicates via skipDuplicates
   */
  scanForSeriesRules(
    programs: EPGProgramRef[],
    onScheduled?: (recording: ScheduledRecording) => void
  ): number {
    this.load();
    const seriesRules = this._schedules.filter(s => s.recordingType === 'series' && s.seriesKeyword);
    let count = 0;
    const now = Date.now();

    for (const rule of seriesRules) {
      const needle = normalizeSeriesTitle(rule.seriesKeyword || rule.programTitle);
      if (!needle) continue;

      const scored = programs
        .filter(p => p.startTimeMs > now)
        .map(p => {
          const hay = normalizeSeriesTitle(p.programTitle);
          const score = scoreSeriesMatch(needle, hay, p.programTitle);
          const sameChannel = p.channelId === rule.channelId ? 15 : 0;
          return { program: p, score: score + sameChannel, sameChannel: sameChannel > 0 };
        })
        .filter(row => row.score >= 40)
        .sort((a, b) => b.score - a.score || (b.sameChannel === a.sameChannel ? 0 : b.sameChannel ? 1 : -1));

      for (const { program } of scored) {
        // Prefer same-channel hits; allow other channels only when score is strong
        const strong = scoreSeriesMatch(needle, normalizeSeriesTitle(program.programTitle), program.programTitle) >= 70;
        if (program.channelId !== rule.channelId && !strong) continue;

        if (this.alreadyScheduled(program.programTitle, program.startTimeMs)) continue;
        if (rule.skipDuplicates && this.alreadyRecordedSeriesEpisode(needle, program.programTitle, program.startTimeMs)) {
          continue;
        }

        const newRec: ScheduledRecording = {
          id: crypto.randomUUID(),
          channelId: program.channelId,
          channelName: program.channelName,
          programTitle: program.programTitle,
          streamUrl: program.streamUrl,
          startTimeMs: program.startTimeMs,
          endTimeMs: program.endTimeMs,
          preBufferSecs: rule.preBufferSecs || 120,
          postBufferSecs: rule.postBufferSecs || 120,
          recordingType: 'series',
          seriesKeyword: rule.seriesKeyword,
          skipDuplicates: rule.skipDuplicates,
          keyword: '',
          keywordChannelIds: [],
          priority: rule.priority,
          conflictAction: rule.conflictAction,
          status: 'pending',
          createdAt: Date.now(),
        };

        const result = this.schedule(newRec);
        if (result.type === 'scheduled') {
          count++;
          onScheduled?.(newRec);
        }
      }
    }

    return count;
  }

  /** Run keyword + series expansion after EPG refresh. */
  expandRulesFromEpg(
    programs: EPGProgramRef[],
    onScheduled?: (recording: ScheduledRecording) => void
  ): { keyword: number; series: number } {
    return {
      keyword: this.scanForKeywords(programs, onScheduled),
      series: this.scanForSeriesRules(programs, onScheduled),
    };
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private alreadyScheduled(title: string, startTimeMs: number): boolean {
    return this._schedules.some(
      s => s.programTitle === title && Math.abs(s.startTimeMs - startTimeMs) < 60_000
    );
  }

  private alreadyRecordedSeriesEpisode(
    seriesKey: string,
    title: string,
    startTimeMs: number
  ): boolean {
    const epKey = episodeIdentity(title);
    return this._schedules.some(s => {
      if (s.recordingType !== 'series' && s.recordingType !== 'once') return false;
      if (s.status === 'cancelled' || s.status === 'failed') return false;
      const sameSeries = normalizeSeriesTitle(s.seriesKeyword || s.programTitle) === seriesKey
        || normalizeSeriesTitle(s.programTitle) === seriesKey;
      if (!sameSeries) return false;
      if (epKey && episodeIdentity(s.programTitle) === epKey) return true;
      // Same calendar day airing of identically-normalized title
      return normalizeSeriesTitle(s.programTitle) === normalizeSeriesTitle(title)
        && Math.abs(s.startTimeMs - startTimeMs) < 36 * 60 * 60 * 1000;
    });
  }

  getPending(): ScheduledRecording[] {
    this.load();
    return this._schedules.filter(s => s.status === 'pending');
  }

  getCompleted(): ScheduledRecording[] {
    this.load();
    return this._schedules.filter(s => s.status === 'completed');
  }

  dispose(): void {
    this.timers.forEach(t => clearTimeout(t));
    this.timers.clear();
  }
}

/** Strip episode markers / quality tags so "Show S02E03 HD" → "show". */
export function normalizeSeriesTitle(title: string): string {
  return String(title || '')
    .toLowerCase()
    .replace(/\b(s\d{1,2}e\d{1,3}|s\d{1,2}\s*e\d{1,3}|\d{1,2}x\d{1,3})\b/gi, ' ')
    .replace(/\b(ep(?:isode)?\.?\s*\d{1,3})\b/gi, ' ')
    .replace(/\b(part|pt)\.?\s*\d{1,2}\b/gi, ' ')
    .replace(/\b(hd|fhd|uhd|4k|8k|hevc|h265|h264|hdr|sd|720p|1080p|2160p)\b/gi, ' ')
    .replace(/\b\d{4}[-/.]\d{1,2}[-/.]\d{1,2}\b/g, ' ')
    .replace(/[^a-z0-9]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function episodeIdentity(title: string): string | null {
  const m =
    title.match(/\b(s\d{1,2}e\d{1,3})\b/i) ||
    title.match(/\b(\d{1,2}x\d{1,3})\b/i) ||
    title.match(/\b(ep(?:isode)?\.?\s*\d{1,3})\b/i);
  return m ? m[1].toLowerCase().replace(/\s+/g, '') : null;
}

function scoreSeriesMatch(needle: string, hayNormalized: string, rawTitle: string): number {
  if (!needle || !hayNormalized) return 0;
  if (hayNormalized === needle) return 100;
  if (hayNormalized.startsWith(needle) || needle.startsWith(hayNormalized)) return 85;
  if (hayNormalized.includes(needle)) return 70;
  const needleTokens = needle.split(' ').filter(Boolean);
  const hayTokens = new Set(hayNormalized.split(' ').filter(Boolean));
  if (needleTokens.length === 0) return 0;
  const hit = needleTokens.filter(t => hayTokens.has(t)).length;
  const ratio = hit / needleTokens.length;
  if (ratio >= 0.85) return 65;
  if (ratio >= 0.6) return 50;
  if (rawTitle.toLowerCase().includes(needle)) return 45;
  return 0;
}

export const dvrScheduler = new DvrSchedulerClass();
export { DvrSchedulerClass };
