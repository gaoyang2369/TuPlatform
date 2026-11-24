<script setup lang="ts">
import UserAvatar from "./UserAvatar.vue";
import { computed } from "vue";
import { BadgeCheck, MapPin, Clock3, Info, BookOpen, GraduationCap, Monitor, ArrowRight, Sparkles, FileText } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { moneyText } from "./discovery-display";
import { teachingIntroduction } from "./teacher-introduction";
import type { PublicTeacher } from "./match-types";
import { categoryNames, areaNames, modeNames } from "./request-display";

const props = defineProps<{ teacher: PublicTeacher; compact?: boolean; publicProfile?: boolean; openDisabled?: boolean }>();
const emit = defineEmits<{ open: [event: MouseEvent] }>();
const rate = computed(() => {
  const { priceMin, priceMax } = props.teacher;
  if (priceMin == null || priceMax == null) return "";
  const min = moneyText(priceMin), max = moneyText(priceMax);
  return min === max ? min : `${min}–${max}`;
});
const introduction = computed(() => teachingIntroduction(props.teacher.introduction));
const cardDirection = computed(() => introduction.value.direction || introduction.value.paragraphs.map(p => p.text).join("\n"));
const grade = computed(() => props.teacher.teachGrades?.join(" / ") || introduction.value.grade);
const subject = computed(() => props.teacher.teachSubjects?.join(" / ") || introduction.value.subject);
const education = computed(() => [props.teacher.collegeMajor, props.teacher.gradeLevel].filter(Boolean).join(" · "));
const supportsTrial = computed(() => props.teacher.trialPolicy && props.teacher.trialPolicy !== "暂不支持试课");
const cardMetadata = computed(() => [grade.value, subject.value].filter(Boolean).join(" · "));
const facts = computed(() => [
  { label: "年级范围", text: grade.value, icon: GraduationCap },
  { label: "教授科目", text: subject.value, icon: BookOpen },
  { label: "服务街道", text: props.teacher.areaCodes.map(code => (areaNames[code] || code) + "街道").join("、") || (props.teacher.serviceModes.includes("ONLINE") ? "线上授课" : ""), icon: MapPin },
  { label: "可授时段", text: props.teacher.availabilityText || "时间可协商", icon: Clock3 },
  { label: "首次试课政策", text: props.teacher.trialPolicy, icon: Sparkles },
].filter(fact => fact.text));
</script>

