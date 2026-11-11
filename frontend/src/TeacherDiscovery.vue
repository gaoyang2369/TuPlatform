<script setup lang="ts">
import {computed,nextTick,onBeforeUnmount,onMounted,reactive,ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me,ProfileOptions} from "./account-api.ts";
import type {PublicTeacher} from "./match-types.ts";
import TeacherSummary from "./TeacherSummary.vue";
import MatchComposer from "./MatchComposer.vue";
import {categoryNames,areaNames,modeNames} from "./request-display.ts";
import {updateQuery,readPages,restoreScroll} from './navigation.ts';
import {moneyText} from './discovery-display.ts';
const props=defineProps<{me:Me;initialId?:string}>();const emit=defineEmits<{matched:[id:string];expired:[];requests:[]}>();
type Filters={category:string;area:string;mode:string};
const query=new URLSearchParams(location.search);
const filters=reactive<Filters>({category:query.get('category')||"",area:query.get('area')||"",mode:query.get('mode')||""}),applied=ref<Filters>({...filters}),filtersExpanded=ref(false);
const items=ref<PublicTeacher[]>([]),detail=ref<PublicTeacher|null>(null),selectedId=ref<string|null>(props.initialId||null),busy=ref(false),error=ref("");
const pageCursors=ref<(string|null)[]>(readPages(query));
const nextCursor=ref<string|null>(null),pageNumber=ref(Math.max(1,Math.min(Math.floor(Number(query.get('page')))||1,pageCursors.value.length))),heading=ref<HTMLElement>();
let openedFromList=false,alive=true;
onBeforeUnmount(()=>{alive=false;});
const filterSummary=computed(()=>[categoryNames[applied.value.category],areaNames[applied.value.area],modeNames[applied.value.mode]].filter(Boolean).join(' · ')||'全部老师 · 坪山区');
const options=ref<ProfileOptions>({categoryCodes:[],serviceModes:[],areaCodes:[]});
let retryAction:(()=>Promise<void>)|null=null;
function labels(codes:string[],names:Record<string,string>){return codes.map(c=>names[c]||c).join('、');}
// Prefer explicitly labelled teaching information without inferring qualifications from a biography.
function teachingSummary(introduction:string){
  const text=introduction.trim();
  const highlights=text.split(/\r?\n/).map(line=>line.trim()).filter(line=>/^(?:教学方向|方向|擅长(?:领域|技能|科目)?|特长|技能|适教(?:范围|年级)?|年级(?:范围)?|科目|学科)\s*[：:]/.test(line));
  return highlights.length?highlights.join("\n"):text;
}
async function focusHeading(){await nextTick();heading.value?.scrollIntoView({block:"start"});heading.value?.focus({preventScroll:true});}
async function run(action:()=>Promise<void>){
  if(busy.value)return;busy.value=true;error.value="";retryAction=null;
  try{await action();}catch(e){retryAction=action;error.value=e instanceof Error?e.message:"读取失败，请重试";if(e instanceof ApiFailure&&(e.status===401||e.code==="SESSION_CHANGED"))emit("expired");}finally{busy.value=false;}
}
async function loadPage(cursor:string|null,index:number,values:Filters=applied.value,reset=false,replace=false){
  const snapshot={...values},q=new URLSearchParams();
  if(snapshot.category)q.set("categoryCode",snapshot.category);if(snapshot.area)q.set("areaCode",snapshot.area);if(snapshot.mode)q.set("serviceMode",snapshot.mode);if(cursor)q.set("cursor",cursor);
  const result=await api.request<{items:PublicTeacher[];nextCursor:string|null}>("/teachers?"+q);
  if(!alive)return;
  items.value=result.items;nextCursor.value=result.nextCursor;pageNumber.value=index;applied.value=snapshot;
  const cursors=reset?[null]:pageCursors.value.slice(0,index);cursors[index-1]=cursor;pageCursors.value=cursors;
  updateQuery({view:'teachers',...snapshot,page:index>1?String(index):undefined,pages:index>1?JSON.stringify(cursors):undefined},replace);
}
async function filter(){const snapshot={...filters};await run(()=>loadPage(null,1,snapshot,true));if(!error.value){filtersExpanded.value=false;await focusHeading();}}
async function resetFilters(){Object.assign(filters,{category:'',area:'',mode:''});await filter();}
async function changePage(direction:number){
  const index=pageNumber.value+direction,cursor=direction>0?nextCursor.value:pageCursors.value[index-1]??null;
  if(index<1||(direction>0&&!cursor))return;
  await run(()=>loadPage(cursor,index));if(!error.value)await focusHeading();
}
async function open(id:string){if(busy.value)return;if(!selectedId.value)openedFromList=true;selectedId.value=id;detail.value=null;updateQuery({view:'teachers',teacher:id});await run(async()=>{detail.value=await api.request<PublicTeacher>("/teachers/"+encodeURIComponent(id));});await focusHeading();}
async function back(){if(busy.value)return;if(openedFromList){history.back();return;}selectedId.value=null;detail.value=null;error.value="";retryAction=null;updateQuery({teacher:undefined});await run(()=>loadPage(pageCursors.value[pageNumber.value-1]??null,pageNumber.value));await focusHeading();}
async function retry(){const action=retryAction;if(action)await run(action);}
onMounted(()=>void run(async()=>{await Promise.all([api.request<ProfileOptions>("/profile-options").then(value=>{options.value=value;}),selectedId.value?api.request<PublicTeacher>('/teachers/'+encodeURIComponent(selectedId.value)).then(value=>{detail.value=value;}):loadPage(pageCursors.value[pageNumber.value-1]??null,pageNumber.value,applied.value,false,true)]);await nextTick();restoreScroll();}));
</script>
<template><section class="discovery" :aria-busy="busy"><div ref="heading" class="page-heading" tabindex="-1"><div><p class="eyebrow">NEARBY TUTORS</p><h1>发现老师</h1><p>先了解教学方向，再找到适合的老师。资料由老师本人填写并主动公开。</p></div><button v-if="selectedId" class="secondary" :disabled="busy" @click="back">返回老师列表</button></div>
  <div v-if="error" role="alert" class="message error">{{ error }}<button class="text-button" :disabled="busy" @click="retry">重新读取</button></div>
  <article v-if="detail" class="form-card"><TeacherSummary :teacher="detail"/><MatchComposer :key="detail.id" :me="me" :teacher-id="detail.id" @matched="emit('matched',$event)" @expired="emit('expired')" @requests="emit('requests')"/></article>
  <p v-else-if="selectedId&&busy" class="form-card" role="status">正在读取老师资料…</p>
  <template v-else-if="!selectedId"><div class="filter-toolbar"><span aria-live="polite">{{filterSummary}}</span><button class="secondary filter-toggle" :aria-expanded="filtersExpanded" aria-controls="teacher-filters" @click="filtersExpanded=!filtersExpanded">{{filtersExpanded?'收起筛选':'筛选条件'}}</button></div><form id="teacher-filters" :class="['form-card filters',{'filters-expanded':filtersExpanded}]" @submit.prevent="filter"><fieldset :disabled="busy"><legend class="sr-only">筛选老师</legend><label>类别<select v-model="filters.category"><option value="">全部类别</option><option v-for="c in options.categoryCodes" :key="c" :value="c">{{ categoryNames[c]||c }}</option></select></label><label>街道<select v-model="filters.area"><option value="">全部街道</option><option v-for="c in options.areaCodes" :key="c" :value="c">{{ areaNames[c]||c }}</option></select></label><label>方式<select v-model="filters.mode"><option value="">全部方式</option><option v-for="c in options.serviceModes" :key="c" :value="c">{{ modeNames[c]||c }}</option></select></label><button class="primary">筛选老师</button><button type="button" class="text-button" @click="resetFilters">重置筛选</button></fieldset></form>
    <p v-if="!items.length&&!error" role="status" class="form-card">{{ busy?'正在读取老师…':'暂无符合条件的公开老师，可以稍后再来查看。' }}</p>
    <article v-for="t in items" :key="t.id" class="form-card teacher-card">
      <div class="teacher-card-heading"><h2>{{ t.displayName }}</h2><p class="teacher-price">{{ t.priceMin&&t.priceMax?`${moneyText(t.priceMin)}—${moneyText(t.priceMax)}`:'面议' }}<small v-if="t.priceMin&&t.priceMax">元/小时</small></p></div>
      <div class="teacher-tags"><span v-for="code in t.categoryCodes" :key="code">{{ categoryNames[code]||code }}</span><span v-for="code in t.serviceModes" :key="code" class="mode-tag">{{ modeNames[code]||code }}</span></div>
      <p v-if="t.introduction?.trim()" class="teacher-introduction">{{ teachingSummary(t.introduction) }}</p>
      <p v-if="t.areaCodes.length" class="teacher-area">服务区域：{{ labels(t.areaCodes,areaNames) }}</p>
      <p class="time-summary">{{t.availabilityText||'时间可协商'}}</p><div class="teacher-card-footer"><small>老师本人自述</small><button class="secondary" :disabled="busy" @click="open(t.id)">查看老师</button></div>
    </article>
    <nav v-if="items.length||pageNumber>1" class="pagination" aria-label="老师分页"><button class="secondary" :disabled="busy||pageNumber===1" @click="changePage(-1)">上一页</button><span aria-live="polite">第 {{ pageNumber }} 页 · 每页10位</span><button class="secondary" :disabled="busy||!nextCursor" @click="changePage(1)">下一页</button></nav>
  </template>
