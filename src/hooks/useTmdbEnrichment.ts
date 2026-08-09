import { useEffect, useRef, useState } from 'react';
import { XtreamMovie } from '../types/xtream';
import { getTMDB, TMDBService } from '../services/tmdb';
import { AppSettings } from '../types/settings';

interface TMDBMovieDetail {
  id: number;
  title: string;
  overview: string;
  poster_path: string | null;
  backdrop_path: string | null;
  release_date: string;
  vote_average: number;
  runtime: number;
  genres: { id: number; name: string }[];
}

export const useTmdbEnrichment = (
  movies: XtreamMovie[],
  settings: AppSettings
) => {
  const [tmdbData, setTmdbData] = useState<Map<number, TMDBMovieDetail>>(new Map());
  const [enrichProgress, setEnrichProgress] = useState({ done: 0, total: 0 });
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    if (!settings.tmdbEnabled || !settings.tmdbApiKey || movies.length === 0) return;

    abortRef.current?.abort();
    abortRef.current = new AbortController();

    const tmdb = getTMDB(settings.tmdbApiKey, settings.tmdbLanguage);

    const firstBatch = movies.slice(0, 500).map(m => ({
      streamId: m.stream_id,
      name: m.name,
    }));

    setEnrichProgress({ done: 0, total: firstBatch.length });

    tmdb.enrichBatch(
      firstBatch,
      (done, total) => setEnrichProgress({ done, total }),
      abortRef.current.signal
    ).then(data => {
      setTmdbData(prev => new Map([...prev, ...data]));
    });

    return () => abortRef.current?.abort();
  }, [movies, settings.tmdbEnabled, settings.tmdbApiKey, settings.tmdbLanguage]);

  return { tmdbData, enrichProgress };
};
