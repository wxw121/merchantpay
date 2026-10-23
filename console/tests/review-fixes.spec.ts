import { test, expect } from "@playwright/test";
import { login } from "./support";

test("administrator searches merchants in business forms and queries an older date range", async ({
  page,
}) => {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await login(page, "admin");
  const merchant = await (
    await page.request.get("/api/console/merchants/merchant-b")
  ).json();
  await page
    .getByRole("link", { name: "支付与退款", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "发起模拟支付", exact: true }).click();
  const merchantInput = page.getByRole("combobox", {
    name: "商户",
    exact: true,
  });
  await merchantInput.fill("merchant-b");
  await page.getByText(String(merchant.name), { exact: true }).click();
  await page.getByLabel("商户支付业务号").fill(`review-${Date.now()}`);
  await page.getByLabel("支付金额 / CNY 元").fill("1.00");
  await page.getByRole("button", { name: "提交模拟支付" }).click();
  await expect(
    page.getByRole("heading", { name: "支付详情", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".page-title .status")).toHaveText("成功");

  await page
    .getByRole("link", { name: "对账与差错", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "拉取模拟账单", exact: true }).click();
  await page
    .getByRole("combobox", { name: "商户", exact: true })
    .fill("merchant-b");
  await page.getByText(String(merchant.name), { exact: true }).click();
  await page.getByRole("button", { name: "关闭", exact: true }).click();

  await page
    .getByRole("link", { name: "导出文件", exact: true })
    .first()
    .click();
  await page.getByRole("button", { name: "创建导出", exact: true }).click();
  await page
    .getByRole("combobox", { name: "商户", exact: true })
    .fill("merchant-b");
  await page.getByText(String(merchant.name), { exact: true }).click();
  await page.getByRole("button", { name: "关闭", exact: true }).click();

  await page
    .getByRole("link", { name: "任务与通知", exact: true })
    .first()
    .click();
  await page.getByLabel("开始日期（北京时间，含）").fill("2026-10-01");
  await page.getByLabel("截止日期（北京时间，不含）").fill("2026-10-11");
  const responsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return (
      url.pathname === "/api/console/tasks" &&
      url.searchParams.get("from") === "2026-09-30T16:00:00.000Z"
    );
  });
  await page.getByRole("button", { name: "查询日期范围" }).click();
  const response = await responsePromise;
  expect(response.status()).toBe(200);
  const result = await response.json();
  expect(new Date(result.from).toISOString()).toBe("2026-09-30T16:00:00.000Z");
  expect(new Date(result.until).toISOString()).toBe("2026-10-10T16:00:00.000Z");
  await expect(page.locator(".ant-spin-spinning, .ant-spin-blur")).toHaveCount(
    0,
  );
  await expect(page.locator(".ant-spin-container")).toHaveCSS("opacity", "1");
  await page.screenshot({
    path: test.info().outputPath("date-filter-desktop.png"),
    fullPage: true,
  });
  await page.setViewportSize({ width: 375, height: 812 });
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: test.info().outputPath("date-filter-mobile.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});
