import React, { useMemo, useState, useEffect, useRef, useCallback } from 'react';
import {
  Play, Heart, Tv, Film, Layout, Search, Radio,
  Star, ChevronRight, Sparkles, Info, Zap,
} from 'lucide-react';
import { XtreamChannel, XtreamMovie, XtreamSeries, WatchProgress } from '../types/xtream';
import { EPGData } from '../types/epg';
import { useLogoUrl } from '../services/logoCache';
import { getNowPlaying } from '../services/xmltvEpg';
import { resolveCachedByIds } from '../services/catalogCache';

interface HomePageProps {
  recentlyWatched: WatchProgress[];
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
  favorites: string[];
  epgData?: EPGData | null;
  profileId?: string | null;
  onPlayChannel: (channel: XtreamChannel) => void;
  onPlayMovie: (movie: XtreamMovie) => void;
  onNavigate: (page: 'live' | 'movies' | 'series' | 'search' | 'favorites') => void;
}

const getGreeting = (): string => {
  const h = new Date().getHours();
  if (h < 5) return 'Late Night';
  if (h < 12) return 'Good Morning';
  if (h < 17) return 'Good Afternoon';
  if (h < 21) return 'Good Evening';
  return 'Late Night';
};

// ── Poster Card ───────────────────────────────────────────────────────────────
const PosterCard: React.FC<{
  title: string;
  posterUrl?: string;
  rating?: number;
  progress?: number;
  isNew?: boolean;
  onClick: () => void;
}> = ({ title, posterUrl, rating, progress, isNew, onClick }) => {
  const [imgErr, setImgErr] = useState(false);
  return (
    <button onClick={onClick}
      className="group relative flex-shrink-0 w-32 rounded-xl overflow-hidden border border-white/[0.06]
        transition-all duration-200 hover:scale-105 hover:border-white/20 hover:shadow-2xl text-left bg-white/[0.02]">
      <div className="aspect-[2/3] bg-white/5">
        {posterUrl && !imgErr ? (
          <img src={posterUrl} alt="" className="w-full h-full object-cover" loading="lazy"
            onError={() => setImgErr(true)} />
        ) : (
          <div className="w-full h-full flex items-center justify-center">
            <Film size={24} className="text-white/15" />
          </div>
        )}
        {/* Hover play overlay */}
        <div className="absolute inset-0 bg-black/50 opacity-0 group-hover:opacity-100 transition-opacity
          flex items-center justify-center">
          <div className="w-10 h-10 rounded-full bg-white/20 backdrop-blur flex items-center justify-center">
            <Play size={16} className="text-white ml-0.5" />
          </div>
        </div>
        {/* Progress bar */}
        {progress !== undefined && progress > 0 && (
          <div className="absolute bottom-0 inset-x-0 h-1 bg-white/20">
            <div className="h-full bg-cyan-400" style={{ width: `${Math.min(progress, 100)}%` }} />
          </div>
        )}
        {/* Rating badge */}
        {rating !== undefined && (
          <div className="absolute top-1.5 right-1.5 flex items-center gap-0.5 px-1.5 py-0.5
            bg-black/70 backdrop-blur rounded-full">
            <Star size={8} className="text-yellow-400 fill-yellow-400" />
            <span className="text-yellow-300 text-[9px] font-bold">{rating.toFixed(1)}</span>
          </div>
        )}
        {/* New badge */}
        {isNew && (
          <div className="absolute top-1.5 left-1.5 px-1.5 py-0.5 bg-cyan-500 text-black text-[8px] font-bold uppercase rounded">
            New
          </div>
        )}
      </div>
      <div className="px-2 py-1.5">
        <p className="text-white/75 text-[10px] font-medium truncate leading-tight">{title}</p>
      </div>
    </button>
  );
};

