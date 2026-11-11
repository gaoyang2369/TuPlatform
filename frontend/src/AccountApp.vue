<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { api, ApiFailure } from "./account-api.ts";
import RequestDrafts from "./RequestDrafts.vue";
import PublicRequests from "./PublicRequests.vue";
import OperationsReview from "./OperationsReview.vue";
import TeacherDiscovery from "./TeacherDiscovery.vue";
import MatchProgress from "./MatchProgress.vue";
import PrivateContacts from "./PrivateContacts.vue";
import FeedbackToast from "./FeedbackToast.vue";
import type { Identity, Me, Profile, ProfileOptions } from "./account-api.ts";
import { codePointLength, moneyError, passwordError } from "./account-form.ts";
import { readScreen, routeTo, writeUrl, listenHistory, restoreScroll, type Screen } from "./navigation.ts";
import type {Match} from './match-types.ts';

const matchId=ref<string|undefined>(),matchRequestId=ref<string|undefined>();
const screen = ref<Screen>("home");
const routeRevision = ref(0);
const teacherId = ref<string>();
const returnTarget = ref(safeReturn(new URLSearchParams(location.search).get("returnTo")));
let stopHistory: (() => void) | undefined;
const pendingReplies=ref<number|null>(null);
let pendingRead=0,pendingTimer:ReturnType<typeof setInterval>|undefined;
const draftsRef = ref<InstanceType<typeof RequestDrafts>>();
const reviewRef = ref<InstanceType<typeof OperationsReview>>();
const contactsRef = ref<InstanceType<typeof PrivateContacts>>();
const publicRequestId=ref<string|undefined>(new URLSearchParams(location.search).get("request")??undefined);
const me = ref<Me | null>(null);
const profile = ref<Profile | null>(null);
const busy = ref(false);
const booting = ref(true);
const notice = ref("");
const error = ref("");
const messageSequence = ref(0);
const fieldErrors = ref<Record<string, string>>({});
const options = ref<ProfileOptions>({ categoryCodes: [], serviceModes: [], areaCodes: [] });
const credentials = reactive({ account: "", password: "", email: "", confirmation: "", adultConfirmed: false });
const identity = ref<Identity | "">("");
const identityConfirmed = ref(false);
const registrationFlowId = ref("");
const mailAccepted = ref(false);
const token = ref("");
const requiresRegistrationPassword = ref(true);
const validLink = ref(false);
const preview = ref(false);
const stopConfirm = ref(false);
const previewDialog = ref<HTMLDialogElement>();
const stopDialog = ref<HTMLDialogElement>();
const conflict = ref(false);
const latest = ref<Profile | null>(null);
const form = reactive({ displayName: "", categoryCodes: [] as string[], serviceModes: [] as string[], areaCodes: [] as string[], introduction: "", priceMin: "", priceMax: "", availabilityText: "" });
let baseline = "";
let pendingVisibility: { path: string; version: number; key: string } | null = null;
const flowStorageKey = "linke-registration-flow";
function safeReturn(value: string | null): string {
  if (!value || value.length > 12000) return "";
  try {
    const url = new URL(value, location.origin);
    if (url.origin !== location.origin || url.pathname !== location.pathname || !["public", "teachers", "matches", "requests", "profile", "contacts"].includes(readScreen(url.search))) return "";
    url.searchParams.delete("returnTo");
    return url.pathname + url.search;
  } catch { return ""; }
}
function defaultScreen(): Screen {
  if (!me.value) return "home";
  if (me.value.operatorGranted && !me.value.businessIdentity) return "review";
  if (!me.value.businessIdentity) return "identity";
  if (!profile.value?.displayName || (isTutor.value && !isPublic.value)) return "profile";
  return isTutor.value ? "public" : "teachers";
}
function applyLocation() {
  const query = new URLSearchParams(location.search);
  let target = readScreen();
  returnTarget.value = safeReturn(query.get("returnTo"));
  if (target === "home" && me.value) target = defaultScreen();
  if (needsIdentity.value) { returnTarget.value ||= safeReturn(location.pathname + location.search); target = "identity"; }
  if (["verify", "reset"].includes(target) && !token.value) { target = "login"; routeTo(target, {}, true); }
  const privatePages = ["profile", "contacts", "requests", "teachers", "matches", "review", "identity"];
  if (!me.value && privatePages.includes(target)) {
    returnTarget.value = safeReturn(location.pathname + location.search);
    target = "login";
    routeTo(target, { returnTo: returnTarget.value }, true);
  } else if (me.value && ((["requests", "teachers"].includes(target) && me.value.businessIdentity !== "REQUESTER") || (target === "review" && !me.value.operatorGranted) || (["matches", "contacts"].includes(target) && !me.value.businessIdentity))) {
    target = defaultScreen();
    notice.value = "当前身份无法使用这个入口，已为你打开可用页面。";
    routeTo(target, {}, true);
  } else if (target !== readScreen()) routeTo(target, { returnTo: returnTarget.value }, true);
  publicRequestId.value = target === "public" ? query.get("request") || undefined : undefined;
  teacherId.value = target === "teachers" ? query.get("teacher") || undefined : undefined;
  matchId.value = target === "matches" ? query.get("match") || undefined : undefined;
  matchRequestId.value = target === "matches" ? query.get("forRequest") || undefined : undefined;
  screen.value = target; routeRevision.value++;
  void nextTick(restoreScroll);
}
function resumeDestination() {
  if (needsIdentity.value) { routeTo("identity", { returnTo: returnTarget.value }, true); return; }
  const destination = returnTarget.value;
  if (destination) writeUrl(new URL(destination, location.origin).searchParams, true);
  else routeTo(defaultScreen(), {}, true);
  applyLocation();
}
function navigate(target: Screen, values: Record<string, string | undefined> = {}) {
  routeTo(target, values); applyLocation();
}
function continueBrowsing() { leaveCurrent(() => { resetMessages(); resumeDestination(); }); }
function rememberFlow(value: string) {
  registrationFlowId.value = value;
  // This opaque flow ID alone cannot resend or verify; the server also requires the HttpOnly context cookie.
  try { if (value) sessionStorage.setItem(flowStorageKey, value); else sessionStorage.removeItem(flowStorageKey); } catch { /* This tab still works without browser storage. */ }
}
watch([preview, stopConfirm], async () => {
  await nextTick();
  for (const [dialog, visible] of [[previewDialog.value, preview.value], [stopDialog.value, stopConfirm.value]] as const) {
    if (visible && dialog && !dialog.open) dialog.showModal();
    if (!visible && dialog?.open) dialog.close();
  }
});
const dirty = computed(() => profile.value !== null && JSON.stringify(form) !== baseline);
const needsIdentity = computed(() => !!me.value && !me.value.businessIdentity && !me.value.operatorGranted);
const isTutor = computed(() => me.value?.businessIdentity === "TUTOR");
const isPublic = computed(() => profile.value?.teacher?.public === true);
const accountName = computed(() => profile.value?.displayName || me.value?.account || "");
const categoryNames: Record<string, string> = { DRAWING: "绘画", DANCE: "舞蹈", OTHER_SKILL: "其他课外技能", EXTRACURRICULAR_STUDY: "课外学习" };
const modeNames: Record<string, string> = { ONLINE: "线上", OFFLINE: "线下" };
const areaNames: Record<string, string> = { PS_PINGSHAN: "坪山", PS_KENGZI: "坑梓", PS_LONGTIAN: "龙田", PS_SHIJING: "石井", PS_MALUAN: "马峦", PS_BILING: "碧岭" };

