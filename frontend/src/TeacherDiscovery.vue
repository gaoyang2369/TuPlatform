<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { ArrowLeft, ArrowRight, BadgeCheck, CircleDollarSign, MapPin, MessagesSquare, Monitor, RotateCcw, Search } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import DiscoverySheet from "./DiscoverySheet.vue";
import DiscoveryCategories from "./DiscoveryCategories.vue";
import DiscoveryFilter from "./DiscoveryFilter.vue";
import DiscoveryStudyFilter from "./DiscoveryStudyFilter.vue";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure, type Me, type ProfileOptions } from "./account-api";
import type { PublicTeacher } from "./match-types";
import TeacherSummary from "./TeacherSummary.vue";
import MatchComposer from "./MatchComposer.vue";
import { areaNames, modeNames } from "./request-display";
import { useDiscovery } from "./use-discovery";
import { filterQuery, priceBands, readTeacherFilters, teacherPage } from "./teacher-search";

const props = defineProps<{ me: Me; routeRevision: number }>();
const emit = defineEmits<{ matched: [id: string]; expired: []; requests: []; createRequest: []; profile: [] }>();
const options = ref<ProfileOptions>({ categoryCodes: [], serviceModes: [], areaCodes: [] });
const optionsError = ref("");
let alive = true;
onBeforeUnmount(() => { alive = false; });
const { filters, items, detail, selectedId, listBusy, listError, detailError, heading, pageNumber, nextCursor,
  apply, changePage, open, close, restoreFocus, loadDetail, retryList } = useDiscovery({
  view: "teachers", detailKey: "teacher", revision: () => props.routeRevision,
  fetchPage: teacherPage, readFilters: readTeacherFilters, filterQuery, failure,
  fetchDetail: id => api.request<PublicTeacher>("/teachers/" + encodeURIComponent(id)),
});
const backLabel = computed(() => selectedId.value && history.state?.fromPendingRequest ? "返回我的需求" : "返回老师列表");
const groups = computed(() => [
  { key: "area" as const, label: "坪山街道", icon: MapPin, options: options.value.areaCodes.map(code => ({ code, label: (areaNames[code] || code) + "街道" })) },
  { key: "mode" as const, label: "授课方式", icon: Monitor, options: options.value.serviceModes.map(code => ({ code, label: modeNames[code] || code })) },
  { key: "price" as const, label: "时薪区间", icon: CircleDollarSign, options: priceBands },
]);
const toggles = [
  { key: "trialOnly", label: "支持试课", icon: MessagesSquare },
  { key: "verifiedOnly", label: "仅看已核验", icon: BadgeCheck },
] as const;
const hasFilters = computed(() => Object.values(filterQuery(filters.value)).some(Boolean));

function failure(e: unknown) {
  if (e instanceof ApiFailure && (e.status === 401 || e.code === "SESSION_CHANGED")) emit("expired");
  return e instanceof Error ? e.message : "读取失败，请重试。";
}
async function loadOptions() {
  optionsError.value = "";
  try { const result = await api.request<ProfileOptions>("/profile-options"); if (alive) options.value = result; }
  catch (e) { if (alive) optionsError.value = failure(e); }
}

function resetFilters() { apply(readTeacherFilters(new URLSearchParams())); }
function toggle(key: "area" | "mode" | "price", code: string) {
  const existing = filters.value[key];
  apply({ ...filters.value, [key]: existing.includes(code) ? existing.filter(v => v !== code) : [...existing, code] });
}
onMounted(loadOptions);
</script>

