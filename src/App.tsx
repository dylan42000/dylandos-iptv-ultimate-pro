import React, { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { TitleBar } from './components/TitleBar';
import { Sidebar } from './components/Sidebar';
import { ProfileSelectionDialog } from './components/ProfileSelectionDialog';
import { VideoPlayerEngine } from './components/VideoPlayerEngine';
import { UpdateBanner } from './components/UpdateBanner';
import { useToast } from './components/ToastProvider';

import { HomePage } from './pages/HomePage';
import { LiveTVPage } from './pages/LiveTVPage';
import { GuidePage } from './pages/GuidePage';
import { MoviesPage } from './pages/MoviesPage';
import { SeriesPage } from './pages/SeriesPage';
import { SearchPage } from './pages/SearchPage';
import { FavoritesPage } from './pages/FavoritesPage';
import { SettingsPage } from './pages/SettingsPage';
import { DVRPage } from './pages/DVRPage';
import { DownloadsPage } from './pages/DownloadsPage';
import { CustomListsPage } from './pages/CustomListsPage';
import { MultiViewPage } from './pages/MultiViewPage';
import { PinEntryDialog } from './components/PinEntryDialog';

import { useSettings } from './hooks/useSettings';
import { useKeyboardShortcuts } from './hooks/useKeyboardShortcuts';
import { useChannelNumberJump } from './hooks/useChannelNumberJump';
import { useAutoRefresh } from './hooks/useAutoRefresh';
import { usePip } from './hooks/usePip';

import {
  loadProfiles,
  saveProfiles,
  loadActiveProfileId,
  saveActiveProfileId,
  loadFavorites,
  saveFavorites,
  loadRecentlyWatched,
  pushLastChannel,
  loadLastChannel,
} from './services/storage';
import { xtreamApi as xtream } from './services/xtreamApi';
import { getNowPlaying, loadEpgFromAllSources } from './services/xmltvEpg';
import { EPG_SOURCE_DEFAULTS } from './services/epgSourceDefaults';
import {
  getCatalogCategory,
  getCatalogCategoryMeta,
  setCatalogCategory,
  clearCatalogMemory,
  warmCatalogIndex,
  type CatalogMeta,
} from './services/catalogCache';
import { setEpgRuntime, clearEpgRuntime } from './services/epgRuntimeStore';
import { useEpgRuntime } from './hooks/useEpgRuntime';
import {
  filterCategories,
  filterChannels,
  filterMovies,
  filterSeries,
  sortCategoriesByPriority,
  type ContentFilterSettings,
} from './services/contentFilter';
import { buildEpgProgramIndex, getProgramsForChannel as resolveProgramsForChannel } from './utils/epgMatching';
import { clearShortEpgCache } from './services/shortEpgCache';
import { dvrScheduler, type EPGProgramRef } from './services/dvrScheduler';
import { parentalControls } from './services/parentalControls';
import {
  installFieldTelemetry,
  setTelemetryEnabled,
  recordTelemetry,
  classifyPlaybackFailure,
} from './services/fieldTelemetry';

import type { AppPage } from './types/settings';
import type {
  XtreamProfile,
  XtreamChannel,
  XtreamMovie,
  XtreamSeries,
  XtreamSeriesInfo,
  XtreamCategory,
  XtreamEpisode,
  WatchProgress,
} from './types/xtream';
import type { EPGProgram, XMLTVSource } from './types/epg';

const formatXtreamExpiry = (expDate?: string | null): string | null => {
  if (!expDate || expDate === '0') {
    return null;
  }

  const numeric = Number(expDate);
  const date = Number.isFinite(numeric)
    ? new Date(numeric * 1000)
    : new Date(expDate);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return date.toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
};

const getUniqueVodExtensions = (preferred?: string): string[] => {
  const cleanPreferred = preferred?.replace(/^\.+/, '').trim().toLowerCase();
  return Array.from(new Set([cleanPreferred || 'mp4', 'mp4', 'mkv', 'avi', 'ts'].filter(Boolean)));
};

export const App: React.FC = () => {
  const { success, error: toastError, info } = useToast();

  // ── Settings ─────────────────────────────────────────────────────────
  const { settings, updateSettings } = useSettings();
  const hasAppliedMpvPrimary = useRef(false);

  // ── Profile state ────────────────────────────────────────────────────
  const [profiles, setProfiles] = useState<XtreamProfile[]>([]);
  const [activeProfileId, setActiveProfileId] = useState<string | null>(null);
  const activeProfileIdRef = useRef<string | null>(null);
  activeProfileIdRef.current = activeProfileId;
  const [showProfileDialog, setShowProfileDialog] = useState(false);
  const [isConnected, setIsConnected] = useState(false);

  // ── Navigation ───────────────────────────────────────────────────────
  const [currentPage, setCurrentPage] = useState<AppPage>('home');
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);

  // ── Content data (category-scoped windows — not full panel catalogs) ──
  const [liveCategories, setLiveCategories] = useState<XtreamCategory[]>([]);
  const [channels, setChannels] = useState<XtreamChannel[]>([]);
  const [vodCategories, setVodCategories] = useState<XtreamCategory[]>([]);
  const [movies, setMovies] = useState<XtreamMovie[]>([]);
  const [seriesCategories, setSeriesCategories] = useState<XtreamCategory[]>([]);
  const [series, setSeries] = useState<XtreamSeries[]>([]);
  const { epgData, revision: epgRevision } = useEpgRuntime();
  const [isLoading, setIsLoading] = useState(false);
  const [isLiveLoading, setIsLiveLoading] = useState(false);
  const [isVodLoading, setIsVodLoading] = useState(false);
  const [isSeriesLoading, setIsSeriesLoading] = useState(false);
  const [isEpgLoading, setIsEpgLoading] = useState(false);
  const [hasLoadedLive, setHasLoadedLive] = useState(false);
  const [hasLoadedVod, setHasLoadedVod] = useState(false);
  const [hasLoadedSeries, setHasLoadedSeries] = useState(false);
  const [activeLiveCategoryId, setActiveLiveCategoryId] = useState<string | null>(null);
  const [activeVodCategoryId, setActiveVodCategoryId] = useState<string | null>(null);
  const [activeSeriesCategoryId, setActiveSeriesCategoryId] = useState<string | null>(null);
  const [liveCatalogMeta, setLiveCatalogMeta] = useState<CatalogMeta | null>(null);
  const [vodCatalogMeta, setVodCatalogMeta] = useState<CatalogMeta | null>(null);
  const [seriesCatalogMeta, setSeriesCatalogMeta] = useState<CatalogMeta | null>(null);
  const catalogAbortRef = useRef<AbortController | null>(null);
  const warmAbortRef = useRef<AbortController | null>(null);

  // ── Player state ─────────────────────────────────────────────────────
  const [streamUrl, setStreamUrl] = useState<string | null>(null);
  const [streamTitle, setStreamTitle] = useState('');
  const [streamType, setStreamType] = useState<'live' | 'vod'>('live');
  const [activeChannel, setActiveChannel] = useState<XtreamChannel | null>(null);
  const [fallbackStreamUrls, setFallbackStreamUrls] = useState<string[]>([]);
  const [epgNowTick, setEpgNowTick] = useState(() => Date.now());
  // Cached allowed output formats from profile auth (used for `liveFormat: 'auto'`)
  const [activeAuthAllowedFormats, setActiveAuthAllowedFormats] = useState<string[] | null>(null);

  // ── Parental PIN gate ────────────────────────────────────────────────
  const [pinDialog, setPinDialog] = useState<{
    categoryName: string;
    categoryId: string;
    kind: 'live' | 'vod' | 'series';
    error?: string;
  } | null>(null);

  const { isPipOpen, openPip, closePip } = usePip();

  // ── Favorites & watch progress ───────────────────────────────────────
  const [favorites, setFavorites] = useState<string[]>([]);
  const [watchProgressList, setWatchProgressList] = useState<WatchProgress[]>([]);

  // ── Refs ──────────────────────────────────────────────────────────────
  const videoRef = useRef<HTMLVideoElement>(null);

  // ── Field telemetry bootstrap ────────────────────────────────────────
  useEffect(() => {
    installFieldTelemetry({
      appVersion: '4.8.0',
      enabled: settings.fieldTelemetryEnabled !== false,
    });
  }, []);

  useEffect(() => {
    setTelemetryEnabled(settings.fieldTelemetryEnabled !== false);
  }, [settings.fieldTelemetryEnabled]);

  useEffect(() => {
    parentalControls.setEnabled(Boolean(settings.parentalControlsEnabled));
  }, [settings.parentalControlsEnabled]);

  useEffect(() => {
    const api = window.electronAPI;
    if (!api?.on) return;
    const onFallback = (data: { reason?: string; codecHint?: string }) => {
      recordTelemetry('hwdec_fallback', 'HWDEC_SOFT', data?.reason || 'hwdec soft fallback', {
        codecHint: data?.codecHint || 'unknown',
      });
      info('GPU decode failed — retrying with software decode');
    };
    const onError = (data: { message?: string; softFallback?: boolean }) => {
      if (data?.softFallback) return;
      const msg = data?.message || 'playback error';
      recordTelemetry('playback_failure', classifyPlaybackFailure(msg), msg);
    };
    const onCrash = () => {
      recordTelemetry('mpv_crash', 'MPV_CRASH', 'MPV process crashed');
    };
    api.on('mpv:hwdec-fallback', onFallback);
    api.on('mpv:error', onError);
    api.on('mpv:crashed', onCrash);
    return () => {
      api.off?.('mpv:hwdec-fallback', onFallback);
      api.off?.('mpv:error', onError);
      api.off?.('mpv:crashed', onCrash);
    };
  }, [info]);

  // ── Init: Load profiles ──────────────────────────────────────────────
  useEffect(() => {
    const saved = loadProfiles();
    setProfiles(saved);
    const favs = loadFavorites();
    setFavorites(favs);
    const progress = loadRecentlyWatched();
    setWatchProgressList(progress);

    const savedActiveId = loadActiveProfileId();
    if (saved.length === 0) {
      setShowProfileDialog(true);
    } else if (savedActiveId) {
      const active = saved.find(p => p.id === savedActiveId);
      if (active) {
        setActiveProfileId(savedActiveId);
        connectToProfile(active);
      } else {
        setShowProfileDialog(true);
      }
    } else {
      setShowProfileDialog(true);
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (hasAppliedMpvPrimary.current) {
      return;
    }

    hasAppliedMpvPrimary.current = true;

    if (settings.preferredEngine !== 'mpv') {
      void updateSettings({ preferredEngine: 'mpv' });
    }
  }, [settings.preferredEngine, updateSettings]);

  useEffect(() => {
    const timer = window.setInterval(() => {
      setEpgNowTick(Date.now());
    }, 30000);

    return () => {
      window.clearInterval(timer);
    };
  }, []);

  // ── Reset loaded content state (profile switch) ───────────────────────
  const resetLoadedContent = useCallback(() => {
    catalogAbortRef.current?.abort();
    catalogAbortRef.current = null;
    warmAbortRef.current?.abort();
    warmAbortRef.current = null;
    setLiveCategories([]);
    setChannels([]);
    setVodCategories([]);
    setMovies([]);
    setSeriesCategories([]);
    setSeries([]);
    clearEpgRuntime();
    clearShortEpgCache();
    clearCatalogMemory();
    setActiveLiveCategoryId(null);
    setActiveVodCategoryId(null);
    setActiveSeriesCategoryId(null);
    setLiveCatalogMeta(null);
    setVodCatalogMeta(null);
    setSeriesCatalogMeta(null);
    setHasLoadedLive(false);
    setHasLoadedVod(false);
    setHasLoadedSeries(false);
    setIsLiveLoading(false);
    setIsVodLoading(false);
    setIsSeriesLoading(false);
    setIsEpgLoading(false);
  }, []);

  // ── Category-scoped loaders (cache + single-category React window) ────
  const loadLiveCategory = useCallback(async (
    categoryId: string,
    signal?: AbortSignal,
    profileIdOverride?: string,
    forceRefresh = false
  ) => {
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    if (!profileId || !categoryId) return;
    try {
      setIsLiveLoading(true);
      if (!forceRefresh) {
        const cached = await getCatalogCategory<XtreamChannel>(profileId, 'live', categoryId);
        if (cached) {
          if (signal?.aborted) return;
          setChannels(cached);
          setActiveLiveCategoryId(categoryId);
          setLiveCatalogMeta(await getCatalogCategoryMeta(profileId, 'live', categoryId));
          return;
        }
      }
      const streams = await xtream.getLiveStreams(categoryId, signal);
      if (signal?.aborted) return;
      const meta = await setCatalogCategory(profileId, 'live', categoryId, streams);
      setChannels(streams);
      setActiveLiveCategoryId(categoryId);
      setLiveCatalogMeta(meta);
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Live TV category load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsLiveLoading(false);
    }
  }, [toastError]);

  const loadVodCategory = useCallback(async (
    categoryId: string,
    signal?: AbortSignal,
    profileIdOverride?: string,
    forceRefresh = false
  ) => {
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    if (!profileId || !categoryId) return;
    try {
      setIsVodLoading(true);
      if (!forceRefresh) {
        const cached = await getCatalogCategory<XtreamMovie>(profileId, 'vod', categoryId);
        if (cached) {
          if (signal?.aborted) return;
          setMovies(cached);
          setActiveVodCategoryId(categoryId);
          setVodCatalogMeta(await getCatalogCategoryMeta(profileId, 'vod', categoryId));
          return;
        }
      }
      const streams = await xtream.getVodStreams(categoryId, signal);
      if (signal?.aborted) return;
      // Cap category size in React state — huge panels freeze/OOM Electron.
      const VOD_CATEGORY_CAP = 500;
      const capped = streams.length > VOD_CATEGORY_CAP
        ? streams.slice(0, VOD_CATEGORY_CAP)
        : streams;
      const meta = await setCatalogCategory(profileId, 'vod', categoryId, capped);
      setMovies(capped);
      setActiveVodCategoryId(categoryId);
      setVodCatalogMeta(meta);
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Movies category load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsVodLoading(false);
    }
  }, [toastError]);

  const loadSeriesCategory = useCallback(async (
    categoryId: string,
    signal?: AbortSignal,
    profileIdOverride?: string,
    forceRefresh = false
  ) => {
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    if (!profileId || !categoryId) return;
    try {
      setIsSeriesLoading(true);
      if (!forceRefresh) {
        const cached = await getCatalogCategory<XtreamSeries>(profileId, 'series', categoryId);
        if (cached) {
          if (signal?.aborted) return;
          setSeries(cached);
          setActiveSeriesCategoryId(categoryId);
          setSeriesCatalogMeta(await getCatalogCategoryMeta(profileId, 'series', categoryId));
          return;
        }
      }
      const list = await xtream.getSeries(categoryId, signal);
      if (signal?.aborted) return;
      const SERIES_CATEGORY_CAP = 500;
      const capped = list.length > SERIES_CATEGORY_CAP
        ? list.slice(0, SERIES_CATEGORY_CAP)
        : list;
      const meta = await setCatalogCategory(profileId, 'series', categoryId, capped);
      setSeries(capped);
      setActiveSeriesCategoryId(categoryId);
      setSeriesCatalogMeta(meta);
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Series category load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsSeriesLoading(false);
    }
  }, [toastError]);

  const loadLiveContent = useCallback(async (profileIdOverride?: string) => {
    if (isLiveLoading || hasLoadedLive) return;
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    const controller = new AbortController();
    catalogAbortRef.current?.abort();
    catalogAbortRef.current = controller;
    try {
      setIsLiveLoading(true);
      const liveCats = await xtream.getLiveCategories(controller.signal);
      if (controller.signal.aborted) return;
      setLiveCategories(liveCats);
      setHasLoadedLive(true);
      if (settings.parentalControlsEnabled && settings.parentalAutoLockAdult) {
        parentalControls.setEnabled(true);
        parentalControls.autoLockAdult(liveCats);
      }
      const firstId = liveCats[0]?.category_id;
      if (firstId) {
        await loadLiveCategory(String(firstId), controller.signal, profileId ?? undefined);
      } else {
        setChannels([]);
      }
      // Background warm-index ALL live categories for Search/Home
      if (profileId && liveCats.length > 0) {
        warmAbortRef.current?.abort();
        const warm = new AbortController();
        warmAbortRef.current = warm;
        void warmCatalogIndex(
          profileId,
          'live',
          liveCats.map(c => String(c.category_id)),
          (categoryId, signal) => xtream.getLiveStreams(categoryId, signal),
          { concurrency: 2, signal: warm.signal }
        ).then(p => {
          if (!warm.signal.aborted && p.done > 0) {
            console.log('[catalog] Live warm-index done', p);
          }
        });
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Live TV load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsLiveLoading(false);
    }
  }, [isLiveLoading, hasLoadedLive, toastError, loadLiveCategory]);

  const loadVodContent = useCallback(async (profileIdOverride?: string) => {
    if (isVodLoading || hasLoadedVod) return;
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    const controller = new AbortController();
    try {
      setIsVodLoading(true);
      const vodCats = await xtream.getVodCategories(controller.signal);
      if (controller.signal.aborted) return;
      setVodCategories(vodCats);
      setHasLoadedVod(true);
      const firstId = vodCats[0]?.category_id;
      if (firstId) {
        await loadVodCategory(String(firstId), controller.signal, profileId ?? undefined);
      } else {
        setMovies([]);
      }
      if (profileId && vodCats.length > 0) {
        const warm = warmAbortRef.current;
        void warmCatalogIndex(
          profileId,
          'vod',
          vodCats.map(c => String(c.category_id)),
          (categoryId, signal) => xtream.getVodStreams(categoryId, signal),
          { concurrency: 2, signal: warm?.signal }
        ).then(p => {
          if (p.done > 0) console.log('[catalog] VOD warm-index done', p);
        });
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Movies load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsVodLoading(false);
    }
  }, [isVodLoading, hasLoadedVod, toastError, loadVodCategory]);

  const loadSeriesContent = useCallback(async (profileIdOverride?: string) => {
    if (isSeriesLoading || hasLoadedSeries) return;
    const profileId = profileIdOverride ?? activeProfileIdRef.current;
    const controller = new AbortController();
    try {
      setIsSeriesLoading(true);
      const serCats = await xtream.getSeriesCategories(controller.signal);
      if (controller.signal.aborted) return;
      setSeriesCategories(serCats);
      setHasLoadedSeries(true);
      const firstId = serCats[0]?.category_id;
      if (firstId) {
        await loadSeriesCategory(String(firstId), controller.signal, profileId ?? undefined);
      } else {
        setSeries([]);
      }
      if (profileId && serCats.length > 0) {
        const warm = warmAbortRef.current;
        void warmCatalogIndex(
          profileId,
          'series',
          serCats.map(c => String(c.category_id)),
          (categoryId, signal) => xtream.getSeries(categoryId, signal),
          { concurrency: 2, signal: warm?.signal }
        ).then(p => {
          if (p.done > 0) console.log('[catalog] Series warm-index done', p);
        });
      }
    } catch (err: any) {
      if (err?.name === 'AbortError') return;
      toastError(`Series load failed: ${err.message || 'Unknown error'}`);
    } finally {
      setIsSeriesLoading(false);
    }
  }, [isSeriesLoading, hasLoadedSeries, toastError, loadSeriesCategory]);

  // ── Connect to profile ───────────────────────────────────────────────
  const connectToProfile = useCallback(async (profile: XtreamProfile) => {
    try {
      setIsLoading(true);
      setIsConnected(false);
      resetLoadedContent();
      xtream.connect(profile);
      const auth = await xtream.authenticate();
      const userInfo = auth?.user_info;
      const status = String(userInfo?.status ?? '').trim();
      const expiry = formatXtreamExpiry(userInfo?.exp_date);
      const serverMessage = (userInfo as { message?: string } | undefined)?.message;

      if (userInfo?.auth !== 1) {
        throw new Error(serverMessage || `Authentication rejected${status ? ` (${status})` : ''}`);
      }

      if (status && !/^active$/i.test(status)) {
        throw new Error(`Subscription status: ${status}${expiry ? `, expires/expired ${expiry}` : ''}`);
      }

      if (userInfo.auth === 1) {
        setIsConnected(true);
        setIsLoading(false);
        // Cache allowed output formats (used when settings.liveFormat === 'auto')
        try {
          setActiveAuthAllowedFormats(userInfo?.allowed_output_formats ?? null);
        } catch {
          setActiveAuthAllowedFormats(null);
        }
        success(`Connected to ${profile.name || profile.serverUrl}${expiry ? ` - expires ${expiry}` : ''}`);

        // Make app responsive immediately, then load content in phases.
        void loadLiveContent(profile.id);

        // Warm category lists in background (still category-scoped, not full panel).
        window.setTimeout(() => {
          void loadVodContent(profile.id);
          void loadSeriesContent(profile.id);
        }, 600);

        // EPG can be expensive; delay to avoid blocking first paint.
        if (settings.epgEnabled) {
          window.setTimeout(() => {
            void loadEpg();
          }, 1200);
        }
      }
    } catch (err: any) {
      setIsLoading(false);
      setIsConnected(false);
      toastError(`Connection failed: ${err.message || 'Unknown error'}`);
    }
  }, [
    settings.epgEnabled,
    success,
    toastError,
    resetLoadedContent,
    loadLiveContent,
    loadVodContent,
    loadSeriesContent,
  ]);

  // ── EPG loading ──────────────────────────────────────────────────────
  const loadEpg = useCallback(async () => {
    if (isEpgLoading) return;
    try {
      setIsEpgLoading(true);
      const sources: XMLTVSource[] = [];
      const xtreamUrl = xtream.getXmltvUrl();

      console.log('[EPG] Starting EPG load...');
      console.log('[EPG] Xtream XMLTV URL:', xtreamUrl);

      if (xtreamUrl) {
        sources.push({ url: xtreamUrl, name: 'Xtream EPG', enabled: true });
      }

      const configuredExtra = (settings.epgSources ?? [])
        .map(url => url.trim())
        .filter(Boolean);

      // Public mega-feeds are opt-in — they rarely match IPTV names and delay EPG.
      // Live/Guide now hydrate Xtream short EPG per channel when XMLTV misses.
      const usePublic = Boolean((settings as { epgUsePublicFallbacks?: boolean }).epgUsePublicFallbacks);
      const extraUrls = [
        ...configuredExtra,
        ...(usePublic ? [...EPG_SOURCE_DEFAULTS] : []),
      ].filter((url, i, arr) => arr.indexOf(url) === i);

      const extraSources = extraUrls
        .filter(url => url !== xtreamUrl)
        .map((url, index) => ({
          url,
          name: `EPG Source ${index + 1}`,
          enabled: true,
        }));

      sources.push(...extraSources);
      console.log('[EPG] Total sources:', sources.length, sources);

      if (sources.length === 0) {
        console.warn('[EPG] No XMLTV sources — short EPG will power Live/Guide');
        setEpgRuntime({ channels: {}, programs: {} });
        setIsEpgLoading(false);
        return;
      }

      // Provider XMLTV first so UI unlocks quickly; merge extras after.
      const primarySources = sources.slice(0, 1);
      const primary = await loadEpgFromAllSources(primarySources, settings.epgRefreshHours);
      setEpgRuntime(primary);

      let data = primary;
      if (sources.length > 1) {
        data = await loadEpgFromAllSources(sources, settings.epgRefreshHours);
        setEpgRuntime(data);
      }

      const channelCount = Object.keys(data.channels).length;
      const programCount = Object.values(data.programs).reduce((sum, progs) => sum + progs.length, 0);

      console.log('[EPG] Loaded data:', {
        channels: channelCount,
        programs: programCount,
        channelIds: Object.keys(data.channels).slice(0, 10),
      });

      if (channelCount > 0 || programCount > 0) {
        info(`EPG guide loaded: ${channelCount} channels, ${programCount.toLocaleString()} programs`);
      } else {
        console.warn('[EPG] XMLTV empty — Live/Guide will use Xtream short EPG');
      }

      // Expand keyword + series DVR rules from EPG programs
      try {
        dvrScheduler.load();
        const refs: EPGProgramRef[] = [];
        for (const [chId, programs] of Object.entries(data.programs || {})) {
          const chMeta = data.channels?.[chId];
          const chName = chMeta?.displayName || chId;
          for (const prog of programs) {
            const startMs = new Date(prog.startTime).getTime();
            const endMs = new Date(prog.stopTime).getTime();
            if (!Number.isFinite(startMs) || !Number.isFinite(endMs)) continue;
            refs.push({
              channelId: chId,
              channelName: chName,
              programTitle: prog.title || 'Untitled',
              streamUrl: '',
              startTimeMs: startMs,
              endTimeMs: endMs,
            });
          }
        }
        // Attach stream URLs when we can resolve against loaded channels
        const byId = new Map(channels.map(c => [String(c.stream_id), c]));
        const byEpg = new Map(channels.map(c => [String(c.epg_channel_id || ''), c]));
        for (const ref of refs) {
          const ch = byId.get(ref.channelId) || byEpg.get(ref.channelId);
          if (ch) {
            ref.channelId = String(ch.stream_id);
            ref.channelName = ch.name;
            ref.streamUrl = ch.direct_source?.trim()
              ? ch.direct_source
              : xtream.getLiveStreamUrl(ch.stream_id, 'ts');
          }
        }
        const expanded = dvrScheduler.expandRulesFromEpg(refs.filter(r => r.streamUrl));
        if (expanded.keyword + expanded.series > 0) {
          info(`DVR auto-scheduled ${expanded.keyword + expanded.series} programs (keyword ${expanded.keyword}, series ${expanded.series})`);
        }
      } catch (expandErr) {
        console.warn('[EPG] DVR rule expansion failed:', expandErr);
      }
    } catch (err) {
      console.error('[EPG] Failed to load:', err);
      setEpgRuntime({ channels: {}, programs: {} });
    } finally {
      setIsEpgLoading(false);
    }
  }, [settings, info, isEpgLoading, channels]);

  // ── Auto-refresh EPG + playlists ─────────────────────────────────────
  useAutoRefresh({
    epgRefreshHours: settings.epgRefreshHours,
    playlistRefreshHours: settings.playlistRefreshHours ?? 24,
    onRefreshEpg: useCallback(() => { void loadEpg(); }, [loadEpg]),
    onRefreshPlaylists: useCallback(async () => {
      const { playlistManager } = await import('./services/playlistManager');
      await playlistManager.refreshAll();
    }, []),
  });

  // ── Demand-load data when user navigates ─────────────────────────────
  useEffect(() => {
    if (!isConnected) return;

    if ((currentPage === 'home' || currentPage === 'live' || currentPage === 'guide' || currentPage === 'dvr') && !hasLoadedLive && !isLiveLoading) {
      void loadLiveContent();
    }

    if (currentPage === 'movies' && !hasLoadedVod && !isVodLoading) {
      void loadVodContent();
    }

    if (currentPage === 'series' && !hasLoadedSeries && !isSeriesLoading) {
      void loadSeriesContent();
    }

    if (currentPage === 'guide' && settings.epgEnabled && !epgData && !isEpgLoading) {
      void loadEpg();
    }
  }, [
    isConnected,
    currentPage,
    hasLoadedLive,
    hasLoadedVod,
    hasLoadedSeries,
    isLiveLoading,
    isVodLoading,
    isSeriesLoading,
    settings.epgEnabled,
    epgData,
    isEpgLoading,
    loadLiveContent,
    loadVodContent,
    loadSeriesContent,
    loadEpg,
  ]);

  // ── Profile management ───────────────────────────────────────────────
  const handleSelectProfile = useCallback((profile: XtreamProfile) => {
    activeProfileIdRef.current = profile.id;
    setActiveProfileId(profile.id);
    saveActiveProfileId(profile.id);
    connectToProfile(profile);
    setShowProfileDialog(false);
  }, [connectToProfile]);

  const handleAddProfile = useCallback(async (profileData: Omit<XtreamProfile, 'id'>) => {
    const newProfile: XtreamProfile = {
      ...profileData,
      id: `profile_${Date.now()}_${Math.random().toString(36).slice(2)}`,
    };
    const updated = [...profiles, newProfile];
    setProfiles(updated);
    saveProfiles(updated);
    return newProfile;
  }, [profiles]);

  const handleDeleteProfile = useCallback((id: string) => {
    const updated = profiles.filter(p => p.id !== id);
    setProfiles(updated);
    saveProfiles(updated);
    if (activeProfileId === id) {
      setActiveProfileId(null);
      saveActiveProfileId(null);
      setIsConnected(false);
      resetLoadedContent();
    }
  }, [profiles, activeProfileId, resetLoadedContent]);

  const handleUpdateProfile = useCallback((id: string, partial: Partial<typeof profiles[number]>) => {
    const updated = profiles.map(p => p.id === id ? { ...p, ...partial } : p);
    setProfiles(updated);
    saveProfiles(updated);
  }, [profiles]);

  const buildMovieStreamCandidates = useCallback((movie: XtreamMovie): string[] => {
    const directSource = movie.direct_source?.trim();
    const extensionUrls = getUniqueVodExtensions(movie.container_extension)
      .map(ext => xtream.getVodStreamUrl(movie.stream_id, ext));

    return Array.from(new Set([
      ...(directSource ? [directSource] : []),
      ...extensionUrls,
    ]));
  }, []);

  const buildEpisodeStreamCandidates = useCallback((episode: XtreamEpisode): string[] => {
    const directSource = episode.direct_source?.trim();
    const extensionUrls = getUniqueVodExtensions(episode.container_extension)
      .map(ext => xtream.getSeriesStreamUrl(episode.id, ext));

    return Array.from(new Set([
      ...(directSource ? [directSource] : []),
      ...extensionUrls,
    ]));
  }, []);

  // ── Channel play ─────────────────────────────────────────────────────
  const handlePlayChannel = useCallback((channel: XtreamChannel) => {
    // Live TV must not keep an MPV VOD session alive underneath HLS.js.
    void window.electronAPI?.invoke?.('mpv:stop')?.catch?.(() => {});

    pushLastChannel(String(channel.stream_id), channel.name);

    // Determine final live format. When settings.liveFormat === 'auto' prefer
    // m3u8 only if server advertised support; otherwise fall back to ts.
    let format: 'ts' | 'm3u8' = 'ts';
    if (settings.liveFormat === 'ts' || settings.liveFormat === 'm3u8') {
      format = settings.liveFormat;
    } else {
      const allowed = (activeAuthAllowedFormats || []).map(f => String(f).toLowerCase());
      if (allowed.some(f => f.includes('m3u8') || f.includes('hls'))) format = 'm3u8';
      else format = 'ts';
    }

    const url = channel.direct_source?.trim() ? channel.direct_source : xtream.getLiveStreamUrl(channel.stream_id, format);
    setFallbackStreamUrls([]);
    setStreamUrl(url);
    setStreamTitle(channel.name);
    setStreamType('live');
    setActiveChannel(channel);
    setCurrentPage('live');
    // Prevent system sleep during playback
    window.electronAPI?.invoke?.('power:prevent-sleep');
  }, [settings, activeAuthAllowedFormats]);

  // ── Movie play ───────────────────────────────────────────────────────
  const handlePlayMovie = useCallback((movie: XtreamMovie) => {
    const [url, ...fallbackUrls] = buildMovieStreamCandidates(movie);
    if (!url) {
      toastError('Movie has no playable stream URL');
      return;
    }
    window.electronAPI?.invoke?.('mpv:stop').catch(() => {});
    setFallbackStreamUrls(fallbackUrls);
    setStreamUrl(url);
    setStreamTitle(movie.name);
    setStreamType('vod');
    setActiveChannel(null);
    setCurrentPage('movies');
    window.electronAPI?.invoke?.('power:prevent-sleep');
  }, [buildMovieStreamCandidates, toastError]);

  // ── Series episode play ──────────────────────────────────────────────
  const handlePlayEpisode = useCallback((episode: XtreamEpisode, _seriesInfo: XtreamSeriesInfo) => {
    const [url, ...fallbackUrls] = buildEpisodeStreamCandidates(episode);
    if (!url) {
      toastError('Episode has no playable stream URL');
      return;
    }
    window.electronAPI?.invoke?.('mpv:stop').catch(() => {});
    setFallbackStreamUrls(fallbackUrls);
    setStreamUrl(url);
    setStreamTitle(episode.title || `Episode ${episode.episode_num}`);
    setStreamType('vod');
    setActiveChannel(null);
    setCurrentPage('series');
    window.electronAPI?.invoke?.('power:prevent-sleep');
  }, [buildEpisodeStreamCandidates, toastError]);

  const handlePlayRecording = useCallback((recordingPath: string) => {
    const normalized = recordingPath.replace(/\\/g, '/');
    const fileUrl = /^[a-zA-Z]:\//.test(normalized)
      ? `file:///${normalized}`
      : normalized.startsWith('file://')
        ? normalized
        : `file://${normalized}`;
    const title = recordingPath.split(/[\\/]/).pop() || 'DVR Recording';

    setFallbackStreamUrls([]);
    setStreamUrl(fileUrl);
    setStreamTitle(title);
    setStreamType('vod');
    setActiveChannel(null);
    setCurrentPage('dvr');
    window.electronAPI?.invoke?.('power:prevent-sleep');
  }, []);

  // ── DVR / Catchup play ───────────────────────────────────────────────
  const handlePlayCatchup = useCallback((channel: XtreamChannel, program: EPGProgram) => {
    if (channel.tv_archive !== 1) return;
    const start = new Date(program.startTime);
    const end = new Date(program.stopTime);
    const durationMin = Math.round((end.getTime() - start.getTime()) / 60000);
    const url = xtream.getCatchupStreamUrl(channel.stream_id, start, durationMin);
    setFallbackStreamUrls([]);
    setStreamUrl(url);
    setStreamTitle(`[DVR] ${program.title} — ${channel.name}`);
    setStreamType('vod');
    setActiveChannel(null);
    info(`Playing catchup: ${program.title}`);
  }, [info]);

  // ── Load series info ─────────────────────────────────────────────────
  const handleLoadSeriesInfo = useCallback(async (seriesId: number) => {
    return xtream.getSeriesInfo(seriesId);
  }, []);

  // ── Favorites ────────────────────────────────────────────────────────
  const handleToggleFavorite = useCallback((id: string) => {
    setFavorites(prev => {
      const updated = prev.includes(id)
        ? prev.filter(f => f !== id)
        : [...prev, id];
      saveFavorites(updated);
      if (updated.includes(id)) {
        success('Added to favorites');
      } else {
        info('Removed from favorites');
      }
      return updated;
    });
  }, [success, info]);

  // ── Watch progress ───────────────────────────────────────────────────
  const handlePositionUpdate = useCallback((position: number, duration: number) => {
    if (duration <= 0) return;
    // Update taskbar progress
    window.electronAPI?.invoke?.('taskbar:set-progress', Math.round((position / duration) * 100));
  }, []);

  const handleStreamEnded = useCallback(() => {
    window.electronAPI?.invoke?.('taskbar:set-progress', -1);
    window.electronAPI?.invoke?.('power:allow-sleep');
  }, []);

  const handleClosePlayer = useCallback(() => {
    window.electronAPI?.invoke?.('mpv:stop').catch(() => {});
    window.electronAPI?.invoke?.('taskbar:set-progress', -1);
    window.electronAPI?.invoke?.('power:allow-sleep');
    setStreamUrl(null);
    setStreamTitle('');
    setActiveChannel(null);
    setFallbackStreamUrls([]);
  }, []);

  // ── Content filter + category priority (before channel jump / pages) ──
  const contentFilterSettings = useMemo<ContentFilterSettings>(() => ({
    enabled: settings.contentFilterEnabled ?? false,
    mode: settings.contentFilterMode ?? 'whitelist',
    allowedPrefixes: settings.contentFilterAllowed ?? [],
    blockedPrefixes: settings.contentFilterBlocked ?? [],
    showUntagged: settings.contentFilterShowUntagged ?? true,
    categoryPriorityPrefixes: settings.categoryPriorityPrefixes ?? [],
  }), [settings]);

  const displayChannels = useMemo(
    () => filterChannels(channels, contentFilterSettings),
    [channels, contentFilterSettings]
  );
  const displayLiveCategories = useMemo(
    () => sortCategoriesByPriority(
      filterCategories(liveCategories, contentFilterSettings),
      contentFilterSettings.categoryPriorityPrefixes
    ),
    [liveCategories, contentFilterSettings]
  );
  const displayMovies = useMemo(
    () => filterMovies(movies, contentFilterSettings),
    [movies, contentFilterSettings]
  );
  const displayVodCategories = useMemo(
    () => sortCategoriesByPriority(
      filterCategories(vodCategories, contentFilterSettings),
      contentFilterSettings.categoryPriorityPrefixes
    ),
    [vodCategories, contentFilterSettings]
  );
  const displaySeries = useMemo(
    () => filterSeries(series, contentFilterSettings),
    [series, contentFilterSettings]
  );
  const displaySeriesCategories = useMemo(
    () => sortCategoriesByPriority(
      filterCategories(seriesCategories, contentFilterSettings),
      contentFilterSettings.categoryPriorityPrefixes
    ),
    [seriesCategories, contentFilterSettings]
  );

  // ── Channel navigation ──────────────────────────────────────────────
  const navigateChannel = useCallback((direction: number) => {
    if (!activeChannel || displayChannels.length === 0) return;
    const currentIdx = displayChannels.findIndex(c => c.stream_id === activeChannel.stream_id);
    if (currentIdx < 0) return;
    const nextIdx = (currentIdx + direction + displayChannels.length) % displayChannels.length;
    handlePlayChannel(displayChannels[nextIdx]);
  }, [activeChannel, displayChannels, handlePlayChannel]);

  const handleLastChannel = useCallback(() => {
    const last = loadLastChannel();
    const prevId = last?.previousStreamId;
    if (!prevId) {
      info('No previous channel');
      return;
    }
    const ch = displayChannels.find(c => String(c.stream_id) === prevId)
      || channels.find(c => String(c.stream_id) === prevId);
    if (ch) handlePlayChannel(ch);
    else info('Previous channel not in current catalog window');
  }, [displayChannels, channels, handlePlayChannel, info]);

  // ── Channel number jump ──────────────────────────────────────────────
  const channelJump = useChannelNumberJump(displayChannels, handlePlayChannel);

  const requestCategoryChange = useCallback((
    kind: 'live' | 'vod' | 'series',
    categoryId: string,
    categories: XtreamCategory[]
  ) => {
    const cat = categories.find(c => String(c.category_id) === String(categoryId));
    const name = cat?.category_name || categoryId;
    if (settings.parentalControlsEnabled && parentalControls.requiresPin(name)) {
      setPinDialog({ categoryName: name, categoryId, kind });
      return;
    }
    switch (kind) {
      case 'live':
        void loadLiveCategory(categoryId);
        break;
      case 'vod':
        void loadVodCategory(categoryId);
        break;
      case 'series':
        void loadSeriesCategory(categoryId);
        break;
      default: {
        const _exhaustive: never = kind;
        void _exhaustive;
      }
    }
  }, [
    settings.parentalControlsEnabled,
    loadLiveCategory,
    loadVodCategory,
    loadSeriesCategory,
  ]);

  const handlePinEntered = useCallback(async (pin: string) => {
    if (!pinDialog) return;
    const ok = await parentalControls.verifyPin(pin);
    if (!ok) {
      setPinDialog(prev => prev ? { ...prev, error: 'Incorrect PIN' } : null);
      return;
    }
    parentalControls.unlockForSession(pinDialog.categoryName);
    const { kind, categoryId } = pinDialog;
    setPinDialog(null);
    switch (kind) {
      case 'live':
        void loadLiveCategory(categoryId);
        break;
      case 'vod':
        void loadVodCategory(categoryId);
        break;
      case 'series':
        void loadSeriesCategory(categoryId);
        break;
      default: {
        const _exhaustive: never = kind;
        void _exhaustive;
      }
    }
  }, [pinDialog, loadLiveCategory, loadVodCategory, loadSeriesCategory]);

  // ── Keyboard shortcuts ───────────────────────────────────────────────
  const shortcuts = useKeyboardShortcuts(
    {
      playPause: () => {
        // Route through MPV IPC when no <video> element (MPV engine active)
        const video = document.querySelector('video');
        if (video && video.src) {
          if (video.paused) video.play().catch(() => {});
          else video.pause();
        } else {
          window.electronAPI?.invoke?.('mpv:toggle-pause').catch(() => {});
        }
      },
      stop: () => {
        handleClosePlayer();
      },
      seekBack10: () => seekVideo(-10),
      seekForward10: () => seekVideo(10),
      seekBack30: () => seekVideo(-30),
      seekForward30: () => seekVideo(30),
      seekBack60: () => seekVideo(-60),
      seekForward60: () => seekVideo(60),
      volumeUp: () => updateSettings({ volume: Math.min(1, (settings.volume ?? 1) + 0.05) }),
      volumeDown: () => updateSettings({ volume: Math.max(0, (settings.volume ?? 1) - 0.05) }),
      mute: () => updateSettings({ muted: !settings.muted }),
      volumeMax: () => updateSettings({ volume: 1, muted: false }),
      nextChannel: () => navigateChannel(1),
      prevChannel: () => navigateChannel(-1),
      lastChannel: () => handleLastChannel(),
      onDigit: (d: string) => channelJump.handleDigit(d.slice(-1)),
      fullscreen: () => {
        if (document.fullscreenElement) document.exitFullscreen();
        else document.documentElement.requestFullscreen();
      },
      pip: () => {
        if (isPipOpen) {
          closePip();
          return;
        }
        if (streamUrl) {
          void openPip({
            streamUrl,
            title: streamTitle || 'PiP',
            channelLogo: activeChannel?.stream_icon,
          });
          return;
        }
        const video = document.querySelector('video');
        if (video) {
          if (document.pictureInPictureElement) document.exitPictureInPicture();
          else video.requestPictureInPicture().catch(() => {});
        }
      },
      toggleMultiview: () => {
        setCurrentPage(prev => (prev === 'multiview' ? 'live' : 'multiview'));
      },
      toggleEpg: () => {
        if (currentPage === 'live') {
          // Toggle is handled inside LiveTVPage
        }
      },
      toggleSidebar: () => setSidebarCollapsed(v => !v),
      openSearch: () => setCurrentPage('search'),
      goHome: () => setCurrentPage('home'),
      goLiveTV: () => setCurrentPage('live'),
      goMovies: () => setCurrentPage('movies'),
      escape: () => {
        if (document.fullscreenElement) {
          document.exitFullscreen();
        } else if (isPipOpen) {
          closePip();
        } else if (streamUrl) {
          handleClosePlayer();
        } else if (showProfileDialog) {
          // Don't close profile dialog if no profiles
          if (profiles.length > 0) setShowProfileDialog(false);
        }
      },
    },
    isConnected
  );

  // ── Deep link handler ────────────────────────────────────────────────
  useEffect(() => {
    const handleDeepLink = (_event: unknown, page: string) => {
      const validPages: AppPage[] = ['home', 'live', 'guide', 'movies', 'series', 'search', 'favorites', 'dvr'];
      if (validPages.includes(page as AppPage)) {
        setCurrentPage(page as AppPage);
      }
    };
    window.electronAPI?.on?.('app:deep-link', handleDeepLink);
    return () => window.electronAPI?.off?.('app:deep-link', handleDeepLink);
  }, []);

  // ── OS media key events (from globalShortcut in main process) ───────
  useEffect(() => {
    const onPlayPause = () => {
      // Route through MPV IPC first; fall back to <video> element for HLS engine
      const video = document.querySelector('video');
      if (video && video.src) {
        video.paused ? video.play().catch(() => {}) : video.pause();
      } else {
        window.electronAPI?.invoke?.('mpv:toggle-pause').catch(() => {});
      }
    };
    const onStop = () => {
      const video = document.querySelector('video');
      if (video && video.src) { video.pause(); video.currentTime = 0; }
      else { window.electronAPI?.invoke?.('mpv:stop').catch(() => {}); }
    };
    const onNext = () => navigateChannel(1);
    const onPrev = () => navigateChannel(-1);

    window.electronAPI?.on?.('media-key:play-pause', onPlayPause);
    window.electronAPI?.on?.('media-key:stop', onStop);
    window.electronAPI?.on?.('media-key:next-channel', onNext);
    window.electronAPI?.on?.('media-key:prev-channel', onPrev);
    return () => {
      window.electronAPI?.off?.('media-key:play-pause', onPlayPause);
      window.electronAPI?.off?.('media-key:stop', onStop);
      window.electronAPI?.off?.('media-key:next-channel', onNext);
      window.electronAPI?.off?.('media-key:prev-channel', onPrev);
    };
  }, []);

  // ── Helper: seek video ───────────────────────────────────────────────
  const seekVideo = (delta: number) => {
    const video = document.querySelector('video');
    if (video && isFinite(video.duration)) {
      video.currentTime = Math.max(0, Math.min(video.duration, video.currentTime + delta));
    }
  };

  // ── Get active profile ───────────────────────────────────────────────
  const activeProfile = useMemo(
    () => profiles.find(p => p.id === activeProfileId) ?? null,
    [profiles, activeProfileId]
  );

  const epgProgramIndex = useMemo(() => buildEpgProgramIndex(epgData), [epgData, epgRevision]);

  const [shortEpgPrograms, setShortEpgPrograms] = useState<EPGProgram[]>([]);

  // Xtream short EPG fallback when XMLTV matching misses the active live channel.
  useEffect(() => {
    if (streamType !== 'live' || !activeChannel || !settings.epgEnabled) {
      setShortEpgPrograms([]);
      return;
    }

    const matched = resolveProgramsForChannel(activeChannel, epgProgramIndex);
    if (matched.length > 0) {
      setShortEpgPrograms([]);
      return;
    }

    let cancelled = false;
    void (async () => {
      try {
        const raw = await xtream.getShortEpg(activeChannel.stream_id, 12);
        const listings = Array.isArray(raw)
          ? raw
          : Array.isArray(raw?.epg_listings)
            ? raw.epg_listings
            : [];
        if (cancelled || !listings.length) return;

        const programs: EPGProgram[] = listings
          .map((item: Record<string, unknown>) => {
            const startTs = Number(item.start_timestamp ?? item.start ?? 0);
            const endTs = Number(item.stop_timestamp ?? item.end ?? item.stop ?? 0);
            const startMs = startTs > 1e12 ? startTs : startTs * 1000;
            const endMs = endTs > 1e12 ? endTs : endTs * 1000;
            if (!Number.isFinite(startMs) || !Number.isFinite(endMs) || endMs <= startMs) {
              return null;
            }
            const titleRaw = String(item.title ?? item.name ?? 'Program');
            let title = titleRaw;
            try {
              // Some providers base64-encode short-EPG titles.
              if (/^[A-Za-z0-9+/=]+$/.test(titleRaw) && titleRaw.length % 4 === 0) {
                const decoded = atob(titleRaw);
                if (decoded.trim()) title = decoded;
              }
            } catch { /* keep raw */ }

            return {
              channelId: String(activeChannel.epg_channel_id || activeChannel.stream_id),
              title,
              description: String(item.description ?? item.desc ?? ''),
              startTime: new Date(startMs).toISOString(),
              stopTime: new Date(endMs).toISOString(),
            } satisfies EPGProgram;
          })
          .filter((p: EPGProgram | null): p is EPGProgram => p !== null);

        if (!cancelled) setShortEpgPrograms(programs);
      } catch (err) {
        console.warn('[EPG] Short EPG fallback failed:', err);
        if (!cancelled) setShortEpgPrograms([]);
      }
    })();

    return () => { cancelled = true; };
  }, [activeChannel, epgProgramIndex, settings.epgEnabled, streamType]);

  const activeChannelPrograms = useMemo(() => {
    if (!activeChannel || streamType !== 'live') {
      return [];
    }

    const matched = resolveProgramsForChannel(activeChannel, epgProgramIndex);
    return matched.length > 0 ? matched : shortEpgPrograms;
  }, [activeChannel, epgProgramIndex, shortEpgPrograms, streamType]);

  const currentLiveProgram = useMemo(() => {
    if (streamType !== 'live') {
      return null;
    }

    return getNowPlaying(activeChannelPrograms, new Date(epgNowTick));
  }, [activeChannelPrograms, streamType, epgNowTick]);

  const nextLiveProgram = useMemo(() => {
    if (streamType !== 'live' || activeChannelPrograms.length === 0) {
      return null;
    }

    const nowMs = epgNowTick;
    for (const program of activeChannelPrograms) {
      const startMs = new Date(program.startTime).getTime();
      if (startMs > nowMs) {
        return program;
      }
    }

    return null;
  }, [activeChannelPrograms, streamType, epgNowTick]);

  const upcomingLivePrograms = useMemo(() => {
    if (streamType !== 'live' || activeChannelPrograms.length === 0) {
      return [];
    }

    const nowMs = epgNowTick;
    return activeChannelPrograms
      .filter(program => new Date(program.startTime).getTime() > nowMs)
      .slice(0, 3)
      .map(program => ({
        title: program.title,
        startTime: new Date(program.startTime).getTime(),
      }))
      .filter(program => Number.isFinite(program.startTime));
  }, [activeChannelPrograms, streamType, epgNowTick]);

  const mpvCurrentProgram = useMemo(() => {
    if (!currentLiveProgram) {
      return null;
    }

    const startTime = new Date(currentLiveProgram.startTime).getTime();
    const endTime = new Date(currentLiveProgram.stopTime).getTime();

    if (!Number.isFinite(startTime) || !Number.isFinite(endTime)) {
      return null;
    }

    return {
      title: currentLiveProgram.title,
      startTime,
      endTime,
    };
  }, [currentLiveProgram]);

  const mpvNextProgram = useMemo(() => {
    if (!nextLiveProgram) {
      return null;
    }

    const startTime = new Date(nextLiveProgram.startTime).getTime();
    if (!Number.isFinite(startTime)) {
      return null;
    }

    return {
      title: nextLiveProgram.title,
      startTime,
    };
  }, [nextLiveProgram]);

  const renderPageLoader = (title: string, subtitle: string) => (
    <div className="flex-1 flex items-center justify-center">
      <div className="text-center">
        <div className="w-8 h-8 mx-auto mb-3 border-2 border-white/10 rounded-full animate-spin"
          style={{ borderTopColor: 'var(--accent)' }} />
        <p className="text-white/80 text-sm font-medium">{title}</p>
        <p className="text-white/30 text-xs mt-1">{subtitle}</p>
      </div>
    </div>
  );

  // ── Render page content ──────────────────────────────────────────────
  const renderPage = () => {
    if (currentPage === 'settings') {
      return (
        <SettingsPage
          settings={settings}
          onUpdateSettings={updateSettings}
          shortcuts={shortcuts.shortcuts}
          appVersion="4.8.0"
        />
      );
    }

    if (!isConnected) {
      return (
        <div className="flex-1 flex items-center justify-center">
          <div className="text-center">
            <div className="w-20 h-20 mx-auto mb-4 rounded-2xl bg-white/5 flex items-center justify-center">
              <span className="text-3xl font-bold" style={{ color: 'var(--accent)' }}>D</span>
            </div>
            <h2 className="text-white text-lg font-semibold mb-2">DYLANDOS IPTV ULTIMATE</h2>
            {isLoading ? (
              <>
                <div className="w-8 h-8 mx-auto mb-3 border-2 border-white/10 rounded-full animate-spin"
                  style={{ borderTopColor: 'var(--accent)' }} />
                <p className="text-white/30 text-sm">Connecting and loading content...</p>
              </>
            ) : (
              <>
                <p className="text-white/30 text-sm mb-4">Connect a profile to get started</p>
                <button
                  onClick={() => setShowProfileDialog(true)}
                  className="neon-btn px-6 py-2.5 text-sm rounded-xl"
                >
                  Add Profile
                </button>
              </>
            )}
          </div>
        </div>
      );
    }

    switch (currentPage) {
      case 'home':
        return (
          <HomePage
            recentlyWatched={watchProgressList}
            channels={displayChannels}
            movies={displayMovies}
            series={displaySeries}
            vodCategoriesCount={displayVodCategories.length}
            seriesCategoriesCount={displaySeriesCategories.length}
            totalChannelsCount={displayChannels.length}
            favorites={favorites}
            epgData={epgData}
            profileId={activeProfileId}
            onPlayChannel={handlePlayChannel}
            onPlayMovie={handlePlayMovie}
            onNavigate={setCurrentPage}
          />
        );
      case 'live':
        if (isLiveLoading && channels.length === 0) {
          return renderPageLoader('Loading Live TV', 'Fetching channels and categories...');
        }
        return (
          <div className="flex-1 flex flex-col overflow-hidden">
            <LiveTVPage
              channels={displayChannels}
              categories={displayLiveCategories}
              epgData={epgData}
              activeChannel={activeChannel}
              favorites={favorites}
              settings={settings}
              activeCategoryId={activeLiveCategoryId}
              isCategoryLoading={isLiveLoading}
              catalogStale={liveCatalogMeta?.stale ?? false}
              onCategoryChange={(categoryId) => {
                requestCategoryChange('live', categoryId, displayLiveCategories);
              }}
              onRefreshCategory={(categoryId) => { void loadLiveCategory(categoryId, undefined, undefined, true); }}
              onChannelSelect={handlePlayChannel}
              onToggleFavorite={handleToggleFavorite}
              channelJump={channelJump}
            />
          </div>
        );
      case 'guide':
        if (isLiveLoading && channels.length === 0) {
          return renderPageLoader('Loading Guide', 'Fetching channels before EPG view...');
        }
        return (
          <GuidePage
            channels={displayChannels}
            categories={displayLiveCategories}
            epgData={epgData}
            favorites={favorites}
            settings={settings}
            isEpgLoading={isEpgLoading}
            activeCategoryId={activeLiveCategoryId}
            onCategoryChange={(categoryId) => {
              requestCategoryChange('live', categoryId, displayLiveCategories);
            }}
            onChannelSelect={handlePlayChannel}
            onToggleFavorite={handleToggleFavorite}
            onPlayCatchup={handlePlayCatchup}
            onLoadEpg={() => { void loadEpg(); }}
          />
        );
      case 'movies':
        if (isVodLoading && movies.length === 0) {
          return renderPageLoader('Loading Movies', 'Building your VOD library...');
        }
        return (
          <div className="flex-1 flex flex-col overflow-hidden">
            <MoviesPage
              movies={displayMovies}
              categories={displayVodCategories}
              favorites={favorites}
              settings={settings}
              activeCategoryId={activeVodCategoryId}
              isCategoryLoading={isVodLoading}
              catalogStale={vodCatalogMeta?.stale ?? false}
              onCategoryChange={(categoryId) => {
                requestCategoryChange('vod', categoryId, displayVodCategories);
              }}
              onRefreshCategory={(categoryId) => { void loadVodCategory(categoryId, undefined, undefined, true); }}
              onPlayMovie={handlePlayMovie}
              onToggleFavorite={handleToggleFavorite}
            />
          </div>
        );
      case 'series':
        if (isSeriesLoading && series.length === 0) {
          return renderPageLoader('Loading Series', 'Building your series library...');
        }
        return (
          <div className="flex-1 flex flex-col overflow-hidden">
            <SeriesPage
              series={displaySeries}
              categories={displaySeriesCategories}
              favorites={favorites}
              settings={settings}
              activeCategoryId={activeSeriesCategoryId}
              isCategoryLoading={isSeriesLoading}
              catalogStale={seriesCatalogMeta?.stale ?? false}
              onCategoryChange={(categoryId) => {
                requestCategoryChange('series', categoryId, displaySeriesCategories);
              }}
              onRefreshCategory={(categoryId) => { void loadSeriesCategory(categoryId, undefined, undefined, true); }}
              onPlayEpisode={handlePlayEpisode}
              onToggleFavorite={handleToggleFavorite}
              onLoadSeriesInfo={handleLoadSeriesInfo}
            />
          </div>
        );
      case 'search':
        return (
          <SearchPage
            channels={displayChannels}
            movies={displayMovies}
            series={displaySeries}
            epgData={epgData}
            profileId={activeProfileId}
            onPlayChannel={handlePlayChannel}
            onPlayMovie={handlePlayMovie}
            onSelectSeries={() => setCurrentPage('series')}
          />
        );
      case 'favorites':
        return (
          <FavoritesPage
            favorites={favorites}
            channels={channels}
            movies={movies}
            series={series}
            profileId={activeProfileId}
            onPlayChannel={handlePlayChannel}
            onPlayMovie={handlePlayMovie}
            onSelectSeries={() => setCurrentPage('series')}
            onRemoveFavorite={handleToggleFavorite}
          />
        );
      case 'dvr':
        return (
          <div className="flex-1 flex flex-col overflow-hidden">
            <DVRPage
              channels={channels}
              onPlayRecording={handlePlayRecording}
              onPlayCatchup={handlePlayCatchup}
            />
          </div>
        );
      case 'downloads':
        return (
          <div className="flex-1 flex flex-col overflow-hidden">
            <DownloadsPage />
          </div>
        );
      case 'lists':
        return (
          <CustomListsPage
            channels={displayChannels}
            movies={displayMovies}
            series={displaySeries}
            onPlayChannel={handlePlayChannel}
            onPlayMovie={handlePlayMovie}
            onSelectSeries={() => setCurrentPage('series')}
          />
        );
      case 'multiview':
        return (
          <MultiViewPage
            channels={displayChannels}
            liveFormat={settings.liveFormat}
            onExit={() => setCurrentPage('live')}
          />
        );
      default: {
        // 'settings' handled above
        return null;
      }
    }
  };

  // ── Apply theme to document ───────────────────────────────────────
  useEffect(() => {
    document.documentElement.setAttribute('data-theme', settings.theme);
  }, [settings.theme]);

  // Keep the renderer transparent while playback overlay is active so
  // embedded MPV video (rendered in the backing window) is visible.
  useEffect(() => {
    const enabled = Boolean(streamUrl);
    const html = document.documentElement;
    const body = document.body;
    const root = document.getElementById('root');

    const prevHtmlBg = html.style.backgroundColor;
    const prevBodyBg = body.style.backgroundColor;
    const prevRootBg = root?.style.backgroundColor ?? '';

    let repaintTimer: number | null = null;

    html.classList.toggle('mpv-transparent-backdrop', enabled);
    body.classList.toggle('mpv-transparent-backdrop', enabled);
    root?.classList.toggle('mpv-transparent-backdrop', enabled);

    if (enabled) {
      // Inline fallback in case theme/base CSS is cached with higher priority.
      html.style.backgroundColor = 'transparent';
      body.style.backgroundColor = 'transparent';
      if (root) root.style.backgroundColor = 'transparent';

      // Repaint after class/style toggle so DWM applies the alpha hole.
      repaintTimer = window.setTimeout(() => {
        window.electronAPI?.invoke?.('window:force-repaint')?.catch?.(() => {});
      }, 80);
    }

    return () => {
      if (repaintTimer) {
        clearTimeout(repaintTimer);
      }
      html.classList.remove('mpv-transparent-backdrop');
      body.classList.remove('mpv-transparent-backdrop');
      root?.classList.remove('mpv-transparent-backdrop');

      html.style.backgroundColor = prevHtmlBg;
      body.style.backgroundColor = prevBodyBg;
      if (root) root.style.backgroundColor = prevRootBg;
    };
  }, [streamUrl]);

  return (
    <div className={`h-screen flex flex-col overflow-hidden theme-text${streamUrl ? '' : ' theme-bg-base'}`}>
      {/* Custom title bar */}
      {!streamUrl && <TitleBar />}

      {/* Update banner */}
      {!streamUrl && <UpdateBanner />}

      {/* ── Fullscreen player overlay — covers everything when a stream is active ── */}
      {streamUrl && (
        <div className="fixed inset-0 z-50">
          <VideoPlayerEngine
            streamUrl={streamUrl}
            streamTitle={streamTitle}
            streamType={streamType}
            settings={settings}
            activeChannel={activeChannel}
            currentProgram={currentLiveProgram}
            epgCurrentProgram={mpvCurrentProgram}
            epgNextProgram={mpvNextProgram}
            epgUpcomingPrograms={upcomingLivePrograms}
            fallbackStreamUrls={fallbackStreamUrls}
            onPositionUpdate={handlePositionUpdate}
            onEnded={handleStreamEnded}
            onBack={handleClosePlayer}
            onChannelUp={streamType === 'live' ? () => navigateChannel(1) : undefined}
            onChannelDown={streamType === 'live' ? () => navigateChannel(-1) : undefined}
          />
        </div>
      )}

      {/* Main layout */}
      {!streamUrl && (
        <>
          <div className="flex-1 flex overflow-hidden">
            {/* Sidebar */}
            <Sidebar
              currentPage={currentPage}
              onNavigate={setCurrentPage}
              isCollapsed={sidebarCollapsed}
              onToggleCollapse={() => setSidebarCollapsed(v => !v)}
              activeProfile={activeProfile}
              onSwitchProfile={() => setShowProfileDialog(true)}
            />

            {/* Page content */}
            {renderPage()}
          </div>

          {/* Profile selection dialog */}
          {showProfileDialog && (
            <ProfileSelectionDialog
              profiles={profiles}
              activeProfileId={activeProfileId}
              onSelectProfile={handleSelectProfile}
              onAddProfile={handleAddProfile}
              onDeleteProfile={handleDeleteProfile}
              onUpdateProfile={handleUpdateProfile}
              isOpen={showProfileDialog}
              onClose={profiles.length > 0 ? () => setShowProfileDialog(false) : () => {}}
            />
          )}
        </>
      )}

      {pinDialog && (
        <PinEntryDialog
          title={`Unlock: ${pinDialog.categoryName}`}
          error={pinDialog.error}
          onPinEntered={(pin) => { void handlePinEntered(pin); }}
          onDismiss={() => setPinDialog(null)}
        />
      )}
    </div>
  );
};
