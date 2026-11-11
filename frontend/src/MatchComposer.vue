<script setup lang="ts">
import {ref} from "vue";
import {api,ApiFailure} from "./account-api.ts";
import type {Me,Profile} from "./account-api.ts";
import type {Match,PublicRequest,PublicTeacher} from "./match-types.ts";
import TeacherSummary from "./TeacherSummary.vue";
import RequestSummary from "./RequestSummary.vue";
const props=defineProps<{me:Me;requestId?:string;teacherId?:string}>();
const emit=defineEmits<{matched:[id:string];expired:[];profile:[];requests:[]}>();
const opened=ref(false),busy=ref(false),error=ref(""),needsProfile=ref(false);
const teacher=ref<PublicTeacher|null>(null),request=ref<PublicRequest|null>(null),choices=ref<{id:string;fields:{title?:string}}[]>([]),selected=ref("");
const cursor=ref<string|null>(null),ready=ref(false),unknown=ref(false);
let pending:{body:unknown;key:string}|null=null;
async function run(action:()=>Promise<void>) {if(busy.value)return;busy.value=true;error.value="";try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作失败，请重试。";if(e instanceof ApiFailure&&(e.status===401||e.code==="SESSION_CHANGED"))emit("expired");}finally{busy.value=false;}}
async function loadChoices(append=false) {
  const result=await api.request<{items:{id:string;status:string;fields:{title?:string}}[];nextCursor:string|null}>("/me/requests"+(append&&cursor.value?"?cursor="+encodeURIComponent(cursor.value):""));
  const eligible=result.items.filter(r=>r.status==="OPEN");choices.value=append?[...choices.value,...eligible]:eligible;cursor.value=result.nextCursor;
}
async function load() {
  ready.value=false;teacher.value=null;request.value=null;needsProfile.value=false;
  if(props.me.businessIdentity==="TUTOR") {
    const p=await api.request<Profile>("/me/profile");
    if(!p.teacher?.public){needsProfile.value=true;throw new Error("请先补齐本人老师资料并主动公开，再投递需求。");}
    teacher.value={...p.teacher,id:props.me.id,displayName:p.displayName,profileVersion:p.version};
  } else {teacher.value=await api.request<PublicTeacher>("/teachers/"+encodeURIComponent(props.teacherId!));if(!props.requestId)await loadChoices();}
  const id=props.requestId||selected.value;
  if(id)request.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(id));
  ready.value=true;pending=null;unknown.value=false;
}
async function open(){opened.value=true;await run(load);}
async function choose(){await run(async()=>{request.value=null;pending=null;unknown.value=false;request.value=await api.request<PublicRequest>("/public/requests/"+encodeURIComponent(selected.value));});}
async function send(){await run(async()=>{
  if(!pending){
    if(!teacher.value||!request.value)return;
    if(!window.confirm(props.me.businessIdentity==="TUTOR"?"确认按当前核对的需求与本人公开资料投递？对方明确接受后才互选，不分享联系方式。":"确认用当前需求邀请这位老师？老师明确接受后才互选，不分享联系方式。"))return;
    pending={key:crypto.randomUUID(),body:{requestId:request.value.id,expectedSubmissionId:request.value.submissionId,expectedTeacherProfileVersion:teacher.value.profileVersion,kind:props.me.businessIdentity==="TUTOR"?"APPLICATION":"INVITATION",...(props.me.businessIdentity==="REQUESTER"?{tutorUserId:teacher.value.id}:{})}};
  }
  try{const result=await api.request<{match:Match;outcome:string}>("/matches","POST",pending.body,pending.key);pending=null;unknown.value=false;emit("matched",result.match.id);}
  catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
</script>
<template>
  <div class="composer">
    <p class="contact-explanation">先表达意向，对方接受后，双方可自愿交换手机号或微信，再到站外沟通。</p>
    <button v-if="!opened" class="primary" @click="open">{{ me.businessIdentity==='TUTOR'?'我对这个需求感兴趣':'邀请这位老师' }}</button>
    <template v-else><h3>核对本次意向</h3><p>请核对需求与老师资料。已有联系记录时，会带你查看已有进度，不会重复发送。</p>
      <div v-if="error" class="message error" role="alert">{{ error }}</div>
      <button v-if="needsProfile" class="secondary" @click="emit('profile')">完善并公开本人资料</button>
      <p v-if="busy" role="status">正在读取可用资料…</p>
      <template v-if="me.businessIdentity==='REQUESTER'&&!requestId"><label>选择本人招募中的需求<select v-model="selected" :disabled="busy||unknown" @change="choose"><option value="" disabled>请选择需求</option><option v-for="item in choices" :key="item.id" :value="item.id">{{ item.fields.title||'未命名需求' }}</option></select></label><button v-if="cursor" class="text-button" :disabled="busy||unknown" @click="run(()=>loadChoices(true))">继续查找本人需求</button><p v-if="!busy&&!error&&ready&&!choices.length">{{cursor?'本页没有招募中的需求，请继续查找。':'还没有可邀请老师的公开需求。先发布并通过审核，让老师了解你的目标与安排。'}}</p><button class="text-button" :disabled="busy||unknown" @click="emit('requests')">查看或发布我的需求</button></template>
      <div v-if="ready&&teacher&&request" class="snapshot"><RequestSummary :fields="request" :display-name="request.publisherDisplayName"/><TeacherSummary :teacher="teacher"/><p class="privacy-note">这次联系会保留你们确认时的资料，后续修改不会替换本次记录。</p></div>
      <p v-if="unknown" role="status">上次结果尚未确认。请用同一次操作重试，或到撮合进度查看。</p>
      <div class="form-actions"><button class="primary" :disabled="busy||(!unknown&&(!ready||!request||!teacher))" @click="send">{{ unknown?'重试同一次意向':me.businessIdentity==='TUTOR'?'确认投递':'确认邀请' }}</button><button class="secondary" :disabled="busy||unknown" @click="run(load)">重新读取并核对</button><button class="text-button" :disabled="busy||unknown" @click="opened=false">取消</button></div>
    </template>
  </div>
</template>
<style scoped>.composer{margin-top:24px;border-top:1px solid var(--line);padding-top:24px}.composer p{line-height:1.8;margin:14px 0}.composer select{width:100%;padding:12px;margin:8px 0;border:1px solid var(--line);border-radius:8px;background:white;font:inherit}.snapshot{margin:20px 0;padding:16px;background:var(--surface,#f8faf9);border-radius:12px}</style>
