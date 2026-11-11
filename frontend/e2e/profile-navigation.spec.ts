import { test, expect } from "@playwright/test";
import path from "node:path";

for (const identity of ["REQUESTER", "TUTOR"] as const) {
  test(`${identity} 保存失败保留输入，未公开老师保存后留在资料，需求方进入发现`, async ({ page }, testInfo) => {
    const tutor = identity === "TUTOR";
    const target = tutor ? "浏览需求" : "发现老师";
    const profilePath = tutor ? "/me/teacher-profile" : "/me/profile";
    const teacher = tutor ? { categoryCodes: [], serviceModes: [], areaCodes: [], introduction: "", priceMin: null, priceMax: null, availabilityText: "", public: false, version: 0 } : null;
    let saved = { displayName: "原展示名", version: 0, teacher };
    let failSave = true;
    let published = 0;
    await page.route("**/api/v1/**", async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname.replace("/api/v1", "");
      if (endpoint.endsWith("/publish")) published++;
      if (endpoint === "/me") return route.fulfill({ json: { id: "00000000-0000-4000-8000-000000000004", account: "profile_test", email: "profile@example.invalid", emailVerified: true, businessIdentity: identity, operatorGranted: false, profileVersion: saved.version } });
      if (endpoint === profilePath && request.method() !== "GET") {
        if (failSave) return route.fulfill({ status: 503, json: { code: "SAVE_FAILED", message: "暂时无法保存，请重试。" } });
        const body = request.postDataJSON();
        expect(body.expectedVersion).toBe(0);
        saved = { ...saved, displayName: body.displayName, version: 1 };
        return route.fulfill({ json: saved });
      }
      if (endpoint === "/me/profile") return route.fulfill({ json: saved });
      if (endpoint === "/profile-options") return route.fulfill({ json: { categoryCodes: [], serviceModes: [], areaCodes: [] } });
      if (endpoint === "/publishing-options") return route.fulfill({ json: { categories: [], streets: [], serviceModes: [] } });
      if (endpoint === "/teachers" || endpoint === "/public/requests") return route.fulfill({ json: { items: [], nextCursor: null } });
      if (endpoint === "/auth/csrf") return route.fulfill({ json: { token: "mock-csrf", headerName: "X-XSRF-TOKEN" } });
      return route.fulfill({ status: 404, json: { message: "Unexpected test endpoint" } });
    });
    await page.goto("/?view=profile");
    const navigation = page.getByRole("navigation", { name: "主要任务" });
    await expect(page.getByRole("banner").getByRole("button", { name: "本人资料", exact: true })).toHaveAttribute("aria-current", "page");
    await page.locator("#field-displayName").fill("修改后的展示名");
    await page.getByRole("button", { name: "保存资料", exact: true }).click();
    await expect(page.getByRole("alert")).toContainText("暂时无法保存");
    await expect(page.locator("#field-displayName")).toHaveValue("修改后的展示名");
    await expect(page.getByRole("banner").getByRole("button", { name: "本人资料", exact: true })).toHaveAttribute("aria-current", "page");
    failSave = false;
    await page.getByRole("button", { name: "保存资料", exact: true }).click();
    if(tutor) { await expect(page.getByRole("button",{name:"预览并公开",exact:true})).toBeEnabled(); } else {
      await expect(page.getByRole("heading", { name: target, exact: true })).toBeVisible();
      await expect(navigation.getByRole("button", { name: target, exact: true })).toHaveAttribute("aria-current", "page");
    }
    await expect(page.locator(".feedback-toast").getByRole("status")).toContainText("资料已保存");
    expect(published).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    const button = navigation.getByRole("button", { name: target, exact: true });
    expect(await button.evaluate(el => parseFloat(getComputedStyle(el).fontSize))).toBeGreaterThanOrEqual(14);
    expect((await button.boundingBox())!.height).toBeGreaterThanOrEqual(44);
    await page.screenshot({ path: path.resolve(import.meta.dirname, `../../.tools/profile-navigation/${identity}-${testInfo.project.name}.png`), fullPage: true });
    await page.getByRole("banner").getByRole("button", { name: "本人资料", exact: true }).click();
    await expect(page.locator("#field-displayName")).toHaveValue("修改后的展示名");
    if (tutor) await expect(page.getByRole("button", { name: "预览并公开", exact: true })).toBeEnabled();
  });
}
