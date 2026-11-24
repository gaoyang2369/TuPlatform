import { expect, test } from "@playwright/test";
import path from "node:path";
import { cleanActors, login, write } from "./browser-accounts";
import { sampleResume } from "./resume-fixture";

test.afterEach(cleanActors);

test("老师提交和重提私有证件，运营核验，家长查看双徽章", async ({ page, browser, request }, info) => {
  test.setTimeout(120000);
  const { baseURL, viewport, isMobile, hasTouch } = info.project.use;
  const parentContext = await browser.newContext({ baseURL, viewport, isMobile, hasTouch });
  const operatorContext = await browser.newContext({ baseURL, viewport, isMobile, hasTouch });
  try {
    const tutor = await login(page.request, "TUTOR");
    await login(parentContext.request, "REQUESTER");
    await login(operatorContext.request, "OPERATOR");
    const saved = await write(page.request, "/me/teacher-profile", { ...sampleResume, expectedVersion: 0,
      displayName: "合成核验老师", categoryCodes: ["DRAWING"], serviceModes: ["OFFLINE"], areaCodes: ["PS_PINGSHAN"],
      teachGrades: ["初一", "初二"], teachSubjects: ["数学", "绘画"],
      introduction: "水彩入门", priceMin: null, priceMax: null, availabilityText: "周末" }, tutor, "PUT");
    await write(page.request, "/me/teacher-profile/publish", {expectedVersion: saved.version}, tutor);

    const parent = await parentContext.newPage(), operator = await operatorContext.newPage();
    await parent.goto("/?view=teachers");
    const card = parent.locator(".teacher-card").filter({hasText: "合成核验老师"});
    await expect(card.getByText("邮箱已验证")).toBeVisible();
    await card.getByRole("button", {name:"查看老师"}).click();
    await expect(parent.getByRole("dialog")).toContainText("该老师已通过邮箱验证，学籍信息核验中；具体授课安排由双方协商确认。");
    await parent.getByRole("button", {name:"返回老师列表"}).click();

    await page.goto("/?view=profile&section=verifications");
    await expect(page.getByRole("heading",{name:"本校学籍与资质认证"})).toBeFocused();
    const mine = page.getByRole("region", {name:"本校学籍与资质认证"});
    const school = mine.getByRole("article").filter({has: page.getByRole("heading", {name:"本校学籍", exact:true})});
    await expect(school).toContainText("未提交");
    const upload = async (label: string) => {
      await mine.getByLabel("选择" + label + "材料").setInputFiles(path.join(import.meta.dirname, "fixtures/resume.pdf"));
      await mine.getByRole("button", {name:"提交" + label + "认证"}).click();
    };
    await upload("本校学籍");
    await expect(school).toContainText("审核中");
    const proof = (await school.getByRole("link").getAttribute("href"))!;
    expect((await page.request.get(proof)).status()).toBe(200);
    expect((await parent.request.get(proof)).status()).toBe(404);
    expect((await request.get(proof)).status()).toBe(401);
    const submissionId = proof.split("/").at(-2);
    const staticRead = await request.get("/private_uploads/verifications/" + submissionId + ".pdf");
    // Some development servers serve the SPA shell for unknown paths.
    expect((await staticRead.body()).subarray(0, 5).toString()).not.toBe("%PDF-");
    expect((await parent.request.get("/api/admin/verifications")).status()).toBe(403);
    expect((await operator.request.post("/api/admin/verifications/" + submissionId + "/review", {
      data: { status: "approved" },
    })).status()).toBe(403);

    await operator.goto("/?view=review");
    await operator.getByRole("button", {name:"老师认证审核"}).click();
    const queue = operator.getByRole("region", {name:"老师认证审核"});
    const submission = (label: string) => queue.getByRole("article").filter({hasText:tutor})
      .filter({has:operator.getByRole("heading", {name:label, exact:true})});
    const row = submission("本校学籍");
    await expect(row).toBeVisible();
    await row.getByRole("button", {name:"查看证件"}).click();
    const proofDialog = operator.getByRole("dialog", {name:"查看证件"});
    await expect(proofDialog.locator("object, img")).toBeVisible();
    await proofDialog.getByRole("button", {name:"关闭"}).click();
    const document = await operator.request.get(proof);
    expect(document.status()).toBe(200);
    expect(document.headers()["cache-control"]).toContain("no-store");
    await row.getByLabel("驳回原因", {exact:false}).fill("请保留院校名称");
    await row.getByRole("button", {name:"驳回申请"}).click();
    await expect(queue.getByRole("status")).toContainText("已驳回");
    await mine.getByRole("button", {name:"刷新认证状态"}).click();
    await expect(school).toContainText("请保留院校名称");
    await upload("本校学籍");
    await expect(school).toContainText("审核中");
    await upload("技能证书");
    await expect(mine.getByText("审核中", {exact:true})).toHaveCount(2);
    await queue.getByRole("button", {name:"刷新待核验申请"}).click();
    for (const label of ["本校学籍","技能证书"]) {
      await submission(label).getByRole("button", {name:"确认核验通过"}).click();
      await expect(submission(label)).toHaveCount(0);
    }
    await mine.getByRole("button", {name:"刷新认证状态"}).click();
    await expect(mine.getByText("已核验", {exact:true})).toHaveCount(2);
    const approvedProof = (await school.getByRole("link").getAttribute("href"))!;
    await queue.getByRole("button", {name:"已核验", exact:true}).click();
    await submission("本校学籍").getByRole("button", {name:"删除已核验证件原图"}).click();
    await operator.getByRole("button", {name:"确认删除原图"}).click();
    await expect(submission("本校学籍")).toContainText("原件已停止提供");
    expect((await page.request.get(approvedProof)).status()).toBe(404);
    await mine.getByRole("button", {name:"刷新认证状态"}).click();
    await expect(school.getByRole("link")).toHaveCount(0);
    await parent.reload();
    await expect(card).toContainText("本校学籍已核验");
    await expect(card).toContainText("技能证书已核验");
    await card.getByRole("button", {name:"查看老师"}).click();
    const drawer = parent.getByRole("dialog");
    await expect(drawer).toContainText("本校学籍已核验");
    await expect(drawer).toContainText("技能证书已核验");
    await expect(drawer).toContainText("该老师已完成本校在读学籍核验；具体授课安排与费用由双方协商确认。");
    await expect(drawer.getByRole("link", {name: /证件|材料/})).toHaveCount(0);
    expect(await parent.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true);
    expect((await parent.request.get(proof)).status()).toBe(404);
    await parent.getByRole("button", {name:"返回老师列表"}).click();
    const study = parent.getByRole("button", {name:"年级与科目"});
    await study.click();
    await parent.getByLabel("年级", {exact:true}).fill("初二");
    await parent.getByLabel("科目", {exact:true}).fill("数学");
    await parent.keyboard.press("Escape");
    await parent.getByRole("button", {name:"坪山街道"}).click();
    await parent.getByRole("checkbox", {name:"坪山街道"}).check();
    await parent.keyboard.press("Escape");
    await parent.getByRole("button", {name:"支持试课"}).click();
    const verified = parent.getByRole("button", {name:"仅看已核验"});
    await verified.click();
    await expect(parent).toHaveURL(url => url.searchParams.get("grade") === "初二"
      && url.searchParams.get("subject") === "数学" && url.searchParams.get("area") === "PS_PINGSHAN"
      && url.searchParams.get("trial_only") === "true" && url.searchParams.get("verified_only") === "true");
    await parent.reload();
    await expect(card).toBeVisible();
    await expect(verified).toHaveAttribute("aria-pressed", "true");

    await study.click();
    await parent.getByLabel("科目", {exact:true}).fill("合成无匹配科目");
    await parent.keyboard.press("Escape");
    await expect(parent.getByText("当前条件下暂未找到完全匹配的老师")).toBeVisible();
    const emptyUrl = parent.url();
    await parent.getByRole("button", {name:"清除筛选条件"}).click();
    await expect(card).toBeVisible();
    await expect(parent.getByRole("button", {name:"重置筛选"})).toHaveCount(0);
    await parent.goto(emptyUrl);
    await parent.getByRole("button", {name:"发布我的需求，让本校老师主动联系你"}).click();
    await expect(parent.getByRole("heading", {name:"填写需求草稿"})).toBeVisible();
    await parent.getByRole("button", {name:"返回我的需求"}).click();
    await expect(parent.getByText(/暂时没有已保存的需求/)).toBeVisible();
    await parent.goBack();
    await expect(parent).toHaveURL(emptyUrl);
    await expect(parent.getByText("当前条件下暂未找到完全匹配的老师")).toBeVisible();
  } finally {
    await parentContext.close();
    await operatorContext.close();
  }
});
