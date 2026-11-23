<script setup lang="ts">
import { computed } from "vue";
import { BookOpen, Footprints, Layers, Palette, Shapes } from "@lucide/vue";
import { categoryNames } from "./request-display";
const props = defineProps<{ codes: string[]; active: string }>();
const emit = defineEmits<{ select: [code: string] }>();
const categoryIcons = { DRAWING: Palette, DANCE: Footprints, EXTRACURRICULAR_STUDY: BookOpen, OTHER_SKILL: Shapes };
const categories = computed(() => ["", ...props.codes].map(code => ({
  code, label: categoryNames[code] || code || "全部类别", icon: categoryIcons[code as keyof typeof categoryIcons] || Layers,
})));
</script>
<template>
  <div role="group" aria-label="教学类别" class="flex gap-2 overflow-x-auto py-1">
    <button v-for="c in categories" :key="c.code" type="button" :aria-pressed="active===c.code"
      @click="emit('select', c.code)"
      class="inline-flex min-h-10 shrink-0 items-center gap-2 rounded-full bg-slate-100 px-4 text-sm font-medium text-secondary-foreground transition-colors hover:bg-blue-50 aria-pressed:bg-primary aria-pressed:text-white">
      <component :is="c.icon" class="size-4"/>{{ c.label }}
    </button>
  </div>
</template>
