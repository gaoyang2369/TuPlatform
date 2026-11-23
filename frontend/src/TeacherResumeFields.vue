<script setup lang="ts">
import { Button } from "@/components/ui/button";
import { Plus, Trash2 } from "@lucide/vue";
import { emptyCase, styles, type ResumeDraft } from "./teacher-resume";
const draft = defineModel<ResumeDraft>({ required: true });
</script>

<template>
  <section class="resume-section">
    <h2>院校与授课方向</h2>
    <div class="grid gap-4 sm:grid-cols-2">
      <label>所在院校<input id="field-schoolName" v-model="draft.schoolName" maxlength="100"></label>
      <label>学院与专业<input id="field-collegeMajor" v-model="draft.collegeMajor" placeholder="大数据与互联网学院 · 计算机" maxlength="100"></label>
      <label>在读年级<input id="field-gradeLevel" v-model="draft.gradeLevel" placeholder="例如 大三" maxlength="30"></label>
      <label>辅导经验<input id="field-yearsExp" v-model="draft.yearsExp" placeholder="例如 2 年家教经验" maxlength="80"></label>
      <label>可授年级（必填）<input id="field-teachGrades" v-model="draft.gradesText" required placeholder="例如 初一、初二"><small>多项用顿号分隔</small></label>
      <label>教授科目（必填）<input id="field-teachSubjects" v-model="draft.subjectsText" required placeholder="例如 数学、物理"><small>也可填写绘画、编程等教学方向</small></label>
    </div>
  </section>
  <section id="field-casesJson" class="resume-section">
    <h2>教学风格与辅导案例</h2>
    <fieldset id="field-teachingStyleTags"><legend>教学风格 · 最多三项</legend>
      <div class="flex flex-wrap gap-2"><label v-for="style in styles" :key="style" class="choice">
        <input v-model="draft.teachingStyleTags" type="checkbox" :value="style" :disabled="draft.teachingStyleTags.length >= 3 && !draft.teachingStyleTags.includes(style)">{{ style }}
      </label></div>
    </fieldset>
    <fieldset v-for="(item, index) in draft.casesJson" :key="index" class="grid gap-3 rounded-xl bg-slate-50 p-4">
      <div class="flex items-center justify-between"><h3 class="font-semibold">辅导案例 {{ index + 1 }}</h3>
        <Button v-if="draft.casesJson.length > 1" type="button" variant="ghost" :aria-label="'删除案例 ' + (index + 1)" @click="draft.casesJson.splice(index, 1)"><Trash2/>删除</Button></div>
      <label>学生年级<input v-model="item.student_grade" required maxlength="40" placeholder="例如 初二"></label>
      <label>辅导内容<input v-model="item.focus" required maxlength="300" placeholder="例如 用画图法理解一次函数"></label>
      <label>阶段变化<textarea v-model="item.outcome" required rows="2" maxlength="300" placeholder="描述学生现在能独立完成的任务，请隐去身份信息。"/></label>
    </fieldset>
    <Button v-if="draft.casesJson.length < 3" type="button" variant="outline" @click="draft.casesJson.push(emptyCase())"><Plus/>添加辅导案例</Button>
  </section>
</template>
