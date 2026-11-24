<script setup lang="ts">
import { computed } from "vue";
import { Check, Minus } from "@lucide/vue";
import type { Match } from "./match-types";

const props = defineProps<{ state: Match["state"]; shared?: boolean }>();
const ended = computed(() => props.state === "WITHDRAWN" || props.state === "REJECTED" || props.state === "closed_no_deal");
const current = computed(() => props.state === "completed" ? 5 : props.state === "in_cooperation" ? 4 :
  props.state === "closed_no_deal" || props.shared ? 3 : props.state === "MUTUAL" ? 2 : props.state === "WITHDRAWN" ? 0 : 1);
const steps = ["发出意向", "对方回应", "双方确认", "交换联系方式", "试课/合作中", "完结互评"];
const compact = ["意向", "回应", "确认", "交换", "合作", "完结"];
</script>
<template>
  <ol aria-label="撮合阶段" class="@container grid grid-cols-6">
    <li v-for="(label, i) in steps" :key="label" :title="label" :aria-current="i === current && !ended ? 'step' : undefined"
      class="relative grid justify-items-center gap-1.5 text-center text-xs" :class="i === current && !ended ? 'font-semibold text-primary' : 'text-muted-foreground'">
      <span v-if="i" aria-hidden="true" class="absolute top-3 right-1/2 h-0.5 w-full" :class="i <= current && !ended ? 'bg-blue-200' : 'bg-slate-200'"/>
      <span aria-hidden="true" class="relative z-10 grid size-6 place-items-center rounded-full ring-4 ring-white"
        :class="i === current && !ended ? 'bg-primary text-white' : i < current ? 'bg-blue-50 text-primary' : 'bg-slate-100'">
        <Check v-if="i < current || (shared && i === 3 && !ended)" class="size-3.5"/><Minus v-else-if="ended && i === current" class="size-3.5"/><template v-else>{{ i + 1 }}</template>
      </span>
      <span class="whitespace-nowrap @max-[32rem]:sr-only">{{ label }}</span>
      <span aria-hidden="true" class="whitespace-nowrap @min-[32rem]:hidden">{{ compact[i] }}</span>
    </li>
  </ol>
</template>
