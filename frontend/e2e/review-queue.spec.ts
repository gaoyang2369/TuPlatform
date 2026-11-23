import { expect, test } from "@playwright/test";

test("审核筛选即时生效，二次确认只关闭顶层并保留审核意见", async ({ page }) => {
  const submission = {
    id: "submission", requestId: "request", status: "PENDING_REVIEW", version: 0,
    fields: { title: "周末素描练习", goal: "想从静物构图开始学习。", categoryCode: "DRAWING", audienceStageCode: "ADULT" },
    submittedAt: "2026-11-20T09:00:00Z", expiresAt: "2026-12-04T16:00:00Z",
    publisherDisplayName: "需求方示例", publisherDeclaration: "SELF_ADULT",
  };
  let decisions = 0;
  let release!: () => void;
  const hold = new Promise<void>(resolve => { release = resolve; });
  let fail = true;
  await page.route("**/api/v1/**", async route => {
    const url = new URL(route.request().url());
    const path = url.pathname.slice("/api/v1".length);
    if (path === "/me") return route.fulfill({ json: { id: "operator", account: "review_demo", businessIdentity: null, operatorGranted: true } });
    if (path === "/operations/submissions") {
      await hold;
      if (fail) { fail = false; return route.fulfill({ status: 503, json: { message: "暂时无法读取审核队列" } }); }
      const items = url.searchParams.get("status") === submission.status ? [submission] : [];
      return route.fulfill({ json: { items, nextCursor: null } });
    }
    if (path === "/operations/submissions/submission") return route.fulfill({ json: submission });
    if (path.endsWith("/decision")) decisions++;
    return route.fulfill({ status: 404, json: { message: "没有对应的测试数据" } });
  });
  await page.goto("/?view=review");
  await expect(page.getByText("正在读取审核队列…")).toBeVisible();
  release();
  await expect(page.getByRole("alert")).toContainText("暂时无法读取审核队列");
  await page.getByRole("button", { name: "读取审核队列" }).click();
  const filters = page.getByRole("group", { name: "审核状态" });
  await filters.getByRole("button", { name: "已通过" }).click();
  await expect(page.getByText("当前没有符合条件的提交。")).toBeVisible();
  await filters.getByRole("button", { name: "待审核" }).click();
  const trigger = page.getByRole("button", { name: "核对提交", exact: true });
  await trigger.click();
  const sheet = page.getByRole("dialog", { name: "核对提交", exact: true });
  await sheet.getByLabel("驳回并说明原因").check();
  await sheet.getByLabel("驳回理由").fill("请补充学习安排。");
  const preview = sheet.getByRole("button", { name: "核对并确认审核" });
  await preview.click();
  const confirm = page.getByRole("dialog", { name: "确认驳回？" });
  await expect(confirm).toContainText("请补充学习安排。");
  await expect(confirm.getByRole("button", { name: "返回核对" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(confirm).toHaveCount(0);
  await expect(preview).toBeFocused();
  await expect(sheet.getByLabel("驳回理由")).toHaveValue("请补充学习安排。");
  page.once("dialog", prompt => prompt.accept());
  await sheet.getByRole("button", { name: "返回审核队列" }).click();
  await expect(trigger).toBeFocused();
  expect(decisions).toBe(0);
});
