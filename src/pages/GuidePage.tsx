import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  Calendar,
  Clock,
  Filter,
  Heart,
  Info,
  Play,
  Radio,
  RefreshCw,
  Search,
  X,
  ChevronDown,
  Circle,
  WifiOff,
} from 'lucide-react';
import { EpgGrid } from '../components/EpgGrid';
import { RecordingButton } from '../components/RecordingButton';
import { useLogoUrl } from '../services/logoCache';
import { useChannelEpg } from '../hooks/useChannelEpg';
import type { AppSettings } from '../types/settings';
import type { EPGData, EPGProgram } from '../types/epg';
import type { XtreamCategory, XtreamChannel } from '../types/xtream';

interface GuidePageProps {
  channels: XtreamChannel[];
  categories: XtreamCategory[];
  epgData: EPGData | null;
  favorites: string[];
  settings: AppSettings;
  isEpgLoading?: boolean;
  activeCategoryId?: string | null;
  onCategoryChange?: (categoryId: string) => void;
  onChannelSelect: (channel: XtreamChannel) => void;
  onToggleFavorite: (id: string) => void;
  onPlayCatchup?: (channel: XtreamChannel, program: EPGProgram) => void;
  onLoadEpg?: () => void;
}

interface SelectedProgramState {
  channel: XtreamChannel;
  program: EPGProgram;
}

function isProgramLive(program: EPGProgram, currentTime: Date): boolean {
  const start = new Date(program.startTime).getTime();
  const end = new Date(program.stopTime).getTime();
  const now = currentTime.getTime();
  return now >= start && now < end;
}

function getProgramProgress(program: EPGProgram, currentTime: Date): number {
  const start = new Date(program.startTime).getTime();
  const end = new Date(program.stopTime).getTime();
  const duration = Math.max(1, end - start);
  return Math.min(1, Math.max(0, (currentTime.getTime() - start) / duration));
}

function formatTimeFull(date: Date, timeFormat: '12h' | '24h'): string {
  return date.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    hour12: timeFormat === '12h',
  });
}