// ── Channel Card ──────────────────────────────────────────────────────────────
const ChannelCard: React.FC<{
  channel: XtreamChannel;
  nowPlaying?: string;
  progress?: number;
  onClick: () => void;
}> = ({ channel, nowPlaying, progress, onClick }) => {
  const logoUrl = useLogoUrl(channel.stream_icon || '');
  const [logoErr, setLogoErr] = useState(false);
  return (
    <button onClick={onClick}
      className="group relative flex-shrink-0 w-36 rounded-xl overflow-hidden border border-white/[0.06]
        transition-all duration-200 hover:scale-105 hover:border-cyan-500/30 text-left bg-white/[0.03]">
      <div className="p-3">
        <div className="w-full aspect-video rounded-lg bg-white/5 flex items-center justify-center mb-2 overflow-hidden">
          {logoUrl && !logoErr ? (
            <img src={logoUrl} alt="" className="max-w-full max-h-full object-contain p-1"
              loading="lazy" onError={() => setLogoErr(true)} />
          ) : (
            <Tv size={20} className="text-white/20" />
          )}
        </div>
        <p className="text-white/80 text-[10px] font-semibold truncate">{channel.name}</p>
        {nowPlaying && (
          <div className="flex items-center gap-1 mt-0.5">
            <div className="w-1.5 h-1.5 rounded-full bg-red-500 animate-pulse shrink-0" />
            <p className="text-white/35 text-[9px] truncate">{nowPlaying}</p>
          </div>
        )}
        {progress !== undefined && progress > 0 && (
          <div className="mt-1.5 h-1 bg-white/10 rounded-full">
            <div className="h-full bg-cyan-400 rounded-full" style={{ width: `${Math.min(progress, 100)}%` }} />
          </div>
        )}
      </div>
    </button>
  );
};

// ── Content Row ───────────────────────────────────────────────────────────────
const ContentRow: React.FC<{
  title: string;
  icon: React.ReactNode;
  onSeeAll?: () => void;
  children: React.ReactNode;
}> = ({ title, icon, onSeeAll, children }) => (
  <section>
    <div className="flex items-center gap-2 mb-3 px-8">
      <span className="theme-text-accent">{icon}</span>
      <h2 className="text-white text-base font-bold">{title}</h2>
      {onSeeAll && (
        <button onClick={onSeeAll}
          className="ml-auto flex items-center gap-1 text-white/30 text-xs hover:text-white/60 transition-colors">
          See all <ChevronRight size={12} />
        </button>
      )}
    </div>
    <div className="px-8">
      <div className="flex gap-3 overflow-x-auto pb-2 scrollbar-none">
        {children}
      </div>
    </div>
  </section>
);

// ── Hero Banner ───────────────────────────────────────────────────────────────
const HeroBanner: React.FC<{
  items: XtreamMovie[];
  onPlay: (movie: XtreamMovie) => void;
}> = ({ items, onPlay }) => {
  const [activeIdx, setActiveIdx] = useState(0);
  const [imgErr, setImgErr] = useState(false);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const startTimer = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current);
    timerRef.current = setInterval(() => {
      setActiveIdx(i => (i + 1) % items.length);
      setImgErr(false);
    }, 5000);
  }, [items.length]);

  useEffect(() => {
    if (items.length < 2) return;
    startTimer();
    return () => { if (timerRef.current) clearInterval(timerRef.current); };
  }, [startTimer, items.length]);

  if (items.length === 0) return null;
  const movie = items[activeIdx] ?? items[0];

  return (
    <div className="relative h-72 overflow-hidden">
      {movie.stream_icon && !imgErr ? (
        <img src={movie.stream_icon} alt="" key={movie.stream_id}
          className="absolute inset-0 w-full h-full object-cover transition-opacity duration-700"
          onError={() => setImgErr(true)} />
      ) : (
        <div className="absolute inset-0 bg-gradient-to-br from-cyan-900/40 to-purple-900/40" />
      )}
      <div className="absolute inset-0 bg-gradient-to-t from-black via-black/60 to-black/10" />
      <div className="absolute inset-0 bg-gradient-to-r from-black/80 via-transparent to-transparent" />
      <div className="absolute bottom-0 left-0 px-8 pb-6 max-w-2xl">
        <div className="flex items-center gap-2 mb-2">
          <span className="px-2 py-0.5 bg-cyan-500 text-black text-[9px] font-bold uppercase tracking-wide rounded">
            App Mode
          </span>
          {movie.rating_5based && (
            <div className="flex items-center gap-1">
              <Star size={10} className="text-yellow-400 fill-yellow-400" />
              <span className="text-yellow-300 text-xs font-bold">{movie.rating_5based}</span>
            </div>
          )}
        </div>
        <h2 className="text-white text-2xl font-extrabold leading-tight mb-1 line-clamp-2">{movie.name}</h2>
        {(movie as any).plot && (
          <p className="text-white/50 text-xs line-clamp-2 mb-3">{(movie as any).plot}</p>
        )}
        <div className="flex items-center gap-3">
          <button onClick={() => onPlay(movie)}
            className="flex items-center gap-2 px-4 py-2 bg-white text-black text-sm font-bold
              rounded-xl hover:bg-white/90 transition-colors">
            <Play size={14} className="fill-black" /> Play
          </button>
          <button onClick={() => onPlay(movie)}
            className="flex items-center gap-2 px-4 py-2 bg-white/15 backdrop-blur text-white text-sm font-medium
              rounded-xl hover:bg-white/25 transition-colors border border-white/20">
            <Info size={14} /> More Info
          </button>
        </div>
      </div>
      {items.length > 1 && (
        <div className="absolute bottom-4 right-6 flex gap-1.5">
          {items.map((_, i) => (
            <button key={i}
              onClick={() => { setActiveIdx(i); setImgErr(false); startTimer(); }}
              className={`h-1.5 rounded-full transition-all ${i === activeIdx ? 'bg-white w-4' : 'bg-white/40 w-1.5'}`}
            />
          ))}
        </div>
      )}
    </div>
  );
};

