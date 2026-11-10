import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import { execFileSync } from "node:child_process";
import { readdirSync } from "node:fs";
import path from "node:path";

type User = { id: string; account: string; password: string; token: string };
const root = path.resolve(import.meta.dirname, "../..");
const driverRoot = path.join(root, ".tools/maven-repository/org/postgresql/postgresql");
const driverVersion = readdirSync(driverRoot).filter(name => /^\d/.test(name)).sort().at(-1)!;
const driver = path.join(driverRoot, driverVersion, `postgresql-${driverVersion}.jar`);
const fixtures: User[] = [];
function fixture(action: string, argument: string) {
  return JSON.parse(execFileSync("java", ["-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", "--class-path", driver,
    path.join(root, "backend/src/test/tools/BrowserFixture.java"), action, argument], { encoding: "utf8", env: process.env }));
}
function user(role: string): User { const value = fixture("create", role); fixtures.push(value); return value; }
async function login(page: Page, actor: User) {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "登录邻课" })).toBeVisible();
  await page.getByLabel("账号", { exact: true }).fill(actor.account);
  await page.getByLabel("密码", { exact: true }).fill(actor.password);
  await page.getByRole("button", { name: "登录", exact: true }).click();
}
async function expectNoHorizontalOverflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
}
test.beforeAll(() => {
  if (!process.env.TEST_DB_URL?.match(/\/[A-Za-z0-9_]+_test(?:\?.*)?$/)) throw new Error("Explicit dedicated TEST_DB_URL is required");
});
test.afterEach(() => { for (const actor of fixtures.splice(0)) fixture("cleanup", actor.id); });

test("匿名入口和主动成年声明", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "登录邻课" })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.getByRole("button", { name: "注册新账号 →" }).click();
  await expect(page.getByRole("button", { name: "注册并验证邮箱" })).toBeDisabled();
  await expect(page.getByLabel("我确认本人已年满18岁。")).not.toBeChecked();
  await expectNoHorizontalOverflow(page);
});

test("验证链接只检查，跨浏览器核对密码后登录、确认身份并保存资料", async ({ page }) => {
  const actor = user("PENDING");
  await page.goto("/#verify=" + actor.token);
  await expect(page.getByRole("heading", { name: "确认验证邮箱" })).toBeVisible();
  await expect(page.getByLabel("注册密码", { exact: true })).toBeVisible();
  expect(page.url()).not.toContain(actor.token);
  const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
  const inspect = await page.request.post("/api/v1/auth/email-verification/inspect", { headers: { [csrf.headerName]: csrf.token }, data: { token: actor.token } });
  expect((await inspect.json()).valid).toBe(true);
  await page.getByLabel("注册密码", { exact: true }).fill(actor.password);
  await page.getByRole("button", { name: "确认验证", exact: true }).click();
  await expect(page.getByRole("heading", { name: "登录邻课" })).toBeVisible();
  await login(page, actor);
  await expect(page.getByRole("heading", { name: "你希望如何参与？" })).toBeVisible();
  await page.getByRole("radio", { name: /我是需求方/ }).check();
  await page.getByRole("checkbox", { name: /我已理解上述职责/ }).check();
  await page.getByRole("button", { name: "确认身份并继续" }).click();
  await expect(page.getByRole("heading", { name: "本人基本资料", exact: true })).toBeVisible();
  await page.locator("#field-displayName").fill("合成需求方");
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("资料已保存。");
  await page.reload();
  await expect(page.locator("#field-displayName")).toHaveValue("合成需求方");
  await expectNoHorizontalOverflow(page);
});

