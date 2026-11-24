<script setup lang="ts">
import { ref, watch } from "vue";
import { api } from "./account-api";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";

type Step = {name:string; visitors:number; advanced:number};
const labels:Record<string,string> = {
  page_view_teachers:"浏览老师", open_teacher_detail:"打开资料", send_match_intent:"发起意向",
  match_confirmed:"达成互选", view_contact:"查看联系方式"
};
const days=ref(7), retry=ref(0), steps=ref<Step[]>([]), error=ref("");
watch([days,retry], async ([period],_,cleanup)=>{
  let active=true;
  cleanup(()=>{ active=false; });
  steps.value=[]; error.value="";
  try {
    const result=await api.request<Step[]>("/admin/funnel?days="+period);
    if(active) steps.value=result;
  } catch(e) { if(active) error.value=e instanceof Error?e.message:"暂时无法读取漏斗"; }
},{immediate:true});
function rate(index:number) {
  const previous=steps.value[index-1]?.visitors;
  return previous ? (100*steps.value[index].advanced/previous).toFixed(1)+"%" : "—";
}
</script>
<template>
  <section aria-label="转化漏斗" class="space-y-4 rounded-2xl border border-border bg-white p-4 sm:p-6">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <h2 class="text-xl font-bold">转化漏斗</h2>
      <div class="flex gap-1"><Button v-for="n in [7,30]" :key="n" variant="ghost" :aria-pressed="days===n" class="aria-pressed:bg-blue-50 aria-pressed:text-primary" @click="days=n">近 {{ n }} 天</Button></div>
    </header>
    <p v-if="error" role="alert" class="text-sm text-red-800">{{ error }} <Button variant="ghost" @click="retry++">重试</Button></p>
    <Skeleton v-else-if="!steps.length" class="h-20 w-full" aria-label="正在读取漏斗"/>
    <template v-else>
      <ol class="grid grid-cols-2 gap-3 sm:grid-cols-5">
        <li v-for="(step,i) in steps" :key="step.name" class="rounded-xl bg-slate-50 p-3">
          <p class="text-sm">{{ labels[step.name] }}</p><strong class="text-2xl">{{ step.visitors }}</strong>
          <p v-if="i" class="text-xs text-muted-foreground">上一步转化 {{ rate(i) }}</p>
        </li>
      </ol>
      <p class="text-sm text-muted-foreground">{{ steps.every(s=>!s.visitors) ? '此期间暂无记录。' : '' }}按账号或匿名会话去重。互选归于发起人，转化率为上一步访客在所选期间继续到本步的比例。</p>
    </template>
  </section>
</template>