function resetMessages() { messageSequence.value++; error.value = ""; notice.value = ""; fieldErrors.value = {}; }
function clearPasswords() { credentials.password = ""; credentials.confirmation = ""; }
function clearPrivate() {
  pendingReplies.value=null;pendingRead++;
  matchId.value=undefined;matchRequestId.value=undefined;
  me.value = null; profile.value = null; latest.value = null; identity.value = ""; identityConfirmed.value = false;
  Object.assign(form, { displayName: "", categoryCodes: [], serviceModes: [], areaCodes: [], introduction: "", priceMin: "", priceMax: "", availabilityText: "" });
  baseline = ""; pendingVisibility = null; preview.value = false; stopConfirm.value = false; conflict.value = false;
  credentials.account = ""; credentials.email = ""; credentials.adultConfirmed = false; clearPasswords();
}
async function run(action: () => Promise<void>) {
  if (busy.value) return;
  busy.value = true; resetMessages();
  try { await action(); }
  catch (e) {
    error.value = e instanceof Error ? e.message : "操作未完成，请重试";
    if (e instanceof ApiFailure) {
      fieldErrors.value = e.fieldErrors;
      if (e.status === 401 && e.code === "LOGIN_REQUIRED") {
        // Expired sessions clear all private state before another account can sign in.
        draftSessionExpired();
      }
      if (e.code === "SESSION_CHANGED") { clearPrivate(); returnTarget.value="";navigate("login"); }
      if (e.code === "VERSION_CONFLICT" || e.code === "IDENTITY_ALREADY_SELECTED") conflict.value = true;
    }
  } finally { busy.value = false; }
}
function go(target: Screen) { if (busy.value) return; resetMessages(); clearPasswords(); credentials.adultConfirmed = false; navigate(target, { returnTo: returnTarget.value }); }
function loadForm(p: Profile) {
  profile.value = p;
  Object.assign(form, { displayName: p.displayName, categoryCodes: [...(p.teacher?.categoryCodes ?? [])], serviceModes: [...(p.teacher?.serviceModes ?? [])], areaCodes: [...(p.teacher?.areaCodes ?? [])], introduction: p.teacher?.introduction ?? "", priceMin: p.teacher?.priceMin ?? "", priceMax: p.teacher?.priceMax ?? "", availabilityText: p.teacher?.availabilityText ?? "" });
  baseline = JSON.stringify(form); conflict.value = false; latest.value = null; preview.value = false; pendingVisibility = null;
}
async function loadAccount() {
  const current = await api.request<Me>("/me");
  // A session replaced in a different tab must not inherit the old user's unsaved values.
  if (me.value && me.value.id !== current.id) clearPrivate();
  me.value = current;
  if (current.operatorGranted && !current.businessIdentity) { screen.value = "review"; return; }
  if (!current.businessIdentity) { screen.value = "identity"; return; }
  screen.value = "profile";
  const [p, o] = await Promise.all([api.request<Profile>("/me/profile"), api.request<ProfileOptions>("/profile-options")]);
  options.value = o; loadForm(p);
  void refreshPendingReplies();
}
async function refreshPendingReplies() {
  const account=me.value?.id;
  if(!account||!me.value?.businessIdentity||document.hidden)return;
  const sequence=++pendingRead;let cursor:string|null=null,count=0;
  try {
    const seen=new Set<string>();
    for(let page=0;page<20;page++) {
      const result:{items:Match[];nextCursor:string|null}=await api.request('/me/matches?state=WAITING'+(cursor?'&cursor='+encodeURIComponent(cursor):''));
      if(sequence!==pendingRead||me.value?.id!==account)return;
      for(const item of result.items)if(item.waitingForMe&&!seen.has(item.id)){seen.add(item.id);count++;}
      cursor=result.nextCursor;
      if(!cursor){pendingReplies.value=count;return;}
    }
    pendingReplies.value=null; // Never present a partial scan as the total.
  } catch { if(sequence===pendingRead)pendingReplies.value=null; }
}
function checkPassword() {
  const message = passwordError(credentials.password);
  if (message) throw new Error(message);
  if (credentials.password !== credentials.confirmation) throw new Error("两次密码不一致，请重新核对。");
}
async function register() {
  await run(async () => {
    checkPassword();
    if (!credentials.adultConfirmed) throw new Error("请主动确认本人已年满18岁。");
    const result = await api.request<{ registrationFlowId: string; mailAccepted: boolean }>("/auth/register", "POST", { account: credentials.account, password: credentials.password, email: credentials.email, adultConfirmed: true });
    rememberFlow(result.registrationFlowId); mailAccepted.value = result.mailAccepted; clearPasswords(); screen.value = "registered";
    notice.value = result.mailAccepted ? "注册已受理，请查看邮箱并完成验证，再回来登录。发信受理不代表已送达。" : "注册已保存，但邮件未能受理发送。请稍后在此页面重新发送，验证后才能登录。";
  });
}
async function resend() { await run(async () => {
  const result = await api.request<{ accepted: boolean }>("/auth/email-verification/resend", "POST", { registrationFlowId: registrationFlowId.value });
  mailAccepted.value = result.accepted; notice.value = result.accepted ? "重新发信已受理，不代表已送达，请查看邮箱。" : "邮件未能受理发送，请稍后重试。";
}); }
async function login() { await run(async () => {
  await api.request<Me>("/auth/login", "POST", { account: credentials.account, password: credentials.password });
  clearPrivate(); await loadAccount(); resumeDestination();
}); }
async function forgot() { await run(async () => {
  await api.request("/auth/forgot-password", "POST", { email: credentials.email });
  notice.value = "请求已受理。如果该邮箱对应已验证账号，系统将尝试发送重置邮件；受理不代表已送达。";
}); }
async function inspectLink() {
  const result = await api.request<{ valid: boolean; requiresRegistrationPassword: boolean }>("/auth/email-verification/inspect", "POST", { token: token.value });
  validLink.value = result.valid; requiresRegistrationPassword.value = result.requiresRegistrationPassword;
  if (!result.valid) error.value = "链接已过期或已使用，请回到原注册页面重发，或重新注册。";
}
async function verify() { await run(async () => {
  await api.request("/auth/email-verification/confirm", "POST", { token: token.value, ...(requiresRegistrationPassword.value ? { registrationPassword: credentials.password } : {}) });
  token.value = ""; rememberFlow(""); clearPasswords(); screen.value = "login"; notice.value = "邮箱验证成功，请使用账号和密码登录。";
}); }
async function reset() { await run(async () => {
  checkPassword(); await api.request("/auth/reset-password", "POST", { token: token.value, newPassword: credentials.password });
  token.value = ""; clearPrivate(); screen.value = "login"; notice.value = "密码已重置，全部旧会话已退出，请重新登录。";
}); }
async function chooseIdentity() { await run(async () => {
  if (!identity.value || !identityConfirmed.value) throw new Error("请选择一种业务身份，并确认已理解上述职责。");
  me.value = await api.request<Me>("/me/business-identity", "PUT", { identity: identity.value });
  await loadAccount(); routeTo("profile", { returnTo: returnTarget.value }, true); notice.value = "业务身份已确认，请完善本人资料。";
}); }
async function save() { await run(async () => {
  if (!profile.value) throw new Error("请先读取本人资料。");
  if (conflict.value) throw new Error("请先读取并核对最新资料，避免覆盖其他页面的修改。");
  const rangeError = moneyError(form.priceMin, form.priceMax);
  if (isTutor.value && rangeError) throw new Error(rangeError);
  const body = isTutor.value ? { expectedVersion: profile.value.version, ...form } : { expectedVersion: profile.value.version, displayName: form.displayName };
  const result = await api.request<Profile>(isTutor.value ? "/me/teacher-profile" : "/me/profile", isTutor.value ? "PUT" : "PATCH", body);
  loadForm(result); notice.value = result.teacher?.public ? "资料已保存并更新公开展示。" : "资料已保存。";
  if (isTutor.value && !result.teacher?.public) {
    notice.value = "资料已保存。核对后点击“预览并公开”，公开后才能向需求方表达意向。";
  } else resumeDestination();
  await nextTick();
  window.scrollTo({ top: 0, behavior: "instant" });
}); }
async function readLatest() { await run(async () => {
  const current = await api.request<Me>("/me");
  if (me.value?.id !== current.id || current.businessIdentity !== me.value?.businessIdentity) { clearPrivate(); await loadAccount(); return; }
  latest.value = await api.request<Profile>("/me/profile"); notice.value = "已读取最新保存内容，当前输入未被覆盖。请核对后选择如何继续。";
}); }
function useLatest() { if (!busy.value && latest.value) { loadForm(latest.value); notice.value = "已载入最新资料。"; } }
function retainInput() {
  if (busy.value || !latest.value || !profile.value) return;
  const currentInput = JSON.stringify(form); const p = latest.value;
  loadForm(p); Object.assign(form, JSON.parse(currentInput)); notice.value = "已保留当前输入并采用最新版本；再次保存将用这些内容更新资料。";
}
async function visibility(publish: boolean) { await run(async () => {
  if (!profile.value) throw new Error("请先读取本人资料。");
  if (publish && dirty.value) throw new Error("请先保存当前修改，再预览并公开。");
  if (conflict.value) throw new Error("请先核对最新资料。");
  const path = "/me/teacher-profile/" + (publish ? "publish" : "unpublish");
  // Reuse the same key and body on an uncertain network result; never blindly create a new operation.
  if (!pendingVisibility || pendingVisibility.path !== path || pendingVisibility.version !== profile.value.version)
    pendingVisibility = { path, version: profile.value.version, key: crypto.randomUUID() };
  const result = await api.request<Profile>(path, "POST", { expectedVersion: pendingVisibility.version }, pendingVisibility.key);
  if (publish || !dirty.value) loadForm(result);
  else { profile.value = result; pendingVisibility = null; } // Preserve unsaved edits and the saved baseline when stopping public access.
  preview.value = false; stopConfirm.value = false; notice.value = publish ? "资料已主动公开。展示内容为本人自述，手机号、微信和邮箱不会随资料公开。" : "已停止公开。已有撮合关系不会因此结束。";
}); }
async function performLogout() { await run(async () => { await api.request("/auth/logout", "POST"); clearPrivate(); rememberFlow(""); returnTarget.value=""; navigate("home"); notice.value = "已退出登录。"; }); }
async function logout() {
  if (busy.value || booting.value) return;
  if (screen.value === "requests") { draftsRef.value?.requestLeave(performLogout); return; }
  if (screen.value === "review") { reviewRef.value?.requestLeave(performLogout); return; }
  if (screen.value === "contacts") { contactsRef.value?.requestLeave(performLogout); return; }
  if (dirty.value && !window.confirm("当前修改尚未保存，退出会放弃这些输入。确定退出？")) return;
  await performLogout();
}
function showRequests() {
  if(screen.value!=="requests")leaveCurrent(()=>{resetMessages();navigate("requests");});
}
function showProfile() {
  leaveCurrent(()=>run(async()=>{await loadAccount();navigate("profile");}));
}
function prepareProfile() {
  const destination = safeReturn(location.pathname + location.search);
  leaveCurrent(()=>run(async()=>{await loadAccount();navigate("profile", {returnTo:destination});}));
}
function prepareRequests() {
  const destination = safeReturn(location.pathname + location.search);
  leaveCurrent(()=>{resetMessages();navigate("requests", {returnTo:destination});});
}
function leaveCurrent(action:()=>void|Promise<void>){
  if(busy.value||booting.value)return;
  if(screen.value==="requests"){draftsRef.value?.requestLeave(action);return;}
  if(screen.value==="review"){reviewRef.value?.requestLeave(action);return;}
  if(screen.value==="contacts"){contactsRef.value?.requestLeave(action);return;}
  if(dirty.value&&!window.confirm("本人资料的修改尚未保存，是否放弃这些输入并离开？"))return;
  if(profile.value)loadForm(profile.value);
  void action();
}
function showPublic(){if(!needsIdentity.value && screen.value!=="public")leaveCurrent(()=>{resetMessages();navigate("public");});}
function showContacts(){if(screen.value!=="contacts")leaveCurrent(()=>{resetMessages();navigate("contacts", {returnTo:safeReturn(location.pathname+location.search)});});}
function showReview(){if(screen.value!=="review")leaveCurrent(()=>{resetMessages();navigate("review");});}
function showTeachers(){if(screen.value!=="teachers")leaveCurrent(()=>{resetMessages();navigate("teachers");});}
function showMatches(id?:string,requestId?:string){leaveCurrent(()=>{resetMessages();navigate("matches", {match:id,forRequest:requestId});});}
function showLogin(){leaveCurrent(()=>{resetMessages();navigate("login", {returnTo:safeReturn(location.pathname+location.search)||returnTarget.value});});}
function draftSessionExpired() { const destination=safeReturn(location.pathname+location.search);clearPrivate();navigate("login",{returnTo:destination}); error.value = "账号或登录状态已变化，请重新登录。"; }

