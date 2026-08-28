import React, { useState, useMemo, useCallback, useRef, useEffect } from 'react';
import {
  Search,
  List,
  Grid3X3,
  Heart,
  Play,
  ChevronDown,
  Tv,
  RefreshCw,
} from 'lucide-react';
import { FixedSizeList, ListChildComponentProps } from 'react-window';
import { XtreamChannel, XtreamCategory } from '../types/xtream';
import { EPGData, EPGProgram } from '../types/epg';
import { EpgGrid } from '../components/EpgGrid';
import { RecordingButton } from '../components/RecordingButton';
import { useLogoUrl } from '../services/logoCache';
import { getNowPlaying } from '../services/xmltvEpg';
import { AppSettings } from '../types/settings';
import { useChannelEpg } from '../hooks/useChannelEpg';

type LiveTvView = 'split' | 'epg';

interface LiveTVPageProps {
  channels: XtreamChannel[];
  categories: XtreamCategory[];
  epgData: EPGData | null;
  activeChannel: XtreamChannel | null;
  favorites: string[];
  settings: AppSettings;
  activeCategoryId?: string | null;
  isCategoryLoading?: boolean;
  catalogStale?: boolean;
  onCategoryChange?: (categoryId: string) => void;
  onRefreshCategory?: (categoryId: string) => void;
  onChannelSelect: (channel: XtreamChannel) => void;
  onToggleFavorite: (id: string) => void;
  channelJump: {
    buffer: string;
    isActive: boolean;
    matchedChannel: XtreamChannel | null;
  };
}

const ChannelRow: React.FC<{
  channel: XtreamChannel;
  isActive: boolean;
  isFavorite: boolean;
  getProgramsForChannel: (channel: XtreamChannel) => EPGProgram[];
  onSelect: () => void;
  onToggleFav: () => void;
}> = ({ channel, isActive, isFavorite, getProgramsForChannel, onSelect, onToggleFav }) => {
  const logoUrl = useLogoUrl(channel.stream_icon || '');
  const [logoFailed, setLogoFailed] = useState(false);
  const channelPrograms = getProgramsForChannel(channel);
  const nowPlaying = channelPrograms.length > 0 ? getNowPlaying(channelPrograms) : null;

  return (
    <div
      role="button"
      tabIndex={0}
      aria-pressed={isActive}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          onSelect();
        }
      }}
      className={`flex items-center gap-3 w-full px-3 py-2.5 rounded-xl transition-all
        ${isActive
          ? 'theme-accent-surface border'
          : 'hover:bg-white/[0.04] border border-transparent'
        }`}
      style={isActive ? { borderColor: 'var(--border-accent)' } : undefined}
    >
      {/* Channel number */}
      <span className="text-white/30 text-xs font-mono w-8 text-right shrink-0">
        {channel.num || '—'}
      </span>

      {/* Logo */}
      <div className="w-8 h-8 rounded-lg bg-white/5 overflow-hidden flex items-center justify-center shrink-0">
        {logoUrl && !logoFailed ? (
          <img
            src={logoUrl}
            alt=""
            className="w-full h-full object-contain"
            loading="lazy"
            onError={() => setLogoFailed(true)}
          />
        ) : (
          <Tv size={14} className="text-white/20" />
        )}
      </div>

      {/* Info */}
      <div className="flex-1 min-w-0 text-left">
        <div className="flex items-center gap-1.5 min-w-0">
          <p className={`text-sm truncate ${isActive ? 'font-semibold' : 'text-white/80'}`}
            style={isActive ? { color: 'var(--text-accent)' } : undefined}>
            {channel.name}
          </p>
          {channel.tv_archive === 1 && (
            <span className="text-[9px] bg-cyan-900/70 border border-cyan-500/40 text-cyan-300 font-bold px-1.5 py-0.2 rounded shrink-0 shadow-sm" title={`Catch-up available (${channel.tv_archive_duration || 7} days)`}>
              DVR {channel.tv_archive_duration ? `${channel.tv_archive_duration}d` : ''}
            </span>
          )}
        </div>
        {nowPlaying && (
          <p className="text-white/30 text-[11px] truncate">{nowPlaying.title}</p>
        )}
      </div>

      {/* Recording button */}
      <RecordingButton 
        channel={channel} 
        program={nowPlaying} 
        size="sm"
        variant="icon"
        className="shrink-0"
      />

      {/* Favorite */}
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); onToggleFav(); }}
        className={`shrink-0 transition-colors ${isFavorite ? 'text-red-400' : 'text-white/10 hover:text-white/30'}`}
      >
        <Heart size={14} fill={isFavorite ? 'currentColor' : 'none'} />
      </button>

      {/* Live indicator */}
      {isActive && (
        <div className="w-2 h-2 rounded-full bg-red-500 animate-pulse shrink-0" />
      )}
    </div>
  );
};

