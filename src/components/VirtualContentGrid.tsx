// components/VirtualContentGrid.tsx — Virtualized poster grid for 100k+ items
// Uses react-window FixedSizeGrid + ResizeObserver (no AutoSizer dep needed)

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { FixedSizeGrid, GridChildComponentProps } from 'react-window';

export interface VirtualContentGridProps<T> {
  items: T[];
  renderCard: (item: T, index: number) => React.ReactNode;
  columnWidth?: number;
  rowHeight?: number;
  gap?: number;
}

function VirtualContentGridInner<T>({
  items,
  renderCard,
  columnWidth = 180,
  rowHeight = 320,
  gap = 12,
}: VirtualContentGridProps<T>) {
  const containerRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });

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

  const Cell = useCallback(
    ({ columnIndex, rowIndex, style }: GridChildComponentProps) => {
      const index = rowIndex * columnCount + columnIndex;
      if (index >= items.length) return null;
      return (
        <div
          style={{
            ...style,
            left: Number(style.left) + columnIndex * gap,
            top: Number(style.top) + rowIndex * gap,
            width: cellWidth,
            height: rowHeight - gap,
          }}
        >
          {renderCard(items[index], index)}
        </div>
      );
    },
    [items, columnCount, cellWidth, renderCard, rowHeight, gap]
  );

  return (
    <div ref={containerRef} style={{ width: '100%', height: '100%' }}>
      {width > 0 && height > 0 && (
        <FixedSizeGrid
          width={width}
          height={height}
          columnCount={columnCount}
          columnWidth={cellWidth + gap}
          rowCount={rowCount}
          rowHeight={rowHeight}
          overscanRowCount={4}
        >
          {Cell}
        </FixedSizeGrid>
      )}
    </div>
  );
}

export const VirtualContentGrid = React.memo(
  VirtualContentGridInner
) as typeof VirtualContentGridInner;
