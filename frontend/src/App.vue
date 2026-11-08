<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from "vue";
import Icon from "./Icon.vue";
import {
  actorId,
  addMessage,
  confirmTrial,
  connect,
  copy,
  isOpen,
  labels,
  matchAction,
  newDraft,
  parentId,
  proposeTrial,
  publicRequests,
  requestAction,
  saveDraft,
  seed,
  tutorId,
  updateTutor,
  validateRequest,
} from "./domain.ts";
import type { Connection, Request, Role, State } from "./domain.ts";

const storageKey = "tuplatform-p0-v1";
const state = ref<State>(seed());
const role = ref<Role>("parent");
const route = ref("discover");
const search = ref("");
const area = ref("全部街道");
const category = ref("全部类别");
const notice = ref("");
const noticeError = ref(false);
const storageWarning = ref("");
let noticeTimer: ReturnType<typeof setTimeout>;
const dialog = ref<HTMLDialogElement>();
const modal = ref("");
const selectedId = ref("");
const draft = ref<Request>(newDraft());
const errors = ref<string[]>([]);
const reason = ref("");
const message = ref("");
const intro = ref("");
const price = ref(100);
const selectedRequestForInvite = ref("");
const trialDate = ref(
  new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10),
);
const trialTime = ref("14:00");
const trialFee = ref(100);
const trialDuration = ref(60);
const trialPlace = ref("坪山街道公共场所（演示，不采集精确住址）");
const trialCancellation = ref("如需取消，请提前一天在撮合留言中协商。");
const myTutor = computed(
  () => state.value.tutors.find((t) => t.id === tutorId)!,
);
const myRequests = computed(() =>
  state.value.requests.filter((r) => r.ownerId === parentId),
);
const openOwn = computed(() => myRequests.value.filter(isOpen));
const myConnections = computed(() =>
  state.value.connections.filter((c) =>
    role.value === "parent"
      ? myRequests.value.some((r) => r.id === c.requestId)
      : role.value === "tutor"
        ? c.tutorId === tutorId
        : false,
  ),
);
const selectedRequest = computed(() =>
  state.value.requests.find((r) => r.id === selectedId.value),
);
const selectedTutor = computed(() =>
  state.value.tutors.find((t) => t.id === selectedId.value),
);
const selectedConnection = computed(() =>
  myConnections.value.find((c) => c.id === selectedId.value),
);
const queue = computed(() =>
  state.value.requests.filter((r) => r.status === "PENDING_REVIEW"),
);
const matchesForRequest = computed(() =>
  myConnections.value.filter((c) => c.requestId === selectedId.value),
);
const nav = computed(() =>
  role.value === "admin"
    ? [
        { id: "review", label: "审核队列", icon: "shield" },
        { id: "manage", label: "需求管理", icon: "paper" },
        { id: "audit", label: "操作记录", icon: "clock" },
      ]
    : [
        {
          id: role.value === "parent" ? "discover" : "requests",
          label: role.value === "parent" ? "发现老师" : "需求广场",
          icon: role.value === "parent" ? "people" : "grid",
        },
        {
          id: "mine",
          label: role.value === "parent" ? "我的需求" : "我的投递",
          icon: "paper",
        },
        { id: "matches", label: "撮合进度", icon: "link" },
        { id: "profile", label: "我的资料", icon: "user" },
      ],
);
const currentTitle = computed(
  () => nav.value.find((n) => n.id === route.value)?.label || "演示指南",
);
const filteredTutors = computed(() =>
  state.value.tutors.filter(
    (t) =>
      (area.value === "全部街道" || t.areas.includes(area.value)) &&
      (category.value === "全部类别" || t.category === category.value) &&
      `${t.name}${t.intro}${t.category}`.includes(search.value),
  ),
);
const filteredRequests = computed(() =>
  publicRequests(state.value).filter(
    (r) =>
      (area.value === "全部街道" || r.area === area.value) &&
      (category.value === "全部类别" || r.category === category.value) &&
      `${r.title}${r.goal}`.includes(search.value),
  ),
);
const displayRequests = computed(() =>
  route.value === "review"
    ? queue.value
    : route.value === "manage"
      ? state.value.requests
      : route.value === "mine"
        ? myRequests.value
        : filteredRequests.value,
);
const steps = ["等待回应", "双方互选", "预约提议", "预约确认"];
const stageIndex = (c: Connection) =>
  ["PENDING", "MUTUAL_INTEREST", "TRIAL_PENDING", "TRIAL_CONFIRMED"].indexOf(
    c.status,
  );