</section></template>
<style scoped>
.discovery{max-width:900px;margin:auto}.page-heading:focus{outline:none}.discovery .form-card{margin-bottom:20px;overflow-wrap:anywhere}.discovery p{line-height:1.8}.filters fieldset{display:flex;gap:16px;align-items:end;flex-wrap:wrap;border:0;padding:0;margin:0;min-width:0}.filters label{flex:1;min-width:140px}.filters select{display:block;width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;margin-top:8px;background:white;font:inherit}.teacher-card-heading{display:flex;align-items:baseline;justify-content:space-between;gap:16px;flex-wrap:wrap}.teacher-card h2{font-size:22px;margin:0}.teacher-price{margin:0;font-size:19px;font-weight:600;color:var(--green)}.teacher-price small{font-size:12px;font-weight:400;color:var(--muted);margin-left:8px}.teacher-tags{display:flex;gap:8px;flex-wrap:wrap;margin-top:16px}.teacher-tags span{padding:4px 10px;border-radius:6px;font-size:13px;background:#eaf0e4;color:var(--green)}.teacher-tags .mode-tag{background:#f4f5f1;color:var(--muted)}.teacher-introduction{white-space:pre-line;margin:20px 0 12px;display:-webkit-box;-webkit-line-clamp:4;-webkit-box-orient:vertical;overflow:hidden}.teacher-area{font-size:13px;color:var(--muted);margin:12px 0}.teacher-card-footer{display:flex;align-items:center;justify-content:space-between;gap:16px;margin-top:20px;padding-top:16px;border-top:1px solid var(--line)}.teacher-card-footer small{font-size:12px;color:var(--muted)}.pagination{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:28px 0}.pagination span{font-size:13px;color:var(--muted);text-align:center}.message .text-button{margin-left:12px}@media(max-width:520px){.filters label{min-width:100%}.teacher-card h2{font-size:20px}.teacher-card-heading{gap:8px}.teacher-price{font-size:17px}.teacher-introduction{font-size:14px}.pagination{gap:8px}.pagination .secondary{padding:10px 12px}.pagination span{font-size:12px}.page-heading{flex-wrap:wrap}}
</style>
