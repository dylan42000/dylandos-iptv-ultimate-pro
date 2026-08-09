import React, { useState, useMemo, useEffect } from 'react';
import { Search as SearchIcon, Tv, Film, Calendar, Star, Play, X } from 'lucide-react';
import { XtreamChannel, XtreamMovie, XtreamSeries } from '../types/xtream';
import { EPGData, EPGProgram } from '../types/epg';
import { useLogoUrl } from '../services/logoCache';
import {
  searchCatalogIndexed,
  hydrateSearchHits,
} from '../services/catalogCache';

type SearchTab = 'all' | 'live' | 'movies' | 'series' | 'epg';

interface SearchPageProps {
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
  epgData?: EPGData | null;
  profileId?: string | null;
  onPlayChannel: (channel: XtreamChannel) => void;
  onPlayMovie: (movie: XtreamMovie) => void;
  onSelectSeries: (series: XtreamSeries) => void;
}

const ChannelResult: React.FC<{
  channel: XtreamChannel;
  onPlay: () => void;
}> = ({ channel, onPlay }) => {
  const logoUrl = useLogoUrl(channel.stream_icon || '');
  return (
    <button
      onClick={onPlay}
      className="flex items-center gap-3 px-3 py-2.5 rounded-xl
        hover:bg-white/[0.04] transition-all w-full group"
    >
      <div className="w-8 h-8 rounded-lg bg-white/5 overflow-hidden flex items-center justify-center shrink-0">
        {logoUrl ? (
          <img src={logoUrl} alt="" className="w-full h-full object-contain" />
        ) : (
          <Tv size={14} className="text-white/20" />
        )}
      </div>
      <div className="flex-1 min-w-0 text-left">
        <p className="text-white/80 text-sm truncate">{channel.name}</p>
        <p className="text-cyan-400/50 text-[10px]">Live TV</p>
      </div>
      <Play size={14} className="text-white/0 group-hover:text-cyan-400 transition-colors shrink-0" />
    </button>
  );
};

const MovieResult: React.FC<{
  movie: XtreamMovie;
  onPlay: () => void;
}> = ({ movie, onPlay }) => (
  <button
    onClick={onPlay}
    className="flex items-center gap-3 px-3 py-2.5 rounded-xl
      hover:bg-white/[0.04] transition-all w-full group"
  >
    <div className="w-8 h-12 rounded-lg bg-white/5 overflow-hidden shrink-0">
      {movie.stream_icon && (
        <img src={movie.stream_icon} alt="" className="w-full h-full object-cover" />
      )}
    </div>
    <div className="flex-1 min-w-0 text-left">
      <p className="text-white/80 text-sm truncate">{movie.name}</p>
      <p className="text-purple-400/50 text-[10px]">Movie</p>
    </div>
    <Play size={14} className="text-white/0 group-hover:text-cyan-400 transition-colors shrink-0" />
  </button>
);

const SeriesResult: React.FC<{
  series: XtreamSeries;
  onClick: () => void;
}> = ({ series, onClick }) => (
  <button
    onClick={onClick}
    className="flex items-center gap-3 px-3 py-2.5 rounded-xl
      hover:bg-white/[0.04] transition-all w-full group"
  >
    <div className="w-8 h-12 rounded-lg bg-white/5 overflow-hidden shrink-0">
      {series.cover && (
        <img src={series.cover} alt="" className="w-full h-full object-cover" />
      )}
    </div>
    <div className="flex-1 min-w-0 text-left">
      <p className="text-white/80 text-sm truncate">{series.name}</p>
      <p className="text-green-400/50 text-[10px]">Series</p>
    </div>
  </button>
);

