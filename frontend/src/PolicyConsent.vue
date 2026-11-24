<script setup lang="ts">
import PolicyLinks from "./PolicyLinks.vue";
defineProps<{disabled?: boolean}>();
const accepted = defineModel<boolean>({required:true});
</script>
<template>
  <label class="flex items-start gap-2 text-sm leading-6">
    <input v-model="accepted" type="checkbox" :disabled="disabled" required class="mt-1.5 shrink-0 accent-blue-600">
    <span>我已阅读并同意<PolicyLinks/></span>
  </label>
</template>
