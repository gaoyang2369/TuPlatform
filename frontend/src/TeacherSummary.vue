<script setup lang="ts">
import { computed } from "vue";
import { BadgeCheck, MapPin, Clock3, Monitor, ArrowRight } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { moneyText } from "./discovery-display";
import type { PublicTeacher } from "./match-types";
import { categoryNames, areaNames, modeNames } from "./request-display";

const props = defineProps<{ teacher: PublicTeacher; compact?: boolean; publicProfile?: boolean; openDisabled?: boolean }>();
const emit = defineEmits<{ open: [event: MouseEvent] }>();
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
  for (const character of props.teacher.id || props.teacher.displayName) {
    hash = (Math.imul(hash, 31) + character.codePointAt(0)!) >>> 0;
  }
  return palettes[hash % palettes.length];
});
const rate = computed(() => {
  const { priceMin, priceMax } = props.teacher;
  if (priceMin == null || priceMax == null) return "";
  const min = moneyText(priceMin), max = moneyText(priceMax);
  return min === max ? min : `${min}–${max}`;
});
const cardIntro = computed(() => {
  const directions: string[] = [];
  const grades: string[] = [];
  const subjects: string[] = [];
  const prose: string[] = [];
  for (const rawLine of (props.teacher.introduction || "").split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line) continue;
    const field = line.match(/^(教学方向|方向|擅长(?:领域|技能|科目)?|特长|技能|适教(?:范围|年级)?|年级(?:范围)?|科目|学科)\s*[：:]\s*(.+)$/);
    if (!field) {
      prose.push(line);
      continue;
    }
    const [, label, value] = field;
    if (/^(?:适教|年级)/.test(label)) {
      grades.push(value);
    } else if (label === "科目" || label === "学科") {
      subjects.push(value.replace(/\s*、\s*/g, " / "));
    } else {
      directions.push(value);
    }
  }
  return {
    direction: directions.length ? directions.join(" · ") : prose.join("\n"),
    metadata: [...grades, ...subjects].join(" · "),
  };
});
</script>

<template>
  <div data-ui :class="['teacher-summary flex min-w-0 flex-col gap-5 text-foreground [overflow-wrap:anywhere]', compact && 'h-full']">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="flex min-w-36 flex-1 items-center gap-3">
        <span aria-hidden="true" :class="['grid size-12 shrink-0 place-items-center overflow-hidden rounded-2xl bg-linear-to-br text-xl font-bold [&_img]:size-full [&_img]:object-cover', palette]"><slot name="avatar" :teacher="teacher">{{ [...teacher.displayName][0] }}</slot></span>
        <div class="min-w-0">
          <h2 class="text-xl leading-snug font-bold">{{ teacher.displayName }}</h2>
          <span v-if="publicProfile" class="mt-1 inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs text-slate-600"><BadgeCheck class="size-3.5 text-blue-600"/>邮箱已验证</span>
        </div>
      </div>
      <div class="max-w-full shrink-0 text-right">
        <strong class="block text-xl leading-tight font-bold tabular-nums text-foreground">{{ rate ? '¥' + rate : '面议' }}</strong>
        <span v-if="rate" class="text-xs text-muted-foreground">元/小时</span>
      </div>
    </div>
    <div v-if="compact" class="teacher-introduction space-y-1.5">
      <p v-if="cardIntro.direction" class="line-clamp-2 whitespace-pre-line text-[15px] leading-6 font-medium">{{ cardIntro.direction }}</p>
      <p v-if="cardIntro.metadata" class="text-sm leading-6 text-secondary-foreground">{{ cardIntro.metadata }}</p>
    </div>
    <p v-else-if="teacher.introduction" class="teacher-introduction whitespace-pre-line text-[15px] leading-7 text-secondary-foreground">{{ teacher.introduction }}</p>
    <div class="flex flex-wrap gap-2">
      <span v-for="code in teacher.categoryCodes" :key="code" class="rounded-full bg-stone-100 px-2.5 py-1 text-xs text-secondary-foreground">{{ categoryNames[code] || code }}</span>
      <span v-for="code in teacher.areaCodes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-secondary-foreground"><MapPin class="size-3.5"/>{{ areaNames[code] || code }}街道</span>
      <span v-for="code in teacher.serviceModes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-secondary-foreground"><Monitor class="size-3.5"/>{{ modeNames[code] || code }}</span>
    </div>
    <p class="flex items-start gap-2 text-sm text-secondary-foreground"><Clock3 class="mt-0.5 size-4 shrink-0 text-muted-foreground"/><span :class="compact ? 'line-clamp-2' : 'whitespace-pre-wrap'">{{ teacher.availabilityText || '时间可协商' }}</span></p>
    <Button v-if="compact" variant="secondary" :disabled="openDisabled" class="mt-auto h-11 w-full justify-between rounded-xl bg-slate-100 px-4 text-slate-900 transition-colors group-hover:bg-primary group-hover:text-white hover:bg-primary focus-visible:bg-primary focus-visible:text-white motion-reduce:transition-none" @click="emit('open', $event)">查看老师<ArrowRight aria-hidden="true"/></Button>
    <p v-else class="rounded-xl bg-slate-50 px-4 py-3 text-sm text-secondary-foreground">资料为老师本人自述，平台未作资格认证。</p>
  </div>
</template>
