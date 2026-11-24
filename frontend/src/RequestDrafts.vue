<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { api, ApiFailure } from "./account-api.ts";
import { updateQuery } from "./navigation";
import { ArrowRight, Clock3, FilePenLine, Plus } from "@lucide/vue";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import RecommendedTeachers from "./RecommendedTeachers.vue";
import DraftChoices from "./DraftChoices.vue";
import ConfirmDialog from "./ConfirmDialog.vue";
import RequestSummary from "./RequestSummary.vue";
import FeedbackToast from "./FeedbackToast.vue";
import { statusNames,declarationNames } from "./request-display.ts";
import type { SavedRequest } from "./request-display.ts";
type Slot = { weekday: number; startMinute: number; endMinute: number };
type Saved = SavedRequest;
type Options = { categories: string[]; streets: string[]; areaHints?: string[]; audienceStages: string[]; serviceModes: string[]; publisherDeclarations?: {code:string;version:string;label:string}[] };
const emit = defineEmits<{ expired: []; dirty: [value: boolean]; profile: []; matches: [id:string]; teacher: [id:string] }>();
const view = ref<"list" | "edit">("list"), busy = ref(false), message = ref(""), error = ref("");
const feedbackId = ref(0);
const items = ref<Saved[]>([]), nextCursor = ref<string | null>(null), current = ref<Saved | null>(null), latest = ref<Saved | null>(null);
const conflict = ref(false), step = ref(1), reached = ref(1);
const errorSummary = ref<HTMLElement>();
const stepHeading = ref<HTMLElement>();
const fieldErrors=ref<Record<string,string>>({});
const fieldNames:Record<string,string>={title:"需求标题",categoryCode:"服务类别",audienceStageCode:"服务对象阶段",street:"公开街道",area_hint:"大致片区",exact_address:"具体地址",grade:"年级",subject:"科目",goal:"目标与现状",serviceMode:"服务方式",budgetMin:"预算下限",budgetMax:"预算上限",durationHours:"单次时长",frequency:"频率",availableSlots:"可约时段",availabilityPreference:"时间偏好",availabilityNote:"时间备注",expectedStartDate:"期望开始日期",tutorExpectations:"对老师的期待",displayName:"本人展示名",publisherDeclaration:"本次发布声明",expectedVersion:"保存版本"};
const options = ref<Options>({ categories: [], streets: [], audienceStages: [], serviceModes: [] });
const form = reactive({ title: "", categoryCode: "", audienceStageCode: "", street: "", area_hint: "", exact_address: "", grade: "", subject: "", goal: "", serviceMode: "", budgetMin: "", budgetMax: "", durationHours: "2", frequencyMode: "", frequencyCount: "", availabilityMode: "NEGOTIABLE", preferredDays: [] as number[], preferredPeriods: [] as string[], availableSlots: [] as { weekday: number; start: string; end: string }[], availabilityNote: "", expectedStartDate: "", tutorExpectations: "" });
const baseline = ref("");
const declaration=ref("");
let pendingAction:{path:string;body:string;key:string}|null=null;
const declarations=computed(()=>(options.value.publisherDeclarations??[]).filter(item=>form.audienceStageCode==='ADULT'?item.code==='SELF_ADULT':['PRIMARY','JUNIOR','SENIOR'].includes(form.audienceStageCode)?item.code==='GUARDIAN_OF_MINOR':true));
const autoSavedAt = ref("");
const autoSaving = ref(false);
let autoTask: Promise<void> | undefined;
let saveTimer: ReturnType<typeof setTimeout> | undefined;
let mounted = true;
let pendingCreation: { key:string; fields:Record<string,unknown> } | null = null;
const unsaved = computed(() => current.value?.id === "");
const dirty = computed(() => view.value === "edit" && current.value?.status==='DRAFT' && JSON.stringify(form) !== baseline.value);
watch(dirty, value => emit("dirty", value));
const confirmLeave = ref(false);
const deleting = ref<Saved|null>(null);
let leaveAction: (() => Promise<void> | void) | null = null;
const categoryNames: Record<string,string> = { DRAWING:"绘画",DANCE:"舞蹈",OTHER_SKILL:"其他课外技能",EXTRACURRICULAR_STUDY:"课外学习" };
const areaNames: Record<string,string> = { PS_PINGSHAN:"坪山",PS_KENGZI:"坑梓",PS_LONGTIAN:"龙田",PS_SHIJING:"石井",PS_MALUAN:"马峦",PS_BILING:"碧岭" };
const stages: Record<string,string> = { PRIMARY:"小学",JUNIOR:"初中",SENIOR:"高中",ADULT:"成年",OTHER:"其他" };
const modes: Record<string,string> = { ONLINE:"线上",OFFLINE:"线下" };
const days = ["周一","周二","周三","周四","周五","周六","周日"];
const weekdayLabels = Object.fromEntries(days.map((day, i) => [i + 1, day]));
const durations = ["1", "1.5", "2", "2.5"];
const periods = [{code:"MORNING",label:"上午"},{code:"AFTERNOON",label:"下午"},{code:"EVENING",label:"晚上"}];
const gradeSuggestions = computed(() => form.audienceStageCode==='PRIMARY' ? ["一年级","二年级","三年级","四年级","五年级","六年级"] : form.audienceStageCode==='JUNIOR' ? ["初一","初二","初三"] : form.audienceStageCode==='SENIOR' ? ["高一","高二","高三"] : ["成人学习","其他阶段"]);
function adjustDuration(delta:number) { const current=Number(form.durationHours); form.durationHours=String(Math.max(.5,Math.min(8,(Number.isFinite(current)&&current>0?current:2)+delta))); }
function sameDays(days:number[]) { return [...form.preferredDays].sort().join(',')===days.join(','); }
function toggleDay(day:number) { form.preferredDays=form.preferredDays.includes(day)?form.preferredDays.filter(value=>value!==day):[...form.preferredDays,day].sort(); }
function togglePeriod(code:string) { form.preferredPeriods=form.preferredPeriods.includes(code)?form.preferredPeriods.filter(value=>value!==code):[...form.preferredPeriods,code]; }
function time(value: number) { return `${Math.floor(value/60)}`.padStart(2,"0")+":"+`${value%60}`.padStart(2,"0"); }
function minute(value: string, end: boolean) {
  if (!/^\d{2}:\d{2}$/.test(value)) throw new Error("时段请按09:00这样的格式填写。");
  const [h,m] = value.split(":").map(Number); const result = h!*60+m!;
  if (m!>59 || h!>24 || result>(end?1440:1439)) throw new Error("时刻超出范围，24:00只可作为结束时间。");
  return result;
}
function load(saved: Saved) {
  declaration.value="";pendingAction=null;
  autoSavedAt.value="";
  current.value=saved; const f=saved.fields;
  for(const key of ["title","categoryCode","audienceStageCode","street","area_hint","exact_address","grade","subject","goal","serviceMode","budgetMin","budgetMax","durationHours","availabilityNote","expectedStartDate","tutorExpectations"] as const) form[key]=String(f[key]??"");
  form.durationHours ||= "2";
  if (!form.street && areaNames[String(f.areaCode)]) form.street = areaNames[String(f.areaCode)] + "街道";
  const frequency=f.frequency as {mode?:string;count?:number|null}|undefined;
  form.frequencyMode=frequency?.mode??""; form.frequencyCount=frequency?.count==null?"":String(frequency.count);
  form.availableSlots=((f.availableSlots??[]) as Slot[]).map(s=>({weekday:s.weekday,start:time(s.startMinute),end:time(s.endMinute)}));
  const preference=f.availabilityPreference as {mode:string;weekdays:number[];periods:string[]}|undefined;
  form.availabilityMode=preference?.mode??(form.availableSlots.length?'EXACT':'NEGOTIABLE');
  form.preferredDays=[...(preference?.weekdays??[])]; form.preferredPeriods=[...(preference?.periods??[])];
  baseline.value=JSON.stringify(form); latest.value=null; conflict.value=false;
}
function fields() {
  if(form.frequencyMode==='PER_WEEK'&&form.frequencyCount!==''&&!/^(?:[1-9]|1[0-4])$/.test(form.frequencyCount))
    throw new Error("每周次数须为1—14的整数；当前输入仍保留，请修改后再保存。");
  const result: Record<string,unknown>={};
  result.districtCode="SZ_PINGSHAN";
  for(const key of ["title","categoryCode","audienceStageCode","street","area_hint","exact_address","goal","serviceMode","budgetMin","budgetMax","durationHours","availabilityNote","expectedStartDate","tutorExpectations"] as const) result[key]=form[key]||null;
  result.areaCode = null; // Saving the structured street replaces the old street code.
  result.grade=form.categoryCode==='EXTRACURRICULAR_STUDY'?(form.grade||null):null;
  result.subject=form.categoryCode==='EXTRACURRICULAR_STUDY'?(form.subject||null):null;
  result.frequency=form.frequencyMode?{mode:form.frequencyMode,count:form.frequencyMode==="PER_WEEK"&&form.frequencyCount!==""?Number(form.frequencyCount):null}:null;
  result.availableSlots=form.availabilityMode==='EXACT'?form.availableSlots.map(s=>({weekday:Number(s.weekday),startMinute:minute(s.start,false),endMinute:minute(s.end,true)})):[];
  result.availabilityPreference=form.availabilityMode==='EXACT'?null:{mode:form.availabilityMode,weekdays:form.availabilityMode==='PREFERRED'?form.preferredDays:[],periods:form.availabilityMode==='PREFERRED'?form.preferredPeriods:[]};
  return result;
}
async function run(action: ()=>Promise<void>|void) {
  if(busy.value)return; busy.value=true; feedbackId.value++; message.value=""; error.value="";fieldErrors.value={};
  try { await autoTask; await action(); }
  catch(e) {
    error.value=e instanceof Error?e.message:"操作未完成，请重试";
    if(e instanceof ApiFailure) {
      fieldErrors.value=e.fieldErrors;
      if(["VERSION_CONFLICT","REQUEST_READ_ONLY","REVISION_NOT_ALLOWED"].includes(e.code))conflict.value=true;
      if(e.code==="SESSION_CHANGED" || e.code==="LOGIN_REQUIRED")emit("expired");
    }
  } finally {busy.value=false;}
  if (error.value && !confirmLeave.value && !deleting.value) {
    await nextTick();
    errorSummary.value?.focus();
  }
}
function fieldFeedback(field: string, hint?: string) {
  const key = field;
  const invalid = !!fieldErrors.value[key];
  return {
    "aria-invalid": invalid || undefined,
    "aria-describedby": [hint, invalid && "error-" + key].filter(Boolean).join(" ") || undefined,
  };
}
async function focusField(name:string){
  if(name==='displayName'){emit("profile");return;}

  step.value=name==='publisherDeclaration'?3:["budgetMin","budgetMax","durationHours","frequency","availableSlots","availabilityPreference","availabilityNote","expectedStartDate"].includes(name)?2:1;
  reached.value=Math.max(reached.value,step.value);await nextTick();document.getElementById("draft-"+name)?.focus();
}
async function list(append=false) {
  const [data,config]=await Promise.all([api.request<{items:Saved[];nextCursor:string|null}>("/me/requests"+(append&&nextCursor.value?"?cursor="+encodeURIComponent(nextCursor.value):"")),api.request<Options>("/publishing-options")]);
  options.value=config; items.value=append?[...items.value,...data.items]:data.items; nextCursor.value=data.nextCursor; view.value="list";
  updateQuery({ownedRequest:undefined}, true);
}
async function create() { await run(async()=>{
  if (new URLSearchParams(location.search).get("create") === "1") {
    options.value = await api.request<Options>("/publishing-options");
    updateQuery({ create: undefined }, true);
  }
  pendingCreation=null;
  load({id:"",status:"DRAFT",version:0,fields:{},lastSavedAt:"",retentionReminder:false,latestSubmission:null});
  step.value=1;reached.value=1;view.value="edit";
}); }
async function open(id: string, fromLink=false) { await run(async()=>{
  if (fromLink) options.value = await api.request<Options>("/publishing-options");
  const saved=await api.request<Saved>("/requests/"+encodeURIComponent(id));
  load(saved);step.value=1;reached.value=2;view.value="edit";
}); }
async function persist() {
  if(!current.value)throw new Error("请重新打开草稿。");
  if(conflict.value)throw new Error("请先读取并核对最新草稿，当前输入仍保留。");
  const snapshot = JSON.stringify(form);
  const values=fields();
  if(unsaved.value) {
    pendingCreation??={key:crypto.randomUUID(),fields:values};
    let created:Saved;
    try { created=await api.request<Saved>("/requests","POST",{fields:pendingCreation.fields},pendingCreation.key); }
    catch(e) {
      // A definitive rejection did not save anything; corrected input starts a new attempt.
      if(e instanceof ApiFailure && e.status>=400 && e.status<500)pendingCreation=null;
      throw e;
    }
    current.value=created;
    const changed=JSON.stringify(values)!==JSON.stringify(pendingCreation.fields);
    pendingCreation=null;
    if(created.status!=='DRAFT'||created.version!==0)throw new ApiFailure(409,"VERSION_CONFLICT","首次保存的草稿状态已变化，请读取并核对最新内容。当前输入仍保留。");
    if(!changed){baseline.value=snapshot;return;}
  }
  const saved=await api.request<Saved>(`/requests/${current.value.id}/draft`,"PATCH",{expectedVersion:current.value.version,fields:values});
  current.value=saved;
  baseline.value=snapshot;
}
function scheduleSave() {
  clearTimeout(saveTimer);
  if (!mounted || !dirty.value || conflict.value || confirmLeave.value) return;
  saveTimer = setTimeout(startAutoSave, 1500);
}
function startAutoSave() {
  if (!mounted || !dirty.value || conflict.value || confirmLeave.value) return;
  if (busy.value || autoTask) { scheduleSave(); return; }
  const sent = JSON.stringify(form);
  autoSaving.value = true;
  autoTask = persist().then(() => {
    if (!mounted) return;
    autoSavedAt.value = new Date(current.value!.lastSavedAt).toLocaleTimeString("zh-CN", {timeZone:"Asia/Shanghai",hour:"2-digit",minute:"2-digit",second:"2-digit",hour12:false});
    error.value=""; fieldErrors.value={};
  }).catch(e => {
    if (!mounted) return;
    error.value = e instanceof Error ? e.message : "自动保存失败，请点击保存草稿重试。";
    if (e instanceof ApiFailure) {
      fieldErrors.value = e.fieldErrors;
      if (["VERSION_CONFLICT","REQUEST_READ_ONLY"].includes(e.code)) conflict.value=true;
      if (e.status===401 || e.code==="SESSION_CHANGED") emit("expired");
    }
  }).finally(() => {
    autoTask=undefined; autoSaving.value=false;
    if (sent !== JSON.stringify(form)) scheduleSave();
  });
}
watch(() => JSON.stringify(form), () => {
  if (dirty.value) { declaration.value=""; pendingAction=null; }
  scheduleSave();
});
watch(confirmLeave, scheduleSave);
function statusColor(status: string) {
  if (status === "OPEN") return "bg-emerald-50 text-emerald-800";
  if (status === "PENDING_REVIEW" || status === "REJECTED") return "bg-orange-50 text-orange-800";
  return "bg-slate-100 text-secondary-foreground";
}
function askDelete(item:Saved) { if(busy.value)return;error.value="";fieldErrors.value={};deleting.value=item; }
async function removeDraft() { await run(async()=>{
  const item=deleting.value;if(!item)return;
  try { await api.request(`/requests/${item.id}`,"DELETE",{expectedVersion:item.version}); }
  catch(e) { if(!(e instanceof ApiFailure && e.status===404 && e.code==='NOT_FOUND'))throw e; }
  items.value=items.value.filter(value=>value.id!==item.id);deleting.value=null;message.value="草稿已删除。";
}); }
async function save(next=false) {clearTimeout(saveTimer);await run(async()=>{await persist();autoSavedAt.value="";message.value="草稿已保存。";if(next){step.value=Math.min(3,step.value+1);reached.value=Math.max(reached.value,step.value);await nextTick();stepHeading.value?.focus();}});}
function requestLeave(action: ()=>Promise<void>|void) {
  if(busy.value)return;
  if(autoTask){void autoTask.then(()=>requestLeave(action));return;}
  if(dirty.value){leaveAction=action;confirmLeave.value=true;}else void run(action);
}
async function leave(saveFirst:boolean) {await run(async()=>{if(saveFirst)await persist();const action=leaveAction;leaveAction=null;confirmLeave.value=false;if(action)await action();});}
async function readLatest() { await run(async()=>{ if(current.value?.id)latest.value=await api.request<Saved>("/requests/"+current.value.id);message.value="最新保存内容已读取，当前输入仍保留。"; }); }
function acceptLatest(keep:boolean) {
  if(!latest.value || busy.value)return;
  const input=JSON.stringify(form);load(latest.value);if(keep&&current.value?.status==='DRAFT')Object.assign(form,JSON.parse(input));
  message.value=keep?"已保留当前输入，请核对后再保存。":"已载入最新保存内容。";
}
async function action(path:string,body:unknown) {
  const encoded=JSON.stringify(body);
  if(!pendingAction||pendingAction.path!==path||pendingAction.body!==encoded)pendingAction={path,body:encoded,key:crypto.randomUUID()};
  await api.request(path,"POST",body,pendingAction.key);
  load(await api.request<Saved>(`/requests/${current.value!.id}`));
}
async function submit() {await run(async()=>{
  if(!current.value||dirty.value||!declaration.value)throw new Error("请先保存并预览内容，再主动选择本次发布声明。");
  await action(`/requests/${current.value.id}/submit`,{expectedVersion:current.value.version,publisherDeclaration:declaration.value,declarationVersion:"publisher-v1"});
  message.value="需求已提交，等待运营审核；当前尚未公开。";
});}
async function revise() {await run(async()=>{
  if(!current.value)return;
  await action(`/requests/${current.value.id}/revisions`,{expectedVersion:current.value.version});
  step.value=1;reached.value=2;message.value="已生成可修改草稿；重新提交时需要再次确认声明并等待审核。";
});}
async function refresh() {await run(async()=>{if(current.value?.id)load(await api.request<Saved>(`/requests/${current.value.id}`));});}
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value){event.preventDefault();event.returnValue="";}}
onMounted(()=>{
  window.addEventListener("beforeunload",beforeUnload);
  const query = new URLSearchParams(location.search);
  const ownedRequest = query.get("ownedRequest");
  if (ownedRequest) void open(ownedRequest, true);
  else if (query.get("create") === "1") void create();
  else void run(()=>list());
});
onBeforeUnmount(()=>{mounted=false;clearTimeout(saveTimer);window.removeEventListener("beforeunload",beforeUnload);});
defineExpose({requestLeave});
</script>

