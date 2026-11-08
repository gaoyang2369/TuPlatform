// P0 browser-only simulation. These guards are examples, not a security boundary.
export type Role = "parent" | "tutor" | "admin";
export type RequestStatus =
  | "DRAFT"
  | "PENDING_REVIEW"
  | "REJECTED"
  | "OPEN"
  | "PAUSED"
  | "CLOSED"
  | "EXPIRED";
export type MatchStatus =
  | "PENDING"
  | "MUTUAL_INTEREST"
  | "TRIAL_PENDING"
  | "TRIAL_CONFIRMED"
  | "DECLINED"
  | "WITHDRAWN";
export const labels: Record<string, string> = {
  DRAFT: "草稿",
  PENDING_REVIEW: "待审核",
  REJECTED: "已驳回",
  OPEN: "招募中",
  PAUSED: "已暂停",
  CLOSED: "已关闭",
  EXPIRED: "已到期",
  PENDING: "等待对方回应",
  MUTUAL_INTEREST: "已互选",
  TRIAL_PENDING: "预约待确认",
  TRIAL_CONFIRMED: "预约已确认",
  DECLINED: "已婉拒",
  WITHDRAWN: "已撤回",
};
export interface Request {
  id: string;
  ownerId: string;
  title: string;
  category: string;
  ageRange: string;
  area: string;
  budget: number;
  unit: string;
  duration: number;
  frequency: string;
  slots: string;
  mode: string;
  goal: string;
  requirements: string;
  status: RequestStatus;
  version: number;
  expiresAt: string;
  updatedAt: string;
  source: string;
  rejectReason: string;
}
export interface Tutor {
  id: string;
  name: string;
  avatar: string;
  color: string;
  education: string;
  category: string;
  areas: string[];
  price: number;
  slots: string;
  intro: string;
  experience: string;
  verification: string;
  verificationDate: string;
  version: number;
}
export interface Trial {
  id: string;
  version: number;
  date: string;
  time: string;
  duration: number;
  fee: number;
  unit: string;
  place: string;
  cancellation: string;
  proposedBy: string;
  confirmedBy: string[];
}
export interface Connection {
  id: string;
  requestId: string;
  tutorId: string;
  source: "application" | "invitation";
  parentInterested: boolean;
  tutorInterested: boolean;
  status: MatchStatus;
  requestSnapshot: Request;
  tutorSnapshot: Tutor | null;
  invitedTutorVersion: number;
  messages: { actor: Role; text: string; at: string }[];
  contactConsents: string[];
  trial: Trial | null;
}
export interface Audit {
  id: string;
  at: string;
  actor: string;
  action: string;
  target: string;
  from: string;
  to: string;
  reason: string;
}
export interface State {
  schemaVersion: 1;
  requests: Request[];
  tutors: Tutor[];
  connections: Connection[];
  audit: Audit[];
}
export const parentId = "parent-demo";
export const tutorId = "tutor-lin";
export const actorId = (role: Role) =>
  role === "parent" ? parentId : role === "tutor" ? tutorId : "admin-demo";
const now = () => new Date().toISOString();
export const copy = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;
const uid = (prefix: string) => `${prefix}-${crypto.randomUUID()}`;
const dateAfter = (days: number) =>
  new Date(Date.now() + days * 86400000).toISOString();
