<script setup lang="ts">
withDefaults(defineProps<{ showNote?: boolean }>(), { showNote: true });
</script>
<template>
  <footer class="sticky bottom-0 z-10 mt-auto shrink-0 space-y-2 border-t border-border bg-white/95 px-5 pt-3 pb-[calc(0.75rem+env(safe-area-inset-bottom))] backdrop-blur sm:px-8 sm:pt-4 sm:pb-5">
    <p v-if="showNote" class="text-center text-xs leading-5 text-secondary-foreground">发出意向，对方接受后即可查看彼此的联系方式。</p>
    <slot/>
  </footer>
</template>