<template>
  <section class="draft-page space-y-5 text-foreground [overflow-wrap:anywhere]" :class="view==='edit' && 'mx-auto max-w-4xl'">
    <header class="flex flex-wrap items-center justify-between gap-3">
      <div><h1 class="text-3xl font-bold tracking-tight sm:text-[40px]">{{ view==='list'?'我的需求':current?.status==='DRAFT'?'填写需求草稿':'需求进度' }}</h1>
        <p class="mt-1 text-[15px] text-secondary-foreground">填写时会自动保存草稿，提交并通过审核后才会公开。</p></div>
      <Button v-if="view==='list'" class="h-11 rounded-xl" :disabled="busy" @click="create"><Plus/>新建需求草稿</Button>
      <Button v-else variant="outline" :disabled="busy" @click="requestLeave(()=>list())">返回我的需求</Button>
    </header>
    <div v-if="error" ref="errorSummary" tabindex="-1" class="message error scroll-mt-32" role="alert"><p>{{ error }}</p><ul v-if="Object.keys(fieldErrors).length"><li v-for="(value,name) in fieldErrors" :key="name" :id="'error-'+name"><Button type="button" variant="ghost" class="h-auto max-w-full whitespace-normal text-left" @click="focusField(String(name))">{{ fieldNames[name]||name }}：{{ value }} · 返回填写</Button></li></ul></div><FeedbackToast v-if="message" :key="feedbackId" :message="message" kind="success" />
    <template v-if="view==='list'">
      <div v-if="!items.length && busy" role="status" class="grid gap-6 md:grid-cols-2"><span class="sr-only">正在读取需求…</span><Skeleton v-for="i in 2" :key="i" class="h-40 rounded-2xl"/></div>
      <div v-else-if="!items.length && !error" class="grid justify-items-center gap-3 rounded-2xl border border-border bg-card p-8 text-center"><FilePenLine class="size-7 text-muted-foreground"/><p>暂时没有已保存的需求，点击“新建需求草稿”开始填写。</p></div>
      <div v-else class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
        <article v-for="item in items" :key="item.id" class="flex min-w-0 flex-col gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm transition hover:shadow-lg motion-safe:hover:-translate-y-0.5 sm:p-6">
          <div class="flex items-center gap-2"><Badge variant="secondary" :class="statusColor(item.status)">{{ statusNames[item.status] || item.status }}</Badge><span v-if="item.status==='DRAFT'" class="text-xs text-muted-foreground">仅本人可见</span></div>
          <h2 class="text-xl font-bold">{{ item.fields.title || '未命名草稿' }}</h2>
          <p v-if="item.fields.goal" class="line-clamp-2 text-[15px] leading-6 text-secondary-foreground">{{ item.fields.goal }}</p>
          <p class="mt-auto flex items-center gap-2 text-xs text-muted-foreground"><Clock3 class="size-3.5"/>最近保存：{{ new Date(item.lastSavedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</p>
          <p v-if="item.retentionReminder" class="inline-warning">这份草稿长期未保存，7天内可能被清理；继续编辑并保存可保留。</p>
          <div class="flex flex-wrap items-center gap-2"><Button variant="secondary" class="h-10 flex-1 justify-between rounded-xl" :disabled="busy" @click="open(item.id)">{{ item.status==='DRAFT'?'继续填写':'查看进度' }}<ArrowRight/></Button>
            <Button v-if="item.status==='DRAFT' && !item.latestSubmission" variant="ghost" class="text-muted-foreground" :disabled="busy" @click="askDelete(item)">删除草稿</Button></div>
          <RecommendedTeachers :disabled="busy" v-if="item.status==='PENDING_REVIEW'" :request-id="item.id" @open="emit('teacher',$event)" @expired="emit('expired')"/>
        </article>
      </div>
      <Button v-if="nextCursor" variant="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多</Button><Button v-if="error" variant="secondary" :disabled="busy" @click="run(()=>list())">重新读取</Button>
    </template>
    <article v-else-if="current && current.status!=='DRAFT'" class="form-card">
      <Badge variant="secondary" :class="statusColor(current.status)">{{ statusNames[current.status] }}</Badge>
      <p v-if="current.status==='PENDING_REVIEW'" class="privacy-note">正在等待运营审核，当前仅本人可见。待审期间不可编辑或撤回。</p>
      <RecommendedTeachers :disabled="busy" v-if="current.status==='PENDING_REVIEW'" :request-id="current.id" @open="emit('teacher',$event)" @expired="emit('expired')"/>
      <p v-if="current.status==='OPEN'" class="privacy-note">已审核通过，当前招募中。公开内容为本次提交版本，不能直接修改或暂停。</p>
      <p v-if="current.status==='EXPIRED'" class="privacy-note">需求已到期，不再公开。你可以主动修改后重新提交并等待审核。</p>
      <p v-if="current.latestSubmission?.reviewFeedback" class="message warning">驳回理由：{{ current.latestSubmission.reviewFeedback }}</p>
      <RequestSummary :fields="current.latestSubmission?.fields??current.fields" :display-name="current.latestSubmission?.publisherDisplayName" />
      <details v-if="current.latestSubmission"><summary>本次提交声明（仅本人及审核人员可见）</summary><p>{{ declarationNames[current.latestSubmission.publisherDeclaration] }}</p></details>
      <div class="form-actions"><Button variant="secondary" :disabled="busy" @click="refresh">刷新进度</Button><Button v-if="current.latestSubmission" variant="secondary" :disabled="busy" @click="emit('matches',current.id)">查看候选与撮合</Button><Button v-if="['REJECTED','EXPIRED'].includes(current.status)" :disabled="busy" @click="revise">修改后重新提交</Button></div>
    </article>
    <template v-else-if="current">
      <details v-if="current.latestSubmission" class="form-card"><summary>上次提交及反馈</summary><p v-if="current.latestSubmission.reviewFeedback">驳回理由：{{ current.latestSubmission.reviewFeedback }}</p><RequestSummary :fields="current.latestSubmission.fields" :display-name="current.latestSubmission.publisherDisplayName" /><p>上次内容会保留，本次修改不会覆盖该提交。</p></details>
      <nav ref="stepHeading" tabindex="-1" class="draft-steps scroll-mt-32" aria-label="填写步骤"><button v-for="(title,index) in ['基本信息','时间预算','草稿预览']" :key="title" :aria-current="step===index+1?'step':undefined" :disabled="busy || index+1>reached || index===2" @click="step=index+1">{{ index+1 }}. {{ title }}</button></nav>
      <div v-if="conflict||latest" class="message warning"><p>草稿有新版本，请核对后继续。当前输入未被覆盖。</p><Button variant="secondary" :disabled="busy" @click="readLatest">读取最新草稿</Button><template v-if="latest"><details><summary>查看最新保存内容</summary><pre>{{ JSON.stringify(latest.fields,null,2) }}</pre></details><Button variant="secondary" :disabled="busy" @click="acceptLatest(false)">{{ latest.status==='DRAFT'?'载入最新内容':'查看已提交状态，放弃当前输入' }}</Button><Button v-if="latest.status==='DRAFT'" variant="secondary" :disabled="busy" @click="acceptLatest(true)">已核对，保留输入</Button></template></div>
      <form class="draft-form" @submit.prevent="save(false)"><fieldset :disabled="busy">
        <section v-if="step===1" class="form-section">
          <h2>学习需求</h2>
          <label>需求标题<input id="draft-title" v-bind="fieldFeedback('title')" v-model="form.title" placeholder="例如：寻找周末绘画指导"><small>提交前必填，最多60个字符。</small></label>
          <DraftChoices id="draft-categoryCode" v-bind="fieldFeedback('categoryCode')" v-model="form.categoryCode" label="服务类别" :options="options.categories" :names="categoryNames" empty-label="暂未选择">
            <small v-if="!options.categories.length">当前没有开放的类别，可先保存其他内容。</small>
          </DraftChoices>
          <DraftChoices id="draft-audienceStageCode" v-bind="fieldFeedback('audienceStageCode')" v-model="form.audienceStageCode" label="服务对象阶段" :options="options.audienceStages" :names="stages" empty-label="暂未选择" @update:model-value="form.grade=''"/>
          <div v-if="form.categoryCode==='EXTRACURRICULAR_STUDY'" class="study-fields">
            <label for="draft-grade">年级<input id="draft-grade" v-bind="fieldFeedback('grade')" v-model="form.grade" list="grade-suggestions" placeholder="例如：高二" maxlength="20"><small>提交前必填，只填写年级，无需学校或班级。</small></label>
            <datalist id="grade-suggestions"><option v-for="grade in gradeSuggestions" :key="grade" :value="grade" /></datalist>
            <label for="draft-subject">科目<input id="draft-subject" v-bind="fieldFeedback('subject')" v-model="form.subject" list="subject-suggestions" placeholder="例如：数学、英语" maxlength="40"><small>可填写一门或多门科目。</small></label>
            <datalist id="subject-suggestions"><option v-for="subject in ['语文','数学','英语','物理','化学','生物','历史','地理','道德与法治']" :key="subject" :value="subject" /></datalist>
          </div>
        </section>
        <section v-if="step===1" class="form-section">
          <h2>授课安排与目标</h2>
          <p class="privacy-note">仅公开街道与大致片区，具体小区地址仅在双方确认意向后可见。</p>
          <div><label for="draft-street">公开街道</label><select id="draft-street" v-model="form.street" v-bind="fieldFeedback('street')"><option value="">请选择（线上可不填）</option><option v-for="code in options.streets" :key="code">{{ areaNames[code] }}街道</option></select></div>
          <div><label for="draft-area_hint">大致片区（选填）</label><select id="draft-area_hint" v-model="form.area_hint"><option value="">仅公开街道</option><option v-for="hint in options.areaHints" :key="hint">{{ hint }}</option></select></div>
          <label>具体地址（选填）<input id="draft-exact_address" v-model="form.exact_address" v-bind="fieldFeedback('exact_address')" placeholder="具体小区、楼栋或见面地点" maxlength="160"><small>仅本条需求匹配的老师在双方同意交换联系方式后可见；停止分享后隐藏。</small></label>
          <p v-if="current.fields.locationText" class="field-help">旧版地点（仅自己可见）：{{ current.fields.locationText }}。如需在互选后分享，请填写上方具体地址。</p>
          <label>目标与现状<textarea id="draft-goal" v-bind="fieldFeedback('goal')" v-model="form.goal" rows="6" placeholder="希望达到什么目标，目前有哪些基础？"></textarea><small>最多2000个字符，勿填儿童实名、学校班级、联系方式。</small></label>
          <DraftChoices id="draft-serviceMode" v-bind="fieldFeedback('serviceMode')" v-model="form.serviceMode" label="服务方式" :options="options.serviceModes" :names="modes" empty-label="留待沟通"/>
          <label>对老师的期待 <span class="optional">选填</span><textarea id="draft-tutorExpectations" v-bind="fieldFeedback('tutorExpectations')" v-model="form.tutorExpectations" rows="3"></textarea><small>最多500个字符。</small></label>
        </section>
        <section v-if="step===2" class="form-section">
          <h2>课时与预算</h2>
          <fieldset class="price-fields"><legend>预算区间 · 元/小时</legend><div class="price-inputs"><label>预算下限<input id="draft-budgetMin" v-bind="fieldFeedback('budgetMin')" v-model="form.budgetMin" inputmode="decimal" placeholder="例如 100"></label><span>至</span><label>预算上限<input id="draft-budgetMax" v-bind="fieldFeedback('budgetMax')" v-model="form.budgetMax" inputmode="decimal" placeholder="例如 200"></label></div><p class="field-help">草稿可先填一端；提交前需两端都填写，金额大于0，最多两位小数。</p></fieldset>
          <fieldset class="duration-fields"><legend>单次时长</legend>
            <div class="duration-control">
              <div class="duration-presets" role="group" aria-label="常用时长"><button v-for="hours in durations" :key="hours" type="button" :aria-pressed="Number(form.durationHours)===Number(hours)" @click="form.durationHours=hours">{{ hours }} 小时</button></div>
              <div class="duration-stepper"><button type="button" class="duration-adjust" aria-label="减少半小时" :disabled="Number(form.durationHours)<=.5" @click="adjustDuration(-.5)">−</button><label for="draft-durationHours" class="sr-only">单次时长（小时）</label><input id="draft-durationHours" v-bind="fieldFeedback('durationHours', 'duration-help')" v-model="form.durationHours" inputmode="decimal"><span>小时</span><button type="button" class="duration-adjust" aria-label="增加半小时" :disabled="Number(form.durationHours)>=8" @click="adjustDuration(.5)">＋</button></div>
            </div>
            <p id="duration-help" class="field-help">可直接输入，每次增减半小时；具体时长可协商。</p>
          </fieldset>
          <DraftChoices id="draft-frequency" v-bind="fieldFeedback('frequency')" v-model="form.frequencyMode" label="频率" :options="['PER_WEEK','NEGOTIABLE']" :names="{PER_WEEK:'每周固定次数',NEGOTIABLE:'可协商'}" empty-label="暂未选择"/>
          <label v-if="form.frequencyMode==='PER_WEEK'">每周次数<input v-model="form.frequencyCount" inputmode="numeric" placeholder="1—14"></label>
          </section>
        <section v-if="step===2" class="form-section">
          <h2>时间安排</h2>
          <fieldset id="draft-availabilityPreference" v-bind="fieldFeedback('availabilityPreference')" tabindex="-1" class="slot-fields"><legend>可约时间</legend><p class="field-help">先给出大致范围，具体安排由双方协商。</p>
            <div class="schedule-modes"><label class="check-line"><input v-model="form.availabilityMode" type="radio" value="NEGOTIABLE">时间可协商</label><label class="check-line"><input v-model="form.availabilityMode" type="radio" value="PREFERRED">选择偏好时段</label><label class="check-line"><input v-model="form.availabilityMode" type="radio" value="EXACT">填写精确时间</label></div>
            <div v-if="form.availabilityMode==='PREFERRED'" class="schedule-preferences">
              <p class="preference-label">偏好日期</p><div class="choice-buttons"><button type="button" :aria-pressed="sameDays([6,7])" @click="form.preferredDays=[6,7]">周末</button><button type="button" :aria-pressed="sameDays([1,2,3,4,5])" @click="form.preferredDays=[1,2,3,4,5]">工作日</button><button type="button" :aria-pressed="sameDays([1,2,3,4,5,6,7])" @click="form.preferredDays=[1,2,3,4,5,6,7]">每天均可</button></div>
              <div class="choice-buttons weekday-buttons" aria-label="选择星期"><button v-for="(day,index) in days" :key="day" type="button" :aria-pressed="form.preferredDays.includes(index+1)" @click="toggleDay(index+1)">{{ day }}</button></div>
              <p class="preference-label">偏好时段 <small>可多选</small></p><div class="choice-buttons"><button type="button" :aria-pressed="!form.preferredPeriods.length" @click="form.preferredPeriods=[]">不限</button><button v-for="period in periods" :key="period.code" type="button" :aria-pressed="form.preferredPeriods.includes(period.code)" @click="togglePeriod(period.code)">{{ period.label }}</button></div>
            </div>
            <div v-else-if="form.availabilityMode==='EXACT'" id="draft-availableSlots" v-bind="fieldFeedback('availableSlots')" tabindex="-1" class="exact-slots"><p class="field-help">仅在已有明确安排时填写；跨午夜请拆开，结束时间可填24:00。</p><div v-for="(slot,index) in form.availableSlots" :key="index" class="slot-row"><DraftChoices v-model="slot.weekday" class="col-span-full" label="星期" :options="days.map((_, i) => i + 1)" :names="weekdayLabels"/><label>开始<input v-model="slot.start" inputmode="numeric" placeholder="09:00"></label><label>结束<input v-model="slot.end" inputmode="numeric" placeholder="10:00"></label><Button type="button" variant="ghost" :aria-label="`删除第${index+1}项时段`" @click="form.availableSlots.splice(index,1)">删除</Button></div><Button type="button" variant="secondary" :disabled="form.availableSlots.length>=21" @click="form.availableSlots.push({weekday:6,start:'09:00',end:'11:00'})">添加时段</Button></div>
          </fieldset>
          <label>时间备注 <span class="optional">选填</span><textarea id="draft-availabilityNote" v-bind="fieldFeedback('availabilityNote')" v-model="form.availabilityNote" rows="3" placeholder="例如：周末下午优先，具体时间可以再商量"></textarea><small>最多300个字符。</small></label>
          <label>期望开始日期 <span class="optional">选填</span><input id="draft-expectedStartDate" v-bind="fieldFeedback('expectedStartDate')" v-model="form.expectedStartDate" type="date"><small>不填表示时间可协商；具体安排仍需双方沟通。</small></label>
        </section>
        <div v-if="step===3" class="draft-preview form-section">
          <RequestSummary :fields="current.fields" />
          <p v-if="current.fields.exact_address" class="privacy-note">互选并交换联系方式后分享的地址：{{ current.fields.exact_address }}</p>
          <fieldset id="draft-publisherDeclaration" v-bind="fieldFeedback('publisherDeclaration')" tabindex="-1" class="publisher-declaration"><legend>本次发布声明 · 提交必选</legend>
            <label v-for="item in declarations" :key="item.code" class="check-line"><input v-model="declaration" type="radio" :value="item.code"><span>{{ item.label }}</span></label>
            <p class="field-help">每次提交都需主动选择。该声明仅本人及审核人员可见。</p>
          </fieldset>
        </div>
        <div class="draft-actions"><p class="save-state" role="status">{{ autoSaving?'正在自动保存…':dirty?'有尚未保存的修改':unsaved?'开始填写后自动保存':autoSavedAt?'已自动保存 · '+autoSavedAt:'当前为已保存内容' }}</p><div class="action-buttons"><Button v-if="step>1" type="button" variant="secondary" @click="step--">上一步</Button><Button v-if="step<3" type="submit" variant="secondary" :disabled="conflict">保存草稿</Button><template v-else><Button type="button" variant="secondary" @click="requestLeave(()=>list())">返回我的需求</Button><Button type="button" variant="ghost" @click="refresh">重新读取保存状态</Button></template><Button v-if="step<3" type="button" :disabled="conflict" @click="save(true)">{{ step===1?'保存并下一步':'保存并预览' }}</Button><Button v-else type="button" :disabled="!declaration||dirty||conflict" @click="submit">确认提交审核</Button></div></div>
      </fieldset></form>
    </template>
    <ConfirmDialog :open="confirmLeave" title="还有未保存的修改" cancel-text="继续编辑" confirm-text="保存并离开" :busy="busy" @cancel="confirmLeave=false" @confirm="leave(true)">
      <p>离开前，可以先保存当前草稿，或继续填写。</p><p v-if="error" role="alert">{{ error }}</p>
      <template #extra><Button variant="ghost" :disabled="busy" @click="leave(false)">放弃修改并离开</Button></template>
    </ConfirmDialog>
    <ConfirmDialog :open="!!deleting" title="删除这份草稿？" confirm-text="确认删除草稿" :busy="busy" @cancel="deleting=null" @confirm="removeDraft">
      <p>“{{ deleting?.fields.title || '未命名草稿' }}”删除后无法恢复。</p><p v-if="error" role="alert">{{ error }}</p>
    </ConfirmDialog>
  </section>
</template>

<style scoped>
@reference "./styles/theme.css";
.draft-form > fieldset { @apply min-w-0 space-y-5; }
.form-section, .form-card { @apply min-w-0 space-y-5 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-7; }
.form-section > h2 { @apply text-xl font-bold; }
.draft-steps, .action-buttons, .form-actions, .schedule-modes, .choice-buttons, .duration-presets { @apply flex flex-wrap items-center gap-2; }
.draft-steps button, .choice-buttons button, .duration-presets button { @apply min-h-10 rounded-full bg-slate-100 px-3 py-2 text-sm font-medium text-secondary-foreground; }
.draft-steps [aria-current="step"], button[aria-pressed="true"] { @apply bg-blue-50 font-semibold text-primary; }
.draft-steps button:disabled:not([aria-current]), button:disabled { @apply opacity-50; }
.draft-form label:not(.sr-only), .draft-form legend { @apply text-sm font-semibold; }
.draft-form input:not([type="radio"]), .draft-form select, .draft-form textarea { @apply mt-1.5 block min-h-11 w-full rounded-xl border border-border bg-card px-3.5 py-2.5 text-base font-normal text-foreground; }
.draft-form input:focus-visible, .draft-form select:focus-visible, .draft-form textarea:focus-visible, button:focus-visible { @apply outline-2 outline-offset-2 outline-primary; }
.draft-form label:not(.sr-only) { @apply block; }
.draft-form small, .field-help, .save-state, .optional { @apply text-xs leading-5 font-normal text-muted-foreground; }
.draft-form small, .field-help { @apply mt-1.5 block; }
.optional { @apply ml-1; }
.study-fields, .price-inputs, .slot-row { @apply grid min-w-0 grid-cols-2 items-end gap-3; }
.price-inputs { @apply grid-cols-[minmax(0,1fr)_auto_minmax(0,1fr)]; }
.price-inputs > span { @apply mb-3 text-sm text-muted-foreground; }
.duration-control { @apply mt-2 flex flex-wrap items-center gap-3; }
.duration-stepper { @apply inline-flex items-center gap-1 rounded-xl bg-slate-50 p-1 text-sm; }
.duration-stepper .duration-adjust { @apply size-10 rounded-lg text-xl; }
.duration-stepper input:not([type="radio"]) { @apply m-0 w-16 p-1 text-center; }
.draft-form .check-line { @apply inline-flex min-h-10 items-center gap-2 rounded-xl bg-slate-50 px-3 py-2 font-normal; }
.check-line input { @apply shrink-0 accent-primary; }
.schedule-preferences, .exact-slots, .publisher-declaration { @apply mt-3 space-y-3 rounded-xl bg-slate-50 p-4; }
.publisher-declaration legend { @apply pt-4; }
.preference-label { @apply text-sm font-semibold; }
.draft-actions { @apply sticky bottom-0 z-10 space-y-2 rounded-t-2xl border border-border bg-white/95 p-3 shadow-sm backdrop-blur sm:flex sm:items-center sm:justify-between sm:gap-3 sm:space-y-0; padding-bottom: max(.75rem, env(safe-area-inset-bottom)); }
.action-buttons { @apply justify-end; }
.action-buttons :deep(button) { @apply h-10 rounded-xl; }
.message, .privacy-note, .inline-warning { @apply rounded-xl p-4 text-sm leading-6; }
.error { @apply bg-red-50 text-red-800; }
.warning, .inline-warning { @apply bg-orange-50 text-orange-900; }
.privacy-note { @apply bg-slate-50 text-secondary-foreground; }
[aria-invalid="true"] { @apply ring-2 ring-red-300; }
.draft-form :is(input, textarea, fieldset) { @apply scroll-mt-32; scroll-margin-bottom: 8rem; }
@media (width < 48rem) {
  .draft-actions { bottom: var(--mobile-nav-height); padding-bottom: .75rem; }
  .draft-form :is(input, textarea, fieldset) { scroll-margin-bottom: 9rem; }
}
summary { @apply cursor-pointer text-sm font-medium; }
pre { @apply whitespace-pre-wrap; }
</style>
