import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Clock, Tv, RotateCcw } from 'lucide-react';
import { FixedSizeList, ListChildComponentProps } from 'react-window';
import { useLogoUrl } from '../services/logoCache';
import { getNowPlaying } from '../services/xmltvEpg';
import type { EPGProgram } from '../types/epg';
import type { XtreamChannel } from '../types/xtream';

// ── Genre Color Map ───────────────────────────────────────────────────────────
// Maps category keywords → Tailwind-compatible inline border-color values
const GENRE_COLORS: Array<{ keywords: string[]; border: string; bg: string }> = [
  { keywords: ['news', 'current affairs', 'weather'], border: 'rgba(21,101,192,0.6)', bg: 'rgba(21,101,192,0.12)' },
  { keywords: ['sport', 'sports', 'football', 'soccer', 'basketball', 'cricket', 'tennis'], border: 'rgba(46,125,50,0.6)', bg: 'rgba(46,125,50,0.12)' },
  { keywords: ['movie', 'movies', 'film', 'cinema'], border: 'rgba(106,27,154,0.6)', bg: 'rgba(106,27,154,0.12)' },
  { keywords: ['kids', 'children', 'cartoon', 'animation', 'family'], border: 'rgba(245,127,23,0.6)', bg: 'rgba(245,127,23,0.12)' },
  { keywords: ['documentary', 'nature', 'science', 'history'], border: 'rgba(0,105,92,0.6)', bg: 'rgba(0,105,92,0.12)' },
  { keywords: ['entertainment', 'comedy', 'drama', 'reality'], border: 'rgba(183,28,28,0.6)', bg: 'rgba(183,28,28,0.12)' },
  { keywords: ['music', 'concert', 'live music'], border: 'rgba(74,20,140,0.6)', bg: 'rgba(74,20,140,0.12)' },
  { keywords: ['cooking', 'food', 'travel', 'lifestyle'], border: 'rgba(230,81,0,0.6)', bg: 'rgba(230,81,0,0.12)' },
];

function getGenreStyle(category?: string): { border: string; bg: string } | null {
  if (!category) return null;
  const lower = category.toLowerCase();
  for (const entry of GENRE_COLORS) {
    if (entry.keywords.some(k => lower.includes(k))) return entry;
  }
  return null;
}

interface EpgGridProps {
  channels: XtreamChannel[];
  getProgramsForChannel: (channel: XtreamChannel) => EPGProgram[];
  onChannelClick: (channel: XtreamChannel) => void;
  onProgramClick?: (program: EPGProgram, channel: XtreamChannel) => void;
  onPlayCatchup?: (channel: XtreamChannel, program: EPGProgram) => void;
  currentTime?: Date;
  timeFormat?: '12h' | '24h';
}

interface RowData {
  channels: XtreamChannel[];
  getProgramsForChannel: (channel: XtreamChannel) => EPGProgram[];
  onChannelClick: (channel: XtreamChannel) => void;
  onProgramClick?: (program: EPGProgram, channel: XtreamChannel) => void;
  onPlayCatchup?: (channel: XtreamChannel, program: EPGProgram) => void;
  currentTime: Date;
  windowStart: Date;
  timeFormat: '12h' | '24h';
}

const CHANNEL_COL_W = 220;
const HOUR_W = 260;
const ROW_H = 72;
const HEADER_H = 48;
const MIN_PROGRAM_W = 48;
const TOTAL_HOURS = 24;
const GRID_W = HOUR_W * TOTAL_HOURS;
const NOW_SCROLL_OFFSET = HOUR_W;

function getTimelineX(value: Date, windowStart: Date): number {
  return ((value.getTime() - windowStart.getTime()) / 3600000) * HOUR_W;
}

function formatTimelineLabel(value: Date, timeFormat: '12h' | '24h'): string {
  return value.toLocaleTimeString([], {
    hour: '2-digit',
    minute: '2-digit',
    hour12: timeFormat === '12h',
  });
}

function getProgramKey(program: EPGProgram, index: number): string {
  return `${program.channelId}-${program.startTime}-${program.stopTime}-${index}`;
}

