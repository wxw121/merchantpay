import { expect, test } from "@playwright/test";
import { login } from "./support";
test("V2 operator pages expose observations and permission boundaries", async ({
  page,
}) => {
  test.skip(
    process.env.RUN_V2_REMAINING_E2E !== "1",
    "Requires explicitly prepared isolated V2 services; never starts them",
  );
  await login(page, "ops");
  await page.goto("/operations");
  await expect(page.getByRole("heading", { name: "运行与渠道" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "未知交易" })).toBeVisible();
  await page.goto("/read-model");
  await expect(
    page.getByRole("heading", { name: "独立查询投影" }),
  ).toBeVisible();
  await expect(page.getByText(/最终一致/)).toBeVisible();
  await page.goto("/balances");
  await expect(page.getByRole("heading", { name: "账务检查点" })).toBeVisible();
  await page.goto("/security");
  await expect(page.getByRole("heading", { name: "安全中心" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "完成双因素认证" }),
  ).toBeVisible();
});
test("merchant cannot read platform operation or quarantine APIs", async ({
  page,
}) => {
  test.skip(
    process.env.RUN_V2_REMAINING_E2E !== "1",
    "Requires isolated V2 services",
  );
  await login(page, "merchant-a");
  for (const path of [
    "/api/console/operations/summary",
    "/api/console/channel-controls",
    "/api/console/read-model/status",
    "/api/console/events/quarantine",
  ]) {
    expect((await page.request.get(path)).status()).toBe(403);
  }
});
