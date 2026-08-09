// workers/vodFilter.worker.ts — Web Worker for 50k+ VOD filtering

interface FilterMessage {
  items: Array<{ stream_id?: number; series_id?: number; name: string; category_id: string }>;
  query: string;
  categoryId: string | null;
}

self.onmessage = (e: MessageEvent<FilterMessage>) => {
  const { items, query, categoryId } = e.data;
  const q = query.toLowerCase().trim();

  const filtered = items.filter(item => {
    if (categoryId && item.category_id !== categoryId) return false;
    if (q && !item.name?.toLowerCase().includes(q)) return false;
    return true;
  });

  self.postMessage(filtered);
};