interface VChannelRowData {
  channels: XtreamChannel[];
  activeId: number | string | undefined;
  favorites: string[];
  getProgramsForChannel: (channel: XtreamChannel) => EPGProgram[];
  onSelect: (ch: XtreamChannel) => void;
  onToggleFav: (id: string) => void;
}

const VChannelRow = React.memo(({ index, style, data }: ListChildComponentProps<VChannelRowData>) => {
  const ch = data.channels[index];
  return (
    <div style={style}>
      <ChannelRow
        channel={ch}
        isActive={data.activeId === ch.stream_id}
        isFavorite={data.favorites.includes(String(ch.stream_id))}
        getProgramsForChannel={data.getProgramsForChannel}
        onSelect={() => data.onSelect(ch)}
        onToggleFav={() => data.onToggleFav(String(ch.stream_id))}
      />
    </div>
  );
});

export const LiveTVPage: React.FC<LiveTVPageProps> = ({
  channels,
  categories,
  epgData,
  activeChannel,
  favorites,
  settings,
  activeCategoryId = null,
  isCategoryLoading = false,
  catalogStale = false,
  onCategoryChange,
  onRefreshCategory,
  onChannelSelect,
  onToggleFavorite,
  channelJump,
}) => {
  const [view, setView] = useState<LiveTvView>('split');
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(activeCategoryId);
  const [showCategories, setShowCategories] = useState(false);
  const listContainerRef = useRef<HTMLDivElement>(null);
  const [listHeight, setListHeight] = useState(600);

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

  const selectCategory = useCallback((categoryId: string) => {
    setSelectedCategoryId(categoryId);
    setShowCategories(false);
    onCategoryChange?.(categoryId);
  }, [onCategoryChange]);

  useEffect(() => {
    const el = listContainerRef.current;
    if (!el) return;
    const ro = new ResizeObserver(entries => {
      if (entries[0]) setListHeight(entries[0].contentRect.height);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const filteredChannels = useMemo(() => {
    const q = searchQuery.toLowerCase().trim();
    // Category already loaded server-side — only filter by search within window
    return channels.filter(ch => {
      if (q && !ch.name?.toLowerCase().includes(q)) return false;
      return true;
    });
  }, [channels, searchQuery]);

  const selectedCategory = useMemo(
    () => categories.find(c => c.category_id === selectedCategoryId),
    [categories, selectedCategoryId]
  );

  const { getProgramsForChannel, matchStats } = useChannelEpg(
    epgData,
    filteredChannels,
    settings.epgEnabled !== false
  );

  const handleChannelSelect = useCallback(
    (channel: XtreamChannel) => {
      onChannelSelect(channel);
      if (view === 'epg') setView('split');
    },
    [onChannelSelect, view]
  );

  const vChannelData = useMemo<VChannelRowData>(() => ({
    channels: filteredChannels,
    activeId: activeChannel?.stream_id,
    favorites,
    getProgramsForChannel,
    onSelect: handleChannelSelect,
    onToggleFav: onToggleFavorite,
  }), [filteredChannels, activeChannel?.stream_id, favorites, getProgramsForChannel, handleChannelSelect, onToggleFavorite]);

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06]">
        <h2 className="text-white font-semibold text-lg">Live TV</h2>
        <span className="text-white/30 text-xs">{filteredChannels.length} channels</span>
        {catalogStale && selectedCategoryId && (
          <button
            onClick={() => onRefreshCategory?.(selectedCategoryId)}
            disabled={isCategoryLoading}
            className="flex items-center gap-1 px-2 py-0.5 rounded-lg text-[10px] font-medium
              border border-amber-500/30 bg-amber-500/10 text-amber-300
              hover:bg-amber-500/20 disabled:opacity-50 transition-colors"
            title="Category cache is stale — refresh from server"
          >
            <RefreshCw size={10} className={isCategoryLoading ? 'animate-spin' : ''} />
            Stale · Refresh
          </button>
        )}
        {settings.epgEnabled !== false && (
          <span className="text-white/25 text-[10px]">
            Guide sample {matchStats.withGuide}/{matchStats.sample}
          </span>
        )}

        <div className="flex-1" />

        {/* View toggle */}
        <div className="flex items-center bg-white/[0.04] rounded-lg p-0.5">
          <button
            onClick={() => setView('split')}
            className="px-3 py-1.5 rounded-md text-xs font-medium transition-colors"
            style={view === 'split' ? { background: 'var(--accent-surface)', color: 'var(--text-accent)' } : { color: 'rgba(255,255,255,0.4)' }}
          >
            <List size={14} />
          </button>
          <button
            onClick={() => setView('epg')}
            className="px-3 py-1.5 rounded-md text-xs font-medium transition-colors"
            style={view === 'epg' ? { background: 'var(--accent-surface)', color: 'var(--text-accent)' } : { color: 'rgba(255,255,255,0.4)' }}
          >
            <Grid3X3 size={14} />
          </button>
        </div>
      </div>

      {/* Content */}
      {view === 'epg' ? (
        <EpgGrid
          channels={filteredChannels}
          getProgramsForChannel={getProgramsForChannel}
          onChannelClick={handleChannelSelect}
          onProgramClick={(_program, channel) => handleChannelSelect(channel)}
          timeFormat={settings.epgTimeFormat}
        />
      ) : (
        <div className="flex-1 flex overflow-hidden">
          {/* Channel list */}
          <div className="w-80 flex flex-col border-r border-white/[0.06] overflow-hidden">
            {/* Search + Category filter */}
            <div className="p-3 space-y-2">
              <div className="relative">
                <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={e => setSearchQuery(e.target.value)}
                  placeholder="Search channels..."
                  className="w-full pl-9 pr-3 py-2 bg-white/[0.04] border border-white/[0.06]
                    rounded-xl text-white text-sm placeholder:text-white/20
                    focus:outline-none"
                  style={{ borderColor: searchQuery ? 'var(--border-accent)' : undefined }}
                />
              </div>

              {/* Category dropdown */}
              <div className="relative">
                <button
                  onClick={() => setShowCategories(!showCategories)}
                  className="w-full flex items-center justify-between px-3 py-2
                    bg-white/[0.04] border border-white/[0.06] rounded-xl text-sm"
                >
                  <span className={selectedCategory ? 'text-white' : 'text-white/40'}>
                    {selectedCategory?.category_name || 'Select category'}
                    {isCategoryLoading ? '…' : ''}
                  </span>
                  <ChevronDown size={14} className="text-white/30" />
                </button>
                {showCategories && (
                  <div className="fixed inset-0 z-[100] flex items-start justify-center p-4 pt-16" onClick={() => setShowCategories(false)}>
                    <div onClick={event => event.stopPropagation()} className="w-80 max-h-[70dvh] overflow-y-auto bg-[#12121e] border border-white/10 rounded-xl shadow-2xl">
                      {categories.map(cat => (
                        <button
                          key={cat.category_id}
                          onClick={() => selectCategory(String(cat.category_id))}
                          className={`w-full text-left px-3 py-2 text-sm hover:bg-white/[0.04] ${
                            String(selectedCategoryId) === String(cat.category_id) ? 'text-cyan-400' : 'text-white/60'
                          }`}
                        >
                          {cat.category_name}
                        </button>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>

            {/* Channel list */}
            <div ref={listContainerRef} className="flex-1 overflow-hidden">
              {filteredChannels.length === 0 ? (
                <p className="text-center text-white/20 text-sm py-8">No channels found</p>
              ) : (
                <FixedSizeList
                  height={listHeight}
                  width="100%"
                  itemCount={filteredChannels.length}
                  itemSize={64}
                  overscanCount={10}
                  itemData={vChannelData}
                >
                  {VChannelRow}
                </FixedSizeList>
              )}
            </div>
          </div>

          {/* Player placeholder area */}
          <div className="flex-1 flex items-center justify-center bg-black/40">
            {!activeChannel && (
              <div className="text-center">
                <Tv size={40} className="text-white/10 mx-auto mb-3" />
                <p className="text-white/20 text-sm">Select a channel to start watching</p>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Channel number jump OSD */}
      {channelJump.isActive && (
        <div className="fixed inset-0 pointer-events-none z-50 flex items-center justify-center">
          <div className="flex flex-col items-center gap-3
            bg-black/90 border border-white/15 rounded-3xl
            px-10 py-7 backdrop-blur-xl shadow-2xl">
            <div className="flex items-center gap-1">
              {[0, 1, 2, 3].map(i => (
                <div
                  key={i}
                  className={`w-12 h-16 rounded-xl border flex items-center justify-center
                    text-4xl font-bold font-mono transition-all
                    ${i < channelJump.buffer.length
                      ? 'text-white'
                      : 'bg-white/5 border-white/10 text-white/15'
                    }`}
                  style={i < channelJump.buffer.length ? { background: 'var(--accent-surface)', borderColor: 'var(--border-accent)' } : undefined}
                >
                  {channelJump.buffer[i] ?? (i === 0 ? '—' : '')}
                </div>
              ))}
            </div>
            {channelJump.matchedChannel ? (
              <div className="flex items-center gap-2">
                {channelJump.matchedChannel.stream_icon && (
                  <img
                    src={channelJump.matchedChannel.stream_icon}
                    className="w-6 h-6 rounded object-contain"
                    alt=""
                    onError={(e) => {
                      e.currentTarget.style.display = 'none';
                    }}
                  />
                )}
                <span className="text-white/80 text-sm">
                  {channelJump.matchedChannel.name}
                </span>
              </div>
            ) : (
              <span className="text-white/30 text-xs">
                {channelJump.buffer.length > 0 ? 'No matching channel' : 'Enter channel number'}
              </span>
            )}
          </div>
        </div>
      )}
    </div>
  );
};
