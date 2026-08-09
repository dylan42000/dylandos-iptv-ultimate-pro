import { useEffect, useRef, useState } from 'react';

export const useWorkerFilter = <T extends { name: string; category_id: string }>(
  items: T[],
  query: string,
  categoryId: string | null,
  debounceMs = 150
): { filtered: T[]; isFiltering: boolean } => {
  const [filtered, setFiltered] = useState<T[]>(items);
  const [isFiltering, setIsFiltering] = useState(false);
  const workerRef = useRef<Worker | null>(null);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    workerRef.current = new Worker(
      new URL('../workers/vodFilter.worker.ts', import.meta.url),
      { type: 'module' }
    );
    workerRef.current.onmessage = (e: MessageEvent<T[]>) => {
      setFiltered(e.data);
      setIsFiltering(false);
    };
    return () => workerRef.current?.terminate();
  }, []);

  useEffect(() => {
    if (!workerRef.current || items.length === 0) {
      setFiltered([]);
      setIsFiltering(false);
      return;
    }

    const q = query.trim();

    // Fast path: no active filters, return source list directly.
    if (!q && !categoryId) {
      setIsFiltering(false);
      setFiltered(items);
      return;
    }

    // For small datasets, filter inline to avoid worker overhead.
    if (items.length < 2000) {
      const lower = q.toLowerCase();
      const inline = items.filter(item => {
        if (categoryId && item.category_id !== categoryId) return false;
        if (lower && !item.name?.toLowerCase().includes(lower)) return false;
        return true;
      });
      setFiltered(inline);
      setIsFiltering(false);
      return;
    }

    if (timerRef.current) clearTimeout(timerRef.current);

    setIsFiltering(true);
    timerRef.current = setTimeout(() => {
      workerRef.current?.postMessage({ items, query, categoryId });
    }, debounceMs);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [items, query, categoryId, debounceMs]);

  return { filtered, isFiltering };
};
