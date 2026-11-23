export const priceBands = [
  { code: "under100", label: "低于 100 元", min: 0, max: 100 },
  { code: "100to200", label: "100 至不足 200 元", min: 100, max: 200 },
  { code: "200plus", label: "200 元及以上", min: 200, max: Infinity },
  { code: "negotiable", label: "面议", min: 0, max: 0 },
];

export type DiscoveryPage<T> = { items: T[]; nextCursor: string | null };

export function matchesPrice(min: unknown, max: unknown, selected: string[]) {
  if (!selected.length) return true;
  if (min == null || max == null) return selected.includes("negotiable");
  return priceBands.some(band => band.code !== "negotiable" && selected.includes(band.code)
    && Number(max) >= band.min && Number(min) < band.max);
}

// Resume at the first unconsumed match, even when one displayed page spans multiple API pages.
export async function filteredPage<T>(cursor: string | null, current: () => boolean,
  fetchPage: (start: string | null) => Promise<DiscoveryPage<T>>, matches: (item: T) => boolean): Promise<DiscoveryPage<T>> {
  let start = cursor;
  let offset = 0;
  if (cursor?.startsWith("[")) {
    const pos: unknown = JSON.parse(cursor);
    if (!Array.isArray(pos) || pos.length !== 2 || (pos[0] !== null && typeof pos[0] !== "string")
      || !Number.isInteger(pos[1]) || pos[1] < 0 || pos[1] >= 10) throw new Error("分页位置已失效，请重置筛选。");
    [start, offset] = [pos[0], pos[1]];
  }
  const items: T[] = [];
  do {
    const page = await fetchPage(start);
    for (let i = offset; i < page.items.length; i++) {
      const item = page.items[i];
      if (!matches(item)) continue;
      if (items.length === 10) return { items, nextCursor: JSON.stringify([start, i]) };
      items.push(item);
    }
    offset = 0;
    start = page.nextCursor;
  } while (start && current());
  return { items, nextCursor: null };
}
