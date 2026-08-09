// ─── Custom Lists / Collections ──────────────────────────────────────────────
// Parity with Android CustomLists: named lists holding live/vod/series items.

export type CustomListContentType = 'live' | 'vod' | 'series';

export interface CustomListItem {
  contentType: CustomListContentType;
  contentId: string;
  title: string;
  posterUrl?: string;
  sortPosition: number;
  addedAt: number;
}

export interface CustomList {
  id: string;
  name: string;
  normalizedName: string;
  sortPosition: number;
  createdAt: number;
  updatedAt: number;
  items: CustomListItem[];
}

const STORAGE_KEY = 'dylandos:customLists';

function normalizeName(name: string): string {
  return name
    .normalize('NFKD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, ' ')
    .trim();
}

function loadRaw(): CustomList[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}

function saveRaw(lists: CustomList[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(lists));
  } catch (err) {
    console.warn('[customLists] Failed to save:', err);
  }
}

class CustomListsService {
  getLists(): CustomList[] {
    return loadRaw().slice().sort((a, b) => a.sortPosition - b.sortPosition);
  }

  getList(id: string): CustomList | null {
    return loadRaw().find(l => l.id === id) ?? null;
  }

  create(name: string): CustomList {
    const clean = name.trim();
    if (!clean) throw new Error('Enter a list name');
    const lists = loadRaw();
    const list: CustomList = {
      id: crypto.randomUUID(),
      name: clean,
      normalizedName: normalizeName(clean),
      sortPosition: lists.length,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      items: [],
    };
    lists.push(list);
    saveRaw(lists);
    return list;
  }

  rename(id: string, name: string): CustomList | null {
    const clean = name.trim();
    if (!clean) throw new Error('Enter a list name');
    const lists = loadRaw();
    const idx = lists.findIndex(l => l.id === id);
    if (idx < 0) return null;
    lists[idx] = {
      ...lists[idx],
      name: clean,
      normalizedName: normalizeName(clean),
      updatedAt: Date.now(),
    };
    saveRaw(lists);
    return lists[idx];
  }

  delete(id: string): void {
    saveRaw(loadRaw().filter(l => l.id !== id));
  }

  addItem(
    listId: string,
    item: Omit<CustomListItem, 'sortPosition' | 'addedAt'>
  ): CustomList | null {
    const lists = loadRaw();
    const idx = lists.findIndex(l => l.id === listId);
    if (idx < 0) return null;
    const list = lists[idx];
    const exists = list.items.some(
      i => i.contentType === item.contentType && i.contentId === item.contentId
    );
    if (exists) return list;
    list.items.push({
      ...item,
      sortPosition: list.items.length,
      addedAt: Date.now(),
    });
    list.updatedAt = Date.now();
    saveRaw(lists);
    return list;
  }

  removeItem(listId: string, contentType: CustomListContentType, contentId: string): void {
    const lists = loadRaw();
    const idx = lists.findIndex(l => l.id === listId);
    if (idx < 0) return;
    lists[idx].items = lists[idx].items
      .filter(i => !(i.contentType === contentType && i.contentId === contentId))
      .map((i, n) => ({ ...i, sortPosition: n }));
    lists[idx].updatedAt = Date.now();
    saveRaw(lists);
  }

  moveItem(listId: string, contentId: string, contentType: CustomListContentType, offset: number): void {
    const lists = loadRaw();
    const idx = lists.findIndex(l => l.id === listId);
    if (idx < 0) return;
    const items = lists[idx].items.slice();
    const from = items.findIndex(i => i.contentId === contentId && i.contentType === contentType);
    if (from < 0) return;
    const to = Math.max(0, Math.min(items.length - 1, from + offset));
    if (from === to) return;
    const [row] = items.splice(from, 1);
    items.splice(to, 0, row);
    lists[idx].items = items.map((i, n) => ({ ...i, sortPosition: n }));
    lists[idx].updatedAt = Date.now();
    saveRaw(lists);
  }
}

export const customLists = new CustomListsService();
