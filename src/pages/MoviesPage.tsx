import React, { useState, useMemo, useCallback } from 'react';
import {
  Search,
  Grid3X3,
  List,
  ChevronDown,
  Star,
  Play,
  Heart,
  Film,
  Loader2,
  RefreshCw,
} from 'lucide-react';
import { XtreamMovie, XtreamCategory } from '../types/xtream';
import { useWorkerFilter } from '../hooks/useWorkerFilter';
import { useLogoUrl } from '../services/logoCache';
import { AppSettings } from '../types/settings';
import { VirtualContentGrid } from '../components/VirtualContentGrid';
import { RecordingButton } from '../components/RecordingButton';
import { xtreamApi as xtream } from '../services/xtreamApi';

interface MoviesPageProps {
  movies: XtreamMovie[];
  categories: XtreamCategory[];
  favorites: string[];
  settings: AppSettings;
  activeCategoryId?: string | null;
  isCategoryLoading?: boolean;
  catalogStale?: boolean;
  onCategoryChange?: (categoryId: string) => void;
  onRefreshCategory?: (categoryId: string) => void;
  onPlayMovie: (movie: XtreamMovie) => void;
  onToggleFavorite: (id: string) => void;
}

const MovieCard: React.FC<{
  movie: XtreamMovie;
  isFavorite: boolean;
  onPlay: () => void;
  onToggleFav: () => void;
  onHover?: () => void;
}> = ({ movie, isFavorite, onPlay, onToggleFav, onHover }) => {
  const posterUrl = useLogoUrl(movie.stream_icon || '');
  const [posterFailed, setPosterFailed] = useState(false);

  const recordVodSource = React.useMemo(() => {
    const url = movie.direct_source?.trim()
      ? movie.direct_source
      : xtream.getVodStreamUrl(movie.stream_id, movie.container_extension || 'mp4');
    return { streamUrl: url, title: movie.name };
  }, [movie]);

  return (
    <div
      className="group relative rounded-xl overflow-hidden bg-white/[0.03] border border-white/[0.06]
        hover:border-white/10 transition-all cursor-pointer h-full"
      onClick={onPlay}
      onMouseEnter={onHover}
    >
      {/* Poster */}
      <div className="aspect-[2/3] bg-white/[0.02] overflow-hidden">
        {posterUrl && !posterFailed ? (
          <img
            src={posterUrl}
            alt={movie.name}
            className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-300"
            loading="lazy"
            onError={() => setPosterFailed(true)}
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
        <div className="flex items-center gap-2 mb-2">
          <button
            className="w-9 h-9 rounded-full bg-cyan-500 flex items-center justify-center
              hover:bg-cyan-400 transition-colors"
            onClick={(e) => { e.stopPropagation(); onPlay(); }}
          >
            <Play size={16} className="text-black ml-0.5" />
          </button>
          <button
            className={`w-9 h-9 rounded-full border flex items-center justify-center transition-colors
              ${isFavorite
                ? 'bg-red-500/20 border-red-500/40 text-red-400'
                : 'bg-white/5 border-white/15 text-white/50 hover:text-white'
              }`}
            onClick={(e) => { e.stopPropagation(); onToggleFav(); }}
          >
            <Heart size={14} fill={isFavorite ? 'currentColor' : 'none'} />
          </button>
          <RecordingButton
            vodSource={recordVodSource}
            size="sm"
            variant="icon"
            className="!w-9 !h-9"
          />
        </div>
      </div>

      {/* Rating */}
      {movie.rating && Number(movie.rating) > 0 && (
        <div className="absolute top-2 right-2 flex items-center gap-1 px-1.5 py-0.5
          bg-black/70 rounded-md backdrop-blur-sm">
          <Star size={10} className="text-yellow-500" fill="currentColor" />
          <span className="text-white text-[10px] font-semibold">
            {Number(movie.rating).toFixed(1)}
          </span>
        </div>
      )}

      {/* Title */}
      <div className="p-2.5">
        <p className="text-white/80 text-xs font-medium truncate">{movie.name}</p>
        {movie.added && (
          <p className="text-white/30 text-[10px] mt-0.5">
            {new Date(Number(movie.added) * 1000).getFullYear()}
          </p>
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

export const MoviesPage: React.FC<MoviesPageProps> = ({
  movies,
  categories,
  favorites,
  settings,
  activeCategoryId = null,
  isCategoryLoading = false,
  catalogStale = false,
  onCategoryChange,
  onRefreshCategory,
  onPlayMovie,
  onToggleFavorite,
}) => {
  const [searchQuery, setSearchQuery] = useState('');
  const [selectedCategoryId, setSelectedCategoryId] = useState<string | null>(activeCategoryId);
  const [showCategories, setShowCategories] = useState(false);
  const [viewMode, setViewMode] = useState<'grid' | 'list'>('grid');
  const [spotlightMovie, setSpotlightMovie] = useState<XtreamMovie | null>(null);

  React.useEffect(() => {
    if (activeCategoryId && activeCategoryId !== selectedCategoryId) {
      setSelectedCategoryId(activeCategoryId);
    }
  }, [activeCategoryId, selectedCategoryId]);

  React.useEffect(() => {
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

  // Category is server-scoped — only search within the loaded window
  const { filtered: filteredMovies, isFiltering } = useWorkerFilter(
    movies,
    searchQuery,
    null
  );

  const selectedCategory = useMemo(
    () => categories.find(c => c.category_id === selectedCategoryId),
    [categories, selectedCategoryId]
  );

  const gridSize = posterGridSizes[settings.mediaPosterSize ?? 'large'];

  const activeSpotlight = spotlightMovie && filteredMovies.some(m => m.stream_id === spotlightMovie.stream_id)
    ? spotlightMovie
    : filteredMovies[0] ?? null;

  // Stable card renderer for VirtualContentGrid — declared before any hook that references it
  const renderMovieCard = useCallback(
    (movie: XtreamMovie) => (
      <MovieCard
        movie={movie}
        isFavorite={favorites.includes(String(movie.stream_id))}
        onPlay={() => onPlayMovie(movie)}
        onToggleFav={() => onToggleFavorite(String(movie.stream_id))}
        onHover={() => setSpotlightMovie(movie)}
      />
    ),
    [favorites, onPlayMovie, onToggleFavorite]
  );

  return (
    <div className="flex-1 flex flex-col overflow-hidden relative">
      {activeSpotlight?.stream_icon && (
        <div className="pointer-events-none absolute inset-0 z-0 overflow-hidden">
          <img
            src={activeSpotlight.stream_icon}
            alt=""
            className="absolute inset-0 w-full h-full object-cover opacity-20 scale-110 blur-[2px] transition-opacity duration-500"
          />
          <div className="absolute inset-0 bg-gradient-to-b from-black/70 via-black/85 to-black" />
        </div>
      )}

      {/* Header */}
      <div className="relative z-10 flex items-center gap-3 px-4 py-3 border-b border-white/[0.06]">
        <h2 className="text-white font-semibold text-lg">Movies</h2>
        <span className="text-white/30 text-xs">
          {filteredMovies.length} total
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

        {/* Search */}
        <div className="relative w-64">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
          <input
            type="text"
            value={searchQuery}
            onChange={e => setSearchQuery(e.target.value)}
            placeholder="Search movies..."
            className="w-full pl-9 pr-3 py-2 bg-white/[0.04] border border-white/[0.06]
              rounded-xl text-white text-sm placeholder:text-white/20
              focus:outline-none focus:border-cyan-500/30"
          />
        </div>

        {/* Category */}
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
            <div className="fixed inset-0 z-[100] flex items-start justify-end p-4 pt-16" onClick={() => setShowCategories(false)}>
              <div onClick={event => event.stopPropagation()} className="w-72 max-h-[70dvh] overflow-y-auto bg-[#12121e] border border-white/10 rounded-xl shadow-2xl">
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

        {/* View toggle */}
        <div className="flex items-center bg-white/[0.04] rounded-lg p-0.5">
          <button
            onClick={() => setViewMode('grid')}
            className={`px-2 py-1.5 rounded-md transition-colors ${
              viewMode === 'grid' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/40'
            }`}
          >
            <Grid3X3 size={14} />
          </button>
          <button
            onClick={() => setViewMode('list')}
            className={`px-2 py-1.5 rounded-md transition-colors ${
              viewMode === 'list' ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/40'
            }`}
          >
            <List size={14} />
          </button>
        </div>
      </div>

      {activeSpotlight && !isFiltering && (
        <div className="relative z-10 mx-4 mt-3 mb-1 flex items-end gap-4 rounded-2xl overflow-hidden
          border border-white/[0.08] bg-black/40 backdrop-blur-sm min-h-[110px]">
          <div className="absolute inset-0 opacity-40">
            {activeSpotlight.stream_icon && (
              <img src={activeSpotlight.stream_icon} alt="" className="w-full h-full object-cover" />
            )}
            <div className="absolute inset-0 bg-gradient-to-r from-black via-black/80 to-transparent" />
          </div>
          <div className="relative flex items-center gap-4 p-4 w-full">
            <div className="w-14 h-20 rounded-lg overflow-hidden bg-white/5 shrink-0 border border-white/10">
              {activeSpotlight.stream_icon ? (
                <img src={activeSpotlight.stream_icon} alt="" className="w-full h-full object-cover" />
              ) : (
                <div className="w-full h-full flex items-center justify-center">
                  <Film size={20} className="text-white/20" />
                </div>
              )}
            </div>
            <div className="min-w-0 flex-1">
              <p className="text-cyan-300/80 text-[10px] font-bold uppercase tracking-widest mb-0.5">
                Spotlight
              </p>
              <h3 className="text-white text-lg font-bold truncate">{activeSpotlight.name}</h3>
              <p className="text-white/40 text-xs mt-0.5">
                {activeSpotlight.rating ? `★ ${Number(activeSpotlight.rating).toFixed(1)}` : 'Ready to play'}
                {selectedCategory ? ` · ${selectedCategory.category_name}` : ''}
              </p>
            </div>
            <button
              onClick={() => onPlayMovie(activeSpotlight)}
              className="shrink-0 flex items-center gap-2 px-4 py-2.5 rounded-xl bg-white text-black
                text-sm font-bold hover:bg-white/90 transition-colors"
            >
              <Play size={14} className="fill-black" /> Play
            </button>
          </div>
        </div>
      )}

      {/* Content */}
      <div className="relative z-10 flex-1 overflow-hidden">
        {isFiltering && (
          <div className="flex items-center justify-center py-8">
            <Loader2 size={24} className="text-cyan-400 animate-spin" />
          </div>
        )}

        {!isFiltering && filteredMovies.length === 0 && (
          <div className="flex flex-col items-center justify-center py-16">
            <Film size={40} className="text-white/10 mb-3" />
            <p className="text-white/30 text-sm">No movies found</p>
          </div>
        )}

        {!isFiltering && filteredMovies.length > 0 && viewMode === 'grid' && (
          <div className="w-full h-full p-4">
            <VirtualContentGrid
              restorationKey={`movies:${selectedCategoryId}:${filteredMovies[0]?.stream_id}`}
              items={filteredMovies}
              renderCard={renderMovieCard}
              columnWidth={gridSize.columnWidth}
              rowHeight={gridSize.rowHeight}
              gap={14}
            />
          </div>
        )}

        {!isFiltering && filteredMovies.length > 0 && viewMode === 'list' && (
          <div className="overflow-y-auto h-full p-4 space-y-1">
            {filteredMovies.slice(0, 500).map(movie => (
              <button
                key={movie.stream_id}
                onClick={() => onPlayMovie(movie)}
                onMouseEnter={() => setSpotlightMovie(movie)}
                className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl
                  hover:bg-white/[0.04] transition-all"
              >
                <div className="w-10 h-14 rounded-lg bg-white/5 overflow-hidden shrink-0">
                  {movie.stream_icon && (
                    <img
                      src={movie.stream_icon}
                      alt=""
                      className="w-full h-full object-cover"
                      onError={(e) => {
                        e.currentTarget.style.display = 'none';
                      }}
                    />
                  )}
                </div>
                <div className="flex-1 min-w-0 text-left">
                  <p className="text-white/80 text-sm font-medium truncate">{movie.name}</p>
                  <p className="text-white/30 text-xs truncate">
                    {movie.added && new Date(Number(movie.added) * 1000).getFullYear()}
                    {movie.rating && ` · ★ ${Number(movie.rating).toFixed(1)}`}
                  </p>
                </div>
                <Play size={14} className="text-white/20 shrink-0" />
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};
