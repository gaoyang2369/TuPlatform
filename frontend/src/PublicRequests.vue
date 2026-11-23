<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ArrowLeft, ArrowRight, BookOpen, ChevronDown, CircleDollarSign, MapPin, Users, RefreshCw, RotateCcw, Search } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { Skeleton } from "@/components/ui/skeleton";
import { api, type Me } from "./account-api";
import type { PublicRequest } from "./match-types";
import DiscoveryCategories from "./DiscoveryCategories.vue";
import DiscoveryFilter from "./DiscoveryFilter.vue";
import DiscoverySheet from "./DiscoverySheet.vue";
import DiscoveryActionBar from "./DiscoveryActionBar.vue";
import MatchComposer from "./MatchComposer.vue";
import RequestSummary from "./RequestSummary.vue";
import { modeNames } from "./request-display";
import { priceBands } from "./discovery-page";
import { readRequestFilters, requestFilterQuery, requestPage } from "./request-search";
import { useDiscovery } from "./use-discovery";

const props = defineProps<{ routeRevision: number; me?: Me | null }>();
const emit = defineEmits<{ matched: [id: string]; expired: []; profile: []; login: [] }>();
const failure = (e: unknown) => e instanceof Error ? e.message : "暂时无法读取需求，请重试。";
const { filters, items, detail, selectedId, listBusy, listError, detailError, heading, pageNumber, nextCursor,
  apply, changePage, open, close, restoreFocus, loadDetail, retryList } = useDiscovery({
  view: "public", detailKey: "request", revision: () => props.routeRevision,
  readFilters: readRequestFilters, filterQuery: requestFilterQuery, fetchPage: requestPage, failure,
  fetchDetail: id => api.request<PublicRequest>("/public/requests/" + encodeURIComponent(id)),
});
interface Options { categories: string[]; districts?: {code: string; label: string}[]; serviceModes: string[] }
const options = ref<Options>({ categories: [], serviceModes: [] }), optionsError = ref("");
let alive = true;
async function loadOptions() {
  optionsError.value = "";
  try { const result = await api.request<Options>("/publishing-options"); if (alive) options.value = result; }
  catch (e) { if (alive) optionsError.value = failure(e); }
}
onMounted(loadOptions);
onBeforeUnmount(() => { alive = false; });
const districts = computed(() => options.value.districts ?? [{ code: "SZ_PINGSHAN", label: "坪山区" }]);
const groups = computed(() => [
  { key: "district" as const, label: "区域", icon: MapPin, options: districts.value },
  { key: "mode" as const, label: "授课方式", shortLabel: "方式", icon: Users, options: options.value.serviceModes.map(code => ({code, label: modeNames[code] || code})) },
  { key: "budget" as const, label: "预算区间", shortLabel: "预算", icon: CircleDollarSign, options: priceBands.filter(b => b.code !== "negotiable") },
]);
const hasFilters = computed(() => Object.values(requestFilterQuery(filters.value)).some(Boolean));
const studyEnabled = computed(() => !filters.value.category || filters.value.category === "EXTRACURRICULAR_STUDY");
const grades = ["一年级","二年级","三年级","四年级","五年级","六年级","初一","初二","初三","高一","高二","高三","成人学习","其他阶段"];
const subjects = ["语文","数学","英语","物理","化学","生物","历史","地理","道德与法治"];
function categoryChanged(category: string) {
  const study = !category || category === "EXTRACURRICULAR_STUDY";
  apply({ ...filters.value, category, grade: study ? filters.value.grade : "", subject: study ? filters.value.subject : "" });
}
function toggle(key: "district" | "mode" | "budget", code: string) {
  const values = filters.value[key];
  apply({ ...filters.value, [key]: values.includes(code) ? values.filter(v => v !== code) : [...values, code] });
}
function studyField(key: "grade" | "subject") {
  return computed({ get: () => filters.value[key], set: (value: string) => apply({ ...filters.value, [key]: value }) });
}
const grade = studyField("grade"), subject = studyField("subject");
function resetFilters() { apply(readRequestFilters(new URLSearchParams())); }
function districtLabel(item: PublicRequest) {
  return districts.value.find(d => d.code === (item.districtCode || "SZ_PINGSHAN"))?.label || "坪山区";
}
</script>

