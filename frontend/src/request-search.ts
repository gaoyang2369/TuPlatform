import { api } from "./account-api";
import { filteredPage, matchesPrice, priceBands, type DiscoveryPage } from "./discovery-page";
import type { PublicRequest } from "./match-types";

export function readRequestFilters(query: URLSearchParams) {
  const codes = (key: string) => [...new Set(query.get(key)?.split(",").filter(Boolean) ?? [])];
  return { category: query.get("category") || "", district: codes("district"), mode: codes("mode"),
    budget: codes("budget").filter(code => priceBands.some(b => b.code === code && code !== "negotiable")),
    grade: query.get("grade") || "", subject: query.get("subject") || "" };
}
export type RequestFilters = ReturnType<typeof readRequestFilters>;
export function requestFilterQuery(filters: RequestFilters) {
  return { ...filters, district: filters.district.join(","), mode: filters.mode.join(","), budget: filters.budget.join(",") };
}

export function requestPage(filters: RequestFilters, cursor: string | null, current: () => boolean) {
  const q = new URLSearchParams();
  if (filters.category) q.set("categoryCode", filters.category);
  if (filters.district.length === 1) q.set("districtCode", filters.district[0]);
  if (filters.mode.length === 1) q.set("serviceMode", filters.mode[0]);
  if (filters.grade.trim()) q.set("grade", filters.grade.trim());
  if (filters.subject.trim()) q.set("subject", filters.subject.trim());
  const fetchPage = (start: string | null) => {
    if (start) q.set("cursor", start);
    else q.delete("cursor");
    return api.request<DiscoveryPage<PublicRequest>>("/public/requests?" + q);
  };
  if (!filters.budget.length && filters.district.length <= 1 && filters.mode.length <= 1) return fetchPage(cursor);
  return filteredPage(cursor, current, fetchPage, item =>
    (filters.district.length <= 1 || filters.district.includes(String(item.districtCode || "SZ_PINGSHAN"))) &&
    (filters.mode.length <= 1 || filters.mode.includes(String(item.serviceMode))) &&
    matchesPrice(item.budgetMin, item.budgetMax, filters.budget));
}
