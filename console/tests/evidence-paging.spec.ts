import { expect, test } from "@playwright/test";
import { consolePost, login } from "./support";

test("pages complete payment evidence through the real API on desktop and mobile", async ({
  page,
}) => {
  test.setTimeout(180_000);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "merchant-a");
  const created = await consolePost(page, "/api/console/payments", {
    merchantOrderNo: `paging-${Date.now()}`,
    amountMinor: "10000",
    currency: "CNY",
    channel: "MOCK_A",
  });
  expect(created.status()).toBe(202);
  const payment = await created.json();
  const path = `/api/console/payments/${payment.id}`;
  await expect
    .poll(
      async () => (await (await page.request.get(path)).json()).payment.status,
    )
    .toBe("SUCCEEDED");
  for (let index = 0; index < 51; index++) {
    const response = await consolePost(page, `${path}/refunds`, {
      merchantRefundNo: `paging-refund-${index}`,
      amountMinor: "1",
    });
    expect(response.status()).toBe(202);
  }
  await expect
    .poll(
      async () => {
        const detail = await (await page.request.get(path)).json();
        return [detail.payment.refundedMinor, detail.payment.reservedMinor];
      },
      { timeout: 60_000 },
    )
    .toEqual(["51", "0"]);

  await page.goto(`/payments/${payment.id}`);
  await page.getByRole("tab", { name: /退款记录/ }).click();
  const refunds = page.getByRole("tabpanel", { name: /退款记录/ });
  const refundRows = refunds.locator(".ant-table-tbody > tr[data-row-key]");
  await expect(refundRows).toHaveCount(50);
  const firstIds = await refundRows.evaluateAll((rows) =>
    rows.map((row) => row.getAttribute("data-row-key")),
  );
  await refunds.getByRole("button", { name: "退款下一页" }).click();
  await expect(refundRows).toHaveCount(1);
  expect(firstIds).not.toContain(
    await refundRows.first().getAttribute("data-row-key"),
  );
  await expect(
    refunds.getByRole("button", { name: "退款下一页" }),
  ).toBeDisabled();
  await refunds.getByRole("button", { name: "退款上一页" }).click();
  await expect(refundRows).toHaveCount(50);

  await page.getByRole("tab", { name: /账务分录/ }).click();
  const entries = page.getByRole("tabpanel", { name: /账务分录/ });
  const nextResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === path && url.searchParams.has("journalsCursor");
  });
  await entries.getByRole("button", { name: "凭证下一页" }).click();
  const response = await nextResponse;
  expect(response.status()).toBe(200);
  const detail = await response.json();
  expect(detail.journals).toHaveLength(2);
  expect(detail.entries).toHaveLength(5);
  const balance = detail.entries.reduce(
    (sum: bigint, entry: { side: string; amountMinor: string }) =>
      sum + BigInt(entry.amountMinor) * (entry.side === "DEBIT" ? 1n : -1n),
    0n,
  );
  expect(balance).toBe(0n);
  await expect(
    entries.locator(".ant-table-tbody > tr[data-row-key]"),
  ).toHaveCount(5);
  await page.screenshot({
    path: test.info().outputPath("evidence-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(
    entries.getByRole("button", { name: "凭证上一页" }),
  ).toBeVisible();
  await page.screenshot({
    path: test.info().outputPath("evidence-mobile.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
