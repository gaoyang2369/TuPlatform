<script setup lang="ts">
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet";
defineProps<{ open: boolean; title: string; description: string; backLabel: string }>();
const emit = defineEmits<{ close: []; restoreFocus: [event: Event] }>();
</script>

<template>
  <Sheet :open="open" @update:open="value => { if (!value) emit('close'); }">
    <SheetContent side="right" class="box-border h-dvh w-full gap-0 overflow-hidden bg-card text-foreground sm:w-[32rem] sm:max-w-[32rem]" @close-auto-focus="emit('restoreFocus', $event)">
      <header class="flex shrink-0 items-center justify-between gap-3 px-5 py-4 pr-14 sm:px-8 sm:py-6 sm:pr-16">
        <SheetTitle class="text-xl font-bold">{{ title }}</SheetTitle>
        <button type="button" class="min-h-9 text-sm text-secondary-foreground hover:text-primary hover:underline" @click="emit('close')">{{ backLabel }}</button>
        <SheetDescription class="sr-only">{{ description }}</SheetDescription>
      </header>
      <slot/>
    </SheetContent>
  </Sheet>
</template>
