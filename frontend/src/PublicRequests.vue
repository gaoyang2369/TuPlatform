<script setup lang="ts">
import { computed,nextTick,onBeforeUnmount,onMounted,reactive,ref } from "vue";
import { api } from "./account-api.ts";
import type { Me } from "./account-api.ts";
import type { PublicRequest } from "./match-types.ts";
import MatchComposer from "./MatchComposer.vue";
import RequestSummary from "./RequestSummary.vue";
import { categoryNames,areaNames,modeNames } from "./request-display.ts";
import { updateQuery, readPages, restoreScroll } from "./navigation.ts";
import { moneyText, timeSummary } from "./discovery-display.ts";
const props=defineProps<{initialId?:string;me?:Me|null}>();
const emit=defineEmits<{matched:[id:string];expired:[];profile:[];login:[]}>();
type Filters={category:string;district:string;mode:string;grade:string;subject:string};
const emptyFilters=():Filters=>({category:"",district:"",mode:"",grade:"",subject:""});
const query=new URLSearchParams(location.search);
const initialFilters={...emptyFilters(),...Object.fromEntries(Object.keys(emptyFilters()).map(key=>[key,query.get(key)||""]))} as Filters;
const filters=reactive(initialFilters),applied=ref<Filters>({...initialFilters}),filtersExpanded=ref(false);
const items=ref<PublicRequest[]>([]),detail=ref<PublicRequest|null>(null),busy=ref(false),error=ref("");
const pageCursors=ref<(string|null)[]>(readPages(query));
const selectedId=ref<string|null>(props.initialId??null),pageNumber=ref(Math.max(1,Math.min(Math.floor(Number(query.get('page')))||1,pageCursors.value.length))),nextCursor=ref<string|null>(null);
const heading=ref<HTMLElement>();
let openedFromList=false,alive=true;
onBeforeUnmount(()=>{alive=false;});
const filterSummary=computed(()=>[categoryNames[applied.value.category],applied.value.mode&&modeNames[applied.value.mode],applied.value.grade,applied.value.subject].filter(Boolean).join(' · ')||'全部需求 · 坪山区');
const options=ref<{categories:string[];districts?:{code:string;label:string}[];serviceModes:string[]}>({categories:[],serviceModes:[]});
const districts=computed(()=>options.value.districts??[{code:"SZ_PINGSHAN",label:"坪山区"}]);
const studyFiltersEnabled=computed(()=>!filters.category||filters.category==="EXTRACURRICULAR_STUDY");
const grades=["一年级","二年级","三年级","四年级","五年级","六年级","初一","初二","初三","高一","高二","高三","成人学习","其他阶段"];
const subjects=["语文","数学","英语","物理","化学","生物","历史","地理","道德与法治"];
async function run(action:()=>Promise<void>) {
  if(busy.value)return;busy.value=true;error.value="";
  try{await action();}catch(e){error.value=e instanceof Error?e.message:"暂时无法读取需求，请重试。";}finally{busy.value=false;}
}
async function loadPage(cursor:string|null,index:number,values:Filters=applied.value,reset=false,replace=false) {
  const snapshot={...values},query=new URLSearchParams();
  if(snapshot.category)query.set("categoryCode",snapshot.category);
  if(snapshot.district)query.set("districtCode",snapshot.district);
  if(snapshot.mode)query.set("serviceMode",snapshot.mode);
  if(snapshot.grade.trim())query.set("grade",snapshot.grade.trim());
  if(snapshot.subject.trim())query.set("subject",snapshot.subject.trim());
  if(cursor)query.set("cursor",cursor);
  const result=await api.request<{items:PublicRequest[];nextCursor:string|null}>("/public/requests?"+query);
  if(!alive)return;
  items.value=result.items;nextCursor.value=result.nextCursor;pageNumber.value=index;applied.value=snapshot;
  const cursors=reset?[null]:pageCursors.value.slice(0,index);cursors[index-1]=cursor;pageCursors.value=cursors;
  updateQuery({view:'public',...snapshot,page:index>1?String(index):undefined,pages:index>1?JSON.stringify(cursors):undefined},replace);
}
async function focusHeading(){await nextTick();heading.value?.scrollIntoView({block:"start"});heading.value?.focus({preventScroll:true});}
async function filter(){await run(()=>loadPage(null,1,{...filters},true));if(!error.value){filtersExpanded.value=false;await focusHeading();}}
async function resetFilters(){Object.assign(filters,emptyFilters());await filter();}
function categoryChanged(){if(!studyFiltersEnabled.value){filters.grade="";filters.subject="";}}
async function changePage(direction:number){
  const index=pageNumber.value+direction;
  const cursor=direction>0?nextCursor.value:pageCursors.value[index-1]??null;
  if(index<1||(direction>0&&!cursor))return;
  await run(()=>loadPage(cursor,index));if(!error.value)await focusHeading();
}
function setUrl(id:string|null){updateQuery({view:'public',request:id||undefined});}
async function open(id:string){if(busy.value)return;if(!selectedId.value)openedFromList=true;selectedId.value=id;detail.value=null;setUrl(id);await run(async()=>{detail.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(id));});await focusHeading();}
async function back(){if(busy.value)return;if(openedFromList){history.back();return;}selectedId.value=null;detail.value=null;setUrl(null);await run(()=>loadPage(pageCursors.value[pageNumber.value-1]??null,pageNumber.value));await focusHeading();}
async function retry(){if(selectedId.value)await open(selectedId.value);else await run(()=>loadPage(pageCursors.value[pageNumber.value-1]??null,pageNumber.value));}
function districtLabel(item:PublicRequest){return districts.value.find(d=>d.code===(item.districtCode??"SZ_PINGSHAN"))?.label??"坪山区";}
onMounted(()=>void run(async()=>{
  options.value=await api.request("/publishing-options");
  if(selectedId.value)detail.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(selectedId.value));
  else await loadPage(pageCursors.value[pageNumber.value-1]??null,pageNumber.value,applied.value,false,true);
  await nextTick();restoreScroll();
}));
</script>
<template>
  <section class="public-page">
    <div ref="heading" class="page-heading" tabindex="-1"><div><p class="eyebrow">NEARBY REQUESTS</p><h1>浏览需求</h1><p>仅展示审核通过且当前有效的需求。</p></div><button v-if="selectedId" class="secondary" :disabled="busy" @click="back">返回需求列表</button></div>
    <div v-if="error" class="message error" role="alert">{{ error }}<button class="text-button" :disabled="busy" @click="retry">重新读取</button></div>
    <article v-if="detail" class="form-card">
      <RequestSummary :fields="detail" :display-name="detail.publisherDisplayName" />
      <p class="privacy-note">表达意向 → 对方接受 → 双方同意交换手机号或微信，再到站外沟通。联系方式不会随需求公开。</p>
      <div class="detail-actions"><button class="secondary" :disabled="busy" @click="open(detail.id)">刷新需求</button><button v-if="!me" class="primary" @click="emit('login')">登录后以老师身份投递</button></div>
      <MatchComposer v-if="me?.businessIdentity==='TUTOR'" :key="detail.id" :me="me" :request-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @profile="emit('profile')" />
    </article>
    <template v-else-if="!selectedId">
      <div class="filter-toolbar"><span aria-live="polite">{{filterSummary}}</span><button class="secondary filter-toggle" :aria-expanded="filtersExpanded" aria-controls="request-filters" @click="filtersExpanded=!filtersExpanded">{{filtersExpanded?'收起筛选':'筛选条件'}}</button></div>
      <form id="request-filters" :class="['form-card request-filters',{'filters-expanded':filtersExpanded}]" @submit.prevent="filter">
        <label>类别<select v-model="filters.category" @change="categoryChanged"><option value="">全部类别</option><option v-for="code in options.categories" :key="code" :value="code">{{ categoryNames[code]||code }}</option></select></label>
        <label>区域<select v-model="filters.district"><option value="">全部已开放区域</option><option v-for="district in districts" :key="district.code" :value="district.code">{{ district.label }}</option></select></label>
        <label>方式<select v-model="filters.mode"><option value="">全部方式</option><option v-for="code in options.serviceModes" :key="code" :value="code">{{ modeNames[code]||code }}</option></select></label>
        <label>年级<input v-model="filters.grade" list="filter-grade-options" placeholder="不限年级" maxlength="20" :disabled="!studyFiltersEnabled"></label>
        <label>科目<input v-model="filters.subject" list="filter-subject-options" placeholder="不限科目" maxlength="40" :disabled="!studyFiltersEnabled"></label>
        <datalist id="filter-grade-options"><option v-for="grade in grades" :key="grade" :value="grade" /></datalist>
        <datalist id="filter-subject-options"><option v-for="subject in subjects" :key="subject" :value="subject" /></datalist>
        <div class="filter-actions"><button class="primary" :disabled="busy">筛选需求</button><button type="button" class="text-button" :disabled="busy" @click="resetFilters">重置筛选</button></div>
        <small class="filter-help">年级和科目用于筛选课外学习需求。当前开放深圳坪山区。</small>
      </form>
      <p v-if="!items.length&&!error" class="form-card" role="status">{{ busy?'正在读取需求…':'暂无符合条件的公开需求，可以调整筛选条件。' }}</p>
      <article v-for="item in items" :key="item.id" class="form-card request-card">
        <div class="request-card-heading"><h2>{{ item.title }}</h2><strong class="request-price">{{ moneyText(item.budgetMin) }}—{{ moneyText(item.budgetMax) }} <small>元/小时</small></strong></div><p>{{ categoryNames[String(item.categoryCode)] }} · {{ item.serviceMode==='ONLINE'?'线上':(String(item.locationText||'').replace(/^深圳市坪山区/,'') || areaNames[String(item.areaCode)] || districtLabel(item)) }}<template v-if="item.serviceMode!=='ONLINE'"> · {{ modeNames[String(item.serviceMode)]||'方式留待沟通' }}</template></p>
        <p v-if="item.categoryCode==='EXTRACURRICULAR_STUDY'&&(item.grade||item.subject)" class="study-meta"><span v-if="item.grade">{{ item.grade }}</span><span v-if="item.subject">{{ item.subject }}</span></p>
        <p class="time-summary">{{timeSummary(item)}}</p><div class="discovery-card-footer"><small>发布者：{{ item.publisherDisplayName }}</small><button class="secondary" :disabled="busy" @click="open(item.id)">查看需求</button></div>
      </article>
      <nav v-if="items.length||pageNumber>1" class="pagination" aria-label="需求分页"><button class="secondary" :disabled="busy||pageNumber===1" @click="changePage(-1)">上一页</button><span aria-live="polite">第 {{ pageNumber }} 页 · 每页10条</span><button class="secondary" :disabled="busy||!nextCursor" @click="changePage(1)">下一页</button></nav>
    </template>
  </section>