function beforeUnload(event: BeforeUnloadEvent) { if (dirty.value) { event.preventDefault(); event.returnValue = ""; } }
async function initialize() {
  booting.value = true;
  try { const flow = sessionStorage.getItem(flowStorageKey); if (flow && /^[0-9a-f-]{36}$/i.test(flow)) registrationFlowId.value = flow; } catch { /* Storage is optional. */ }
  const hash = new URLSearchParams(location.hash.slice(1));
  const verifyToken = hash.get("verify"), resetToken = hash.get("reset");
  // Remove link secrets from browser history immediately; no GET consumes them.
  if (verifyToken || resetToken) {
    token.value = verifyToken ?? resetToken ?? ""; history.replaceState(null, "", location.pathname + location.search);
    clearPrivate(); screen.value = verifyToken ? "verify" : "reset";
    if (verifyToken) await run(inspectLink);
    booting.value = false; return;
  }
  try { await loadAccount(); }
  catch (e) {
    if (!(e instanceof ApiFailure && e.status === 401)) error.value = e instanceof Error ? e.message : "服务暂不可用";
    if (!me.value) screen.value = "login";
  } finally { applyLocation();booting.value = false; }
}
// Reconcile sessions after tab switches. Saving never trusts a cached identity.
async function reconcileSession() {
  if (!me.value || busy.value || document.visibilityState !== "visible") return;
  try {
    const current = await api.request<Me>("/me");
    if (current.id !== me.value.id || current.businessIdentity !== me.value.businessIdentity) {
      clearPrivate(); returnTarget.value=""; await run(async()=>{await loadAccount();routeTo(defaultScreen(),{},true);applyLocation();});
    } else void refreshPendingReplies();
  } catch (e) {
    if (e instanceof ApiFailure && e.status === 401) { draftSessionExpired(); error.value = "登录已失效，请重新登录。"; }
  }
}
onMounted(() => { pendingTimer=setInterval(()=>void refreshPendingReplies(),60000);stopHistory=listenHistory(leaveCurrent,()=>{resetMessages();applyLocation();});window.addEventListener("beforeunload", beforeUnload); document.addEventListener("visibilitychange", reconcileSession); void initialize(); });
onBeforeUnmount(() => { pendingRead++;clearInterval(pendingTimer);stopHistory?.();window.removeEventListener("beforeunload", beforeUnload); document.removeEventListener("visibilitychange", reconcileSession); });
</script>

