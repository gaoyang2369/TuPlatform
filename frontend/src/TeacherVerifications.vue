<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ShieldCheck, RefreshCw } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import { verificationTypes, statusLabels, verificationError, type Verification, type VerificationType } from "./verification";

const emit = defineEmits<{ expired: []; updated: [school: boolean, skill: boolean] }>();
const records = ref<Verification[]>([]);
const files = reactive<Partial<Record<VerificationType, File>>>({});
const busy = ref(false), loaded = ref(false), error = ref(""), notice = ref("");
const cards = computed(() => verificationTypes.map(item => ({ ...item, record: records.value.find(r => r.verify_type === item.type) })));
function report(e: unknown) {
  error.value = verificationError(e);
  if (e instanceof ApiFailure && ["LOGIN_REQUIRED", "SESSION_CHANGED"].includes(e.code)) emit("expired");
}
async function refresh() {
  busy.value = true;
  error.value = notice.value = "";
  try {
    records.value = await api.request<Verification[]>("/verifications");
    loaded.value = true;
    for (const record of records.value) if (record.status !== "rejected") delete files[record.verify_type];
    const approved = (type: VerificationType) => records.value.some(r => r.verify_type === type && r.status === "approved");
    emit("updated", approved("student_id"), approved("skill_cert"));
  } catch (e) { report(e); } finally { busy.value = false; }
}
function select(type: VerificationType, event: Event) {
  const input = event.target as HTMLInputElement;
  delete files[type];
  error.value = notice.value = "";
  const file = input.files?.[0];
  if (!file) return;
  if (file.size > 5 * 1024 * 1024 || !["image/jpeg","image/png","image/webp","application/pdf"].includes(file.type)) {
    input.value = "";
    error.value = "请选择不超过 5 MB 的 JPG、PNG、WebP 或 PDF 文件。";
  } else files[type] = file;
}
async function submit(type: VerificationType) {
  const file = files[type];
  if (!file || busy.value) return;
  busy.value = true;
  error.value = notice.value = "";
  const body = new FormData();
  body.append("verify_type", type);
  body.append("file", file);
  try {
    const record = await api.request<Verification>("/verifications", "POST", body);
    records.value = [...records.value.filter(r => r.verify_type !== type), record];
    delete files[type];
    notice.value = "认证材料已提交，可以在这里查看审核结果。";
  } catch (e) { report(e); } finally { busy.value = false; }
}
const heading = ref<HTMLElement>();
onMounted(async () => {
  await refresh();
  if (new URLSearchParams(location.search).get("section") === "verifications") {
    heading.value?.focus({preventScroll:true});
    heading.value?.scrollIntoView({block:"start"});
  }
});
</script>
<template>
  <section class="my-6 space-y-4 rounded-2xl border border-border bg-card p-4 shadow-sm sm:p-6" aria-label="本校学籍与资质认证">
    <header class="flex flex-wrap items-center justify-between gap-2">
      <h2 ref="heading" tabindex="-1" class="flex scroll-mt-32 items-center gap-2 text-xl font-bold"><ShieldCheck class="size-5 text-primary"/>本校学籍与资质认证</h2>
      <Button type="button" variant="ghost" class="min-h-11 text-primary" :disabled="busy" @click="refresh"><RefreshCw/>刷新认证状态</Button>
    </header>
    <p class="rounded-xl bg-blue-50 px-4 py-3 text-[15px] font-medium text-blue-900">证件仅用于平台人工核验本校在读身份，绝不对外展示或泄露</p>
    <p class="text-sm text-secondary-foreground">请遮盖身份证号、住址等无关信息，保留核验所需的姓名、院校及在读信息或证书内容。</p>
    <Skeleton v-if="busy && !loaded" class="h-32 w-full rounded-xl"/>
    <div v-if="loaded" class="grid gap-4 sm:grid-cols-2">
      <article v-for="card in cards" :key="card.type" class="min-w-0 space-y-3 rounded-xl bg-slate-50 p-4">
        <header class="flex items-center justify-between gap-2"><h3 class="text-base font-bold">{{ card.label }}</h3>
          <span class="rounded-full bg-white px-2.5 py-1 text-xs font-semibold" :class="card.record?.status==='approved' ? 'text-primary' : 'text-slate-600'">{{ card.record ? statusLabels[card.record.status] : '未提交' }}</span>
        </header>
        <p class="text-sm text-secondary-foreground">{{ card.hint }}</p>
        <p v-if="card.record?.status==='rejected'" class="break-words text-sm text-red-800">驳回原因：{{ card.record.reject_reason }}</p>
        <template v-if="!card.record || card.record.status==='rejected'">
          <label class="block text-sm font-medium">选择{{ card.label }}材料
            <input type="file" accept="image/jpeg,image/png,image/webp,application/pdf" :disabled="busy" class="mt-2 block w-full text-sm file:mr-2 file:rounded-lg file:border file:border-border file:bg-white file:px-3 file:py-2" @change="select(card.type, $event)">
          </label>
          <p class="text-xs text-muted-foreground">JPG、PNG、WebP 或 PDF，最大 5 MB</p>
          <Button type="button" class="min-h-11" :disabled="busy || !files[card.type]" @click="submit(card.type)">提交{{ card.label }}认证</Button>
        </template>
        <a v-if="card.record" class="block py-2 text-sm font-semibold text-primary underline" :href="'/api/admin/verifications/' + card.record.id + '/proof'">下载本人已提交材料</a>
      </article>
    </div>
    <p v-if="error" role="alert" class="text-sm text-red-800">{{ error }}</p>
    <p v-if="notice" role="status" class="text-sm text-primary">{{ notice }}</p>
  </section>
</template>
