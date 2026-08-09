import React, { useState, useMemo, useCallback, useEffect } from 'react';
import {
  Search,
  ChevronDown,
  Play,
  Heart,
  Film,
  Loader2,
  ArrowLeft,
  RefreshCw,
} from 'lucide-react';
import {
  XtreamSeries,
  XtreamSeriesInfo,
  XtreamCategory,
  XtreamEpisode,
} from '../types/xtream';
import { useWorkerFilter } from '../hooks/useWorkerFilter';
import { useLogoUrl } from '../services/logoCache';
import { AppSettings } from '../types/settings';
import { VirtualContentGrid } from '../components/VirtualContentGrid';
import { RecordingButton } from '../components/RecordingButton';
import { xtreamApi as xtream } from '../services/xtreamApi';

interface SeriesPageProps {
  series: XtreamSeries[];
  categories: XtreamCategory[];
  favorites: string[];
  settings: AppSettings;
  activeCategoryId?: string | null;
  isCategoryLoading?: boolean;
  catalogStale?: boolean;
  onCategoryChange?: (categoryId: string) => void;
  onRefreshCategory?: (categoryId: string) => void;
  onPlayEpisode: (episode: XtreamEpisode, seriesInfo: XtreamSeriesInfo) => void;
  onToggleFavorite: (id: string) => void;
  onLoadSeriesInfo: (seriesId: number) => Promise<XtreamSeriesInfo | null>;
}

const SeriesCard: React.FC<{
  series: XtreamSeries;
  isFavorite: boolean;
  onClick: () => void;
  onToggleFav: () => void;
}> = ({ series, isFavorite, onClick, onToggleFav }) => {
  const coverUrl = useLogoUrl(series.cover || '');
  const [coverFailed, setCoverFailed] = useState(false);

  return (
    <div
      className="group relative rounded-xl overflow-hidden bg-white/[0.03] border border-white/[0.06]
        hover:border-white/10 transition-all cursor-pointer h-full"
      onClick={onClick}
    >
      <div className="aspect-[2/3] bg-white/[0.02] overflow-hidden">
        {coverUrl && !coverFailed ? (
          <img
            src={coverUrl}
            alt={series.name}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
            loading="lazy"
            onError={() => setCoverFailed(true)}
          />
        ) : (
          <div className="w-full h-full flex items-center justify-center">
            <Film size={32} className="text-white/10" />
          </div>
        )}
      </div>

      {/* Hover overlay */}
      <div className="absolute inset-0 bg-gradient-to-t from-black/90 via-black/30 to-transparent
        opacity-0 group-hover:opacity-100 transition-opacity duration-200
        flex flex-col justify-end p-3">
        <button
          className={`absolute top-2 right-2 w-8 h-8 rounded-full flex items-center justify-center transition-colors
            ${isFavorite
              ? 'bg-red-500/20 text-red-400'
              : 'bg-black/50 text-white/50 hover:text-white'
            }`}
          onClick={(e) => { e.stopPropagation(); onToggleFav(); }}
        >
          <Heart size={14} fill={isFavorite ? 'currentColor' : 'none'} />
        </button>
      </div>

      <div className="p-2.5">
        <p className="text-white/80 text-xs font-medium truncate">{series.name}</p>
        {series.rating && (
          <p className="text-white/30 text-[10px] mt-0.5">★ {series.rating}</p>
        )}
      </div>
    </div>
  );
};

const posterGridSizes = {
  standard: { columnWidth: 180, rowHeight: 320 },
  large: { columnWidth: 220, rowHeight: 380 },
  'extra-large': { columnWidth: 260, rowHeight: 440 },
} as const;

