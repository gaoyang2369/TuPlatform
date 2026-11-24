<script setup lang="ts">
import { ref, watch } from "vue";
import { BadgeCheck, ChevronRight } from "@lucide/vue";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import { moneyText } from "./discovery-display";
import UserAvatar from "./UserAvatar.vue";

interface TeacherCard {
  id: string; displayName: string; avatar_url?: string; is_school_verified: boolean;
  collegeMajor?: string; gradeLevel?: string; priceMin?: string; priceMax?: string; reasons: string[];
}
const props = defineProps<{requestId: string; disabled?: boolean}>();
const emit = defineEmits<{ open: [id:string]; expired: [] }>();
const items = ref<TeacherCard[]>([]), busy = ref(true), error = ref(""), retry = ref(0);
watch([() => props.requestId, retry], async ([id], _, cleanup) => {
  let current = true;
  cleanup(() => { current=false; });
  busy.value=true; error.value=""; items.value=[];
  try {
    const result = await api.request<{items:TeacherCard[]}>("/requests/"+encodeURIComponent(id)+"/recommended-teachers");
    if (current) items.value=result.items;
  } catch (e) {
    if (!current) return;
    if (e instanceof ApiFailure && (e.status===401 || e.code==="SESSION_CHANGED")) emit("expired");
    error.value=e instanceof Error ? e.message : "暂时无法读取推荐老师";
  } finally { if (current) busy.value=false; }
}, {immediate:true});
function rate(t: TeacherCard) {
  if (!t.priceMin || !t.priceMax) return "面议";
  const min=moneyText(t.priceMin), max=moneyText(t.priceMax);
  return "¥"+(min===max?min:min+"–"+max)+"/小时";
}
</script>

<template>
  <section aria-label="推荐老师" :aria-busy="busy" class="space-y-3 rounded-2xl bg-slate-50 p-4">
    <h3 class="text-[15px] font-semibold">审核预计很快完成，你可以先看看这几位匹配的本校老师</h3>
    <Skeleton v-if="busy" class="h-20 rounded-xl" aria-label="正在读取推荐老师"/>
    <p v-else-if="error" role="alert" class="text-sm text-red-700">{{ error }} <button class="underline" @click="retry++">重试</button></p>
    <p v-else-if="!items.length" class="text-sm text-muted-foreground">暂时没有匹配的本校老师，需求审核通过后，老师也可以主动投递。</p>
    <button v-for="t in items" :key="t.id" type="button" :disabled="disabled" :aria-label="'查看老师资料：'+t.displayName" @click="emit('open',t.id)"
      class="grid w-full grid-cols-[auto_minmax(0,1fr)_auto] items-start gap-3 rounded-xl border border-border bg-white p-3 text-left hover:border-primary focus-visible:outline-primary">
      <UserAvatar :url="t.avatar_url" :name="t.displayName" class="size-10 rounded-xl"/>
      <span class="grid min-w-0 gap-1">
        <strong class="break-words text-xl">{{ t.displayName }}</strong>
        <span v-if="t.is_school_verified" class="inline-flex items-center gap-1 text-xs font-semibold text-primary"><BadgeCheck class="size-4"/>本校学籍已核验</span>
        <span v-else class="text-xs text-muted-foreground">学籍未核验</span>
        <span class="text-sm text-secondary-foreground">{{ [t.collegeMajor,t.gradeLevel].filter(Boolean).join(' · ') }}</span>
        <strong class="text-xl">{{ rate(t) }}</strong>
        <span class="flex flex-wrap gap-1"><span v-for="reason in t.reasons" :key="reason" class="rounded-full bg-blue-50 px-2 py-0.5 text-xs text-blue-700">{{ reason }}</span></span>
      </span>
      <ChevronRight aria-hidden="true" class="mt-1 size-4 text-primary"/>
    </button>
  </section>
</template>
