<script setup lang="ts">
import { computed } from "vue";
import { Button } from "@/components/ui/button";
import { progressActions, type Match, type ProgressAction } from "./match-types";

const props = defineProps<{ match: Match; accountId: string; shared: boolean; disabled: boolean }>();
const emit = defineEmits<{ action: [action: ProgressAction, event: MouseEvent] }>();

type Panel = { title: string; message: string; actions: ProgressAction[] };
const panel = computed<Panel | null>(() => {
  const m = props.match;
  if (m.state === "MUTUAL" && props.shared) {
    const sender = m.cooperationRequestedBy;
    return {
      title: sender ? "等待确认合作" : "试课约好了吗？",
      message: sender === props.accountId ? "已发起合作确认，等待对方确认。3 天内无异议将自动进入合作中。" :
        sender ? "对方已标记开始合作。请核对实际安排，3 天内无异议将自动进入合作中。" : "约好试课或开始合作后，双方可以在这里记录进展。",
      actions: sender === props.accountId ? ["close-no-deal"] :
        [sender ? "confirm-cooperation" : "request-cooperation", "close-no-deal"],
    };
  }
  if (m.state === "in_cooperation") {
    const sender = m.completionRequestedBy;
    return {
      title: sender ? "等待确认完结" : "试课 / 合作中",
      message: sender === props.accountId ? "已发起完结确认，等待对方确认。3 天内无异议将自动完结。" :
        sender ? "对方已标记阶段完结。如果合作仍在进行，请在 3 天内选择「仍在合作」。" : "试课或本阶段合作结束后，任一方都可以发起完结确认。",
      actions: sender === props.accountId ? [] : sender ? ["confirm-completion", "continue-cooperation"] : ["request-completion"],
    };
  }
  if (m.state === "completed" && m.reviewDeadlineAt) {
    const deadline = new Date(m.reviewDeadlineAt);
    return { title: m.reviewEligible ? "互评资格已解锁" : "互评期限已结束",
      message: "本阶段已完结。互评截止时间为 " + deadline.toLocaleString("zh-CN", { hour12: false }) + "。", actions: [] };
  }
  if (m.state === "closed_no_deal") {
    return { title: "本次沟通已结束", message: "双方暂未开展合作，本轮不进入互评。", actions: [] };
  }
  return null;
});
</script>
<template>
  <section v-if="panel" class="space-y-3 rounded-xl bg-blue-50 p-4 sm:p-5">
    <div aria-live="polite">
      <h3 class="text-xl font-bold">{{ panel.title }}</h3>
      <p class="mt-1 text-[15px] leading-6 text-secondary-foreground">{{ panel.message }}</p>
    </div>
    <div v-if="panel.actions.length" class="flex flex-wrap gap-2">
      <Button v-for="action in panel.actions" :key="action" :disabled="disabled"
        :variant="action === 'close-no-deal' || action === 'continue-cooperation' ? 'outline' : 'default'"
        @click="emit('action', action, $event)">{{ progressActions[action].label }}</Button>
    </div>
  </section>
</template>
