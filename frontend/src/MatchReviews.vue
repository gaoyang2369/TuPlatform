<script setup lang="ts">
import { computed, ref } from "vue";
import { Star } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import type { Match } from "./match-types";

interface Review {
  id: string; roundId: string; reviewerRole: "parent" | "teacher"; mine: boolean;
  overallScore: number; subScores: Record<string, number>; tags: string[]; content: string;
  published: boolean; replyContent: string | null; createdAt: string;
}
interface ReviewPage {
  roundId: string; canSubmit: boolean; form: { dimensions: string[]; tags: string[] }; reviews: Review[];
}
const props = defineProps<{ match: Match }>();
const emit = defineEmits<{ expired: [] }>();
const open = ref(false), busy = ref(false), error = ref(""), success = ref("");
const data = ref<ReviewPage | null>(null);
const scores = ref<Record<string, number>>({}), tags = ref<string[]>([]), comment = ref("");
const replies = ref<Record<string,string>>({});
const current = computed(() => data.value?.reviews.filter(r => r.roundId === props.match.roundId));
const label = computed(() => {
  if (current.value?.some(r => r.published) || props.match.reviewsPublished) return "查看互评";
  if (current.value?.some(r => r.mine) || props.match.ownReviewSubmitted) return "已提交评价，等待双盲公开";
  if (props.match.state === "completed" && (data.value?.canSubmit ?? props.match.reviewEligible)) {
    const days = Math.ceil((Date.parse(props.match.reviewDeadlineAt!) - Date.now()) / 86400000);
    if (days > 0) return `去评价（剩余 ${days} 天）`;
  }
  return props.match.hasPastReviews ? "查看往期互评" : "";
});
const ready = computed(() => data.value?.form.dimensions.every(d => scores.value[d]));
const reviewPath = computed(() => `/matches/${props.match.id}/reviews`);
async function load() {
  const latest = await api.request<ReviewPage>(reviewPath.value);
  if (latest.roundId !== props.match.roundId) {
    data.value = null;
    throw new Error("这段联系已进入新轮次，请关闭弹窗并刷新撮合进度。");
  }
  data.value = latest;
}
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true; error.value = "";
  try { await action(); }
  catch (e) {
    error.value = e instanceof Error ? e.message : "读取失败，请重试";
    if (e instanceof ApiFailure && (e.status === 401 || e.code === "SESSION_CHANGED")) emit("expired");
  } finally { busy.value = false; }
}
async function show(value: boolean) {
  if (busy.value) return;
  open.value = value;
  if (value) { success.value = ""; await run(load); }
}
function toggleTag(tag: string) {
  tags.value = tags.value.includes(tag) ? tags.value.filter(t => t !== tag) : [...tags.value, tag];
}
// A transport retry of unchanged input keeps the same receipt.
let lastPayload = "", lastKey = "";
async function save(path: string, body: unknown, message: string) {
  await run(async () => {
    const payload = JSON.stringify([path, body]);
    if (payload !== lastPayload) { lastKey = crypto.randomUUID(); lastPayload = payload; }
    success.value = "";
    await api.request(path, "POST", body, lastKey);
    success.value = message;
    await load();
  });
}
function submit() {
  if (ready.value && data.value?.canSubmit) return save(reviewPath.value, {
    roundId: data.value.roundId, subScores: scores.value, tags: tags.value, content: comment.value,
  }, "评价已提交");
}
function reply(id: string) {
  return save(`/reviews/${id}/reply`, { content: replies.value[id] }, "回复已发布");
}
</script>

