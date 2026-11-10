<script setup lang="ts">
import type {PublicTeacher} from "./match-types.ts";
import {categoryNames,areaNames,modeNames} from "./request-display.ts";
defineProps<{teacher:PublicTeacher}>();
function labels(codes:string[],names:Record<string,string>){return codes.map(c=>names[c]||c).join('、');}
</script>
<template>
  <div class="teacher-summary"><h2>{{ teacher.displayName }}</h2><p class="privacy-note">资料为老师本人自述，平台未作资格认证。</p>
    <p>{{ labels(teacher.categoryCodes,categoryNames) }} · {{ labels(teacher.serviceModes,modeNames) }}</p>
    <p v-if="teacher.areaCodes.length">坪山 · {{ labels(teacher.areaCodes,areaNames) }}</p>
    <p class="prose">{{ teacher.introduction }}</p><p>报价：{{ teacher.priceMin&&teacher.priceMax?`${teacher.priceMin}—${teacher.priceMax} 元/小时`:'面议' }}</p>
    <p class="prose">{{ teacher.availabilityText||'时间可协商' }}</p>
  </div>
</template>
<style scoped>.teacher-summary{overflow-wrap:anywhere}.teacher-summary p{margin:14px 0;line-height:1.8}.prose{white-space:pre-wrap}</style>
