import { expect, type Page } from "@playwright/test";

export async function expectMobileFit(page: Page) {
  const viewport = page.viewportSize();
  if (!viewport || viewport.width >= 768) return;

  // Mobile emulation can expand innerWidth to fit overflowing content.
  // Compare against the configured device, including portalled overlays.
  await expect.poll(() => page.evaluate(width => {
    const issues: string[] = [];
    if (document.documentElement.scrollWidth > width) issues.push("页面超宽");
    for (const dialog of document.querySelectorAll<HTMLElement>('[role="dialog"]')) {
      const box = dialog.getBoundingClientRect();
      if (box.width && (box.left < -1 || box.right > width + 1 || dialog.scrollWidth > dialog.clientWidth + 1)) {
        issues.push("弹窗超宽");
      }
    }
    for (const field of document.querySelectorAll<HTMLElement>("input, textarea, select")) {
      if (field.offsetWidth && parseFloat(getComputedStyle(field).fontSize) < 16) {
        issues.push("小字号控件 " + (field.id || field.tagName));
      }
    }
    return issues;
  }, viewport.width)).toEqual([]);
}
