<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue';
import {api,ApiFailure} from './account-api.ts';
import type {Contacts} from './contact-types.ts';
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { LockKeyhole, RefreshCw, ShieldCheck } from "@lucide/vue";
import ConfirmDialog from './ConfirmDialog.vue';
const emit=defineEmits<{expired:[]}>();
const saved=ref<Contacts|null>(null),latest=ref<Contacts|null>(null),busy=ref(false),conflict=ref(false),error=ref(''),notice=ref('');
const form=reactive({phone:'',wechat:''});let baseline='',alive=true;
const dirty=computed(()=>saved.value!==null&&JSON.stringify(form)!==baseline);
const confirming=ref<'save'|'leave'|null>(null);
let leaveAction:(()=>void|Promise<void>)|null=null;
function cancelConfirm(){if(busy.value)return;confirming.value=null;leaveAction=null;}
async function confirm(){if(confirming.value==='save'){confirming.value=null;await save();}else{const action=leaveAction;cancelConfirm();void action?.();}}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await action();}catch(e){if(!alive)return;error.value=e instanceof Error?e.message:'操作未完成';if(e instanceof ApiFailure){error.value=[e.message,...Object.values(e.fieldErrors)].join('；');if(e.status===401||e.code==='SESSION_CHANGED')emit('expired');if(e.status===409)conflict.value=true;}}finally{busy.value=false;}}
function load(c:Contacts){saved.value=c;form.phone=c.phone??'';form.wechat=c.wechat??'';baseline=JSON.stringify(form);latest.value=null;conflict.value=false;}
async function read(){await run(async()=>{const c=await api.request<Contacts>('/me/contacts');if(!alive)return;if(saved.value){latest.value=c;notice.value='最新保存内容已读取，当前输入仍保留。请核对后选择。';}else load(c);});}
async function save(){await run(async()=>{if(!saved.value||conflict.value||!dirty.value)return;
  const result=await api.request<{contacts:Contacts;affectedGrantCount:number}>('/me/contacts','PUT',{expectedVersion:saved.value.version,phone:form.phone||null,wechat:form.wechat||null});if(!alive)return;load(result.contacts);notice.value=`私密联系方式已保存。${result.affectedGrantCount?`已使${result.affectedGrantCount}段关系停止展示，双方须重确认。`:'未影响其他关系授权。'}`;
});}
function useLatest(retain=false){if(!latest.value||busy.value)return;const input={...form};load(latest.value);if(retain)Object.assign(form,input);notice.value=retain?'已核对并保留输入；再次保存将更新为这些值。':'已载入最新保存内容。';}
function requestLeave(action:()=>void|Promise<void>){if(busy.value)return;if(dirty.value){leaveAction=action;confirming.value='leave';return;}void action();}
function beforeUnload(e:BeforeUnloadEvent){if(dirty.value){e.preventDefault();e.returnValue='';}}
defineExpose({requestLeave});
onMounted(()=>{window.addEventListener('beforeunload',beforeUnload);void read();});
onBeforeUnmount(()=>{alive=false;window.removeEventListener('beforeunload',beforeUnload);form.phone='';form.wechat='';saved.value=null;latest.value=null;});
</script>
<template>
<section class="space-y-5 text-foreground [overflow-wrap:anywhere]">
  <header class="flex flex-wrap items-center justify-between gap-3">
    <div><h1 class="text-3xl font-bold tracking-tight">本人私密联系方式</h1><p class="mt-2 text-[15px] text-secondary-foreground">仅在双方同意后向对方展示，邮箱始终保密。</p></div>
    <Button variant="outline" :disabled="busy" @click="read"><RefreshCw/>读取最新联系方式</Button>
  </header>
  <p v-if="error" class="rounded-xl bg-red-50 p-4 text-sm text-red-800" role="alert">{{ error }}</p>
  <p v-if="notice" class="rounded-xl bg-blue-50 p-4 text-sm text-foreground" role="status">{{ notice }}</p>
  <div v-if="latest" class="space-y-3 rounded-xl bg-orange-50 p-4"><h2 class="font-bold">最新保存内容</h2><p>手机号：{{latest.phone?'+86 '+latest.phone:'未填写'}}；微信：{{latest.wechat||'未填写'}}</p>
    <div class="flex flex-wrap gap-2"><Button variant="secondary" @click="useLatest()">载入最新内容，放弃输入</Button><Button variant="outline" @click="useLatest(true)">已核对，保留输入</Button></div></div>
  <p v-if="conflict" class="rounded-xl bg-orange-50 p-4 text-sm text-orange-900">保存版本已变化，先读取最新内容并核对。若上次响应丢失，可通过最新保存内容确认结果。</p>
  <form v-if="saved" class="rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-8" @submit.prevent="!busy&&!conflict&&dirty&&(confirming='save')">
    <fieldset :disabled="busy" class="space-y-5"><legend class="mb-3 flex items-center gap-2 text-xl font-bold"><LockKeyhole class="size-5"/>仅本人维护</legend>
      <p v-if="!saved.phone && !saved.wechat" class="text-sm text-secondary-foreground">尚未填写联系方式。手机号和微信均为选填，可在互选后补充。</p>
      <div class="grid gap-5 sm:grid-cols-2">
        <div><label for="contact-phone">手机号（选填，+86）</label><input id="contact-phone" v-model="form.phone" aria-describedby="phone-help" type="tel" inputmode="numeric" autocomplete="tel-national" pattern="1[0-9]{10}" placeholder="输入手机号">
          <small id="phone-help">暂支持中国大陆 +86，以 1 开头的 11 位数字。清空后保存即删除。</small></div>
        <div><label for="contact-wechat">微信号（选填）</label><input id="contact-wechat" v-model="form.wechat" aria-describedby="wechat-help" autocomplete="off" placeholder="输入微信号">
          <small id="wechat-help">1—64 个字符，不含空格、换行或控制字符。清空后保存即删除。</small></div>
      </div>
      <p class="flex items-start gap-2 rounded-xl bg-slate-50 p-4 text-sm leading-6 text-secondary-foreground"><ShieldCheck class="mt-0.5 size-4 shrink-0"/>修改或删除已分享的字段后，相关联系记录会停止展示，直到双方重新确认。</p>
      <div class="contact-save-bar flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between sm:gap-6">
        <span class="save-state flex items-center gap-2 text-sm text-secondary-foreground" role="status"><span aria-hidden="true" class="size-2 rounded-full" :class="dirty?'bg-neighborhood-orange':'bg-slate-400'"/>{{busy?'正在保存…':dirty?'尚未保存的修改':'所有修改已保存'}}</span>
        <Button type="submit" class="h-11 rounded-xl" :disabled="busy||conflict||!dirty">保存私密联系方式</Button>
      </div>
    </fieldset>
  </form>
  <div v-else-if="busy" role="status"><span class="sr-only">正在读取联系方式…</span><Skeleton class="h-52 w-full rounded-2xl"/></div>
  <ConfirmDialog :open="confirming!==null" :title="confirming==='leave'?'放弃尚未保存的修改？':'保存私密联系方式？'" :confirm-text="confirming==='leave'?'放弃并离开':'确认保存'" @cancel="cancelConfirm" @confirm="confirm"><p>{{confirming==='leave'?'离开后，本次输入的修改不会保存。你也可以取消，继续编辑。':'这些信息仅由你本人维护，保存不会自动分享给他人。'}}</p><p v-if="confirming==='save'" class="rounded-xl bg-slate-50 p-3">若修改或删除已分享的字段，相关关系将停止展示联系方式，需双方重新确认。</p></ConfirmDialog>
</section></template>
<style scoped>
@reference "./styles/theme.css";
label { @apply block text-sm font-semibold; }
input { @apply field-control my-2; }
small { @apply block text-xs leading-5 text-muted-foreground; }
</style>
