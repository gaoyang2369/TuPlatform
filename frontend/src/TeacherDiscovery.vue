<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { ArrowLeft, ArrowRight, BookOpen, ChevronDown, CircleDollarSign, Footprints, Layers, MapPin, Monitor, Palette, RotateCcw, Search, Shapes } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure, type Me, type ProfileOptions } from "./account-api";
import type { PublicTeacher } from "./match-types";
import TeacherSummary from "./TeacherSummary.vue";
import MatchComposer from "./MatchComposer.vue";
import { categoryNames, areaNames, modeNames } from "./request-display";
import { updateQuery, readPages, restoreScroll } from "./navigation";
import { filterQuery, priceBands, readTeacherFilters, teacherPage, type TeacherFilters } from "./teacher-search";

const props = defineProps<{ me: Me; initialId?: string; routeRevision: number }>();
const emit = defineEmits<{ matched: [id: string]; expired: []; requests: []; profile: [] }>();
const filters = ref(readTeacherFilters(new URLSearchParams(location.search)));
const options = ref<ProfileOptions>({ categoryCodes: [], serviceModes: [], areaCodes: [] });
const items = ref<PublicTeacher[]>([]);
const pageNumber = ref(1), pageCursors = ref<(string | null)[]>([null]), nextCursor = ref<string | null>(null);
const listBusy = ref(true), listError = ref(""), optionsError = ref("");
const selectedId = ref(props.initialId), detail = ref<PublicTeacher | null>(null), detailError = ref("");
const heading = ref<HTMLElement>();
let trigger: HTMLElement | null = null;
let listVersion = 0, detailVersion = 0, alive = true, loadedKey = "";
let displayedFilters = filters.value;
let retryList = () => {};
onBeforeUnmount(() => { alive = false; listVersion++; detailVersion++; });

const categoryIcons = { DRAWING: Palette, DANCE: Footprints, EXTRACURRICULAR_STUDY: BookOpen, OTHER_SKILL: Shapes };
const categories = computed(() => ["", ...options.value.categoryCodes].map(code => ({
  code, label: categoryNames[code] || code || "全部类别", icon: categoryIcons[code as keyof typeof categoryIcons] || Layers,
})));
const groups = computed(() => [
  { key: "area" as const, label: "坪山街道", icon: MapPin, options: options.value.areaCodes.map(code => ({ code, label: (areaNames[code] || code) + "街道" })) },
  { key: "mode" as const, label: "授课方式", icon: Monitor, options: options.value.serviceModes.map(code => ({ code, label: modeNames[code] || code })) },
  { key: "price" as const, label: "时薪区间", icon: CircleDollarSign, options: priceBands },
]);
const hasFilters = computed(() => !!(filters.value.category || filters.value.area.length || filters.value.mode.length || filters.value.price.length));

function failure(e: unknown) {
  if (e instanceof ApiFailure && (e.status === 401 || e.code === "SESSION_CHANGED")) emit("expired");
  return e instanceof Error ? e.message : "读取失败，请重试。";
}
async function loadOptions() {
  optionsError.value = "";
  try { const result = await api.request<ProfileOptions>("/profile-options"); if (alive) options.value = result; }
  catch (e) { if (alive) optionsError.value = failure(e); }
}

