<script setup lang="ts">
import { computed } from "vue";
import { BadgeCheck, MapPin, Clock3, Monitor, ArrowUpRight } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { moneyText } from "./discovery-display";
import type { PublicTeacher } from "./match-types";
import { categoryNames, areaNames, modeNames } from "./request-display";

const props = defineProps<{ teacher: PublicTeacher; compact?: boolean; publicProfile?: boolean; openDisabled?: boolean }>();
const emit = defineEmits<{ open: [event: MouseEvent] }>();
const palettes: Record<string, string> = {
  DRAWING: "from-rose-100 to-orange-200 text-rose-800",
  DANCE: "from-violet-100 to-indigo-200 text-violet-800",
  OTHER_SKILL: "from-amber-100 to-orange-200 text-amber-800",
};
const palette = computed(() => palettes[props.teacher.categoryCodes[0]] || "from-sky-100 to-blue-200 text-blue-800");
const rate = computed(() => {
  const { priceMin, priceMax } = props.teacher;
  if (priceMin == null || priceMax == null) return "";
  const min = moneyText(priceMin), max = moneyText(priceMax);
  return min === max ? min : `${min}–${max}`;
});
const introduction = computed(() => {
  const text = props.teacher.introduction?.trim() || "";
  if (!props.compact) return text;
  const highlights = text.split(/\r?\n/).map(line => line.trim()).filter(line =>
    /^(?:教学方向|方向|擅长(?:领域|技能|科目)?|特长|技能|适教(?:范围|年级)?|年级(?:范围)?|科目|学科)\s*[：:]/.test(line));
  return highlights.length ? highlights.join("\n") : text;
});
</script>

<template>
  <div data-ui :class="['teacher-summary flex min-w-0 flex-col gap-5 text-foreground [overflow-wrap:anywhere]', compact && 'h-full']">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="flex min-w-36 flex-1 items-center gap-3">
        <span aria-hidden="true" :class="['grid size-12 shrink-0 place-items-center overflow-hidden rounded-2xl bg-linear-to-br text-xl font-bold [&_img]:size-full [&_img]:object-cover', palette]"><slot name="avatar" :teacher="teacher">{{ [...teacher.displayName][0] }}</slot></span>
        <div class="min-w-0">
          <h2 class="text-xl leading-snug font-bold">{{ teacher.displayName }}</h2>
          <span v-if="publicProfile" class="mt-1 inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-medium text-primary"><BadgeCheck class="size-3.5"/>邮箱已验证</span>
        </div>
      </div>
      <div class="max-w-full shrink-0 text-right">
        <strong class="block text-xl leading-tight font-bold tabular-nums text-primary">{{ rate ? '¥' + rate : '面议' }}</strong>
        <span v-if="rate" class="text-xs text-muted-foreground">元/小时</span>
      </div>
    </div>
    <div class="space-y-2">
      <p class="flex flex-wrap gap-2 text-sm font-semibold text-primary"><span v-for="code in teacher.categoryCodes" :key="code">{{ categoryNames[code] || code }}</span></p>
      <p v-if="introduction" :class="['teacher-introduction whitespace-pre-line text-[15px] leading-7 text-secondary-foreground', compact && 'line-clamp-3']">{{ introduction }}</p>
    </div>
    <div class="flex flex-wrap gap-2">
      <span v-for="code in teacher.areaCodes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-secondary-foreground"><MapPin class="size-3.5"/>{{ areaNames[code] || code }}街道</span>
      <span v-for="code in teacher.serviceModes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2.5 py-1 text-xs text-secondary-foreground"><Monitor class="size-3.5"/>{{ modeNames[code] || code }}</span>
    </div>
    <p class="flex items-start gap-2 text-sm text-secondary-foreground"><Clock3 class="mt-0.5 size-4 shrink-0 text-muted-foreground"/><span :class="compact ? 'line-clamp-2' : 'whitespace-pre-wrap'">{{ teacher.availabilityText || '时间可协商' }}</span></p>
    <Button v-if="compact" :disabled="openDisabled" class="mt-auto h-11 w-full justify-between rounded-xl px-4" @click="emit('open', $event)">查看老师<ArrowUpRight aria-hidden="true"/></Button>
    <p v-else class="rounded-xl bg-slate-50 px-4 py-3 text-sm text-secondary-foreground">资料为老师本人自述，平台未作资格认证。</p>
  </div>
</template>