// ── Main Component ────────────────────────────────────────────────────────────
export const HomePage: React.FC<HomePageProps> = ({
  recentlyWatched,
  channels,
  movies,
  series,
  favorites,
  epgData,
  profileId,
  onPlayChannel,
  onPlayMovie,
  onNavigate,
}) => {
  const greeting = useMemo(getGreeting, []);
  const [cachedFavChannels, setCachedFavChannels] = useState<XtreamChannel[]>([]);

  useEffect(() => {
    if (!profileId || favorites.length === 0) {
      setCachedFavChannels([]);
      return;
    }
    let cancelled = false;
    void resolveCachedByIds(profileId, favorites).then(resolved => {
      if (!cancelled) setCachedFavChannels(resolved.channels);
    });
    return () => { cancelled = true; };
  }, [profileId, favorites]);

  const continueWatching = useMemo(() =>
    recentlyWatched
      .filter(w => {
        const pct = w.duration > 0 ? (w.position / w.duration) * 100 : (w.percentage ?? 0);
        return pct > 5 && pct < 85;
      })
      .sort((a, b) => b.lastWatched - a.lastWatched)
      .slice(0, 15),
    [recentlyWatched]
  );

  const heroMovies = useMemo(() =>
    [...movies]
      .filter(m => m.stream_icon && Number(m.rating_5based || 0) >= 3)
      .sort((a, b) => Number(b.rating_5based || 0) - Number(a.rating_5based || 0))
      .slice(0, 6),
    [movies]
  );

  const topRated = useMemo(() =>
    [...movies]
      .sort((a, b) => Number(b.rating_5based || b.rating || 0) - Number(a.rating_5based || a.rating || 0))
      .slice(0, 20),
    [movies]
  );

  const recentlyAdded = useMemo(() =>
    [...movies]
      .sort((a, b) => parseInt(b.added || '0', 10) - parseInt(a.added || '0', 10))
      .slice(0, 20),
    [movies]
  );

  const favoriteChannels = useMemo(() => {
    const seen = new Set<string>();
    const out: XtreamChannel[] = [];
    for (const c of [...channels, ...cachedFavChannels]) {
      const id = String(c.stream_id);
      if (!favorites.includes(id) || seen.has(id)) continue;
      seen.add(id);
      out.push(c);
      if (out.length >= 15) break;
    }
    return out;
  }, [channels, cachedFavChannels, favorites]);

  const liveNow = useMemo(() => {
    if (!epgData || channels.length === 0) return channels.slice(0, 15);
    const now = new Date();
    const withEpg = channels.filter(ch => {
      const programs = epgData.programs[ch.epg_channel_id || ''] ?? [];
      return getNowPlaying(programs, now) !== null;
    });
    return (withEpg.length > 0 ? withEpg : channels).slice(0, 15);
  }, [channels, epgData]);

  return (
    <div className="flex-1 overflow-y-auto scrollbar-none">
      {/* Hero Banner */}
      {heroMovies.length > 0 && <HeroBanner items={heroMovies} onPlay={onPlayMovie} />}

      {/* Greeting bar */}
      <div className="px-8 py-4 flex items-center justify-between border-b border-white/[0.04]">
        <div>
          <p className="text-white/40 text-xs uppercase tracking-widest">{greeting}</p>
          <h1 className="text-white text-lg font-extrabold">
            DYLANDOS <span className="neon-text">IPTV ULTIMATE</span>
          </h1>
        </div>
        <div className="flex items-center gap-4 text-white/30 text-xs">
          <button onClick={() => onNavigate('live')} className="flex items-center gap-1.5 hover:text-white/60 transition-colors">
            <Tv size={13} />{channels.length} Channels
          </button>
          <button onClick={() => onNavigate('movies')} className="flex items-center gap-1.5 hover:text-white/60 transition-colors">
            <Film size={13} />{movies.length} Movies
          </button>
          <button onClick={() => onNavigate('series')} className="flex items-center gap-1.5 hover:text-white/60 transition-colors">
            <Layout size={13} />{series.length} Series
          </button>
          <button onClick={() => onNavigate('search')} className="flex items-center gap-1.5 hover:text-white/60 transition-colors">
            <Search size={13} />Search
          </button>
        </div>
      </div>

      <div className="py-6 space-y-8">
        {/* Play Next — Sparkle-style resume rail */}
        {continueWatching.length > 0 && (
          <ContentRow title="Play Next" icon={<Zap size={15} />}>
            {continueWatching.map((w, i) => {
              const ch = channels.find(c => String(c.stream_id) === w.streamId);
              const mv = movies.find(m => String(m.stream_id) === w.streamId);
              const pct = w.duration > 0 ? Math.round((w.position / w.duration) * 100) : (w.percentage ?? 0);
              if (ch) return (
                <ChannelCard key={i} channel={ch} nowPlaying={w.title} progress={pct}
                  onClick={() => onPlayChannel(ch)} />
              );
              if (mv) return (
                <PosterCard key={i} title={mv.name} posterUrl={mv.stream_icon} progress={pct}
                  onClick={() => onPlayMovie(mv)} />
              );
              return <PosterCard key={i} title={w.title || 'Unknown'} progress={pct} onClick={() => {}} />;
            })}
          </ContentRow>
        )}

        {/* Live Now */}
        {liveNow.length > 0 && (
          <ContentRow title="Live Now" icon={<Radio size={15} />} onSeeAll={() => onNavigate('live')}>
            {liveNow.map(ch => {
              const programs = epgData?.programs[ch.epg_channel_id || ''] ?? [];
              const nowPlaying = getNowPlaying(programs, new Date());
              return (
                <ChannelCard key={ch.stream_id} channel={ch} nowPlaying={nowPlaying?.title}
                  onClick={() => onPlayChannel(ch)} />
              );
            })}
          </ContentRow>
        )}

        {/* Favorites */}
        {favoriteChannels.length > 0 && (
          <ContentRow title="My Favorites" icon={<Heart size={15} />} onSeeAll={() => onNavigate('favorites')}>
            {favoriteChannels.map(ch => (
              <ChannelCard key={ch.stream_id} channel={ch} onClick={() => onPlayChannel(ch)} />
            ))}
          </ContentRow>
        )}

        {/* Top Rated */}
        {topRated.length > 0 && (
          <ContentRow title="Top Rated" icon={<Star size={15} />} onSeeAll={() => onNavigate('movies')}>
            {topRated.map(m => (
              <PosterCard key={m.stream_id} title={m.name} posterUrl={m.stream_icon}
                rating={m.rating_5based ? Number(m.rating_5based) * 2 : undefined}
                onClick={() => onPlayMovie(m)} />
            ))}
          </ContentRow>
        )}

        {/* Recently Added */}
        {recentlyAdded.length > 0 && (
          <ContentRow title="Recently Added" icon={<Sparkles size={15} />} onSeeAll={() => onNavigate('movies')}>
            {recentlyAdded.map(m => (
              <PosterCard key={m.stream_id} title={m.name} posterUrl={m.stream_icon} isNew
                onClick={() => onPlayMovie(m)} />
            ))}
          </ContentRow>
        )}

        {/* Series */}
        {series.length > 0 && (
          <ContentRow title="Series" icon={<Layout size={15} />} onSeeAll={() => onNavigate('series')}>
            {series.slice(0, 20).map(s => (
              <PosterCard key={s.series_id} title={s.name} posterUrl={s.cover}
                rating={s.rating ? Number(s.rating) : undefined}
                onClick={() => onNavigate('series')} />
            ))}
          </ContentRow>
        )}

        {/* Quick Nav */}
        <div className="px-8 pt-2 pb-4">
          <div className="grid grid-cols-4 gap-3">
            {[
              { icon: <Tv size={18} />, label: 'Live TV', sub: `${channels.length}`, page: 'live' as const },
              { icon: <Film size={18} />, label: 'Movies', sub: `${movies.length}`, page: 'movies' as const },
              { icon: <Layout size={18} />, label: 'Series', sub: `${series.length}`, page: 'series' as const },
              { icon: <Search size={18} />, label: 'Search', sub: 'Find anything', page: 'search' as const },
            ].map(item => (
              <button key={item.page} onClick={() => onNavigate(item.page)}
                className="neon-card rounded-2xl p-4 flex flex-col items-center gap-2 group hover:border-cyan-500/30 transition-all">
                <span className="theme-text-accent group-hover:scale-110 transition-transform">{item.icon}</span>
                <p className="text-white text-sm font-semibold">{item.label}</p>
                <p className="text-white/30 text-xs">{item.sub}</p>
              </button>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
};
