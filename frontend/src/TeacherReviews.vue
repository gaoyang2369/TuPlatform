<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import TeacherRating from "./TeacherRating.vue";

interface Review {
  id: string; parent_name: string; grade: string | null; subject: string | null;
  sub_scores: Record<string, number>; tags: string[];
  content: string | null; reply_content: string | null; created_at: string;
}
interface Page {
  items: Review[]; published_count: number; avg_overall_score: number | null;
  sub_score_averages: Record<string, number>; top_tags: {tag: string; count: number}[];
  nextCursor: string | null;
}
const props = defineProps<{ teacherId: string }>();
const emit = defineEmits<{ expired: [] }>();
const page = ref<Page>(), busy = ref(false), error = ref("");
const date = new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "numeric", day: "numeric" });
let active = true;
onBeforeUnmount(() => { active = false; });
async function load() {
  if (busy.value) return;
  busy.value = true;
  error.value = "";
  const cursor = page.value?.nextCursor;
  try {
    const result = await api.request<Page>("/teachers/" + encodeURIComponent(props.teacherId) + "/reviews" +
      (cursor ? "?cursor=" + encodeURIComponent(cursor) : ""));
    if (active) page.value = { ...result, items: [...(page.value?.items ?? []), ...result.items] };
  } catch (e) {
    if (!active) return;
    if (e instanceof ApiFailure && (e.status === 401 || e.code === "SESSION_CHANGED")) emit("expired");
    error.value = e instanceof Error ? e.message : "评价暂时无法读取";
  } finally { if (active) busy.value = false; }
}
onMounted(load);
</script>

<template>
  <section aria-label="真实家长评价" :aria-busy="busy" class="mt-7 space-y-4 [overflow-wrap:anywhere]">
    <header class="flex flex-wrap items-center justify-between gap-2">
      <h3 class="text-xl font-bold">真实家长评价</h3>
      <TeacherRating v-if="page" :count="page.published_count" :average="page.avg_overall_score"/>
    </header>
    <div v-if="!page && busy" role="status" class="space-y-3"><span class="sr-only">正在读取评价</span><Skeleton class="h-5 w-2/3"/><Skeleton class="h-16 w-full"/></div>
    <p v-if="page?.published_count === 0" class="rounded-xl bg-slate-50 p-4 text-sm leading-6 text-slate-600">新入驻本校老师，完成首次试课或合作后将展示真实家长评价</p>
    <template v-if="page && page.published_count > 0">
      <dl aria-label="维度均分" class="grid grid-cols-2 gap-x-5 gap-y-3 rounded-xl bg-slate-50 p-4">
        <div v-for="(score, label) in page.sub_score_averages" :key="label" class="flex min-w-0 flex-wrap items-baseline justify-between gap-x-2 text-sm">
          <dt>{{ label }}</dt><dd class="contents"><span class="font-semibold tabular-nums">{{ score.toFixed(1) }} / 5</span>
            <span aria-hidden="true" class="mt-1.5 h-1 w-full overflow-hidden rounded-full bg-slate-200"><span class="block h-full rounded-full bg-blue-500" :style="{ width: score * 20 + '%' }"/></span>
          </dd>
        </div>
      </dl>
      <ul v-if="page.top_tags.length" aria-label="高频标签" class="flex flex-wrap gap-2">
        <li v-for="item in page.top_tags" :key="item.tag" class="rounded-full bg-blue-50 px-2.5 py-1 text-xs text-blue-800">{{ item.tag }} · {{ item.count }}</li>
      </ul>
      <article v-for="item in page.items" :key="item.id" class="space-y-3 rounded-2xl border border-border p-4">
        <header class="flex flex-wrap items-baseline justify-between gap-2">
          <strong class="text-[15px]">{{ item.parent_name }}</strong>
          <time class="text-xs text-muted-foreground" :datetime="item.created_at">{{ date.format(new Date(item.created_at)) }}</time>
        </header>
        <p v-if="item.grade || item.subject" class="text-sm text-secondary-foreground">{{ [item.grade, item.subject].filter(Boolean).join(" · ") }}</p>
        <ul class="flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-600">
          <li v-for="(score, label) in item.sub_scores" :key="label">{{ label }} {{ score }}/5</li>
        </ul>
        <div v-if="item.tags.length" class="flex flex-wrap gap-1.5"><span v-for="tag in item.tags" :key="tag" class="rounded-full bg-slate-100 px-2 py-1 text-xs text-slate-700">{{ tag }}</span></div>
        <p v-if="item.content" class="whitespace-pre-line text-[15px] leading-6">{{ item.content }}</p>
        <div v-if="item.reply_content" class="ml-3 space-y-1 rounded-xl bg-slate-50 p-3"><strong class="text-sm">老师回复</strong><p class="whitespace-pre-line text-sm leading-6">{{ item.reply_content }}</p></div>
      </article>
    </template>
    <div v-if="error" role="alert" class="flex flex-wrap items-center gap-3 rounded-xl bg-red-50 p-3 text-sm text-red-800">{{ error }} <Button variant="outline" @click="load">重新读取评价</Button></div>
    <Button v-else-if="page?.nextCursor" variant="outline" :disabled="busy" class="w-full" @click="load">{{ busy ? "正在读取…" : "查看更多评价" }}</Button>
  </section>
</template>
