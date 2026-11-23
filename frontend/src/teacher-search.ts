import { api } from "./account-api";
import type { PublicTeacher } from "./match-types";

import { filteredPage, matchesPrice, priceBands } from "./discovery-page";
export { priceBands };

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

  return filteredPage(cursor, current, fetchPage, t =>
    (filters.area.length <= 1 || t.areaCodes.some(code => filters.area.includes(code))) &&
    (filters.mode.length <= 1 || t.serviceModes.some(code => filters.mode.includes(code))) &&
    matchesPrice(t.priceMin, t.priceMax, filters.price));
}