const ChannelCell: React.FC<{
  channel: XtreamChannel;
  nowPlaying: EPGProgram | null;
  onClick: () => void;
}> = ({ channel, nowPlaying, onClick }) => {
  const logoUrl = useLogoUrl(channel.stream_icon || '');
  const [logoFailed, setLogoFailed] = useState(false);

  return (
    <button
      onClick={onClick}
      className="group flex h-full w-full items-center gap-3 px-4 text-left transition-colors hover:bg-white/[0.04]"
    >
      <div className="flex h-10 w-10 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white/[0.05] ring-1 ring-white/[0.06]">
        {logoUrl && !logoFailed ? (
          <img
            src={logoUrl}
            alt=""
            className="h-full w-full object-contain"
            loading="lazy"
            onError={() => setLogoFailed(true)}
          />
        ) : (
          <Tv size={16} className="text-white/25" />
        )}
      </div>

      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-semibold text-white transition-colors group-hover:text-cyan-300">
          {channel.name}
        </p>
        <div className="mt-1 flex items-center gap-2 text-[11px] text-white/30">
          <span className="font-mono">CH {channel.num || '—'}</span>
          {nowPlaying ? (
            <span className="truncate">{nowPlaying.title}</span>
          ) : (
            <span className="truncate italic text-white/20">No guide data</span>
          )}
        </div>
      </div>
    </button>
  );
};

