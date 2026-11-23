import { api } from "./account-api";
import type { PublicTeacher } from "./match-types";

export const priceBands = [
  { code: "under100", label: "低于 100 元", min: 0, max: 100 },
  { code: "100to200", label: "100 至不足 200 元", min: 100, max: 200 },
  { code: "200plus", label: "200 元及以上", min: 200, max: Infinity },
  { code: "negotiable", label: "面议", min: 0, max: 0 },
];

export interface TeacherFilters {
  category: string;
  area: string[];
  mode: string[];
  price: string[];
}

export function readTeacherFilters(query: URLSearchParams): TeacherFilters {
  const codes = (key: string) => [...new Set(query.get(key)?.split(",").filter(Boolean) ?? [])];
  return { category: query.get("category") || "", area: codes("area"), mode: codes("mode"),
    price: codes("price").filter(code => priceBands.some(band => band.code === code)) };
}

export function filterQuery(filters: TeacherFilters) {
  return { category: filters.category, area: filters.area.join(","), mode: filters.mode.join(","), price: filters.price.join(",") };
}

type Page = { items: PublicTeacher[]; nextCursor: string | null };

function matchesPrice(teacher: PublicTeacher, selected: string[]) {
  if (!selected.length) return true;
  if (teacher.priceMin == null || teacher.priceMax == null) return selected.includes("negotiable");
  return priceBands.some(band => band.code !== "negotiable" && selected.includes(band.code)
    && Number(teacher.priceMax) >= band.min && Number(teacher.priceMin) < band.max);
}

// The API supports one street/mode at a time. For a union or price filter, fill
// a displayed page across server pages and resume at the first unconsumed match.
export async function teacherPage(filters: TeacherFilters, cursor: string | null, current: () => boolean): Promise<Page> {
  const query = new URLSearchParams();
  if (filters.category) query.set("categoryCode", filters.category);
  if (filters.area.length === 1) query.set("areaCode", filters.area[0]);
  if (filters.mode.length === 1) query.set("serviceMode", filters.mode[0]);
  const fetchPage = (start: string | null) => {
    if (start) query.set("cursor", start);
    else query.delete("cursor");
    return api.request<Page>("/teachers?" + query);
  };
  if (!filters.price.length && filters.area.length <= 1 && filters.mode.length <= 1) return fetchPage(cursor);

  let start = cursor;
  let offset = 0;
  if (cursor?.startsWith("[")) {
    const pos: unknown = JSON.parse(cursor);
    if (!Array.isArray(pos) || pos.length !== 2 || (pos[0] !== null && typeof pos[0] !== "string")
      || !Number.isInteger(pos[1]) || pos[1] < 0 || pos[1] >= 10) throw new Error("分页位置已失效，请重置筛选。");
    [start, offset] = [pos[0], pos[1]];
  }
  const items: PublicTeacher[] = [];
  do {
    const page = await fetchPage(start);
    for (let i = offset; i < page.items.length; i++) {
      const t = page.items[i];
      if (filters.area.length > 1 && !t.areaCodes.some(code => filters.area.includes(code))) continue;
      if (filters.mode.length > 1 && !t.serviceModes.some(code => filters.mode.includes(code))) continue;
      if (!matchesPrice(t, filters.price)) continue;
      if (items.length === 10) return { items, nextCursor: JSON.stringify([start, i]) };
      items.push(t);
    }
    offset = 0;
    start = page.nextCursor;
  } while (start && current());
  return { items, nextCursor: null };
}
