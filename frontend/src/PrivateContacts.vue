<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,reactive,ref} from 'vue';
import {api,ApiFailure} from './account-api.ts';
import type {Contacts} from './contact-types.ts';
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
<template><section class="private-contacts"><p class="eyebrow">PRIVATE CONTACTS</p><h1>本人私密联系方式</h1><p>手机号、微信均选填，不影响发布或投递；只在互选后按本轮逐项授权分享。邮箱不分享。</p>
  <p v-if="error" class="message error" role="alert">{{error}}</p><p v-if="notice" class="message success" role="status">{{notice}}</p>
  <button class="secondary" :disabled="busy" @click="read">读取最新联系方式</button>
  <div v-if="latest" class="message warning"><h2>最新保存内容</h2><p>手机号：{{latest.phone?'+86 '+latest.phone:'未填写'}}；微信：{{latest.wechat||'未填写'}}</p><button class="secondary" @click="useLatest()">载入最新内容，放弃输入</button><button class="secondary" @click="useLatest(true)">已核对，保留输入</button></div>
  <p v-if="conflict" class="message warning">保存版本已变化，先读取最新内容并核对。若上次响应丢失，可通过最新保存内容确认结果。</p>
  <form v-if="saved" class="form-card profile-form" @submit.prevent="!busy&&!conflict&&dirty&&(confirming='save')"><fieldset :disabled="busy"><legend>仅本人维护</legend>
    <label for="contact-phone">手机号（选填，+86）<input id="contact-phone" v-model="form.phone" type="tel" inputmode="numeric" autocomplete="tel-national" pattern="1[0-9]{10}"><small>暂支持中国大陆 +86；以1开头的11位数字。清空后保存即删除。</small></label>
    <label for="contact-wechat">微信号（选填）<input id="contact-wechat" v-model="form.wechat" autocomplete="off"><small>1—64个字符，不含空格、换行或控制字符，请本人核对。清空后保存即删除。</small></label>
    <p class="privacy-note">修改或删除已用于授权的字段，将使受影响关系停止展示，并要求双方重新确认。未选字段修改不影响无关授权。</p>
    <div class="contact-save-bar"><span class="save-state" role="status"><span class="state-dot" :class="{dirty}" aria-hidden="true"></span>{{busy?'正在保存…':dirty?'尚未保存的修改':'所有修改已保存'}}</span><button class="primary" :disabled="conflict||!dirty">保存私密联系方式</button></div>
  </fieldset></form>
  <ConfirmDialog :open="confirming!==null" :title="confirming==='leave'?'放弃尚未保存的修改？':'保存私密联系方式？'" :confirm-text="confirming==='leave'?'放弃并离开':'确认保存'" @cancel="cancelConfirm" @confirm="confirm"><p>{{confirming==='leave'?'离开后，本次输入的修改不会保存。你也可以取消，继续编辑。':'这些信息仅由你本人维护，保存不会自动分享给他人。'}}</p><p v-if="confirming==='save'" class="confirm-impact">若修改或删除已分享的字段，相关关系将停止展示联系方式，需双方重新确认。</p></ConfirmDialog>
</section></template>
<style scoped>.private-contacts{max-width:760px;margin:auto;overflow-wrap:anywhere}.private-contacts>p{margin:18px 0;line-height:1.8}.private-contacts>.secondary{margin-bottom:20px}.private-contacts .message button{margin:6px}.private-contacts legend{font-weight:700}</style>
<style scoped>.contact-save-bar{display:flex;align-items:center;justify-content:space-between;gap:24px;border-top:1px solid var(--line);padding-top:22px;margin-top:24px}.contact-save-bar .save-state{display:flex;align-items:center;gap:8px}.state-dot{width:7px;height:7px;border-radius:50%;background:#66845f;flex:none}.state-dot.dirty{background:#b4863d}.confirm-impact{margin-top:14px;padding:14px;background:#eef2e8;border-radius:8px}@media(max-width:520px){.contact-save-bar{align-items:stretch;flex-direction:column;gap:16px}.contact-save-bar .primary{width:100%}}</style>
