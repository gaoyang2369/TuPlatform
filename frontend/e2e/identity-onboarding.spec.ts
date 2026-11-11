import { test, expect } from "@playwright/test";

const account = { id: "00000000-0000-4000-8000-000000000002", account: "onboarding_test", email: "onboarding@example.invalid", emailVerified: true, businessIdentity: null as string | null, operatorGranted: false, profileVersion: 0 };

// All account state is mocked; these checks never change an existing user's identity.
test("未选身份时隐藏导航，需求链接不能跳过选择，完成后恢复导航", async ({ page }) => {
  let businessIdentity: string | null = null;
  let selections = 0;
  await page.route("**/api/v1/me", route => route.fulfill({ json: { ...account, businessIdentity } }));
  await page.route("**/api/v1/me/business-identity", async route => {
    selections++;
    expect(route.request().postDataJSON()).toEqual({ identity: "REQUESTER" });
    businessIdentity = "REQUESTER";
    await route.fulfill({ json: { ...account, businessIdentity } });
  });
  await page.route("**/api/v1/me/profile", route => route.fulfill({ json: { displayName: "", version: 0, teacher: null } }));
  await page.route("**/api/v1/profile-options", route => route.fulfill({ json: { categoryCodes: [], serviceModes: [], areaCodes: [] } }));

  await page.goto("/?request=00000000-0000-4000-8000-000000000003");
  await expect(page.getByRole("heading", { name: "你希望如何参与？" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "主要任务" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "浏览需求", exact: true })).toHaveCount(0);
  await expect(page.getByText(/首版暂不支持修改/)).toHaveCount(0);
  const confirmation = page.getByRole("checkbox", { name: "我已理解上述职责。", exact: true });
  const submit = page.getByRole("button", { name: "确认身份并继续" });
  await expect(submit).toBeDisabled();
  await page.getByRole("radio", { name: /我是需求方/ }).check();
  await expect(submit).toBeDisabled();
  await confirmation.check();
  await expect(submit).toBeEnabled();
  await submit.click();
  await expect(page.getByRole("heading", { name: "本人基本资料", exact: true })).toBeVisible();
  expect(selections).toBe(1);
  await expect(page.getByRole("navigation", { name: "主要任务" })).toBeVisible();
  await expect(page.getByRole("button", { name: "发现老师", exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});

test("匿名访客保留浏览需求入口", async ({ page }) => {
  await page.route("**/api/v1/me", route => route.fulfill({ status: 401, json: { code: "LOGIN_REQUIRED", message: "请先登录" } }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "我想找老师",exact:true })).toBeVisible();
  await expect(page.getByRole("button", { name: "浏览需求", exact: true })).toBeVisible();
});

test("无业务身份的运营账号保留审核入口", async ({ page }) => {
  await page.route("**/api/v1/me", route => route.fulfill({ json: { ...account, email: "", operatorGranted: true } }));
  await page.route("**/api/v1/operations/**", route => route.fulfill({ json: [] }));
  await page.goto("/");
  await expect(page.getByRole("button", { name: "需求审核", exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "你希望如何参与？" })).toHaveCount(0);
});
