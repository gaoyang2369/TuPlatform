<script setup lang="ts">
import type {Contacts} from './contact-types.ts';
defineProps<{contacts:Contacts;action:string}>();
</script>
<template><div class="exchange-notice">
  <template v-if="contacts.phone||contacts.wechat"><p>{{action}}即同意在互选后向对方分享以下联系方式。</p><p class="own-contact"><span v-if="contacts.phone">手机 {{contacts.phone}}</span><span v-if="contacts.wechat">微信 {{contacts.wechat}}</span></p></template>
  <p v-else>你还未填写联系方式，可以先{{action}}，互选后再补充。</p>
</div></template>
<style scoped>.exchange-notice{padding:12px 16px;border-radius:10px;background:#f1f5ec;font-size:13px;color:var(--muted);overflow-wrap:anywhere}.exchange-notice p{margin:0;line-height:1.7}.exchange-notice .own-contact{display:flex;flex-wrap:wrap;gap:4px 20px;margin-top:4px;color:var(--green)}</style>