function pageKey(values: TeacherFilters, cursors: (string | null)[], index: number) {
  return JSON.stringify([filterQuery(values), cursors, index]);
}
async function loadList(values: TeacherFilters, cursors: (string | null)[], index: number, fromHistory = false) {
  const version = ++listVersion;
  const current = () => version === listVersion;
  filters.value = values;
  listBusy.value = true;
  listError.value = "";
  try {
    const result = await teacherPage(values, cursors[index - 1], current);
    if (!current()) return;
    items.value = result.items;
    nextCursor.value = result.nextCursor;
    pageNumber.value = index;
    pageCursors.value = cursors;
    displayedFilters = values;
    loadedKey = pageKey(values, cursors, index);
    updateQuery({ view: "teachers", ...filterQuery(values), page: index > 1 ? String(index) : undefined,
      pages: index > 1 ? JSON.stringify(cursors) : undefined }, fromHistory);
    if (fromHistory) { await nextTick(); restoreScroll(); }
    return true;
  } catch (e) {
    if (!current()) return;
    listError.value = failure(e);
    if (loadedKey) filters.value = displayedFilters;
    retryList = () => void loadList(values, cursors, index, fromHistory);
  } finally { if (current()) listBusy.value = false; }
}
function apply(values: TeacherFilters) { void loadList(values, [null], 1); }
function resetFilters() { apply({ category: "", area: [], mode: [], price: [] }); }
function toggle(key: "area" | "mode" | "price", code: string) {
  const existing = filters.value[key];
  apply({ ...filters.value, [key]: existing.includes(code) ? existing.filter(v => v !== code) : [...existing, code] });
}
async function changePage(direction: number) {
  const index = pageNumber.value + direction;
  const cursors = pageCursors.value.slice(0, index);
  if (direction > 0 && nextCursor.value) cursors[index - 1] = nextCursor.value;
  if (await loadList(filters.value, cursors, index)) {
    heading.value?.focus({ preventScroll: true });
    heading.value?.scrollIntoView({ block: "start" });
  }
}
async function loadDetail(id?: string) {
  const version = ++detailVersion;
  selectedId.value = id; detail.value = null; detailError.value = "";
  if (!id) return;
  try {
    const result = await api.request<PublicTeacher>("/teachers/" + encodeURIComponent(id));
    if (version === detailVersion) detail.value = result;
  } catch (e) { if (version === detailVersion) detailError.value = failure(e); }
}
function open(id: string, event: MouseEvent) {
  trigger = event.currentTarget as HTMLElement;
  updateQuery({ view: "teachers", teacher: id });
  history.replaceState({ ...history.state, fromTeacherList: true }, "");
  void loadDetail(id);
}
function close() {
  if (history.state?.fromTeacherList) { history.back(); return; }
  updateQuery({ teacher: undefined }, true);
  void loadDetail();
}
function restoreFocus(event: Event) {
  event.preventDefault();
  if (alive) (trigger?.isConnected ? trigger : heading.value)?.focus({ preventScroll: true });
}
function syncRoute() {
  const query = new URLSearchParams(location.search);
  const values = readTeacherFilters(query), cursors = readPages(query);
  const index = Math.max(1, Math.min(parseInt(query.get("page") || "1", 10) || 1, cursors.length));
  const path = cursors.slice(0, index);
  if (pageKey(values, path, index) !== loadedKey) void loadList(values, path, index, true);
  else { listVersion++; listBusy.value = false; filters.value = values; listError.value = ""; }
  const id = query.get("teacher") || undefined;
  if (id !== selectedId.value || (id && !detail.value)) void loadDetail(id);
}
watch(() => props.routeRevision, syncRoute);
onMounted(() => { void loadOptions(); syncRoute(); });
</script>

