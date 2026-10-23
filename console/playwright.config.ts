import { defineConfig, devices } from "@playwright/test";
export default defineConfig({
  testDir: "./tests",
  timeout: 60_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    ...devices["Desktop Chrome"],
    channel: process.env.PLAYWRIGHT_CHANNEL ?? "msedge",
    baseURL: "http://127.0.0.1:5173",
    viewport: { width: 1440, height: 1050 },
    screenshot: "only-on-failure",
    trace: "off",
  },
});
