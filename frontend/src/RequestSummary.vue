<script setup lang="ts">
import {moneyText} from "./discovery-display.ts";
import { computed } from "vue";
import { categoryNames,areaNames,stageNames,modeNames,weekdayNames,minuteText } from "./request-display.ts";
const props=defineProps<{ fields:Record<string,unknown>; displayName?:string }>();
const frequency=computed(()=>props.fields.frequency as {mode?:string;count?:number}|undefined);
const slots=computed(()=> (props.fields.availableSlots??[]) as {weekday:number;startMinute:number;endMinute:number}[]);
const preference=computed(()=>props.fields.availabilityPreference as {mode:string;weekdays:number[];periods:string[]}|undefined);
const periodNames:Record<string,string>={MORNING:"上午",AFTERNOON:"下午",EVENING:"晚上"};
function hasText(value:unknown) { return typeof value === "string" && value.trim().length > 0; }
const preferenceText=computed(()=>{
  const value=preference.value;
  if(!value)return "";
  if(value.mode==='NEGOTIABLE')return "时间可协商";
  const days=[...value.weekdays].sort().join(',');
  const label=days==='6,7'?'周末':days==='1,2,3,4,5'?'工作日':days==='1,2,3,4,5,6,7'?'每天':value.weekdays.map(day=>weekdayNames[day-1]).join('、');
  return `${label||'日期待补充'} · ${value.periods.length?value.periods.map(code=>periodNames[code]).join('、'):'时段不限'}（具体时间可协商）`;
});
</script>
<template>
  <article class="request-summary">
    <h2>{{ fields.title||'未填写标题' }}</h2><p v-if="displayName" class="publisher">发布者：{{ displayName }}</p>
    <dl><dt>服务类别</dt><dd>{{ categoryNames[String(fields.categoryCode)]||'待补充' }}</dd>
      <template v-if="fields.categoryCode!=='EXTRACURRICULAR_STUDY'"><dt>服务对象</dt><dd>{{ stageNames[String(fields.audienceStageCode)]||'待补充' }}</dd></template>
      <template v-if="fields.categoryCode==='EXTRACURRICULAR_STUDY'"><dt>年级</dt><dd>{{ fields.grade||'待补充' }}</dd><dt>科目</dt><dd>{{ fields.subject||'待补充' }}</dd></template>
      <template v-if="fields.serviceMode!=='ONLINE'||hasText(fields.locationText)||areaNames[String(fields.areaCode)]"><dt>服务地点</dt><dd>{{ fields.locationText||(areaNames[String(fields.areaCode)] ? areaNames[String(fields.areaCode)]+'街道附近' : '待补充') }}</dd></template>
      <template v-if="modeNames[String(fields.serviceMode)]"><dt>服务方式</dt><dd>{{ modeNames[String(fields.serviceMode)] }}</dd></template>
      <dt>目标与现状</dt><dd class="pre-wrap">{{ fields.goal||'待补充' }}</dd>
      <dt>预算</dt><dd>{{ moneyText(fields.budgetMin)||'待补充' }}—{{ moneyText(fields.budgetMax)||'待补充' }} 元/小时</dd>
      <dt>单次时长</dt><dd>{{ fields.durationHours||'待补充' }} 小时</dd>
      <dt>频率</dt><dd>{{ frequency?.mode==='NEGOTIABLE'?'可协商':frequency?.mode==='PER_WEEK'?`每周${frequency.count??'待补充'}次`:'待补充' }}</dd>
      <dt>可约时间</dt><dd><span v-if="preference">{{ preferenceText }}</span><template v-else><p v-for="(slot,index) in slots" :key="index">{{ weekdayNames[slot.weekday-1] }} {{ minuteText(slot.startMinute) }}—{{ minuteText(slot.endMinute) }}</p><span v-if="!slots.length">待补充</span></template></dd>
      <template v-if="hasText(fields.availabilityNote)"><dt>时间备注</dt><dd class="pre-wrap">{{ fields.availabilityNote }}</dd></template>
      <template v-if="hasText(fields.expectedStartDate)"><dt>期望开始</dt><dd>{{ fields.expectedStartDate }}</dd></template>
      <template v-if="hasText(fields.tutorExpectations)"><dt>对老师的期待</dt><dd class="pre-wrap">{{ fields.tutorExpectations }}</dd></template>
    </dl>
  </article>
</template>
<style scoped>
.request-summary{overflow-wrap:anywhere}.request-summary h2{font-size:23px;line-height:1.5}.publisher{color:var(--muted);margin:12px 0}.request-summary dl{display:grid;grid-template-columns:110px minmax(0,1fr);gap:15px;margin:24px 0}.request-summary dt{color:var(--muted)}.request-summary dd{margin:0;line-height:1.7}.request-summary dd p{margin:0 0 6px}@media(max-width:520px){.request-summary dl{grid-template-columns:80px minmax(0,1fr);font-size:13px;gap:12px}.request-summary h2{font-size:21px}}
</style>
