<script setup lang="ts">
import { ref } from "vue";
import { ClipboardCheck, BadgeCheck, Users } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import OperationsReview from "./OperationsReview.vue";
import VerificationReview from "./VerificationReview.vue";
import AdminUsers from "./AdminUsers.vue";

defineProps<{accountId: string}>();
const tabs = [
  { id: "demands", label: "家长需求", icon: ClipboardCheck },
  { id: "verifications", label: "老师认证审核", icon: BadgeCheck },
  { id: "users", label: "用户与风控", icon: Users },
] as const;
const active = ref<typeof tabs[number]["id"]>("demands");
const demands = ref<InstanceType<typeof OperationsReview>>();
const emit = defineEmits<{ expired: [] }>();
function requestLeave(proceed: () => void) {
  if (demands.value) demands.value.requestLeave(proceed);
  else proceed();
}
defineExpose({ requestLeave });
</script>
<template>
  <div class="space-y-6">
    <h1 class="text-3xl font-bold tracking-tight md:text-4xl">管理后台</h1>
    <div role="group" aria-label="管理任务" class="flex w-fit max-w-full flex-wrap gap-1 rounded-2xl border border-border bg-white p-1">
      <Button v-for="tab in tabs" :key="tab.id" variant="ghost" class="min-h-11 gap-1 px-2 aria-pressed:bg-blue-50 aria-pressed:text-primary sm:px-4"
        :aria-pressed="active===tab.id" @click="requestLeave(()=>active=tab.id)"><component :is="tab.icon"/>{{ tab.label }}</Button>
    </div>
    <OperationsReview v-if="active==='demands'" ref="demands" @expired="emit('expired')"/>
    <VerificationReview v-else-if="active==='verifications'" @expired="emit('expired')"/>
    <AdminUsers v-else :account-id="accountId" @expired="emit('expired')"/>
  </div>
</template>