<template>
  <div class="account-shell">
    <header class="site-header">
      <a class="brand" href="/" aria-label="邻课首页" @click.prevent="leaveCurrent(()=>{resetMessages();navigate('home');})"><span class="brand-mark">邻</span><span>邻课<small>坪山 · 认真相遇</small></span></a>
      <div v-if="me" class="account-summary"><button class="account-profile-button" aria-label="本人资料" title="查看本人资料" :aria-current="['profile','contacts'].includes(screen)?'page':undefined" :disabled="busy||booting||!me.businessIdentity" @click="showProfile"><span class="account-avatar" aria-hidden="true">{{ [...accountName][0] || '我' }}</span><span class="account-name">{{ accountName }}<small>{{ me.businessIdentity === 'TUTOR' ? '老师' : me.businessIdentity === 'REQUESTER' ? '需求方' : '未选身份' }}</small></span></button><button class="text-button" :disabled="busy||booting" @click="logout">退出登录</button></div>
      <button v-else class="text-button" :disabled="busy||booting" @click="showLogin">登录 / 注册</button>
    </header>
    <main class="account-main" :aria-busy="busy || booting">
      <nav v-if="!booting && !needsIdentity" class="account-nav" aria-label="主要任务">
        <button v-if="!me" class="nav-item" :disabled="busy" @click="showTeachers">找老师</button>
        <button v-if="!me || me.businessIdentity==='TUTOR'" class="nav-item" :aria-current="screen === 'public' ? 'page' : undefined" :disabled="busy" @click="showPublic">浏览需求</button>
        <button v-if="me?.businessIdentity==='REQUESTER'" class="nav-item" :aria-current="screen === 'teachers' ? 'page' : undefined" :disabled="busy" @click="showTeachers">发现老师</button>
        <button v-if="me?.businessIdentity==='REQUESTER'" class="nav-item" :aria-current="screen === 'requests' ? 'page' : undefined" :disabled="busy" @click="showRequests">我的需求</button>
        <button v-if="me?.businessIdentity" class="nav-item" aria-label="撮合进度" :aria-description="pendingReplies?`有${pendingReplies}条意向等待你回应`:undefined" :aria-current="screen === 'matches' ? 'page' : undefined" :disabled="busy" @click="showMatches()">撮合进度 <small v-if="pendingReplies" class="pending-count" aria-hidden="true">待回应 {{pendingReplies}}</small></button>
        <button v-if="me?.operatorGranted" class="nav-item" :aria-current="screen === 'review' ? 'page' : undefined" :disabled="busy" @click="showReview">需求审核</button>
      </nav>
      <div v-if="booting" class="loading" role="status">正在读取账号状态…</div>
      <template v-else>
        <FeedbackToast v-if="error" :key="'error-' + messageSequence" :message="error" kind="error" :field-errors="fieldErrors" />
        <FeedbackToast v-else-if="notice" :key="'notice-' + messageSequence" :message="notice" kind="success" />
        <p v-if="me?.operatorGranted && !me.email" class="privacy-note">运营账号未绑定邮箱，忘记密码请通过维护操作重置。</p>
        <div v-if="returnTarget && ['profile','contacts','requests'].includes(screen)" class="continuation-banner"><span>处理完成后，可以继续查看刚才的对象。</span><button class="secondary" :disabled="busy" @click="continueBrowsing">返回刚才的页面</button></div>

        <section v-if="screen==='home'" class="home-page">
          <div class="home-intro"><p class="eyebrow">邻课 · 深圳坪山</p><h1>找合适的老师，<br>遇见合适的教学需求。</h1><p>从方向、时间和预算开始了解彼此。先表达意向，双方同意后交换联系方式，再沟通具体安排。</p><div class="home-actions"><button class="primary" @click="showTeachers">我想找老师</button><button class="secondary" @click="showPublic">我是老师，找需求</button></div><small>老师资料需以需求方身份登录查看；公开需求可以先浏览。</small></div>
          <div class="home-paths"><article><span class="home-number">01</span><h2>找到合适的对象</h2><p>需求方查看老师的教学方向与报价，老师寻找时间、地点和目标合适的需求。</p></article><article><span class="home-number">02</span><h2>表达意向，等待回应</h2><p>需求方可直接邀请心仪的老师，也可发布需求等待投递。对方接受后达成互选。</p></article><article><span class="home-number">03</span><h2>双方同意后联系</h2><p>各自选择要分享的手机号或微信，双方同意后查看，再到站外商量后续安排。</p></article></div>
        </section>
        <section v-else-if="['login', 'register', 'forgot', 'registered', 'verify', 'reset'].includes(screen)" class="auth-layout">
          <div class="intro"><p class="eyebrow">LINK WITH YOUR NEIGHBORHOOD</p><h1>让每一份认真，<br>遇见合适的需要。</h1><p>在坪山，发现身边的老师与学习机会。先了解，再选择。</p><div class="intro-art" aria-hidden="true"><span class="art-circle"></span><span class="art-arch"></span><span class="art-dot"></span><span class="art-line"></span></div></div>
          <div class="form-card auth-card">
            <form v-if="screen === 'login'" @submit.prevent="login">
              <p class="eyebrow">WELCOME BACK</p><h2>登录邻课</h2><p class="form-caption">输入账号和密码继续。</p>
              <label for="field-account">账号<input id="field-account" v-model="credentials.account" name="username" autocomplete="username" required :disabled="busy"></label>
              <label for="field-password">密码<input id="field-password" v-model="credentials.password" name="password" type="password" autocomplete="current-password" required :disabled="busy"></label>
              <button class="primary full" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</button>
              <div class="form-links"><button type="button" class="text-button" @click="go('forgot')">忘记密码</button><button type="button" class="text-button" @click="go('register')">注册新账号 →</button></div>
              <button v-if="registrationFlowId" type="button" class="text-button" @click="go('registered')">回到注册进度 / 重新发送验证邮件</button>
              <button v-if="!me && error" type="button" class="secondary full" :disabled="busy" @click="run(loadAccount)">重新检查账号状态</button>
            </form>
            <form v-else-if="screen === 'register'" @submit.prevent="register">
              <h2>创建账号</h2><p class="form-caption">注册后验证邮箱，再确认一种业务身份。</p>
              <label for="field-account">账号 <small>4—16位，字母开头</small><input id="field-account" v-model="credentials.account" autocomplete="username" pattern="[A-Za-z][A-Za-z0-9_]{3,15}" minlength="4" maxlength="16" required :disabled="busy"></label>
              <label for="field-email">验证邮箱<input id="field-email" v-model="credentials.email" type="email" autocomplete="email" required :disabled="busy"></label>
              <label for="field-password">密码 <small>8—64个字符，可包含空格</small><input id="field-password" v-model="credentials.password" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <label>确认密码<input v-model="credentials.confirmation" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <label id="field-adultConfirmed" class="check-line"><input v-model="credentials.adultConfirmed" type="checkbox" required :disabled="busy"><span>我确认本人已年满18岁。</span></label>
              <button class="primary full" :disabled="busy || !credentials.adultConfirmed">{{ busy ? '正在注册…' : '注册并验证邮箱' }}</button><button type="button" class="text-button below" @click="go('login')">已有账号，返回登录</button>
            </form>
            <div v-else-if="screen === 'registered'">
              <div class="status-icon">✉</div><h2>请验证注册邮箱</h2><p class="form-caption">{{ mailAccepted ? '发信请求已受理，请检查收件箱和垃圾邮件。' : '注册已保存，邮件尚未受理发送，请稍后重试。' }}</p><p>打开邮件链接后，需要在页面上明确确认。验证不会自动登录。未验证注册自首次登记起保留7天，重发不会延长。</p>
              <button class="primary full" :disabled="busy || !registrationFlowId" @click="resend">重新发送验证邮件</button><button class="text-button below" @click="go('login')">返回登录</button>
            </div>
            <form v-else-if="screen === 'forgot'" @submit.prevent="forgot">
              <h2>找回密码</h2><p class="form-caption">输入注册时绑定的验证邮箱。</p><label for="field-email">邮箱<input id="field-email" v-model="credentials.email" type="email" autocomplete="email" required :disabled="busy"></label>
              <button class="primary full" :disabled="busy">{{ busy ? '正在受理…' : '申请重置邮件' }}</button><button type="button" class="text-button below" @click="go('login')">返回登录</button>
            </form>
            <form v-else-if="screen === 'verify'" @submit.prevent="verify">
              <h2>确认验证邮箱</h2><p class="form-caption">仅打开此页面不会完成验证。</p>
              <template v-if="requiresRegistrationPassword && validLink"><p>当前浏览器没有原注册上下文，请核对注册时设置的密码。</p><label for="field-registrationPassword">注册密码<input id="field-registrationPassword" v-model="credentials.password" type="password" autocomplete="current-password" required :disabled="busy"></label></template>
              <button class="primary full" :disabled="busy || !validLink">确认验证</button><button v-if="!validLink" type="button" class="secondary full" :disabled="busy" @click="run(inspectLink)">重新检查链接</button><button type="button" class="text-button below" @click="go('login')">返回登录</button>
            </form>
            <form v-else-if="screen === 'reset'" @submit.prevent="reset">
              <h2>设置新密码</h2><p class="form-caption">确认后将退出全部旧会话，需要重新登录。</p><label for="field-password">新密码 <small>8—64个字符</small><input id="field-password" v-model="credentials.password" type="password" autocomplete="new-password" required :disabled="busy"></label><label>确认新密码<input v-model="credentials.confirmation" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <button class="primary full" :disabled="busy">确认重置密码</button><button type="button" class="text-button below" @click="go('login')">返回登录</button>
            </form>
          </div>
        </section>

        <section v-else-if="screen === 'identity'" class="identity-page">
          <p class="eyebrow">YOUR FIRST STEP</p><h1>你希望如何参与？</h1><p>一个账号选择一种业务身份。请按本人实际用途确认。</p>
          <form @submit.prevent="chooseIdentity"><fieldset :disabled="busy"><legend class="sr-only">选择业务身份</legend><div class="identity-grid"><label :class="['identity-card', { selected: identity === 'REQUESTER' }]"><input v-model="identity" type="radio" value="REQUESTER"><strong>我是需求方</strong><span>为成年本人，或作为监护人为未成年人发布需求，寻找合适的老师。</span></label><label :class="['identity-card', { selected: identity === 'TUTOR' }]"><input v-model="identity" type="radio" value="TUTOR"><strong>我是老师</strong><span>介绍自己的服务，主动公开资料，向合适的需求表达意向。</span></label></div><label class="check-line"><input v-model="identityConfirmed" type="checkbox" required><span>我已理解上述职责。</span></label><button class="primary" :disabled="!identity || !identityConfirmed">确认身份并继续</button></fieldset></form>
          <button v-if="conflict" class="secondary" :disabled="busy" @click="run(loadAccount)">读取已确认的账号身份</button>
        </section>

        <RequestDrafts v-else-if="screen === 'requests'" ref="draftsRef" :key="routeRevision" @expired="draftSessionExpired" @profile="prepareProfile" @matches="showMatches(undefined,$event)" />
        <PublicRequests v-else-if="screen==='public'" :key="routeRevision" :initial-id="publicRequestId" :me="me" @matched="showMatches($event)" @expired="draftSessionExpired" @profile="prepareProfile" @login="showLogin" />
        <TeacherDiscovery v-else-if="screen==='teachers'&&me?.businessIdentity==='REQUESTER'" :key="routeRevision" :initial-id="teacherId" :me="me" @matched="showMatches($event)" @expired="draftSessionExpired" @requests="prepareRequests" />
        <MatchProgress v-else-if="screen==='matches'&&me?.businessIdentity" :key="routeRevision" :me="me" :initial-id="matchId" :request-id="matchRequestId" @changed="refreshPendingReplies" @expired="draftSessionExpired" @profile="prepareProfile" @requests="prepareRequests" @contacts="showContacts" @browse="me?.businessIdentity==='TUTOR'?showPublic():showTeachers()" />
        <OperationsReview v-else-if="screen==='review'" ref="reviewRef" @expired="draftSessionExpired" />
        <section v-else-if="['profile','contacts'].includes(screen)" class="profile-page">
          <nav class="profile-tabs" aria-label="个人资料栏目"><button :aria-current="screen==='profile'?'page':undefined" :disabled="busy" @click="showProfile">基本资料</button><button :aria-current="screen==='contacts'?'page':undefined" :disabled="busy" @click="showContacts">私密联系方式</button></nav>
          <PrivateContacts v-if="screen==='contacts'&&me?.businessIdentity" ref="contactsRef" :key="me.id" @expired="draftSessionExpired" />
          <template v-else>
          <div class="page-heading"><div><p class="eyebrow">MY PROFILE</p><h1>{{ isTutor ? '让合适的人认识你' : '本人基本资料' }}</h1><p>{{ isTutor ? '资料由本人填写，不设人工审核。保存后，需要你主动公开。' : '填写展示名，就可以邀请老师或发布需求。' }}</p></div><span v-if="isTutor" :class="['pill', { published: isPublic }]">{{ isPublic ? '资料已公开' : '仅本人可见' }}</span></div>
          <div v-if="!profile" class="form-card"><p>本人资料尚未成功读取。</p><button class="primary" :disabled="busy" @click="run(loadAccount)">重新读取</button></div>
          <template v-else>
            <div v-if="conflict || latest" class="message warning"><p>请核对最新资料后再保存，当前输入仍保留。</p><button class="secondary" :disabled="busy" @click="readLatest">读取最新保存内容</button><div v-if="latest" class="latest-profile"><p>最新保存：{{ latest.displayName || '未填展示名' }} · 版本 {{ latest.version }}</p><p v-if="latest.teacher">{{ latest.teacher.introduction || '未填介绍' }}</p><details><summary>查看最新完整内容</summary><pre>{{ JSON.stringify(latest, null, 2) }}</pre></details><button class="secondary" :disabled="busy" @click="useLatest">载入最新内容，放弃当前输入</button><button class="secondary" :disabled="busy" @click="retainInput">已核对，保留输入继续编辑</button></div></div>
            <form class="form-card profile-form" @submit.prevent="save"><fieldset :disabled="busy"><legend class="sr-only">本人资料</legend>
              <label for="field-displayName">展示名 <span class="required">{{ isTutor && !isPublic ? '公开前必填' : '必填' }}</span><input id="field-displayName" v-model="form.displayName" autocomplete="nickname" :required="!isTutor || isPublic" :aria-invalid="!!fieldErrors.displayName"><small>{{ codePointLength(form.displayName) }}/30个字符；账号名不会自动作为展示名。</small></label>
              <template v-if="isTutor">
                <fieldset id="field-categoryCodes" class="choice-group"><legend>服务类别 <span class="required">公开前必填 · 可多选</span></legend><p v-if="!options.categoryCodes.length" class="inline-warning">当前没有开放的服务类别，可先保存其他资料，暂不能公开。</p><label v-for="code in options.categoryCodes" :key="code" class="choice"><input v-model="form.categoryCodes" type="checkbox" :value="code">{{ categoryNames[code] ?? code }}</label></fieldset>
                <fieldset id="field-serviceModes" class="choice-group"><legend>服务方式 <span class="required">公开前必填 · 可多选</span></legend><label v-for="code in options.serviceModes" :key="code" class="choice"><input v-model="form.serviceModes" type="checkbox" :value="code">{{ modeNames[code] ?? code }}</label></fieldset>
                <fieldset id="field-areaCodes" class="choice-group"><legend>服务区域 <span class="required">提供线下服务时必填 · 可多选</span></legend><p class="field-help">深圳市坪山区，仅线上服务可不填；不收精确住址。</p><label v-for="code in options.areaCodes" :key="code" class="choice"><input v-model="form.areaCodes" type="checkbox" :value="code">{{ areaNames[code] ?? code }}街道</label></fieldset>
                <label for="field-introduction">个人介绍 <span class="required">公开前必填</span><textarea id="field-introduction" v-model="form.introduction" rows="6" :required="isPublic" :aria-invalid="!!fieldErrors.introduction" placeholder="方向：小学三至六年级数学，或成人水彩入门。再介绍你的教学方式、教育和经验。"></textarea><small>{{ codePointLength(form.introduction) }}/1000个字符；建议先写明擅长技能、适教年级和科目，方便需求方选择。勿填手机号、微信、邮箱或精确住址。</small></label>
                <fieldset class="price-fields"><legend>报价区间 <span class="optional">选填 · 元/小时</span></legend><div class="price-inputs"><label for="field-priceMin">下限<input id="field-priceMin" v-model="form.priceMin" type="text" inputmode="decimal" placeholder="例如 100"></label><span>至</span><label for="field-priceMax">上限<input id="field-priceMax" v-model="form.priceMax" type="text" inputmode="decimal" placeholder="例如 200"></label></div><p class="field-help">两端均不填显示“面议”；填写时须同时填写，最多两位小数。</p></fieldset>
                <label for="field-availabilityText">可服务时间 <span class="optional">选填</span><textarea id="field-availabilityText" v-model="form.availabilityText" rows="3" placeholder="例如：周末白天可协商"></textarea><small>{{ codePointLength(form.availabilityText) }}/300个字符；不填显示“时间可协商”。</small></label>
                <p class="privacy-note">手机号、微信和邮箱不会随资料公开。联系方式在互选后的独立授权流程中处理。</p>
              </template>
              <div class="form-actions"><button class="primary" :disabled="conflict">{{ busy ? '正在处理…' : isPublic ? '保存并更新公开资料' : '保存资料' }}</button><button v-if="isTutor && !isPublic" type="button" class="secondary" :disabled="dirty || conflict" @click="preview = true">预览并公开</button><button v-if="isTutor && isPublic" type="button" class="text-button danger" @click="stopConfirm = true">停止公开</button><span class="save-state">{{ dirty ? '有尚未保存的修改' : '当前为已保存内容' }}</span></div>
              <p v-if="isPublic" class="field-help">保存成功即更新对外展示，历史意向资料不随之覆盖。</p>
            </fieldset></form>
          </template>
          </template>
        </section>
      </template>
    </main>
    <footer class="site-footer">邻课 · 坪山区域供需撮合 <span>先了解，再选择</span></footer>

    <dialog ref="previewDialog" class="native-dialog" aria-labelledby="preview-title" @cancel.prevent="!busy && (preview = false)"><section v-if="preview && profile?.teacher" class="dialog-card"><h2 id="preview-title">公开资料预览</h2><p class="field-help">以下为本人自述，未作资格核验。</p><h3>{{ profile.displayName || '未填展示名' }}</h3><p>{{ profile.teacher.categoryCodes.map(c => categoryNames[c] ?? c).join('、') || '未选择服务类别' }} · {{ profile.teacher.serviceModes.map(c => modeNames[c] ?? c).join('、') || '未选择服务方式' }}</p><p>{{ profile.teacher.areaCodes.map(c => (areaNames[c] ?? c) + '街道').join('、') }}</p><p class="pre-wrap">{{ profile.teacher.introduction || '未填介绍' }}</p><p>{{ profile.teacher.priceMin ? `¥${profile.teacher.priceMin}—${profile.teacher.priceMax} / 小时` : '面议' }}</p><p class="pre-wrap">{{ profile.teacher.availabilityText || '时间可协商' }}</p><p>公开后，已登录的需求方可查看以上资料。手机号、微信、邮箱不会公开。</p><div class="form-actions"><button class="secondary" :disabled="busy" @click="preview = false">返回编辑</button><button class="primary" :disabled="busy" @click="visibility(true)">确认公开资料</button></div><p v-if="error" class="inline-warning" role="alert">{{ error }} {{ Object.values(fieldErrors).join('；') }}</p></section></dialog>
    <dialog ref="stopDialog" class="native-dialog" aria-labelledby="stop-title" @cancel.prevent="!busy && (stopConfirm = false)"><section v-if="stopConfirm" class="dialog-card"><h2 id="stop-title">停止公开资料？</h2><p>将从老师发现中移除，也不能新增投递或被邀请。已有意向、互选及联系方式授权管理继续。</p><p v-if="dirty">未保存输入将留在当前页面，停止公开不会替你保存这些修改。</p><div class="form-actions"><button class="secondary" :disabled="busy" @click="stopConfirm = false">继续公开</button><button class="primary" :disabled="busy" @click="visibility(false)">确认停止公开</button></div><p v-if="error" class="inline-warning" role="alert">{{ error }}</p></section></dialog>
  </div>
</template>
