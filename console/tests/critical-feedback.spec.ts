import { expect, test, type Page, type Locator } from "@playwright/test";
import { writeFile } from "node:fs/promises";
import { login } from "./support";
import { clientBudget } from "./resource-budget";

async function armPaint(
  page: Page,
  key: string,
  selector: string,
  text: string,
) {
  await page.evaluate(
    ({ key, selector, text }) => {
      const started = performance.now();
      const marks = window as unknown as Record<string, unknown>;
      marks[key] = null;
      const observer = new MutationObserver(() => {
        if (
          [...document.querySelectorAll(selector)].some(
            (node) =>
              node.getClientRects().length && node.textContent?.includes(text),
          )
        ) {
          observer.disconnect();
          requestAnimationFrame(() =>
            requestAnimationFrame(() => {
              marks[key] = performance.now() - started;
            }),
          );
        }
      });
      observer.observe(document.body, {
        subtree: true,
        childList: true,
        characterData: true,
        attributes: true,
      });
      setTimeout(() => observer.disconnect(), 2000);
    },
    { key, selector, text },
  );
}

async function painted(page: Page, key: string) {
  await expect
    .poll(() =>
      page.evaluate(
        (key) => (window as unknown as Record<string, unknown>)[key],
        key,
      ),
    )
    .toEqual(expect.any(Number));
  return page.evaluate(
    (key) => (window as unknown as Record<string, number>)[key],
    key,
  );
}

