// ─── On-Device Recommendation Engine ─────────────────────────────────────────
// Scores content using watch history + genre affinity + time-of-day patterns.
// Pure TypeScript — no external ML dependency for V1.

import { XtreamMovie, XtreamChannel, WatchProgress } from '../types/xtream';
import { TMDBMovieDetail } from './tmdb';

interface ScoredItem {
  id: string;
  type: 'movie' | 'live';
  score: number;
  reason: string;
  data: XtreamMovie | XtreamChannel;
}

export class RecommendationEngine {
  // ── Genre Affinity ────────────────────────────────────────────────────────
  // Returns a map of genre → normalized weight (0.0–1.0)

  buildGenreAffinity(
    watchHistory: WatchProgress[],
    tmdbCache: Map<number, TMDBMovieDetail>
  ): Record<string, number> {
    const counts: Record<string, number> = {};
    let total = 0;

    for (const w of watchHistory) {
      if (w.streamType !== 'movie') continue;
      const streamId = Number(w.streamId);
      const tmdb = tmdbCache.get(streamId);
      if (!tmdb?.genres) continue;
      tmdb.genres.forEach(g => {
        counts[g.name] = (counts[g.name] ?? 0) + 1;
        total++;
      });
    }

    if (total === 0) return {};
    return Object.fromEntries(
      Object.entries(counts).map(([k, v]) => [k, v / total])
    );
  }

  // ── Time-of-Day Patterns ──────────────────────────────────────────────────
  // Returns a map of hour → set of most-watched streamIds

  buildHourlyPatterns(watchHistory: WatchProgress[]): Map<number, string[]> {
    const patterns = new Map<number, Map<string, number>>();

    for (const w of watchHistory) {
      const hour = new Date(w.lastWatched).getHours();
      if (!patterns.has(hour)) patterns.set(hour, new Map());
      const hourMap = patterns.get(hour)!;
      const key = w.streamId ?? w.key;
      hourMap.set(key, (hourMap.get(key) ?? 0) + 1);
    }

    // Convert to sorted arrays
    const result = new Map<number, string[]>();
    patterns.forEach((hourMap, hour) => {
      const sorted = [...hourMap.entries()]
        .sort((a, b) => b[1] - a[1])
        .slice(0, 5)
        .map(([id]) => id);
      result.set(hour, sorted);
    });

    return result;
  }

  // ── Score a single item ───────────────────────────────────────────────────

  scoreItem(
    id: string,
    type: 'movie' | 'live',
    data: XtreamMovie | XtreamChannel,
    genreAffinity: Record<string, number>,
    watchedIds: Set<string>,
    hourlyPatterns: Map<number, string[]>,
    currentHour: number,
    tmdbData?: TMDBMovieDetail
  ): ScoredItem {
    let score = 0;
    let reason = '';

    // Signal 1: Genre affinity (weight: 40%)
    if (tmdbData?.genres) {
      const genreBoost = tmdbData.genres.reduce(
        (sum, g) => sum + (genreAffinity[g.name] ?? 0), 0
      );
      score += Math.min(genreBoost, 1) * 0.4;
      if (genreBoost > 0.1) reason = 'Matches your taste';
    }

    // Signal 2: TMDB rating (weight: 30%)
    if (tmdbData?.vote_average && tmdbData.vote_average >= 7.0) {
      score += (tmdbData.vote_average / 10) * 0.3;
      if (!reason) reason = 'Highly rated';
    }

    // Signal 3: Not yet watched (weight: 20%)
    if (!watchedIds.has(id)) {
      score += 0.2;
    }

    // Signal 4: Time-of-day (weight: 10%)
    const hourRecs = hourlyPatterns.get(currentHour) ?? [];
    if (hourRecs.includes(id)) {
      score += 0.1;
      if (!reason) reason = 'You usually watch this now';
    }

    return { id, type, score, reason, data };
  }

  // ── Get full recommendations list ─────────────────────────────────────────

  getRecommendations(
    movies: XtreamMovie[],
    channels: XtreamChannel[],
    watchHistory: WatchProgress[],
    tmdbCache: Map<number, TMDBMovieDetail>,
    limit = 20
  ): Array<XtreamMovie | XtreamChannel> {
    const genreAffinity = this.buildGenreAffinity(watchHistory, tmdbCache);
    const hourlyPatterns = this.buildHourlyPatterns(watchHistory);
    const currentHour = new Date().getHours();
    const watchedIds = new Set(
      watchHistory
        .filter(w => (w.percentage ?? 0) > 80) // watched >80% = completed
        .map(w => w.streamId ?? w.key)
    );

    const scored: ScoredItem[] = [];

    // Score movies
    for (const movie of movies) {
      const id = String(movie.stream_id);
      const tmdb = movie.tmdb_id ? tmdbCache.get(movie.tmdb_id) : undefined;
      scored.push(this.scoreItem(id, 'movie', movie, genreAffinity, watchedIds, hourlyPatterns, currentHour, tmdb));
    }

    // Score live channels (no TMDB, but hourly patterns apply)
    for (const ch of channels) {
      const id = String(ch.stream_id);
      scored.push(this.scoreItem(id, 'live', ch, genreAffinity, watchedIds, hourlyPatterns, currentHour));
    }

    return scored
      .sort((a, b) => b.score - a.score)
      .slice(0, limit)
      .map(s => s.data);
  }

  // ── Continue Watching ─────────────────────────────────────────────────────
  // Items with 5% < progress < 85% (in progress, not completed)

  getContinueWatching(watchHistory: WatchProgress[]): WatchProgress[] {
    return watchHistory
      .filter(w => {
        const pct = w.percentage ?? (w.duration > 0 ? (w.position / w.duration) * 100 : 0);
        return pct > 5 && pct < 85;
      })
      .sort((a, b) => b.lastWatched - a.lastWatched)
      .slice(0, 20);
  }

  // ── Top Rated ─────────────────────────────────────────────────────────────

  getTopRated(
    movies: XtreamMovie[],
    tmdbCache: Map<number, TMDBMovieDetail>,
    minRating = 7.5,
    limit = 20
  ): XtreamMovie[] {
    return movies
      .filter(m => {
        const tmdb = m.tmdb_id ? tmdbCache.get(m.tmdb_id) : undefined;
        return tmdb && tmdb.vote_average >= minRating;
      })
      .sort((a, b) => {
        const rA = a.tmdb_id ? (tmdbCache.get(a.tmdb_id)?.vote_average ?? 0) : 0;
        const rB = b.tmdb_id ? (tmdbCache.get(b.tmdb_id)?.vote_average ?? 0) : 0;
        return rB - rA;
      })
      .slice(0, limit);
  }

  // ── Recently Added ────────────────────────────────────────────────────────

  getRecentlyAdded(movies: XtreamMovie[], limit = 20): XtreamMovie[] {
    return [...movies]
      .sort((a, b) => {
        const aTime = parseInt(a.added ?? '0', 10);
        const bTime = parseInt(b.added ?? '0', 10);
        return bTime - aTime;
      })
      .slice(0, limit);
  }
}

export const recommendationEngine = new RecommendationEngine();
