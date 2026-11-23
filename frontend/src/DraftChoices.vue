<script setup lang="ts" generic="T extends string | number">
import { useId } from "vue";

const model = defineModel<T>({ required: true });
defineProps<{ label: string; options: T[]; names: Record<string, string>; emptyLabel?: string }>();
const name = useId();
</script>
<template>
  <fieldset tabindex="-1" class="min-w-0">
    <legend class="mb-2 text-sm font-semibold">{{ label }}</legend>
    <div class="flex flex-wrap gap-2">
      <label v-for="value in (emptyLabel ? ['', ...options] : options)" :key="value" class="relative inline-flex cursor-pointer">
        <input v-model="model" type="radio" :name="name" :value="value" class="peer absolute inset-0 size-full cursor-pointer opacity-0">
        <span class="rounded-full border border-border bg-card px-3.5 py-2 text-sm text-secondary-foreground peer-checked:border-blue-200 peer-checked:bg-blue-50 peer-checked:font-semibold peer-checked:text-primary peer-focus-visible:outline-2 peer-focus-visible:outline-primary">{{ value === '' ? emptyLabel : names[String(value)] || value }}</span>
      </label>
    </div>
    <slot/>
  </fieldset>
</template>