const EpgResult: React.FC<{ program: EPGProgram; channelName: string }> = ({ program, channelName }) => {
  const now = Date.now();
  const start = new Date(program.startTime);
  const end = new Date(program.stopTime);
  const isLive = now >= start.getTime() && now < end.getTime();
  const formatTime = (d: Date) => d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  const formatDay = (d: Date) => {
    const today = new Date();
    const tomorrow = new Date(today); tomorrow.setDate(today.getDate() + 1);
    if (d.toDateString() === today.toDateString()) return 'Today';
    if (d.toDateString() === tomorrow.toDateString()) return 'Tomorrow';
    return d.toLocaleDateString([], { weekday: 'short', month: 'short', day: 'numeric' });
  };

  return (
    <div className="flex items-start gap-3 px-3 py-2.5 rounded-xl hover:bg-white/[0.04] transition-all">
      <Calendar size={14} className="text-white/20 mt-0.5 shrink-0" />
      <div className="min-w-0 flex-1">
        <div className="flex items-center gap-2 flex-wrap">
          <p className="text-white/80 text-sm truncate">{program.title}</p>
          {isLive && (
            <span className="inline-flex items-center gap-1 rounded-full bg-red-500/15 border border-red-500/30 px-1.5 py-0.5 text-[10px] font-bold text-red-300">
              <span className="h-1.5 w-1.5 rounded-full bg-red-400 animate-pulse" />LIVE
            </span>
          )}
          {program.category && (
            <span className="text-[10px] text-cyan-400/50">{program.category}</span>
          )}
        </div>
        <p className="text-white/30 text-[11px] mt-0.5">
          {channelName} · {formatDay(start)} {formatTime(start)}–{formatTime(end)}
        </p>
      </div>
    </div>
  );
};

function mergeById<T extends { stream_id?: number; series_id?: number }>(
  primary: T[],
  secondary: T[],
  getId: (item: T) => string
): T[] {
  const seen = new Set(primary.map(getId));
  const merged = [...primary];
  for (const item of secondary) {
    const id = getId(item);
    if (!id || seen.has(id)) continue;
    seen.add(id);
    merged.push(item);
  }
  return merged;
}