export function seed(): State {
  const base: Request = {
    id: "request-open",
    ownerId: parentId,
    title: "寻找一位耐心的老师，先从一次体验开始",
    category: "示例类别 A",
    ageRange: "演示区间 A",
    area: "坪山街道",
    budget: 120,
    unit: "每小时",
    duration: 60,
    frequency: "每周一次",
    slots: "周六 14:00—17:00",
    mode: "线下",
    goal: "希望先沟通学习目标，再通过一次体验了解双方是否合适。此为合成需求。",
    requirements: "沟通清晰、有耐心，能够提前说明体验安排。",
    status: "OPEN",
    version: 1,
    expiresAt: dateAfter(14),
    updatedAt: now(),
    source: "家长直发（模拟）",
    rejectReason: "",
  };
  return {
    schemaVersion: 1,
    requests: [
      base,
      {
        ...base,
        id: "request-review",
        title: "周末的陪伴，从一次认真沟通开始",
        status: "PENDING_REVIEW",
        area: "坑梓街道",
      },
      {
        ...base,
        id: "request-rejected",
        title: "希望找到时间合适的老师",
        status: "REJECTED",
        rejectReason: "请补充可预约时间及明确的服务目标。",
        slots: "",
      },
      {
        ...base,
        id: "request-expired",
        title: "一条已到期的历史需求",
        status: "EXPIRED",
        expiresAt: dateAfter(-2),
      },
      {
        ...base,
        id: "request-other",
        ownerId: "parent-other",
        title: "希望先交流，再确认体验安排",
        area: "碧岭街道",
        category: "示例类别 B",
        budget: 100,
      },
    ],
    tutors: [
      {
        id: tutorId,
        name: "林老师",
        avatar: "林",
        color: "sage",
        education: "教育相关专业 · 本科（合成）",
        category: "示例类别 A",
        areas: ["坪山街道", "坑梓街道"],
        price: 100,
        slots: "周六下午、周日上午",
        intro:
          "比起匆忙开始，我更希望先听听你的目标。每次体验前都会准备一份清晰的安排。",
        experience: "2 年自述经验",
        verification: "学历材料核验（模拟）",
        verificationDate: "2026-10-28",
        version: 1,
      },
      {
        id: "tutor-chen",
        name: "陈老师",
        avatar: "陈",
        color: "sand",
        education: "人文相关专业 · 本科（合成）",
        category: "示例类别 B",
        areas: ["碧岭街道", "坪山街道"],
        price: 120,
        slots: "周末上午",
        intro: "重视节奏与反馈，愿意和家长一起把每次体验的目标说清楚。",
        experience: "3 年自述经验",
        verification: "身份核验（模拟）",
        verificationDate: "2026-10-28",
        version: 1,
      },
      {
        id: "tutor-zhou",
        name: "周老师",
        avatar: "周",
        color: "clay",
        education: "综合类专业 · 本科（合成）",
        category: "示例类别 A",
        areas: ["坑梓街道", "龙田街道"],
        price: 90,
        slots: "周六全天",
        intro: "希望建立轻松、耐心的沟通方式，先体验，再共同决定是否继续。",
        experience: "1 年自述经验",
        verification: "未申请核验",
        verificationDate: "",
        version: 1,
      },
    ],
    connections: [],
    audit: [],
  };
}
function invariant(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}
function record(
  state: State,
  actor: string,
  action: string,
  target: string,
  from: string,
  to: string,
  reason = "",
) {
  state.audit.unshift({
    id: uid("audit"),
    at: now(),
    actor,
    action,
    target,
    from,
    to,
    reason,
  });
}
export const isOpen = (request: Request) =>
  request.status === "OPEN" && Date.parse(request.expiresAt) > Date.now();
export const publicRequests = (state: State) =>
  state.requests
    .filter(isOpen)
    .sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt));
