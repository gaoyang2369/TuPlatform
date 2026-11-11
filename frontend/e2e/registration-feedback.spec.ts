import { test, expect } from "@playwright/test";

// Isolated UI regression: mock registration only; never create accounts or send mail.
test("注册短密码提交与悬浮提示不挤动页面、自动消失及重复显示", async ({ page }) => {
  let submissions = 0;
  await page.route("**/api/v1/auth/register", async route => {
    submissions++;
    expect(route.request().postDataJSON().password).toBe("Abcd1234");
    await route.fulfill({ status: 201, contentType: "application/json", body: JSON.stringify({ registrationFlowId: "00000000-0000-4000-8000-000000000001", mailAccepted: true }) });
  });
  await page.goto("/?view=login");
  await page.getByRole("button", { name: "注册新账号 →" }).click();
  await expect(page.getByText("4—16位，字母开头", { exact: true })).toBeVisible();
  await expect(page.getByText("8—64个字符，可包含空格", { exact: true })).toBeVisible();
  await expect(page.getByText("账号邮箱仅用于验证与找回，不会随资料公开。")).toHaveCount(0);
  await page.locator("#field-account").fill("test_feedback");
  await page.locator("#field-email").fill("feedback@example.invalid");
  await page.locator("#field-password").fill("1234567");
  await page.getByLabel("确认密码", { exact: true }).fill("1234567");
  await page.getByRole("checkbox", { name: "我确认本人已年满18岁。" }).check();
  const card = page.locator(".auth-card");
  const before = await card.boundingBox();
  await page.getByRole("button", { name: "注册并验证邮箱" }).click();
  const alert = page.getByRole("alert");
  await expect(alert).toContainText("密码须为8—64个字符");
  expect(submissions).toBe(0);
  expect((await card.boundingBox())?.y).toBe(before?.y);
  expect(await page.locator(".feedback-toast").evaluate(el => getComputedStyle(el).position)).toBe("fixed");
  // Move away so hover does not pause the dismissal timer on mobile-width emulation.
  await page.mouse.move(0, 0);
  await expect(alert).toHaveCount(0, { timeout: 7000 });
  expect((await card.boundingBox())?.y).toBe(before?.y);
  await page.getByRole("button", { name: "注册并验证邮箱" }).click();
  await expect(alert).toBeVisible();
  await page.getByRole("button", { name: "关闭提示" }).click();
  await expect(alert).toHaveCount(0);
  await page.locator("#field-password").fill("Abcd1234");
  await page.getByLabel("确认密码", { exact: true }).fill("Abcd1234");
  await page.getByRole("button", { name: "注册并验证邮箱" }).click();
  await expect(page.getByRole("heading", { name: "请验证注册邮箱" })).toBeVisible();
  expect(submissions).toBe(1);
  await expect(page.getByRole("status")).toContainText("注册已受理");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
});
