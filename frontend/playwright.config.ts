import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60000,
  expect: { timeout: 10000 },
  use: { baseURL: process.env.E2E_BASE_URL || "http://127.0.0.1:5174", channel: process.env.E2E_BROWSER_CHANNEL || "chrome", headless: true, trace: "off", screenshot: "only-on-failure" },
  reporter: [["list"], ["json", { outputFile: "../.tools/stage1-browser/results.json" }]],
  projects: [
    { name: "desktop", use: { viewport: { width: 1366, height: 900 } } },
    { name: "mobile-width", use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true } },
  ],
});
