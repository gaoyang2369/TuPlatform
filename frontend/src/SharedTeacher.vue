<script setup lang="ts">
import { track } from "./analytics";
import { ref, watch, onMounted } from "vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import DiscoverySheet from "./DiscoverySheet.vue";
import DiscoveryActionBar from "./DiscoveryActionBar.vue";
import TeacherSummary from "./TeacherSummary.vue";
import TeacherShareButton from "./TeacherShareButton.vue";
import { api } from "./account-api";
import type { PublicTeacher } from "./match-types";

const props=defineProps<{id:string; guest:boolean}>();
const emit=defineEmits<{close:[];login:[]}>();
onMounted(() => track("page_view_teachers"));
const teacher=ref<PublicTeacher>(), error=ref(""), retry=ref(0);
watch([()=>props.id,retry],async ([id],_,cleanup)=>{
  let current=true;cleanup(()=>{current=false;});
  teacher.value=undefined;error.value="";
  try {
    const result=await api.request<PublicTeacher>("/public/teachers/"+encodeURIComponent(id));
    if(current) { teacher.value=result; track("open_teacher_detail"); }
  } catch(e) {if(current) error.value=e instanceof Error ? e.message : "暂时无法读取老师资料。";}
},{immediate:true});
</script>
<template>
  <section><h1 class="text-3xl font-bold">发现老师</h1>
    <DiscoverySheet open title="老师资料" description="查看老师的公开教学资料与可约时段" back-label="返回首页" @close="emit('close')">
      <template #actions><TeacherShareButton v-if="teacher" :teacher-id="teacher.id"/></template>
      <div v-if="teacher" class="min-h-0 flex-1 overflow-y-auto px-5 pb-6 sm:px-8"><TeacherSummary :teacher="teacher" public-profile/></div>
      <div v-else-if="error" role="alert" class="mx-5 space-y-3 rounded-xl bg-slate-50 p-4"><p>{{ error }}</p><Button variant="outline" @click="retry++">重新读取</Button></div>
      <div v-else role="status" class="space-y-4 px-5"><span class="sr-only">正在读取老师资料…</span><Skeleton class="size-16"/><Skeleton class="h-28"/></div>
      <DiscoveryActionBar v-if="guest && teacher"><Button class="min-h-11 w-full" @click="emit('login')">登录后邀请老师</Button></DiscoveryActionBar>
    </DiscoverySheet>
  </section>
</template>
