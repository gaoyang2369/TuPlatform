<script setup lang="ts">
import {computed,nextTick,ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me,Profile} from "./account-api.ts";
import type {Match,PublicRequest,PublicTeacher} from "./match-types.ts";
import TeacherSummary from "./TeacherSummary.vue";
import RequestSummary from "./RequestSummary.vue";
import ContactExchangeNotice from './ContactExchangeNotice.vue';
import type {Contacts} from './contact-types.ts';
import DiscoveryActionBar from "./DiscoveryActionBar.vue";
const props=defineProps<{drawer?:boolean;me:Me;requestId?:string;teacherId?:string}>();
const emit=defineEmits<{matched:[id:string];expired:[];profile:[];requests:[]}>();
const opened=ref(false),busy=ref(false),error=ref(""),needsProfile=ref(false);
const teacher=ref<PublicTeacher|null>(null),request=ref<PublicRequest|null>(null),introduction=ref('');
const direct=computed(()=>props.me.businessIdentity==='REQUESTER'&&!props.requestId);
const ready=ref(false),unknown=ref(false);
const contacts=ref<Contacts|null>(null);
let pending:{body:unknown;key:string}|null=null;
async function run(action:()=>Promise<void>) {if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作失败，请重试。";if(e instanceof ApiFailure){if(e.status===401||e.code==="SESSION_CHANGED")emit("expired");if(e.code==='PROFILE_REQUIRED')needsProfile.value=true;}}finally{busy.value=false;}}
async function load() {
  ready.value=false;teacher.value=null;request.value=null;needsProfile.value=false;
  if(props.me.businessIdentity==="TUTOR") {
    const p=await api.request<Profile>("/me/profile");
    if(!p.teacher?.public){needsProfile.value=true;throw new Error("请先补齐本人老师资料并主动公开，再投递需求。");}
    teacher.value={...p.teacher,id:props.me.id,avatar_url:p.avatar_url,displayName:p.displayName,profileVersion:p.version};
  } else {teacher.value=await api.request<PublicTeacher>("/teachers/"+encodeURIComponent(props.teacherId!));}
  const id=props.requestId;
  if(id)request.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(id));
  contacts.value=await api.request<Contacts>('/me/contacts');
  ready.value=true;pending=null;unknown.value=false;
}
const formTitle = ref<HTMLElement>(), openButton = ref<HTMLButtonElement>();
const primaryClass = computed(() => ["min-h-11 rounded-xl bg-primary px-5 font-semibold text-white hover:bg-primary-hover disabled:opacity-50", props.drawer && "min-h-12 w-full text-base"]);
async function open() {
  opened.value = true;
  void nextTick(() => formTitle.value?.focus());
  await run(load);
}
async function cancel() {
  opened.value = false;
  await nextTick();
  openButton.value?.focus({ preventScroll: true });
}
async function send(){await run(async()=>{
  if(!pending){
    if(!ready.value||!teacher.value||!contacts.value||(!direct.value&&!request.value))return;
    pending={key:crypto.randomUUID(),body:direct.value?{kind:'DIRECT_INVITATION',tutorUserId:teacher.value.id,expectedTeacherProfileVersion:teacher.value.profileVersion,introduction:introduction.value.trim()}:{requestId:request.value!.id,expectedSubmissionId:request.value!.submissionId,expectedTeacherProfileVersion:teacher.value.profileVersion,kind:props.me.businessIdentity==="TUTOR"?"APPLICATION":"INVITATION",...(props.me.businessIdentity==="REQUESTER"?{tutorUserId:teacher.value.id}:{})}};
    pending.body={...pending.body as object,contactExchange:{agree:true,expectedVersion:contacts.value.version}};
  }
  try{const result=await api.request<{match:Match;outcome:string}>("/matches","POST",pending.body,pending.key);pending=null;unknown.value=false;emit("matched",result.match.id);}
  catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
</script>
<template>
  <div :class="drawer ? 'drawer-composer flex min-h-0 flex-1 flex-col' : 'composer space-y-4 rounded-xl bg-slate-50 p-4'">
    <div :class="drawer && 'min-h-0 flex-1 overflow-y-auto overscroll-contain px-5 pb-5 sm:px-8'">
      <slot v-if="!opened"/>
      <p v-if="!drawer" class="mb-3 text-sm text-secondary-foreground">发出意向，对方接受后即可查看彼此的联系方式。</p>
      <section v-if="opened" class="space-y-4">
        <h3 ref="formTitle" tabindex="-1" class="text-xl font-bold outline-none">{{ direct ? '邀请老师聊一聊' : '核对本次意向' }}</h3>
        <p>{{ direct ? '无需先发布需求，直接向心仪的老师发出邀请。' : '请核对需求与老师资料。' }}已有进行中的联系时，会带你查看已有进度。</p>
        <div v-if="error" class="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{{ error }}</div>
        <button v-if="needsProfile" class="rounded-xl bg-slate-100 px-4 py-2 text-sm font-medium" @click="emit('profile')">{{ direct ? '完善本人展示名' : '完善并公开本人资料' }}</button>
        <p v-if="busy" role="status">{{ ready ? '正在发送意向…' : '正在读取可用资料…' }}</p>
        <div v-if="direct && ready && teacher" class="direct-invitation space-y-3">
          <p>邀请对象：<strong>{{ teacher.displayName }}</strong></p>
          <label for="invitation-introduction">想和老师聊什么？<span class="ml-2 text-xs text-muted-foreground">选填</span>
            <textarea id="invitation-introduction" v-model="introduction" rows="3" maxlength="500" :disabled="busy || unknown" class="mt-2 block w-full rounded-xl border border-border p-3" placeholder="例如：想了解周末的入门课程，方便聊聊时间和安排吗？"/>
          </label>
          <p class="text-sm text-secondary-foreground">简单说说目标或时间，方便老师回应。</p>
        </div>
        <div v-if="ready && teacher && request" class="space-y-5 rounded-xl bg-card p-4">
          <RequestSummary :fields="request" :display-name="request.publisherDisplayName"/>
          <TeacherSummary :teacher="teacher"/>
          <p class="text-sm text-secondary-foreground">这次联系会保留你们确认时的资料，后续修改不会替换本次记录。</p>
        </div>
        <p v-if="unknown" role="status">上次结果尚未确认。请用同一次操作重试，或到撮合进度查看。</p>
        <ContactExchangeNotice v-if="!drawer && ready && contacts" :contacts="contacts" :action="me.businessIdentity==='TUTOR'?'投递需求':'发送邀请'"/>
      </section>
    </div>
    <component :is="drawer ? DiscoveryActionBar : 'div'" v-bind="drawer ? { showNote: !(opened && ready && contacts) } : {}">
      <ContactExchangeNotice v-if="drawer && opened && ready && contacts" :contacts="contacts" :action="me.businessIdentity==='TUTOR'?'投递需求':'发送邀请'"/>
      <button v-if="!opened" ref="openButton" type="button" :class="primaryClass" @click="open">{{ me.businessIdentity==='TUTOR' ? (drawer ? '发出意向' : '我对这个需求感兴趣') : '邀请这位老师' }}</button>
      <div v-else :class="drawer ? 'space-y-1' : 'flex flex-wrap items-center gap-2'">
        <button :class="primaryClass" :disabled="busy || (!unknown && (!ready || (!direct && !request) || !teacher))" @click="send">{{ unknown?'重试同一次意向':me.businessIdentity==='TUTOR'?'确认投递':'发送邀请' }}</button>
        <div class="flex justify-center gap-4">
          <button v-if="error && !unknown" class="min-h-9 text-sm text-primary" :disabled="busy" @click="run(load)">重新读取并核对</button>
          <button class="min-h-9 text-sm text-secondary-foreground" :disabled="busy || unknown" @click="cancel">取消</button>
        </div>
      </div>
    </component>
  </div>
</template>
<style scoped>
@reference "./styles/theme.css";
.drawer-composer :deep(.exchange-notice) { @apply rounded-xl bg-slate-50 text-sm text-secondary-foreground; }
.drawer-composer :deep(.own-contact) { @apply text-foreground; }
</style>
