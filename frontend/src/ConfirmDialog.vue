<script setup lang="ts">
import { ref, watch } from "vue";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";

const props = withDefaults(defineProps<{ open: boolean; title: string; confirmText?: string; cancelText?: string; busy?: boolean }>(), { confirmText: "确认", cancelText: "取消" });
const emit = defineEmits<{ confirm: []; cancel: [] }>();
const cancelButton = ref<InstanceType<typeof Button>>();
let trigger: HTMLElement | null = null;
watch(() => props.open, open => {
  if (open) trigger = document.activeElement instanceof HTMLElement ? document.activeElement : null;
}, { flush: "sync" });
</script>
<template>
  <Dialog :open="open" @update:open="!$event && !busy && emit('cancel')">
    <DialogContent :show-close-button="false" :aria-busy="busy" class="max-h-[90dvh] overflow-y-auto rounded-2xl bg-card"
      @open-auto-focus.prevent="cancelButton?.$el.focus()" @close-auto-focus.prevent="trigger?.focus()" @interact-outside.prevent>
      <DialogHeader><DialogTitle class="text-xl font-bold">{{ title }}</DialogTitle>
        <DialogDescription as="div" class="mt-2 space-y-3 text-left text-[15px] leading-6 text-secondary-foreground"><slot/></DialogDescription>
      </DialogHeader>
      <DialogFooter class="gap-2">
        <Button ref="cancelButton" variant="secondary" :disabled="busy" @click="emit('cancel')">{{ cancelText }}</Button>
        <slot name="extra"/>
        <Button :disabled="busy" @click="emit('confirm')">{{ busy ? '正在处理…' : confirmText }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