<template>
  <section class="space-y-5 text-foreground sm:space-y-8">
    <header ref="heading" tabindex="-1" class="scroll-mt-32 space-y-2 outline-none">
      <p class="inline-flex items-center gap-1.5 rounded-full bg-orange-50 px-3 py-1 text-xs font-semibold text-orange-700"><MapPin class="size-3.5"/>深圳 · 坪山</p>
      <h1 class="text-3xl font-bold tracking-tight sm:text-[40px]">浏览需求</h1>
      <p class="text-[15px] leading-6 text-secondary-foreground">仅展示审核通过且当前有效的需求。</p>
    </header>
    <div class="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-slate-200/80 bg-card p-3 shadow-xs sm:gap-3 sm:p-5">
      <DiscoveryCategories :codes="options.categories" :active="filters.category" @select="categoryChanged"/>
      <div class="flex flex-wrap items-center gap-1.5 sm:gap-2">
        <DiscoveryFilter v-for="group in groups" :key="group.key" :label="group.label" :short-label="group.shortLabel" :icon="group.icon" :options="group.options" :selected="filters[group.key]" @toggle="toggle(group.key, $event)"/>
        <Popover>
          <PopoverTrigger as-child><Button variant="outline" aria-label="年级与科目" :disabled="!studyEnabled" class="h-10 gap-1 rounded-xl bg-white has-[>svg]:px-2 sm:has-[>svg]:px-3"><BookOpen class="text-muted-foreground"/>年级科目<span v-if="grade || subject" class="text-xs text-primary">{{ Number(!!grade) + Number(!!subject) }}</span><ChevronDown class="hidden size-3.5 sm:block"/></Button></PopoverTrigger>
          <PopoverContent align="end" class="w-64 space-y-3 rounded-2xl">
            <label class="block text-sm font-medium">年级<input v-model="grade" list="filter-grades" maxlength="20" placeholder="不限年级" class="mt-1 block w-full rounded-lg border border-border p-2"/></label>
            <datalist id="filter-grades"><option v-for="value in grades" :key="value" :value="value"/></datalist>
            <label class="block text-sm font-medium">科目<input v-model="subject" list="filter-subjects" maxlength="40" placeholder="不限科目" class="mt-1 block w-full rounded-lg border border-border p-2"/></label>
            <datalist id="filter-subjects"><option v-for="value in subjects" :key="value" :value="value"/></datalist>
          </PopoverContent>
        </Popover>
        <button v-if="hasFilters" class="inline-flex min-h-10 items-center gap-1.5 px-2 text-sm text-primary" @click="resetFilters"><RotateCcw class="size-4"/>重置筛选</button>
      </div>
      <p v-if="optionsError" role="alert" class="text-sm text-red-700">{{ optionsError }} <button class="underline" @click="loadOptions">重新读取筛选项</button></p>
    </div>
    <div v-if="listError" role="alert" class="flex items-center gap-3 rounded-xl bg-red-50 p-4 text-red-800">{{ listError }} <Button variant="outline" @click="retryList">重新读取</Button></div>
    <section aria-label="需求列表" :aria-busy="listBusy" class="space-y-3 sm:space-y-4">
      <div class="flex items-baseline justify-between"><h2 class="text-xl font-bold">需求列表</h2><p role="status" class="text-sm text-muted-foreground">{{ listBusy ? '正在读取需求…' : listError ? '' : '本页 ' + items.length + ' 条' }}</p></div>
      <div v-if="listBusy && !items.length" aria-hidden="true" class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <div v-for="i in 3" :key="i" class="space-y-4 rounded-2xl border border-border bg-card p-6"><Skeleton class="h-6 w-2/3"/><Skeleton class="h-16 w-full"/><Skeleton class="h-11 w-full"/></div>
      </div>
      <div v-else-if="!items.length && !listError" class="grid justify-items-center gap-3 rounded-2xl border border-border bg-card p-8 text-center"><Search class="size-7 text-muted-foreground"/><p>暂无符合条件的公开需求，可以调整筛选条件。</p><button v-if="hasFilters" class="text-sm text-primary" @click="resetFilters">查看全部需求</button></div>
      <TransitionGroup v-else tag="div" name="requests" class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <RequestSummary v-for="item in items" :key="item.id" class="request-card group rounded-2xl border border-border bg-card p-5 shadow-sm transition hover:shadow-lg motion-safe:hover:-translate-y-0.5 motion-reduce:transition-none sm:p-6" compact :fields="item" :display-name="item.publisherDisplayName" :district-label="districtLabel(item)" :open-disabled="listBusy" @open="open(item.id, $event)"/>
      </TransitionGroup>
      <nav v-if="items.length || pageNumber > 1" aria-label="需求分页" class="pagination flex items-center justify-between gap-3 py-3">
        <Button variant="outline" :disabled="listBusy || pageNumber===1" @click="changePage(-1)"><ArrowLeft/>上一页</Button>
        <span aria-live="polite" class="text-sm text-secondary-foreground">第 {{ pageNumber }} 页 · 每页10条</span>
        <Button variant="outline" :disabled="listBusy || !nextCursor" @click="changePage(1)">下一页<ArrowRight/></Button>
      </nav>
    </section>
    <DiscoverySheet :open="!!selectedId" title="需求详情" description="查看学习目标、授课安排与预算。" back-label="返回需求列表" @close="close" @restore-focus="restoreFocus">
      <template v-if="detail">
        <MatchComposer v-if="me?.businessIdentity==='TUTOR'" drawer :key="detail.id" :me="me" :request-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @profile="emit('profile')">
          <RequestSummary :fields="detail" :display-name="detail.publisherDisplayName"/>
          <Button variant="ghost" class="mt-3 text-secondary-foreground" @click="loadDetail(detail.id)"><RefreshCw/>刷新需求</Button>
        </MatchComposer>
        <template v-else>
          <div class="min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-5 sm:px-8">
            <RequestSummary :fields="detail" :display-name="detail.publisherDisplayName"/>
            <Button variant="ghost" class="mt-3 text-secondary-foreground" @click="loadDetail(detail.id)"><RefreshCw/>刷新需求</Button>
          </div>
          <DiscoveryActionBar v-if="!me"><Button class="h-12 w-full rounded-xl text-base" @click="emit('login')">登录后以老师身份投递</Button></DiscoveryActionBar>
        </template>
      </template>
      <div v-else-if="detailError" role="alert" class="mx-5 space-y-3 rounded-xl bg-red-50 p-4 text-red-800"><p>{{ detailError }}</p><Button variant="outline" @click="loadDetail(selectedId)">重新读取</Button></div>
      <div v-else role="status" class="space-y-4 px-5"><span class="sr-only">正在读取需求详情…</span><Skeleton class="h-6 w-2/3"/><Skeleton class="h-20 w-full"/></div>
    </DiscoverySheet>
  </section>
</template>
<style scoped>
@media (prefers-reduced-motion: no-preference) {
  .requests-enter-active, .requests-move { transition: opacity .18s ease, transform .18s ease; }
  .requests-enter-from { opacity: 0; transform: translateY(5px); }
}
</style>