<template>
  <section data-ui class="space-y-6 text-foreground sm:space-y-8">
    <header ref="heading" tabindex="-1" class="scroll-mt-24 space-y-3 outline-none">
      <p class="inline-flex items-center gap-1.5 rounded-full bg-orange-50 px-3 py-1 text-xs font-semibold text-orange-700"><MapPin class="size-3.5 text-neighborhood-orange"/>深圳 · 坪山</p>
      <h1 class="text-3xl font-bold tracking-tight sm:text-[40px]">发现老师</h1>
      <p class="text-[15px] leading-7 text-secondary-foreground">按教学方向、街道和时薪筛选。以下资料均由老师本人填写并公开。</p>
    </header>
    <div class="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-border bg-card p-4 shadow-sm sm:p-5">
      <div role="group" aria-label="教学类别" class="flex gap-2 overflow-x-auto py-1">
        <button v-for="c in categories" :key="c.code" type="button" :aria-pressed="filters.category===c.code"
          @click="apply({ ...filters, category: c.code })"
          class="inline-flex min-h-10 shrink-0 items-center gap-2 rounded-full bg-slate-100 px-4 text-sm font-medium text-secondary-foreground transition-colors hover:bg-blue-50 aria-pressed:bg-primary aria-pressed:text-white">
          <component :is="c.icon" class="size-4"/>{{ c.label }}
        </button>
      </div>
      <div class="flex flex-wrap items-center gap-2">
        <Popover v-for="group in groups" :key="group.key">
          <PopoverTrigger as-child>
            <Button variant="outline" :aria-label="group.label" class="h-10 gap-1 rounded-xl border-border bg-white px-2 has-[>svg]:px-2 sm:gap-2 sm:has-[>svg]:px-3">
              <component :is="group.icon" class="text-primary"/>{{ group.label }}
              <span v-if="filters[group.key].length" class="text-xs text-primary">{{ filters[group.key].length }}</span><ChevronDown class="hidden size-3.5 text-muted-foreground sm:block"/>
            </Button>
          </PopoverTrigger>
          <PopoverContent align="start" class="box-border w-60 rounded-2xl p-3">
            <fieldset class="space-y-1"><legend class="mb-2 px-2 text-sm font-semibold">{{ group.label }}（可多选）</legend>
              <p v-if="!group.options.length" class="px-2 py-3 text-sm text-muted-foreground">暂无可选项</p>
              <label v-for="option in group.options" :key="option.code" class="flex min-h-10 cursor-pointer items-center gap-3 rounded-lg px-2 text-sm hover:bg-slate-50">
                <input type="checkbox" :checked="filters[group.key].includes(option.code)" class="size-4 accent-primary" @change="toggle(group.key,option.code)"/>{{ option.label }}
              </label>
            </fieldset>
          </PopoverContent>
        </Popover>
        <button v-if="hasFilters" class="inline-flex min-h-10 items-center gap-1.5 px-2 text-sm text-primary" @click="resetFilters"><RotateCcw class="size-4"/>重置筛选</button>
      </div>
      <p v-if="optionsError" role="alert" class="text-sm text-red-700">{{ optionsError }} <button class="underline" @click="loadOptions">重新读取筛选项</button></p>
    </div>
    <div v-if="listError" role="alert" class="flex flex-wrap items-center gap-3 rounded-2xl bg-red-50 p-4 text-sm text-red-800">{{ listError }} <Button variant="outline" @click="retryList">重新读取</Button></div>
    <section aria-label="老师列表" :aria-busy="listBusy" class="space-y-4">
      <div class="flex items-baseline justify-between gap-3"><h2 class="text-xl font-bold">老师列表</h2>
        <p role="status" class="text-sm text-muted-foreground">{{ listBusy ? '正在读取老师…' : listError ? '' : `本页 ${items.length} 位` }}</p>
      </div>
      <div v-if="listBusy && !items.length" aria-hidden="true" class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <div v-for="i in 3" :key="i" class="space-y-5 rounded-2xl border border-border bg-card p-6"><Skeleton class="size-12 rounded-xl"/><Skeleton class="h-5 w-2/3"/><Skeleton class="h-4 w-full"/><Skeleton class="h-11 w-full"/></div>
      </div>
      <div v-else-if="!items.length && !listError" class="grid justify-items-center gap-3 rounded-2xl border border-border bg-card p-10 text-center">
        <Search class="size-8 text-primary"/><p class="text-[15px]">暂无符合条件的公开老师，可以稍后再来查看。</p>
        <button v-if="hasFilters" class="text-sm font-medium text-primary" @click="resetFilters">查看全部老师</button>
      </div>
      <TransitionGroup v-else tag="div" name="results" class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <article v-for="t in items" :key="t.id" class="teacher-card min-w-0 rounded-2xl border border-border bg-card p-6 shadow-sm transition motion-safe:hover:-translate-y-0.5 hover:shadow-lg">
          <TeacherSummary :teacher="t" compact public-profile :open-disabled="listBusy" @open="open(t.id, $event)"/>
        </article>
      </TransitionGroup>
      <nav v-if="items.length || pageNumber>1" aria-label="老师分页" class="flex items-center justify-between gap-3 py-3">
        <Button variant="outline" :disabled="listBusy || pageNumber===1" @click="changePage(-1)"><ArrowLeft/>上一页</Button>
        <span aria-live="polite" class="text-sm text-secondary-foreground">第 {{ pageNumber }} 页 · 每页10位</span>
        <Button variant="outline" :disabled="listBusy || !nextCursor" @click="changePage(1)">下一页<ArrowRight/></Button>
      </nav>
    </section>
    <Sheet :open="!!selectedId" @update:open="value => { if (!value) close(); }">
      <SheetContent side="right" class="box-border h-dvh w-full gap-6 overflow-y-auto overscroll-contain bg-card p-5 text-foreground sm:w-[32rem] sm:max-w-[32rem] sm:p-8" @close-auto-focus="restoreFocus">
        <div class="space-y-2 pr-6"><SheetTitle class="text-xl">老师资料</SheetTitle><SheetDescription>查看教学安排，合适时可直接邀请老师。</SheetDescription></div>
        <template v-if="detail">
          <TeacherSummary :teacher="detail" public-profile/>
          <div class="invitation rounded-2xl bg-slate-50 p-4">
            <MatchComposer :key="detail.id" :me="me" :teacher-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @requests="emit('requests')" @profile="emit('profile')"/>
          </div>
        </template>
        <div v-else-if="detailError" role="alert" class="space-y-3 rounded-xl bg-red-50 p-4 text-red-800"><p>{{ detailError }}</p><Button variant="outline" @click="loadDetail(selectedId)">重新读取</Button></div>
        <div v-else role="status" class="space-y-4"><span class="sr-only">正在读取老师资料…</span><Skeleton class="size-12"/><Skeleton class="h-5 w-1/2"/><Skeleton class="h-24 w-full"/></div>
        <Button variant="outline" class="shrink-0" @click="close"><ArrowLeft/>返回老师列表</Button>
      </SheetContent>
    </Sheet>
  </section>
