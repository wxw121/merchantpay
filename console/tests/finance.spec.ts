import { expect, test, type Page } from "@playwright/test";
import { createHash } from "node:crypto";
import { consolePost, login } from "./support";

// Opt-in: isolated V2 Compose deployment and two distinct FINANCE demo identities.
test("reconciliation case -> independent adjustment -> source-based simulated settlement", async ({
  browser,
}) => {
  test.skip(process.env.RUN_V2_E2E !== "1", "Requires isolated V2 deployment");
  test.setTimeout(240_000);
  const contexts = await Promise.all([
    browser.newContext({ baseURL: "http://127.0.0.1:15173" }),
    browser.newContext({ baseURL: "http://127.0.0.1:15173" }),
  ]);
  const errors: string[] = [];
  const [page, reviewPage] = await Promise.all(
    contexts.map((c) => c.newPage()),
  );
  for (const current of [page!, reviewPage!])
    current.on("pageerror", (error) => errors.push(error.message));
  async function submit(current: Page, reason: string) {
    const modal = current.locator(".ant-modal");
    await modal.getByLabel("操作原因").fill(reason);
    await modal.getByRole("button", { name: /提\s*交/ }).click();
    await expect(modal).not.toBeVisible();
  }
  try {
    await login(page!, "admin");
    const merchantId = `finance-e2e-${Date.now()}`;
    const merchantName = `财务验收 ${merchantId}`;
    expect(
      (
        await consolePost(page!, "/api/console/merchants", {
          id: merchantId,
          name: merchantName,
          enabled: true,
          notificationUrl: null,
        })
      ).ok(),
    ).toBeTruthy();
    const created = await consolePost(
      page!,
      `/api/console/payments?merchantId=${merchantId}`,
      {
        merchantOrderNo: `finance-${Date.now()}`,
        amountMinor: "10000",
        currency: "CNY",
        channel: "MOCK_A",
      },
    );
    expect(created.status()).toBe(202);
    const payment = await created.json();
    await expect
      .poll(
        async () =>
          (
            await (
              await page!.request.get(`/api/console/payments/${payment.id}`)
            ).json()
          ).payment.status,
        { timeout: 90_000 },
      )
      .toBe("SUCCEEDED");
    const paid = (
      await (
        await page!.request.get(`/api/console/payments/${payment.id}`)
      ).json()
    ).payment;
    await contexts[0]!.close();
    // New context removes the administrator session and Keycloak SSO for the actual finance flow.
    contexts[0] = await browser.newContext({
      baseURL: "http://127.0.0.1:15173",
    });
    const financePage = await contexts[0].newPage();
    financePage.on("pageerror", (error) => errors.push(error.message));
    await login(financePage, "finance");
    await login(reviewPage!, "finance-reviewer");
    const day = new Intl.DateTimeFormat("en-CA", {
      timeZone: "Asia/Shanghai",
    }).format(new Date());
    const body = `#merchantpay-bill-v1,MOCK_A,${merchantId},${day},1,10100\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\nPAYMENT,${payment.id},SUCCEEDED,10100,${paid.feeMinor},CNY,${paid.channelReference}\n`;
    const version = Math.floor(Date.now() / 1000);
    const session = await (
      await financePage.request.get("/api/console/session")
    ).json();
    const upload = await financePage.request.post(
      "/api/console/reconciliation/upload",
      {
        headers: { [session.csrfHeader]: session.csrfToken },
        multipart: {
          merchantId,
          channel: "MOCK_A",
          day,
          version: String(version),
          sha256: createHash("sha256").update(body).digest("hex"),
          file: {
            name: "finance-acceptance.csv",
            mimeType: "text/csv",
            buffer: Buffer.from(body),
          },
        },
      },
    );
    expect(upload.status()).toBe(202);
    const batch = await upload.json();
    await expect
      .poll(
        async () =>
          (
            await (
              await financePage.request.get(`/api/console/batches/${batch.id}`)
            ).json()
          ).batch.status,
        { timeout: 60_000 },
      )
      .toBe("COMPLETE");
    await financePage.goto("/reconciliation");
    await financePage
      .getByRole("button", { name: new RegExp(`v${version}$`) })
      .click();
    await financePage.getByRole("button", { name: "创建或查看案件" }).click();
    await financePage
      .getByRole("button", { name: "指派与调查", exact: true })
      .click();
    await financePage
      .getByLabel("调查证据")
      .fill("浏览器验收：已核对原始渠道账单与支付流水");
    await financePage
      .locator(".ant-modal")
      .getByRole("button", { name: /提\s*交/ })
      .click();
    await financePage
      .getByRole("button", { name: "提出处理方案", exact: true })
      .click();
    await financePage.getByLabel("金额 / 分（无需调账填0）").fill("100");
    await submit(financePage, "补记商户应付100分");
    await expect(
      financePage.getByRole("button", { name: "修改方案" }),
    ).toBeVisible();
    await expect(
      financePage.getByRole("button", { name: "审批方案", exact: true }),
    ).toHaveCount(0);
    const proposalUrl = financePage.url();
    await reviewPage!.goto(proposalUrl);
    await reviewPage!
      .getByRole("button", { name: "审批方案", exact: true })
      .click();
    await submit(reviewPage!, "第二名财务独立复核金额与原始证据");
    await financePage
      .getByRole("button", { name: "刷新详情", exact: true })
      .click();
    await financePage
      .getByRole("button", { name: "执行已批准方案", exact: true })
      .click();
    await submit(financePage, "执行独立批准的调账");
    await expect(
      financePage.locator(".ant-drawer").getByText("已执行", { exact: true }),
    ).toBeVisible();
    const adjustmentId = new URL(financePage.url()).searchParams.get("id")!;
    const after = await (
      await financePage.request.get(`/api/console/payments/${payment.id}`)
    ).json();
    expect(after.payment.amountMinor).toBe("10000");
    expect(
      after.journals.filter(
        (j: { sourceType: string }) => j.sourceType === "ADJUSTMENT",
      ),
    ).toHaveLength(1);
    await financePage.locator(".ant-drawer-close").click();
    const selector = financePage.getByRole("combobox", { name: "当前商户" });
    await selector.fill(merchantId);
    await financePage
      .getByRole("option", { name: merchantName, exact: true })
      .click();
    await financePage
      .getByRole("tab", { name: "模拟结算", exact: true })
      .click();
    await financePage
      .getByRole("button", { name: "创建模拟结算", exact: true })
      .click();
    await financePage
      .getByLabel("来源截止时间（含时区的 ISO 时间）")
      .fill(new Date().toISOString());
    await submit(financePage, "浏览器验收结算来源快照");
    const settlementUrl = financePage.url();
    await reviewPage!.goto(settlementUrl);
    await reviewPage!
      .getByRole("button", { name: "审批方案", exact: true })
      .click();
    await submit(reviewPage!, "复核支付与调账来源共10040分");
    await financePage
      .getByRole("button", { name: "刷新详情", exact: true })
      .click();
    await financePage
      .getByRole("button", { name: "执行模拟结算", exact: true })
      .click();
    await submit(financePage, "仅记录模拟核销");
    await expect(
      financePage
        .locator(".ant-drawer")
        .getByText("已模拟付款", { exact: true }),
    ).toBeVisible();
    const settlementId = new URL(financePage.url()).searchParams.get("id")!;
    const settled = await (
      await financePage.request.get(
        `/api/console/finance/settlements/${settlementId}`,
      )
    ).json();
    expect(settled.netMinor).toBe("10040");
    expect(settled.lines).toHaveLength(2);
    await financePage.screenshot({
      path: "../.work/v2/finance-browser.png",
      fullPage: true,
    });
    expect(errors).toEqual([]);
    expect(adjustmentId).toMatch(/^adj_/);
  } finally {
    await Promise.all(contexts.map((c) => c.close()));
  }
});
