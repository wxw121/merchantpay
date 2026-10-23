import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";
import { readFileSync } from "node:fs";
const environment = Object.fromEntries(
  readFileSync(new URL("../../.env", import.meta.url), "utf8")
    .split(/\r?\n/)
    .filter((line) => line.includes("="))
    .map((line) => [
      line.slice(0, line.indexOf("=")),
      line.slice(line.indexOf("=") + 1),
    ]),
);
async function login(page: Page, username: string) {
  await page.goto("/");
  await page.getByRole("link", { name: "登录工作台" }).click();
  await page.getByRole("textbox", { name: /Username|用户名/i }).fill(username);
  await page.locator("input#password").fill(environment.DEMO_PASSWORD!);
  await page.getByRole("button", { name: /Sign In|登录/i }).click();
  await expect(
    page.getByRole("heading", { name: "业务概览", exact: true }),
  ).toBeVisible();
}
let paymentId: string, batchId: string, exportId: string;
test.describe
  .serial("V1 merchant payment lifecycle on real MySQL and independent channel", () => {
  test("signs in through OIDC, pays, refunds, reconciles, and downloads authorized export", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    const failures: string[] = [];
    page.on("pageerror", (error) => failures.push(error.message));
    await login(page, "merchant-a");
    await page
      .getByRole("link", { name: "支付与退款", exact: true })
      .first()
      .click();
    await page
      .getByRole("button", { name: "发起模拟支付", exact: true })
      .click();
    await page.getByLabel("商户支付业务号").fill(`e2e-${Date.now()}`);
    await page.getByLabel("支付金额 / CNY 元").fill("100.00");
    await page.getByRole("button", { name: "提交模拟支付" }).click();
    await expect(
      page.getByRole("heading", { name: "支付详情", exact: true }),
    ).toBeVisible();
    paymentId = page.url().split("/").at(-1)!;
    await expect(page.locator(".page-title .status")).toHaveText("成功");
    await page.getByRole("button", { name: "申请退款", exact: true }).click();
    await page.getByLabel("退款金额 / 元").fill("30.00");
    await page.getByRole("button", { name: "提交退款申请" }).click();
    await expect
      .poll(async () => {
        const response = await page.request.get(
          `/api/console/payments/${paymentId}`,
        );
        return (await response.json()).payment.refundedMinor;
      })
      .toBe("3000");
    const detail = await (
      await page.request.get(`/api/console/payments/${paymentId}`)
    ).json();
    expect(detail.payment.reservedMinor).toBe("0");
    expect(detail.payment.refundedFeeMinor).toBe("18");
    expect(detail.journals).toHaveLength(2);
    expect(detail.entries).toHaveLength(6);
    await expect(
      page.getByRole("tab", { name: "账务分录（本页 6 条）", exact: true }),
    ).toBeVisible();
    await expect(page.locator(".ant-descriptions").first()).toContainText(
      /已成功退款\s*[:：]?\s*30\.00\s*元/,
    );
    await expect(page.locator(".ant-descriptions").first()).toContainText(
      /处理中预占\s*[:：]?\s*0\.00\s*元/,
    );
    await page.getByRole("tab", { name: /账务分录/ }).click();
    await expect(
      page.getByRole("cell", { name: "商户应付", exact: true }).first(),
    ).toBeVisible();
    await page.screenshot({
      path: "test-results/payment-ledger.png",
      fullPage: true,
    });
    await page
      .getByRole("link", { name: "对账与差错", exact: true })
      .first()
      .click();
    await page
      .getByRole("button", { name: "拉取模拟账单", exact: true })
      .click();
    await page
      .getByLabel("账单版本")
      .fill(String(Math.floor(Date.now() / 1000)));
    const pulled = page.waitForResponse(
      (response) =>
        response.url().includes("/api/console/reconciliation/pull") &&
        response.request().method() === "POST",
    );
    await page.getByRole("button", { name: "拉取并开始对账" }).click();
    const pullResponse = await pulled;
    expect(pullResponse.status()).toBe(202);
    batchId = (await pullResponse.json()).id;
    await expect
      .poll(
        async () =>
          (
            await (
              await page.request.get(`/api/console/batches/${batchId}`)
            ).json()
          ).batch.status,
        { timeout: 120_000 },
      )
      .toBe("COMPLETE");
    const batch = await (
      await page.request.get(`/api/console/batches/${batchId}`)
    ).json();
    expect(
      batch.issues.filter(
        (issue: { aggregateId: string }) => issue.aggregateId === paymentId,
      ),
    ).toEqual([]);
    await page
      .getByRole("dialog", { name: "对账批次与差错" })
      .getByRole("button", { name: "关闭", exact: true })
      .click();
    await page.getByRole("link", { name: "导出文件", exact: true }).click();
    await page.getByRole("button", { name: "创建导出", exact: true }).click();
    const exported = page.waitForResponse(
      (response) =>
        response.url().endsWith("/api/console/exports") &&
        response.request().method() === "POST",
    );
    await page
      .getByRole("button", { name: "创建导出任务", exact: true })
      .click();
    exportId = (await (await exported).json()).id;
    await expect(
      page
        .getByRole("row")
        .filter({ hasText: exportId })
        .getByRole("link", { name: "下载 ZIP" }),
    ).toBeEnabled();
    const download = page.waitForEvent("download");
    await page
      .getByRole("row")
      .filter({ hasText: exportId })
      .getByRole("link", { name: "下载 ZIP" })
      .click();
    expect((await download).suggestedFilename()).toBe(`export-${exportId}.zip`);
    expect(failures).toEqual([]);
  });
  test("another merchant cannot read the payment, original bill, or export", async ({
    page,
  }) => {
    await login(page, "merchant-b");
    expect(
      (await page.request.get(`/api/console/payments/${paymentId}`)).status(),
    ).toBe(404);
    expect(
      (
        await page.request.get(`/api/console/batches/${batchId}/original`)
      ).status(),
    ).toBe(404);
    expect(
      (
        await page.request.get(`/api/console/exports/${exportId}/download`)
      ).status(),
    ).toBe(404);
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto("/cashier");
    await expect(
      page.getByRole("heading", { name: "模拟收银台", exact: true }),
    ).toBeVisible();
    expect(
      await page
        .locator("body")
        .evaluate((element) => element.scrollWidth <= window.innerWidth),
    ).toBe(true);
    await page.screenshot({
      path: "test-results/cashier-mobile.png",
      fullPage: true,
    });
  });
});