</template>

<style scoped>
@reference "./styles/theme.css";
@media (prefers-reduced-motion: no-preference) {
  .results-enter-active, .results-move { transition: opacity .18s ease, transform .18s ease; }
  .results-enter-from { opacity: 0; transform: translateY(5px); }
}
.invitation :deep(.composer) { @apply m-0 border-0 p-0 text-[15px] text-secondary-foreground; }
.invitation :deep(h3) { @apply text-lg font-bold text-foreground; }
.invitation :deep(.primary) { @apply min-h-11 rounded-xl bg-primary px-4 font-medium text-white hover:bg-primary-hover disabled:opacity-50; }
.invitation :deep(.secondary), .invitation :deep(.text-button) { @apply min-h-11 rounded-xl px-3 text-sm text-primary; }
.invitation :deep(.form-actions) { @apply mt-4 flex flex-wrap gap-2; }
.invitation :deep(label) { @apply block font-medium text-foreground; }
.invitation :deep(.optional) { @apply ml-2 text-xs text-muted-foreground; }
.invitation :deep(textarea) { @apply mt-2 block w-full rounded-xl border border-border bg-white px-3 py-2; }
.invitation :deep(.exchange-notice) { @apply bg-blue-50 text-sm text-secondary-foreground; }
.invitation :deep(.own-contact) { @apply text-foreground; }
.invitation :deep(.error) { @apply rounded-xl bg-red-50 p-3 text-red-800; }
</style>
