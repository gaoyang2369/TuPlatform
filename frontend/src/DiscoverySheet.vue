<script setup lang="ts">
import { Sheet, SheetContent, SheetDescription, SheetTitle } from "@/components/ui/sheet";
import { useMobileViewport } from "./use-mobile-viewport";
const mobile = useMobileViewport();
defineProps<{ open: boolean; title: string; description: string; backLabel: string }>();
const emit = defineEmits<{ close: []; restoreFocus: [event: Event] }>();
</script>

<template>
  <Sheet :open="open" @update:open="value => { if (!value) emit('close'); }">
    <SheetContent :side="mobile ? 'bottom' : 'right'" class="box-border h-[92dvh] max-h-dvh w-full gap-0 overflow-hidden rounded-t-2xl bg-card text-foreground md:h-dvh md:w-[32rem] md:max-w-[32rem] md:rounded-none" @close-auto-focus="emit('restoreFocus', $event)">
      <header class="flex shrink-0 items-center justify-between gap-2 px-4 py-3 pr-14 md:gap-3 sm:px-8 sm:py-6 sm:pr-16">
        <SheetTitle class="shrink-0 text-xl font-bold">{{ title }}</SheetTitle>
        <slot name="actions"/>
        <button type="button" class="min-h-11 whitespace-nowrap text-sm md:min-h-9 text-secondary-foreground hover:text-primary hover:underline" @click="emit('close')">{{ backLabel }}</button>
        <SheetDescription class="sr-only">{{ description }}</SheetDescription>
      </header>
      <slot/>
    </SheetContent>
  </Sheet>
</template>
