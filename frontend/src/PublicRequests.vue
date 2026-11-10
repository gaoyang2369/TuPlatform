<script setup lang="ts">
import { onMounted,ref } from "vue";
import { api } from "./account-api.ts";
import RequestSummary from "./RequestSummary.vue";
import { categoryNames,areaNames,modeNames } from "./request-display.ts";
const props=defineProps<{ initialId?:string }>();
type PublicRequest=Record<string,unknown>&{id:string;title:string;publisherDisplayName:string};
const items=ref<PublicRequest[]>([]), detail=ref<PublicRequest|null>(null), busy=ref(false), error=ref("");
const cursor=ref<string|null>(null), category=ref(""), area=ref(""), mode=ref("");
const options=ref<{categories:string[];streets:string[];serviceModes:string[]}>({categories:[],streets:[],serviceModes:[]});
let selectedId:string|null=null;
async function run(action:()=>Promise<void>) {
  if(busy.value)return;busy.value=true;error.value="";
  try{await action();}catch(e){error.value=e instanceof Error?e.message:"暂时无法读取需求，请重试。";}finally{busy.value=false;}
}
async function list(append=false) {
  const query=new URLSearchParams();if(category.value)query.set("categoryCode",category.value);if(area.value)query.set("areaCode",area.value);if(mode.value)query.set("serviceMode",mode.value);if(append&&cursor.value)query.set("cursor",cursor.value);
  const result=await api.request<{items:PublicRequest[];nextCursor:string|null}>("/public/requests?"+query);
  items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;
}
function setUrl(id:string|null){const url=new URL(location.href);if(id)url.searchParams.set("request",id);else url.searchParams.delete("request");history.replaceState(null,"",url.pathname+url.search);}
async function open(id:string){selectedId=id;detail.value=null;setUrl(id);await run(async()=>{detail.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(id));});}
async function back(){selectedId=null;detail.value=null;setUrl(null);await run(()=>list());}
async function retry(){if(selectedId)await open(selectedId);else await run(()=>list());}
onMounted(()=>void run(async()=>{
  options.value=await api.request("/publishing-options");
  if(props.initialId){selectedId=props.initialId;detail.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(props.initialId));}
  else await list();
}));
</script>
<template>
  <section class="public-page"><div class="page-heading"><div><p class="eyebrow">NEARBY REQUESTS</p><h1>浏览需求</h1><p>深圳坪山区 · 仅展示审核通过且当前有效的需求。</p></div><button v-if="detail||initialId" class="secondary" :disabled="busy" @click="back">返回需求列表</button></div>
    <div v-if="error" class="message error" role="alert">{{ error }}<button class="text-button" :disabled="busy" @click="retry">重新读取</button><button class="text-button" :disabled="busy" @click="back">查看需求列表</button></div>
    <article v-if="detail" class="form-card"><RequestSummary :fields="detail" :display-name="detail.publisherDisplayName" /><p class="privacy-note">具体安排需双方沟通。平台不会在公开需求中展示联系方式。</p><button class="secondary" :disabled="busy" @click="open(detail.id)">刷新需求</button><button class="text-button" :disabled="busy" @click="back">返回需求列表</button></article>
    <template v-else-if="!error">
      <form class="form-card request-filters" @submit.prevent="run(()=>list())"><label>类别<select v-model="category"><option value="">全部类别</option><option v-for="code in options.categories" :key="code" :value="code">{{ categoryNames[code]||code }}</option></select></label><label>街道<select v-model="area"><option value="">全部街道</option><option v-for="code in options.streets" :key="code" :value="code">{{ areaNames[code]||code }}</option></select></label><label>方式<select v-model="mode"><option value="">全部方式</option><option v-for="code in options.serviceModes" :key="code" :value="code">{{ modeNames[code]||code }}</option></select></label><button class="secondary" :disabled="busy">筛选需求</button></form>
      <p v-if="!items.length" class="form-card" role="status">{{ busy?'正在读取需求…':'暂无符合条件的公开需求，可以稍后再来查看。' }}</p>
      <article v-for="item in items" :key="item.id" class="form-card request-card"><h2>{{ item.title }}</h2><p>{{ categoryNames[String(item.categoryCode)] }} · {{ areaNames[String(item.areaCode)] }} · {{ modeNames[String(item.serviceMode)]||'方式留待沟通' }}</p><p>{{ item.budgetMin }}—{{ item.budgetMax }} 元/小时 · 发布者：{{ item.publisherDisplayName }}</p><button class="secondary" :disabled="busy" @click="open(item.id)">查看需求</button></article>
      <button v-if="cursor" class="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多需求</button>
    </template>
  </section>
</template>
<style scoped>
.public-page{max-width:900px;margin:auto}.public-page h1{font-size:30px}.request-card{margin-bottom:18px;overflow-wrap:anywhere}.request-card h2{font-size:21px}.request-card p{margin:12px 0;color:var(--muted);font-size:14px}.request-filters{display:flex;align-items:flex-end;gap:16px;margin-bottom:24px;flex-wrap:wrap}.request-filters label{flex:1;min-width:130px}.request-filters select{width:100%;display:block;padding:10px;margin-top:8px;border:1px solid var(--line);border-radius:8px;font:inherit;background:white}.public-page .message button{margin:10px 10px 0 0}@media(max-width:520px){.public-page .page-heading{flex-wrap:wrap}.request-filters label{min-width:100%;}.public-page h1{font-size:25px}}
</style>
