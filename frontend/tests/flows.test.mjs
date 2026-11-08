import test from "node:test";
import assert from "node:assert/strict";
import {
  actorId,
  addMessage,
  confirmTrial,
  connect,
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
} from "../src/domain.ts";

const trial = () => ({
  date: new Date(Date.now() + 3 * 86400000).toISOString().slice(0, 10),
  time: "14:00",
  duration: 60,
  fee: 100,
  unit: "每次",
  place: "模拟公共场所",
  cancellation: "提前一天协商",
});

test("AC-P0-01：草稿→提交→运营审核→公开→投递→家长互选", () => {
  const s = seed();
  const draft = newDraft();
  Object.assign(draft, {
    title: "模拟新需求",
    goal: "一次体验",
    slots: "周六下午",
  });
  const id = saveDraft(s, "parent", draft);
  assert.equal(
    publicRequests(s).some((r) => r.id === id),
    false,
  );
  requestAction(s, "parent", id, "submit");
  assert.equal(
    publicRequests(s).some((r) => r.id === id),
    false,
  );
  requestAction(s, "admin", id, "approve");
  assert.equal(
    publicRequests(s).some((r) => r.id === id),
    true,
  );
  const match = connect(s, "tutor", id, tutorId, "application");
  assert.equal(s.connections[0].parentInterested, false);
  matchAction(s, "parent", match, "accept");
  assert.equal(s.connections[0].status, "MUTUAL_INTEREST");
  assert.ok(s.audit.some((a) => a.action === "approve"));
});

test("AC-P0-02：邀请→老师互选→预约提议→另一方同版本确认", () => {
  const s = seed();
  const id = connect(s, "parent", "request-open", tutorId, "invitation");
  assert.equal(s.connections[0].tutorSnapshot, null);
  assert.throws(() => matchAction(s, "parent", id, "accept"), /对方回应/);
  matchAction(s, "tutor", id, "accept");
  assert.equal(s.connections[0].tutorSnapshot.version, 1);
  proposeTrial(s, "parent", id, trial());
  assert.throws(() => confirmTrial(s, "parent", id, 1), /另一方/);
  assert.throws(() => confirmTrial(s, "tutor", id, 2), /版本/);
  confirmTrial(s, "tutor", id, 1);
  assert.equal(s.connections[0].status, "TRIAL_CONFIRMED");
  assert.deepEqual(s.connections[0].trial.confirmedBy, [
    actorId("parent"),
    actorId("tutor"),
  ]);
});

test("AC-P0-03：无效表单不允许提交，草稿允许不完整", () => {
  const s = seed();
  const d = newDraft();
  assert.ok(validateRequest(d).length >= 3);
  const id = saveDraft(s, "parent", d);
  assert.throws(() => requestAction(s, "parent", id, "submit"), /标题/);
  assert.equal(s.requests.find((r) => r.id === id).status, "DRAFT");
});

test("AC-P0-04：驳回原因必填，修订产生新版本并重新审核", () => {
  const s = seed();
  assert.throws(
    () => requestAction(s, "admin", "request-review", "reject"),
    /驳回原因/,
  );
  requestAction(s, "admin", "request-review", "reject", "请补充目标");
  const d = {
    ...s.requests.find((r) => r.id === "request-review"),
    goal: "补充的模拟目标",
  };
  saveDraft(s, "parent", d);
  requestAction(s, "parent", d.id, "submit");
  assert.equal(s.requests.find((r) => r.id === d.id).version, 2);
  assert.equal(
    publicRequests(s).some((r) => r.id === d.id),
    false,
  );
});

test("AC-P0-05：去重且简历、需求快照不跟随编辑变化", () => {
  const s = seed();
  const id = connect(s, "tutor", "request-open", tutorId, "application");
  assert.equal(connect(s, "tutor", "request-open", tutorId, "application"), id);
  assert.equal(connect(s, "parent", "request-open", tutorId, "invitation"), id);
  updateTutor(s, "tutor", "新的当前简介", 150);
  s.requests.find((r) => r.id === "request-open").goal = "新的当前需求";
  assert.equal(s.connections.length, 1);
  assert.equal(s.connections[0].tutorSnapshot.version, 1);
  assert.notEqual(s.connections[0].tutorSnapshot.intro, "新的当前简介");
  assert.notEqual(s.connections[0].requestSnapshot.goal, "新的当前需求");
});

test("AC-P0-06：到期判断不依赖状态字段；暂停、关闭不可新增关系", () => {
  for (const status of ["OPEN", "PAUSED", "CLOSED"]) {
    const s = seed();
    const r = s.requests.find((r) => r.id === "request-open");
    r.status = status;
    if (status === "OPEN")
      r.expiresAt = new Date(Date.now() - 1000).toISOString();
    assert.equal(
      publicRequests(s).some((item) => item.id === r.id),
      false,
    );
    assert.throws(
      () => connect(s, "tutor", r.id, tutorId, "application"),
      /仅招募中/,
    );
  }
});

test("AC-P0-07：模拟对象归属与角色守卫拒绝错误动作", () => {
  const s = seed();
  assert.throws(
    () => requestAction(s, "parent", "request-review", "approve"),
    /只有运营/,
  );
  assert.throws(
    () => requestAction(s, "parent", "request-other", "pause"),
    /持有人/,
  );
  assert.throws(
    () => connect(s, "parent", "request-other", tutorId, "invitation"),
    /无权/,
  );
  const id = connect(s, "parent", "request-open", "tutor-chen", "invitation");
  assert.throws(() => addMessage(s, "tutor", id, "不能冒充陈老师"), /双方/);
  assert.throws(
    () => addMessage(s, "admin", id, "运营不能代替用户留言"),
    /双方/,
  );
});

test("AC-P0-08：联系方式双方分别同意，任一方可撤回", () => {
  const s = seed();
  const id = connect(s, "tutor", "request-open", tutorId, "application");
  assert.throws(
    () => matchAction(s, "parent", id, "contact"),
    /先完成双方互选/,
  );
  matchAction(s, "parent", id, "accept");
  matchAction(s, "parent", id, "contact");
  assert.equal(s.connections[0].contactConsents.length, 1);
  matchAction(s, "tutor", id, "contact");
  assert.equal(s.connections[0].contactConsents.length, 2);
  matchAction(s, "parent", id, "revoke");
  assert.deepEqual(s.connections[0].contactConsents, [tutorId]);
});

test("AC-P0-09：撤回后暂不重投，预约前重新校验当前需求", () => {
  const s = seed();
  const id = connect(s, "tutor", "request-open", tutorId, "application");
  matchAction(s, "tutor", id, "withdraw");
  assert.throws(
    () => connect(s, "tutor", "request-open", tutorId, "application"),
    /重投规则待确认/,
  );
  const s2 = seed();
  const id2 = connect(s2, "tutor", "request-open", tutorId, "application");
  matchAction(s2, "parent", id2, "accept");
  requestAction(s2, "parent", "request-open", "pause");
  assert.throws(() => proposeTrial(s2, "parent", id2, trial()), /暂定规则/);
});

test("AC-P0-10：过期预约不能被事后确认", () => {
  const s = seed();
  const id = connect(s, "parent", "request-open", tutorId, "invitation");
  matchAction(s, "tutor", id, "accept");
  proposeTrial(s, "parent", id, trial());
  s.connections[0].trial.date = "2020-01-01";
  assert.throws(() => confirmTrial(s, "tutor", id, 1), /时间已过/);
  assert.equal(s.connections[0].status, "TRIAL_PENDING");
});
