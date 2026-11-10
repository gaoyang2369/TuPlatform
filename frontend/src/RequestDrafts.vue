<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { api, ApiFailure } from "./account-api.ts";
import RequestSummary from "./RequestSummary.vue";
import { statusNames,declarationNames } from "./request-display.ts";
import type { SavedRequest } from "./request-display.ts";
type Slot = { weekday: number; startMinute: number; endMinute: number };
type Saved = SavedRequest;
type Options = { categories: string[]; streets: string[]; audienceStages: string[]; serviceModes: string[]; publisherDeclarations?: {code:string;version:string;label:string}[] };
const emit = defineEmits<{ expired: []; dirty: [value: boolean]; profile: [] }>();
const view = ref<"list" | "edit">("list"), busy = ref(false), message = ref(""), error = ref("");
const items = ref<Saved[]>([]), nextCursor = ref<string | null>(null), current = ref<Saved | null>(null), latest = ref<Saved | null>(null);
const conflict = ref(false), step = ref(1), reached = ref(1);
const fieldErrors=ref<Record<string,string>>({});
const fieldNames:Record<string,string>={title:"需求标题",categoryCode:"服务类别",audienceStageCode:"服务对象阶段",areaCode:"街道",goal:"目标与现状",serviceMode:"服务方式",budgetMin:"预算下限",budgetMax:"预算上限",durationHours:"单次时长",frequency:"频率",availableSlots:"可约时段",availabilityNote:"时间备注",expectedStartDate:"期望开始日期",tutorExpectations:"对老师的期待",displayName:"本人展示名",publisherDeclaration:"本次发布声明",expectedVersion:"保存版本"};
const options = ref<Options>({ categories: [], streets: [], audienceStages: [], serviceModes: [] });
const form = reactive({ title: "", categoryCode: "", audienceStageCode: "", areaCode: "", goal: "", serviceMode: "", budgetMin: "", budgetMax: "", durationHours: "", frequencyMode: "", frequencyCount: "", availableSlots: [] as { weekday: number; start: string; end: string }[], availabilityNote: "", expectedStartDate: "", tutorExpectations: "" });
const baseline = ref("");
const declaration=ref("");
let pendingAction:{path:string;body:string;key:string}|null=null;
const declarations=computed(()=>(options.value.publisherDeclarations??[]).filter(item=>form.audienceStageCode==='ADULT'?item.code==='SELF_ADULT':['PRIMARY','JUNIOR','SENIOR'].includes(form.audienceStageCode)?item.code==='GUARDIAN_OF_MINOR':true));
let creationKey: string | null = null;
const dirty = computed(() => view.value === "edit" && current.value?.status==='DRAFT' && JSON.stringify(form) !== baseline.value);
watch(dirty, value => emit("dirty", value));
const leaveDialog = ref<HTMLDialogElement>(), confirmLeave = ref(false);
let leaveAction: (() => Promise<void> | void) | null = null;
watch(confirmLeave, async value => { await nextTick(); if (value && !leaveDialog.value?.open) leaveDialog.value?.showModal(); if (!value && leaveDialog.value?.open) leaveDialog.value.close(); });
const categoryNames: Record<string,string> = { DRAWING:"绘画",DANCE:"舞蹈",OTHER_SKILL:"其他课外技能",EXTRACURRICULAR_STUDY:"课外学习" };
const areaNames: Record<string,string> = { PS_PINGSHAN:"坪山",PS_KENGZI:"坑梓",PS_LONGTIAN:"龙田",PS_SHIJING:"石井",PS_MALUAN:"马峦",PS_BILING:"碧岭" };
const stages: Record<string,string> = { PRIMARY:"小学",JUNIOR:"初中",SENIOR:"高中",ADULT:"成年",OTHER:"其他" };
const modes: Record<string,string> = { ONLINE:"线上",OFFLINE:"线下" };
const days = ["周一","周二","周三","周四","周五","周六","周日"];
const durations = Array.from({length:16},(_,i)=>String((i+1)/2));
function time(value: number) { return `${Math.floor(value/60)}`.padStart(2,"0")+":"+`${value%60}`.padStart(2,"0"); }
function minute(value: string, end: boolean) {
  if (!/^\d{2}:\d{2}$/.test(value)) throw new Error("时段请按09:00这样的格式填写。");
  const [h,m] = value.split(":").map(Number); const result = h!*60+m!;
  if (m!>59 || h!>24 || result>(end?1440:1439)) throw new Error("时刻超出范围，24:00只可作为结束时间。");
  return result;
}
function load(saved: Saved) {
  declaration.value="";pendingAction=null;
  current.value=saved; const f=saved.fields;
  for(const key of ["title","categoryCode","audienceStageCode","areaCode","goal","serviceMode","budgetMin","budgetMax","durationHours","availabilityNote","expectedStartDate","tutorExpectations"] as const) form[key]=String(f[key]??"");
  const frequency=f.frequency as {mode?:string;count?:number|null}|undefined;
  form.frequencyMode=frequency?.mode??""; form.frequencyCount=frequency?.count==null?"":String(frequency.count);
  form.availableSlots=((f.availableSlots??[]) as Slot[]).map(s=>({weekday:s.weekday,start:time(s.startMinute),end:time(s.endMinute)}));
  baseline.value=JSON.stringify(form); latest.value=null; conflict.value=false;
}
function fields() {
  if(form.frequencyMode==='PER_WEEK'&&form.frequencyCount!==''&&!/^(?:[1-9]|1[0-4])$/.test(form.frequencyCount))
    throw new Error("每周次数须为1—14的整数；当前输入仍保留，请修改后再保存。");
  const result: Record<string,unknown>={};
  for(const key of ["title","categoryCode","audienceStageCode","areaCode","goal","serviceMode","budgetMin","budgetMax","durationHours","availabilityNote","expectedStartDate","tutorExpectations"] as const) result[key]=form[key]||null;
  result.frequency=form.frequencyMode?{mode:form.frequencyMode,count:form.frequencyMode==="PER_WEEK"&&form.frequencyCount!==""?Number(form.frequencyCount):null}:null;
  result.availableSlots=form.availableSlots.map(s=>({weekday:Number(s.weekday),startMinute:minute(s.start,false),endMinute:minute(s.end,true)}));
  return result;
}
async function run(action: ()=>Promise<void>|void) {
  if(busy.value)return; busy.value=true; message.value=""; error.value="";fieldErrors.value={};
  try { await action(); }
  catch(e) {
    error.value=e instanceof Error?e.message:"操作未完成，请重试";
    if(e instanceof ApiFailure) {
      fieldErrors.value=e.fieldErrors;
      if(["VERSION_CONFLICT","REQUEST_READ_ONLY","REVISION_NOT_ALLOWED"].includes(e.code))conflict.value=true;
      if(e.code==="SESSION_CHANGED" || e.code==="LOGIN_REQUIRED")emit("expired");
    }
  } finally {busy.value=false;}
}
async function focusField(name:string){
  if(name==='displayName'){emit("profile");return;}
  step.value=name==='publisherDeclaration'?3:["budgetMin","budgetMax","durationHours","frequency","availableSlots","availabilityNote","expectedStartDate"].includes(name)?2:1;
  reached.value=Math.max(reached.value,step.value);await nextTick();document.getElementById("draft-"+name)?.focus();
}
async function list(append=false) {
  const [data,config]=await Promise.all([api.request<{items:Saved[];nextCursor:string|null}>("/me/requests"+(append&&nextCursor.value?"?cursor="+encodeURIComponent(nextCursor.value):"")),api.request<Options>("/publishing-options")]);
  options.value=config; items.value=append?[...items.value,...data.items]:data.items; nextCursor.value=data.nextCursor; view.value="list";
}
async function create() { await run(async()=>{creationKey??=crypto.randomUUID(); const saved=await api.request<Saved>("/requests","POST",undefined,creationKey); creationKey=null; load(saved);step.value=1;reached.value=1;view.value="edit";}); }
async function open(id: string) { await run(async()=>{const saved=await api.request<Saved>("/requests/"+id);load(saved);step.value=1;reached.value=2;view.value="edit";}); }
async function persist() {
  if(!current.value)throw new Error("请重新打开草稿。");
  if(conflict.value)throw new Error("请先读取并核对最新草稿，当前输入仍保留。");
  const saved=await api.request<Saved>(`/requests/${current.value.id}/draft`,"PATCH",{expectedVersion:current.value.version,fields:fields()});
  load(saved);
}
async function save(next=false) {await run(async()=>{await persist();message.value="草稿已保存，尚未提交审核。";if(next){step.value=Math.min(3,step.value+1);reached.value=Math.max(reached.value,step.value);}});}
function requestLeave(action: ()=>Promise<void>|void) {
  if(busy.value)return;
  if(dirty.value){leaveAction=action;confirmLeave.value=true;}else void run(action);
}
async function leave(saveFirst:boolean) {await run(async()=>{if(saveFirst)await persist();const action=leaveAction;leaveAction=null;confirmLeave.value=false;if(action)await action();});}
async function readLatest() { await run(async()=>{ if(current.value)latest.value=await api.request<Saved>("/requests/"+current.value.id);message.value="最新保存内容已读取，当前输入仍保留。"; }); }
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
async function refresh() {await run(async()=>{if(current.value)load(await api.request<Saved>(`/requests/${current.value.id}`));});}
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value){event.preventDefault();event.returnValue="";}}
onMounted(()=>{window.addEventListener("beforeunload",beforeUnload);void run(()=>list());});
onBeforeUnmount(()=>window.removeEventListener("beforeunload",beforeUnload));
defineExpose({requestLeave});
</script>