const displayDate = (value: string) =>
  new Intl.DateTimeFormat("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(new Date(value));
const statusText = (r: Request) =>
  r.status === "OPEN" && !isOpen(r) ? "已到期" : labels[r.status];
function toast(text: string, error = false) {
  notice.value = text;
  noticeError.value = error;
  clearTimeout(noticeTimer);
  noticeTimer = setTimeout(() => (notice.value = ""), 4500);
}
function run(fn: () => unknown, success = "操作已保存") {
  try {
    fn();
    toast(success);
    return true;
  } catch (error) {
    toast(error instanceof Error ? error.message : "操作未完成", true);
    return false;
  }
}
function navigate(target: string) {
  route.value = target;
  search.value = "";
  area.value = "全部街道";
  category.value = "全部类别";
  window.location.hash = target;
  window.scrollTo({ top: 0 });
}
function changeRole(target: Role) {
  closeDialog();
  role.value = target;
  navigate(
    target === "admin"
      ? "review"
      : target === "tutor"
        ? "requests"
        : "discover",
  );
  toast(
    `已切换为${target === "parent" ? "家长" : target === "tutor" ? "林老师" : "运营"}演示身份`,
  );
}
async function showDialog(type: string, id = "") {
  selectedId.value = id;
  modal.value = type;
  errors.value = [];
  reason.value = "";
  message.value = "";
  notice.value = "";
  selectedRequestForInvite.value = openOwn.value[0]?.id || "";
  await nextTick();
  if (!dialog.value?.open) dialog.value?.showModal();
  dialog.value?.scrollTo({ top: 0 });
}
function closeDialog() {
  dialog.value?.close();
  modal.value = "";
}
function editRequest(r?: Request) {
  draft.value = r ? copy(r) : newDraft();
  showDialog("form");
}
function saveRequest(submit: boolean) {
  errors.value = submit ? validateRequest(draft.value) : [];
  if (errors.value.length) {
    nextTick(() => dialog.value?.scrollTo({ top: 0 }));
    return;
  }
  if (
    run(
      () => {
        const id = saveDraft(state.value, role.value, draft.value);
        if (submit) requestAction(state.value, role.value, id, "submit");
      },
      submit ? "已提交，请切换运营身份审核" : "草稿已保存",
    )
  ) {
    closeDialog();
    navigate("mine");
  }
}
function review(action: "approve" | "reject") {
  if (
    run(
      () =>
        requestAction(
          state.value,
          role.value,
          selectedId.value,
          action,
          reason.value,
        ),
      action === "approve" ? "审核通过，需求已公开" : "已驳回并记录原因",
    )
  )
    closeDialog();
}
function createConnection(
  requestId: string,
  id: string,
  source: "application" | "invitation",
) {
  let matchId = "";
  if (
    run(
      () => {
        matchId = connect(state.value, role.value, requestId, id, source);
      },
      source === "application"
        ? "投递已送达，等待家长回应"
        : "邀请已送达，等待老师回应",
    )
  ) {
    navigate("matches");
    showDialog("match", matchId);
  }
}
function relationAction(
  action: "accept" | "decline" | "withdraw" | "contact" | "revoke",
) {
  run(
    () => matchAction(state.value, role.value, selectedId.value, action),
    action === "accept" ? "双方已互选，可以协商预约" : "操作已记录",
  );
}
function canRespond(c: Connection) {
  return (
    c.status === "PENDING" &&
    (c.source === "application"
      ? role.value === "parent"
      : role.value === "tutor")
  );
}
function sendMessage() {
  if (
    run(
      () =>
        addMessage(state.value, role.value, selectedId.value, message.value),
      "留言已发送",
    )
  )
    message.value = "";
}
function submitTrial() {
  run(
    () =>
      proposeTrial(state.value, role.value, selectedId.value, {
        date: trialDate.value,
        time: trialTime.value,
        duration: trialDuration.value,
        fee: trialFee.value,
        unit: "每次",
        place: trialPlace.value,
        cancellation: trialCancellation.value,
      }),
    "预约方案已发送，等待对方确认",
  );
}
function reset() {
  state.value = seed();
  closeDialog();
  navigate(
    role.value === "admin"
      ? "review"
      : role.value === "tutor"
        ? "requests"
        : "discover",
  );
  toast("模拟数据已恢复初始状态");
}
watch(
  state,
  (value) => {
    try {
      localStorage.setItem(storageKey, JSON.stringify(value));
      storageWarning.value = "";
    } catch {
      storageWarning.value = "浏览器未能保存演示数据，刷新后可能丢失。";
    }
  },
  { deep: true },
);
onMounted(() => {
  try {
    const raw = localStorage.getItem(storageKey);
    if (raw) {
      const saved = JSON.parse(raw);
      if (
        saved.schemaVersion !== 1 ||
        !["requests", "tutors", "connections", "audit"].every((k) =>
          Array.isArray(saved[k]),
        )
      )
        throw new Error("invalid");
      state.value = saved;
    }
  } catch {
    storageWarning.value = "历史演示数据无法读取，已载入初始样例。";
  }
  const initialRoute = window.location.hash.slice(1);
  route.value =
    nav.value.some((n) => n.id === initialRoute) || initialRoute === "guide"
      ? initialRoute
      : nav.value[0]!.id;
  window.location.hash = route.value;
  window.addEventListener("hashchange", () => {
    const target = window.location.hash.slice(1);
    if (nav.value.some((n) => n.id === target) || target === "guide")
      route.value = target;
  });
});
</script>

<template>
  <div class="prototype-banner">
    <span class="banner-dot"></span>P0 交互原型 ·
    全部为模拟数据，未接入真实服务<span class="banner-end"
      >类别与年龄段待确认</span
    >
  </div>
  <div class="app-shell">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="navigate(nav[0]!.id)"
        ><span class="brand-mark">邻</span
        ><span>邻课<small>LOCAL CONNECTIONS</small></span></a
      >
      <div class="region">
        <Icon name="pin" :size="16" />深圳 · 坪山区<span>区域试点</span>
      </div>
      <div class="nav-caption">
        {{ role === "admin" ? "运营工作台" : "你的本地连接" }}
      </div>
      <nav aria-label="主要导航">
        <button
          v-for="item in nav"
          :key="item.id"
          :class="{ active: route === item.id }"
          @click="navigate(item.id)"
        >
          <Icon :name="item.icon" /><span>{{ item.label }}</span
          ><span v-if="item.id === 'review' && queue.length" class="count">{{
            queue.length
          }}</span>
        </button>
      </nav>
      <div class="sidebar-bottom">
        <div class="small-illustration">
          <span class="plant">⌁</span>
          <p>先认真了解<br />再放心选择。</p>
        </div>
        <button
          class="sidebar-help"
          :class="{ active: route === 'guide' }"
          @click="navigate('guide')"
        >
          <Icon name="book" />原型演示指南<Icon
            name="arrow"
            :size="15"
          /></button
        ><button class="reset-link" @click="showDialog('reset')">
          <Icon name="reset" :size="14" />重置模拟数据</button
        ><span class="version">P0 / 2026.10 · 本地原型</span>
      </div>
    </aside>
    <div class="main-shell">
      <header class="topbar">
        <div class="breadcrumb">
          工作台<span>/</span><strong>{{ currentTitle }}</strong>
        </div>
        <div class="role-switch" aria-label="演示身份">
          <button
            v-for="r in [
              ['parent', '家长'],
              ['tutor', '老师'],
              ['admin', '运营'],
            ] as const"
            :key="r[0]"
            :aria-pressed="role === r[0]"
            :class="{ selected: role === r[0] }"
            @click="changeRole(r[0])"
          >
            {{ r[1] }}
          </button>
        </div>
        <span class="identity-avatar">{{
          role === "parent" ? "许" : role === "tutor" ? "林" : "运"
        }}</span
        ><button
          class="topbar-help"
          aria-label="打开演示指南"
          @click="navigate('guide')"
        >
          <Icon name="book" :size="17" />
        </button>
      </header>
      <main>
        <div v-if="storageWarning" class="warning" role="alert">
          {{ storageWarning }}
        </div>
        <template v-if="route === 'discover' || route === 'requests'">
          <section class="hero">
            <div class="hero-copy">
              <div class="eyebrow"><span></span>在坪山，把连接做得更近一点</div>
              <h1>
                {{ role === "parent" ? "每一次合适的相遇，" : "让你的认真，"
                }}<br /><em>{{
                  role === "parent" ? "从认真了解开始。" : "遇见真实的需要。"
                }}</em>
              </h1>
              <p>
                {{
                  role === "parent"
                    ? "浏览资料，沟通期待，先体验再共同决定。"
                    : "找到时间和区域合适的需求，先沟通，再约定。"
                }}
              </p>
              <button
                v-if="role === 'parent'"
                class="button primary"
                @click="editRequest()"
              >
                <Icon name="plus" :size="17" />发布我的需求<Icon
                  name="arrow"
                  :size="17"
                /></button
              ><button
                v-else
                class="button primary"
                @click="navigate('profile')"
              >
                完善我的资料<Icon name="arrow" :size="17" />
              </button>
            </div>
            <div class="hero-art" aria-hidden="true">
              <div class="orb orb-one"></div>
              <div class="orb orb-two"></div>
              <div class="art-grid"></div>
              <div class="floating-card card-back">
                <span class="mini-avatar">林</span>
                <div>
                  <strong>认真准备每一次体验</strong
                  ><small>先交流 · 再确认</small>
                </div>
                <span class="tiny-check">✓</span>
              </div>
              <div class="floating-card card-front">
                <span class="art-symbol"><Icon name="link" :size="29" /></span>
                <div>
                  <strong>让信任，有迹可循</strong
                  ><small>资料核验 / 双方确认 / 过程留痕</small>
                </div>
              </div>
              <div class="art-caption">GOOD THINGS BEGIN NEARBY.</div>
            </div>
          </section>
          <div class="trust-strip">
            <span><Icon name="shield" :size="17" />核验范围清晰</span
            ><span><Icon name="link" :size="17" />双方意向确认</span
            ><span><Icon name="paper" :size="17" />约定过程留痕</span
            ><small>核验标签不代表教学能力或安全担保</small>
          </div>
          <section class="listing-section">
            <div class="section-heading">
              <div>
                <span class="eyebrow muted">{{
                  role === "parent"
                    ? "MEET YOUR NEXT CONNECTION"
                    : "NEEDS IN YOUR NEIGHBORHOOD"
                }}</span>
                <h2>
                  {{ role === "parent" ? "认识附近的老师" : "看看附近的需求"
                  }}<span class="result-count"
                    >{{
                      role === "parent"
                        ? filteredTutors.length
                        : filteredRequests.length
                    }}
                    {{ role === "parent" ? "位" : "条" }}</span
                  >
                </h2>
              </div>
              <span class="subtle">{{
                role === "parent"
                  ? "先按区域与时间了解对方"
                  : "仅展示有效、审核通过的需求"
              }}</span>
            </div>
            <div class="filters">
              <label class="search-field"
                ><Icon name="search" :size="18" /><input
                  v-model="search"
                  :aria-label="role === 'parent' ? '搜索老师' : '搜索需求'"
                  :placeholder="
                    role === 'parent'
                      ? '搜索老师、简介关键词'
                      : '搜索需求关键词'
                  " /></label
              ><select v-model="area" aria-label="筛选街道">
                <option>全部街道</option>
                <option
                  v-for="a in [
                    '坪山街道',
                    '坑梓街道',
                    '碧岭街道',
                    '龙田街道',
                    '石井街道',
                    '马峦街道',
                  ]"
                  :key="a"
                >
                  {{ a }}
                </option></select
              ><select v-model="category" aria-label="筛选类别">
                <option>全部类别</option>
                <option>示例类别 A</option>
                <option>示例类别 B</option></select
              ><span class="filter-note">{{
                role === "parent" ? "核验范围见资料详情" : "按最近确认时间展示"
              }}</span>
            </div>
            <div
              v-if="role === 'parent' && filteredTutors.length"
              class="tutor-grid"
            >
              <article
                v-for="t in filteredTutors"
                :key="t.id"
                class="tutor-card"
              >
                <div class="tutor-card-top">
                  <div :class="['avatar', t.color]">
                    {{ t.avatar }}<span v-if="t.verificationDate">✓</span>
                  </div>
                  <div>
                    <h3>
                      <button
                        class="name-link"
                        @click="showDialog('tutor', t.id)"
                      >
                        {{ t.name }}
                      </button>
                    </h3>
                    <p>{{ t.education }}</p>
                  </div>
                  <span class="available">可沟通</span>
                </div>
                <div class="tag-row">
                  <span class="tag">{{ t.category }}</span
                  ><span class="tag soft">{{ t.experience }}</span>
                </div>
                <p class="tutor-intro">{{ t.intro }}</p>
                <div class="card-facts">
                  <span
                    ><Icon name="pin" :size="14" />{{
                      t.areas.join(" / ")
                    }}</span
                  ><span><Icon name="clock" :size="14" />{{ t.slots }}</span>
                </div>
                <div class="card-bottom">
                  <div class="price">
                    <span>¥</span>{{ t.price }}<small>/ 小时起</small>
                  </div>
                  <button
                    class="button outline compact"
                    @click="showDialog('tutor', t.id)"
                  >
                    了解老师<Icon name="arrow" :size="15" />
                  </button>
                </div>
                <div class="verification-line">
                  <Icon name="shield" :size="13" />{{ t.verification }}
                </div>
              </article>
            </div>
            <div
              v-if="role === 'tutor' && filteredRequests.length"
              class="request-list"
            >
              <article
                v-for="r in filteredRequests"
                :key="r.id"
                class="request-card"
              >
                <div>
                  <div class="tag-row">
                    <span class="status OPEN">招募中</span
                    ><span class="subtle">{{ r.area }} · {{ r.source }}</span>
                  </div>
                  <h3>
                    <button
                      class="name-link"
                      @click="showDialog('request', r.id)"
                    >
                      {{ r.title }}
                    </button>
                  </h3>
                  <p>{{ r.goal }}</p>
                  <div class="tag-row">
                    <span class="tag">{{ r.category }}</span
                    ><span class="tag soft">{{ r.slots }}</span
                    ><span class="tag soft">{{ r.frequency }}</span>
                  </div>
                  <small class="subtle"
                    >最近确认 {{ displayDate(r.updatedAt) }} · 有效至
                    {{ displayDate(r.expiresAt) }}</small
                  >
                </div>
                <div class="request-side">
                  <div class="price">
                    <span>¥</span>{{ r.budget }}<small>/ 小时</small>
                  </div>
                  <button
                    class="button outline"
                    @click="showDialog('request', r.id)"
                  >
                    查看需求<Icon name="arrow" :size="15" />
                  </button>
                </div>
              </article>
            </div>
            <div
              v-if="
                role === 'parent'
                  ? !filteredTutors.length
                  : !filteredRequests.length
              "
              class="empty"
            >
              <Icon name="search" :size="32" />
              <h3>暂时没有合适的结果</h3>
              <p>试试其他街道或清除筛选条件。</p>
              <button
                class="button outline"
                @click="
                  search = '';
                  area = '全部街道';
                  category = '全部类别';
                "
              >
                清除筛选
              </button>
            </div>
          </section>
          <div class="gentle-note">
            <Icon name="book" /><span
              ><strong>不着急做决定</strong
              >先聊一聊彼此的目标和时间，双方确认后再安排体验。</span
            ><button @click="navigate('guide')">了解流程 →</button>
          </div>
        </template>

        <template
          v-else-if="
            ['mine', 'review', 'manage'].includes(route) &&
            !(role === 'tutor' && route === 'mine')
          "
        >
          <div class="page-heading">
            <div>
              <span class="eyebrow muted">{{
                role === "admin" ? "OPERATIONS" : "YOUR REQUESTS"
              }}</span>
              <h1>{{ currentTitle }}</h1>
              <p>
                {{
                  role === "admin"
                    ? "每一次公开，都从认真核对开始。"
                    : "需求、候选和下一步，都在这里。"
                }}
              </p>
            </div>
            <button
              v-if="role === 'parent'"
              class="button primary"
              @click="editRequest()"
            >
              <Icon name="plus" :size="18" />发布需求
            </button>
          </div>
          <div class="summary-grid">
            <div>
              <span>{{ role === "admin" ? "待审核" : "全部需求" }}</span
              ><strong>{{
                role === "admin" ? queue.length : myRequests.length
              }}</strong>
            </div>
            <div>
              <span>招募中</span
              ><strong>{{
                (role === "admin" ? state.requests : myRequests).filter(isOpen)
                  .length
              }}</strong>
            </div>
            <div>
              <span>{{
                role === "admin" ? "审核与状态记录" : "已建立的候选关系"
              }}</span
              ><strong>{{
                role === "admin" ? state.audit.length : myConnections.length
              }}</strong>
            </div>
          </div>
          <div class="request-list">
            <article
              v-for="r in displayRequests"
              :key="r.id"
              class="request-card"
            >
              <div>
                <div class="tag-row">
                  <span :class="['status', isOpen(r) ? 'OPEN' : r.status]">{{
                    statusText(r)
                  }}</span
                  ><span class="subtle">V{{ r.version }} · {{ r.area }}</span>
                </div>
                <h3>
                  <button
                    class="name-link"
                    @click="showDialog('request', r.id)"
                  >
                    {{ r.title || "未命名草稿" }}
                  </button>
                </h3>
                <p v-if="r.rejectReason" class="rejection">
                  审核意见：{{ r.rejectReason }}
                </p>
                <p v-else>{{ r.goal || "还未填写目标与现状" }}</p>
                <small class="subtle"
                  >最近更新 {{ displayDate(r.updatedAt) }} · 有效至
                  {{ displayDate(r.expiresAt) }}</small
                >
              </div>
              <div class="request-side">
                <button
                  class="button outline"
                  @click="showDialog('request', r.id)"
                >
                  {{
                    role === "admin" && r.status === "PENDING_REVIEW"
                      ? "去审核"
                      : "查看详情"
                  }}<Icon name="arrow" :size="15" /></button
                ><button
                  v-if="
                    role === 'parent' &&
                    ['DRAFT', 'REJECTED'].includes(r.status)
                  "
                  class="text-button"
                  @click="editRequest(r)"
                >
                  编辑并提交
                </button>
              </div>
            </article>
            <div v-if="!displayRequests.length" class="empty">
              <Icon name="check" :size="36" />
              <h3>
                {{ route === "review" ? "审核队列已清空" : "还没有需求" }}
              </h3>
              <p>
                {{
                  route === "review"
                    ? "新的模拟需求提交后会出现在这里。"
                    : "创建第一条需求，开始一次连接。"
                }}
              </p>
            </div>
          </div>
        </template>

        <template
          v-else-if="
            route === 'matches' || (route === 'mine' && role === 'tutor')
          "
        >
          <div class="page-heading">
            <div>
              <span class="eyebrow muted">YOUR CONNECTIONS</span>
              <h1>{{ currentTitle }}</h1>
              <p>把意向说清楚，让每一步都有回应。</p>
            </div>
          </div>
          <div v-if="!myConnections.length" class="empty large">
            <Icon name="link" :size="42" />
            <h3>你的下一次连接，还在路上</h3>
            <p>
              {{
                role === "parent"
                  ? "浏览老师并发出邀请，或等待老师投递。"
                  : "查看有效需求，投递自己的资料。"
              }}
            </p>
            <button
              class="button primary"
              @click="navigate(role === 'parent' ? 'discover' : 'requests')"
            >
              {{ role === "parent" ? "发现老师" : "浏览需求"
              }}<Icon name="arrow" :size="17" />
            </button>
          </div>
          <article
            v-for="c in myConnections"
            :key="c.id"
            class="connection-card"
          >
            <div class="connection-top">
              <div>
                <span class="eyebrow muted">{{
                  c.source === "application" ? "老师主动投递" : "家长发起邀请"
                }}</span>
                <h3>{{ c.requestSnapshot.title }}</h3>
                <p>
                  {{ state.tutors.find((t) => t.id === c.tutorId)?.name }} ·
                  需求 V{{ c.requestSnapshot.version }} ·
                  {{
                    c.tutorSnapshot
                      ? `简历 V${c.tutorSnapshot.version}`
                      : "接受邀请时冻结简历"
                  }}
                </p>
              </div>
              <span :class="['status', c.status]">{{ labels[c.status] }}</span>
            </div>
            <div class="stepper">
              <div
                v-for="(step, i) in steps"
                :key="step"
                :class="{ done: i <= stageIndex(c) }"
              >
                <span>{{ i < stageIndex(c) ? "✓" : `0${i + 1}` }}</span
                >{{ step }}
              </div>
            </div>
            <div class="connection-footer">
              <span class="subtle">{{
                c.trial
                  ? `${c.trial.date} ${c.trial.time} · 北京时间`
                  : "先沟通彼此期待，再决定是否预约"
              }}</span
              ><button
                class="button outline compact"
                @click="showDialog('match', c.id)"
              >
                查看并处理<Icon name="arrow" :size="15" />
              </button>
            </div>
          </article>
        </template>

        <template v-else-if="route === 'profile'">
          <div class="page-heading">
            <div>
              <span class="eyebrow muted">YOUR PROFILE</span>
              <h1>我的资料</h1>
              <p>清楚地介绍自己，也尊重彼此的信息边界。</p>
            </div>
          </div>
          <section v-if="role === 'tutor'" class="panel profile-panel">
            <div class="profile-heading">
              <div class="avatar sage">林</div>
              <div>
                <h2>林老师 <span class="tag">模拟账号</span></h2>
                <p>{{ myTutor.education }}</p>
              </div>
              <span class="status OPEN">简历 V{{ myTutor.version }}</span>
            </div>
            <p class="muted-text">{{ myTutor.intro }}</p>
            <dl class="detail-grid">
              <div>
                <dt>服务区域</dt>
                <dd>{{ myTutor.areas.join(" / ") }}</dd>
              </div>
              <div>
                <dt>可约时间</dt>
                <dd>{{ myTutor.slots }}</dd>
              </div>
              <div>
                <dt>报价</dt>
                <dd>¥{{ myTutor.price }} / 小时</dd>
              </div>
              <div>
                <dt>核验范围</dt>
                <dd>{{ myTutor.verification }}</dd>
              </div>
            </dl>
            <button
              class="button primary"
              @click="
                intro = myTutor.intro;
                price = myTutor.price;
                showDialog('profile');
              "
            >
              编辑简介与报价
            </button>
            <p class="help-text">
              修改只影响当前简历，已投递的版本快照保持不变。
            </p>
          </section>
          <section v-else class="panel profile-panel">
            <div class="profile-heading">
              <div class="avatar sand">许</div>
              <div>
                <h2>许家长 <span class="tag">模拟账号</span></h2>
                <p>深圳 · 坪山</p>
              </div>
            </div>
            <p class="muted-text">
              本原型使用固定家长身份，不创建学生独立账号，不收集真实姓名、电话或住址。
            </p>
            <div class="callout">
              定向联系方式交换在“撮合进度”中由双方分别同意，公开页面不会展示。
            </div>
          </section>
        </template>

        <template v-else-if="route === 'audit' && role === 'admin'">
          <div class="page-heading">
            <div>
              <span class="eyebrow muted">TRACEABLE ACTIONS</span>
              <h1>操作记录</h1>
              <p>模拟记录操作人、状态前后变化与处理理由。</p>
            </div>
          </div>
          <div class="panel audit-panel">
            <div v-for="a in state.audit" :key="a.id" class="audit-row">
              <span class="audit-dot"></span>
              <div>
                <strong>{{ a.action }}</strong>
                <p>{{ a.actor }} · {{ a.from || "起点" }} → {{ a.to }}</p>
                <p v-if="a.reason">{{ a.reason }}</p>
              </div>
              <time>{{ displayDate(a.at) }}</time>
            </div>
            <div v-if="!state.audit.length" class="empty">
              <Icon name="clock" :size="32" />
              <h3>还没有操作记录</h3>
              <p>提交、审核、投递等操作后会在这里留下记录。</p>
            </div>
          </div>
        </template>

        <template v-else>
          <div class="page-heading">
            <div>
              <span class="eyebrow muted">PROTOTYPE WALKTHROUGH</span>
              <h1>一次连接，怎样开始？</h1>
              <p>使用右上角切换角色；浏览器会保存本次模拟操作。</p>
            </div>
          </div>
          <div class="guide-grid">
            <article class="panel">
              <span class="guide-number">01</span>
              <h2>发布 → 审核 → 投递</h2>
              <ol>
                <li>家长：我的需求，发布需求并提交。</li>
                <li>运营：审核队列，查看详情并通过。</li>
                <li>老师：需求广场，找到新需求并投递。</li>
                <li>家长：撮合进度，回应投递并确认意向。</li>
                <li>双方互选后可留言、交换授权并预约。</li>
              </ol>
              <button
                class="button outline"
                @click="
                  changeRole('parent');
                  navigate('mine');
                "
              >
                从发布需求开始<Icon name="arrow" :size="16" />
              </button>
            </article>
            <article class="panel">
              <span class="guide-number">02</span>
              <h2>邀请 → 互选 → 预约</h2>
              <ol>
                <li>家长：发现老师，打开林老师详情。</li>
                <li>选择一条自己的招募中需求，发出邀请。</li>
                <li>老师：撮合进度，接受家长邀请。</li>
                <li>任一方：填写预约方案，发送给对方。</li>
                <li>另一方：查看同一版本方案并确认。</li>
              </ol>
              <button class="button outline" @click="changeRole('parent')">
                从了解老师开始<Icon name="arrow" :size="16" />
              </button>
            </article>
          </div>
          <div class="callout">
            <strong>原型边界</strong>
            <p>
              “邻课”为暂用演示名称。类别、年龄段、暂停后既有关系规则尚未确认；预约仅演示提议和双方确认。试课结果、合作、评价、投诉在本轮有规则和验收用例，尚未实现。角色切换不等于真实登录，浏览器数据不构成授权或并发安全验证。
            </p>
          </div>
        </template>
        <div v-if="route === 'guide'" class="guide-actions">
          <button class="button outline" @click="showDialog('reset')">
            <Icon name="reset" :size="16" />重置模拟数据
          </button>
        </div>
        <footer>
          <span>邻课 · 坪山区域撮合原型</span
          ><span
            >先了解，再连接。<span class="footer-dot"> · </span>P0 / MOCK
            DATA</span
          >
        </footer>
      </main>
    </div>
  </div>
  <div
    v-if="notice && !modal"
    class="toast"
    :role="noticeError ? 'alert' : 'status'"
  >
    <Icon name="bell" :size="18" />{{ notice }}
  </div>
  <dialog
    ref="dialog"
    class="detail-dialog"
    @cancel="modal = ''"
    @click.self="closeDialog()"
  >
    <div class="dialog-content">
      <button class="dialog-close" aria-label="关闭详情" @click="closeDialog()">
        <Icon name="close" />
      </button>
      <div
        v-if="notice"
        :class="['dialog-notice', { error: noticeError }]"
        :role="noticeError ? 'alert' : 'status'"
      >
        {{ notice }}
      </div>
      <template v-if="modal === 'tutor' && selectedTutor"
        ><span class="eyebrow muted">GET TO KNOW EACH OTHER</span>
        <div class="profile-heading">
          <div :class="['avatar', selectedTutor.color]">
            {{ selectedTutor.avatar }}
          </div>
          <div>
            <h2>{{ selectedTutor.name }}</h2>
            <p>{{ selectedTutor.education }}</p>
          </div>
        </div>
        <p class="lead">{{ selectedTutor.intro }}</p>
        <dl class="detail-grid">
          <div>
            <dt>服务类别</dt>
            <dd>{{ selectedTutor.category }}（待确认）</dd>
          </div>
          <div>
            <dt>报价</dt>
            <dd>¥{{ selectedTutor.price }} / 小时</dd>
          </div>
          <div>
            <dt>区域</dt>
            <dd>{{ selectedTutor.areas.join(" / ") }}</dd>
          </div>
          <div>
            <dt>可约时间</dt>
            <dd>{{ selectedTutor.slots }}</dd>
          </div>
          <div>
            <dt>核验范围</dt>
            <dd>{{ selectedTutor.verification }}</dd>
          </div>
          <div>
            <dt>核验日期</dt>
            <dd>{{ selectedTutor.verificationDate || "无" }}</dd>
          </div>
        </dl>
        <div class="callout">
          经验为本人自述；模拟核验标签不代表能力或安全担保。新老师暂无评价，不显示零分。
        </div>
        <label class="field"
          >用哪条需求发出邀请？<select v-model="selectedRequestForInvite">
            <option value="">请选择招募中的需求</option>
            <option v-for="r in openOwn" :key="r.id" :value="r.id">
              {{ r.title }}
            </option>
          </select></label
        ><button
          class="button primary full"
          :disabled="!selectedRequestForInvite"
          @click="
            createConnection(
              selectedRequestForInvite,
              selectedTutor.id,
              'invitation',
            )
          "
        >
          发出邀请<Icon name="arrow" :size="17" />
        </button>
        <p v-if="!openOwn.length" class="help-text">
          先发布需求并通过审核，再发出邀请。
        </p>
        <p v-else class="help-text">
          本轮可切换的老师身份为林老师；其他老师可浏览和邀请，暂不能模拟其回应。
        </p></template
      >

      <template v-else-if="modal === 'request' && selectedRequest"
        ><div class="tag-row">
          <span :class="['status', selectedRequest.status]">{{
            statusText(selectedRequest)
          }}</span
          ><span class="subtle">需求 V{{ selectedRequest.version }}</span>
        </div>
        <h2>{{ selectedRequest.title || "未命名草稿" }}</h2>
        <p class="lead">{{ selectedRequest.goal || "尚未填写目标与现状" }}</p>
        <dl class="detail-grid">
          <div>
            <dt>类别 / 年龄区间</dt>
            <dd>
              {{ selectedRequest.category }} /
              {{ selectedRequest.ageRange }}（待确认）
            </dd>
          </div>
          <div>
            <dt>预算与时长</dt>
            <dd>
              ¥{{ selectedRequest.budget }} / {{ selectedRequest.unit }} ·
              {{ selectedRequest.duration }} 分钟
            </dd>
          </div>
          <div>
            <dt>街道 / 方式</dt>
            <dd>{{ selectedRequest.area }} / {{ selectedRequest.mode }}</dd>
          </div>
          <div>
            <dt>频率</dt>
            <dd>{{ selectedRequest.frequency }}</dd>
          </div>
          <div>
            <dt>可约时间</dt>
            <dd>{{ selectedRequest.slots || "尚未填写" }}</dd>
          </div>
          <div>
            <dt>有效至</dt>
            <dd>{{ displayDate(selectedRequest.expiresAt) }}</dd>
          </div>
        </dl>
        <h4>对老师的期待</h4>
        <p class="muted-text">
          {{ selectedRequest.requirements || "暂无额外要求" }}
        </p>
        <p class="help-text">
          来源：{{ selectedRequest.source }} · 最近确认
          {{ displayDate(selectedRequest.updatedAt) }}
        </p>
        <div v-if="selectedRequest.rejectReason" class="warning">
          驳回原因：{{ selectedRequest.rejectReason }}
        </div>
        <template
          v-if="role === 'admin' && selectedRequest.status === 'PENDING_REVIEW'"
          ><label class="field"
            >驳回原因（驳回时必填）<textarea
              v-model="reason"
              placeholder="说明需要补充或修改的内容"
              rows="3"
            ></textarea>
          </label>
          <div class="button-row">
            <button class="button outline" @click="review('reject')">
              驳回并说明原因</button
            ><button class="button primary" @click="review('approve')">
              审核通过<Icon name="check" :size="17" />
            </button></div
        ></template>
        <template
          v-if="role === 'parent' && selectedRequest.ownerId === parentId"
          ><div class="button-row">
            <button
              v-if="['DRAFT', 'REJECTED'].includes(selectedRequest.status)"
              class="button primary"
              @click="editRequest(selectedRequest)"
            >
              编辑需求</button
            ><button
              v-if="isOpen(selectedRequest)"
              class="button outline"
              @click="
                run(() =>
                  requestAction(state, role, selectedRequest!.id, 'pause'),
                )
              "
            >
              暂停招募</button
            ><button
              v-if="selectedRequest.status === 'PAUSED'"
              class="button primary"
              @click="
                run(() =>
                  requestAction(state, role, selectedRequest!.id, 'resume'),
                )
              "
            >
              恢复招募</button
            ><button
              v-if="!['EXPIRED', 'CLOSED'].includes(selectedRequest.status)"
              class="button text-danger"
              @click="
                run(() =>
                  requestAction(state, role, selectedRequest!.id, 'close'),
                )
              "
            >
              关闭需求
            </button>
          </div>
          <h4>候选关系（{{ matchesForRequest.length }}）</h4>
          <p v-if="!matchesForRequest.length" class="muted-text">
            还没有候选，可以先去发现老师并发出邀请。
          </p>
          <button
            v-for="c in matchesForRequest"
            :key="c.id"
            class="candidate-row"
            @click="showDialog('match', c.id)"
          >
            <span>{{ state.tutors.find((t) => t.id === c.tutorId)?.name }}</span
            ><span>{{ labels[c.status] }} →</span>
          </button></template
        >
        <button
          v-if="role === 'tutor'"
          class="button primary full"
          :disabled="!isOpen(selectedRequest)"
          @click="createConnection(selectedRequest.id, tutorId, 'application')"
        >
          {{
            myConnections.some((c) => c.requestId === selectedRequest!.id)
              ? "查看已建立的关系"
              : "投递我的资料"
          }}<Icon name="arrow" :size="17" />
        </button>
      </template>

      <template v-else-if="modal === 'form'"
        ><span class="eyebrow muted">TELL US WHAT YOU NEED</span>
        <h2>
          {{
            state.requests.some((r) => r.id === draft.id)
              ? "编辑需求"
              : "发布一条需求"
          }}
        </h2>
        <p class="muted-text">
          先说清楚期待。提交后由运营审核，通过后才对外展示。
        </p>
        <form novalidate @submit.prevent="saveRequest(true)">
          <div v-if="errors.length" class="warning" role="alert">
            <p v-for="e in errors" :key="e">{{ e }}</p>
          </div>
          <label class="field"
            >需求标题<input
              v-model="draft.title"
              maxlength="60"
              placeholder="例如：寻找一位耐心的老师，先体验再决定"
          /></label>
          <div class="form-grid">
            <label class="field"
              >服务类别（演示占位）<select v-model="draft.category">
                <option>示例类别 A</option>
                <option>示例类别 B</option>
              </select></label
            ><label class="field"
              >年龄区间（演示占位）<select v-model="draft.ageRange">
                <option>演示区间 A</option>
                <option>演示区间 B</option>
              </select></label
            ><label class="field"
              >街道<select v-model="draft.area">
                <option
                  v-for="a in [
                    '坪山街道',
                    '坑梓街道',
                    '碧岭街道',
                    '龙田街道',
                    '石井街道',
                    '马峦街道',
                  ]"
                  :key="a"
                >
                  {{ a }}
                </option>
              </select></label
            ><label class="field"
              >服务方式<select v-model="draft.mode">
                <option>线下</option>
                <option>线上</option>
              </select></label
            ><label class="field"
              >预算（元 / 小时）<input
                v-model.number="draft.budget"
                type="number"
                min="1"
                step="1" /></label
            ><label class="field"
              >单次时长（分钟）<input
                v-model.number="draft.duration"
                type="number"
                min="15"
                max="240"
                step="15" /></label
            ><label class="field"
              >频率<select v-model="draft.frequency">
                <option>每周一次</option>
                <option>每周两次</option>
                <option>先体验一次</option>
              </select></label
            ><label class="field"
              >可预约时间<input
                v-model="draft.slots"
                placeholder="例如：周六下午 14:00—17:00"
            /></label>
          </div>
          <label class="field"
            >目标与现状<textarea
              v-model="draft.goal"
              rows="3"
              maxlength="500"
              placeholder="描述期待，不填写孩子真实姓名、电话或精确住址"
            ></textarea></label
          ><label class="field"
            >对老师的期待<textarea
              v-model="draft.requirements"
              rows="2"
              maxlength="300"
              placeholder="例如：沟通清晰，提前说明体验安排"
            ></textarea>
          </label>
          <p class="help-text">
            演示有效期：创建时起 14 天；类别、年龄及限制范围不代表可上线业务。
          </p>
          <div class="button-row">
            <button
              type="button"
              class="button outline"
              @click="saveRequest(false)"
            >
              保存草稿</button
            ><button class="button primary" type="submit">
              提交审核<Icon name="arrow" :size="17" />
            </button>
          </div></form
      ></template>

      <template v-else-if="modal === 'match' && selectedConnection"
        ><span class="eyebrow muted">A CONNECTION, STEP BY STEP</span>
        <h2>
          {{
            state.tutors.find((t) => t.id === selectedConnection!.tutorId)?.name
          }}
          · 撮合详情
        </h2>
        <p class="muted-text">{{ selectedConnection.requestSnapshot.title }}</p>
        <span :class="['status', selectedConnection.status]">{{
          labels[selectedConnection.status]
        }}</span>
        <div class="stepper modal-stepper">
          <div
            v-for="(step, i) in steps"
            :key="step"
            :class="{ done: i <= stageIndex(selectedConnection) }"
          >
            <span>{{ i < stageIndex(selectedConnection) ? "✓" : i + 1 }}</span
            >{{ step }}
          </div>
        </div>
        <div v-if="canRespond(selectedConnection)" class="button-row">
          <button class="button outline" @click="relationAction('decline')">
            婉拒意向</button
          ><button class="button primary" @click="relationAction('accept')">
            确认我也有意向<Icon name="check" :size="17" />
          </button>
        </div>
        <button
          v-else-if="selectedConnection.status === 'PENDING'"
          class="button outline"
          @click="relationAction('withdraw')"
        >
          撤回{{
            selectedConnection.source === "application" ? "投递" : "邀请"
          }}
        </button>
        <details class="snapshot">
          <summary>
            查看当时的资料快照 · 需求 V{{
              selectedConnection.requestSnapshot.version
            }}
            / 简历
            {{
              selectedConnection.tutorSnapshot
                ? `V${selectedConnection.tutorSnapshot.version}`
                : "待接受"
            }}
          </summary>
          <p>{{ selectedConnection.requestSnapshot.goal }}</p>
          <p v-if="selectedConnection.tutorSnapshot">
            {{ selectedConnection.tutorSnapshot.intro }} · ¥{{
              selectedConnection.tutorSnapshot.price
            }}
            / 小时
          </p>
          <p v-else>
            邀请时引用简历 V{{
              selectedConnection.invitedTutorVersion
            }}，接受时冻结当前简历与需求版本。
          </p>
        </details>
        <template
          v-if="!['DECLINED', 'WITHDRAWN'].includes(selectedConnection.status)"
          ><h4>沟通留言</h4>
          <div class="messages">
            <p v-if="!selectedConnection.messages.length" class="muted-text">
              还没有留言，先向对方打个招呼吧。
            </p>
            <div
              v-for="(m, i) in selectedConnection.messages"
              :key="i"
              :class="['message', { own: m.actor === role }]"
            >
              <small
                >{{ m.actor === "parent" ? "许家长" : "老师" }} ·
                {{ displayDate(m.at) }}</small
              >
              <p>{{ m.text }}</p>
            </div>
          </div>
          <form class="message-form" @submit.prevent="sendMessage">
            <input
              v-model="message"
              aria-label="留言内容"
              maxlength="500"
              placeholder="先交流目标与时间，请勿填写敏感信息"
            /><button
              class="button primary compact"
              :disabled="!message.trim()"
            >
              发送
            </button>
          </form>
          <template v-if="selectedConnection.status !== 'PENDING'"
            ><h4>联系方式 · 双方同意后交换</h4>
            <div class="callout">
              <template
                v-if="
                  selectedConnection.contactConsents.includes(parentId) &&
                  selectedConnection.contactConsents.includes(
                    selectedConnection.tutorId,
                  )
                "
                >双方已同意 ·
                演示联络标识：DEMO-CONTACT-001（非真实联系方式）</template
              ><template v-else
                >你{{
                  selectedConnection.contactConsents.includes(actorId(role))
                    ? "已同意"
                    : "尚未同意"
                }}交换；{{
                  selectedConnection.contactConsents.length
                    ? "等待另一方同意。"
                    : "双方分别授权后才展示。"
                }}</template
              >
            </div>
            <button
              class="button outline compact"
              @click="
                relationAction(
                  selectedConnection.contactConsents.includes(actorId(role))
                    ? 'revoke'
                    : 'contact',
                )
              "
            >
              {{
                selectedConnection.contactConsents.includes(actorId(role))
                  ? "撤回我的授权"
                  : "同意交换联系方式"
              }}
            </button>
            <p class="help-text">
              撤回停止后续展示，不能收回对方已经获知的信息。
            </p></template
          >
          <template v-if="selectedConnection.status === 'MUTUAL_INTEREST'"
            ><h4>提议一次体验预约</h4>
            <form @submit.prevent="submitTrial">
              <div class="form-grid">
                <label class="field"
                  >日期（北京时间）<input
                    v-model="trialDate"
                    type="date"
                    required /></label
                ><label class="field"
                  >开始时间<input
                    v-model="trialTime"
                    type="time"
                    required /></label
                ><label class="field"
                  >时长（分钟）<input
                    v-model.number="trialDuration"
                    type="number"
                    min="15"
                    max="240"
                    required /></label
                ><label class="field"
                  >体验费用（元 / 次）<input
                    v-model.number="trialFee"
                    type="number"
                    min="0"
                    step="1"
                    required
                /></label>
              </div>
              <label class="field"
                >地点说明<input v-model="trialPlace" required /></label
              ><label class="field"
                >取消约定<textarea
                  v-model="trialCancellation"
                  rows="2"
                  required
                ></textarea></label
              ><button class="button primary full">
                发送预约方案<Icon name="calendar" :size="17" />
              </button>
            </form>
            <p class="help-text">
              P0
              暂定：需求须有效且招募中才可预约；每段关系仅演示一次。正式多次预约在
              P5 实现。
            </p></template
          >
          <template v-if="selectedConnection.trial"
            ><h4>体验预约 · V{{ selectedConnection.trial.version }}</h4>
            <div class="trial-card">
              <strong
                ><Icon name="calendar" />{{ selectedConnection.trial.date }} ·
                {{ selectedConnection.trial.time }}</strong
              >
              <p>
                北京时间 · {{ selectedConnection.trial.duration }} 分钟 · ¥{{
                  selectedConnection.trial.fee
                }}
                / 次
              </p>
              <p>{{ selectedConnection.trial.place }}</p>
              <p>取消约定：{{ selectedConnection.trial.cancellation }}</p>
              <span :class="['status', selectedConnection.status]">{{
                labels[selectedConnection.status]
              }}</span>
            </div>
            <button
              v-if="
                selectedConnection.status === 'TRIAL_PENDING' &&
                selectedConnection.trial.proposedBy !== actorId(role)
              "
              class="button primary full"
              @click="
                run(
                  () =>
                    confirmTrial(
                      state,
                      role,
                      selectedConnection!.id,
                      selectedConnection!.trial!.version,
                    ),
                  '双方已确认同一版本预约',
                )
              "
            >
              确认这一版预约<Icon name="check" :size="17" />
            </button>
            <p
              v-else-if="selectedConnection.status === 'TRIAL_PENDING'"
              class="help-text"
            >
              已记录你的确认，请切换另一方身份查看并确认。
            </p>
            <div v-else class="callout">
              双方已确认安排。试课结果、合作与评价将在后续阶段实现；此处不模拟服务已完成。
            </div></template
          ></template
        >
      </template>

      <template v-else-if="modal === 'profile'"
        ><h2>编辑简介与报价</h2>
        <form
          @submit.prevent="
            run(
              () => updateTutor(state, role, intro, price),
              '简历已更新，历史投递快照保持不变',
            ) && closeDialog()
          "
        >
          <label class="field"
            >自我介绍<textarea
              v-model="intro"
              rows="5"
              maxlength="500"
              required
            ></textarea></label
          ><label class="field"
            >报价（元 / 小时）<input
              v-model.number="price"
              type="number"
              min="1"
              required /></label
          ><button class="button primary full">保存新版本</button>
        </form></template
      >
      <template v-else-if="modal === 'reset'"
        ><span class="eyebrow muted">START FRESH</span>
        <h2>重新开始这次演示？</h2>
        <p class="lead">
          清除当前浏览器中的模拟需求、投递、预约和操作记录，恢复初始样例。
        </p>
        <div class="button-row">
          <button class="button outline" @click="closeDialog()">
            保留当前数据</button
          ><button class="button primary" @click="reset">重置模拟数据</button>
        </div></template
      >
    </div>
  </dialog>
</template>
