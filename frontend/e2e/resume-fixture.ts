import type { Page } from "@playwright/test";

export const sampleResume = {
  teachGrades: ["成人"], teachSubjects: ["绘画"], collegeMajor: "设计学院", gradeLevel: "大三",
  casesJson: [{ student_grade: "成人", focus: "水彩调色练习", outcome: "能独立调出所需色彩" }],
  trialPolicy: "支持首次试课半价",
};

export async function fillResume(page: Page) {
  await page.getByLabel("学院与专业").fill(sampleResume.collegeMajor);
  await page.getByLabel("在读年级").fill(sampleResume.gradeLevel);
  await page.locator("#field-teachGrades").fill("成人");
  await page.locator("#field-teachSubjects").fill("绘画");
  await page.getByLabel("学生年级").fill("成人");
  await page.getByLabel("辅导内容").fill(sampleResume.casesJson[0]!.focus);
  await page.getByLabel("阶段变化").fill(sampleResume.casesJson[0]!.outcome);
  await page.getByLabel(sampleResume.trialPolicy).check();
}
