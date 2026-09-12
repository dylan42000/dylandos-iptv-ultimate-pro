// components/VirtualContentGrid.tsx — Virtualized poster grid for 100k+ items
// Uses react-window FixedSizeGrid + ResizeObserver (no AutoSizer dep needed)

import React, { useEffect, useMemo, useRef, useState } from 'react';
import { FixedSizeGrid, GridChildComponentProps } from 'react-window';

export interface VirtualContentGridProps<T> {
  items: T[];
  renderCard: (item: T, index: number) => React.ReactNode;
  columnWidth?: number;
  rowHeight?: number;
  gap?: number;
  restorationKey?: string;
}

const positions = new Map<string, { scrollTop: number; focusedIndex: number | null }>();
type CellData<T> = { items: T[]; renderCard: (item: T, index: number) => React.ReactNode;
  columnCount: number; cellWidth: number; rowHeight: number; gap: number };

// Stable component identity prevents every visible card from remounting when
// favorites, progress, or parent callbacks change.
function ContentCell<T>({ columnIndex, rowIndex, style, data }: GridChildComponentProps<CellData<T>>) {
  const index = rowIndex * data.columnCount + columnIndex;
  if (index >= data.items.length) return null;
  return <div data-grid-index={index} style={{ ...style, width: data.cellWidth, height: data.rowHeight - data.gap }}>
    {data.renderCard(data.items[index], index)}
  </div>;
}

function VirtualContentGridInner<T>({
  items,
  renderCard,
  columnWidth = 180,
  rowHeight = 320,
  gap = 12,
  restorationKey,
}: VirtualContentGridProps<T>) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const saved = restorationKey ? positions.get(restorationKey) : undefined;
  const positionRef = useRef(saved || { scrollTop: 0, focusedIndex: null as number | null });
  useEffect(() => {
    positionRef.current = (restorationKey && positions.get(restorationKey)) || { scrollTop: 0, focusedIndex: null };
    return () => {
      if (!restorationKey) return;
      positions.set(restorationKey, { ...positionRef.current });
      if (positions.size > 40) positions.delete(positions.keys().next().value!);
    };
  }, [restorationKey]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const obs = new ResizeObserver(entries => {
      const { width, height } = entries[0].contentRect;
      setSize({ width, height });
    });
    obs.observe(el);
    // Set initial size
    const rect = el.getBoundingClientRect();
    setSize({ width: rect.width, height: rect.height });
    return () => obs.disconnect();
  }, []);

  const { width, height } = size;
  const columnCount = Math.max(1, Math.floor((width + gap) / (columnWidth + gap)));
  const rowCount = Math.ceil(items.length / columnCount);
  const cellWidth = columnCount > 1
    ? (width - gap * (columnCount - 1)) / columnCount
    : width;

  const data = useMemo(() => ({ items, renderCard, columnCount, cellWidth, rowHeight, gap }),
    [items, renderCard, columnCount, cellWidth, rowHeight, gap]);

  return (
    <div ref={containerRef} style={{ width: '100%', height: '100%' }} onFocusCapture={event => {
      const cell = (event.target as HTMLElement).closest<HTMLElement>('[data-grid-index]');
      if (cell) positionRef.current.focusedIndex = Number(cell.dataset.gridIndex);
    }}>
      {width > 0 && height > 0 && (
        <FixedSizeGrid<CellData<T>>
          key={restorationKey}
          itemData={data}
          initialScrollTop={saved?.scrollTop || 0}
          onScroll={({ scrollTop }) => { positionRef.current.scrollTop = scrollTop; }}
          onItemsRendered={() => {
            const index = positionRef.current.focusedIndex;
            if (index == null || document.activeElement !== document.body) return;
            containerRef.current?.querySelector<HTMLElement>(`[data-grid-index="${index}"] button, [data-grid-index="${index}"] [tabindex="0"]`)?.focus({ preventScroll: true });
          }}
          width={width}
          height={height}
          columnCount={columnCount}
          columnWidth={cellWidth + gap}
          rowCount={rowCount}
          rowHeight={rowHeight}
          overscanRowCount={4}
        >
          {ContentCell}
        </FixedSizeGrid>
      )}
    </div>
  );
}

export const VirtualContentGrid = React.memo(
  VirtualContentGridInner
) as typeof VirtualContentGridInner;
