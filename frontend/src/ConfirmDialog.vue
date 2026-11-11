<script setup lang="ts">
import {nextTick,onBeforeUnmount,ref,useId,watch} from 'vue';
const props=withDefaults(defineProps<{open:boolean;title:string;confirmText?:string;busy?:boolean}>(),{confirmText:'确认',busy:false});
const emit=defineEmits<{confirm:[];cancel:[]}>();
const dialog=ref<HTMLDialogElement|null>(null),cancelButton=ref<HTMLButtonElement|null>(null);
const titleId=useId(),descriptionId=useId();
let previousFocus:HTMLElement|null=null;
watch(()=>props.open,async open=>{
  await nextTick();
  if(open&&props.open&&dialog.value&&!dialog.value.open){previousFocus=document.activeElement as HTMLElement;dialog.value.showModal();cancelButton.value?.focus();}
  else if(!props.open&&dialog.value?.open){dialog.value.close();previousFocus?.focus();}
},{immediate:true});
onBeforeUnmount(()=>{dialog.value?.close();previousFocus?.focus();});
</script>
<template><Teleport to="body"><dialog ref="dialog" class="native-dialog confirm-dialog" :aria-labelledby="titleId" :aria-describedby="descriptionId" :aria-busy="busy" @cancel.prevent="!busy&&emit('cancel')">
  <div class="dialog-card"><p class="eyebrow">请确认</p><h2 :id="titleId">{{title}}</h2><div :id="descriptionId" class="confirm-description"><slot/></div><div class="form-actions"><button ref="cancelButton" class="secondary" :disabled="busy" @click="emit('cancel')">取消</button><button class="primary" :disabled="busy" @click="emit('confirm')">{{busy?'正在处理…':confirmText}}</button></div></div>
</dialog></Teleport></template>
<style scoped>
.confirm-dialog{position:fixed;inset:0;margin:auto;max-height:calc(100dvh - 32px);color:var(--ink,#253e34)}
.confirm-dialog .dialog-card{background:#fcfcf8;border:1px solid var(--line)}
.confirm-dialog .eyebrow{margin:0 0 12px}.confirm-description{margin-top:16px;color:var(--muted);line-height:1.8;font-size:14px}.confirm-dialog .form-actions{justify-content:flex-end}
@media(max-width:520px){.confirm-dialog .form-actions{flex-wrap:nowrap}.confirm-dialog .form-actions button{flex:1}}
</style>