const SeriesDetail: React.FC<{
  seriesInfo: XtreamSeriesInfo;
  onBack: () => void;
  onPlayEpisode: (episode: XtreamEpisode) => void;
}> = ({ seriesInfo, onBack, onPlayEpisode }) => {
  const [selectedSeason, setSelectedSeason] = useState<string | null>(null);
  const seasons = useMemo(() => Object.keys(seriesInfo.episodes || {}), [seriesInfo.episodes]);

  useEffect(() => {
    if (seasons.length > 0 && !selectedSeason) {
      setSelectedSeason(seasons[0]);
    }
  }, [seasons, selectedSeason]);

  const episodes = useMemo(
    () => (selectedSeason ? seriesInfo.episodes?.[selectedSeason] || [] : []),
    [seriesInfo.episodes, selectedSeason]
  );

  const coverUrl = useLogoUrl(seriesInfo.info?.cover || '');

  return (
    <div className="flex-1 overflow-y-auto relative">
      {coverUrl && (
        <div className="pointer-events-none absolute inset-0 z-0 overflow-hidden max-h-[420px]">
          <img src={coverUrl} alt="" className="w-full h-full object-cover opacity-25 blur-sm scale-110" />
          <div className="absolute inset-0 bg-gradient-to-b from-black/50 via-black/80 to-black" />
        </div>
      )}
      {/* Back button */}
      <button
        onClick={onBack}
        className="relative z-10 flex items-center gap-2 px-4 py-3 text-white/50 hover:text-white transition-colors"
      >
        <ArrowLeft size={16} />
        <span className="text-sm">Back to Series</span>
      </button>

      {/* Hero */}
      <div className="relative z-10 flex gap-6 px-6 pb-6">
        {/* Cover */}
        <div className="w-48 shrink-0">
          <div className="aspect-[2/3] rounded-xl overflow-hidden bg-white/[0.03]">
            {coverUrl ? (
              <img src={coverUrl} alt="" className="w-full h-full object-cover" />
            ) : (
              <div className="w-full h-full flex items-center justify-center">
                <Film size={40} className="text-white/10" />
              </div>
            )}
          </div>
        </div>

        {/* Info */}
        <div className="flex-1 min-w-0">
          <h1 className="text-2xl font-bold text-white mb-2">
            {seriesInfo.info?.name || 'Unknown Series'}
          </h1>
          {seriesInfo.info?.plot && (
            <p className="text-white/50 text-sm leading-relaxed mb-4 line-clamp-4">
              {seriesInfo.info.plot}
            </p>
          )}
          <div className="flex items-center gap-4 text-white/40 text-sm">
            {seriesInfo.info?.rating && <span>★ {seriesInfo.info.rating}</span>}
            {seriesInfo.info?.genre && <span>{seriesInfo.info.genre}</span>}
            {seasons.length > 0 && <span>{seasons.length} seasons</span>}
          </div>
        </div>
      </div>

      {/* Season tabs */}
      <div className="relative z-10 px-6 border-b border-white/[0.06]">
        <div className="flex gap-1 overflow-x-auto">
          {seasons.map(s => (
            <button
              key={s}
              onClick={() => setSelectedSeason(s)}
              className={`px-4 py-2.5 text-sm font-medium whitespace-nowrap border-b-2 transition-colors ${
                selectedSeason === s
                  ? 'border-cyan-400 text-cyan-300'
                  : 'border-transparent text-white/40 hover:text-white/60'
              }`}
            >
              Season {s}
            </button>
          ))}
        </div>
      </div>

      {/* Episodes */}
      <div className="relative z-10 p-6 space-y-2">
        {episodes.map((ep) => (
          <button
            key={ep.id}
            onClick={() => onPlayEpisode(ep)}
            className="w-full flex items-center gap-4 p-3 rounded-xl
              hover:bg-white/[0.04] transition-all group text-left"
          >
            {/* Episode thumbnail */}
            <div className="w-32 h-20 rounded-lg bg-white/[0.03] overflow-hidden shrink-0
              flex items-center justify-center">
              {ep.info?.movie_image ? (
                <img src={ep.info.movie_image} alt="" className="w-full h-full object-cover" />
              ) : (
                <Play size={20} className="text-white/10" />
              )}
            </div>

            <div className="flex-1 min-w-0">
              <p className="text-white text-sm font-medium">
                E{ep.episode_num} · {ep.title || `Episode ${ep.episode_num}`}
              </p>
              {ep.info?.plot && (
                <p className="text-white/40 text-xs mt-1 line-clamp-2">{ep.info.plot}</p>
              )}
              {ep.info?.duration && (
                <p className="text-white/30 text-[10px] mt-1">{ep.info.duration}</p>
              )}
            </div>

            <div className="flex items-center gap-2 shrink-0">
              <RecordingButton
                vodSource={{
                  streamUrl: ep.direct_source?.trim()
                    ? ep.direct_source
                    : xtream.getSeriesStreamUrl(ep.id, ep.container_extension || 'mp4'),
                  title: ep.title || `Episode ${ep.episode_num}`,
                }}
                size="sm"
                variant="icon"
              />
              <Play
                size={16}
                className="text-white/0 group-hover:text-cyan-400 transition-colors"
              />
            </div>
          </button>
        ))}

        {episodes.length === 0 && (
          <p className="text-white/20 text-sm text-center py-8">No episodes available</p>
        )}
      </div>
    </div>
  );
};