export const SearchPage: React.FC<SearchPageProps> = ({
  channels,
  movies,
  series,
  epgData,
  profileId,
  onPlayChannel,
  onPlayMovie,
  onSelectSeries,
}) => {
  const [query, setQuery] = useState('');
  const [activeTab, setActiveTab] = useState<SearchTab>('all');
  const [filterRating, setFilterRating] = useState(false);
  const [filterGenre, setFilterGenre] = useState<string | null>(null);
  const [indexedChannels, setIndexedChannels] = useState<XtreamChannel[]>([]);
  const [indexedMovies, setIndexedMovies] = useState<XtreamMovie[]>([]);
  const [indexedSeries, setIndexedSeries] = useState<XtreamSeries[]>([]);
  const [indexSearching, setIndexSearching] = useState(false);

  // Indexed search across all durable cached categories (not just current window)
  useEffect(() => {
    const q = query.trim();
    if (!q || q.length < 2 || !profileId) {
      setIndexedChannels([]);
      setIndexedMovies([]);
      setIndexedSeries([]);
      setIndexSearching(false);
      return;
    }

    let cancelled = false;
    setIndexSearching(true);
    const timer = setTimeout(() => {
      void (async () => {
        try {
          const hits = await searchCatalogIndexed(profileId, q, { limit: 80 });
          if (cancelled) return;
          const liveHits = hits.filter(h => h.kind === 'live');
          const vodHits = hits.filter(h => h.kind === 'vod');
          const seriesHits = hits.filter(h => h.kind === 'series');
          const [liveItems, vodItems, seriesItems] = await Promise.all([
            hydrateSearchHits<XtreamChannel>(profileId, liveHits),
            hydrateSearchHits<XtreamMovie>(profileId, vodHits),
            hydrateSearchHits<XtreamSeries>(profileId, seriesHits),
          ]);
          if (cancelled) return;
          setIndexedChannels(liveItems);
          setIndexedMovies(vodItems);
          setIndexedSeries(seriesItems);
        } finally {
          if (!cancelled) setIndexSearching(false);
        }
      })();
    }, 180);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [query, profileId]);

  const results = useMemo(() => {
    const q = query.toLowerCase().trim();
    if (!q) return { channels: [], movies: [], series: [], epg: [] };

    const epgMatches: Array<{ program: EPGProgram; channelName: string }> = [];
    if (epgData) {
      const now = Date.now();
      const weekMs = 7 * 24 * 3600 * 1000;
      for (const programs of Object.values(epgData.programs)) {
        for (const prog of programs) {
          const endTime = new Date(prog.stopTime).getTime();
          if (endTime < now - 3600000) continue;
          if (endTime > now + weekMs) continue;
          if (prog.title?.toLowerCase().includes(q) || prog.description?.toLowerCase().includes(q)) {
            const ch = Object.values(epgData.channels).find(c => c.id === prog.channelId);
            epgMatches.push({ program: prog, channelName: ch?.displayName ?? prog.channelId });
            if (epgMatches.length >= 100) break;
          }
        }
        if (epgMatches.length >= 100) break;
      }
    }

    const windowChannels = channels.filter(c => c.name?.toLowerCase().includes(q));
    const windowMovies = movies.filter(m => m.name?.toLowerCase().includes(q));
    const windowSeries = series.filter(s => s.name?.toLowerCase().includes(q));

    return {
      channels: mergeById(indexedChannels, windowChannels, c => String(c.stream_id)).slice(0, 50),
      movies: mergeById(indexedMovies, windowMovies, m => String(m.stream_id)).slice(0, 50),
      series: mergeById(indexedSeries, windowSeries, s => String(s.series_id)).slice(0, 50),
      epg: epgMatches.slice(0, 50),
    };
  }, [query, channels, movies, series, epgData, indexedChannels, indexedMovies, indexedSeries]);

  const epgGenres = useMemo(() => {
    const genres = new Set<string>();
    results.epg.forEach(({ program }) => {
      if (program.category) genres.add(program.category);
    });
    return [...genres].slice(0, 8);
  }, [results.epg]);

  const filteredEpg = useMemo(() => {
    return results.epg.filter(({ program }) => {
      if (filterGenre && program.category !== filterGenre) return false;
      if (filterRating) {
        const rating = Number(program.rating || 0);
        if (rating < 7) return false;
      }
      return true;
    });
  }, [results.epg, filterGenre, filterRating]);

  const totalResults =
    results.channels.length + results.movies.length + results.series.length + filteredEpg.length;

  // Keep rest of UI from original file — read remaining section
  return (
    <div className="flex-1 flex flex-col overflow-hidden">
      <div className="px-6 py-4 border-b border-white/[0.06] space-y-3">
        <div className="relative">
          <SearchIcon size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-white/30" />
          <input
            type="text"
            value={query}
            onChange={e => setQuery(e.target.value)}
            placeholder="Search channels, movies, series, EPG…"
            className="w-full pl-10 pr-10 py-2.5 bg-white/[0.04] border border-white/[0.08]
              rounded-xl text-white text-sm placeholder:text-white/25
              focus:outline-none focus:border-cyan-500/40"
            autoFocus
          />
          {query && (
            <button
              onClick={() => setQuery('')}
              className="absolute right-3 top-1/2 -translate-y-1/2 text-white/30 hover:text-white/60"
            >
              <X size={14} />
            </button>
          )}
        </div>

        {query && (
          <div className="flex items-center gap-2 flex-wrap">
            {([
              ['all', 'All'],
              ['live', 'Live'],
              ['movies', 'Movies'],
              ['series', 'Series'],
              ['epg', 'Guide'],
            ] as const).map(([id, label]) => (
              <button
                key={id}
                onClick={() => setActiveTab(id)}
                className={`px-3 py-1 rounded-lg text-xs font-medium transition-colors ${
                  activeTab === id ? 'bg-cyan-500/20 text-cyan-300' : 'text-white/40 hover:text-white/70'
                }`}
              >
                {label}
              </button>
            ))}
            <span className="text-white/25 text-[11px] ml-auto">
              {indexSearching ? 'Indexing…' : `${totalResults} results`}
              {profileId ? ' · cached catalog' : ''}
            </span>
          </div>
        )}

        {query && (activeTab === 'epg' || activeTab === 'all') && results.epg.length > 0 && (
          <div className="flex items-center gap-2 flex-wrap">
            <button
              onClick={() => setFilterRating(r => !r)}
              className={`flex items-center gap-1 px-2 py-1 rounded-lg text-[11px] border transition-colors ${
                filterRating
                  ? 'border-amber-500/40 bg-amber-500/10 text-amber-300'
                  : 'border-white/10 text-white/40 hover:text-white/60'
              }`}
            >
              <Star size={10} /> Rating ≥7
            </button>
            {epgGenres.map(genre => (
              <button
                key={genre}
                onClick={() => setFilterGenre(filterGenre === genre ? null : genre)}
                className={`px-2 py-1 rounded-lg text-[11px] border transition-colors ${
                  filterGenre === genre
                    ? 'border-cyan-500/40 bg-cyan-500/10 text-cyan-300'
                    : 'border-white/10 text-white/40 hover:text-white/60'
                }`}
              >
                {genre}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-6">
        {!query && (
          <div className="flex flex-col items-center justify-center py-20">
            <SearchIcon size={40} className="text-white/10 mb-3" />
            <p className="text-white/30 text-sm">Start typing to search</p>
            <p className="text-white/20 text-xs mt-1">Searches all categories you&apos;ve browsed (durable cache)</p>
          </div>
        )}

        {query && totalResults === 0 && !indexSearching && (
          <div className="flex flex-col items-center justify-center py-20">
            <p className="text-white/30 text-sm">No results for &ldquo;{query}&rdquo;</p>
            <p className="text-white/20 text-xs mt-1">Browse more categories to grow the search index</p>
          </div>
        )}

        {(activeTab === 'all' || activeTab === 'live') && results.channels.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-2 px-1 flex items-center gap-2">
              <Tv size={12} /> Live Channels ({results.channels.length})
            </h3>
            <div className="space-y-0.5">
              {results.channels.map(ch => (
                <ChannelResult key={ch.stream_id} channel={ch} onPlay={() => onPlayChannel(ch)} />
              ))}
            </div>
          </section>
        )}

        {(activeTab === 'all' || activeTab === 'movies') && results.movies.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-2 px-1 flex items-center gap-2">
              <Film size={12} /> Movies ({results.movies.length})
            </h3>
            <div className="space-y-0.5">
              {results.movies.map(m => (
                <MovieResult key={m.stream_id} movie={m} onPlay={() => onPlayMovie(m)} />
              ))}
            </div>
          </section>
        )}

        {(activeTab === 'all' || activeTab === 'series') && results.series.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-2 px-1 flex items-center gap-2">
              <Film size={12} /> Series ({results.series.length})
            </h3>
            <div className="space-y-0.5">
              {results.series.map(s => (
                <SeriesResult key={s.series_id} series={s} onClick={() => onSelectSeries(s)} />
              ))}
            </div>
          </section>
        )}

        {(activeTab === 'all' || activeTab === 'epg') && filteredEpg.length > 0 && (
          <section>
            <h3 className="text-white/50 text-xs font-semibold uppercase tracking-wider mb-2 px-1 flex items-center gap-2">
              <Calendar size={12} /> Guide Programs ({filteredEpg.length})
            </h3>
            <div className="space-y-0.5">
              {filteredEpg.map(({ program, channelName }, i) => (
                <EpgResult key={`${program.channelId}-${program.startTime}-${i}`} program={program} channelName={channelName} />
              ))}
            </div>
          </section>
        )}
      </div>
    </div>
  );
};
