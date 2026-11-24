<script setup lang="ts">
import { BookOpen, ChevronDown } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";

defineProps<{ disabled?: boolean; teacherGrades?: boolean }>();
const grade = defineModel<string>("grade", { required: true });
const subject = defineModel<string>("subject", { required: true });
const grades = ["一年级","二年级","三年级","四年级","五年级","六年级","初一","初二","初三","高一","高二","高三","成人学习","其他阶段"];
const stages = ["小学", "初中", "高中", "成人"];
const subjects = ["语文","数学","英语","物理","化学","生物","历史","地理","道德与法治"];
</script>

<template>
  <Popover>
    <PopoverTrigger as-child>
      <Button variant="outline" aria-label="年级与科目" :disabled="disabled" class="h-10 gap-1 rounded-xl bg-white has-[>svg]:px-2 sm:has-[>svg]:px-3">
        <BookOpen :class="grade || subject ? 'text-primary' : 'text-muted-foreground'"/>年级科目
        <span v-if="grade || subject" class="text-xs text-primary">{{ Number(!!grade) + Number(!!subject) }}</span>
        <ChevronDown class="hidden size-3.5 sm:block"/>
      </Button>
    </PopoverTrigger>
    <PopoverContent align="end" class="w-64 space-y-3 rounded-2xl">
      <label class="block text-sm font-medium">年级<input v-model="grade" list="filter-grades" :maxlength="teacherGrades ? 40 : 20" placeholder="不限年级" class="mt-1 block w-full rounded-lg border border-border p-2"/></label>
      <datalist id="filter-grades"><option v-for="value in teacherGrades ? [...stages, ...grades] : grades" :key="value" :value="value"/></datalist>
      <label class="block text-sm font-medium">科目<input v-model="subject" list="filter-subjects" maxlength="40" placeholder="不限科目" class="mt-1 block w-full rounded-lg border border-border p-2"/></label>
      <datalist id="filter-subjects"><option v-for="value in subjects" :key="value" :value="value"/></datalist>
    </PopoverContent>
  </Popover>
</template>
