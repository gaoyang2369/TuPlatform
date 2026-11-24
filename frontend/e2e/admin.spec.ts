import { expect, test } from "@playwright/test";
import { cleanActors, login } from "./browser-accounts";

test.afterEach(cleanActors);

test("普通账号与访客访问后台返回首页，所有管理接口拒绝普通账号", async ({ page, request }) => {
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("alert")).toContainText("无权访问管理后台");
  const id = await login(page.request, "REQUESTER");
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByRole("alert")).toContainText("无权访问管理后台");
  await expect(page.getByRole("link", {name:"管理后台"})).toHaveCount(0);
  const csrf = await (await page.request.get("/api/v1/auth/csrf")).json();
  const endpoints = [
    ["GET", "/demands"], ["GET", "/demands/" + id], ["POST", "/demands/" + id + "/review"],
    ["GET", "/verifications"], ["POST", "/verifications/" + id + "/review"],
    ["GET", "/verifications/" + id + "/proof"], ["DELETE", "/verifications/" + id + "/proof"],
    ["GET", "/users"], ["GET", "/users/" + id + "/reviews"], ["POST", "/reviews/" + id + "/hide"],
    ...["freeze", "unfreeze", "reset-avatar"].map(action => ["POST", "/users/" + id + "/" + action]),
  ];
  for (const prefix of ["/api/admin", "/api/v1/admin"]) {
    expect((await request.get(prefix + "/users")).status()).toBe(403);
    for (const [method, path] of endpoints) {
      const response = await page.request.fetch(prefix + path, { method, headers: {
        [csrf.headerName]: csrf.token, "X-Account-Id": id
      } });
      expect(response.status(), path).toBe(403);
      expect((await response.json()).code).toBe("ADMIN_REQUIRED");
    }
  }
});

test("用户菜单进入后台，按邮箱查找用户并冻结和解冻", async ({ page, request }) => {
  const userId = await login(request, "REQUESTER");
  const account = await (await request.get("/api/v1/me")).json();
  const csrf = await (await request.get("/api/v1/auth/csrf")).json();
  const bitmap = await page.evaluate(() => {
    const canvas = document.createElement("canvas");
    canvas.width = canvas.height = 32;
    return canvas.toDataURL().split(",")[1]!;
  });
  const upload = await request.post("/api/v1/upload/avatar", {headers:{[csrf.headerName]:csrf.token,"X-Account-Id":userId},
    multipart:{file:{name:"test.png",mimeType:"image/png",buffer:Buffer.from(bitmap,"base64")}}});
  expect(upload.ok()).toBe(true);
  await login(page.request, "OPERATOR");
  await page.goto("/");
  await page.getByRole("button", {name:/切换账号/}).click();
  await page.getByRole("link", {name:"管理后台"}).click();
  await expect(page).toHaveURL(/\/admin$/);
  await page.getByRole("button", {name:"用户与风控"}).click();
  await page.getByLabel("姓名、账号或邮箱").fill(account.email);
  await page.getByLabel("角色", {exact:true}).selectOption("REQUESTER");
  await page.getByRole("button", {name:"搜索用户"}).click();
  const row = page.getByRole("article").filter({hasText:account.email});
  await expect(row.locator("img")).toBeVisible();
  await row.getByRole("button", {name:"重置头像"}).click();
  const reset = page.getByRole("dialog", {name:"重置头像"});
  await reset.getByLabel("操作理由").fill("合成头像违规");
  await reset.getByRole("button", {name:"确认",exact:true}).click();
  await expect(reset).toHaveCount(0);
  await expect(row.getByRole("button", {name:"重置头像"})).toBeDisabled();
  await expect(row.locator("img")).toHaveCount(0);
  expect((await (await request.get("/api/v1/me")).json()).avatar_url).toBeNull();
  await row.getByRole("button", {name:"查看已发评价"}).click();
  await expect(row).toContainText("此页没有公开评价");
  for (const label of ["冻结账号", "解冻账号"]) {
    await row.getByRole("button", {name:label}).click();
    const dialog = page.getByRole("dialog", {name:label});
    await dialog.getByLabel("操作理由").fill("合成测试复核");
    await dialog.getByRole("button", {name:"确认",exact:true}).click();
    await expect(dialog).toHaveCount(0);
    await expect(row).toContainText(label==="冻结账号" ? "已冻结" : "正常");
    expect((await request.get("/api/v1/me")).status()).toBe(401);
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
