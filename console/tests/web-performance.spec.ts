import { expect, test } from "@playwright/test";
import { writeFile } from "node:fs/promises";
import { login } from "./support";
import { clientBudget } from "./resource-budget";

test("30 cold navigations record authenticated LCP under the declared network", async ({
  browser,
}, testInfo) => {
  test.skip(
    process.env.RUN_WEB_PERF !== "1",
    "Opt-in production-build measurement",
  );
  test.setTimeout(15 * 60_000);
  const target = process.env.WEB_PERF_BASE_URL;
  if (!target)
    throw new Error("WEB_PERF_BASE_URL must point to a production build");
  const context = await browser.newContext({
    baseURL: target,
    viewport: { width: 1440, height: 900 },
    serviceWorkers: "block",
  });
  try {
    const page = await context.newPage();
    await login(page, "merchant-a");
    const session = await (
      await page.request.get(new URL("/api/console/session", target).href)
    ).json();
    expect(session.merchantId).toBe("merchant-a");
    clientBudget();
    await page.addInitScript(() => {
      (window as unknown as { measuredLcp: number }).measuredLcp = 0;
      new PerformanceObserver((list) => {
        for (const entry of list.getEntries()) {
          (window as unknown as { measuredLcp: number }).measuredLcp =
            entry.startTime;
        }
      }).observe({ type: "largest-contentful-paint", buffered: true });
    });
    const cdp = await context.newCDPSession(page);
    await cdp.send("Network.enable");
    await cdp.send("Network.setCacheDisabled", { cacheDisabled: true });
    await cdp.send("Network.setBypassServiceWorker", { bypass: true });
    await cdp.send("Network.emulateNetworkConditions", {
      offline: false,
      latency: 40,
      downloadThroughput: (20 * 1_000_000) / 8,
      uploadThroughput: (20 * 1_000_000) / 8,
    });
    const rounds: { path: string; lcpMillis: number; transferBytes: number }[] =
      [];
    for (const path of ["/", "/payments"]) {
      for (let round = 0; round < 30; round++) {
        clientBudget();
        await cdp.send("Network.clearBrowserCache");
        await page.goto(new URL(path, target).href);
        await expect(
          page.getByRole("heading", {
            name: path === "/" ? "业务概览" : "支付与退款",
            exact: true,
          }),
        ).toBeVisible();
        await page.waitForLoadState("networkidle");
        await page.evaluate(() => document.fonts.ready);
        const metrics = await page.evaluate(() => ({
          lcpMillis: (window as unknown as { measuredLcp: number }).measuredLcp,
          transferBytes: performance
            .getEntriesByType("resource")
            .reduce(
              (sum, entry) =>
                sum + (entry as PerformanceResourceTiming).transferSize,
              0,
            ),
        }));
        expect(metrics.lcpMillis).toBeGreaterThan(0);
        rounds.push({ path, ...metrics });
      }
    }
    const summaries = ["/", "/payments"].map((path) => {
      const values = rounds
        .filter((round) => round.path === path)
        .map((round) => round.lcpMillis)
        .sort((a, b) => a - b);
      return {
        path,
        samples: values.length,
        p75Millis: values[Math.ceil(values.length * 0.75) - 1],
      };
    });
    const report = {
      target,
      viewport: { width: 1440, height: 900 },
      network: { latencyMillis: 40, downMbps: 20 },
      browserVersion: browser.version(),
      logicalProcessorsReported: await page.evaluate(
        () => navigator.hardwareConcurrency,
      ),
      clientCpuAndMemoryBudgetVerified: true,
      resourceBudget: clientBudget(),
      merchantScopeVerified: session.merchantId === "merchant-a",
      referenceR1Certified: false,
      summaries,
      rounds,
    };
    await writeFile(
      new URL("../../.work/release/web-performance.json", import.meta.url),
      JSON.stringify(report, null, 2),
    );
    await testInfo.attach("authenticated-cold-navigation.json", {
      body: JSON.stringify(report, null, 2),
      contentType: "application/json",
    });
    for (const summary of summaries)
      expect(summary.p75Millis).toBeLessThanOrEqual(2500);
  } finally {
    await context.close();
  }
});
