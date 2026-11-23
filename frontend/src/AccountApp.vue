<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { api, ApiFailure } from "./account-api.ts";
import { motion, useReducedMotion } from "motion-v";
import { ArrowLeftRight, BookOpen, Compass, ClipboardList, MessagesSquare, ShieldCheck, LogOut, GraduationCap, Mail, Users, Search } from "@lucide/vue";
import { Popover, PopoverTrigger, PopoverContent } from "@/components/ui/popover";
import { Button } from "@/components/ui/button";
import { Skeleton } from "@/components/ui/skeleton";
import UserAvatar from "./UserAvatar.vue";
import TeacherResumeFields from "./TeacherResumeFields.vue";
import TeacherSummary from "./TeacherSummary.vue";
import DiscoverySheet from "./DiscoverySheet.vue";
import { resumeDraft, resumeFields, trialPolicies } from "./teacher-resume";
import AvatarUploader from "./AvatarUploader.vue";
import RequestDrafts from "./RequestDrafts.vue";
import OperationsReview from "./OperationsReview.vue";
import MatchProgress from "./MatchProgress.vue";
import PrivateContacts from "./PrivateContacts.vue";
import ConfirmDialog from "./ConfirmDialog.vue";
import { Badge } from "@/components/ui/badge";
import FeedbackToast from "./FeedbackToast.vue";
import type { Identity, Me, Profile, ProfileOptions } from "./account-api.ts";
import { codePointLength, moneyError, passwordError } from "./account-form.ts";
import { readScreen, routeTo, writeUrl, listenHistory, restoreScroll, type Screen } from "./navigation.ts";
import type {Match} from './match-types.ts';

const PublicRequests = defineAsyncComponent(() => import("./PublicRequests.vue"));
const TeacherDiscovery = defineAsyncComponent(() => import("./TeacherDiscovery.vue"));

const matchId=ref<string|undefined>(),matchRequestId=ref<string|undefined>();
const screen = ref<Screen>("home");
const authScreen = computed(() => ["login", "register", "forgot", "registered", "verify", "reset"].includes(screen.value));
const routeRevision = ref(0);
const returnTarget = ref(safeReturn(new URLSearchParams(location.search).get("returnTo")));
let stopHistory: (() => void) | undefined;
const pendingReplies=ref<number|null>(null);
let pendingRead=0,pendingTimer:ReturnType<typeof setInterval>|undefined;
const draftsRef = ref<InstanceType<typeof RequestDrafts>>();
const reviewRef = ref<InstanceType<typeof OperationsReview>>();
const contactsRef = ref<InstanceType<typeof PrivateContacts>>();
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
const livePreview = ref(false);
const previewButton = ref<HTMLButtonElement>();
const stopConfirm = ref(false);
const conflict = ref(false);
const latest = ref<Profile | null>(null);
const form = reactive({ resume: resumeDraft(), displayName: "", categoryCodes: [] as string[], serviceModes: [] as string[], areaCodes: [] as string[], introduction: "", priceMin: "", priceMax: "", availabilityText: "" });
const teacherValues = computed(() => {
  const { resume, ...base } = form;
  return { ...base, ...resumeFields(resume) };
});
const previewTeacher = computed(() => ({
  ...teacherValues.value, displayName: form.displayName || "尚未填写展示名", id: me.value?.id ?? "",
  profileVersion: profile.value?.version ?? 0, avatar_url: me.value?.avatar_url,
  resumePdfUrl: profile.value?.teacher?.resumePdfUrl, resumePdfName: profile.value?.teacher?.resumePdfName,
}));
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
  matchId.value = target === "matches" ? query.get("match") || undefined : undefined;
  matchRequestId.value = target === "matches" ? query.get("forRequest") || undefined : undefined;
  if (target !== "profile") livePreview.value = false;
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
const dirty = computed(() => profile.value !== null && JSON.stringify(form) !== baseline);
const needsIdentity = computed(() => !!me.value && !me.value.businessIdentity && !me.value.operatorGranted);
const isTutor = computed(() => me.value?.businessIdentity === "TUTOR");
const isPublic = computed(() => profile.value?.teacher?.public === true);
const accountName = computed(() => profile.value?.displayName || me.value?.account || "");
const reduceMotion = useReducedMotion();
const roleLabel = computed(() => isTutor.value ? "老师" : me.value?.businessIdentity === "REQUESTER" ? "需求方" : me.value?.operatorGranted ? "运营" : "未选身份");
const switchLabel = computed(() => isTutor.value ? "切为需求方" : me.value?.businessIdentity === "REQUESTER" ? "切为老师" : "切换账号");
const navItems = computed(() => [
  { view: "teachers", label: me.value ? "发现老师" : "找老师", icon: GraduationCap, show: !me.value || me.value.businessIdentity === "REQUESTER", go: showTeachers },
  { view: "public", label: "浏览需求", icon: Compass, show: !me.value || isTutor.value, go: showPublic },
  { view: "requests", label: "我的需求", icon: ClipboardList, show: me.value?.businessIdentity === "REQUESTER", go: showRequests },
  { view: "matches", label: "撮合进度", icon: MessagesSquare, show: !!me.value?.businessIdentity, go: () => showMatches() },
  { view: "profile", label: "我的资料", icon: BookOpen, show: isTutor.value, go: showProfile },
  { view: "review", label: "需求审核", icon: ShieldCheck, show: me.value?.operatorGranted, go: showReview },
].filter(item => item.show));
const categoryNames: Record<string, string> = { DRAWING: "绘画", DANCE: "舞蹈", OTHER_SKILL: "其他课外技能", EXTRACURRICULAR_STUDY: "课外学习" };
const modeNames: Record<string, string> = { ONLINE: "线上", OFFLINE: "线下" };
const areaNames: Record<string, string> = { PS_PINGSHAN: "坪山", PS_KENGZI: "坑梓", PS_LONGTIAN: "龙田", PS_SHIJING: "石井", PS_MALUAN: "马峦", PS_BILING: "碧岭" };

