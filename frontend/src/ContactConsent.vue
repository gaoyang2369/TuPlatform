<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue';
import {api,ApiFailure} from './account-api.ts';
import type {ContactField,Contacts,Grant} from './contact-types.ts';
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { ShieldCheck } from "@lucide/vue";
import ConfirmDialog from './ConfirmDialog.vue';
import ContactExchangeNotice from './ContactExchangeNotice.vue';
const props=defineProps<{matchId:string;roundId:string}>();
const emit=defineEmits<{expired:[];contacts:[];visibility:[shared:boolean]}>();
const grant=ref<Grant|null>(null),contacts=ref<Contacts|null>(null),withdrawOpen=ref(false);
watch(() => !!grant.value?.canViewContacts, shared => emit('visibility', shared));
const selected=computed<ContactField[]>(()=>[...(contacts.value?.phone?['PHONE' as const]:[]),...(contacts.value?.wechat?['WECHAT' as const]:[])]);
const busy=ref(false),error=ref(''),notice=ref(''),unknown=ref(false),conflict=ref(false),saved=ref(false);
let alive=true,epoch=0,timer:ReturnType<typeof setInterval>|undefined;
let pending:{method:string;body:unknown;key:string}|null=null;
const path=()=>`/matches/${encodeURIComponent(props.matchId)}/contact-consent`;
const canAgree=computed(()=>!!grant.value&&!!contacts.value&&selected.value.length>0&&selected.value.every(f=>f==='PHONE'?!!contacts.value?.phone:!!contacts.value?.wechat)&&!unknown.value&&!conflict.value);
function clearValues(){if(grant.value)grant.value={...grant.value,canViewContacts:false,mySharedContacts:undefined,otherSharedContacts:undefined};}
async function read(reset=true){
  clearValues();const requestEpoch=++epoch;
  const [g,c]=await Promise.all([api.request<Grant>(path()),api.request<Contacts>('/me/contacts')]);
  if(!alive||requestEpoch!==epoch||document.hidden)return;
  grant.value=g;contacts.value=c;
  if(reset){pending=null;unknown.value=false;conflict.value=false;saved.value=false;}
}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await action();}catch(e){clearValues();if(!alive)return;error.value=e instanceof Error?e.message:'操作未完成';if(e instanceof ApiFailure){error.value=[e.message,...Object.values(e.fieldErrors)].join('；');if(e.status===401||e.code==='SESSION_CHANGED')emit('expired');if(e.status===409)conflict.value=true;}}finally{busy.value=false;}}
async function refresh(){await run(()=>read());}
async function perform(method='PUT'){await run(async()=>{
  if(!pending){if(!grant.value||!contacts.value)return;
    saved.value=false;
    if(method==='PUT'){
      if(!canAgree.value)return;
      pending={method,body:{roundId:props.roundId,expectedGrantVersion:grant.value.version,selectedFields:[...selected.value],expectedContactFieldVersions:contacts.value.fieldVersions,agree:true},key:crypto.randomUUID()};
    }else{
      if(!withdrawOpen.value)return;
      pending={method,body:{roundId:props.roundId,expectedGrantVersion:grant.value.version},key:crypto.randomUUID()};
    }
  }
  withdrawOpen.value=false;clearValues();++epoch;
  try{const g=await api.request<Grant>(path(),pending.method,pending.body,pending.key);if(!alive)return;grant.value=g;saved.value=true;await read(false);
    pending=null;unknown.value=false;notice.value='已更新。';
  }catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
function background(){if(document.hidden){++epoch;clearValues();return;}if(!busy.value&&!unknown.value&&!withdrawOpen.value)void run(()=>read(false));}
onMounted(()=>{void refresh();document.addEventListener('visibilitychange',background);window.addEventListener('focus',background);timer=setInterval(background,15000);});
onBeforeUnmount(()=>{alive=false;++epoch;clearInterval(timer);document.removeEventListener('visibilitychange',background);window.removeEventListener('focus',background);clearValues();contacts.value=null;pending=null;});
</script>
<template><section data-ui class="space-y-4 rounded-2xl bg-slate-50 p-4 sm:p-6" aria-labelledby="consent-title">
  <div class="flex items-center justify-between gap-3"><h3 id="consent-title" class="flex items-center gap-2 text-xl font-bold"><ShieldCheck class="size-5 text-primary"/>{{grant?.canViewContacts?'对方联系方式':'联系对方'}}</h3><Button v-if="error||conflict||unknown" variant="ghost" :disabled="busy" @click="refresh">重新读取</Button></div>
  <p class="text-sm leading-6 text-secondary-foreground">平台无中介。双方同意后，可直接联系对方商定安排。</p>
  <p v-if="error" class="rounded-xl bg-red-50 p-3 text-sm text-red-800" role="alert">{{error}}</p><p v-if="notice" class="text-sm text-secondary-foreground" role="status">{{notice}}</p>
  <p v-if="unknown" class="rounded-xl bg-orange-50 p-3 text-sm text-orange-900">{{saved?'操作已保存，最新状态暂未更新。':'上次操作结果尚未确认。'}}<Button variant="ghost" :disabled="busy" @click="perform()">重试同一次授权操作</Button></p>
  <p v-if="conflict" class="rounded-xl bg-orange-50 p-3 text-sm text-orange-900">联系方式已变化，请重新读取后确认。</p>
  <template v-if="grant">
    <div v-if="grant.canViewContacts&&grant.otherSharedContacts" class="space-y-3 rounded-xl bg-card p-4" data-testid="shared-contacts"><div v-if="grant.otherSharedContacts.phone" class="grid gap-1 text-xl font-bold [&>span]:text-xs [&>span]:font-medium [&>span]:text-muted-foreground"><span>手机号</span><a :href="`tel:+86${grant.otherSharedContacts.phone}`">{{grant.otherSharedContacts.phone}}</a></div><div v-if="grant.otherSharedContacts.wechat" class="grid gap-1 text-xl font-bold [&>span]:text-xs [&>span]:font-medium [&>span]:text-muted-foreground"><span>微信</span><strong>{{grant.otherSharedContacts.wechat}}</strong></div><p class="text-sm leading-6 text-secondary-foreground">添加时可以说明来自邻课，沟通课程与安排。</p></div>
    <template v-else-if="!grant.myConsent">
      <template v-if="contacts&&(contacts.phone||contacts.wechat)"><p class="text-sm leading-6 text-secondary-foreground">{{grant.requiresReconfirmation?'联系方式有更新，确认后即可继续交换。':'这条联系记录尚未确认交换联系方式。'}}</p><ContactExchangeNotice :contacts="contacts" action="同意交换"/><Button class="h-11 rounded-xl" :disabled="busy||!canAgree" @click="perform()">同意交换联系方式</Button></template>
      <template v-else-if="contacts"><p class="text-sm leading-6 text-secondary-foreground">补充手机号或微信，让对方能联系到你。</p><Button class="h-11 rounded-xl" :disabled="busy||unknown" @click="emit('contacts')">填写联系方式</Button></template>
    </template>
    <p v-else class="text-sm leading-6 text-secondary-foreground" role="status">你已同意交换，等待对方补充或确认联系方式。</p>
    <details class="rounded-xl bg-card p-3.5 text-sm"><summary>管理我的联系方式</summary><div class="mt-2 space-y-2"><p v-if="grant.myConsent" class="text-sm leading-6 text-secondary-foreground">{{grant.canViewContacts?'已向对方分享：':'将向对方分享：'}}{{grant.mySelectedFields.map(f=>f==='PHONE'?'手机号':'微信').join('、')}}</p><div class="flex flex-wrap gap-2"><Button variant="ghost" :disabled="busy||unknown" @click="emit('contacts')">填写或修改我的联系方式</Button><Button v-if="grant.myConsent||grant.requiresReconfirmation" variant="ghost" :disabled="busy||unknown||conflict" @click="withdrawOpen=true">停止分享</Button></div></div></details>
  </template><div v-else-if="busy" class="text-sm leading-6 text-secondary-foreground" role="status">正在读取联系方式…<Skeleton class="mt-2 h-16 w-full"/></div>
  <ConfirmDialog :open="withdrawOpen" title="停止分享联系方式？" confirm-text="停止分享" :busy="busy" @cancel="withdrawOpen=false" @confirm="perform('DELETE')"><p>停止后，双方将无法在这里查看联系方式。对方已经保存的信息不受影响。</p></ConfirmDialog>
</section></template>
