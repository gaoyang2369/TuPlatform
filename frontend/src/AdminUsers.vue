<script setup lang="ts">
import { onMounted, ref } from "vue";
import { Search, Users } from "@lucide/vue";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import { api, ApiFailure } from "./account-api";
import { verificationError } from "./verification";
import ConfirmDialog from "./ConfirmDialog.vue";
import UserAvatar from "./UserAvatar.vue";

interface User {
  id: string; account: string; email: string; display_name: string; avatar_url: string | null;
  operator_granted: boolean; business_identity: string | null; is_frozen: boolean; email_verified: boolean;
  school_verified: boolean; skill_verified: boolean; demand_count: number;
}
interface Review { id: string; content: string | null; overall_score: number; created_at: string; is_hidden: boolean; hidden_reason: string | null }
defineProps<{accountId: string}>();
const labels = { "reset-avatar": "重置头像", freeze: "冻结账号", unfreeze: "解冻账号", hide: "隐藏评价" };
type Action = keyof typeof labels;
const emit = defineEmits<{expired: []}>();
const q = ref(""), role = ref(""), users = ref<User[]>([]), offset = ref(0);
const applied = ref({ q: "", role: "" });
const selected = ref<User>(), reviews = ref<Review[]>([]), reviewOffset = ref(0);
const form = ref<{ action: Action; user: User; review?: Review }>();
const reason = ref(""), busy = ref(false), error = ref(""), notice = ref("");
const roles = [["", "全部角色"], ["REQUESTER", "需求方"], ["TUTOR", "老师"], ["ADMIN", "管理员"]];
const name = (u: User) => u.display_name || u.account;
async function run(work: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true;
  error.value = notice.value = "";
  try { await work(); }
  catch (e) {
    error.value = verificationError(e);
    if (e instanceof ApiFailure && ["ADMIN_REQUIRED", "LOGIN_REQUIRED", "SESSION_CHANGED"].includes(e.code)) emit("expired");
  } finally { busy.value = false; }
}
async function list(start = 0, filters = applied.value) {
  users.value = await api.request<User[]>("/admin/users?" + new URLSearchParams({...filters, offset: String(start)}));
  applied.value = filters;
  offset.value = start;
  selected.value = undefined;
}
async function showReviews(user: User, start = 0) {
  reviews.value = await api.request<Review[]>("/admin/users/" + user.id + "/reviews?offset=" + start);
  selected.value = user;
  reviewOffset.value = start;
}
function ask(action: Action, user: User, review?: Review) {
  reason.value = error.value = notice.value = "";
  form.value = {action, user, review};
}
async function confirm() {
  const target = form.value;
  if (!target) return;
  if (!reason.value.trim()) { error.value = "请填写操作理由。"; return; }
  await run(async () => {
    const endpoint = target.review ? "/admin/reviews/" + target.review.id + "/hide" : "/admin/users/" + target.user.id + "/" + target.action;
    await api.request(endpoint, "POST", { reason: reason.value.trim() });
    form.value = undefined;
    notice.value = "已" + labels[target.action] + "，操作理由已登记。";
    if (target.review) await showReviews(target.user, reviewOffset.value);
    else await list(offset.value);
  });
}
onMounted(() => run(() => list()));
</script>
<template>
  <section aria-label="用户与风控" class="space-y-5 [overflow-wrap:anywhere]">
    <form class="flex flex-wrap items-end gap-3 rounded-2xl border border-border bg-white p-4" @submit.prevent="run(()=>list(0,{q,role}))">
      <label class="min-w-48 flex-1 text-sm font-medium">姓名、账号或邮箱<input v-model="q" maxlength="254" type="search" class="field-control mt-1" placeholder="输入搜索内容"/></label>
      <label class="text-sm font-medium">角色<select v-model="role" aria-label="角色" class="field-control mt-1"><option v-for="[value,label] in roles" :key="value" :value="value">{{ label }}</option></select></label>
      <Button type="submit" class="min-h-11" :disabled="busy"><Search/>搜索用户</Button>
    </form>
    <p v-if="error && !form" role="alert" class="rounded-xl bg-red-50 p-3 text-red-800">{{ error }}</p>
    <p v-if="notice" role="status" class="rounded-xl bg-blue-50 p-3 text-primary">{{ notice }}</p>
    <Skeleton v-if="busy && !users.length" class="h-32 rounded-2xl"/>
    <p v-else-if="!users.length && !error" class="flex items-center gap-3 rounded-2xl bg-white p-6"><Users/>没有符合条件的用户。</p>
    <div class="grid gap-5 md:grid-cols-2 lg:grid-cols-3">
      <article v-for="user in users" :key="user.id" class="space-y-4 rounded-2xl border border-border bg-white p-5 shadow-sm">
        <header class="flex items-start gap-3"><UserAvatar :url="user.avatar_url" :name="name(user)" :color-key="user.id" class="size-12 shrink-0 rounded-xl"/>
          <div><h2 class="text-xl font-bold">{{ name(user) }}</h2><p class="text-sm text-secondary-foreground">{{ user.email }}</p></div></header>
        <p class="text-sm">{{ roles.find(([v])=>v===user.business_identity)?.[1] || '未选身份' }} {{ user.operator_granted ? '· 管理员' : '' }} · <strong>{{ user.is_frozen ? '已冻结' : '正常' }}</strong> · 已发布需求 {{ user.demand_count }} 条</p>
        <p class="text-sm text-secondary-foreground">{{ user.email_verified ? '邮箱已验证' : '邮箱待验证' }}<template v-if="user.business_identity==='TUTOR'"> · {{ user.school_verified ? '学籍已核验' : '学籍未核验' }} · {{ user.skill_verified ? '技能已核验' : '技能未核验' }}</template></p>
        <div class="flex flex-wrap gap-2">
          <Button variant="secondary" :disabled="busy" @click="run(()=>showReviews(user))">查看已发评价</Button>
          <Button variant="outline" :disabled="busy || !user.avatar_url" @click="ask('reset-avatar',user)">重置头像</Button>
          <Button v-if="user.id!==accountId" variant="outline" :disabled="busy" @click="ask(user.is_frozen?'unfreeze':'freeze',user)">{{ user.is_frozen?'解冻账号':'冻结账号' }}</Button>
        </div>
        <section v-if="selected?.id===user.id" class="space-y-3 rounded-xl bg-slate-50 p-3">
          <h3 class="font-bold">已发公开评价</h3>
          <p v-if="!reviews.length" class="text-sm">此页没有公开评价。</p>
          <div v-for="review in reviews" :key="review.id" class="space-y-2 rounded-xl bg-white p-3 text-sm">
            <strong>{{ review.overall_score }} 星 · {{ new Date(review.created_at).toLocaleDateString('zh-CN') }}</strong>
            <p>{{ review.content || '仅评分' }}</p>
            <p v-if="review.is_hidden">已隐藏 · {{ review.hidden_reason }}</p>
            <Button v-else variant="outline" :disabled="busy" @click="ask('hide',user,review)">隐藏评价</Button>
          </div>
          <Button v-if="reviewOffset" variant="ghost" :disabled="busy" @click="run(()=>showReviews(user, reviewOffset-20))">上一页评价</Button>
          <Button v-if="reviews.length===20" variant="ghost" :disabled="busy" @click="run(()=>showReviews(user, reviewOffset+20))">下一页评价</Button>
        </section>
      </article>
    </div>
    <div class="flex gap-2"><Button v-if="offset" variant="outline" :disabled="busy" @click="run(()=>list(offset-20))">上一页用户</Button>
      <Button v-if="users.length===20" variant="outline" :disabled="busy" @click="run(()=>list(offset+20))">下一页用户</Button></div>
    <ConfirmDialog :open="!!form" :title="form ? labels[form.action] : ''" :busy="busy" @cancel="form=undefined;error=''" @confirm="confirm">
      <p v-if="form">{{ name(form.user) }} · {{ form.user.email }}</p>
      <p v-if="form?.action==='freeze'">该账号会立即退出登录，其公开老师名片和需求将下架。</p>
      <p v-if="form?.action==='hide'">该评价将从前台移除，评分和标签统计将重新计算。</p>
      <label class="block">操作理由<textarea v-model="reason" class="field-control mt-1" maxlength="300" rows="3"/></label>
      <p v-if="error" role="alert" class="text-red-800">{{ error }}</p>
    </ConfirmDialog>
  </section>
</template>