test("老师保存、主动公开、冲突留输入、网络失败恢复及停止公开", async ({ page, browser }, testInfo) => {
  const actor = user("TUTOR");
  await login(page, actor);
  await expect(page.getByRole("heading", { name: "让合适的人认识你" })).toBeVisible();
  await page.locator("#field-displayName").fill("合成绘画老师");
  await page.getByRole("checkbox", { name: "绘画", exact: true }).check();
  await page.getByRole("checkbox", { name: "线下", exact: true }).check();
  await page.getByRole("checkbox", { name: "坪山街道", exact: true }).check();
  await page.locator("#field-introduction").fill("合成资料：面向成年人的绘画技能分享。");
  await page.getByRole("button", { name: "保存资料", exact: true }).click();
  await expect(page.getByRole("status")).toHaveText("资料已保存。");
  await expect(page.getByText("仅本人可见", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "预览并公开" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("dialog").getByText("面议", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "确认公开资料" }).click();
  await expect(page.getByText("资料已公开", { exact: true })).toBeVisible();
  await expectNoHorizontalOverflow(page);
  await page.screenshot({ path: path.join(root, `.tools/stage1-browser/teacher-${testInfo.project.name}.png`), fullPage: true });
  const other = await browser.newContext({ baseURL: testInfo.project.use.baseURL as string || "http://127.0.0.1:5174" });
  try {
    const second = await other.newPage(); await login(second, actor);
    await expect(second.locator("#field-displayName")).toHaveValue("合成绘画老师");
    await second.locator("#field-displayName").fill("合成老师最新资料");
    await second.getByRole("button", { name: "保存并更新公开资料" }).click();
    await expect(second.getByRole("status")).toHaveText("资料已保存并更新公开展示。");
    await page.locator("#field-displayName").fill("当前页面保留输入");
    await page.getByRole("button", { name: "保存并更新公开资料" }).click();
    await expect(page.getByRole("alert")).toContainText("资料已在其他页面修改");
    await expect(page.locator("#field-displayName")).toHaveValue("当前页面保留输入");
    await page.getByRole("button", { name: "读取最新保存内容" }).click();
    await page.getByRole("button", { name: "已核对，保留输入继续编辑" }).click();
  } finally { await other.close(); }
  await page.route("**/api/v1/me/teacher-profile", route => route.abort("failed"), { times: 1 });
  await page.getByRole("button", { name: "保存并更新公开资料" }).click();
  await expect(page.getByRole("alert")).toContainText("暂时无法连接服务");
  await expect(page.locator("#field-displayName")).toHaveValue("当前页面保留输入");
  await page.getByRole("button", { name: "保存并更新公开资料" }).click();
  await expect(page.getByRole("status")).toHaveText("资料已保存并更新公开展示。");
  await page.getByRole("button", { name: "停止公开", exact: true }).click();
  await page.getByRole("button", { name: "继续公开", exact: true }).click();
  await expect(page.getByText("资料已公开", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "停止公开", exact: true }).click();
  await page.getByRole("button", { name: "确认停止公开" }).click();
  await expect(page.getByText("仅本人可见", { exact: true })).toBeVisible();
});

test("重置密码撤销旧浏览器会话，退出后不显示上一账号资料", async ({ page, browser }) => {
  const actor = user("REQUESTER");
  await login(page, actor);
  await expect(page.locator("#field-displayName")).toBeVisible();
  const reset = fixture("reset", actor.id);
  const other = await browser.newContext({ baseURL: "http://127.0.0.1:5174" });
  const newPassword = "New browser password 123456";
  try {
    const resetPage = await other.newPage(); await resetPage.goto("/#reset=" + reset.token);
    await resetPage.locator("#field-password").fill(newPassword);
    await resetPage.getByLabel("确认新密码", { exact: true }).fill(newPassword);
    await resetPage.getByRole("button", { name: "确认重置密码" }).click();
    await expect(resetPage.getByRole("heading", { name: "登录邻课" })).toBeVisible();
    await page.reload();
    await expect(page.getByRole("heading", { name: "登录邻课" })).toBeVisible();
    await expect(page.locator("#field-displayName")).toHaveCount(0);
    await login(page, { ...actor, password: newPassword });
    await expect(page.locator("#field-displayName")).toBeVisible();
    await page.getByRole("button", { name: "退出登录", exact: true }).click();
    await expect(page.getByRole("heading", { name: "登录邻课" })).toBeVisible();
    await expect(page.locator("#field-displayName")).toHaveCount(0);
  } finally { await other.close(); }
});