</template>
<style scoped>
.public-page{max-width:900px;margin:auto}.public-page h1{font-size:30px}.page-heading:focus{outline:none}.request-card{margin-bottom:18px;overflow-wrap:anywhere}.request-card h2{font-size:21px}.request-card p{margin:12px 0;color:var(--muted);font-size:14px}.request-filters{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:20px;margin-bottom:24px}.request-filters label{min-width:0}.request-filters select{width:100%;display:block;padding:10px;margin-top:8px;border:1px solid var(--line);border-radius:8px;font:inherit;background:white;color:inherit}.request-filters input{display:block;margin-top:8px;min-height:48px}.filter-actions{display:flex;align-items:flex-end;gap:16px}.filter-actions .primary{min-height:48px}.filter-help{grid-column:1/-1}.pagination{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:28px 0 0}.pagination span{font-size:13px;color:var(--muted);text-align:center}.detail-actions{display:flex;gap:16px;align-items:center;flex-wrap:wrap;margin:24px 0}.study-meta{display:flex;gap:8px;flex-wrap:wrap}.study-meta span{padding:3px 10px;border-radius:6px;background:#eff4e9;color:var(--green)}.public-page .message button{margin:10px 10px 0 0}@media(max-width:650px){.request-filters{grid-template-columns:repeat(2,minmax(0,1fr));gap:18px}.filter-actions{grid-column:1/-1}}@media(max-width:520px){.public-page .page-heading{flex-wrap:wrap}.public-page h1{font-size:25px}.pagination{gap:8px}.pagination .secondary{padding:10px 12px}.pagination span{font-size:12px}.request-filters{grid-template-columns:1fr 1fr;gap:16px 12px}.request-filters select,.request-filters input{font-size:13px;min-height:44px}.filter-actions{justify-content:space-between}.filter-help{font-size:11px}}
</style>