<template>
  <section class="space-y-6 text-foreground sm:space-y-8">
    <header ref="heading" tabindex="-1" class="scroll-mt-24 space-y-3 outline-none">
      <p class="inline-flex items-center gap-1.5 rounded-full bg-orange-50 px-3 py-1 text-xs font-semibold text-orange-700"><MapPin class="size-3.5 text-neighborhood-orange"/>深圳 · 坪山</p>
      <h1 class="text-3xl font-bold tracking-tight sm:text-[40px]">发现老师</h1>
      <p class="text-[15px] leading-7 text-secondary-foreground">按年级、科目和授课安排查找老师，也可只看支持试课或本校学籍已核验的老师。</p>
    </header>
    <div class="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200/80 bg-card p-3 shadow-xs sm:p-5">
      <DiscoveryCategories :codes="options.categoryCodes" :active="filters.category" @select="apply({ ...filters, category: $event })"/>
      <div class="flex flex-wrap items-center gap-1.5 sm:gap-2">
        <DiscoveryFilter v-for="group in groups" :key="group.key" :label="group.label" :icon="group.icon" :options="group.options" :selected="filters[group.key]" @toggle="toggle(group.key, $event)"/>
        <DiscoveryStudyFilter teacher-grades :grade="filters.grade" :subject="filters.subject"
          @update:grade="apply({ ...filters, grade: $event })" @update:subject="apply({ ...filters, subject: $event })"/>
        <Button v-for="option in toggles" :key="option.key" variant="outline" :aria-pressed="filters[option.key]"
          class="h-10 gap-1.5 rounded-full has-[>svg]:px-2 aria-pressed:border-blue-300 aria-pressed:bg-blue-50 aria-pressed:text-primary sm:has-[>svg]:px-3"
          @click="apply({ ...filters, [option.key]: !filters[option.key] })"><component :is="option.icon"/>{{ option.label }}</Button>
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
      <div v-else-if="!items.length && !listError" class="grid justify-items-center gap-4 rounded-2xl border border-border bg-card px-4 py-10 text-center sm:p-10">
        <Search class="size-8 text-primary"/><h3 class="text-xl font-bold">当前条件下暂未找到完全匹配的老师</h3>
        <div class="flex w-full flex-col justify-center gap-2 sm:w-auto sm:flex-row">
          <Button class="h-auto min-h-11 whitespace-normal rounded-xl" @click="emit('createRequest')">发布我的需求，让本校老师主动联系你</Button>
          <Button variant="outline" class="h-11 rounded-xl" :disabled="!hasFilters" @click="resetFilters">清除筛选条件</Button>
        </div>
      </div>
      <TransitionGroup v-else tag="div" name="results" class="grid grid-cols-1 gap-6 md:grid-cols-2 lg:grid-cols-3">
        <article v-for="t in items" :key="t.id" class="teacher-card group min-w-0 rounded-2xl border border-border bg-card p-6 shadow-sm transition motion-safe:hover:-translate-y-0.5 hover:shadow-lg motion-reduce:transition-none">
          <TeacherSummary :teacher="t" compact public-profile :open-disabled="listBusy" @open="open(t.id, $event)"/>
        </article>
      </TransitionGroup>
      <nav v-if="items.length || pageNumber>1" aria-label="老师分页" class="flex items-center justify-between gap-3 py-3">
        <Button variant="outline" :disabled="listBusy || pageNumber===1" @click="changePage(-1)"><ArrowLeft/>上一页</Button>
        <span aria-live="polite" class="text-sm text-secondary-foreground">第 {{ pageNumber }} 页 · 每页10位</span>
        <Button variant="outline" :disabled="listBusy || !nextCursor" @click="changePage(1)">下一页<ArrowRight/></Button>
      </nav>
    </section>
    <DiscoverySheet :open="!!selectedId" title="老师资料" description="查看教学安排，合适时可直接邀请老师。" :back-label="backLabel" @close="close" @restore-focus="restoreFocus">
      <MatchComposer v-if="detail" drawer :key="detail.id" :me="me" :teacher-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @requests="emit('requests')" @profile="emit('profile')">
        <TeacherSummary :teacher="detail" public-profile/>
      </MatchComposer>
      <div v-else-if="detailError" role="alert" class="mx-5 space-y-3 rounded-xl bg-red-50 p-4 text-red-800"><p>{{ detailError }}</p><Button variant="outline" @click="loadDetail(selectedId)">重新读取</Button></div>
      <div v-else role="status" class="space-y-4 px-5"><span class="sr-only">正在读取老师资料…</span><Skeleton class="size-12"/><Skeleton class="h-5 w-1/2"/><Skeleton class="h-24 w-full"/></div>
    </DiscoverySheet>
  </section>
</template>

<style scoped>
@reference "./styles/theme.css";
@media (prefers-reduced-motion: no-preference) {
  .results-enter-active, .results-move { transition: opacity .18s ease, transform .18s ease; }
  .results-enter-from { opacity: 0; transform: translateY(5px); }
}
</style>
