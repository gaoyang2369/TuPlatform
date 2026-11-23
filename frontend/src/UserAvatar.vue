<script setup lang="ts">
import { computed, ref, watch } from "vue";

const props = defineProps<{ url?: string | null; name: string; colorKey?: string }>();
const failed = ref(false);
watch(() => props.url, () => { failed.value = false; });
const palettes = [
  "from-indigo-50 to-indigo-200 text-indigo-800",
  "from-amber-50 to-orange-200 text-amber-900",
  "from-teal-50 to-teal-200 text-teal-800",
  "from-violet-50 to-purple-200 text-violet-800",
  "from-rose-50 to-rose-200 text-rose-800",
  "from-stone-100 to-stone-300 text-stone-700",
];
const palette = computed(() => {
  let hash = 0;
  for (const character of props.colorKey || props.name) {
    hash = (Math.imul(hash, 31) + character.codePointAt(0)!) >>> 0;
  }
  return palettes[hash % palettes.length];
});
</script>

<template>
  <span aria-hidden="true" class="inline-grid shrink-0 place-items-center overflow-hidden bg-linear-to-br font-bold" :class="palette">
    <img v-if="url && !failed" :key="url" :src="url" alt="" class="size-full object-cover" @error="failed = true">
    <template v-else>{{ [...name.trim()][0] || '我' }}</template>
  </span>
</template>