<template>
  <section class="draft-page">
    <div class="page-heading"><div><p class="eyebrow">MY REQUESTS</p><h1>{{ view==='list'?'我的需求':current?.status==='DRAFT'?'填写需求草稿':'需求进度' }}</h1><p>草稿手动保存；明确提交并审核通过后才会公开。</p></div><button v-if="view==='list'" class="primary" :disabled="busy" @click="create">新建需求草稿</button><button v-else class="secondary" :disabled="busy" @click="requestLeave(()=>list())">返回我的需求</button></div>
    <div v-if="error" class="message error" role="alert"><p>{{ error }}</p><ul v-if="Object.keys(fieldErrors).length"><li v-for="(value,name) in fieldErrors" :key="name"><button type="button" class="text-button" @click="focusField(String(name))">{{ fieldNames[name]||name }}：{{ value }} · 返回填写</button></li></ul></div><div v-if="message" class="message success" role="status">{{ message }}</div>
    <template v-if="view==='list'">
      <p v-if="!items.length" class="form-card">{{ busy?'正在读取需求…':'暂时没有已保存的需求，点击“新建需求草稿”开始填写。' }}</p>
      <article v-for="item in items" :key="item.id" class="form-card draft-list-item"><div><h2>{{ item.fields.title||'未命名草稿' }}</h2><p>{{ statusNames[item.status] }}{{ item.status==='DRAFT'?' · 仅本人可见':'' }}</p><small>最近保存：{{ new Date(item.lastSavedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</small><p v-if="item.retentionReminder" class="inline-warning">这份草稿长期未保存，7天内可能被清理；继续编辑并保存可保留。</p></div><button class="secondary" :disabled="busy" @click="open(item.id)">{{ item.status==='DRAFT'?'继续填写':'查看进度' }}</button></article>
      <button v-if="nextCursor" class="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多</button><button v-if="error" class="secondary" :disabled="busy" @click="run(()=>list())">重新读取</button>
    </template>
    <article v-else-if="current && current.status!=='DRAFT'" class="form-card">
      <p class="pill">{{ statusNames[current.status] }}</p>
      <p v-if="current.status==='PENDING_REVIEW'" class="privacy-note">正在等待运营审核，当前仅本人可见。待审期间不可编辑或撤回。</p>
      <p v-if="current.status==='OPEN'" class="privacy-note">已审核通过，当前招募中。公开内容为本次提交版本，不能直接修改或暂停。</p>
      <p v-if="current.status==='EXPIRED'" class="privacy-note">需求已到期，不再公开。你可以主动修改后重新提交并等待审核。</p>
      <p v-if="current.latestSubmission?.reviewFeedback" class="message warning">驳回理由：{{ current.latestSubmission.reviewFeedback }}</p>
      <RequestSummary :fields="current.latestSubmission?.fields??current.fields" :display-name="current.latestSubmission?.publisherDisplayName" />
      <details v-if="current.latestSubmission"><summary>本次提交声明（仅本人及审核人员可见）</summary><p>{{ declarationNames[current.latestSubmission.publisherDeclaration] }}</p></details>
      <div class="form-actions"><button class="secondary" :disabled="busy" @click="refresh">刷新进度</button><button v-if="['REJECTED','EXPIRED'].includes(current.status)" class="primary" :disabled="busy" @click="revise">修改后重新提交</button></div>
    </article>
    <template v-else-if="current">
      <details v-if="current.latestSubmission" class="form-card"><summary>上次提交及反馈</summary><p v-if="current.latestSubmission.reviewFeedback">驳回理由：{{ current.latestSubmission.reviewFeedback }}</p><RequestSummary :fields="current.latestSubmission.fields" :display-name="current.latestSubmission.publisherDisplayName" /><p>上次内容会保留，本次修改不会覆盖该提交。</p></details>
      <nav class="draft-steps" aria-label="填写步骤"><button v-for="(title,index) in ['基本信息','时间预算','草稿预览']" :key="title" :aria-current="step===index+1?'step':undefined" :disabled="busy || index+1>reached || index===2" @click="step=index+1">{{ index+1 }}. {{ title }}</button></nav>
      <div v-if="conflict||latest" class="message warning"><p>草稿有新版本，请核对后继续。当前输入未被覆盖。</p><button class="secondary" :disabled="busy" @click="readLatest">读取最新草稿</button><template v-if="latest"><details><summary>查看最新保存内容</summary><pre>{{ JSON.stringify(latest.fields,null,2) }}</pre></details><button class="secondary" :disabled="busy" @click="acceptLatest(false)">{{ latest.status==='DRAFT'?'载入最新内容':'查看已提交状态，放弃当前输入' }}</button><button v-if="latest.status==='DRAFT'" class="secondary" :disabled="busy" @click="acceptLatest(true)">已核对，保留输入</button></template></div>
      <form class="form-card draft-form" @submit.prevent="save(false)"><fieldset :disabled="busy">
        <template v-if="step===1">
          <label>需求标题<input id="draft-title" v-model="form.title" placeholder="例如：寻找周末绘画指导"><small>提交前必填，最多60个字符。</small></label>
          <label>服务类别<select id="draft-categoryCode" v-model="form.categoryCode"><option value="">暂未选择</option><option v-for="code in options.categories" :key="code" :value="code">{{ categoryNames[code]||code }}</option></select><small v-if="!options.categories.length">当前没有开放的类别，可先保存其他内容。</small></label>
          <label>服务对象阶段<select id="draft-audienceStageCode" v-model="form.audienceStageCode"><option value="">暂未选择</option><option v-for="code in options.audienceStages" :key="code" :value="code">{{ stages[code]||code }}</option></select></label>
          <label>坪山区街道<select id="draft-areaCode" v-model="form.areaCode"><option value="">暂未选择</option><option v-for="code in options.streets" :key="code" :value="code">{{ areaNames[code]||code }}街道</option></select><small>深圳市坪山区，不收精确住址。</small></label>
          <label>目标与现状<textarea id="draft-goal" v-model="form.goal" rows="6" placeholder="希望达到什么目标，目前有哪些基础？"></textarea><small>最多2000个字符，勿填儿童实名、学校班级、联系方式。</small></label>
          <label>服务方式<select id="draft-serviceMode" v-model="form.serviceMode"><option value="">留待沟通</option><option v-for="code in options.serviceModes" :key="code" :value="code">{{ modes[code]||code }}</option></select></label>
          <label>对老师的期待 <span class="optional">选填</span><textarea id="draft-tutorExpectations" v-model="form.tutorExpectations" rows="3"></textarea><small>最多500个字符。</small></label>
        </template>
        <template v-else-if="step===2">
          <fieldset class="price-fields"><legend>预算区间 · 元/小时</legend><div class="price-inputs"><label>预算下限<input id="draft-budgetMin" v-model="form.budgetMin" inputmode="decimal" placeholder="例如 100"></label><span>至</span><label>预算上限<input id="draft-budgetMax" v-model="form.budgetMax" inputmode="decimal" placeholder="例如 200"></label></div><p class="field-help">草稿可先填一端；提交前需两端都填写，金额大于0，最多两位小数。</p></fieldset>
          <label>单次时长<select id="draft-durationHours" v-model="form.durationHours"><option value="">暂未选择</option><option v-for="hours in durations" :key="hours" :value="hours">{{ hours }} 小时</option></select></label>
          <label>频率<select id="draft-frequency" v-model="form.frequencyMode"><option value="">暂未选择</option><option value="PER_WEEK">每周固定次数</option><option value="NEGOTIABLE">可协商</option></select></label><label v-if="form.frequencyMode==='PER_WEEK'">每周次数<input v-model="form.frequencyCount" inputmode="numeric" placeholder="1—14"></label>
          <fieldset id="draft-availableSlots" tabindex="-1" class="slot-fields"><legend>可约时段</legend><p class="field-help">最多21项，同日不重叠；跨午夜拆开，24:00只用于结束。</p><div v-for="(slot,index) in form.availableSlots" :key="index" class="slot-row"><label>星期<select v-model="slot.weekday"><option v-for="(day,i) in days" :key="day" :value="i+1">{{ day }}</option></select></label><label>开始<input v-model="slot.start" inputmode="numeric" placeholder="09:00"></label><label>结束<input v-model="slot.end" inputmode="numeric" placeholder="10:00"></label><button type="button" class="text-button danger" :aria-label="`删除第${index+1}项时段`" @click="form.availableSlots.splice(index,1)">删除</button></div><button type="button" class="secondary" :disabled="form.availableSlots.length>=21" @click="form.availableSlots.push({weekday:1,start:'09:00',end:'10:00'})">添加时段</button></fieldset>
          <label>时间备注 <span class="optional">选填</span><textarea id="draft-availabilityNote" v-model="form.availabilityNote" rows="3"></textarea><small>最多300个字符。</small></label>
          <label>期望开始日期 <span class="optional">选填</span><input id="draft-expectedStartDate" v-model="form.expectedStartDate" type="date"><small>不填表示时间可协商；具体安排仍需双方沟通。</small></label>
        </template>
        <div v-else class="draft-preview">
          <RequestSummary :fields="current.fields" />
          <p class="privacy-note">当前是已保存的草稿，尚未提交审核，也未公开。</p>
          <fieldset id="draft-publisherDeclaration" tabindex="-1" class="publisher-declaration"><legend>本次发布声明 · 提交必选</legend>
            <label v-for="item in declarations" :key="item.code" class="check-line"><input v-model="declaration" type="radio" :value="item.code"><span>{{ item.label }}</span></label>
            <p class="field-help">每次提交都需主动选择。该声明仅本人及审核人员可见。</p>
          </fieldset>
          <button type="button" class="primary" :disabled="!declaration||dirty||conflict" @click="submit">确认提交审核</button>
          <button type="button" class="text-button" @click="refresh">重新读取保存状态</button>
        </div>
        <div class="form-actions"><button v-if="step>1" type="button" class="secondary" @click="step--">上一步</button><button class="secondary" :disabled="conflict">保存草稿</button><button v-if="step<3" type="button" class="primary" :disabled="conflict" @click="save(true)">{{ step===1?'保存并下一步':'保存并预览' }}</button><button v-else type="button" class="primary" @click="requestLeave(()=>list())">返回我的需求</button><span class="save-state">{{ dirty?'有尚未保存的修改':'当前为已保存内容' }}</span></div>
      </fieldset></form>
    </template>
    <dialog ref="leaveDialog" class="native-dialog" aria-labelledby="leave-title" @cancel.prevent="confirmLeave=false"><section class="dialog-card"><h2 id="leave-title">还有未保存的修改</h2><p>离开前，可以先保存当前草稿，或继续填写。</p><p v-if="error" class="inline-warning" role="alert">{{ error }}</p><div class="form-actions"><button class="secondary" :disabled="busy" @click="confirmLeave=false">继续编辑</button><button class="primary" :disabled="busy" @click="leave(true)">保存并离开</button><button class="text-button danger" :disabled="busy" @click="leave(false)">放弃修改并离开</button></div></section></dialog>
  </section>
</template>

<style scoped>
.draft-page{max-width:900px;margin:auto}.draft-list-item{display:flex;justify-content:space-between;align-items:center;gap:24px;margin-bottom:18px}.draft-list-item h2{font-size:20px}.draft-list-item p{color:var(--muted);font-size:13px;margin:8px 0}.draft-steps{display:flex;gap:12px;margin-bottom:24px;flex-wrap:wrap}.draft-steps button{padding:12px 18px;border-radius:8px;background:#eaf0e5;color:var(--green)}.draft-steps button[aria-current=step]{background:var(--green);color:white}.draft-form label,.draft-form fieldset fieldset{margin-bottom:25px}.draft-form select{display:block;width:100%;margin-top:7px;padding:12px;border:1px solid #d7dfd3;background:white;color:inherit;border-radius:8px;font:inherit}.slot-row{display:grid;grid-template-columns:1fr 1fr 1fr auto;align-items:center;gap:12px}.slot-row label{min-width:0}.slot-row button{margin-top:7px}.draft-preview dl{display:grid;grid-template-columns:120px 1fr;gap:14px;margin:24px 0}.draft-preview dt{color:var(--muted)}.draft-preview dd{margin:0;overflow-wrap:anywhere}.draft-page pre{white-space:pre-wrap;overflow-wrap:anywhere}.draft-preview p{overflow-wrap:anywhere}.draft-form input{max-width:100%}@media(max-width:520px){.draft-list-item{align-items:flex-start;flex-direction:column}.draft-page .page-heading{flex-wrap:wrap}.draft-steps{gap:8px}.draft-steps button{font-size:12px;padding:10px}.slot-row{grid-template-columns:1fr 1fr;gap:8px}.slot-row label{margin-bottom:10px}.draft-preview dl{grid-template-columns:90px 1fr;font-size:13px}.draft-page h1{font-size:25px}}
</style>