const ProgramDetail: React.FC<{
  selected: SelectedProgramState;
  currentTime: Date;
  timeFormat: '12h' | '24h';
  isFavorite: boolean;
  onClose: () => void;
  onPlay: () => void;
  onToggleFavorite: () => void;
}> = ({
  selected,
  currentTime,
  timeFormat,
  isFavorite,
  onClose,
  onPlay,
  onToggleFavorite,
}) => {
  const logoUrl = useLogoUrl(selected.channel.stream_icon || '');
  const live = isProgramLive(selected.program, currentTime);
  const progress = getProgramProgress(selected.program, currentTime);
  const hasCatchup = selected.channel.tv_archive === 1 && !live;

  return (
    <div
      className="fixed inset-0 z-[300] flex items-center justify-center bg-black/70 p-6 backdrop-blur-sm"
      onClick={onClose}
    >
      <div
        className="w-full max-w-3xl overflow-hidden rounded-3xl border border-white/[0.08] bg-[#070a12] shadow-[0_30px_80px_rgba(0,0,0,0.55)]"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="relative overflow-hidden border-b border-white/[0.08] bg-[radial-gradient(circle_at_top_left,_rgba(34,211,238,0.22),_transparent_42%),radial-gradient(circle_at_top_right,_rgba(236,72,153,0.18),_transparent_36%),linear-gradient(180deg,_rgba(255,255,255,0.04),_rgba(255,255,255,0))] p-8">
          <button
            onClick={onClose}
            className="absolute right-5 top-5 rounded-full border border-white/[0.08] bg-black/20 p-2 text-white/50 transition-colors hover:text-white"
          >
            <X size={18} />
          </button>

          <div className="flex items-end gap-6">
            <div className="flex h-24 w-24 items-center justify-center overflow-hidden rounded-2xl bg-black/35 ring-1 ring-white/[0.08]">
              {logoUrl ? (
                <img src={logoUrl} alt="" className="h-full w-full object-contain p-3" />
              ) : (
                <Radio size={28} className="text-white/25" />
              )}
            </div>

            <div className="min-w-0 flex-1">
              <p className="mb-2 text-xs font-semibold uppercase tracking-[0.28em] text-cyan-300/80">
                DYLANDOS GUIDE
              </p>
              <h2 className="line-clamp-2 text-3xl font-bold text-white">{selected.program.title}</h2>
              <div className="mt-3 flex flex-wrap items-center gap-3 text-sm text-white/55">
                <span className="inline-flex items-center gap-2">
                  <Radio size={14} className="text-cyan-300" />
                  {selected.channel.name}
                </span>
                <span className="inline-flex items-center gap-2">
                  <Clock size={14} />
                  {formatTimeFull(new Date(selected.program.startTime), timeFormat)} - {formatTimeFull(new Date(selected.program.stopTime), timeFormat)}
                </span>
                <span className="inline-flex items-center gap-2">
                  <Calendar size={14} />
                  {new Date(selected.program.startTime).toLocaleDateString()}
                </span>
              </div>
            </div>
          </div>
        </div>

        <div className="space-y-6 p-8">
          <div className="flex flex-wrap items-center gap-3 text-xs font-semibold uppercase tracking-[0.18em]">
            {live ? (
              <span className="inline-flex items-center gap-2 rounded-full border border-red-500/35 bg-red-500/15 px-3 py-1.5 text-red-300">
                <span className="h-2 w-2 rounded-full bg-red-400 animate-pulse" />
                Live Now
              </span>
            ) : hasCatchup ? (
              <span className="rounded-full border border-cyan-400/25 bg-cyan-400/10 px-3 py-1.5 text-cyan-300">
                Catchup Available
              </span>
            ) : (
              <span className="rounded-full border border-white/[0.08] bg-white/[0.04] px-3 py-1.5 text-white/45">
                Playback will tune to the live channel
              </span>
            )}
          </div>

          <div className="rounded-2xl border border-white/[0.08] bg-white/[0.03] p-5 text-white/70">
            <p className="text-sm leading-7">
              {selected.program.description || 'No description is available for this listing yet.'}
            </p>
          </div>

          {live && (
            <div className="rounded-2xl border border-cyan-400/15 bg-cyan-400/6 p-5">
              <div className="mb-2 flex items-center justify-between text-xs font-medium text-white/45">
                <span>Live progress</span>
                <span>{Math.round(progress * 100)}%</span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-white/[0.06]">
                <div
                  className="h-full rounded-full bg-gradient-to-r from-cyan-400 to-sky-300"
                  style={{ width: `${progress * 100}%` }}
                />
              </div>
            </div>
          )}

          <div className="flex items-center gap-3">
            <button
              onClick={onPlay}
              className="flex h-12 flex-1 items-center justify-center gap-2 rounded-2xl bg-white text-sm font-bold text-black transition-colors hover:bg-cyan-300"
            >
              <Play size={16} fill="currentColor" />
              {live ? 'Watch Now' : hasCatchup ? 'Play Catchup' : 'Tune Channel'}
            </button>
            <RecordingButton 
              channel={selected.channel} 
              program={selected.program} 
              size="lg" 
              variant="icon"
              className="shrink-0"
            />
            <button
              onClick={onToggleFavorite}
              className={`flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl border transition-colors ${
                isFavorite
                  ? 'border-red-500/35 bg-red-500/15 text-red-300'
                  : 'border-white/[0.08] bg-white/[0.04] text-white/45 hover:text-white'
              }`}
            >
              <Heart size={18} fill={isFavorite ? 'currentColor' : 'none'} />
            </button>
          </div>
        </div>
      </div>
    </div>
  );
};

