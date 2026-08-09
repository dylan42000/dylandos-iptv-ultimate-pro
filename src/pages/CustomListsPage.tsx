import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ListPlus,
  Trash2,
  Pencil,
  ChevronUp,
  ChevronDown,
  Tv,
  Film,
  Clapperboard,
  Play,
} from 'lucide-react';
import {
  customLists,
  type CustomList,
  type CustomListItem,
  type CustomListContentType,
} from '../services/customLists';
import type { XtreamChannel, XtreamMovie, XtreamSeries } from '../types/xtream';

interface CustomListsPageProps {
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
  onPlayChannel: (channel: XtreamChannel) => void;
  onPlayMovie: (movie: XtreamMovie) => void;
  onSelectSeries: (series: XtreamSeries) => void;
}

export const CustomListsPage: React.FC<CustomListsPageProps> = ({
  channels,
  movies,
  series,
  onPlayChannel,
  onPlayMovie,
  onSelectSeries,
}) => {
  const [lists, setLists] = useState<CustomList[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [newName, setNewName] = useState('');
  const [renameValue, setRenameValue] = useState('');
  const [message, setMessage] = useState<string | null>(null);

  const reload = useCallback(() => {
    const next = customLists.getLists();
    setLists(next);
    setSelectedId(prev => {
      if (prev && next.some(l => l.id === prev)) return prev;
      return next[0]?.id ?? null;
    });
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const selected = useMemo(
    () => lists.find(l => l.id === selectedId) ?? null,
    [lists, selectedId]
  );

  const flash = (msg: string) => {
    setMessage(msg);
    window.setTimeout(() => setMessage(null), 2200);
  };

  const handleCreate = () => {
    try {
      const list = customLists.create(newName);
      setNewName('');
      reload();
      setSelectedId(list.id);
      flash('List created');
    } catch (err: unknown) {
      flash(err instanceof Error ? err.message : 'Could not create list');
    }
  };

  const handleRename = () => {
    if (!selected) return;
    try {
      customLists.rename(selected.id, renameValue || selected.name);
      setRenameValue('');
      reload();
      flash('List renamed');
    } catch (err: unknown) {
      flash(err instanceof Error ? err.message : 'Could not rename');
    }
  };

  const handleDelete = () => {
    if (!selected) return;
    customLists.delete(selected.id);
    reload();
    flash('List deleted');
  };

  const resolveItem = (item: CustomListItem) => {
    switch (item.contentType) {
      case 'live':
        return channels.find(c => String(c.stream_id) === item.contentId) ?? null;
      case 'vod':
        return movies.find(m => String(m.stream_id) === item.contentId) ?? null;
      case 'series':
        return series.find(s => String(s.series_id) === item.contentId) ?? null;
      default: {
        const _exhaustive: never = item.contentType;
        void _exhaustive;
        return null;
      }
    }
  };

  const playItem = (item: CustomListItem) => {
    const resolved = resolveItem(item);
    switch (item.contentType) {
      case 'live':
        if (resolved) onPlayChannel(resolved as XtreamChannel);
        break;
      case 'vod':
        if (resolved) onPlayMovie(resolved as XtreamMovie);
        break;
      case 'series':
        if (resolved) onSelectSeries(resolved as XtreamSeries);
        break;
      default: {
        const _exhaustive: never = item.contentType;
        void _exhaustive;
      }
    }
  };

  const iconFor = (t: CustomListContentType) => {
    switch (t) {
      case 'live': return <Tv size={14} className="text-cyan-400/70" />;
      case 'vod': return <Film size={14} className="text-purple-400/70" />;
      case 'series': return <Clapperboard size={14} className="text-green-400/70" />;
      default: {
        const _exhaustive: never = t;
        return _exhaustive;
      }
    }
  };

  return (
    <div className="flex-1 flex overflow-hidden">
      <aside className="w-64 border-r border-white/5 p-4 flex flex-col gap-3 shrink-0">
        <h2 className="text-white/80 text-sm font-semibold">Collections</h2>
        <div className="flex gap-2">
          <input
            value={newName}
            onChange={e => setNewName(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') handleCreate(); }}
            placeholder="New list name"
            className="flex-1 bg-white/5 border border-white/10 rounded-lg px-2 py-1.5 text-sm text-white/80"
          />
          <button
            onClick={handleCreate}
            className="p-2 rounded-lg bg-cyan-500/15 text-cyan-400 hover:bg-cyan-500/25"
            title="Create list"
          >
            <ListPlus size={16} />
          </button>
        </div>
        <div className="flex-1 overflow-y-auto space-y-1">
          {lists.map(list => (
            <button
              key={list.id}
              onClick={() => setSelectedId(list.id)}
              className={`w-full text-left px-3 py-2 rounded-xl text-sm transition-colors ${
                selectedId === list.id
                  ? 'bg-cyan-500/15 text-cyan-300'
                  : 'text-white/60 hover:bg-white/5'
              }`}
            >
              <div className="truncate font-medium">{list.name}</div>
              <div className="text-[10px] text-white/30">{list.items.length} items</div>
            </button>
          ))}
          {lists.length === 0 && (
            <p className="text-white/30 text-xs px-1">No collections yet. Create one to group channels, movies, or series.</p>
          )}
        </div>
      </aside>

      <main className="flex-1 flex flex-col overflow-hidden p-6">
        {!selected ? (
          <p className="text-white/40 text-sm">Select or create a collection.</p>
        ) : (
          <>
            <div className="flex items-center gap-3 mb-4 flex-wrap">
              <h1 className="text-white text-lg font-semibold">{selected.name}</h1>
              <input
                value={renameValue}
                onChange={e => setRenameValue(e.target.value)}
                placeholder="Rename…"
                className="bg-white/5 border border-white/10 rounded-lg px-2 py-1 text-sm text-white/70 w-40"
              />
              <button onClick={handleRename} className="p-2 rounded-lg hover:bg-white/5 text-white/50" title="Rename">
                <Pencil size={14} />
              </button>
              <button onClick={handleDelete} className="p-2 rounded-lg hover:bg-red-500/10 text-red-400/70" title="Delete list">
                <Trash2 size={14} />
              </button>
              {message && <span className="text-cyan-400/80 text-xs">{message}</span>}
            </div>

            <div className="flex-1 overflow-y-auto space-y-1">
              {selected.items.length === 0 && (
                <p className="text-white/30 text-sm">
                  This list is empty. From Live TV / Movies / Series, add items via the list actions (or favorites for now, then move here in a future build).
                  You can also use Search results — right-click add coming soon; use the add buttons below from current browse windows.
                </p>
              )}
              {selected.items.map(item => (
                <div
                  key={`${item.contentType}:${item.contentId}`}
                  className="flex items-center gap-3 px-3 py-2 rounded-xl hover:bg-white/[0.04] group"
                >
                  {iconFor(item.contentType)}
                  <div className="flex-1 min-w-0">
                    <p className="text-white/80 text-sm truncate">{item.title}</p>
                    <p className="text-white/30 text-[10px] uppercase">{item.contentType}</p>
                  </div>
                  <button
                    onClick={() => playItem(item)}
                    className="p-1.5 rounded-lg text-white/0 group-hover:text-cyan-400 hover:bg-white/5"
                    title="Play / Open"
                  >
                    <Play size={14} />
                  </button>
                  <button
                    onClick={() => { customLists.moveItem(selected.id, item.contentId, item.contentType, -1); reload(); }}
                    className="p-1 text-white/30 hover:text-white/70"
                  >
                    <ChevronUp size={14} />
                  </button>
                  <button
                    onClick={() => { customLists.moveItem(selected.id, item.contentId, item.contentType, 1); reload(); }}
                    className="p-1 text-white/30 hover:text-white/70"
                  >
                    <ChevronDown size={14} />
                  </button>
                  <button
                    onClick={() => { customLists.removeItem(selected.id, item.contentType, item.contentId); reload(); }}
                    className="p-1 text-white/30 hover:text-red-400"
                  >
                    <Trash2 size={14} />
                  </button>
                </div>
              ))}
            </div>

            <QuickAddBar
              listId={selected.id}
              channels={channels}
              movies={movies}
              series={series}
              onAdded={() => { reload(); flash('Item added'); }}
            />
          </>
        )}
      </main>
    </div>
  );
};

const QuickAddBar: React.FC<{
  listId: string;
  channels: XtreamChannel[];
  movies: XtreamMovie[];
  series: XtreamSeries[];
  onAdded: () => void;
}> = ({ listId, channels, movies, series, onAdded }) => {
  const [query, setQuery] = useState('');
  const q = query.trim().toLowerCase();
  const hits = useMemo(() => {
    if (!q) return [] as Array<{ type: CustomListContentType; id: string; title: string; poster?: string }>;
    const out: Array<{ type: CustomListContentType; id: string; title: string; poster?: string }> = [];
    for (const c of channels) {
      if (c.name?.toLowerCase().includes(q)) {
        out.push({ type: 'live', id: String(c.stream_id), title: c.name, poster: c.stream_icon });
      }
      if (out.length >= 12) break;
    }
    for (const m of movies) {
      if (m.name?.toLowerCase().includes(q)) {
        out.push({ type: 'vod', id: String(m.stream_id), title: m.name, poster: m.stream_icon });
      }
      if (out.length >= 18) break;
    }
    for (const s of series) {
      if (s.name?.toLowerCase().includes(q)) {
        out.push({ type: 'series', id: String(s.series_id), title: s.name, poster: s.cover });
      }
      if (out.length >= 24) break;
    }
    return out.slice(0, 12);
  }, [q, channels, movies, series]);

  return (
    <div className="mt-4 border-t border-white/5 pt-4">
      <p className="text-white/40 text-xs mb-2">Quick add from currently loaded catalogs</p>
      <input
        value={query}
        onChange={e => setQuery(e.target.value)}
        placeholder="Search current browse window…"
        className="w-full bg-white/5 border border-white/10 rounded-xl px-3 py-2 text-sm text-white/80 mb-2"
      />
      <div className="flex flex-wrap gap-2">
        {hits.map(h => (
          <button
            key={`${h.type}:${h.id}`}
            onClick={() => {
              customLists.addItem(listId, {
                contentType: h.type,
                contentId: h.id,
                title: h.title,
                posterUrl: h.poster,
              });
              setQuery('');
              onAdded();
            }}
            className="px-2.5 py-1 rounded-lg bg-white/5 border border-white/10 text-xs text-white/70 hover:border-cyan-500/40 hover:text-cyan-300"
          >
            + {h.title.slice(0, 40)}
          </button>
        ))}
      </div>
    </div>
  );
};
