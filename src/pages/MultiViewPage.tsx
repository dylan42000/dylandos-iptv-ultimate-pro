import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Hls from 'hls.js';
import { LayoutGrid, Volume2, VolumeX, X } from 'lucide-react';
import type { XtreamChannel } from '../types/xtream';
import { xtreamApi as xtream } from '../services/xtreamApi';

type TileCount = 2 | 3 | 4;

interface MultiViewPageProps {
  channels: XtreamChannel[];
  liveFormat: 'ts' | 'm3u8' | 'auto';
  onExit?: () => void;
}

interface TileState {
  channelId: string | null;
  url: string | null;
  title: string;
}

function buildLiveUrl(channel: XtreamChannel, liveFormat: 'ts' | 'm3u8' | 'auto'): string {
  if (channel.direct_source?.trim()) return channel.direct_source.trim();
  const format = liveFormat === 'm3u8' ? 'm3u8' : 'ts';
  return xtream.getLiveStreamUrl(channel.stream_id, format);
}

const MultiViewTile: React.FC<{
  index: number;
  tile: TileState;
  audioFocused: boolean;
  channels: XtreamChannel[];
  liveFormat: 'ts' | 'm3u8' | 'auto';
  onFocusAudio: () => void;
  onAssign: (channel: XtreamChannel) => void;
  onClear: () => void;
}> = ({ index, tile, audioFocused, channels, liveFormat, onFocusAudio, onAssign, onClear }) => {
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const hlsRef = useRef<Hls | null>(null);
  const [picker, setPicker] = useState('');

  useEffect(() => {
    const video = videoRef.current;
    if (!video || !tile.url) return;

    const url = tile.url;
    const isHls = /\.m3u8(\?|$)/i.test(url) || url.includes('m3u8');

    if (hlsRef.current) {
      hlsRef.current.destroy();
      hlsRef.current = null;
    }

    if (isHls && Hls.isSupported()) {
      const hls = new Hls({ enableWorker: true, lowLatencyMode: true });
      hlsRef.current = hls;
      hls.loadSource(url);
      hls.attachMedia(video);
      hls.on(Hls.Events.MANIFEST_PARSED, () => {
        video.play().catch(() => {});
      });
    } else {
      video.src = url;
      video.play().catch(() => {});
    }

    return () => {
      if (hlsRef.current) {
        hlsRef.current.destroy();
        hlsRef.current = null;
      }
      video.removeAttribute('src');
      video.load();
    };
  }, [tile.url]);

  useEffect(() => {
    const video = videoRef.current;
    if (!video) return;
    video.muted = !audioFocused;
    if (audioFocused) video.volume = 1;
  }, [audioFocused]);

  const filtered = useMemo(() => {
    const q = picker.trim().toLowerCase();
    if (!q) return channels.slice(0, 40);
    return channels.filter(c => c.name?.toLowerCase().includes(q)).slice(0, 40);
  }, [channels, picker]);

  return (
    <div
      className={`relative rounded-xl overflow-hidden border bg-black ${
        audioFocused ? 'border-cyan-400/60 ring-1 ring-cyan-400/30' : 'border-white/10'
      }`}
      onClick={onFocusAudio}
    >
      <video
        ref={videoRef}
        className="w-full h-full object-contain bg-black"
        playsInline
        autoPlay
        muted={!audioFocused}
      />
      <div className="absolute top-2 left-2 right-2 flex items-center gap-2 pointer-events-none">
        <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded bg-black/60 text-white/70">
          Tile {index + 1}
        </span>
        <span className="text-xs text-white/80 truncate drop-shadow">{tile.title || 'Empty'}</span>
        <span className="ml-auto pointer-events-auto">
          {audioFocused ? (
            <Volume2 size={14} className="text-cyan-300" />
          ) : (
            <VolumeX size={14} className="text-white/40" />
          )}
        </span>
      </div>
      <div className="absolute bottom-0 inset-x-0 p-2 bg-gradient-to-t from-black/90 to-transparent">
        <div className="flex gap-2 items-center">
          <input
            value={picker}
            onChange={e => setPicker(e.target.value)}
            onClick={e => e.stopPropagation()}
            placeholder="Pick channel…"
            className="flex-1 bg-white/10 border border-white/15 rounded-lg px-2 py-1 text-xs text-white/80"
          />
          {tile.url && (
            <button
              onClick={e => { e.stopPropagation(); onClear(); }}
              className="p-1 rounded bg-white/10 text-white/50 hover:text-white"
            >
              <X size={12} />
            </button>
          )}
        </div>
        {picker && (
          <div className="mt-1 max-h-28 overflow-y-auto rounded-lg bg-black/80 border border-white/10">
            {filtered.map(ch => (
              <button
                key={ch.stream_id}
                onClick={e => {
                  e.stopPropagation();
                  onAssign(ch);
                  setPicker('');
                }}
                className="w-full text-left px-2 py-1 text-xs text-white/70 hover:bg-cyan-500/20 hover:text-cyan-200 truncate"
              >
                {ch.num != null ? `#${ch.num} ` : ''}{ch.name}
              </button>
            ))}
          </div>
        )}
      </div>
    </div>
  );
};