<template>
  <Dialog v-if="label || open" :open="open" @update:open="show">
    <DialogTrigger as-child><Button :variant="label.startsWith('去评价') ? 'default' : 'outline'">{{ label }}</Button></DialogTrigger>
    <DialogContent class="max-h-[90dvh] grid-cols-[minmax(0,1fr)] overflow-y-auto overscroll-contain [overflow-wrap:anywhere] rounded-2xl p-5 sm:max-w-xl sm:p-6">
      <DialogHeader>
        <DialogTitle class="pr-6 text-xl font-bold">合作互评</DialogTitle>
        <DialogDescription class="text-left text-sm leading-6">采用 14 天双盲互评机制，双方均提交或到期后同时公开，请放心给出真实反馈。</DialogDescription>
        <p class="text-sm text-muted-foreground">公开后，家长评价和老师回复会显示在老师资料里，家长称呼将脱敏。老师给家长的标签会计入合作信用。请勿填写联系方式或住址。</p>
      </DialogHeader>
      <p v-if="success" role="status" class="rounded-xl bg-blue-50 p-3 text-sm text-blue-800">{{ success }}</p>
      <div v-if="error" role="alert" class="space-y-2 rounded-xl bg-red-50 p-3 text-sm text-red-800">
        <p>{{ error }}</p><Button variant="outline" :disabled="busy" @click="run(load)">读取最新评价</Button>
      </div>
      <Skeleton v-if="busy && !data" class="h-36 rounded-xl" aria-label="正在读取评价"/>
      <template v-if="data">
        <form v-if="data.canSubmit" class="grid gap-4" @submit.prevent="submit">
          <h3 class="font-bold">{{ match.myRole === "REQUESTER" ? "评价老师" : "评价家长" }}</h3>
          <fieldset v-for="dimension in data.form.dimensions" :key="dimension" :disabled="busy" class="min-w-0">
            <legend class="text-[15px] font-medium">{{ dimension }}</legend>
            <div class="flex gap-1">
              <label v-for="n in 5" :key="n" class="cursor-pointer">
                <input v-model="scores[dimension]" type="radio" :name="dimension" :value="n" :aria-label="`${n} 星`" class="peer sr-only"/>
                <span class="flex size-10 items-center justify-center rounded-lg peer-focus-visible:ring-2 peer-focus-visible:ring-primary">
                  <Star aria-hidden="true" class="size-6" :class="n <= (scores[dimension] || 0) ? 'fill-blue-600 text-blue-600' : 'text-slate-400'"/>
                </span>
              </label>
            </div>
          </fieldset>
          <fieldset :disabled="busy">
            <legend class="mb-2 text-sm font-medium">快捷标签（最多选 4 个）</legend>
            <div class="flex flex-wrap gap-2">
              <Button v-for="tag in data.form.tags" :key="tag" size="sm" variant="outline" class="rounded-full aria-pressed:bg-blue-50 aria-pressed:text-primary"
                :aria-pressed="tags.includes(tag)" :disabled="tags.length >= 4 && !tags.includes(tag)" @click="toggleTag(tag)">{{ tag }}</Button>
            </div>
          </fieldset>
          <label class="grid gap-2 text-sm font-medium">文字评语（选填，最多 300 字）
            <textarea v-model="comment" rows="3" class="rounded-xl border p-3 text-[15px]" :disabled="busy"/>
            <span class="text-right text-xs text-muted-foreground" :class="{ 'text-red-700': Array.from(comment).length > 300 }">{{ Array.from(comment).length }} / 300</span>
          </label>
          <Button type="submit" :disabled="busy || !ready || Array.from(comment).length > 300">提交评价</Button>
        </form>
        <p v-if="!data.canSubmit && !current?.length && match.state === 'completed'" class="rounded-xl bg-slate-50 p-3 text-sm">本轮评价窗口已结束，暂无评价。</p>
        <article v-for="r in data.reviews" :key="r.id" class="grid gap-3 rounded-xl bg-slate-50 p-4 text-[15px]">
          <div class="flex flex-wrap items-center justify-between gap-2">
            <h3 class="font-bold">{{ r.roundId !== match.roundId ? '往期 · ' : '' }}{{ r.mine ? '我给对方的评价' : '对方给我的评价' }}</h3>
            <span class="font-bold text-primary">综合 {{ r.overallScore }} 星</span>
          </div>
          <p class="text-sm text-muted-foreground">{{ new Date(r.createdAt).toLocaleDateString('zh-CN') }} · {{ r.published ? '已公开' : '待对方提交或到期后公开' }}</p>
          <ul class="grid grid-cols-1 gap-1 text-sm sm:grid-cols-2">
            <li v-for="(score, dimension) in r.subScores" :key="dimension">{{ dimension }} · {{ score }} 星</li>
          </ul>
          <div v-if="r.tags.length" class="flex flex-wrap gap-2"><span v-for="tag in r.tags" :key="tag" class="rounded-full bg-white px-2.5 py-1 text-xs">{{ tag }}</span></div>
          <p v-if="r.content" class="whitespace-pre-wrap break-words">{{ r.content }}</p>
          <p v-if="r.replyContent" class="whitespace-pre-wrap break-words rounded-lg bg-white p-3"><strong>被评价方回复</strong><br>{{ r.replyContent }}</p>
          <form v-else-if="!r.mine && r.published" class="grid gap-2" @submit.prevent="reply(r.id)">
            <label class="grid gap-2 text-sm">礼貌回复（仅限一次，最多 200 字）
              <textarea v-model="replies[r.id]" rows="2" class="rounded-xl border bg-white p-3 text-[15px]" :disabled="busy"/>
              <span class="text-right text-xs text-muted-foreground" :class="{ 'text-red-700': Array.from(replies[r.id] || '').length > 200 }">{{ Array.from(replies[r.id] || '').length }} / 200</span>
            </label>
            <Button type="submit" variant="outline" :disabled="busy || !replies[r.id]?.trim() || Array.from(replies[r.id] || '').length > 200">发布回复</Button>
          </form>
        </article>
      </template>
    </DialogContent>
  </Dialog>
</template>
