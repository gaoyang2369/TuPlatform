<script setup lang="ts">
import {computed,nextTick,onMounted,ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me} from "./account-api.ts";
import {matchLabel, progressActions, type ProgressAction} from "./match-types.ts";
import type {Match} from "./match-types.ts";
import { ArrowLeft, ArrowRight, RefreshCw, MessagesSquare } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import UserAvatar from "./UserAvatar.vue";
import MatchLifecyclePanel from "./MatchLifecyclePanel.vue";
import MatchReviews from "./MatchReviews.vue";
import MatchStepper from "./MatchStepper.vue";
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
const shared = ref(false);
const selectedId = ref(props.initialId);
let pending:{path:string;body:unknown;key:string}|null=null;
const confirmAction=ref(''),contacts=ref<Contacts|null>(null);
const actionNames:Record<string,string>={accept:'接受意向',reject:'婉拒意向','withdraw-intent':'撤回意向',...Object.fromEntries(Object.entries(progressActions).map(([key,action])=>[key,action.label]))};
const filters=[{value:'',label:'全部'},{value:'WAITING',label:'待回应'},{value:'MUTUAL',label:'已互选'},{value:'in_cooperation',label:'合作中'},{value:'completed',label:'已完结'},{value:'closed_no_deal',label:'暂未合作'},{value:'REJECTED',label:'已拒绝'},{value:'WITHDRAWN',label:'已撤回'}];
function partner(m:Match){return m.myRole==='REQUESTER'?m.teacherSnapshot.displayName:m.submissionSnapshot.publisherDisplayName;}
function source(m:Match){return m.requestId?m.submissionSnapshot.title:'直接邀请';}
async function ask(action:string,event:MouseEvent){const trigger=event.currentTarget as HTMLElement;await run(async()=>{contacts.value=null;if(action==='accept')contacts.value=await api.request<Contacts>('/me/contacts');});if(error.value)return;await nextTick();trigger.focus();confirmAction.value=action;}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作失败，请重试";if(e instanceof ApiFailure){if(e.status===401||e.code==="SESSION_CHANGED")emit("expired");if(e.status===409)conflict.value=true;}}finally{busy.value=false;}}
async function list(append=false){const q=new URLSearchParams();if(state.value)q.set("state",state.value);if(props.requestId)q.set("requestId",props.requestId);if(append&&cursor.value)q.set("cursor",cursor.value);const result=await api.request<{items:Match[];nextCursor:string|null}>("/me/matches?"+q);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;}
async function read(id:string){const current=await api.request<Match>("/matches/"+encodeURIComponent(id));detail.value=current;selectedId.value=id;pending=null;unknown.value=false;conflict.value=false;responseSaved.value=false;}
async function open(id:string){if(busy.value)return;updateQuery({view:"matches",match:id});selectedId.value=id;shared.value=false;detail.value=null;await run(()=>read(id));window.scrollTo({top:0,behavior:"instant"});}
async function refresh(){shared.value=false;detail.value=null;await run(async()=>{if(selectedId.value)await read(selectedId.value);else await list();});}
async function respond(action:string){await run(async()=>{
  if(!pending){if(!detail.value||confirmAction.value!==action||(action==='accept'&&!contacts.value))return;pending={path:`/matches/${detail.value.id}/${action}`,body:{roundId:detail.value.roundId,expectedVersion:detail.value.version,...(action==='accept'?{contactExchange:{agree:true,expectedVersion:contacts.value!.version}}:{})},key:crypto.randomUUID()};}
  confirmAction.value='';contacts.value=null;
  // Keep the same receipt until the follow-up read succeeds, including when only that GET fails.
  try{const result=await api.request<Match>(pending.path,"POST",pending.body,pending.key);detail.value=result;responseSaved.value=true;await read(result.id);emit("changed");}
  catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
async function back(){updateQuery({match:undefined});selectedId.value=undefined;shared.value=false;detail.value=null;pending=null;unknown.value=false;conflict.value=false;await run(()=>list());}
async function filter(value: string) {
  await run(async () => {
    const previous = state.value;
    state.value = value;
    try { await list(); }
    catch (e) { state.value = previous; throw e; }
    updateQuery({state: value || undefined});
  });
}
onMounted(async()=>{await refresh();await nextTick();restoreScroll();});
</script>
<template>
<section class="space-y-5 text-foreground [overflow-wrap:anywhere] sm:space-y-7" :class="selectedId && 'mx-auto max-w-4xl'" :aria-busy="busy">
  <header class="flex flex-wrap items-center justify-between gap-3">
    <div class="space-y-1"><h1 class="text-3xl font-bold tracking-tight sm:text-[40px]">撮合进度</h1>
      <p class="text-[15px] text-secondary-foreground">{{ selectedId ? '查看回应，继续沟通。' : '查看收到和发出的意向，跟进每一次回应。' }}</p></div>
    <div class="flex gap-2"><Button v-if="selectedId" variant="ghost" :disabled="busy" @click="back"><ArrowLeft/>返回列表</Button>
      <Button variant="outline" aria-label="读取最新进度" :disabled="busy" @click="refresh"><RefreshCw/>{{ busy ? '更新中' : '刷新' }}</Button></div>
  </header>
  <p v-if="error" class="rounded-xl bg-red-50 p-4 text-sm text-red-800" role="alert">{{error}}</p><p v-if="unknown" class="rounded-xl bg-orange-50 p-4 text-sm text-orange-900">{{responseSaved?'回应已保存，最新进度暂未更新。':'上次结果尚未确认。'}}<Button variant="ghost" :disabled="busy" @click="respond('')">重试同一次回应</Button></p><p v-if="conflict" class="rounded-xl bg-orange-50 p-4 text-sm text-orange-900">进度或资料已变化，请刷新后重试。</p>
  <article v-if="detail" class="match-detail mx-auto max-w-4xl space-y-6 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-8">
    <div class="flex items-center gap-3"><UserAvatar :url="detail.avatar_url" :name="partner(detail)" class="size-11 rounded-xl text-xl"/><div><p class="text-sm text-muted-foreground">{{source(detail)}}</p><h2 class="text-xl font-bold">{{partner(detail)}}</h2></div><Badge variant="secondary" class="status-pill ml-auto" :class="detail.waitingForMe ? 'bg-orange-50 text-orange-800' : ''">{{matchLabel(detail)}}</Badge></div>
    <MatchStepper :state="detail.state" :shared="shared"/>
    <div v-if="detail.state==='WAITING'" class="flex flex-wrap items-center justify-between gap-4 rounded-xl bg-slate-50 p-4"><div><h3 class="font-semibold">{{detail.waitingForMe?'期待你的回应':'意向已送达'}}</h3><p class="mt-1 text-sm text-secondary-foreground">{{detail.waitingForMe?'合适的话，接受后就可以联系沟通。':'对方接受后，即可查看彼此的联系方式。'}}</p></div><div class="flex flex-wrap items-center gap-2"><Button v-if="detail.canAccept" :disabled="busy||unknown||conflict" @click="ask('accept',$event)">接受意向</Button><Button v-if="detail.canReject" variant="ghost" :disabled="busy||unknown||conflict" @click="ask('reject',$event)">婉拒</Button><Button v-if="detail.canWithdraw" variant="ghost" :disabled="busy||unknown||conflict" @click="ask('withdraw-intent',$event)">撤回意向</Button></div></div>
    <MatchLifecyclePanel :match="detail" :account-id="me.id" :shared="shared" :disabled="busy||unknown||conflict" @action="ask"/>
    <MatchReviews v-if="detail.state==='completed' || detail.hasPastReviews" :key="detail.roundId" :match="detail" @expired="emit('expired')"/>
    <ContactConsent :with-request-address="detail.myRole==='TUTOR' && !!detail.requestId" v-if="['MUTUAL','in_cooperation','completed'].includes(detail.state)" :key="detail.roundId" @visibility="shared=$event" :match-id="detail.id" :round-id="detail.roundId" @expired="emit('expired')" @contacts="emit('contacts')"/>
    <section v-if="!detail.requestId&&detail.submissionSnapshot.introduction" class="invitation-summary space-y-2"><h3 class="font-semibold">邀请留言</h3><p class="whitespace-pre-wrap text-[15px] leading-7">{{detail.submissionSnapshot.introduction}}</p></section>
    <p v-if="detail.requestExpired||!detail.teacherPublic||detail.submissionChanged||detail.teacherProfileChanged" class="record-note rounded-xl bg-slate-50 p-3 text-sm text-secondary-foreground">{{detail.requestExpired?'原需求已到期。':''}}{{!detail.teacherPublic?'老师已停止公开。':''}}{{detail.submissionChanged||detail.teacherProfileChanged?'资料已有更新。':''}}本次联系记录保留。</p>
    <details class="rounded-xl bg-slate-50 p-4 text-sm"><summary>查看发起时的{{detail.requestId?'需求与老师资料':'老师资料'}}</summary><div class="mt-4 space-y-4 rounded-xl bg-card p-4"><template v-if="detail.requestId"><h3>发起时的需求</h3><RequestSummary :fields="detail.submissionSnapshot" :display-name="detail.submissionSnapshot.publisherDisplayName"/></template><h3>发起时的老师资料</h3><TeacherSummary :teacher="detail.teacherSnapshot"/></div></details>
    <MatchComposer v-if="detail.canInitiateAgain" :key="detail.roundId" :me="me" :request-id="detail.requestId??undefined" :teacher-id="detail.teacherSnapshot.id" @matched="open" @expired="emit('expired')" @profile="emit('profile')" @requests="emit('requests')"/>
    <p v-else-if="detail.state==='REJECTED'||detail.state==='WITHDRAWN'" class="record-note rounded-xl bg-slate-50 p-3 text-sm text-secondary-foreground">{{detail.state==='REJECTED'?'本次意向已婉拒，可继续寻找其他合适的对象。':'本次意向已撤回。'}}</p>
  </article>
  <template v-else-if="!selectedId">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div class="flex max-w-full gap-1 overflow-x-auto rounded-2xl border border-border bg-card p-1.5" role="group" aria-label="筛选进度">
        <Button v-for="f in filters" :key="f.value" variant="ghost" class="shrink-0 rounded-full px-3 aria-pressed:bg-blue-50 aria-pressed:text-primary" :aria-pressed="state===f.value" :disabled="busy" @click="filter(f.value)">{{ f.label }}</Button>
      </div>
      <span v-if="waitingForMe.length" class="reply-count rounded-full bg-orange-50 px-3 py-1 text-sm text-orange-800" role="status">{{waitingForMe.length}} 条待你回应{{cursor?'（已加载）':''}}</span>
    </div>
    <div v-if="!items.length && busy" role="status" class="space-y-3"><span class="sr-only">正在读取…</span><Skeleton class="h-40 w-full rounded-2xl"/></div>
    <div v-else-if="!items.length && !error" class="grid justify-items-center gap-3 rounded-2xl border border-border bg-card p-8 text-center"><MessagesSquare class="size-7 text-muted-foreground"/>
      <p>{{state?'暂无此状态的联系记录':'还没有联系记录'}}</p><Button variant="secondary" @click="emit('browse')">{{me.businessIdentity==='TUTOR'?'去浏览需求':'去发现老师'}}<ArrowRight/></Button>
    </div>
    <div class="grid gap-6 md:grid-cols-2">
      <article v-for="m in orderedItems" :key="m.id" class="match-row grid content-between gap-5 rounded-2xl border border-border bg-card p-5 shadow-sm transition hover:shadow-lg motion-safe:hover:-translate-y-0.5 sm:p-6">
        <div class="flex items-start gap-3"><UserAvatar :url="m.avatar_url" :name="partner(m)" class="size-11 rounded-xl text-xl"/>
          <div class="min-w-0 flex-1"><h2 class="text-xl font-bold">{{ partner(m) }}</h2><p class="text-sm text-secondary-foreground">{{ source(m) }}</p></div>
          <Badge variant="secondary" :class="m.waitingForMe && 'bg-orange-50 text-orange-800'">{{ matchLabel(m) }}</Badge>
        </div>
        <MatchStepper :state="m.state" :shared="m.contactsExchanged"/>
        <MatchReviews v-if="m.state==='completed' || m.hasPastReviews" :key="m.roundId" :match="m" @expired="emit('expired')"/>
        <Button :variant="m.waitingForMe ? 'default' : 'secondary'" class="h-10 justify-between rounded-xl" :disabled="busy" :aria-label="`查看${partner(m)}的撮合进度`" @click="open(m.id)">{{ m.waitingForMe ? '去回应' : m.state==='MUTUAL' ? '去联系' : '查看' }}<ArrowRight/></Button>
      </article>
    </div>
    <Button v-if="cursor" variant="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多进度</Button>
  </template><div v-else-if="busy" role="status" class="space-y-3"><p>正在读取联系记录…</p><Skeleton class="h-48 w-full rounded-2xl"/></div>
  <ConfirmDialog :open="!!confirmAction" :title="`${actionNames[confirmAction]||''}？`" :confirm-text="actionNames[confirmAction]" :busy="busy" @cancel="confirmAction='';contacts=null" @confirm="respond(confirmAction)"><template v-if="confirmAction==='accept'"><p>接受后，与{{detail?partner(detail):'对方'}}开始沟通。</p><ContactExchangeNotice v-if="contacts" :contacts="contacts" action="接受意向"/></template><p v-else-if="confirmAction==='reject'">婉拒后，本次意向将结束。</p><p v-else-if="confirmAction==='withdraw-intent'">撤回后，对方将无法接受本次意向。</p><p v-else>{{ progressActions[confirmAction as ProgressAction]?.description }}</p></ConfirmDialog>
</section></template>
