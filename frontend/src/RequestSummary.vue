<script setup lang="ts">
import { computed } from "vue";
import { ArrowRight, BookOpen, CalendarClock, Clock3, MapPin, Monitor, Users, UserRound } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { moneyText, timeSummary } from "./discovery-display";
import { categoryNames, areaNames, stageNames, modeNames, weekdayNames, minuteText } from "./request-display";
const props = defineProps<{ fields: Record<string, unknown>; displayName?: string; compact?: boolean; openDisabled?: boolean; districtLabel?: string }>();
const emit = defineEmits<{ open: [event: MouseEvent] }>();
const text = (key: string) => String(props.fields[key] ?? "").trim();
const study = computed(() => props.fields.categoryCode === "EXTRACURRICULAR_STUDY");
const audience = computed(() => study.value ? [text("grade"), text("subject")].filter(Boolean).join(" · ") : stageNames[text("audienceStageCode")]);
const category = computed(() => categoryNames[text("categoryCode")] || text("categoryCode"));
const mode = computed(() => modeNames[text("serviceMode")]);
const street = computed(() => text("street") || (areaNames[text("areaCode")] ? areaNames[text("areaCode")] + "街道" : ""));
const place = computed(() => [street.value, text("area_hint")].filter(Boolean).join(" · ") || (mode.value === "线上" ? "" : props.districtLabel));
const budget = computed(() => {
  const min = moneyText(props.fields.budgetMin), max = moneyText(props.fields.budgetMax);
  if (!min && !max) return "待补充";
  return "¥" + (min === max ? min : (min || "待补充") + "–" + (max || "待补充"));
});
const time = computed(() => {
  const pref = props.fields.availabilityPreference as {mode:string;weekdays:number[];periods:string[]} | undefined;
  if (pref) {
    if (pref.mode === "NEGOTIABLE") return "时间可协商";
    const days = [...pref.weekdays].sort().join(",");
    const label = days === "6,7" ? "周末" : days === "1,2,3,4,5" ? "工作日" : days === "1,2,3,4,5,6,7" ? "每天" : pref.weekdays.map(day => weekdayNames[day-1]).join("、");
    const periods: Record<string,string> = { MORNING:"上午", AFTERNOON:"下午", EVENING:"晚上" };
    return (label || "日期待补充") + " · " + (pref.periods.map(code => periods[code]).join("、") || "时段不限") + "（具体时间可协商）";
  }
  const slots = (props.fields.availableSlots || []) as {weekday:number;startMinute:number;endMinute:number}[];
  return slots.map(s => weekdayNames[s.weekday-1] + " " + minuteText(s.startMinute) + "—" + minuteText(s.endMinute)).join("\n") || "待补充";
});
const facts = computed(() => {
  const freq = props.fields.frequency as {mode?:string;count?:number} | undefined;
  const frequency = freq?.mode === "NEGOTIABLE" ? "频率可协商" : freq?.mode === "PER_WEEK" ? "每周" + (freq.count ?? "待补充") + "次" : "频率待补充";
  return [
    { label: study.value ? "年级与科目" : "服务对象", icon: BookOpen, lines: [audience.value || "待补充", category.value] },
    { label: "授课安排", icon: Clock3, lines: [frequency, text("durationHours") ? text("durationHours") + " 小时/次" : "时长待补充"] },
    { label: "方式与地点", icon: props.fields.serviceMode === "ONLINE" ? Monitor : Users, lines: [mode.value, street.value, text("area_hint")].filter(Boolean) },
    { label: "可约时间", icon: CalendarClock, lines: [time.value, text("expectedStartDate") && "期望开始 " + text("expectedStartDate")] },
  ];
});
</script>
<template>
  <article :class="['request-summary flex min-w-0 flex-col gap-4 text-foreground [overflow-wrap:anywhere]', compact && 'h-full']">
    <div class="flex flex-wrap items-start justify-between gap-x-3 gap-y-2">
      <div class="min-w-28 flex-1 space-y-1">
        <span v-if="compact && category" class="text-xs font-semibold text-muted-foreground">{{ category }}</span>
        <h2 class="text-xl leading-snug font-bold">{{ fields.title || '未填写标题' }}</h2>
      </div>
      <div class="max-w-full text-right" aria-label="预算"><strong class="block text-xl leading-tight font-bold tabular-nums">{{ budget }}</strong><span class="text-xs text-muted-foreground">元/小时</span></div>
    </div>
    <template v-if="compact">
      <div class="space-y-1">
        <p v-if="audience" class="text-[15px] font-semibold">{{ audience }}</p>
        <p v-if="fields.goal" class="line-clamp-2 text-[15px] leading-6 text-secondary-foreground">{{ fields.goal }}</p>
      </div>
      <div class="flex flex-wrap gap-2 text-xs text-secondary-foreground">
        <span v-if="place" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1"><MapPin class="size-3.5 shrink-0"/>{{ place }}</span>
        <span class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1"><component :is="fields.serviceMode === 'ONLINE' ? Monitor : Users" class="size-3.5"/>{{ mode || '方式留待沟通' }}</span>
      </div>
      <p class="flex items-start gap-2 text-sm text-secondary-foreground"><CalendarClock class="mt-0.5 size-4 shrink-0"/>{{ timeSummary(fields) }}</p>
      <p v-if="displayName" class="mt-auto flex items-center gap-2 text-xs text-muted-foreground"><UserRound class="size-3.5"/>{{ displayName }}</p>
      <Button variant="secondary" :disabled="openDisabled" class="h-11 w-full justify-between rounded-xl bg-slate-100 text-slate-900 group-hover:bg-primary group-hover:text-white hover:bg-primary focus-visible:bg-primary focus-visible:text-white motion-reduce:transition-none" @click="emit('open', $event)">查看需求<ArrowRight/></Button>
    </template>
    <template v-else>
      <p v-if="displayName" class="text-sm text-secondary-foreground">发布者：{{ displayName }}</p>
      <section class="space-y-2"><h3 class="text-xs font-semibold text-slate-500">目标与现状</h3><p v-for="(paragraph, i) in (text('goal') || '待补充').split(/\n+/)" :key="i" class="text-[15px] leading-7">{{ paragraph }}</p></section>
      <dl class="grid grid-cols-2 gap-3">
        <div v-for="fact in facts" :key="fact.label" class="min-w-0 rounded-xl bg-slate-50 p-3.5">
          <dt class="flex items-center gap-1.5 text-xs font-semibold text-slate-500"><component :is="fact.icon" class="size-3.5"/>{{ fact.label }}</dt>
          <dd class="mt-1 whitespace-pre-line text-[15px]">{{ fact.lines.filter(Boolean).join(' · ') || '待补充' }}</dd>
        </div>
      </dl>
      <section v-for="[label, value] in [['时间备注', text('availabilityNote')], ['对老师的期待', text('tutorExpectations')]].filter(([,v]) => v)" :key="label" class="space-y-1.5">
        <h3 class="text-xs font-semibold text-slate-500">{{ label }}</h3><p class="whitespace-pre-line text-[15px] leading-7">{{ value }}</p>
      </section>
    </template>
  </article>
</template>