export const GuidePage: React.FC<GuidePageProps> = ({
  channels,
  categories,
  epgData,
  favorites,
  settings,
  isEpgLoading = false,
  activeCategoryId = null,
  onCategoryChange,
  onChannelSelect,
  onToggleFavorite,
  onPlayCatchup,
  onLoadEpg,
}) => {
  const [currentTime, setCurrentTime] = useState(() => new Date());
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(activeCategoryId);
  const [showCategories, setShowCategories] = useState(false);
  const [favoritesOnly, setFavoritesOnly] = useState(false);
  const [selectedProgram, setSelectedProgram] = useState<SelectedProgramState | null>(null);

  useEffect(() => {
    if (activeCategoryId && activeCategoryId !== selectedCategoryId) {
      setSelectedCategoryId(activeCategoryId);
    }
  }, [activeCategoryId, selectedCategoryId]);

  useEffect(() => {
    if (!selectedCategoryId && categories[0]?.category_id) {
      const first = String(categories[0].category_id);
      setSelectedCategoryId(first);
      onCategoryChange?.(first);
    }
  }, [categories, selectedCategoryId, onCategoryChange]);

  // ── Category dropdown: use fixed positioning to escape overflow-hidden parent ──
  const filterBtnRef = useRef<HTMLButtonElement>(null);
  const [dropdownPos, setDropdownPos] = useState<{ top: number; right: number } | null>(null);

  const openDropdown = useCallback(() => {
    if (filterBtnRef.current) {
      const rect = filterBtnRef.current.getBoundingClientRect();
      setDropdownPos({ top: rect.bottom + 6, right: window.innerWidth - rect.right });
    }
    setShowCategories(true);
  }, []);

  const closeDropdown = useCallback(() => {
    setShowCategories(false);
    setDropdownPos(null);
  }, []);

  const selectCategory = useCallback((categoryId: string) => {
    setSelectedCategoryId(categoryId);
    onCategoryChange?.(categoryId);
    closeDropdown();
  }, [onCategoryChange, closeDropdown]);

  // ── G key shortcut: toggle category dropdown ────────────────────────
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLInputElement || event.target instanceof HTMLTextAreaElement) return;
      if (event.key === 'g' || event.key === 'G') {
        event.preventDefault();
        if (showCategories) closeDropdown(); else openDropdown();
      }
    };
    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [showCategories, openDropdown, closeDropdown]);

  useEffect(() => {
    const timer = window.setInterval(() => setCurrentTime(new Date()), 30000);
    return () => {
      window.clearInterval(timer);
    };
  }, []);

  const timeFormat = settings.epgTimeFormat || '24h';

  const filteredChannels = useMemo(() => {
    const normalizedQuery = searchQuery.trim().toLowerCase();

    return channels.filter((channel) => {
      if (favoritesOnly && !favorites.includes(String(channel.stream_id))) {
        return false;
      }
      if (normalizedQuery && !channel.name?.toLowerCase().includes(normalizedQuery)) {
        return false;
      }
      return true;
    });
  }, [channels, favorites, searchQuery, favoritesOnly]);

  const { getProgramsForChannel } = useChannelEpg(
    epgData,
    filteredChannels,
    settings.epgEnabled !== false
  );

  const selectedCategory = useMemo(
    () => categories.find((category) => category.category_id === selectedCategoryId),
    [categories, selectedCategoryId]
  );

  const guideStats = useMemo(() => {
    let channelsWithGuide = 0;
    let programCount = 0;

    for (const channel of filteredChannels) {
      const programs = getProgramsForChannel(channel);
      if (programs.length > 0) {
        channelsWithGuide += 1;
        programCount += programs.length;
      }
    }

    return { channelsWithGuide, programCount };
  }, [filteredChannels, getProgramsForChannel]);

  const handleProgramClick = useCallback((program: EPGProgram, channel: XtreamChannel) => {
    setSelectedProgram({ program, channel });
  }, []);

  const handlePlaySelectedProgram = useCallback(() => {
    if (!selectedProgram) {
      return;
    }

    const live = isProgramLive(selectedProgram.program, currentTime);

    if (live || selectedProgram.channel.tv_archive !== 1 || !onPlayCatchup) {
      onChannelSelect(selectedProgram.channel);
    } else {
      onPlayCatchup(selectedProgram.channel, selectedProgram.program);
    }

    setSelectedProgram(null);
  }, [currentTime, onChannelSelect, onPlayCatchup, selectedProgram]);

  const selectedProgramFavorite = selectedProgram
    ? favorites.includes(String(selectedProgram.channel.stream_id))
    : false;

  return (
    <div className="flex h-full flex-col overflow-hidden bg-[#05070d]">
      {/* ── FIXED-POSITION DROPDOWN — rendered at root level to escape any backdrop-filter stacking context ── */}
      {showCategories && dropdownPos && (
        <>
          <div
            className="fixed inset-0 z-[150]"
            onClick={closeDropdown}
          />
          <div
            className="fixed z-[200] max-h-96 w-72 overflow-y-auto rounded-2xl border border-white/[0.12] bg-[#0a0e18] p-2 shadow-[0_20px_60px_rgba(0,0,0,0.9)]"
            style={{
              top: dropdownPos.top,
              right: dropdownPos.right,
              scrollbarWidth: 'thin',
              scrollbarColor: 'rgba(255,255,255,0.2) transparent',
            }}
          >
            {categories.map((category) => (
              <button
                key={category.category_id}
                onClick={() => selectCategory(String(category.category_id))}
                className={`w-full rounded-xl px-3 py-2.5 text-left text-sm font-medium transition-all ${
                  String(selectedCategoryId) === String(category.category_id)
                    ? 'bg-gradient-to-r from-cyan-500/20 to-blue-500/20 text-cyan-300 border border-cyan-400/30'
                    : 'text-white/80 hover:bg-white/[0.08] hover:text-white border border-transparent'
                }`}
              >
                {category.category_name}
              </button>
            ))}
          </div>
        </>
      )}

      {/* ── Header bar — NOTE: no backdrop-blur here so it does NOT trap fixed children ── */}
      <div className="flex flex-wrap items-center gap-3 border-b border-white/[0.06] bg-black/30 px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <Calendar size={16} className="text-cyan-300" />
            <h2 className="text-lg font-semibold text-white">Guide</h2>
          </div>
          <p className="mt-1 text-xs text-white/35">
            {guideStats.channelsWithGuide} channels with listings · {guideStats.programCount.toLocaleString()} programs
          </p>
        </div>

        <div className="flex-1" />

        <div className="relative w-full max-w-xs">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
          <input
            type="text"
            value={searchQuery}
            onChange={(event) => setSearchQuery(event.target.value)}
            placeholder="Search channels..."
            className="w-full rounded-2xl border border-white/[0.08] bg-white/[0.04] py-2 pl-9 pr-3 text-sm text-white placeholder:text-white/20 focus:border-cyan-400/35 focus:outline-none"
          />
        </div>

        {/* ── Category filter button — dropdown rendered at viewport root to escape stacking contexts ── */}
        <div className="relative">
          <button
            ref={filterBtnRef}
            onClick={() => (showCategories ? closeDropdown() : openDropdown())}
            className="flex items-center gap-2 rounded-2xl border border-white/[0.08] bg-white/[0.04] px-3 py-2 text-sm text-white/70 transition-colors hover:text-white hover:border-white/[0.15]"
          >
            <Filter size={14} />
            <span>{selectedCategory?.category_name || 'Select category'}</span>
            <ChevronDown size={14} className={`transition-transform ${showCategories ? 'rotate-180' : ''}`} />
          </button>
        </div>

        <button
          onClick={() => setFavoritesOnly((current) => !current)}
          className={`flex items-center gap-2 rounded-2xl border px-3 py-2 text-sm transition-colors ${
            favoritesOnly
              ? 'border-red-500/35 bg-red-500/15 text-red-300'
              : 'border-white/[0.08] bg-white/[0.04] text-white/60 hover:text-white'
          }`}
        >
          <Heart size={14} fill={favoritesOnly ? 'currentColor' : 'none'} />
          Favorites
        </button>
      </div>

      {/* ── EPG Status Banner ───────────────────────────────────────────── */}
      {isEpgLoading && (
        <div className="flex items-center gap-2 border-b border-cyan-400/12 bg-cyan-400/6 px-4 py-2 text-xs text-cyan-200/80">
          <RefreshCw size={14} className="animate-spin" />
          Guide data is loading in the background. Channels stay usable while XMLTV finishes parsing.
        </div>
      )}
      {!isEpgLoading && epgData && guideStats.channelsWithGuide === 0 && channels.length > 0 && (
        <div className="flex items-center gap-2 border-b border-amber-400/12 bg-amber-400/6 px-4 py-2 text-xs text-amber-200/80">
          <WifiOff size={14} />
          EPG loaded but no channel listings matched. Check your EPG source in Settings.
          {onLoadEpg && (
            <button
              onClick={onLoadEpg}
              className="ml-2 flex items-center gap-1 rounded-lg border border-amber-400/30 bg-amber-400/10 px-2 py-0.5 text-xs text-amber-300 hover:bg-amber-400/20 transition-colors"
            >
              <RefreshCw size={10} />
              Refresh
            </button>
          )}
        </div>
      )}
      {!isEpgLoading && !epgData && settings.epgEnabled && (
        <div className="flex items-center gap-2 border-b border-cyan-400/12 bg-cyan-400/6 px-4 py-2 text-xs text-cyan-200/80">
          <Info size={14} />
          Guide data not loaded yet.
          {onLoadEpg && (
            <button
              onClick={onLoadEpg}
              className="ml-2 flex items-center gap-1 rounded-lg border border-cyan-400/30 bg-cyan-400/10 px-2 py-0.5 text-xs text-cyan-300 hover:bg-cyan-400/20 transition-colors"
            >
              <RefreshCw size={10} />
              Load EPG
            </button>
          )}
        </div>
      )}
      {!isEpgLoading && !settings.epgEnabled && (
        <div className="flex items-center gap-2 border-b border-white/[0.06] bg-white/[0.02] px-4 py-2 text-xs text-white/40">
          <Info size={14} />
          EPG guide is disabled. Enable it in Settings → EPG to see program listings.
        </div>
      )}

      <div className="flex-1 overflow-hidden">
        {filteredChannels.length === 0 ? (
          <div className="flex h-full items-center justify-center">
            <div className="text-center">
              <Search size={36} className="mx-auto mb-3 text-white/10" />
              <p className="text-sm text-white/30">No channels match the current guide filters.</p>
            </div>
          </div>
        ) : (
          <EpgGrid
            channels={filteredChannels}
            getProgramsForChannel={getProgramsForChannel}
            onChannelClick={onChannelSelect}
            onProgramClick={handleProgramClick}
            onPlayCatchup={onPlayCatchup}
            currentTime={currentTime}
            timeFormat={timeFormat}
          />
        )}
      </div>

      {selectedProgram && (
        <ProgramDetail
          selected={selectedProgram}
          currentTime={currentTime}
          timeFormat={timeFormat}
          isFavorite={selectedProgramFavorite}
          onClose={() => setSelectedProgram(null)}
          onPlay={handlePlaySelectedProgram}
          onToggleFavorite={() => onToggleFavorite(String(selectedProgram.channel.stream_id))}
        />
      )}
    </div>
  );
};
