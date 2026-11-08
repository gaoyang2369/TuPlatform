import test from "node:test";
import assert from "node:assert/strict";
import { AccountApi, ApiFailure } from "../src/account-api.ts";

const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: { "Content-Type": "application/json" } });
test("写入先取CSRF，通过同源Cookie发起，不在GET消费邮件凭据", async () => {
  const calls = [];
  const client = new AccountApi(async (url, init) => {
    calls.push([url, init]);
    return url.endsWith("/csrf") ? json({ token: "csrf", headerName: "X-XSRF-TOKEN" }) : json({ valid: true });
  });
  await client.request("/auth/email-verification/inspect", "POST", { token: "mail-secret" });
  assert.equal(calls[0][0], "/api/v1/auth/csrf");
  assert.equal(calls[1][1].headers["X-XSRF-TOKEN"], "csrf");
  assert.equal(calls[1][1].credentials, "same-origin");
  assert.equal(calls[1][1].cache, "no-store");
  assert.equal(calls.some(([url]) => url.includes("mail-secret")), false);
});
test("CSRF失效只重取一次，重试保留公开操作的同一幂等键和内容", async () => {
  let tokens = 0, mutations = 0;
  const calls = [];
  const client = new AccountApi(async (url, init) => {
    if (url.endsWith("/csrf")) return json({ token: `csrf-${++tokens}`, headerName: "X-XSRF-TOKEN" });
    calls.push(init); mutations++;
    return mutations === 1 ? json({ code: "CSRF_INVALID" }, 403) : json({ version: 2 });
  });
  await client.request("/me/teacher-profile/publish", "POST", { expectedVersion: 1 }, "same-key");
  assert.equal(tokens, 2); assert.equal(mutations, 2);
  assert.equal(calls[0].body, calls[1].body);
  assert.equal(calls[0].headers["Idempotency-Key"], calls[1].headers["Idempotency-Key"]);
});
test("网络错误及409保留结构化失败，不自动重试业务写入", async () => {
  let writes = 0;
  const client = new AccountApi(async url => {
    if (url.endsWith("/csrf")) return json({ token: "csrf", headerName: "X-XSRF-TOKEN" });
    writes++; return json({ code: "VERSION_CONFLICT", message: "资料已变化", fieldErrors: {} }, 409);
  });
  await assert.rejects(client.request("/me/profile", "PATCH", { expectedVersion: 1 }), e => e instanceof ApiFailure && e.status === 409 && e.code === "VERSION_CONFLICT");
  assert.equal(writes, 1);
  await assert.rejects(new AccountApi(async () => { throw new Error("offline"); }).request("/me"), e => e.code === "NETWORK_ERROR");
});
test("已读取账号后，资料操作携带账号前置条件，阻止跨标签页串号保存", async () => {
  let accountHeader;
  const client = new AccountApi(async (url, init) => {
    if (url.endsWith("/csrf")) return json({ token: "csrf", headerName: "X-XSRF-TOKEN" });
    if (url === "/api/v1/me") return json({ id: "account-a" });
    accountHeader = init.headers["X-Account-Id"];
    return json({ version: 1 });
  });
  await client.request("/me"); await client.request("/me/profile", "PATCH", { expectedVersion: 0, displayName: "甲" });
  assert.equal(accountHeader, "account-a");
});