function resetMessages() { messageSequence.value++; error.value = ""; notice.value = ""; fieldErrors.value = {}; }
function clearPasswords() { credentials.password = ""; credentials.confirmation = ""; }
function clearPrivate() {
  pendingReplies.value=null;pendingRead++;
  matchId.value=undefined;matchRequestId.value=undefined;
  me.value = null; profile.value = null; latest.value = null; identity.value = ""; identityConfirmed.value = false;
  livePreview.value = false;
  Object.assign(form, { resume: resumeDraft(), displayName: "", categoryCodes: [], serviceModes: [], areaCodes: [], introduction: "", priceMin: "", priceMax: "", availabilityText: "" });
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
function updateAvatar(id: string, url: string | null) {
  if (me.value?.id !== id) return;
  me.value.avatar_url = url;
  if (profile.value) profile.value.avatar_url = url;
}
function loadForm(p: Profile) {
  profile.value = p;
  Object.assign(form, { resume: resumeDraft(p.teacher), displayName: p.displayName, categoryCodes: [...(p.teacher?.categoryCodes ?? [])], serviceModes: [...(p.teacher?.serviceModes ?? [])], areaCodes: [...(p.teacher?.areaCodes ?? [])], introduction: p.teacher?.introduction ?? "", priceMin: p.teacher?.priceMin ?? "", priceMax: p.teacher?.priceMax ?? "", availabilityText: p.teacher?.availabilityText ?? "" });
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
  if (isTutor.value && !teacherValues.value.teachGrades.length) throw new Error("请填写至少一个可授年级。");
  if (isTutor.value && !teacherValues.value.teachSubjects.length) throw new Error("请填写至少一个教授科目。");
  const body = isTutor.value ? { expectedVersion: profile.value.version, ...teacherValues.value } : { expectedVersion: profile.value.version, displayName: form.displayName };
  const result = await api.request<Profile>(isTutor.value ? "/me/teacher-profile" : "/me/profile", isTutor.value ? "PUT" : "PATCH", body);
  loadForm(result); notice.value = result.teacher?.public ? "资料已保存并更新公开展示。" : "资料已保存。";
  if (isTutor.value && !result.teacher?.public) {
    notice.value = "资料已保存。核对后点击“预览并公开”，公开后才能向需求方表达意向。";
  } else resumeDestination();
  await nextTick();
  window.scrollTo({ top: 0, behavior: "instant" });
}); }
async function changeResume(file?: File) {
  await run(async () => {
    if (!profile.value?.teacher || conflict.value) throw new Error("请先核对最新资料。");
    if (file && (!file.name.toLowerCase().endsWith(".pdf") || file.size > 5 * 1024 * 1024))
      throw new Error("请选择不超过 5 MB 的 PDF 文件。");
    const body = new FormData();
    if (file) body.append("file", file);
    const result = await api.request<{ resumePdfUrl: string | null; resumePdfName: string | null; version: number }>(
      "/upload/resume?expectedVersion=" + profile.value.version, file ? "POST" : "DELETE", file ? body : undefined);
    profile.value.version = result.version;
    Object.assign(profile.value.teacher, result);
    notice.value = file ? "PDF 简历已上传。" : "PDF 简历已删除。";
  });
}
function selectResume(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (file) void changeResume(file);
}
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
async function performLogout(destination: "home" | "login") { await run(async () => { await api.request("/auth/logout", "POST"); clearPrivate(); rememberFlow(""); returnTarget.value=""; navigate(destination); notice.value = "已退出登录。"; }); }
async function logout(destination: "home" | "login" = "home") {
  const finish = () => performLogout(destination);
  if (busy.value || booting.value) return;
  if (screen.value === "requests") { draftsRef.value?.requestLeave(finish); return; }
  if (screen.value === "review") { reviewRef.value?.requestLeave(finish); return; }
  if (screen.value === "contacts") { contactsRef.value?.requestLeave(finish); return; }
  if (dirty.value && !window.confirm("当前修改尚未保存，退出会放弃这些输入。确定退出？")) return;
  await finish();
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
    } else { updateAvatar(current.id, current.avatar_url); void refreshPendingReplies(); }
  } catch (e) {
    if (e instanceof ApiFailure && e.status === 401) { draftSessionExpired(); error.value = "登录已失效，请重新登录。"; }
  }
}
onMounted(() => { pendingTimer=setInterval(()=>void refreshPendingReplies(),60000);stopHistory=listenHistory(leaveCurrent,()=>{resetMessages();applyLocation();});window.addEventListener("beforeunload", beforeUnload); document.addEventListener("visibilitychange", reconcileSession); void initialize(); });
onBeforeUnmount(() => { pendingRead++;clearInterval(pendingTimer);stopHistory?.();window.removeEventListener("beforeunload", beforeUnload); document.removeEventListener("visibilitychange", reconcileSession); });
</script>

