import React, { useMemo, useEffect, useState } from 'react';
import { Heart, Tv, Film, Play, Trash2 } from 'lucide-react';
import { XtreamChannel, XtreamMovie, XtreamSeries } from '../types/xtream';
import { useLogoUrl } from '../services/logoCache';
import { resolveCachedByIds } from '../services/catalogCache';

interface FavoritesPageProps {
  favorites: string[];
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
  profileId?: string | null;
  onPlayChannel: (channel: XtreamChannel) => void;
  onPlayMovie: (movie: XtreamMovie) => void;
  onSelectSeries: (series: XtreamSeries) => void;
  onRemoveFavorite: (id: string) => void;
}

function mergeUniqueById<T>(
  a: T[],
  b: T[],
  getId: (item: T) => string
): T[] {
  const seen = new Set(a.map(getId));
  const out = [...a];
  for (const item of b) {
    const id = getId(item);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    out.push(item);
  }
  return out;
}

export const FavoritesPage: React.FC<FavoritesPageProps> = ({
  favorites,
  channels,
  movies,
  series,
  profileId,
  onPlayChannel,
  onPlayMovie,
  onSelectSeries,
  onRemoveFavorite,
}) => {
  const [cachedChannels, setCachedChannels] = useState<XtreamChannel[]>([]);
  const [cachedMovies, setCachedMovies] = useState<XtreamMovie[]>([]);
  const [cachedSeries, setCachedSeries] = useState<XtreamSeries[]>([]);

  useEffect(() => {
    if (!profileId || favorites.length === 0) {
      setCachedChannels([]);
      setCachedMovies([]);
      setCachedSeries([]);
      return;
    }
    let cancelled = false;
    void resolveCachedByIds(profileId, favorites).then(resolved => {
      if (cancelled) return;
      setCachedChannels(resolved.channels);
      setCachedMovies(resolved.movies);
      setCachedSeries(resolved.series);
    });
    return () => { cancelled = true; };
  }, [profileId, favorites]);

  const favoriteChannels = useMemo(() => {
    const pool = mergeUniqueById(channels, cachedChannels, c => String(c.stream_id));
    return pool.filter(c => favorites.includes(String(c.stream_id)));
  }, [channels, cachedChannels, favorites]);

  const favoriteMovies = useMemo(() => {
    const pool = mergeUniqueById(movies, cachedMovies, m => String(m.stream_id));
    return pool.filter(m => favorites.includes(String(m.stream_id)));
  }, [movies, cachedMovies, favorites]);

  const favoriteSeries = useMemo(() => {
    const pool = mergeUniqueById(series, cachedSeries, s => String(s.series_id));
    return pool.filter(s => favorites.includes(String(s.series_id)));
  }, [series, cachedSeries, favorites]);

  const totalFavs = favoriteChannels.length + favoriteMovies.length + favoriteSeries.length;

  return (
    <div className="flex-1 overflow-y-auto">
      {/* Header */}
      <div className="flex items-center gap-3 px-6 py-4 border-b border-white/[0.06]">
        <Heart size={18} className="text-red-400" />
        <h2 className="text-white font-semibold text-lg">Favorites</h2>
        <span className="text-white/30 text-xs">{totalFavs} items</span>
      </div>

      <div className="p-6 space-y-8">
        {totalFavs === 0 && (
          <div className="flex flex-col items-center justify-center py-20">
            <Heart size={40} className="text-white/10 mb-3" />
            <p className="text-white/30 text-sm">No favorites yet</p>
            <p className="text-white/20 text-xs mt-1">
              Tap the heart icon on channels, movies, or series to add them here
            </p>
          </div>
        )}

        {/* Channels */}
        {favoriteChannels.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-3 flex items-center gap-2">
              <Tv size={12} />
              Live Channels ({favoriteChannels.length})
            </h3>
            <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-2">
              {favoriteChannels.map(ch => (
                <div
                  key={ch.stream_id}
                  className="flex items-center gap-3 px-3 py-2.5 rounded-xl
                    bg-white/[0.03] border border-white/[0.06]
                    hover:bg-white/[0.06] transition-all group cursor-pointer"
                  onClick={() => onPlayChannel(ch)}
                >
                  <div className="w-8 h-8 rounded-lg bg-white/5 overflow-hidden flex items-center justify-center shrink-0">
                    {ch.stream_icon ? (
                      <img src={ch.stream_icon} alt="" className="w-full h-full object-contain" />
                    ) : (
                      <Tv size={14} className="text-white/20" />
                    )}
                  </div>
                  <p className="flex-1 text-white/80 text-sm truncate">{ch.name}</p>
                  <Play size={14} className="text-white/0 group-hover:text-cyan-400 transition-colors shrink-0" />
                  <button
                    onClick={(e) => { e.stopPropagation(); onRemoveFavorite(String(ch.stream_id)); }}
                    className="text-white/0 group-hover:text-red-400/60 hover:!text-red-400 transition-colors shrink-0"
                  >
                    <Trash2 size={12} />
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Movies */}
        {favoriteMovies.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-3 flex items-center gap-2">
              <Film size={12} />
              Movies ({favoriteMovies.length})
            </h3>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
              {favoriteMovies.map(m => (
                <div
                  key={m.stream_id}
                  className="group relative rounded-xl overflow-hidden bg-white/[0.03]
                    border border-white/[0.06] hover:border-white/10 transition-all cursor-pointer"
                  onClick={() => onPlayMovie(m)}
                >
                  <div className="aspect-[2/3] bg-white/[0.02]">
                    {m.stream_icon ? (
                      <img src={m.stream_icon} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <Film size={24} className="text-white/10" />
                      </div>
                    )}
                  </div>
                  <div className="p-2">
                    <p className="text-white/70 text-xs truncate">{m.name}</p>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); onRemoveFavorite(String(m.stream_id)); }}
                    className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full
                      bg-black/60 flex items-center justify-center
                      opacity-0 group-hover:opacity-100 transition-opacity
                      text-red-400 hover:bg-red-500/20"
                  >
                    <Trash2 size={10} />
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}

        {/* Series */}
        {favoriteSeries.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-3 flex items-center gap-2">
              <Film size={12} />
              Series ({favoriteSeries.length})
            </h3>
            <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
              {favoriteSeries.map(s => (
                <div
                  key={s.series_id}
                  className="group relative rounded-xl overflow-hidden bg-white/[0.03]
                    border border-white/[0.06] hover:border-white/10 transition-all cursor-pointer"
                  onClick={() => onSelectSeries(s)}
                >
                  <div className="aspect-[2/3] bg-white/[0.02]">
                    {s.cover ? (
                      <img src={s.cover} alt="" className="w-full h-full object-cover" />
                    ) : (
                      <div className="w-full h-full flex items-center justify-center">
                        <Film size={24} className="text-white/10" />
                      </div>
                    )}
                  </div>
                  <div className="p-2">
                    <p className="text-white/70 text-xs truncate">{s.name}</p>
                  </div>
                  <button
                    onClick={(e) => { e.stopPropagation(); onRemoveFavorite(String(s.series_id)); }}
                    className="absolute top-1.5 right-1.5 w-6 h-6 rounded-full
                      bg-black/60 flex items-center justify-center
                      opacity-0 group-hover:opacity-100 transition-opacity
                      text-red-400 hover:bg-red-500/20"
                  >
                    <Trash2 size={10} />
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  );
};
