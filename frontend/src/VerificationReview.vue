<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { Button } from "@/components/ui/button";
import ConfirmDialog from "./ConfirmDialog.vue";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import { verificationError, verificationTypes, type Verification } from "./verification";

const emit = defineEmits<{ expired: [] }>();
const items = ref<Verification[]>([]), reasons = ref<Record<string,string>>({});
const status = ref<Verification["status"]>("pending");
const toDelete = ref<Verification>();
const preview = ref<{id: string; url: string; pdf: boolean}>();
let previewRead = 0;
function closePreview() {
  previewRead++;
  if (preview.value?.url) URL.revokeObjectURL(preview.value.url);
  preview.value = undefined;
}
onBeforeUnmount(closePreview);
async function view(item: Verification) {
  closePreview();
  const read = previewRead;
  await run(async () => {
    const response = await fetch("/api/admin/verifications/" + item.id + "/proof", { credentials: "same-origin", cache: "no-store" });
    if (!response.ok) {
      if (response.status===403) emit("expired");
      throw new Error("证件暂不可查看，请刷新队列。");
    }
    const blob = await response.blob();
    if (read !== previewRead) return;
    preview.value = {id: item.id, url: URL.createObjectURL(blob), pdf: blob.type === "application/pdf"};
  });
}
function remove() {
  const item = toDelete.value;
  if (!item) return;
  return run(async () => {
    await api.request("/admin/verifications/" + item.id + "/proof", "DELETE");
    item.proof_available = false;
    toDelete.value = undefined;
    notice.value = "原件已停止提供，清理请求已登记。认证徽章仍然有效。";
  });
}
const busy = ref(false), error = ref(""), notice = ref("");
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = notice.value = "";
  try { await action(); }
  catch (e) {
    error.value = verificationError(e);
    if (e instanceof ApiFailure && ["ADMIN_REQUIRED", "LOGIN_REQUIRED", "SESSION_CHANGED"].includes(e.code)) emit("expired");
  } finally { busy.value = false; }
}
function load(next = status.value) {
  return run(async () => { items.value = await api.request<Verification[]>("/admin/verifications?status=" + next); status.value = next; });
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
onMounted(() => load());
</script>
<template>
  <section aria-label="老师认证审核" class="my-6 space-y-4 rounded-2xl border border-border bg-card p-4 sm:p-6">
    <header class="flex flex-wrap items-center justify-between gap-2"><h2 class="text-xl font-bold">老师认证审核</h2>
      <Button variant="outline" class="min-h-11" :disabled="busy" @click="load()">{{ status === 'pending' ? '刷新待核验申请' : '刷新队列' }}</Button>
    </header>
    <p class="text-[15px] text-secondary-foreground">学籍材料须能确认申请人就读于试点高校。技能材料须能确认持证人和证书内容。</p>
    <div role="group" aria-label="认证状态" class="flex gap-1"><Button v-for="code in (['pending','approved','rejected'] as const)" :key="code" variant="ghost" class="aria-pressed:bg-blue-50 aria-pressed:text-primary" :aria-pressed="status===code" :disabled="busy" @click="load(code)">{{ {pending:'待核验',approved:'已核验',rejected:'已驳回'}[code] }}</Button></div>
    <Skeleton v-if="busy && !items.length" class="h-24 rounded-xl"/>
    <p v-else-if="!items.length && !error" class="rounded-xl bg-slate-50 p-4 text-sm">当前队列没有认证申请。</p>
    <div class="grid gap-4 md:grid-cols-2">
      <article v-for="item in items" :key="item.id" class="min-w-0 space-y-3 rounded-xl bg-slate-50 p-4">
        <h3 class="text-lg font-semibold">{{ verificationTypes.find(t => t.type===item.verify_type)?.label }}</h3>
        <p class="break-all text-sm text-secondary-foreground">申请人编号 {{ item.user_id }}</p>
        <p class="text-sm text-secondary-foreground">{{ new Date(item.created_at).toLocaleString('zh-CN') }}</p>
        <Button v-if="item.proof_available" variant="secondary" :disabled="busy" @click="view(item)">查看证件</Button>
        <p v-else class="text-sm">原件已停止提供</p>
        <Button v-if="status==='approved' && item.proof_available" variant="outline" :disabled="busy" @click="toDelete=item">删除已核验证件原图</Button>
        <p v-if="item.reject_reason" class="text-sm">{{ item.reject_reason }}</p>
        <label v-if="status==='pending'" class="block text-sm">驳回原因（驳回时必填）
          <textarea v-model="reasons[item.id]" maxlength="300" class="mt-1 block w-full rounded-lg border border-border bg-white p-2" placeholder="说明需要补充的材料，避免抄录证件号"></textarea>
        </label>
        <div v-if="status==='pending'" class="flex flex-wrap gap-2"><Button class="min-h-11" :disabled="busy" @click="review(item,'approved')">确认核验通过</Button>
          <Button variant="outline" class="min-h-11" :disabled="busy || !reasons[item.id]?.trim()" @click="review(item,'rejected')">驳回申请</Button></div>
      </article>
    </div>
    <Dialog :open="!!preview" @update:open="!$event && closePreview()">
      <DialogContent v-if="preview" class="max-h-[90dvh] overflow-auto sm:max-w-3xl">
        <DialogTitle>查看证件</DialogTitle><DialogDescription>仅供本次认证审核使用。</DialogDescription>
        <object v-if="preview.pdf" :data="preview.url" type="application/pdf" class="h-[58dvh] w-full"><p>当前浏览器无法内嵌预览 PDF。</p></object>
        <img v-else :src="preview.url" alt="申请人提交的证件原图" class="mx-auto max-h-[65dvh] max-w-full object-contain"/>
        <a v-if="preview.pdf" class="text-sm font-semibold text-primary" :href="'/api/admin/verifications/'+preview.id+'/proof'">下载 PDF 核对</a>
      </DialogContent>
    </Dialog>
    <ConfirmDialog :open="!!toDelete" title="删除已核验证件原图？" confirm-text="确认删除原图" :busy="busy" @cancel="toDelete=undefined" @confirm="remove">
      <p>删除后，老师和管理员均无法再读取原件。系统保留认证结果与操作记录。</p>
      <p v-if="error" role="alert" class="text-red-800">{{ error }}</p>
    </ConfirmDialog>
    <p v-if="error && !toDelete" role="alert" class="text-sm text-red-800">{{ error }}</p>
    <p v-if="notice" role="status" class="text-sm text-primary">{{ notice }}</p>
  </section>
</template>
