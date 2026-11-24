<script setup lang="ts">
defineProps<{ count: number; average?: number | null }>();
</script>

<template>
  <span v-if="count >= 3 && average != null" class="inline-flex items-center gap-1 text-sm font-semibold text-slate-800" :aria-label="`综合评分 ${average.toFixed(1)} 分，${count} 条评价`">
    <span aria-hidden="true" class="text-amber-600">★</span>
    <strong class="tabular-nums">{{ average.toFixed(1) }}</strong>（{{ count }} 条评价）
  </span>
  <span v-else-if="count > 0" class="inline-flex w-fit rounded-full bg-slate-100 px-2.5 py-1 text-xs font-medium text-slate-600">已有 {{ count }} 条真实评价</span>
</template>
