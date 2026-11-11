<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref} from 'vue';
import {api,ApiFailure} from './account-api.ts';
import type {ContactField,Contacts,Grant} from './contact-types.ts';
import ConfirmDialog from './ConfirmDialog.vue';
import ContactExchangeNotice from './ContactExchangeNotice.vue';
const props=defineProps<{matchId:string;roundId:string}>();
const emit=defineEmits<{expired:[];contacts:[]}>();
const grant=ref<Grant|null>(null),contacts=ref<Contacts|null>(null),withdrawOpen=ref(false);
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
<template><section class="consent" aria-labelledby="consent-title">
  <div class="contact-heading"><h3 id="consent-title">{{grant?.canViewContacts?'对方联系方式':'联系对方'}}</h3><button v-if="error||conflict||unknown" class="text-button" :disabled="busy" @click="refresh">重新读取</button></div>
  <p v-if="error" class="message error" role="alert">{{error}}</p><p v-if="notice" class="sr-only" role="status">{{notice}}</p>
  <p v-if="unknown" class="message warning">{{saved?'操作已保存，最新状态暂未更新。':'上次操作结果尚未确认。'}}<button class="text-button" :disabled="busy" @click="perform()">重试同一次授权操作</button></p>
  <p v-if="conflict" class="message warning">联系方式已变化，请重新读取后确认。</p>
  <template v-if="grant">
    <div v-if="grant.canViewContacts&&grant.otherSharedContacts" class="shared-contacts" data-testid="shared-contacts"><div v-if="grant.otherSharedContacts.phone" class="contact-value"><span>手机号</span><a :href="`tel:+86${grant.otherSharedContacts.phone}`">{{grant.otherSharedContacts.phone}}</a></div><div v-if="grant.otherSharedContacts.wechat" class="contact-value"><span>微信</span><strong>{{grant.otherSharedContacts.wechat}}</strong></div><p class="contact-help">添加时可以说明来自邻课，沟通课程与安排。</p></div>
    <template v-else-if="!grant.myConsent">
      <template v-if="contacts&&(contacts.phone||contacts.wechat)"><p class="contact-help">{{grant.requiresReconfirmation?'联系方式有更新，确认后即可继续交换。':'这条联系记录尚未确认交换联系方式。'}}</p><ContactExchangeNotice :contacts="contacts" action="同意交换"/><button class="primary agree-button" :disabled="busy||!canAgree" @click="perform()">同意交换联系方式</button></template>
      <template v-else-if="contacts"><p class="contact-help">补充手机号或微信，让对方能联系到你。</p><button class="primary agree-button" :disabled="busy||unknown" @click="emit('contacts')">填写联系方式</button></template>
    </template>
    <p v-else class="contact-help" role="status">你已同意交换，等待对方补充或确认联系方式。</p>
    <details class="contact-settings"><summary>管理我的联系方式</summary><div class="contact-settings-body"><p v-if="grant.myConsent" class="contact-help">{{grant.canViewContacts?'已向对方分享：':'将向对方分享：'}}{{grant.mySelectedFields.map(f=>f==='PHONE'?'手机号':'微信').join('、')}}</p><div class="form-actions"><button class="text-button" :disabled="busy||unknown" @click="emit('contacts')">填写或修改我的联系方式</button><button v-if="grant.myConsent||grant.requiresReconfirmation" class="text-button" :disabled="busy||unknown||conflict" @click="withdrawOpen=true">停止分享</button></div></div></details>
  </template><p v-else-if="busy" class="contact-help" role="status">正在读取联系方式…</p>
  <ConfirmDialog :open="withdrawOpen" title="停止分享联系方式？" confirm-text="停止分享" :busy="busy" @cancel="withdrawOpen=false" @confirm="perform('DELETE')"><p>停止后，双方将无法在这里查看联系方式。对方已经保存的信息不受影响。</p></ConfirmDialog>
</section></template>
<style scoped>
.consent{margin-top:24px;padding:22px;border:1px solid #dce6d2;border-radius:12px;background:#f4f7ef;overflow-wrap:anywhere}.contact-heading{display:flex;align-items:center;justify-content:space-between;gap:16px}.contact-heading h3{font-size:17px}.contact-heading button{padding:0}.consent .message{margin:14px 0}.shared-contacts{margin-top:16px}.contact-value{display:grid;grid-template-columns:64px 1fr;align-items:baseline;gap:16px;margin:10px 0}.contact-value>span{font-size:13px;color:var(--muted)}.contact-value strong,.contact-value a{font-size:20px;font-weight:600;color:var(--green);word-break:break-all}.contact-value a{text-decoration:none}.contact-value a:hover{text-decoration:underline}.contact-help{font-size:13px;color:var(--muted);margin:12px 0;line-height:1.7}.contact-settings{margin-top:18px;padding-top:12px;border-top:1px solid #dce6d2}.contact-settings summary{font-size:12px;color:var(--muted);cursor:pointer}.contact-settings-body{padding-top:6px}.contact-settings .form-actions{gap:20px}.agree-button{margin-top:14px}.consent .exchange-notice{padding:0;background:transparent}@media(max-width:600px){.consent{padding:18px 16px}.contact-value{grid-template-columns:50px 1fr;gap:10px}.contact-value strong,.contact-value a{font-size:18px}}
</style>
