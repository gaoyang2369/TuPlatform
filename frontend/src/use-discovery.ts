import { nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from "vue";
import { readPages, restoreScroll, updateQuery } from "./navigation";
import { ApiFailure } from "./account-api";

interface DiscoveryOptions<F, T> {
  view: "teachers" | "public";
  detailKey: "teacher" | "request";
  revision: () => number;
  readFilters: (query: URLSearchParams) => F;
  filterQuery: (filters: F) => Record<string, string>;
  fetchPage: (filters: F, cursor: string | null, current: () => boolean) => Promise<{ items: T[]; nextCursor: string | null }>;
  fetchDetail: (id: string) => Promise<T>;
  failure: (error: unknown) => string;
}

export function useDiscovery<F, T>(options: DiscoveryOptions<F, T>) {
  const query = () => new URLSearchParams(location.search);
  const filters = shallowRef<F>(options.readFilters(query()));
  const items = shallowRef<T[]>([]), detail = shallowRef<T | null>(null);
  const selectedId = ref(query().get(options.detailKey) || undefined);
  const listBusy = ref(true), listError = ref(""), detailError = ref("");
  const pageNumber = ref(1), nextCursor = ref<string | null>(null);
  const heading = ref<HTMLElement>();
  let pageCursors: (string | null)[] = [null];
  let listVersion = 0, detailVersion = 0, loadedKey = "", alive = true;
  let refreshOnClose = false;
  let displayedFilters = filters.value, trigger: HTMLElement | null = null;
  let retry = () => {};
  const pageKey = (values: F, cursors: (string | null)[]) => JSON.stringify([options.filterQuery(values), cursors]);

  async function loadList(values: F, cursors: (string | null)[], fromHistory = false) {
    const version = ++listVersion;
    const current = () => version === listVersion;
    filters.value = values;
    listBusy.value = true;
    listError.value = "";
    try {
      const result = await options.fetchPage(values, cursors[cursors.length - 1], current);
      if (!current()) return;
      items.value = result.items;
      nextCursor.value = result.nextCursor;
      pageNumber.value = cursors.length;
      pageCursors = cursors;
      loadedKey = pageKey(values, cursors);
      displayedFilters = values;
      updateQuery({ view: options.view, ...options.filterQuery(values), page: cursors.length > 1 ? String(cursors.length) : undefined,
        pages: cursors.length > 1 ? JSON.stringify(cursors) : undefined }, fromHistory);
      if (fromHistory && !selectedId.value) { await nextTick(); restoreScroll(); }
      return true;
    } catch (e) {
      if (!current()) return;
      listError.value = options.failure(e);
      if (loadedKey) filters.value = displayedFilters;
      retry = () => void loadList(values, cursors, fromHistory);
    } finally { if (current()) listBusy.value = false; }
  }

  async function changePage(direction: number) {
    if (listBusy.value || (direction > 0 ? !nextCursor.value : pageNumber.value <= 1)) return;
    const cursors = direction > 0 ? [...pageCursors, nextCursor.value] : pageCursors.slice(0, -1);
    if (await loadList(filters.value, cursors)) {
      heading.value?.focus({ preventScroll: true });
      heading.value?.scrollIntoView({ block: "start" });
    }
  }

  async function loadDetail(id?: string) {
    const version = ++detailVersion;
    selectedId.value = id;
    detail.value = null;
    detailError.value = "";
    if (!id) return;
    try {
      const result = await options.fetchDetail(id);
      if (version === detailVersion) detail.value = result;
    } catch (e) {
      if (version !== detailVersion) return;
      detailError.value = options.failure(e);
      if (e instanceof ApiFailure && e.status === 404) refreshOnClose = true;
    }
  }

  function open(id: string, event: MouseEvent) {
    trigger = event.currentTarget as HTMLElement;
    updateQuery({ view: options.view, [options.detailKey]: id });
    history.replaceState({ ...history.state, discoveryList: options.view }, "");
    void loadDetail(id);
  }
  function close() {
    if (history.state?.discoveryList === options.view || (options.view === "teachers" && history.state?.fromTeacherList)) {
      history.back();
      return;
    }
    updateQuery({ [options.detailKey]: undefined }, true);
    syncRoute();
  }
  function restoreFocus(event: Event) {
    event.preventDefault();
    if (alive) (trigger?.isConnected ? trigger : heading.value)?.focus({ preventScroll: true });
  }
  function syncRoute() {
    const params = query(), values = options.readFilters(params), cursors = readPages(params);
    const index = Math.max(1, Math.min(parseInt(params.get("page") || "1", 10) || 1, cursors.length));
    const path = cursors.slice(0, index);
    const id = params.get(options.detailKey) || undefined;
    if (refreshOnClose && !id) {
      loadedKey = "";
      items.value = [];
      refreshOnClose = false;
    }
    if (pageKey(values, path) !== loadedKey) void loadList(values, path, true);
    else { listVersion++; listBusy.value = false; filters.value = values; listError.value = ""; }
    if (id !== selectedId.value || (id && !detail.value)) void loadDetail(id);
  }
  watch(options.revision, syncRoute);
  onMounted(syncRoute);
  onBeforeUnmount(() => { alive = false; listVersion++; detailVersion++; });
  return { filters, items, detail, selectedId, listBusy, listError, detailError, heading, pageNumber, nextCursor,
    changePage, open, close, restoreFocus, loadDetail, apply: (values: F) => void loadList(values, [null]), retryList: () => retry() };
}