<template>
  <div :class="['teacher-summary flex min-w-0 flex-col gap-5 text-foreground [overflow-wrap:anywhere]', compact && 'h-full']">
    <header :class="publicProfile && !compact ? 'space-y-4 rounded-2xl bg-gradient-to-br from-blue-50 via-slate-50 to-white p-5 ring-1 ring-inset ring-blue-100' : 'space-y-3'">
    <div class="flex flex-wrap items-start justify-between gap-3">
      <div class="flex min-w-36 flex-1 items-center gap-3">
        <slot name="avatar" :teacher="teacher"><UserAvatar :url="teacher.avatar_url" :name="teacher.displayName" :color-key="teacher.id" class="rounded-2xl text-xl" :class="publicProfile && !compact ? 'size-16 ring-2 ring-white' : 'size-12'"/></slot>
        <div class="min-w-0">
          <h2 class="text-xl leading-snug font-bold">{{ teacher.displayName }}</h2>
          <div v-if="publicProfile" class="mt-1 flex flex-wrap gap-1.5">
            <span class="inline-flex items-center gap-1 rounded-full bg-blue-50 px-2 py-0.5 text-xs font-semibold text-blue-700"><BadgeCheck class="size-3.5"/>{{ teacher.is_school_verified ? '本校学籍已核验' : '邮箱已验证' }}</span>
            <span v-if="teacher.is_skill_verified" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-700"><BadgeCheck class="size-3.5"/>技能证书已核验</span>
          </div>
        </div>
      </div>
      <div class="max-w-full shrink-0 text-right">
        <strong class="block text-xl leading-tight font-bold tabular-nums text-foreground">{{ rate ? '¥' + rate : '面议' }}</strong>
        <span v-if="rate" class="text-xs text-muted-foreground">元/小时</span>
      </div>
    </div>
    <div v-if="education || supportsTrial || (publicProfile && !compact && teacher.schoolName)" class="-mt-1 flex flex-wrap items-center gap-2">
      <p class="text-sm font-medium text-secondary-foreground">
        <span v-if="publicProfile && !compact && teacher.schoolName" class="mr-2">{{ teacher.schoolName }}</span>{{ education }}
      </p>
      <span v-if="supportsTrial" class="inline-flex items-center gap-1 rounded-full bg-orange-50 px-2.5 py-1 text-xs font-semibold text-orange-700"><Sparkles class="size-3.5"/>{{ teacher.trialPolicy?.includes('免费沟通') ? '可试聊' : '可试课' }}</span>
    </div>
    </header>
    <template v-if="publicProfile && !compact">
      <p v-if="introduction.direction" class="text-base font-semibold">{{ introduction.direction }}</p>
      <dl class="grid grid-cols-2 gap-3">
        <div v-for="fact in facts" :key="fact.label" class="min-w-0 rounded-xl bg-slate-50 p-3.5">
          <dt class="mb-1.5 flex items-center gap-1.5 text-xs font-semibold text-slate-500"><component :is="fact.icon" class="size-3.5"/>{{ fact.label }}</dt>
          <dd class="whitespace-pre-line text-[15px] font-medium">{{ fact.text }}</dd>
        </div>
      </dl>
      <div class="flex flex-wrap gap-2 text-xs text-secondary-foreground">
        <span v-for="code in teacher.categoryCodes" :key="code" class="rounded-full bg-stone-100 px-2.5 py-1">{{ categoryNames[code] || code }}</span>
        <span v-for="code in teacher.serviceModes" :key="code" class="rounded-full bg-slate-100 px-2.5 py-1">{{ modeNames[code] || code }}</span>
      </div>
      <section v-if="teacher.teachingStyleTags?.length" class="space-y-2">
        <h3 class="text-lg font-bold">教学风格</h3>
        <div class="flex flex-wrap gap-2"><span v-for="tag in teacher.teachingStyleTags" :key="tag" class="rounded-full bg-blue-50 px-3 py-1 text-sm text-blue-800">{{ tag }}</span></div>
      </section>
      <p v-if="teacher.yearsExp" class="text-[15px] font-semibold"><GraduationCap class="mr-1 inline size-4 text-primary"/>{{ teacher.yearsExp }}</p>
      <section v-for="part in introduction.paragraphs.filter(p => p.label !== '服务说明')" :key="part.label" class="space-y-1.5">
        <h3 class="text-lg font-bold">{{ part.label }}</h3>
        <p class="whitespace-pre-line text-[15px] leading-7 text-secondary-foreground">{{ part.text }}</p>
      </section>
      <section v-if="teacher.casesJson?.some(c => c.student_grade || c.focus || c.outcome)" class="space-y-3">
        <h3 class="text-lg font-bold">过往辅导案例</h3>
        <article v-for="(item, index) in teacher.casesJson" :key="index" class="space-y-2 rounded-2xl bg-slate-50 p-4">
          <span class="text-xs font-semibold text-primary">{{ item.student_grade }}</span>
          <h4 class="font-semibold">{{ item.focus }}</h4>
          <p class="whitespace-pre-line text-[15px] leading-6 text-secondary-foreground">{{ item.outcome }}</p>
        </article>
      </section>
      <section class="space-y-2"><h3 class="text-lg font-bold">服务说明</h3>
        <p class="whitespace-pre-line text-[15px] leading-7 text-secondary-foreground">{{ introduction.paragraphs.find(p => p.label === '服务说明')?.text || '授课内容、具体时间和费用由双方沟通确认。' }}</p>
      </section>
      <p class="flex items-start gap-2 rounded-xl bg-blue-50 p-3 text-sm text-secondary-foreground"><Info class="mt-0.5 size-4 shrink-0 text-primary"/>{{ teacher.is_school_verified ? '该老师已完成本校在读学籍核验；具体授课安排与费用由双方协商确认。' : '该老师已通过邮箱验证，学籍信息核验中；具体授课安排由双方协商确认。' }}</p>
      <section v-if="teacher.resumePdfUrl" class="space-y-2 rounded-2xl border border-blue-100 bg-blue-50 p-4">
        <h3 class="flex items-center gap-2 font-bold"><FileText class="size-5 text-primary"/>完整简历（PDF）</h3>
        <p class="break-all text-sm text-secondary-foreground">{{ teacher.resumePdfName }}</p>
        <div class="flex gap-3 text-sm font-semibold text-primary">
          <a class="py-2 underline" target="_blank" rel="noopener" :href="teacher.resumePdfUrl">查看完整简历</a>
          <a class="py-2 underline" :href="teacher.resumePdfUrl + '?download=true'" :download="teacher.resumePdfName || '简历.pdf'">下载 PDF</a>
        </div>
      </section>
    </template>
    <template v-else>
      <div v-if="compact" class="teacher-introduction space-y-1.5">
        <p v-if="cardDirection" class="line-clamp-2 whitespace-pre-line text-[15px] leading-6 font-medium">{{ cardDirection }}</p>
        <p v-if="cardMetadata" class="text-sm leading-6 text-secondary-foreground">{{ cardMetadata }}</p>
      </div>
      <p v-else-if="teacher.introduction" class="teacher-introduction whitespace-pre-line text-[15px] leading-7 text-secondary-foreground">{{ teacher.introduction }}</p>
      <div class="flex flex-wrap gap-2">
        <span v-for="code in teacher.categoryCodes" :key="code" class="rounded-full bg-stone-100 px-2.5 py-1 text-xs text-secondary-foreground">{{ categoryNames[code] || code }}</span>
        <span v-for="code in teacher.areaCodes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-secondary-foreground"><MapPin class="size-3.5"/>{{ areaNames[code] || code }}街道</span>
        <span v-for="code in teacher.serviceModes" :key="code" class="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2.5 py-1 text-xs text-secondary-foreground"><Monitor class="size-3.5"/>{{ modeNames[code] || code }}</span>
      </div>
      <p class="flex items-start gap-2 text-sm text-secondary-foreground"><Clock3 class="mt-0.5 size-4 shrink-0 text-muted-foreground"/><span :class="compact ? 'line-clamp-2' : 'whitespace-pre-wrap'">{{ teacher.availabilityText || '时间可协商' }}</span></p>
      <Button v-if="compact" variant="default" :disabled="openDisabled" class="mt-auto h-11 w-full justify-between rounded-xl bg-primary px-4 text-white transition-colors group-hover:bg-primary group-hover:text-white hover:bg-primary focus-visible:bg-primary focus-visible:text-white motion-reduce:transition-none" @click="emit('open', $event)">查看老师<ArrowRight aria-hidden="true"/></Button>
    </template>
  </div>
</template>