export const MultiViewPage: React.FC<MultiViewPageProps> = ({
  channels,
  liveFormat,
  onExit,
}) => {
  const [tileCount, setTileCount] = useState<TileCount>(4);
  const [audioFocus, setAudioFocus] = useState(0);
  const [tiles, setTiles] = useState<TileState[]>(() =>
    Array.from({ length: 4 }, () => ({ channelId: null, url: null, title: '' }))
  );

  const assign = useCallback((index: number, channel: XtreamChannel) => {
    const url = buildLiveUrl(channel, liveFormat);
    setTiles(prev => {
      const next = [...prev];
      next[index] = {
        channelId: String(channel.stream_id),
        url,
        title: channel.name,
      };
      return next;
    });
    setAudioFocus(index);
  }, [liveFormat]);

  const clear = useCallback((index: number) => {
    setTiles(prev => {
      const next = [...prev];
      next[index] = { channelId: null, url: null, title: '' };
      return next;
    });
  }, []);

  const gridClass = useMemo(() => {
    switch (tileCount) {
      case 2: return 'grid-cols-2 grid-rows-1';
      case 3: return 'grid-cols-2 grid-rows-2';
      case 4: return 'grid-cols-2 grid-rows-2';
      default: {
        const _exhaustive: never = tileCount;
        return _exhaustive;
      }
    }
  }, [tileCount]);

  return (
    <div className="flex-1 flex flex-col overflow-hidden p-4 gap-3">
      <div className="flex items-center gap-3 shrink-0">
        <LayoutGrid size={16} className="text-cyan-400" />
        <h1 className="text-white/90 text-sm font-semibold">Multi-View</h1>
        <p className="text-white/35 text-xs">One audio focus — click a tile for sound (Ctrl+M)</p>
        <div className="ml-auto flex gap-1">
          {([2, 3, 4] as TileCount[]).map(n => (
            <button
              key={n}
              onClick={() => setTileCount(n)}
              className={`px-2.5 py-1 rounded-lg text-xs ${
                tileCount === n ? 'bg-cyan-500/20 text-cyan-300' : 'bg-white/5 text-white/50'
              }`}
            >
              {n}
            </button>
          ))}
          {onExit && (
            <button onClick={onExit} className="px-2.5 py-1 rounded-lg text-xs bg-white/5 text-white/50 hover:text-white">
              Exit
            </button>
          )}
        </div>
      </div>

      <div className={`flex-1 grid gap-2 min-h-0 ${gridClass}`}>
        {tiles.slice(0, tileCount).map((tile, i) => (
          <MultiViewTile
            key={i}
            index={i}
            tile={tile}
            audioFocused={audioFocus === i}
            channels={channels}
            liveFormat={liveFormat}
            onFocusAudio={() => setAudioFocus(i)}
            onAssign={ch => assign(i, ch)}
            onClear={() => clear(i)}
          />
        ))}
        {tileCount === 3 && <div className="rounded-xl border border-dashed border-white/5" />}
      </div>
    </div>
  );
};
