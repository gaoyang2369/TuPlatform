<script setup lang="ts">
import {computed,nextTick,onMounted,ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me} from "./account-api.ts";
import {matchLabel} from "./match-types.ts";
import type {Match} from "./match-types.ts";
import RequestSummary from "./RequestSummary.vue";
import TeacherSummary from "./TeacherSummary.vue";
import MatchComposer from "./MatchComposer.vue";
import ContactConsent from "./ContactConsent.vue";
import ConfirmDialog from './ConfirmDialog.vue';
import ContactExchangeNotice from './ContactExchangeNotice.vue';
import type {Contacts} from './contact-types.ts';
import {updateQuery,restoreScroll} from "./navigation.ts";
const props=defineProps<{me:Me;initialId?:string;requestId?:string}>();
const emit=defineEmits<{expired:[];profile:[];requests:[];browse:[];contacts:[];changed:[]}>();
const items=ref<Match[]>([]),detail=ref<Match|null>(null),cursor=ref<string|null>(null),state=ref(new URLSearchParams(location.search).get("state")||"");
const waitingForMe=computed(()=>items.value.filter(m=>m.waitingForMe));
const orderedItems=computed(()=>[...items.value].sort((a,b)=>Number(b.waitingForMe)-Number(a.waitingForMe)));
const busy=ref(false),error=ref(""),unknown=ref(false),conflict=ref(false),responseSaved=ref(false);
let selectedId=props.initialId;
let pending:{path:string;body:unknown;key:string}|null=null;
const confirmAction=ref(''),contacts=ref<Contacts|null>(null);
const actionNames:Record<string,string>={accept:'接受意向',reject:'婉拒意向','withdraw-intent':'撤回意向'};
const filters=[{value:'',label:'全部'},{value:'WAITING',label:'待回应'},{value:'MUTUAL',label:'已互选'},{value:'REJECTED',label:'已拒绝'},{value:'WITHDRAWN',label:'已撤回'}];
function partner(m:Match){return m.myRole==='REQUESTER'?m.teacherSnapshot.displayName:m.submissionSnapshot.publisherDisplayName;}
function source(m:Match){return m.requestId?m.submissionSnapshot.title:'直接邀请';}
async function ask(action:string,event:MouseEvent){const trigger=event.currentTarget as HTMLElement;await run(async()=>{contacts.value=null;if(action==='accept')contacts.value=await api.request<Contacts>('/me/contacts');});if(error.value)return;await nextTick();trigger.focus();confirmAction.value=action;}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作失败，请重试";if(e instanceof ApiFailure){if(e.status===401||e.code==="SESSION_CHANGED")emit("expired");if(e.status===409)conflict.value=true;}}finally{busy.value=false;}}
async function list(append=false){const q=new URLSearchParams();if(state.value)q.set("state",state.value);if(props.requestId)q.set("requestId",props.requestId);if(append&&cursor.value)q.set("cursor",cursor.value);const result=await api.request<{items:Match[];nextCursor:string|null}>("/me/matches?"+q);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;}
async function read(id:string){const current=await api.request<Match>("/matches/"+encodeURIComponent(id));detail.value=current;selectedId=id;pending=null;unknown.value=false;conflict.value=false;responseSaved.value=false;}
async function open(id:string){if(busy.value)return;updateQuery({view:"matches",match:id});detail.value=null;await run(()=>read(id));window.scrollTo({top:0,behavior:"instant"});}
async function refresh(){detail.value=null;await run(async()=>{if(selectedId)await read(selectedId);else await list();});}
async function respond(action:string){await run(async()=>{
  if(!pending){if(!detail.value||confirmAction.value!==action||(action==='accept'&&!contacts.value))return;pending={path:`/matches/${detail.value.id}/${action}`,body:{roundId:detail.value.roundId,expectedVersion:detail.value.version,...(action==='accept'?{contactExchange:{agree:true,expectedVersion:contacts.value!.version}}:{})},key:crypto.randomUUID()};}
  confirmAction.value='';contacts.value=null;
  // Keep the same receipt until the follow-up read succeeds, including when only that GET fails.
  try{const result=await api.request<Match>(pending.path,"POST",pending.body,pending.key);detail.value=result;responseSaved.value=true;await read(result.id);emit("changed");}
  catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
async function back(){updateQuery({match:undefined});selectedId=undefined;detail.value=null;pending=null;unknown.value=false;conflict.value=false;await run(()=>list());}
async function filter(){await run(()=>list());if(!error.value)updateQuery({state:state.value||undefined});}
onMounted(async()=>{await refresh();await nextTick();restoreScroll();});
</script>
<template><section class="matches" :aria-busy="busy">
  <header class="page-heading"><div><p class="eyebrow">MY MATCHES</p><h1>撮合进度</h1><p>{{selectedId?'查看回应，继续沟通。':'与合适的人，聊聊下一步。'}}</p></div><div class="heading-actions"><button v-if="selectedId" class="text-button" :disabled="busy" @click="back">返回列表</button><button class="text-button refresh-button" aria-label="读取最新进度" :disabled="busy" @click="refresh"><span aria-hidden="true">↻</span> {{busy?'更新中':'刷新'}}</button></div></header>
  <p v-if="error" class="message error" role="alert">{{error}}</p><p v-if="unknown" class="message warning">{{responseSaved?'回应已保存，最新进度暂未更新。':'上次结果尚未确认。'}}<button class="text-button" :disabled="busy" @click="respond('')">重试同一次回应</button></p><p v-if="conflict" class="message warning">进度或资料已变化，请刷新后重试。</p>
  <article v-if="detail" class="form-card match-detail">
    <div class="person-heading"><span class="person-avatar" aria-hidden="true">{{[...partner(detail)][0]}}</span><div><p class="record-source">{{source(detail)}}</p><h2>{{partner(detail)}}</h2></div><span class="status-pill" :class="{mutual:detail.state==='MUTUAL',attention:detail.waitingForMe}">{{matchLabel(detail)}}</span></div>
    <div v-if="detail.state==='WAITING'" class="response-bar"><div><h3>{{detail.waitingForMe?'期待你的回应':'意向已送达'}}</h3><p>{{detail.waitingForMe?'合适的话，接受后就可以联系沟通。':'对方接受后，即可查看彼此的联系方式。'}}</p></div><div class="form-actions"><button v-if="detail.canAccept" class="primary" :disabled="busy||unknown||conflict" @click="ask('accept',$event)">接受意向</button><button v-if="detail.canReject" class="text-button" :disabled="busy||unknown||conflict" @click="ask('reject',$event)">婉拒</button><button v-if="detail.canWithdraw" class="text-button" :disabled="busy||unknown||conflict" @click="ask('withdraw-intent',$event)">撤回意向</button></div></div>
    <ContactConsent v-if="detail.state==='MUTUAL'" :key="detail.roundId" :match-id="detail.id" :round-id="detail.roundId" @expired="emit('expired')" @contacts="emit('contacts')"/>
    <section v-if="!detail.requestId&&detail.submissionSnapshot.introduction" class="invitation-summary"><h3>邀请留言</h3><p class="pre-wrap">{{detail.submissionSnapshot.introduction}}</p></section>
    <p v-if="detail.requestExpired||!detail.teacherPublic||detail.submissionChanged||detail.teacherProfileChanged" class="record-note">{{detail.requestExpired?'原需求已到期。':''}}{{!detail.teacherPublic?'老师已停止公开。':''}}{{detail.submissionChanged||detail.teacherProfileChanged?'资料已有更新。':''}}本次联系记录保留。</p>
    <details class="record-details"><summary>查看发起时的{{detail.requestId?'需求与老师资料':'老师资料'}}</summary><div class="record-content"><template v-if="detail.requestId"><h3>发起时的需求</h3><RequestSummary :fields="detail.submissionSnapshot" :display-name="detail.submissionSnapshot.publisherDisplayName"/></template><h3>发起时的老师资料</h3><TeacherSummary :teacher="detail.teacherSnapshot"/></div></details>
    <MatchComposer v-if="detail.canInitiateAgain" :key="detail.roundId" :me="me" :request-id="detail.requestId??undefined" :teacher-id="detail.teacherSnapshot.id" @matched="open" @expired="emit('expired')" @profile="emit('profile')" @requests="emit('requests')"/>
    <p v-else-if="detail.state==='REJECTED'||detail.state==='WITHDRAWN'" class="record-note">{{detail.state==='REJECTED'?'本次意向已婉拒，可继续寻找其他合适的对象。':'本次意向已撤回。'}}</p>
  </article>
  <template v-else-if="!selectedId">
    <div class="match-toolbar"><div class="state-filters" role="group" aria-label="筛选进度"><button v-for="f in filters" :key="f.value" :aria-pressed="state===f.value" :disabled="busy" @click="state=f.value;filter()">{{f.label}}</button></div><span v-if="waitingForMe.length" class="reply-count" role="status">{{waitingForMe.length}} 条待你回应{{cursor?'（已加载）':''}}</span></div>
    <p v-if="!items.length&&!error" class="empty-state">{{busy?'正在读取…':state?'暂无此状态的联系记录':'还没有联系记录'}}<button v-if="!busy" class="text-button" @click="emit('browse')">{{me.businessIdentity==='TUTOR'?'去浏览需求':'去发现老师'}} →</button></p>
    <div class="match-list"><article v-for="m in orderedItems" :key="m.id" class="form-card match-row"><span class="person-avatar" aria-hidden="true">{{[...partner(m)][0]}}</span><div class="row-content"><div class="row-heading"><h2>{{partner(m)}}</h2><span class="status-pill" :class="{mutual:m.state==='MUTUAL',attention:m.waitingForMe}">{{matchLabel(m)}}</span></div><p>{{source(m)}}</p></div><button :class="m.waitingForMe?'primary':'secondary'" :disabled="busy" :aria-label="`查看${partner(m)}的撮合进度`" @click="open(m.id)">{{m.waitingForMe?'去回应':m.state==='MUTUAL'?'去联系':'查看'}} <span aria-hidden="true">→</span></button></article></div>
    <button v-if="cursor" class="secondary load-more" :disabled="busy" @click="run(()=>list(true))">加载更多进度</button>
  </template><p v-else-if="busy" class="empty-state" role="status">正在读取联系记录…</p>
  <ConfirmDialog :open="!!confirmAction" :title="`${actionNames[confirmAction]||''}？`" :confirm-text="actionNames[confirmAction]" :busy="busy" @cancel="confirmAction='';contacts=null" @confirm="respond(confirmAction)"><template v-if="confirmAction==='accept'"><p>接受后，与{{detail?partner(detail):'对方'}}开始沟通。</p><ContactExchangeNotice v-if="contacts" :contacts="contacts" action="接受意向"/></template><p v-else>{{confirmAction==='reject'?'婉拒后，本次意向将结束。':'撤回后，对方将无法接受本次意向。'}}</p></ConfirmDialog>
</section></template>
<style scoped>
.matches{max-width:900px;margin:auto}.matches .page-heading{align-items:center;margin-bottom:24px}.heading-actions{display:flex;align-items:center;gap:20px;flex:none}.heading-actions button{min-height:40px}.refresh-button span{font-size:20px;vertical-align:-1px;margin-right:4px}.match-toolbar{display:flex;justify-content:space-between;align-items:center;gap:14px;flex-wrap:wrap;margin-bottom:18px}.state-filters{display:flex;flex-wrap:wrap;gap:6px}.state-filters button{min-height:40px;padding:8px 15px;background:transparent;color:var(--muted);border-radius:8px;font-size:14px}.state-filters button[aria-pressed=true]{background:#e5eddf;color:var(--green);font-weight:600}.reply-count{font-size:13px;color:var(--green)}.match-list{display:grid;gap:12px}.matches .form-card{overflow-wrap:anywhere;box-shadow:none}.match-row{display:flex;align-items:center;gap:18px;padding:22px 24px;border-radius:14px}.person-avatar{display:grid;place-items:center;flex:none;width:46px;height:46px;border-radius:14px;background:#edf2e6;color:var(--green);font-size:20px}.row-content{flex:1;min-width:0}.row-heading{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.row-heading h2{font-size:19px}.row-content>p,.record-source{color:var(--muted);font-size:13px;margin-top:6px}.status-pill{display:inline-block;font-size:12px;color:#6b746b;background:#f0f2ed;padding:4px 10px;border-radius:6px;flex:none}.status-pill.mutual{color:#31563c;background:#e8f1df}.status-pill.attention{color:#81632e;background:#faf1dc}.match-row>button{flex:none;gap:12px;padding:8px 16px}.match-detail{padding:28px}.person-heading{display:flex;align-items:center;gap:14px}.person-heading>div{flex:1;min-width:0}.person-heading h2{font-size:24px}.person-heading .record-source{margin:0 0 2px}.response-bar{display:flex;justify-content:space-between;align-items:center;gap:18px;padding:20px;margin-top:24px;border-radius:12px;background:#f0f5eb}.response-bar h3,.invitation-summary h3{font-size:16px}.response-bar p{font-size:13px;color:var(--muted);margin-top:5px}.response-bar .form-actions{flex:none;gap:18px}.invitation-summary{margin-top:24px}.invitation-summary p{font-size:14px;line-height:1.8;margin-top:8px}.record-details{margin-top:24px;border-top:1px solid var(--line)}.record-details summary{padding:17px 0 0;font-size:14px;color:var(--muted);cursor:pointer}.record-content{padding:20px 0 0}.record-content>h3{font-size:17px;margin:16px 0}.record-note{font-size:12px;color:var(--muted);margin-top:20px}.empty-state{text-align:center;padding:50px 20px;border:1px dashed var(--line);border-radius:14px;color:var(--muted)}.empty-state button{display:block;margin:12px auto 0}.load-more{display:flex;margin:20px auto 0}
@media(max-width:600px){.heading-actions{gap:12px}.matches .page-heading{gap:8px}.match-row{padding:18px 16px;gap:12px;flex-wrap:wrap}.match-row .person-avatar{width:38px;height:38px;font-size:17px}.row-heading{gap:7px}.row-heading h2{font-size:17px}.match-row>button{margin-left:50px;padding:7px 15px;min-height:40px}.match-detail{padding:20px 18px}.person-heading{flex-wrap:wrap;gap:12px}.person-heading h2{font-size:21px}.person-heading>.status-pill{margin-left:58px}.response-bar{align-items:stretch;flex-direction:column;padding:16px;margin-top:20px}.response-bar .form-actions{flex-wrap:nowrap}.response-bar .form-actions .primary{width:auto;flex:1}.state-filters{gap:2px}.state-filters button{padding:8px 12px;font-size:13px}.match-toolbar{gap:8px}}
</style>