<template>
  <div class="flex min-h-dvh flex-col bg-background">
    <header class="sticky top-0 z-30 border-b border-border bg-white/80 text-foreground backdrop-blur-md">
      <div class="mx-auto flex max-w-7xl flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 md:h-[72px] md:flex-nowrap md:px-6 lg:gap-x-8 lg:px-8">
        <a href="/" aria-label="邻课首页" class="flex shrink-0 items-center gap-2 font-bold" @click.prevent="leaveCurrent(()=>{resetMessages();navigate('home');})">
          <span class="grid size-9 place-items-center rounded-xl bg-primary text-white"><BookOpen class="size-5" aria-hidden="true"/></span>
          <span class="text-2xl tracking-tight">邻课</span>
        </a>
        <nav v-if="!booting && !needsIdentity" aria-label="主要任务" class="order-last flex w-full min-w-0 items-center gap-1 overflow-x-auto rounded-2xl bg-slate-100 px-1 py-0.5 [scrollbar-width:none] md:order-none md:w-auto md:bg-transparent md:p-0">
          <button v-for="tab in navItems" :key="tab.view" type="button" :aria-label="tab.label"
            :aria-description="tab.view==='matches' && pendingReplies ? `有${pendingReplies}条意向等待你回应` : undefined"
            :aria-current="screen===tab.view ? 'page' : undefined" :disabled="busy" @click="tab.go()"
            class="relative inline-flex min-h-11 flex-1 items-center justify-center gap-1 rounded-xl px-1 text-sm font-medium text-secondary-foreground hover:bg-slate-100 focus-visible:outline-primary aria-[current=page]:text-primary md:flex-none md:gap-1.5 md:px-2 lg:px-3.5">
            <motion.span v-if="screen===tab.view" layout-id="active-task" :transition="{ duration: reduceMotion ? 0 : 0.2 }" class="absolute inset-0 rounded-xl bg-white shadow-xs md:bg-blue-50 md:shadow-none" />
            <component :is="tab.icon" class="relative hidden size-[18px] min-[360px]:block" aria-hidden="true"/>
            <span class="relative whitespace-nowrap">{{ tab.label }}</span>
            <small v-if="tab.view==='matches' && pendingReplies" aria-hidden="true" class="relative rounded-full bg-blue-100 px-1.5 text-xs text-primary"><span class="sr-only lg:not-sr-only">待回应 </span>{{ pendingReplies }}</small>
          </button>
        </nav>
        <div v-if="me" class="ml-auto flex shrink-0 items-center gap-2">
          <button aria-label="本人资料" title="查看本人资料" :aria-current="['profile','contacts'].includes(screen)?'page':undefined" :disabled="busy||booting||!me.businessIdentity" @click="showProfile"
            class="flex min-h-11 items-center gap-2 rounded-xl px-1 text-left hover:bg-slate-50 focus-visible:outline-primary aria-[current=page]:bg-blue-50 sm:px-2">
            <UserAvatar :url="me.avatar_url" :name="accountName" :color-key="me.id" class="size-9 rounded-full"/>
            <span><strong class="hidden max-w-32 truncate text-sm md:block">{{ accountName }}</strong><span class="block text-xs text-secondary-foreground">{{ roleLabel }}</span></span>
          </button>
          <Popover><PopoverTrigger as-child><Button variant="secondary" :aria-label="switchLabel === '切换账号' ? switchLabel : switchLabel + '（切换账号）'" title="切换账号" class="h-11 gap-1.5 rounded-full bg-slate-100 text-secondary-foreground" :disabled="busy||booting"><ArrowLeftRight/><span>{{ switchLabel }}</span></Button></PopoverTrigger>
            <PopoverContent align="end" class="w-60 space-y-3 rounded-2xl">
              <strong class="block text-sm [overflow-wrap:anywhere]">{{ accountName }}</strong>
              <p class="text-sm text-secondary-foreground">当前身份为{{ roleLabel }}。可登录另一账号使用其业务身份。</p>
              <Button class="w-full" :disabled="busy" @click="logout('login')">退出并切换账号</Button>
              <Button variant="ghost" class="w-full" :disabled="busy" @click="logout()"><LogOut/>退出登录</Button>
            </PopoverContent>
          </Popover>
        </div>
        <Button v-else class="ml-auto shrink-0" :disabled="busy||booting" @click="showLogin">登录 / 注册</Button>
      </div>
    </header>
    <main class="mx-auto w-full max-w-7xl flex-1 px-4 py-8 sm:px-6 sm:py-10 lg:px-8" :class="authScreen && 'flex flex-col justify-center'" :aria-busy="busy || booting">
      <div v-if="booting" role="status" class="space-y-4 py-8"><span class="sr-only">正在读取账号状态…</span><Skeleton class="h-8 w-44"/><Skeleton class="h-28 w-full rounded-2xl"/></div>
      <template v-else>
        <FeedbackToast v-if="error" :key="'error-' + messageSequence" :message="error" kind="error" :field-errors="fieldErrors" />
        <FeedbackToast v-else-if="notice" :key="'notice-' + messageSequence" :message="notice" kind="success" />
        <p v-if="me?.operatorGranted && !me.email" class="mb-4 rounded-xl bg-slate-100 p-4 text-sm text-secondary-foreground">运营账号未绑定邮箱，忘记密码请通过维护操作重置。</p>
        <div v-if="returnTarget && ['profile','contacts','requests'].includes(screen)" class="mb-5 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-border bg-card p-4 text-sm"><span>处理完成后，可以继续查看刚才的对象。</span><Button variant="secondary" class="h-11 rounded-xl" :disabled="busy" @click="continueBrowsing">返回刚才的页面</Button></div>

        <section v-if="screen==='home'" class="mx-auto max-w-5xl space-y-6">
          <div class="rounded-2xl border border-border bg-card p-6 shadow-sm sm:p-10">
            <p class="mb-3 text-sm font-medium text-secondary-foreground">邻课 · 深圳坪山</p>
            <h1 class="max-w-3xl text-3xl leading-tight font-bold tracking-tight sm:text-5xl">找合适的老师，<br>遇见合适的教学需求。</h1>
            <p class="mt-4 max-w-xl leading-7 text-secondary-foreground">从方向、时间和预算开始了解彼此。先表达意向，双方同意后交换联系方式，再沟通具体安排。</p>
            <div class="mt-6 flex flex-wrap gap-3"><Button class="h-11 rounded-xl" @click="showTeachers"><Search/>我想找老师</Button><Button variant="secondary" class="h-11 rounded-xl" @click="showPublic">我是老师，找需求</Button></div>
            <p class="mt-3 text-sm text-muted-foreground">老师资料需以需求方身份登录查看；公开需求可以先浏览。</p>
          </div>
          <div class="grid gap-4 md:grid-cols-3">
            <article v-for="part in [
              {icon:Compass,title:'找到合适的对象',text:'需求方查看老师的教学方向与报价，老师寻找时间、地点和目标合适的需求。'},
              {icon:MessagesSquare,title:'表达意向，等待回应',text:'需求方可直接邀请心仪的老师，也可发布需求等待投递。对方接受后达成互选。'},
              {icon:ShieldCheck,title:'双方同意后联系',text:'发送和接受意向时即同意交换已填写的联系方式，互选后直接联系，商量后续安排。'}
            ]" :key="part.title" class="space-y-3 rounded-2xl border border-border bg-card p-5 shadow-sm">
              <component :is="part.icon" class="size-5 text-secondary-foreground"/><h2 class="text-xl font-bold">{{ part.title }}</h2><p class="text-sm leading-6 text-secondary-foreground">{{ part.text }}</p>
            </article>
          </div>
        </section>
        <section v-else-if="authScreen" aria-label="账号服务" class="account-fields mx-auto w-full max-w-md rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-8">
          <div class="[&>form]:space-y-4 [&>div]:space-y-4">
            <form v-if="screen === 'login'" @submit.prevent="login">
              <h1>登录邻课</h1><p class="text-[15px] leading-6 text-secondary-foreground">输入账号和密码继续。</p>
              <label for="field-account">账号<input id="field-account" v-model="credentials.account" name="username" autocomplete="username" required :disabled="busy"></label>
              <label for="field-password">密码<input id="field-password" v-model="credentials.password" name="password" type="password" autocomplete="current-password" required :disabled="busy"></label>
              <Button type="submit" class="h-11 rounded-xl w-full" :disabled="busy">{{ busy ? '正在登录…' : '登录' }}</Button>
              <div class="flex flex-wrap justify-between gap-2"><Button type="button" variant="ghost" class="h-11 rounded-xl" @click="go('forgot')">忘记密码</Button><Button type="button" variant="ghost" class="h-11 rounded-xl" @click="go('register')">注册新账号 →</Button></div>
              <Button v-if="registrationFlowId" type="button" variant="ghost" class="h-11 rounded-xl" @click="go('registered')">回到注册进度 / 重新发送验证邮件</Button>
              <Button v-if="!me && error" type="button" variant="secondary" class="h-11 rounded-xl w-full" :disabled="busy" @click="run(loadAccount)">重新检查账号状态</Button>
            </form>
            <form v-else-if="screen === 'register'" @submit.prevent="register">
              <h1>创建账号</h1><p class="text-[15px] leading-6 text-secondary-foreground">注册后验证邮箱，再确认一种业务身份。</p>
              <label for="field-account">账号 <small>4—16位，字母开头</small><input id="field-account" v-model="credentials.account" autocomplete="username" pattern="[A-Za-z][A-Za-z0-9_]{3,15}" minlength="4" maxlength="16" required :disabled="busy"></label>
              <label for="field-email">验证邮箱<input id="field-email" v-model="credentials.email" type="email" autocomplete="email" required :disabled="busy"></label>
              <label for="field-password">密码 <small>8—64个字符，可包含空格</small><input id="field-password" v-model="credentials.password" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <label>确认密码<input v-model="credentials.confirmation" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <label id="field-adultConfirmed" class="flex items-center gap-2 text-sm"><input v-model="credentials.adultConfirmed" type="checkbox" required :disabled="busy"><span>我确认本人已年满18岁。</span></label>
              <Button type="submit" class="h-11 rounded-xl w-full" :disabled="busy || !credentials.adultConfirmed">{{ busy ? '正在注册…' : '注册并验证邮箱' }}</Button><Button type="button" variant="ghost" class="h-11 rounded-xl w-full" @click="go('login')">已有账号，返回登录</Button>
            </form>
            <div v-else-if="screen === 'registered'">
              <Mail class="size-8 text-primary" aria-hidden="true"/><h1>请验证注册邮箱</h1><p class="text-[15px] leading-6 text-secondary-foreground">{{ mailAccepted ? '发信请求已受理，请检查收件箱和垃圾邮件。' : '注册已保存，邮件尚未受理发送，请稍后重试。' }}</p><p>打开邮件链接后，需要在页面上明确确认。验证不会自动登录。未验证注册自首次登记起保留7天，重发不会延长。</p>
              <Button class="h-11 rounded-xl w-full" :disabled="busy || !registrationFlowId" @click="resend">重新发送验证邮件</Button><Button variant="ghost" class="h-11 rounded-xl w-full" @click="go('login')">返回登录</Button>
            </div>
            <form v-else-if="screen === 'forgot'" @submit.prevent="forgot">
              <h1>找回密码</h1><p class="text-[15px] leading-6 text-secondary-foreground">输入注册时绑定的验证邮箱。</p><label for="field-email">邮箱<input id="field-email" v-model="credentials.email" type="email" autocomplete="email" required :disabled="busy"></label>
              <Button type="submit" class="h-11 rounded-xl w-full" :disabled="busy">{{ busy ? '正在受理…' : '申请重置邮件' }}</Button><Button type="button" variant="ghost" class="h-11 rounded-xl w-full" @click="go('login')">返回登录</Button>
            </form>
            <form v-else-if="screen === 'verify'" @submit.prevent="verify">
              <h1>确认验证邮箱</h1><p class="text-[15px] leading-6 text-secondary-foreground">仅打开此页面不会完成验证。</p>
              <template v-if="requiresRegistrationPassword && validLink"><p>当前浏览器没有原注册上下文，请核对注册时设置的密码。</p><label for="field-registrationPassword">注册密码<input id="field-registrationPassword" v-model="credentials.password" type="password" autocomplete="current-password" required :disabled="busy"></label></template>
              <Button type="submit" class="h-11 rounded-xl w-full" :disabled="busy || !validLink">确认验证</Button><Button v-if="!validLink" type="button" variant="secondary" class="h-11 rounded-xl w-full" :disabled="busy" @click="run(inspectLink)">重新检查链接</Button><Button type="button" variant="ghost" class="h-11 rounded-xl w-full" @click="go('login')">返回登录</Button>
            </form>
            <form v-else-if="screen === 'reset'" @submit.prevent="reset">
              <h1>设置新密码</h1><p class="text-[15px] leading-6 text-secondary-foreground">确认后将退出全部旧会话，需要重新登录。</p><label for="field-password">新密码 <small>8—64个字符</small><input id="field-password" v-model="credentials.password" type="password" autocomplete="new-password" required :disabled="busy"></label><label>确认新密码<input v-model="credentials.confirmation" type="password" autocomplete="new-password" required :disabled="busy"></label>
              <Button type="submit" class="h-11 rounded-xl w-full" :disabled="busy">确认重置密码</Button><Button type="button" variant="ghost" class="h-11 rounded-xl w-full" @click="go('login')">返回登录</Button>
            </form>
          </div>
        </section>

        <section v-else-if="screen === 'identity'" class="mx-auto max-w-2xl space-y-4 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-8">
          <h1 class="text-3xl font-bold">你希望如何参与？</h1><p class="text-secondary-foreground">一个账号选择一种业务身份。请按本人实际用途确认。</p>
          <form @submit.prevent="chooseIdentity"><fieldset :disabled="busy" class="space-y-4"><legend class="sr-only">选择业务身份</legend>
            <div class="grid gap-3 sm:grid-cols-2">
              <label v-for="role in [{code:'REQUESTER',label:'我是需求方',icon:Users,text:'为成年本人，或作为监护人为未成年人发布需求，寻找合适的老师。'},{code:'TUTOR',label:'我是老师',icon:GraduationCap,text:'介绍自己的服务，主动公开资料，向合适的需求表达意向。'}]" :key="role.code"
                class="relative grid cursor-pointer gap-2 rounded-xl border border-border p-4 has-checked:border-primary has-checked:bg-blue-50">
                <input v-model="identity" type="radio" name="identity" :value="role.code" class="absolute top-4 right-4 accent-primary">
                <component :is="role.icon" class="size-5"/><strong class="text-xl">{{ role.label }}</strong><span class="text-sm text-secondary-foreground">{{ role.text }}</span>
              </label>
            </div>
            <label class="flex items-center gap-2 text-sm"><input v-model="identityConfirmed" type="checkbox" required class="accent-primary"><span>我已理解上述职责。</span></label>
            <Button type="submit" class="h-11 w-full rounded-xl" :disabled="!identity || !identityConfirmed">确认身份并继续</Button>
          </fieldset></form>
          <Button v-if="conflict" variant="outline" :disabled="busy" @click="run(loadAccount)">读取已确认的账号身份</Button>
        </section>

        <RequestDrafts v-else-if="screen === 'requests'" ref="draftsRef" :key="routeRevision" @expired="draftSessionExpired" @profile="prepareProfile" @matches="showMatches(undefined,$event)" />
        <Suspense v-else-if="screen==='public'">
          <PublicRequests :key="me?.id" :route-revision="routeRevision" :me="me" @matched="showMatches($event)" @expired="draftSessionExpired" @profile="prepareProfile" @login="showLogin" />
          <template #fallback><div role="status"><span class="sr-only">正在打开公开需求…</span><Skeleton class="h-40 w-full rounded-2xl"/></div></template>
        </Suspense>
        <Suspense v-else-if="screen==='teachers'&&me?.businessIdentity==='REQUESTER'">
          <TeacherDiscovery :key="me.id" :route-revision="routeRevision" :me="me" @matched="showMatches($event)" @expired="draftSessionExpired" @requests="prepareRequests" @profile="prepareProfile" />
          <template #fallback><div role="status"><span class="sr-only">正在打开发现老师…</span><Skeleton class="h-40 w-full rounded-2xl"/></div></template>
        </Suspense>
        <MatchProgress v-else-if="screen==='matches'&&me?.businessIdentity" :key="routeRevision" :me="me" :initial-id="matchId" :request-id="matchRequestId" @changed="refreshPendingReplies" @expired="draftSessionExpired" @profile="prepareProfile" @requests="prepareRequests" @contacts="showContacts" @browse="me?.businessIdentity==='TUTOR'?showPublic():showTeachers()" />
        <OperationsReview v-else-if="screen==='review'" ref="reviewRef" @expired="draftSessionExpired" />
        <section v-else-if="['profile','contacts'].includes(screen)" class="mx-auto max-w-4xl">
          <nav class="mb-6 inline-flex gap-1 rounded-full border border-border bg-card p-1 [&>button]:rounded-full [&>button]:px-4 [&>button]:py-2 [&>button]:text-sm [&>button[aria-current=page]]:bg-blue-50 [&>button[aria-current=page]]:text-primary" aria-label="个人资料栏目"><button :aria-current="screen==='profile'?'page':undefined" :disabled="busy" @click="showProfile">基本资料</button><button :aria-current="screen==='contacts'?'page':undefined" :disabled="busy" @click="showContacts">私密联系方式</button></nav>
          <PrivateContacts v-if="screen==='contacts'&&me?.businessIdentity" ref="contactsRef" :key="me.id" @expired="draftSessionExpired" />
          <template v-else>
          <div class="mb-5 flex flex-wrap items-center justify-between gap-3 [&_h1]:text-3xl [&_h1]:font-bold [&_p]:mt-2 [&_p]:text-secondary-foreground"><div><h1>{{ isTutor ? '让合适的人认识你' : '本人基本资料' }}</h1><p>{{ isTutor ? (isPublic ? '修改并保存后，需求方即可看到最新资料。' : '完善教学资料后，可以预览并决定是否公开。') : '填写展示名，就可以邀请老师或发布需求。' }}</p></div><div class="flex flex-wrap items-center gap-3"><button v-if="isTutor && profile" ref="previewButton" type="button" :disabled="busy" class="min-h-11 rounded-xl border border-blue-200 bg-blue-50 px-4 text-sm font-semibold text-primary" @click="livePreview=true">预览家长看到的样子</button><Badge v-if="isTutor" variant="secondary" :class="isPublic && 'bg-emerald-50 text-emerald-800'">{{ isPublic ? '资料已公开' : '仅本人可见' }}</Badge></div></div>
          <Skeleton v-if="!profile && busy" class="h-64 w-full rounded-2xl"/>
          <div v-else-if="!profile" class="space-y-4 rounded-2xl border border-border bg-card p-6"><p>本人资料尚未成功读取。</p><Button class="h-11 rounded-xl" :disabled="busy" @click="run(loadAccount)">重新读取</Button></div>
          <template v-else>
            <div v-if="conflict || latest" class="mb-4 space-y-3 rounded-xl bg-orange-50 p-4 text-sm text-orange-900"><p>请核对最新资料后再保存，当前输入仍保留。</p><Button variant="secondary" class="h-11 rounded-xl" :disabled="busy" @click="readLatest">读取最新保存内容</Button><div v-if="latest" class="space-y-2 [&_pre]:whitespace-pre-wrap"><p>最新保存：{{ latest.displayName || '未填展示名' }} · 版本 {{ latest.version }}</p><p v-if="latest.teacher">{{ latest.teacher.introduction || '未填介绍' }}</p><details><summary>查看最新完整内容</summary><pre>{{ JSON.stringify(latest, null, 2) }}</pre></details><Button variant="secondary" class="h-11 rounded-xl" :disabled="busy" @click="useLatest">载入最新内容，放弃当前输入</Button><Button variant="secondary" class="h-11 rounded-xl" :disabled="busy" @click="retainInput">已核对，保留输入继续编辑</Button></div></div>
            <h2 v-if="isTutor" class="mb-3 text-xl font-bold">基本信息与头像</h2>
            <AvatarUploader v-if="me" :key="me.id" :url="me.avatar_url" :name="accountName" :account-id="me.id" :teacher="isTutor" :disabled="busy"
              class="mb-4" @uploading="busy = $event" @uploaded="updateAvatar" @expired="draftSessionExpired"/>
            <form class="account-fields" @submit.prevent="save"><fieldset :disabled="busy" class="space-y-5"><legend class="sr-only">本人资料</legend>
              <section class="resume-section"><label for="field-displayName">展示名 <span class="ml-2 text-xs font-normal text-muted-foreground">{{ isTutor && !isPublic ? '公开前必填' : '必填' }}</span><input id="field-displayName" v-model="form.displayName" autocomplete="nickname" :required="!isTutor || isPublic" :aria-invalid="!!fieldErrors.displayName"><small>{{ codePointLength(form.displayName) }}/30个字符；账号名不会自动作为展示名。</small></label></section>
              <template v-if="isTutor">
                <TeacherResumeFields v-model="form.resume"/>
                <section class="resume-section"><h2>教学背景与服务说明</h2>
                <fieldset id="field-categoryCodes" class="space-y-2"><legend>服务类别 <span class="ml-2 text-xs font-normal text-muted-foreground">公开前必填 · 可多选</span></legend><p v-if="!options.categoryCodes.length" class="rounded-xl bg-orange-50 p-3 text-sm text-orange-900">当前没有开放的服务类别，可先保存其他资料，暂不能公开。</p><label v-for="code in options.categoryCodes" :key="code" class="mr-2 mb-2 inline-flex cursor-pointer items-center gap-2 rounded-full border border-border px-3 py-2 text-sm has-checked:border-blue-200 has-checked:bg-blue-50"><input v-model="form.categoryCodes" type="checkbox" :value="code">{{ categoryNames[code] ?? code }}</label></fieldset>
                <label for="field-introduction">个人介绍 <span class="ml-2 text-xs font-normal text-muted-foreground">公开前必填</span><textarea id="field-introduction" v-model="form.introduction" rows="6" :required="isPublic" :aria-invalid="!!fieldErrors.introduction" placeholder="方向：小学三至六年级数学，或成人水彩入门。再介绍你的教学方式、教育和经验。"></textarea><small>{{ codePointLength(form.introduction) }}/1000个字符；可分段填写教学背景、教学方式和服务说明。勿填手机号、微信、邮箱或精确住址。</small></label>
                </section>
                <section class="resume-section"><h2>试课政策与服务安排</h2>
                <fieldset id="field-trialPolicy"><legend>首次试课政策</legend><div class="flex flex-wrap gap-2"><label v-for="policy in trialPolicies" :key="policy" class="choice"><input v-model="form.resume.trialPolicy" type="radio" :value="policy">{{ policy }}</label></div></fieldset>
                <fieldset id="field-serviceModes" class="space-y-2"><legend>服务方式 <span class="ml-2 text-xs font-normal text-muted-foreground">公开前必填 · 可多选</span></legend><label v-for="code in options.serviceModes" :key="code" class="mr-2 mb-2 inline-flex cursor-pointer items-center gap-2 rounded-full border border-border px-3 py-2 text-sm has-checked:border-blue-200 has-checked:bg-blue-50"><input v-model="form.serviceModes" type="checkbox" :value="code">{{ modeNames[code] ?? code }}</label></fieldset>
                <fieldset id="field-areaCodes" class="space-y-2"><legend>服务区域 <span class="ml-2 text-xs font-normal text-muted-foreground">提供线下服务时必填 · 可多选</span></legend><p class="text-sm leading-6 text-secondary-foreground">深圳市坪山区，仅线上服务可不填；不收精确住址。</p><label v-for="code in options.areaCodes" :key="code" class="mr-2 mb-2 inline-flex cursor-pointer items-center gap-2 rounded-full border border-border px-3 py-2 text-sm has-checked:border-blue-200 has-checked:bg-blue-50"><input v-model="form.areaCodes" type="checkbox" :value="code">{{ areaNames[code] ?? code }}街道</label></fieldset>

                <fieldset class="space-y-2"><legend>报价区间 <span class="ml-2 text-xs font-normal text-muted-foreground">选填 · 元/小时</span></legend><div class="grid grid-cols-[1fr_auto_1fr] items-center gap-3"><label for="field-priceMin">下限<input id="field-priceMin" v-model="form.priceMin" type="text" inputmode="decimal" placeholder="例如 100"></label><span>至</span><label for="field-priceMax">上限<input id="field-priceMax" v-model="form.priceMax" type="text" inputmode="decimal" placeholder="例如 200"></label></div><p class="text-sm leading-6 text-secondary-foreground">两端均不填显示“面议”；填写时须同时填写，最多两位小数。</p></fieldset>
                <label for="field-availabilityText">可服务时间 <span class="ml-2 text-xs font-normal text-muted-foreground">选填</span><textarea id="field-availabilityText" v-model="form.availabilityText" rows="3" placeholder="例如：周末白天可协商"></textarea><small>{{ codePointLength(form.availabilityText) }}/300个字符；不填显示“时间可协商”。</small></label>
                <p class="mb-4 rounded-xl bg-slate-100 p-4 text-sm text-secondary-foreground">手机号、微信和邮箱不会随资料公开。发送或接受意向时同意交换，互选后仅向对方展示。</p></section>
                <section id="field-file" class="resume-section"><h2>PDF 简历附件 <small>选填</small></h2>
                  <p class="text-sm text-secondary-foreground">上传或删除后立即生效。资料公开时，需求方可下载附件。请先移除联系方式与学生身份信息。</p>
                  <p v-if="profile.teacher?.resumePdfUrl" class="flex flex-wrap items-center gap-3"><a :href="profile.teacher.resumePdfUrl" target="_blank" rel="noopener" class="break-all text-primary underline">{{ profile.teacher.resumePdfName }}</a>
                    <Button type="button" variant="ghost" :disabled="conflict" @click="changeResume()">删除附件</Button></p>
                  <label>{{ profile.teacher?.resumePdfUrl ? '替换' : '选择' }} PDF（最大 5 MB）<input type="file" accept=".pdf,application/pdf" :disabled="conflict" @change="selectResume"></label>
                </section>
              </template>
              <div class="flex flex-wrap items-center gap-2"><Button type="submit" class="h-11 rounded-xl" :disabled="conflict">{{ busy ? '正在处理…' : isPublic ? '保存并更新公开资料' : '保存资料' }}</Button><Button v-if="isTutor && !isPublic" type="button" variant="secondary" class="h-11 rounded-xl" :disabled="dirty || conflict" @click="preview = true">预览并公开</Button><Button v-if="isTutor && isPublic" type="button" variant="ghost" class="h-11 rounded-xl text-red-700" @click="stopConfirm = true">停止公开</Button><span class="text-sm text-muted-foreground">{{ dirty ? '有尚未保存的修改' : '当前为已保存内容' }}</span></div>
              <ul v-if="Object.keys(fieldErrors).length" aria-label="填写错误" class="space-y-1 rounded-xl bg-red-50 p-4 text-sm text-red-800"><li v-for="(message, field) in fieldErrors" :key="field"><a :href="'#field-' + field" class="underline">{{ message }}</a></li></ul>
              <p v-if="isPublic" class="text-sm leading-6 text-secondary-foreground">保存成功即更新对外展示，历史意向资料不随之覆盖。</p>
            </fieldset></form>
          </template>
          </template>
        </section>
      </template>
    </main>
    <footer class="mt-auto flex flex-wrap justify-between gap-3 border-t border-border bg-white px-[max(1rem,calc((100%-76rem)/2))] pt-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))] text-sm text-muted-foreground">邻课 · 坪山区域供需撮合 <span>先了解，再选择</span></footer>

    <DiscoverySheet v-if="isTutor && screen==='profile'" :open="livePreview" title="老师资料" description="预览当前填写的公开资料" back-label="返回编辑" @close="livePreview=false" @restore-focus="$event.preventDefault(); previewButton?.focus()">
      <div class="min-h-0 overflow-y-auto px-5 pb-8 sm:px-8"><TeacherSummary :teacher="previewTeacher" public-profile/></div>
    </DiscoverySheet>
    <ConfirmDialog :open="preview" title="公开资料预览" cancel-text="返回编辑" confirm-text="确认公开资料" :busy="busy" @cancel="preview=false" @confirm="visibility(true)">
      <template v-if="profile?.teacher">
        <TeacherSummary :teacher="previewTeacher" public-profile/>
        <p>公开后，已登录的需求方可查看以上资料。手机号、微信、邮箱不会公开。</p>
      </template>
      <p v-if="error" class="rounded-xl bg-red-50 p-3 text-red-800" role="alert">{{ error }} {{ Object.values(fieldErrors).join('；') }}</p>
    </ConfirmDialog>
    <ConfirmDialog :open="stopConfirm" title="停止公开资料？" cancel-text="继续公开" confirm-text="确认停止公开" :busy="busy" @cancel="stopConfirm=false" @confirm="visibility(false)">
      <p>将从老师发现中移除，也不能新增投递或被邀请。已有意向、互选及联系方式授权管理继续。</p>
      <p v-if="dirty">未保存输入将留在当前页面，停止公开不会替你保存这些修改。</p><p v-if="error" role="alert" class="text-red-700">{{ error }}</p>
    </ConfirmDialog>
  </div>
</template>

<style scoped>
@reference "./styles/theme.css";
.account-fields :deep(.resume-section) { @apply space-y-5 rounded-2xl border border-border bg-card p-5 shadow-sm sm:p-6; }
.account-fields :deep(.resume-section h2) { @apply text-xl font-bold; }
.account-fields :deep(.choice) { @apply inline-flex cursor-pointer items-center gap-2 rounded-full border border-border px-3 py-2 text-sm has-checked:border-blue-200 has-checked:bg-blue-50; }
.account-fields h1 { @apply text-2xl font-bold tracking-tight; }
.account-fields :deep(label:has(input:not([type=checkbox],[type=radio]))), .account-fields :deep(label:has(textarea)) { @apply block text-sm font-semibold; }
.account-fields :deep(input:not([type=checkbox],[type=radio])), .account-fields :deep(textarea) { @apply field-control mt-2; }
.account-fields :deep(small) { @apply text-xs leading-5 font-normal text-muted-foreground; }
.account-fields input + small, .account-fields textarea + small { @apply mt-1 block; }
.account-fields legend { @apply mb-2 text-sm font-semibold; }
</style>
