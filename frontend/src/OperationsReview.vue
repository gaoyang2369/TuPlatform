<script setup lang="ts">
import { computed,onBeforeUnmount,onMounted,ref } from "vue";
import { api,ApiFailure } from "./account-api.ts";
import { ArrowRight, ClipboardCheck, RefreshCw, ShieldCheck } from "@lucide/vue";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import DiscoverySheet from "./DiscoverySheet.vue";
import ConfirmDialog from "./ConfirmDialog.vue";
import RequestSummary from "./RequestSummary.vue";
import { areaNames, categoryNames, stageNames, statusNames, declarationNames } from "./request-display";
import { moneyText } from "./discovery-display";
type Submission={id:string;requestId:string;status:string;version:number;submittedAt:string;expiresAt:string;fields:Record<string,unknown>;publisherDisplayName:string;publisherDeclaration:string;reviewFeedback:string|null;priorReviewFeedback:string|null};
const emit=defineEmits<{expired:[]}>();
const items=ref<Submission[]>([]),selected=ref<Submission|null>(null),cursor=ref<string|null>(null),status=ref("PENDING_REVIEW");
const busy=ref(false),error=ref(""),message=ref(""),decision=ref(""),reason=ref("");
const confirmation = ref(false);
const states = ["PENDING_REVIEW", "APPROVED", "REJECTED", "EXPIRED"];
const heading = ref<HTMLElement>();
let trigger: HTMLElement | null = null;
const formatTime = (value: string) => new Date(value).toLocaleString("zh-CN", {timeZone:"Asia/Shanghai"});
let pending:{body:string;key:string}|null=null;
const dirty=computed(()=>selected.value?.status==='PENDING_REVIEW'&&(!!decision.value||!!reason.value));
async function run(action:()=>Promise<void>){
  if(busy.value)return;busy.value=true;error.value="";message.value="";
  try{await action();}catch(e){error.value=e instanceof Error?e.message:"操作未完成，请重试。";if(e instanceof ApiFailure){error.value+=" "+Object.values(e.fieldErrors).join("；");if(e.code==='LOGIN_REQUIRED'||e.code==='SESSION_CHANGED'||e.code==='ADMIN_REQUIRED')emit("expired");}}finally{busy.value=false;}
}
async function list(append=false){const query=new URLSearchParams({status:status.value==="PENDING_REVIEW"?"pending":status.value.toLowerCase()});if(append&&cursor.value)query.set("cursor",cursor.value);const result=await api.request<{items:Submission[];nextCursor:string|null}>("/admin/demands?"+query);items.value=append?[...items.value,...result.items]:result.items;cursor.value=result.nextCursor;selected.value=null;decision.value="";reason.value="";pending=null;}
async function open(id:string,event:MouseEvent){trigger=event.currentTarget as HTMLElement;await run(async()=>{selected.value=await api.request<Submission>("/admin/demands/"+id);decision.value="";reason.value="";pending=null;});}
async function refresh(){await run(async()=>{if(selected.value)selected.value=await api.request<Submission>("/admin/demands/"+selected.value.id);message.value="已读取最新状态，当前填写的审核意见仍保留。";});}
function requestLeave(action:()=>void|Promise<void>){if(busy.value && selected.value)return;if(dirty.value&&!window.confirm("审核意见尚未提交，离开将放弃本页填写。是否继续离开？"))return;void action();}
function closeSheet() {
  if (confirmation.value) return;
  requestLeave(() => {
    selected.value = null;
    decision.value = "";
    reason.value = "";
    pending = null;
    return run(() => list());
  });
}
function restoreFocus(event: Event) {
  event.preventDefault();
  (trigger?.isConnected ? trigger : heading.value)?.focus({ preventScroll: true });
}
async function filter(value: string) {
  await run(async () => {
    const previous = status.value;
    status.value = value;
    try { await list(); } catch (e) { status.value = previous; throw e; }
  });
}
function preview(){error.value="";if(!decision.value){error.value="请选择通过或驳回。";return;}if(decision.value==='REJECT'&&!reason.value.trim()){error.value="驳回前请填写理由。";return;}confirmation.value=true;}
async function confirm(){await run(async()=>{
  if(!selected.value)return;
  const body={expectedVersion:selected.value.version,decision:decision.value,reason:decision.value==='REJECT'?reason.value:null};const encoded=JSON.stringify(body);
  if(!pending||pending.body!==encoded)pending={body:encoded,key:crypto.randomUUID()};
  await api.request(`/admin/demands/${selected.value.id}/review`,"POST",body,pending.key);
  confirmation.value=false;selected.value=await api.request<Submission>("/admin/demands/"+selected.value.id);decision.value="";reason.value="";pending=null;message.value="审核结果已保存。";
});}
function beforeUnload(event:BeforeUnloadEvent){if(dirty.value){event.preventDefault();event.returnValue="";}}
onMounted(()=>{void run(()=>list());window.addEventListener("beforeunload",beforeUnload);});onBeforeUnmount(()=>window.removeEventListener("beforeunload",beforeUnload));
defineExpose({requestLeave});
</script>
<template>
  <section class="space-y-6 [overflow-wrap:anywhere]" :aria-busy="busy">
    <header ref="heading" tabindex="-1" class="space-y-2">
      <h2 class="text-xl font-bold">家长需求审核队列</h2>
      <p class="text-[15px] text-secondary-foreground">核对本次提交的内容。自己的需求不会进入审核队列。</p>
    </header>
    <div class="flex flex-wrap items-center justify-between gap-2">
      <div role="group" aria-label="审核状态" class="flex flex-wrap gap-1 rounded-2xl border border-border bg-card p-1">
        <Button v-for="code in states" :key="code" variant="ghost" class="rounded-full aria-pressed:bg-blue-50 aria-pressed:text-primary" :aria-pressed="status===code" :disabled="busy" @click="filter(code)">{{ statusNames[code] }}</Button>
      </div>
      <Button variant="outline" :disabled="busy" @click="run(()=>list())"><RefreshCw/>读取审核队列</Button>
    </div>
    <p v-if="error && !selected" role="alert" class="rounded-xl bg-red-50 p-4 text-red-800">{{ error }}</p>
    <div v-if="busy && !items.length" role="status" class="grid gap-6 md:grid-cols-2"><span class="sr-only">正在读取审核队列…</span><Skeleton v-for="i in 2" :key="i" class="h-40 rounded-2xl"/></div>
    <div v-else-if="!items.length && !error" class="grid justify-items-center gap-3 rounded-2xl border border-border bg-card p-8"><ClipboardCheck class="size-7 text-muted-foreground"/><p>当前没有符合条件的提交。</p></div>
    <div class="grid gap-6 md:grid-cols-2 lg:grid-cols-3">
      <article v-for="item in items" :key="item.id" class="flex min-w-0 flex-col items-start gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm transition hover:shadow-lg motion-safe:hover:-translate-y-0.5 sm:p-6">
        <Badge variant="secondary" :class="item.status==='PENDING_REVIEW' && 'bg-orange-50 text-orange-800'">{{ statusNames[item.status] }}</Badge>
        <h2 class="text-xl font-bold [overflow-wrap:anywhere]">{{ item.fields.title }}</h2>
        <strong class="text-xl">¥{{ moneyText(item.fields.budgetMin) || '—' }} 至 {{ moneyText(item.fields.budgetMax) || '—' }} <small class="text-xs font-normal">元/小时</small></strong>
        <p>{{ item.fields.grade || stageNames[String(item.fields.audienceStageCode)] }} · {{ item.fields.subject || categoryNames[String(item.fields.categoryCode)] }}</p>
        <p class="text-sm text-secondary-foreground">{{ item.fields.street || areaNames[String(item.fields.areaCode)] || '未填街道' }} · {{ item.fields.exact_address || item.fields.locationText || '无地址备注' }}</p>
        <p class="mt-auto text-sm text-secondary-foreground">{{ item.publisherDisplayName }} · {{ formatTime(item.submittedAt) }}</p>
        <Button variant="secondary" class="h-11 w-full justify-between rounded-xl" :disabled="busy" @click="open(item.id,$event)">核对提交<ArrowRight/></Button>
      </article>
    </div>
    <Button v-if="cursor" variant="outline" :disabled="busy" @click="run(()=>list(true))">加载更多提交</Button>
    <DiscoverySheet :open="!!selected" title="核对提交" description="核对提交版本、私有声明及审核决定。" back-label="返回审核队列" @close="closeSheet" @restore-focus="restoreFocus">
      <template v-if="selected">
        <div class="min-h-0 flex-1 space-y-5 overflow-y-auto overscroll-contain [overflow-wrap:anywhere] px-5 pb-5 sm:px-8">
          <Badge variant="secondary">{{ statusNames[selected.status] }}</Badge>
          <RequestSummary :fields="selected.fields" :display-name="selected.publisherDisplayName"/>
          <p class="rounded-xl bg-blue-50 p-3 text-sm">私有地址备注 · {{ selected.fields.exact_address || selected.fields.locationText || '未填写' }}</p>
          <section class="space-y-2 rounded-xl bg-slate-50 p-4 text-sm text-secondary-foreground">
            <h3 class="flex items-center gap-2 font-bold text-foreground"><ShieldCheck class="size-4"/>本次私有发布声明</h3>
            <p>{{ declarationNames[selected.publisherDeclaration] }}</p><p>提交时间：{{ formatTime(selected.submittedAt) }}</p><p>审核有效期限：{{ formatTime(selected.expiresAt) }}（内部）</p>
          </section>
          <p v-if="selected.priorReviewFeedback" class="rounded-xl bg-orange-50 p-3 text-sm">上次驳回理由：{{ selected.priorReviewFeedback }}</p>
          <p v-if="selected.reviewFeedback" class="rounded-xl bg-orange-50 p-3 text-sm">本次驳回理由：{{ selected.reviewFeedback }}</p>
          <fieldset v-if="selected.status==='PENDING_REVIEW'" :disabled="busy" class="space-y-3">
            <legend class="mb-2 font-bold">审核决定</legend>
            <p class="text-sm text-secondary-foreground">请检查服务内容、发布对象及自由文本。公开字段含联系方式、儿童实名或精确住址时应驳回并说明。</p>
            <div class="flex flex-wrap gap-2">
              <label v-for="[value,label] in [['APPROVE','通过审核并公开'], ['REJECT','驳回并说明原因']]" :key="value" class="flex min-h-11 items-center gap-2 rounded-xl border border-border px-3 text-sm has-checked:border-blue-200 has-checked:bg-blue-50">
                <input v-model="decision" type="radio" name="decision" :value="value" class="accent-primary">{{ label }}
              </label>
            </div>
            <label v-if="decision==='REJECT'" class="block text-sm font-semibold">驳回理由<textarea v-model="reason" rows="4" class="field-control mt-2" placeholder="请说明需要修改的具体内容，最多1000个字符。"/></label>
          </fieldset>
          <p v-else class="text-sm text-secondary-foreground">当前提交已处理或已到期，不能再次作出决定。</p>
          <Button variant="ghost" :disabled="busy" @click="refresh"><RefreshCw/>读取最新审核状态</Button>
          <p v-if="message" role="status" class="rounded-xl bg-emerald-50 p-3 text-sm text-emerald-800">{{ message }}</p>
        </div>
        <div v-if="(error && !confirmation) || selected.status==='PENDING_REVIEW'" class="space-y-2 border-t border-border bg-white px-5 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))] sm:px-8">
          <p v-if="error && !confirmation" role="alert" class="text-sm text-red-700">{{ error }}</p>
          <Button v-if="selected.status==='PENDING_REVIEW'" class="h-11 w-full rounded-xl" :disabled="busy || !decision" @click="preview">核对并确认审核</Button>
        </div>
        <ConfirmDialog :open="confirmation" :busy="busy" :title="decision==='APPROVE'?'确认通过并公开？':'确认驳回？'" :confirm-text="decision==='APPROVE'?'确认通过并公开':'确认驳回'" cancel-text="返回核对" @cancel="confirmation=false" @confirm="confirm">
          <p>{{ decision==='APPROVE'?'通过后，访客可以查看需求。完整地址与发布声明仍受保护。':'需求方会看到以下理由，并可修改后重新提交。' }}</p>
          <p v-if="decision==='REJECT'" class="whitespace-pre-wrap">{{ reason }}</p>
          <p v-if="error" role="alert" class="rounded-xl bg-red-50 p-3 text-red-800">{{ error }}</p>
        </ConfirmDialog>
      </template>
    </DiscoverySheet>
  </section>
</template>
