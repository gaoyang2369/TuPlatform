<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref} from 'vue';
import {api,ApiFailure} from './account-api.ts';
import type {ContactField,Contacts,Grant} from './contact-types.ts';
const props=defineProps<{matchId:string;roundId:string}>();
const emit=defineEmits<{expired:[];contacts:[]}>();
const grant=ref<Grant|null>(null),contacts=ref<Contacts|null>(null),selected=ref<ContactField[]>([]),confirmed=ref(false),preview=ref(false);
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
  if(reset){selected.value=g.mySelectedFields.filter(f=>f==='PHONE'?!!c.phone:!!c.wechat);confirmed.value=false;preview.value=false;pending=null;unknown.value=false;conflict.value=false;saved.value=false;}
}
async function run(action:()=>Promise<void>){if(busy.value)return;busy.value=true;error.value='';notice.value='';try{await action();}catch(e){clearValues();if(!alive)return;error.value=e instanceof Error?e.message:'授权操作未完成';if(e instanceof ApiFailure){error.value=[e.message,...Object.values(e.fieldErrors)].join('；');if(e.status===401||e.code==='SESSION_CHANGED')emit('expired');if(e.status===409){conflict.value=true;preview.value=false;}}}finally{busy.value=false;}}
async function refresh(){await run(()=>read());}
function prepare(){confirmed.value=false;preview.value=true;}
async function perform(method='PUT'){await run(async()=>{
  if(!pending){if(!grant.value||!contacts.value)return;
    saved.value=false;
    if(method==='PUT'){
      if(!confirmed.value||!preview.value||!canAgree.value)return;
      pending={method,body:{roundId:props.roundId,expectedGrantVersion:grant.value.version,selectedFields:[...selected.value],expectedContactFieldVersions:contacts.value.fieldVersions,agree:true},key:crypto.randomUUID()};
    }else{
      if(!window.confirm('确认撤回本人的联系方式授权？本关系双方将停止后续平台访问，对方的独立意愿保留；平台不能收回对方已经知道的信息。'))return;
      pending={method,body:{roundId:props.roundId,expectedGrantVersion:grant.value.version},key:crypto.randomUUID()};
    }
  }
  clearValues();++epoch;
  try{const g=await api.request<Grant>(path(),pending.method,pending.body,pending.key);if(!alive)return;grant.value=g;saved.value=true;await read(false);
    pending=null;unknown.value=false;preview.value=false;confirmed.value=false;notice.value='本次授权操作已完成，已读取当前有效进度。';
  }catch(e){unknown.value=!(e instanceof ApiFailure)||e.status===0||e.status>=500||e.code==='INVALID_RESPONSE';if(!unknown.value)pending=null;throw e;}
});}
function background(){if(document.hidden){++epoch;clearValues();preview.value=false;confirmed.value=false;return;}if(!busy.value&&!unknown.value&&!preview.value)void run(()=>read(false));}
onMounted(()=>{void refresh();document.addEventListener('visibilitychange',background);window.addEventListener('focus',background);timer=setInterval(background,15000);});
onBeforeUnmount(()=>{alive=false;++epoch;clearInterval(timer);document.removeEventListener('visibilitychange',background);window.removeEventListener('focus',background);clearValues();contacts.value=null;pending=null;});
</script>
<template><section class="consent" aria-labelledby="consent-title"><h2 id="consent-title">本轮联系方式授权</h2><p>互选不会自动分享。双方分别选择本人字段并明确同意，只有双方有效同意才展示选中值。邮箱不分享。</p>
  <p v-if="error" class="message error" role="alert">{{error}}</p><p v-if="notice" class="message success" role="status">{{notice}}</p>
  <p v-if="unknown" class="message warning">{{saved?'本次操作已成功，但最新授权未能刷新。':'上次操作结果尚未确认。'}}可同键重试，或读取最新授权核对。</p>
  <p v-if="conflict" class="message warning">授权或联系方式已变化，请读取最新授权并重新核对。</p>
  <button class="secondary" :disabled="busy" @click="refresh">读取最新授权</button><button v-if="unknown" class="primary" :disabled="busy" @click="perform()">重试同一次授权操作</button>
  <template v-if="grant">
    <p v-if="grant.requiresReconfirmation||grant.otherRequiresReconfirmation" class="message warning">已选联系方式发生变化，本关系停止展示；双方须分别重新核对并确认。</p>
    <p class="consent-status">我的授权：{{grant.myConsent?'已同意':'未有效同意'}} · 对方授权：{{grant.otherConsent?'已同意':'未有效同意'}}</p>
    <div v-if="grant.canViewContacts&&grant.otherSharedContacts" class="shared-contacts" data-testid="shared-contacts"><h3>对方本轮分享</h3><p v-if="grant.otherSharedContacts.phone">手机号：+86 {{grant.otherSharedContacts.phone}}</p><p v-if="grant.otherSharedContacts.wechat">微信：{{grant.otherSharedContacts.wechat}}</p><h3>本人本轮分享</h3><p v-if="grant.mySharedContacts?.phone">手机号：+86 {{grant.mySharedContacts.phone}}</p><p v-if="grant.mySharedContacts?.wechat">微信：{{grant.mySharedContacts.wechat}}</p></div>
    <p v-else class="privacy-note">当前未满足双方有效授权，不展示双方分享值。</p>
    <fieldset v-if="contacts" :disabled="busy||unknown||conflict"><legend>选择本人本次分享字段</legend>
      <label class="check-line"><input v-model="selected" type="checkbox" value="PHONE" :disabled="!contacts.phone" @change="preview=false;confirmed=false"><span>分享本人手机号{{contacts.phone?'（已填写）':'（未填写）'}}</span></label>
      <label class="check-line"><input v-model="selected" type="checkbox" value="WECHAT" :disabled="!contacts.wechat" @change="preview=false;confirmed=false"><span>分享本人微信{{contacts.wechat?'（已填写）':'（未填写）'}}</span></label>
      <button class="secondary" :disabled="!canAgree" @click="prepare">预览本人分享内容</button>
      <div v-if="preview" class="consent-preview"><h3>请核对本人将分享的实际值</h3><p v-if="selected.includes('PHONE')">手机号：+86 {{contacts.phone}}</p><p v-if="selected.includes('WECHAT')">微信：{{contacts.wechat}}</p>
        <label class="check-line"><input v-model="confirmed" type="checkbox"><span>我已核对，明确同意在本轮双方有效授权时分享以上本人联系方式。</span></label><button class="primary" :disabled="!confirmed||!canAgree" @click="perform()">确认本轮授权</button>
      </div>
    </fieldset>
    <div class="form-actions"><button class="text-button" :disabled="busy||unknown" @click="emit('contacts')">维护本人私密联系方式</button><button v-if="grant.myConsent||grant.requiresReconfirmation" class="secondary" :disabled="busy||unknown||conflict" @click="perform('DELETE')">撤回本人联系方式授权</button></div>
  </template>
  <p class="privacy-note">任一方撤回会停止本关系双方后续平台访问，不能收回对方已知信息。页面切回及定期读取当前授权；对方已打开的页面无法被远程擦除。</p>
</section></template>
<style scoped>.consent{margin-top:30px;border-top:1px solid var(--line);padding-top:20px;overflow-wrap:anywhere}.consent p{margin:14px 0;line-height:1.8}.consent>button{margin:4px 8px 8px 0}.consent fieldset{margin:20px 0;padding:18px;border:1px solid var(--line);border-radius:12px}.shared-contacts,.consent-preview{padding:18px;margin-top:18px;background:var(--soft,#f4f7f4);border-radius:12px}.consent legend{font-weight:700}</style>
