<script setup lang="ts">
import type { Component } from "vue";
import { ChevronDown } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
defineProps<{ label: string; shortLabel?: string; icon: Component; options: {code: string; label: string}[]; selected: string[] }>();
const emit = defineEmits<{ toggle: [code: string] }>();
</script>
<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button variant="outline" :aria-label="label" class="h-10 gap-1 rounded-xl border-border bg-white px-2 has-[>svg]:px-2 sm:gap-2 sm:has-[>svg]:px-3">
        <component :is="icon" :class="selected.length ? 'text-primary' : 'text-muted-foreground'"/>
        <template v-if="shortLabel"><span class="sm:hidden">{{ shortLabel }}</span><span class="hidden sm:inline">{{ label }}</span></template>
        <template v-else>{{ label }}</template>
        <span v-if="selected.length" class="text-xs text-primary">{{ selected.length }}</span><ChevronDown class="hidden size-3.5 text-muted-foreground sm:block"/>
      </Button>
    </PopoverTrigger>
    <PopoverContent align="start" class="box-border w-60 rounded-2xl p-3">
      <fieldset class="space-y-1"><legend class="mb-2 px-2 text-sm font-semibold">{{ label }}（可多选）</legend>
        <p v-if="!options.length" class="px-2 py-3 text-sm text-muted-foreground">暂无可选项</p>
        <label v-for="option in options" :key="option.code" class="flex min-h-10 cursor-pointer items-center gap-3 rounded-lg px-2 text-sm hover:bg-slate-50">
          <input type="checkbox" :checked="selected.includes(option.code)" class="size-4 accent-primary" @change="emit('toggle', option.code)"/>{{ option.label }}
        </label>
      </fieldset>
    </PopoverContent>
  </Popover>
</template>
