<script setup lang="ts">
import { computed,nextTick,onBeforeUnmount,onMounted,ref,watch } from "vue";
import { api,ApiFailure } from "./account-api.ts";
import RequestSummary from "./RequestSummary.vue";
import { statusNames,declarationNames } from "./request-display.ts";
type Submission={id:string;requestId:string;status:string;version:number;submittedAt:string;expiresAt:string;fields:Record<string,unknown>;publisherDisplayName:string;publisherDeclaration:string;reviewFeedback:string|null;priorReviewFeedback:string|null};
const emit=defineEmits<{expired:[]}>();
const items=ref<Submission[]>([]),selected=ref<Submission|null>(null),cursor=ref<string|null>(null),status=ref("PENDING_REVIEW");
const busy=ref(false),error=ref(""),message=ref(""),decision=ref(""),reason=ref("");
const confirmation=ref(false),dialog=ref<HTMLDialogElement>();
let pending:{body:string;key:string}|null=null;
const dirty=computed(()=>selected.value?.status==='PENDING_REVIEW'&&(!!decision.value||!!reason.value));
watch(confirmation,async value=>{await nextTick();if(value&&!dialog.value?.open)dialog.value?.showModal();if(!value&&dialog.value?.open)dialog.value.close();});
async function run(action:()=>Promise<void>){
  if(busy.value)return;busy.value=true;error.value="";message.value="";
  try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作未完成，请重试。";if(e instanceof ApiFailure){error.value+=" "+Object.values(e.fieldErrors).join("；");if(e.code==='LOGIN_REQUIRED'||e.code==='SESSION_CHANGED')emit("expired");}}finally{busy.value=false;}
}
async function list(append=false){const query=new URLSearchParams({status:status.value});if(append&&cursor.value)query.set("cursor",cursor.value);const result=await api.request<{items:Submission[];nextCursor:string|null}>("/operations/submissions?"+query);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;selected.value=null;decision.value="";reason.value="";pending=null;}
async function open(id:string){await run(async()=>{selected.value=await api.request<Submission>("/operations/submissions/"+id);decision.value="";reason.value="";pending=null;});}
async function refresh(){await run(async()=>{if(selected.value)selected.value=await api.request<Submission>("/operations/submissions/"+selected.value.id);message.value="已读取最新状态，当前填写的审核意见仍保留。";});}
function requestLeave(action:()=>void|Promise<void>){if(busy.value)return;if(dirty.value&&!window.confirm("审核意见尚未提交，离开将放弃本页填写。是否继续离开？"))return;void action();}
function preview(){error.value="";if(!decision.value){error.value="请选择通过或驳回。";return;}if(decision.value==='REJECT'&&!reason.value.trim()){error.value="驳回前请填写理由。";return;}confirmation.value=true;}
async function confirm(){await run(async()=>{
  if(!selected.value)return;
  const body={expectedVersion:selected.value.version,decision:decision.value,reason:decision.value==='REJECT'?reason.value:null};const encoded=JSON.stringify(body);
  if(!pending||pending.body!==encoded)pending={body:encoded,key:crypto.randomUUID()};
  await api.request(`/operations/submissions/${selected.value.id}/decision`,"POST",body,pending.key);
  confirmation.value=false;selected.value=await api.request<Submission>("/operations/submissions/"+selected.value.id);decision.value="";reason.value="";pending=null;message.value="审核结果已保存。";
});}
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value){event.preventDefault();event.returnValue="";}}
onMounted(()=>{void run(()=>list());window.addEventListener("beforeunload",beforeUnload);});onBeforeUnmount(()=>window.removeEventListener("beforeunload",beforeUnload));
defineExpose({requestLeave});
</script>
<template>
  <section class="review-page"><div class="page-heading"><div><p class="eyebrow">REQUEST REVIEW</p><h1>需求审核</h1><p>核对固定提交内容；本人账号的需求不进入审核队列。</p></div><button v-if="selected" class="secondary" :disabled="busy" @click="requestLeave(()=>run(()=>list()))">返回审核队列</button></div>
    <div v-if="error&&!confirmation" class="message error" role="alert">{{ error }}</div><p v-if="message" class="message success" role="status">{{ message }}</p>
    <template v-if="!selected"><form class="form-card" @submit.prevent="run(()=>list())"><label>审核状态<select v-model="status"><option value="PENDING_REVIEW">待审核</option><option value="APPROVED">已通过</option><option value="REJECTED">已驳回</option><option value="EXPIRED">已到期</option></select></label><button class="secondary" :disabled="busy">读取审核队列</button></form><p v-if="!items.length" class="form-card">{{ busy?'正在读取…':'当前没有符合条件的提交。' }}</p><article v-for="item in items" :key="item.id" class="form-card review-item"><h2>{{ item.fields.title }}</h2><p>{{ statusNames[item.status] }} · {{ item.publisherDisplayName }}</p><button class="secondary" :disabled="busy" @click="open(item.id)">核对提交</button></article><button v-if="cursor" class="secondary" :disabled="busy" @click="run(()=>list(true))">加载更多提交</button></template>
    <article v-else class="form-card"><p class="pill">{{ statusNames[selected.status] }}</p><RequestSummary :fields="selected.fields" :display-name="selected.publisherDisplayName" />
      <div class="private-declaration"><h3>本次私有发布声明</h3><p>{{ declarationNames[selected.publisherDeclaration] }}</p><p>提交时间：{{ new Date(selected.submittedAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}</p><p>审核有效期限：{{ new Date(selected.expiresAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'}) }}（内部）</p></div>
      <p v-if="selected.priorReviewFeedback" class="message warning">上次驳回理由：{{ selected.priorReviewFeedback }}</p><p v-if="selected.reviewFeedback" class="message warning">本次驳回理由：{{ selected.reviewFeedback }}</p>
      <form v-if="selected.status==='PENDING_REVIEW'" @submit.prevent="preview"><fieldset :disabled="busy"><legend>审核决定</legend><p class="field-help">请检查服务内容、发布对象及自由文本；含联系方式、儿童实名或精确住址时应驳回并说明。</p><label class="check-line"><input v-model="decision" type="radio" value="APPROVE">通过审核并公开</label><label class="check-line"><input v-model="decision" type="radio" value="REJECT">驳回并说明原因</label><label v-if="decision==='REJECT'">驳回理由<textarea v-model="reason" rows="4" placeholder="请说明需要修改的具体内容，最多1000个字符。"></textarea></label><button class="primary" :disabled="!decision">核对并确认审核</button></fieldset></form>
      <p v-else class="privacy-note">当前提交已处理或已到期，不能再次作出决定。</p><button class="secondary below" :disabled="busy" @click="refresh">读取最新审核状态</button>
    </article>
    <dialog ref="dialog" class="native-dialog" aria-labelledby="review-confirm-title" @cancel.prevent="!busy&&(confirmation=false)"><section class="dialog-card"><h2 id="review-confirm-title">{{ decision==='APPROVE'?'确认通过并公开？':'确认驳回？' }}</h2><p>{{ decision==='APPROVE'?'通过后，该提交内容将向所有访客公开。请确认已核对本次内容。':'需求方会看到以下理由，并可修改后重新提交。' }}</p><p v-if="decision==='REJECT'" class="pre-wrap">{{ reason }}</p><p v-if="error" class="message error" role="alert">{{ error }}</p><div class="form-actions"><button class="secondary" :disabled="busy" @click="confirmation=false">返回核对</button><button class="primary" :disabled="busy" @click="confirm">{{ decision==='APPROVE'?'确认通过并公开':'确认驳回' }}</button></div></section></dialog>
  </section>
</template>
<style scoped>
.review-page{max-width:900px;margin:auto}.review-item{margin-top:18px;overflow-wrap:anywhere}.review-item p{margin:12px 0;color:var(--muted)}.review-item h2{font-size:21px}.review-page select{display:block;padding:10px;border:1px solid var(--line);border-radius:8px;margin:10px 0 16px;background:white;font:inherit}.private-declaration{padding:18px;background:#f5f7f1;border-radius:8px;margin:20px 0;overflow-wrap:anywhere}.private-declaration p{margin-top:12px;font-size:13px}.review-page .check-line{margin:18px 0}.review-page textarea{margin-bottom:18px}.review-page .pill{display:inline-block}.review-page .form-actions{margin-top:22px}@media(max-width:520px){.review-page .page-heading{flex-wrap:wrap}.review-page h1{font-size:25px}}
</style>
