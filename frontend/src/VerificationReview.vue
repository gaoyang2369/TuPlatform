<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import { verificationError, verificationTypes, type Verification } from "./verification";

const emit = defineEmits<{ expired: [] }>();
const items = ref<Verification[]>([]), reasons = ref<Record<string,string>>({});
const busy = ref(false), error = ref(""), notice = ref("");
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = notice.value = "";
  try { await action(); }
  catch (e) {
    error.value = verificationError(e);
    if (e instanceof ApiFailure && ["LOGIN_REQUIRED", "SESSION_CHANGED"].includes(e.code)) emit("expired");
  } finally { busy.value = false; }
}
function load() {
  return run(async () => { items.value = await api.request<Verification[]>("/admin/verifications?status=pending"); });
}
function review(item: Verification, status: "approved" | "rejected") {
  return run(async () => {
    await api.request("/admin/verifications/" + item.id + "/review", "POST", {
      status, reject_reason: status === "rejected" ? reasons.value[item.id]?.trim() : null,
    });
    items.value = items.value.filter(row => row.id !== item.id);
    delete reasons.value[item.id];
    notice.value = status === "approved" ? "已通过核验。" : "已驳回，老师可查看原因并重新提交。";
  });
}
onMounted(load);
</script>
<template>
  <section aria-label="老师认证审核" class="my-6 space-y-4 rounded-2xl border border-border bg-card p-4 sm:p-6">
    <header class="flex flex-wrap items-center justify-between gap-2"><h2 class="text-xl font-bold">老师认证审核</h2>
      <Button variant="outline" class="min-h-11" :disabled="busy" @click="load">刷新待核验申请</Button>
    </header>
    <p class="text-[15px] text-secondary-foreground">学籍材料须能确认申请人就读于试点高校。技能材料须能确认持证人和证书内容。</p>
    <Skeleton v-if="busy && !items.length" class="h-24 rounded-xl"/>
    <p v-else-if="!items.length && !error" class="rounded-xl bg-slate-50 p-4 text-sm">当前没有待核验的证件。</p>
    <div class="grid gap-4 md:grid-cols-2">
      <article v-for="item in items" :key="item.id" class="min-w-0 space-y-3 rounded-xl bg-slate-50 p-4">
        <h3 class="text-lg font-semibold">{{ verificationTypes.find(t => t.type===item.verify_type)?.label }}</h3>
        <p class="break-all text-sm text-secondary-foreground">申请人编号 {{ item.user_id }}</p>
        <a class="inline-block py-2 text-sm font-semibold text-primary underline" :href="'/api/admin/verifications/'+item.id+'/proof'">下载证件核对</a>
        <label class="block text-sm">驳回原因（驳回时必填）
          <textarea v-model="reasons[item.id]" maxlength="300" class="mt-1 block w-full rounded-lg border border-border bg-white p-2" placeholder="说明需要补充的材料，避免抄录证件号"></textarea>
        </label>
        <div class="flex flex-wrap gap-2"><Button class="min-h-11" :disabled="busy" @click="review(item,'approved')">确认核验通过</Button>
          <Button variant="outline" class="min-h-11" :disabled="busy || !reasons[item.id]?.trim()" @click="review(item,'rejected')">驳回申请</Button></div>
      </article>
    </div>
    <p v-if="error" role="alert" class="text-sm text-red-800">{{ error }}</p>
    <p v-if="notice" role="status" class="text-sm text-primary">{{ notice }}</p>
  </section>
</template>
