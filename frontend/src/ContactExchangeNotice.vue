<script setup lang="ts">
import { ShieldCheck } from "@lucide/vue";
import type {Contacts} from './contact-types.ts';
defineProps<{contacts:Contacts;action:string}>();
</script>
<template>
  <div data-ui class="exchange-notice flex items-start gap-2.5 rounded-xl bg-slate-50 p-3.5 text-sm leading-6 text-secondary-foreground">
    <ShieldCheck class="mt-0.5 size-4 shrink-0" aria-hidden="true"/>
    <div v-if="contacts.phone || contacts.wechat"><p>{{ action }}即同意在互选后向对方分享以下联系方式。</p>
      <p class="own-contact mt-1 flex flex-wrap gap-x-4 font-semibold text-foreground [overflow-wrap:anywhere]"><span v-if="contacts.phone">手机 {{ contacts.phone }}</span><span v-if="contacts.wechat">微信 {{ contacts.wechat }}</span></p>
    </div>
    <p v-else>你还未填写联系方式，可以先{{ action }}，互选后再补充。</p>
  </div>
</template>
