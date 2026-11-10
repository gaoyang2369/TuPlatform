<script setup lang="ts">
import { computed } from "vue";
import { categoryNames,areaNames,stageNames,modeNames,weekdayNames,minuteText } from "./request-display.ts";
const props=defineProps<{ fields:Record<string,unknown>; displayName?:string }>();
const frequency=computed(()=>props.fields.frequency as {mode?:string;count?:number}|undefined);
const slots=computed(()=> (props.fields.availableSlots??[]) as {weekday:number;startMinute:number;endMinute:number}[]);
</script>
<template>
  <article class="request-summary">
    <h2>{{ fields.title||'未填写标题' }}</h2><p v-if="displayName" class="publisher">发布者：{{ displayName }}</p>
    <dl><dt>服务类别</dt><dd>{{ categoryNames[String(fields.categoryCode)]||'待补充' }}</dd>
      <dt>服务对象</dt><dd>{{ stageNames[String(fields.audienceStageCode)]||'待补充' }}</dd>
      <dt>街道</dt><dd>{{ areaNames[String(fields.areaCode)]||'待补充' }}</dd>
      <dt>服务方式</dt><dd>{{ modeNames[String(fields.serviceMode)]||'留待沟通' }}</dd>
      <dt>目标与现状</dt><dd class="pre-wrap">{{ fields.goal||'待补充' }}</dd>
      <dt>预算</dt><dd>{{ fields.budgetMin||'待补充' }}—{{ fields.budgetMax||'待补充' }} 元/小时</dd>
      <dt>单次时长</dt><dd>{{ fields.durationHours||'待补充' }} 小时</dd>
      <dt>频率</dt><dd>{{ frequency?.mode==='NEGOTIABLE'?'可协商':frequency?.mode==='PER_WEEK'?`每周${frequency.count??'待补充'}次`:'待补充' }}</dd>
      <dt>可约时段</dt><dd><p v-for="(slot,index) in slots" :key="index">{{ weekdayNames[slot.weekday-1] }} {{ minuteText(slot.startMinute) }}—{{ minuteText(slot.endMinute) }}</p><span v-if="!slots.length">待补充</span></dd>
      <dt>时间备注</dt><dd class="pre-wrap">{{ fields.availabilityNote||'未填写' }}</dd>
      <dt>期望开始</dt><dd>{{ fields.expectedStartDate||'时间可协商' }}</dd>
      <dt>对老师的期待</dt><dd class="pre-wrap">{{ fields.tutorExpectations||'未填写' }}</dd>
    </dl>
  </article>
</template>
<style scoped>
.request-summary{overflow-wrap:anywhere}.request-summary h2{font-size:23px;line-height:1.5}.publisher{color:var(--muted);margin:12px 0}.request-summary dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:15px;margin:24px 0}.request-summary dt{color:var(--muted)}.request-summary dd{margin:0;line-height:1.7}.request-summary dd p{margin:0 0 6px}@media(max-width:520px){.request-summary dl{grid-template-columns:80px minmax(0,1fr);font-size:13px;gap:12px}.request-summary h2{font-size:21px}}
</style>