test("critical controls paint pending, validation, error and unknown feedback within 200ms", async ({
  browser,
}, info) => {
  test.skip(
    process.env.RUN_CRITICAL_FEEDBACK !== "1",
    "Opt-in constrained client measurement",
  );
  test.setTimeout(180_000);
  const context = await browser.newContext({
    baseURL: process.env.WEB_PERF_BASE_URL,
    serviceWorkers: "block",
  });
  const samples: { action: string; millis: number }[] = [];
  const payment = {
    id: "feedback-payment",
    merchantId: "merchant-a",
    merchantOrderNo: "feedback",
    amountMinor: "10000",
    feeMinor: "60",
    refundedMinor: "0",
    reservedMinor: "0",
    refundedFeeMinor: "0",
    currency: "CNY",
    status: "UNKNOWN",
    channel: "MOCK_A",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
  const batch = {
    id: "feedback-batch",
    merchantId: "merchant-a",
    businessDay: "2026-10-19",
    channel: "MOCK_A",
    version: 1,
    status: "FAILED",
    actualCount: 1,
    expectedCount: 1,
    actualAmountMinor: "10000",
    expectedAmountMinor: "10000",
    processedLines: 1,
  };
  try {
    const page = await context.newPage();
    await login(page, "admin");
    clientBudget();
    let unknownArmed = false;
    // Isolate response latency from render latency. Login and the served production bundle are real;
    // fixture API responses below exercise display states without creating financial requests.
    await page.route("**/api/console/**", async (route) => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith("/session")) return route.continue();
      if (route.request().method() === "POST") {
        await new Promise((resolve) => setTimeout(resolve, 600));
        await armPaint(page, "errorPaint", ".ant-message", "反馈测试错误");
        return route.fulfill({
          status: 503,
          json: { code: "FEEDBACK_TEST", message: "反馈测试错误" },
        });
      }
      let body: unknown = { items: [], nextCursor: null };
      if (path === "/api/console/merchants")
        body = [{ id: "merchant-a", name: "商户A", enabled: true }];
      else if (path.includes("/merchants/"))
        body = { id: "merchant-a", name: "商户A", enabled: true };
      else if (path.includes("/payments/"))
        body = {
          payment,
          refunds: [],
          entries: [],
          journals: [],
          tasks: [],
          conflicts: [],
          attempts: [],
          nextCursors: {},
        };
      else if (path.includes("/batches/"))
        body = { batch, issues: [], nextCursor: null };
      else if (path.endsWith("/batches"))
        body = { items: [batch], nextCursor: null };
      else if (path.endsWith("/tasks"))
        body = {
          items: [
            {
              id: "feedback-task",
              merchantId: "merchant-a",
              kind: "CHANNEL_PAYMENT",
              status: "DEAD",
              attempts: 10,
              dueAt: new Date().toISOString(),
              createdAt: new Date().toISOString(),
            },
          ],
          nextCursor: null,
        };
      if (
        path.includes("/payments/") &&
        payment.status === "UNKNOWN" &&
        !unknownArmed
      ) {
        unknownArmed = true;
        await armPaint(page, "unknownPaint", ".page-title .status", "结果未知");
      }
      return route.fulfill({ json: body });
    });
    async function measure(action: string, control: Locator, waiting: string) {
      await expect(control).toBeVisible();
      await expect(page.getByText("反馈测试错误").first()).not.toBeVisible();
      const millis = await control.evaluate(async (element, text) => {
        const button = element as HTMLButtonElement;
        const start = performance.now();
        return await new Promise<number>((resolve, reject) => {
          const timeout = setTimeout(() => {
            observer.disconnect();
            reject(new Error("No pending feedback"));
          }, 1500);
          const observer = new MutationObserver(() => {
            if (button.disabled && button.textContent?.includes(text)) {
              observer.disconnect();
              clearTimeout(timeout);
              requestAnimationFrame(() =>
                requestAnimationFrame(() => resolve(performance.now() - start)),
              );
            }
          });
          observer.observe(button, {
            subtree: true,
            childList: true,
            attributes: true,
            characterData: true,
          });
          button.click();
        });
      }, waiting);
      samples.push({ action, millis });
      expect(millis, action).toBeLessThanOrEqual(200);
      await expect(page.getByText("反馈测试错误").first()).toBeVisible();
      const errorMillis = await painted(page, "errorPaint");
      samples.push({
        action: `${action} error after response`,
        millis: errorMillis,
      });
      expect(errorMillis).toBeLessThanOrEqual(200);
    }
    await page.goto("/cashier");
    const submit = page.getByRole("button", { name: "提交模拟支付" });
    await expect(submit).toBeVisible();
    // Client validation includes its visible paint, using the same browser clock.
    const validation = await page.evaluate(async () => {
      const started = performance.now();
      const observerResult = new Promise<number>((resolve) => {
        const observer = new MutationObserver(() => {
          if (document.querySelector(".ant-form-item-explain-error")) {
            observer.disconnect();
            requestAnimationFrame(() =>
              requestAnimationFrame(() => resolve(performance.now() - started)),
            );
          }
        });
        observer.observe(document.body, { subtree: true, childList: true });
      });
      (
        document.querySelector('button[type="submit"]') as HTMLButtonElement
      ).click();
      return observerResult;
    });
    samples.push({ action: "payment validation", millis: validation });
    expect(validation).toBeLessThanOrEqual(200);
    // Admin must choose the merchant in forms; the global filter also applies to subsequent pages.
    await page.getByLabel("商户", { exact: true }).click();
    await page.getByText("商户A", { exact: true }).last().click();
    await page.getByLabel("支付金额 / CNY 元").fill("100.00");
    await measure("payment submit", submit, "正在提交支付");
    await page.goto("/payments/feedback-payment");
    await expect(page.locator(".page-title .status")).toHaveText("结果未知");
    const unknownMillis = await painted(page, "unknownPaint");
    samples.push({
      action: "unknown result after response",
      millis: unknownMillis,
    });
    expect(unknownMillis).toBeLessThanOrEqual(200);
    await measure(
      "query",
      page.getByRole("button", { name: "主动查单" }),
      "正在安排查单",
    );
    await page.getByRole("button", { name: "请求关闭", exact: true }).click();
    await measure(
      "close",
      page.getByRole("dialog").getByRole("button", { name: /^确\s*定$/ }),
      "正在提交关闭请求",
    );
    payment.status = "SUCCEEDED";
    await page.goto("/payments/feedback-payment");
    await page.getByRole("button", { name: "申请退款", exact: true }).click();
    await page.getByLabel("退款金额 / 元").fill("1.00");
    await measure(
      "refund submit",
      page.getByRole("button", { name: "提交退款申请" }),
      "正在提交退款",
    );
    await page.goto("/reconciliation");
    await page
      .getByRole("button", { name: "拉取模拟账单", exact: true })
      .click();
    await page.getByLabel("商户", { exact: true }).click();
    await page.getByText("商户A", { exact: true }).last().click();
    await measure(
      "bill submit",
      page.getByRole("button", { name: "拉取并开始对账" }),
      "正在提交账单",
    );
    await page.goto("/reconciliation");
    await page.getByRole("button", { name: /2026-10-19/ }).click();
    await measure(
      "reconciliation retry",
      page.getByRole("button", { name: "从检查点续跑" }),
      "正在安排续跑",
    );
    await page.goto("/tasks");
    await measure(
      "task replay",
      page.getByRole("button", { name: "重放", exact: true }),
      "正在安排重放",
    );
    await page.goto("/exports");
    await page.getByRole("button", { name: "创建导出", exact: true }).click();
    await page.getByLabel("商户", { exact: true }).click();
    await page.getByText("商户A", { exact: true }).last().click();
    await measure(
      "export submit",
      page.getByRole("button", { name: "创建导出任务", exact: true }),
      "正在创建导出",
    );
    const report = {
      at: new Date().toISOString(),
      controlledResponses: true,
      responseDelayMillis: 600,
      measured: "click to second animation frame after visible pending state",
      samples,
      resourceBudget: clientBudget(),
      referenceR1Certified: false,
    };
    await writeFile(
      new URL("../../.work/release/critical-feedback.json", import.meta.url),
      JSON.stringify(report, null, 2),
    );
    await info.attach("critical-feedback.json", {
      body: JSON.stringify(report),
      contentType: "application/json",
    });
  } finally {
    await context.close();
  }
});
