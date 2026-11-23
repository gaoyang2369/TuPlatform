<script setup lang="ts">
import { CircleCheck, CircleAlert, X } from "@lucide/vue";
import { onBeforeUnmount, ref, watch } from "vue";

const props = defineProps<{ message: string; kind: "error" | "success"; fieldErrors?: Record<string, string> }>();
const visible = ref(true);
let timer: ReturnType<typeof setTimeout> | undefined;
function pause() { clearTimeout(timer); }
function resume() { pause(); timer = setTimeout(() => { visible.value = false; }, 5000); }
watch(() => props.message, () => { visible.value = true; resume(); }, { immediate: true });
onBeforeUnmount(pause);
</script>


<template>
  <div v-if="visible" class="feedback-toast fixed top-3 left-1/2 z-40 w-max max-w-[min(34rem,calc(100%-2rem))] -translate-x-1/2 rounded-2xl border border-border bg-white p-4 text-foreground shadow-lg sm:top-6"
    @mouseenter="pause" @mouseleave="resume" @focusin="pause" @focusout="resume">
    <div :role="kind === 'error' ? 'alert' : 'status'" class="flex items-start gap-3 text-sm leading-6" aria-atomic="true">
      <component :is="kind === 'error' ? CircleAlert : CircleCheck" class="mt-0.5 size-5 shrink-0" :class="kind === 'error' ? 'text-red-700' : 'text-emerald-700'"/>
      <div class="min-w-0 [overflow-wrap:anywhere]">{{ message }}
        <ul v-if="fieldErrors" class="mt-1 space-y-1"><li v-for="(value, field) in fieldErrors" :key="field"><a :href="'#field-' + field" class="text-primary underline">{{ value }}</a></li></ul>
      </div>
      <button type="button" class="ml-auto grid size-7 shrink-0 place-items-center rounded-full hover:bg-slate-100" aria-label="关闭提示" @click="visible = false"><X class="size-4"/></button>
    </div>
  </div>
</template>
