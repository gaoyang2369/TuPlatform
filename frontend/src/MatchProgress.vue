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
import {updateQuery,restoreScroll} from "./navigation.ts";
const props=defineProps<{me:Me;initialId?:string;requestId?:string}>();
const emit=defineEmits<{expired:[];profile:[];requests:[];browse:[];contacts:[];changed:[]}>();
const items=ref<Match[]>([]),detail=ref<Match|null>(null),cursor=ref<string|null>(null),state=ref(new URLSearchParams(location.search).get("state")||"");
const waitingForMe=computed(()=>items.value.filter(m=>m.waitingForMe));
const orderedItems=computed(()=>[...items.value].sort((a,b)=>Number(b.waitingForMe)-Number(a.waitingForMe)));
const busy=ref(false),error=ref(""),unknown=ref(false),conflict=ref(false),responseSaved=ref(false);
let selectedId=props.initialId;
let pending:{path:string;body:unknown;key:string}|null=null;
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作失败，请重试";if(e instanceof ApiFailure){if(e.status===401||e.code==="SESSION_CHANGED")emit("expired");if(e.status===409)conflict.value=true;}}finally{busy.value=false;}}
async function list(append=false){const q=new URLSearchParams();if(state.value)q.set("state",state.value);if(props.requestId)q.set("requestId",props.requestId);if(append&&cursor.value)q.set("cursor",cursor.value);const result=await api.request<{items:Match[];nextCursor:string|null}>("/me/matches?"+q);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;}
async function read(id:string){const current=await api.request<Match>("/matches/"+encodeURIComponent(id));detail.value=current;selectedId=id;pending=null;unknown.value=false;conflict.value=false;responseSaved.value=false;}
async function open(id:string){if(busy.value)return;updateQuery({view:"matches",match:id});detail.value=null;await run(()=>read(id));window.scrollTo({top:0,behavior:"instant"});}
async function refresh(){detail.value=null;await run(async()=>{if(selectedId)await read(selectedId);else await list();});}
async function respond(action:string){await run(async()=>{
  if(!pending){if(!detail.value)return;const messages:Record<string,string>={accept:"确认接受本次意向？互选不会自动分享联系方式，也不产生预约或合作。",reject:"确认拒绝本次意向？对方不能对本提交版本再次发起，你仍可主动改变主意重新发起。","withdraw-intent":"确认撤回本人发起的待回应意向？撤回后对方不能接受本轮意向。"};if(!window.confirm(messages[action]))return;pending={path:`/matches/${detail.value.id}/${action}`,body:{roundId:detail.value.roundId,expectedVersion:detail.value.version},key:crypto.randomUUID()};}
  // Keep the same receipt until the follow-up read succeeds, including when only that GET fails.
  try{const result=await api.request<Match>(pending.path,"POST",pending.body,pending.key);detail.value=result;responseSaved.value=true;await read(result.id);emit("changed");}
  catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
async function back(){updateQuery({match:undefined});selectedId=undefined;detail.value=null;pending=null;unknown.value=false;conflict.value=false;await run(()=>list());}
async function filter(){await run(()=>list());if(!error.value)updateQuery({state:state.value||undefined});}
onMounted(async()=>{await refresh();await nextTick();restoreScroll();});
</script>
<template><section class="matches"><div class="page-heading"><div><p class="eyebrow">MY MATCHES</p><h1>撮合进度</h1><p>发送意向 → 对方回应 → 双方同意交换联系方式，再到站外沟通。</p></div><button v-if="selectedId" class="secondary" :disabled="busy" @click="back">返回撮合列表</button></div>
  <div v-if="error" class="message error" role="alert">{{ error }}</div><p v-if="unknown" class="message warning">{{ responseSaved?'本次回应已成功，但最新进度未能刷新，可以重试或重新读取。':'上次结果尚未确认，可以重试或读取最新进度。' }}</p><p v-if="conflict" class="message warning">进度或资料已变化，先读取最新内容并核对，再决定下一步。</p>
  <button class="secondary" :disabled="busy" @click="refresh">读取最新进度</button><button v-if="unknown" class="primary" :disabled="busy" @click="respond('')">重试同一次回应</button>
  <article v-if="detail" class="form-card"><div class="next-step"><h2>{{ matchLabel(detail) }}</h2><p>{{detail.waitingForMe?'下一步：核对下方资料，决定是否接受本次意向。':detail.state==='WAITING'?'意向已发出，等待对方回应。':detail.state==='MUTUAL'?'下一步：双方同意交换联系方式后，就可以联系沟通。':'这次意向已结束，可查看记录和可用的后续操作。'}}</p><div class="form-actions"><button v-if="detail.canAccept" class="primary" :disabled="busy||unknown||conflict" @click="respond('accept')">接受意向</button><button v-if="detail.canReject" class="secondary" :disabled="busy||unknown||conflict" @click="respond('reject')">拒绝意向</button><button v-if="detail.canWithdraw" class="secondary" :disabled="busy||unknown||conflict" @click="respond('withdraw-intent')">撤回本人意向</button></div></div><ContactConsent v-if="detail.state==='MUTUAL'" :key="detail.roundId" :match-id="detail.id" :round-id="detail.roundId" @expired="emit('expired')" @contacts="emit('contacts')"/><p v-if="detail.requestExpired" class="message warning">发起时的这版需求已到期，不再接收新意向。{{ detail.state==='WAITING'?'本次待回应意向仍可处理。':'当时资料继续保留。' }}</p><p v-if="!detail.teacherPublic" class="message warning">老师已停止公开，本次关系与当时资料保留。</p><p v-if="detail.submissionChanged" class="privacy-note">需求已有后续修订。本页保留本轮发起时内容，不复制原关系。</p><p v-if="detail.teacherProfileChanged" class="privacy-note">老师资料已有变化。本页保留当时内容，新意向请重新读取公开资料。</p>
    <h3>发起时的需求</h3><RequestSummary :fields="detail.submissionSnapshot" :display-name="detail.submissionSnapshot.publisherDisplayName"/><h3>发起时的老师资料</h3><TeacherSummary :teacher="detail.teacherSnapshot"/>
    <MatchComposer v-if="detail.canInitiateAgain" :key="detail.roundId" :me="me" :request-id="detail.requestId" :teacher-id="detail.teacherSnapshot.id" @matched="open" @expired="emit('expired')" @profile="emit('profile')" @requests="emit('requests')"/>
    <p v-else-if="detail.state==='REJECTED'||detail.state==='WITHDRAWN'" class="privacy-note">当前不能重新发起。新意向须需求有效公开、老师资料公开；被拒绝方不能对同一提交版本再次发起。</p>
  </article>
  <template v-else-if="!selectedId"><div v-if="items.length" class="next-step" role="status"><h2>需要我回应 · {{waitingForMe.length}}</h2><p>已加载 {{items.length}} 条意向，需要你回应的优先展示{{cursor?'；下方可继续加载更多':''}}。</p></div><form class="form-card filters" @submit.prevent="filter"><label>进度<select v-model="state"><option value="">全部进度</option><option value="WAITING">待回应</option><option value="MUTUAL">已互选</option><option value="REJECTED">已拒绝</option><option value="WITHDRAWN">已撤回</option></select></label><button class="secondary" :disabled="busy">筛选进度</button></form><p v-if="!items.length&&!error" class="form-card">{{ busy?'正在读取…':'暂无撮合进度。' }}<button class="text-button" @click="emit('browse')">{{ me.businessIdentity==='TUTOR'?'浏览需求':'发现老师' }}</button></p><article v-for="m in orderedItems" :key="m.id" class="form-card"><h2>{{ m.submissionSnapshot.title }}</h2><p>{{ m.teacherSnapshot.displayName }} · {{ matchLabel(m) }}</p><button class="secondary" :disabled="busy" @click="open(m.id)">查看撮合进度</button></article><button v-if="cursor" class="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多进度</button></template>
</section></template>
<style scoped>.matches{max-width:900px;margin:auto}.matches>.secondary{margin-bottom:20px}.matches .form-card{margin-bottom:20px;overflow-wrap:anywhere}.matches h3{margin-top:28px}.matches p{margin:14px 0;line-height:1.8}.filters{display:flex;gap:16px;align-items:end;flex-wrap:wrap}.filters select{display:block;padding:10px;margin-top:8px;border:1px solid var(--line);border-radius:8px;background:white;font:inherit}</style>