const EpgRow = React.memo(({ index, style, data }: ListChildComponentProps<RowData>) => {
  const channel = data.channels[index];
  const programs = data.getProgramsForChannel(channel);
  const nowPlaying = getNowPlaying(programs, data.currentTime);

  return (
    <div style={style} className="flex border-b border-white/[0.05] bg-black/10">
      <div
        className="sticky left-0 z-20 shrink-0 border-r border-white/[0.08] bg-[#05070d]/95 backdrop-blur-md"
        style={{ width: CHANNEL_COL_W, height: ROW_H }}
      >
        <ChannelCell
          channel={channel}
          nowPlaying={nowPlaying}
          onClick={() => data.onChannelClick(channel)}
        />
      </div>

      <div className="relative shrink-0" style={{ width: GRID_W, height: ROW_H }}>
        {Array.from({ length: TOTAL_HOURS + 1 }).map((_, hourIndex) => (
          <div
            key={hourIndex}
            className="pointer-events-none absolute inset-y-0 w-px bg-white/[0.04]"
            style={{ left: hourIndex * HOUR_W }}
          />
        ))}

        {programs.length === 0 && (
          <div className="flex h-full items-center px-4 text-xs italic text-white/20">
            No guide data for this channel yet.
          </div>
        )}

        {programs.map((program, programIndex) => {
          const start = new Date(program.startTime);
          const end = new Date(program.stopTime);

          if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime())) {
            return null;
          }

          const rawLeft = getTimelineX(start, data.windowStart);
          const rawRight = getTimelineX(end, data.windowStart);
          const clippedLeft = Math.max(0, rawLeft);
          const clippedRight = Math.min(GRID_W, rawRight);
          const visibleWidth = clippedRight - clippedLeft;

          if (visibleWidth <= 1) {
            return null;
          }

          const isLive = data.currentTime >= start && data.currentTime < end;
          const isPast = data.currentTime >= end;
          const hasCatchup = isPast && (channel as any).tv_archive === 1;
          const progress = isLive
            ? Math.min(
                1,
                Math.max(
                  0,
                  (data.currentTime.getTime() - start.getTime()) /
                    Math.max(1, end.getTime() - start.getTime())
                )
              )
            : 0;

          const genreStyle = getGenreStyle(program.category);

          // Dynamic inline styles for genre coloring
          const blockBorder = isLive
            ? 'rgba(34,211,238,0.4)'
            : isPast
            ? 'rgba(255,255,255,0.05)'
            : (genreStyle?.border ?? 'rgba(255,255,255,0.08)');
          const blockBg = isLive
            ? 'rgba(6,182,212,0.12)'
            : isPast
            ? 'rgba(255,255,255,0.02)'
            : (genreStyle?.bg ?? 'rgba(255,255,255,0.05)');

          return (
            <button
              key={getProgramKey(program, programIndex)}
              onClick={() => {
                if (hasCatchup) {
                  data.onPlayCatchup?.(channel, program);
                } else {
                  data.onProgramClick?.(program, channel);
                }
              }}
              className={`absolute inset-y-2 overflow-hidden rounded-2xl px-3 text-left transition-all border ${
                isLive
                  ? 'shadow-[0_0_20px_rgba(34,211,238,0.12)] hover:border-cyan-300/70'
                  : hasCatchup
                  ? 'hover:border-white/30 hover:bg-white/10'
                  : 'hover:border-white/16 hover:bg-white/[0.08]'
              } ${isPast && !hasCatchup ? 'opacity-40' : ''}`}
              style={{
                left: clippedLeft + 1,
                width: Math.max(MIN_PROGRAM_W, visibleWidth - 2),
                borderColor: blockBorder,
                backgroundColor: blockBg,
              }}
            >
              {isLive && (
                <div
                  className="absolute inset-y-0 left-0 rounded-2xl bg-cyan-400/18"
                  style={{ width: `${progress * 100}%` }}
                />
              )}

              <div className="relative z-10 flex h-full flex-col justify-center">
                <div className="flex items-center gap-1.5">
                  {hasCatchup && (
                    <RotateCcw size={10} className="shrink-0 text-white/50" />
                  )}
                  {isLive && (
                    <span className="shrink-0 inline-block w-1.5 h-1.5 rounded-full bg-cyan-400 animate-pulse" />
                  )}
                  <p className={`truncate text-xs font-semibold ${
                    isLive ? 'text-cyan-200' : isPast ? 'text-white/50' : 'text-white/85'
                  }`}>
                    {program.title}
                  </p>
                </div>
                <p className="mt-1 truncate text-[10px] text-white/35">
                  {formatTimelineLabel(start, data.timeFormat)} – {formatTimelineLabel(end, data.timeFormat)}
                </p>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
});

export const EpgGrid: React.FC<EpgGridProps> = ({
  channels,
  getProgramsForChannel,
  onChannelClick,
  onProgramClick,
  onPlayCatchup,
  currentTime,
  timeFormat = '24h',
}) => {
  const [internalTime, setInternalTime] = useState<Date>(() => currentTime ?? new Date());
  const [viewportHeight, setViewportHeight] = useState(600);
  const containerRef = useRef<HTMLDivElement>(null);
  const headerScrollRef = useRef<HTMLDivElement>(null);
  const bodyScrollRef = useRef<HTMLDivElement>(null);
  const hasAutoScrolled = useRef(false);

  useEffect(() => {
    if (currentTime) {
      setInternalTime(currentTime);
      return;
    }

    const timer = window.setInterval(() => {
      setInternalTime(new Date());
    }, 30000);

    return () => {
      window.clearInterval(timer);
    };
  }, [currentTime]);

  useEffect(() => {
    if (!currentTime) {
      return;
    }
    setInternalTime(currentTime);
  }, [currentTime]);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) {
      return;
    }

    const updateHeight = () => {
      setViewportHeight(Math.max(240, element.clientHeight - HEADER_H));
    };

    const observer = new ResizeObserver(updateHeight);
    observer.observe(element);
    updateHeight();

    return () => {
      observer.disconnect();
    };
  }, []);

  const activeTime = currentTime ?? internalTime;

  const windowStart = useMemo(() => {
    const nextStart = new Date(activeTime);
    nextStart.setMinutes(0, 0, 0);
    nextStart.setHours(nextStart.getHours() - 2);
    return nextStart;
  }, [activeTime]);

  const nowX = useMemo(() => getTimelineX(activeTime, windowStart), [activeTime, windowStart]);

  const timeSlots = useMemo(() => {
    return Array.from({ length: TOTAL_HOURS + 1 }, (_, index) => {
      const labelTime = new Date(windowStart.getTime() + index * 3600000);
      return {
        x: index * HOUR_W,
        label: formatTimelineLabel(labelTime, timeFormat),
      };
    });
  }, [timeFormat, windowStart]);

  const syncHeaderScroll = useCallback((scrollLeft: number) => {
    if (headerScrollRef.current) {
      headerScrollRef.current.scrollLeft = scrollLeft;
    }
  }, []);

  const jumpToNow = useCallback(() => {
    const targetLeft = Math.max(0, nowX - NOW_SCROLL_OFFSET);
    if (bodyScrollRef.current) {
      bodyScrollRef.current.scrollTo({ left: targetLeft, behavior: 'smooth' });
    }
    syncHeaderScroll(targetLeft);
  }, [nowX, syncHeaderScroll]);

  useEffect(() => {
    if (hasAutoScrolled.current || channels.length === 0) {
      return;
    }

    const targetLeft = Math.max(0, nowX - NOW_SCROLL_OFFSET);
    if (bodyScrollRef.current) {
      bodyScrollRef.current.scrollLeft = targetLeft;
    }
    syncHeaderScroll(targetLeft);
    hasAutoScrolled.current = true;
  }, [channels.length, nowX, syncHeaderScroll]);

  const handleTimelineScroll = useCallback(
    (event: React.UIEvent<HTMLDivElement>) => {
      syncHeaderScroll(event.currentTarget.scrollLeft);
    },
    [syncHeaderScroll]
  );

  const rowData = useMemo<RowData>(
    () => ({
      channels,
      getProgramsForChannel,
      onChannelClick,
      onProgramClick,
      onPlayCatchup,
      currentTime: activeTime,
      windowStart,
      timeFormat,
    }),
    [activeTime, channels, getProgramsForChannel, onChannelClick, onPlayCatchup, onProgramClick, timeFormat, windowStart]
  );

  if (channels.length === 0) {
    return (
      <div className="flex h-full items-center justify-center bg-black/20">
        <div className="text-center">
          <Clock size={36} className="mx-auto mb-3 text-white/10" />
          <p className="text-sm text-white/30">No channels available for the guide.</p>
        </div>
      </div>
    );
  }

  return (
    <div ref={containerRef} className="relative flex h-full flex-col overflow-hidden bg-black/30 select-none">
      <div className="flex shrink-0 border-b border-white/[0.08]" style={{ height: HEADER_H }}>
        <div
          className="flex shrink-0 items-center justify-center gap-2 border-r border-white/[0.08] bg-[#05070d] text-xs font-semibold text-white/45"
          style={{ width: CHANNEL_COL_W }}
        >
          <Clock size={12} />
          <span>Guide</span>
        </div>

        <div ref={headerScrollRef} className="flex-1 overflow-hidden bg-black/40">
          <div className="relative" style={{ width: GRID_W, height: HEADER_H }}>
            {timeSlots.map((slot, index) => (
              <div key={`${slot.label}-${index}`} className="absolute inset-y-0" style={{ left: slot.x }}>
                <div className="absolute inset-y-0 w-px bg-white/[0.05]" />
                <span className="relative top-3 pl-2 font-mono text-[11px] text-white/45">
                  {slot.label}
                </span>
              </div>
            ))}

            <div className="pointer-events-none absolute inset-y-0 z-20" style={{ left: nowX }}>
              <div className="h-full w-px bg-red-500/90" />
              <div className="absolute left-1/2 top-1 -translate-x-1/2 rounded-full bg-red-500 px-2 py-0.5 text-[9px] font-bold tracking-wide text-white">
                NOW
              </div>
            </div>
          </div>
        </div>
      </div>

      <div
        ref={bodyScrollRef}
        className="relative flex-1 overflow-x-auto overflow-y-hidden"
        onScroll={handleTimelineScroll}
      >
        <div className="relative" style={{ width: CHANNEL_COL_W + GRID_W, minHeight: viewportHeight }}>
          <div
            className="pointer-events-none absolute inset-y-0 z-10"
            style={{ left: CHANNEL_COL_W + nowX }}
          >
            <div className="h-full w-px bg-red-500/60" />
          </div>

          <FixedSizeList
            height={viewportHeight}
            width={CHANNEL_COL_W + GRID_W}
            itemCount={channels.length}
            itemSize={ROW_H}
            itemData={rowData}
            overscanCount={6}
          >
            {EpgRow}
          </FixedSizeList>
        </div>
      </div>

      <div className="absolute bottom-4 right-4 z-30">
        <button
          onClick={jumpToNow}
          className="rounded-full border border-red-500/35 bg-red-500/15 px-3 py-1.5 text-xs font-semibold text-red-300 transition-colors hover:bg-red-500/25"
        >
          Jump to Now
        </button>
      </div>
    </div>
  );
};

