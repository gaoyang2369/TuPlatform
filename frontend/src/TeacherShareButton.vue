<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { Download, Link, Share2 } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { api } from "./account-api";
import type { PublicTeacher } from "./match-types";
import { teacherShareUrl } from "./navigation";

const props = defineProps<{ teacherId:string; disabled?:boolean }>();
const open=ref(false), png=ref(""), name=ref(""), error=ref(""), notice=ref(""), retry=ref(0);
const manualCopy=ref(false), linkField=ref<HTMLInputElement>();
const url=computed(()=>teacherShareUrl(props.teacherId));
watch([open,()=>props.teacherId,()=>props.disabled,retry],async ([show,id,disabled],_,cleanup)=>{
  let current=true; cleanup(()=>{current=false;});
  png.value=error.value=notice.value=""; manualCopy.value=false;
  if(disabled) open.value=false;
  if(!show || disabled) return;
  try {
    const [teacher,{renderTeacherPoster}]=await Promise.all([
      api.request<PublicTeacher>("/public/teachers/"+encodeURIComponent(id)),
      import("./teacher-poster"),
    ]);
    if(!current) return;
    const result=await renderTeacherPoster(teacher,url.value);
    if(current) {name.value=teacher.displayName; png.value=result;}
  } catch(e) {if(current) error.value=e instanceof Error ? e.message : "名片生成失败，请重试。";}
});
async function copyLink() {
  try {await navigator.clipboard.writeText(url.value);notice.value="老师主页链接已复制。";}
  catch {manualCopy.value=true;await nextTick();linkField.value?.select();}
}
</script>

<template>
  <Dialog v-model:open="open">
    <DialogTrigger as-child><Button type="button" variant="outline" aria-label="生成分享名片" :disabled="disabled" class="min-h-10 shrink-0 gap-1.5 rounded-xl px-3 text-primary">
      <Share2 class="size-4"/><span><span class="max-md:hidden">生成</span>分享名片</span>
    </Button></DialogTrigger>
    <DialogContent class="flex max-h-[min(94dvh,calc(100dvh-2rem-env(safe-area-inset-bottom)-env(safe-area-inset-top)))] flex-col overflow-y-auto gap-3 rounded-2xl p-4 sm:max-w-md sm:p-5">
      <DialogTitle class="pr-8 text-xl font-bold">老师分享名片</DialogTitle>
      <DialogDescription class="sr-only">预览微信分享名片，可保存为 PNG 或复制老师主页链接。</DialogDescription>
      <div v-if="png" class="min-h-0 overflow-y-auto rounded-xl bg-slate-100 p-2 text-center">
        <img :src="png" :alt="name+'的微信分享名片'" class="mx-auto h-auto w-full rounded-lg" draggable="false">
      </div>
      <div v-else-if="error" role="alert" class="rounded-xl bg-red-50 p-4 text-sm text-red-800">{{ error }} <button class="underline" @click="retry++">重新生成</button></div>
      <div v-else role="status" class="space-y-2"><Skeleton class="h-64 w-full rounded-xl"/><p class="text-sm">正在生成高清名片…</p></div>
      <p v-if="png" class="text-sm text-muted-foreground sm:pointer-fine:hidden">也可长按上方图片直接保存发送给微信好友/群聊</p>
      <div v-if="png" class="flex flex-wrap gap-2">
        <Button as-child class="min-h-11 flex-1 rounded-xl"><a :href="png" :download="name+'-邻课名片.png'"><Download/>保存名片图片（PNG）</a></Button>
        <Button variant="outline" class="min-h-11 rounded-xl" @click="copyLink"><Link/>复制链接</Button>
      </div>
      <input v-if="manualCopy" ref="linkField" aria-label="老师主页链接，长按复制" readonly :value="url" class="w-full rounded border p-2 text-sm" @focus="linkField?.select()">
      <p v-if="notice" role="status" class="text-sm text-primary">{{ notice }}</p>
    </DialogContent>
  </Dialog>
</template>