export const SeriesPage: React.FC<SeriesPageProps> = ({
  series,
  categories,
  favorites,
  settings,
  activeCategoryId = null,
  isCategoryLoading = false,
  catalogStale = false,
  onCategoryChange,
  onRefreshCategory,
  onPlayEpisode,
  onToggleFavorite,
  onLoadSeriesInfo,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(activeCategoryId);
  const [showCategories, setShowCategories] = useState(false);
  const [selectedSeriesInfo, setSelectedSeriesInfo] = useState<XtreamSeriesInfo | null>(null);
  const [loadingSeriesId, setLoadingSeriesId] = useState<number | null>(null);

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

  const { filtered: filteredSeries, isFiltering } = useWorkerFilter(
    series as any[],
    searchQuery,
    null
  ) as { filtered: XtreamSeries[]; isFiltering: boolean };

  const selectedCategory = useMemo(
    () => categories.find(c => c.category_id === selectedCategoryId),
    [categories, selectedCategoryId]
  );

  const gridSize = posterGridSizes[settings.mediaPosterSize ?? 'large'];

  const handleSeriesClick = useCallback(async (s: XtreamSeries) => {
    setLoadingSeriesId(s.series_id);
    try {
      const info = await onLoadSeriesInfo(s.series_id);
      if (info) setSelectedSeriesInfo(info);
    } finally {
      setLoadingSeriesId(null);
    }
  }, [onLoadSeriesInfo]);

  // Stable card renderer for VirtualContentGrid
  const renderSeriesCard = useCallback(
    (s: XtreamSeries) => (
      <SeriesCard
        series={s}
        isFavorite={favorites.includes(String(s.series_id))}
        onClick={() => handleSeriesClick(s)}
        onToggleFav={() => onToggleFavorite(String(s.series_id))}
      />
    ),
    [favorites, handleSeriesClick, onToggleFavorite]
  );

  if (selectedSeriesInfo) {
    return (
      <SeriesDetail
        seriesInfo={selectedSeriesInfo}
        onBack={() => setSelectedSeriesInfo(null)}
        onPlayEpisode={(ep) => onPlayEpisode(ep, selectedSeriesInfo)}
      />
    );
  }

  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-3 px-4 py-3 border-b border-white/[0.06]">
        <h2 className="text-white font-semibold text-lg">Series</h2>
        <span className="text-white/30 text-xs">
          {filteredSeries.length} total
        </span>
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

        <div className="flex-1" />

        <div className="relative w-64">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search series..."
            className="w-full pl-9 pr-3 py-2 bg-white/[0.04] border border-white/[0.06]
              rounded-xl text-white text-sm placeholder:text-white/20
              focus:outline-none focus:border-cyan-500/30"
          />
        </div>

        <div className="relative">
          <button
            onClick={() => setShowCategories(!showCategories)}
            className="flex items-center gap-2 px-3 py-2 bg-white/[0.04] border border-white/[0.06]
              rounded-xl text-sm"
          >
            <span className={selectedCategory ? 'text-white' : 'text-white/40'}>
              {selectedCategory?.category_name || 'Select category'}
              {isCategoryLoading ? '…' : ''}
            </span>
            <ChevronDown size={14} className="text-white/30" />
          </button>
          {showCategories && (
            <div className="absolute z-50 top-full mt-1 right-0 w-56 max-h-60 overflow-y-auto
              bg-[#12121e] border border-white/10 rounded-xl shadow-2xl">
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
          )}
        </div>
      </div>

      {/* Grid */}
      <div className="flex-1 overflow-hidden">
        {(isFiltering || loadingSeriesId !== null) && (
          <div className="flex items-center justify-center py-8">
            <Loader2 size={24} className="text-cyan-400 animate-spin" />
          </div>
        )}

        {!isFiltering && filteredSeries.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16">
            <Film size={40} className="text-white/10 mb-3" />
            <p className="text-white/30 text-sm">No series found</p>
          </div>
        )}

        {!isFiltering && filteredSeries.length > 0 && (
          <div className="w-full h-full p-4">
            <VirtualContentGrid
              items={filteredSeries}
              renderCard={renderSeriesCard}
              columnWidth={gridSize.columnWidth}
              rowHeight={gridSize.rowHeight}
              gap={14}
            />
          </div>
        )}
      </div>
    </div>
  );
};