export function validateRequest(request: Request): string[] {
  const errors: string[] = [];
  if (!request.title.trim()) errors.push("请填写需求标题");
  if (!request.goal.trim()) errors.push("请说明目标与现状");
  if (!request.slots.trim()) errors.push("请填写可预约时间");
  if (!request.category || !request.ageRange || !request.area)
    errors.push("请选择类别、年龄区间和街道");
  if (!Number.isFinite(request.budget) || request.budget <= 0)
    errors.push("预算需大于 0");
  if (
    !Number.isFinite(request.duration) ||
    request.duration < 15 ||
    request.duration > 240
  )
    errors.push("演示时长范围为 15—240 分钟");
  if (!(Date.parse(request.expiresAt) > Date.now()))
    errors.push("有效期需晚于当前时间");
  return errors;
}
export function newDraft(): Request {
  return {
    ...seed().requests[0]!,
    id: uid("request"),
    title: "",
    goal: "",
    requirements: "",
    slots: "",
    status: "DRAFT",
    source: "家长直发（模拟）",
  };
}
export function saveDraft(state: State, role: Role, input: Request) {
  invariant(
    role === "parent" && input.ownerId === parentId,
    "只有持有人可以编辑需求",
  );
  const old = state.requests.find((r) => r.id === input.id);
  invariant(
    !old ||
      (old.ownerId === parentId && ["DRAFT", "REJECTED"].includes(old.status)),
    "原型仅支持修改草稿和驳回需求",
  );
  const draft = copy(input);
  draft.status = "DRAFT";
  draft.updatedAt = now();
  draft.version = old ? old.version + 1 : 1;
  if (old) Object.assign(old, draft);
  else state.requests.unshift(draft);
  record(
    state,
    parentId,
    "保存草稿",
    draft.id,
    old ? "已有版本" : "新建",
    "DRAFT",
  );
  return draft.id;
}
export function requestAction(
  state: State,
  role: Role,
  id: string,
  action: "submit" | "approve" | "reject" | "pause" | "resume" | "close",
  reason = "",
) {
  const r = state.requests.find((r) => r.id === id);
  invariant(r, "需求不存在");
  const from = r.status;
  if (action === "approve" || action === "reject") {
    invariant(role === "admin", "只有运营可以审核");
    invariant(from === "PENDING_REVIEW", "仅待审核需求可处理");
    invariant(action !== "reject" || reason.trim(), "请填写驳回原因");
    invariant(
      action !== "approve" || validateRequest(r).length === 0,
      "需求字段或有效期不完整，不能通过审核",
    );
    r.status = action === "approve" ? "OPEN" : "REJECTED";
    r.rejectReason = action === "reject" ? reason.trim() : "";
  } else {
    invariant(
      role === "parent" && r.ownerId === parentId,
      "只有需求持有人可以操作",
    );
    if (action === "submit") {
      invariant(["DRAFT", "REJECTED"].includes(from), "当前状态不能提交");
      invariant(!validateRequest(r).length, validateRequest(r).join("；"));
      r.status = "PENDING_REVIEW";
    }
    if (action === "pause") {
      invariant(isOpen(r), "只有有效招募中的需求可暂停");
      r.status = "PAUSED";
    }
    if (action === "resume") {
      invariant(
        from === "PAUSED" && Date.parse(r.expiresAt) > Date.now(),
        "暂停且未到期的需求才能恢复",
      );
      r.status = "OPEN";
    }
    if (action === "close") {
      invariant(
        ["DRAFT", "PENDING_REVIEW", "REJECTED", "OPEN", "PAUSED"].includes(
          from,
        ),
        "当前状态不可关闭",
      );
      r.status = "CLOSED";
    }
  }
  r.updatedAt = now();
  record(state, actorId(role), action, id, from, r.status, reason);
}
export function connect(
  state: State,
  role: Role,
  requestId: string,
  targetTutor: string,
  source: Connection["source"],
) {
  const r = state.requests.find((r) => r.id === requestId);
  const t = state.tutors.find((t) => t.id === targetTutor);
  invariant(r && t, "需求或老师不存在");
  invariant(isOpen(r), "仅招募中且未到期的需求可投递或邀请");
  invariant(
    source === "application"
      ? role === "tutor" && targetTutor === tutorId
      : role === "parent" && r.ownerId === parentId,
    "当前身份无权发起此操作",
  );
  const previous = state.connections.find(
    (c) => c.requestId === requestId && c.tutorId === targetTutor,
  );
  // P0 proposal: repeated active requests return the same relation; terminal relations cannot reapply yet.
  if (previous) {
    invariant(
      !["WITHDRAWN", "DECLINED"].includes(previous.status),
      "撤回或拒绝后的重投规则待确认，本原型不开放重投",
    );
    return previous.id;
  }
  const c: Connection = {
    id: uid("match"),
    requestId,
    tutorId: targetTutor,
    source,
    parentInterested: source === "invitation",
    tutorInterested: source === "application",
    status: "PENDING",
    requestSnapshot: copy(r),
    tutorSnapshot: source === "application" ? copy(t) : null,
    invitedTutorVersion: t.version,
    messages: [],
    contactConsents: [],
    trial: null,
  };
  state.connections.unshift(c);
  record(
    state,
    actorId(role),
    source === "application" ? "投递" : "邀请",
    c.id,
    "",
    "PENDING",
  );
  return c.id;
}
function allowed(state: State, role: Role, c: Connection) {
  const r = state.requests.find((r) => r.id === c.requestId);
  invariant(
    r &&
      ((role === "parent" && r.ownerId === parentId) ||
        (role === "tutor" && c.tutorId === tutorId)),
    "仅该撮合关系的双方可操作",
  );
  return r;
}
export function matchAction(
  state: State,
  role: Role,
  id: string,
  action: "accept" | "decline" | "withdraw" | "contact" | "revoke",
) {
  const c = state.connections.find((c) => c.id === id);
  invariant(c, "撮合关系不存在");
  const r = allowed(state, role, c);
  const from = c.status;
  invariant(!["DECLINED", "WITHDRAWN"].includes(c.status), "该关系已结束");
  if (action === "accept") {
    invariant(isOpen(r), "需求已不可招募，请先处理需求状态");
    invariant(c.status === "PENDING", "当前不是待回应状态");
    invariant(
      c.source === "application" ? role === "parent" : role === "tutor",
      "请由对方回应意向",
    );
    if (role === "parent") c.parentInterested = true;
    else {
      c.tutorInterested = true;
      c.tutorSnapshot = copy(state.tutors.find((t) => t.id === c.tutorId)!);
      c.requestSnapshot = copy(r);
    }
    if (c.parentInterested && c.tutorInterested) c.status = "MUTUAL_INTEREST";
  }
  if (action === "decline") {
    invariant(
      c.status === "PENDING" &&
        (c.source === "application" ? role === "parent" : role === "tutor"),
      "只有待回应方可婉拒",
    );
    c.status = "DECLINED";
  }
  if (action === "withdraw") {
    invariant(
      c.status === "PENDING" &&
        (c.source === "application" ? role === "tutor" : role === "parent"),
      "仅发起方可撤回尚未回应的意向",
    );
    c.status = "WITHDRAWN";
  }
  if (action === "contact" || action === "revoke") {
    invariant(
      ["MUTUAL_INTEREST", "TRIAL_PENDING", "TRIAL_CONFIRMED"].includes(
        c.status,
      ),
      "请先完成双方互选",
    );
    c.contactConsents = c.contactConsents.filter((a) => a !== actorId(role));
    if (action === "contact") c.contactConsents.push(actorId(role));
  }
  record(state, actorId(role), action, id, from, c.status);
}
export function addMessage(state: State, role: Role, id: string, text: string) {
  const c = state.connections.find((c) => c.id === id);
  invariant(c, "关系不存在");
  allowed(state, role, c);
  invariant(
    !["DECLINED", "WITHDRAWN"].includes(c.status),
    "已结束关系不能继续留言",
  );
  invariant(text.trim() && text.length <= 500, "留言需为 1—500 字");
  c.messages.push({ actor: role, text: text.trim(), at: now() });
}
export function proposeTrial(
  state: State,
  role: Role,
  id: string,
  draft: Omit<Trial, "id" | "version" | "proposedBy" | "confirmedBy">,
) {
  const c = state.connections.find((c) => c.id === id);
  invariant(c, "关系不存在");
  const r = allowed(state, role, c);
  invariant(isOpen(r), "请先恢复有效招募状态，再安排预约（P0暂定规则）");
  invariant(
    c.status === "MUTUAL_INTEREST",
    "请先完成互选；本原型每段关系演示一次预约",
  );
  invariant(
    Date.parse(`${draft.date}T${draft.time}:00+08:00`) > Date.now(),
    "预约时间需晚于当前时间（北京时间）",
  );
  invariant(
    draft.duration >= 15 &&
      draft.duration <= 240 &&
      draft.fee >= 0 &&
      Number.isFinite(draft.fee),
    "请检查时长和费用",
  );
  invariant(
    draft.place.trim() && draft.cancellation.trim(),
    "请填写地点说明与取消条件",
  );
  c.trial = {
    ...copy(draft),
    id: uid("trial"),
    version: 1,
    proposedBy: actorId(role),
    confirmedBy: [actorId(role)],
  };
  c.status = "TRIAL_PENDING";
  record(state, actorId(role), "提议预约", id, "MUTUAL_INTEREST", c.status);
}
export function confirmTrial(
  state: State,
  role: Role,
  id: string,
  version: number,
) {
  const c = state.connections.find((c) => c.id === id);
  invariant(c, "关系不存在");
  const r = allowed(state, role, c);
  invariant(isOpen(r), "需求不在有效招募状态（P0暂定规则）");
  invariant(c.status === "TRIAL_PENDING" && c.trial, "没有待确认预约");
  invariant(c.trial.version === version, "预约版本已变化，请重新查看");
  invariant(c.trial.proposedBy !== actorId(role), "请由另一方确认预约");
  invariant(
    Date.parse(`${c.trial.date}T${c.trial.time}:00+08:00`) > Date.now(),
    "预约时间已过，请重新协商",
  );
  c.trial.confirmedBy.push(actorId(role));
  c.status = "TRIAL_CONFIRMED";
  record(state, actorId(role), "确认预约", id, "TRIAL_PENDING", c.status);
}
export function updateTutor(
  state: State,
  role: Role,
  intro: string,
  price: number,
) {
  invariant(role === "tutor", "仅老师本人可编辑");
  invariant(
    intro.trim() && price > 0 && Number.isFinite(price),
    "请填写简介及有效报价",
  );
  const t = state.tutors.find((t) => t.id === tutorId)!;
  t.intro = intro.trim();
  t.price = price;
  t.version++;
  record(
    state,
    tutorId,
    "更新简历",
    tutorId,
    `${t.version - 1}`,
    `${t.version}`,
  );
}
