<script setup lang="ts">
import {onMounted,ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me,ProfileOptions} from "./account-api.ts";
import type {PublicTeacher} from "./match-types.ts";
import TeacherSummary from "./TeacherSummary.vue";
import MatchComposer from "./MatchComposer.vue";
import {categoryNames,areaNames,modeNames} from "./request-display.ts";
defineProps<{me:Me}>();const emit=defineEmits<{matched:[id:string];expired:[];requests:[]}>();
const items=ref<PublicTeacher[]>([]),detail=ref<PublicTeacher|null>(null),busy=ref(false),error=ref("");
const cursor=ref<string|null>(null),category=ref(""),area=ref(""),mode=ref("");
const options=ref<ProfileOptions>({categoryCodes:[],serviceModes:[],areaCodes:[]});
function categories(codes:string[]){return codes.map(c=>categoryNames[c]||c).join('、');}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"读取失败，请重试";if(e instanceof ApiFailure&&(e.status===401||e.code==="SESSION_CHANGED"))emit("expired");}finally{busy.value=false;}}
async function list(append=false){const q=new URLSearchParams();if(category.value)q.set("categoryCode",category.value);if(area.value)q.set("areaCode",area.value);if(mode.value)q.set("serviceMode",mode.value);if(append&&cursor.value)q.set("cursor",cursor.value);const result=await api.request<{items:PublicTeacher[];nextCursor:string|null}>("/teachers?"+q);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;}
async function open(id:string){detail.value=null;await run(async()=>{detail.value=await api.request<PublicTeacher>("/teachers/"+encodeURIComponent(id));});}
onMounted(()=>void run(async()=>{options.value=await api.request("/profile-options");await list();}));
</script>
<template><section class="discovery"><div class="page-heading"><div><p class="eyebrow">NEARBY TUTORS</p><h1>发现老师</h1><p>仅展示老师本人主动公开的资料。</p></div><button v-if="detail" class="secondary" :disabled="busy" @click="detail=null;run(()=>list())">返回老师列表</button></div>
  <div v-if="error" role="alert" class="message error">{{ error }}<button class="text-button" :disabled="busy" @click="detail=null;run(()=>list())">重新读取老师列表</button></div>
  <article v-if="detail" class="form-card"><TeacherSummary :teacher="detail"/><MatchComposer :key="detail.id" :me="me" :teacher-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @requests="emit('requests')"/></article>
  <template v-else><form class="form-card filters" @submit.prevent="run(()=>list())"><label>类别<select v-model="category"><option value="">全部类别</option><option v-for="c in options.categoryCodes" :key="c" :value="c">{{ categoryNames[c]||c }}</option></select></label><label>街道<select v-model="area"><option value="">全部街道</option><option v-for="c in options.areaCodes" :key="c" :value="c">{{ areaNames[c]||c }}</option></select></label><label>方式<select v-model="mode"><option value="">全部方式</option><option v-for="c in options.serviceModes" :key="c" :value="c">{{ modeNames[c]||c }}</option></select></label><button class="secondary" :disabled="busy">筛选老师</button></form>
    <p v-if="!items.length&&!error" role="status" class="form-card">{{ busy?'正在读取老师…':'暂无符合条件的公开老师，可以稍后再来查看。' }}</p><article v-for="t in items" :key="t.id" class="form-card"><h2>{{ t.displayName }}</h2><p>{{ categories(t.categoryCodes) }} · {{ t.priceMin&&t.priceMax?`${t.priceMin}—${t.priceMax} 元/小时`:'面议' }}</p><p>本人自述</p><button class="secondary" :disabled="busy" @click="open(t.id)">查看老师</button></article><button v-if="cursor" class="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多老师</button></template>
</section></template>
<style scoped>.discovery{max-width:900px;margin:auto}.discovery .form-card{margin-bottom:20px;overflow-wrap:anywhere}.discovery p{margin:14px 0;line-height:1.8}.filters{display:flex;gap:16px;align-items:end;flex-wrap:wrap}.filters label{flex:1;min-width:140px}.filters select{display:block;width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;margin-top:8px;background:white;font:inherit}@media(max-width:520px){.filters label{min-width:100%}}</style>
