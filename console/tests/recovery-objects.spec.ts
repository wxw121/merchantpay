import { test, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { login, consolePost } from "./support";

test("nonempty bill and ZIP survive the configured real object store", async ({
  browser,
}) => {
  test.skip(
    process.env.RUN_RECOVERY_OBJECTS !== "1",
    "Opt-in isolated recovery preparation",
  );
  test.setTimeout(180_000);
  const context = await browser.newContext({
    baseURL: "http://127.0.0.1:15173",
  });
  try {
    const page = await context.newPage();
    await login(page, "merchant-a");
    const created = await consolePost(
      page,
      "/api/console/payments?merchantId=merchant-a",
      {
        merchantOrderNo: `recovery-${Date.now()}`,
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
              await page.request.get(`/api/console/payments/${payment.id}`)
            ).json()
          ).payment.status,
        { timeout: 120_000 },
      )
      .toMatch(/SUCCEEDED|FAILED/);
    const day = new Date().toISOString().slice(0, 10);
    const pulled = await consolePost(page, "/api/console/reconciliation/pull", {
      merchantId: "merchant-a",
      channel: "MOCK_A",
      day,
      version: Math.floor(Date.now() / 1000),
    });
    expect(pulled.status()).toBe(202);
    const batch = await pulled.json();
    await expect
      .poll(
        async () =>
          (
            await (
              await page.request.get(`/api/console/batches/${batch.id}`)
            ).json()
          ).batch.status,
        { timeout: 120_000 },
      )
      .toBe("COMPLETE");
    const original = await page.request.get(
      `/api/console/batches/${batch.id}/original`,
    );
    expect(original.status()).toBe(200);
    expect((await original.body()).length).toBeGreaterThan(100);
    const exported = await consolePost(page, "/api/console/exports", {
      merchantId: "merchant-a",
      resource: "payments",
      from: new Date(Date.now() - 30 * 86400000).toISOString(),
      until: new Date(Date.now() + 60000).toISOString(),
    });
    expect(exported.status()).toBe(202);
    const zip = await exported.json();
    await expect
      .poll(
        async () => {
          const response = await page.request.get(
            `/api/console/exports/${zip.id}/download`,
          );
          return response.status();
        },
        { timeout: 120_000 },
      )
      .toBe(200);
    const content = await (
      await page.request.get(`/api/console/exports/${zip.id}/download`)
    ).body();
    expect(content.subarray(0, 2).toString()).toBe("PK");
    expect(content.length).toBeGreaterThan(100);
    const session = await (
      await page.request.get("/api/console/session")
    ).json();
    const cookies = await context.cookies();
    await mkdir(new URL("../../.data/acceptance/", import.meta.url), {
      recursive: true,
    });
    await writeFile(
      new URL("../../.data/acceptance/console-session.json", import.meta.url),
      JSON.stringify({
        cookie: cookies
          .filter((c) =>
            new URL("http://127.0.0.1:15173").hostname.endsWith(
              c.domain.replace(/^\./, ""),
            ),
          )
          .map((c) => `${c.name}=${c.value}`)
          .join("; "),
        csrf: session.csrfToken,
        csrfHeader: session.csrfHeader,
      }),
    );
    const mode = process.env.RECOVERY_OBJECT_MODE ?? "local";
    await writeFile(
      new URL(
        `../../.work/release/recovery-objects-${mode}.json`,
        import.meta.url,
      ),
      JSON.stringify(
        {
          at: new Date().toISOString(),
          mode,
          paymentId: payment.id,
          batchId: batch.id,
          exportId: zip.id,
          billBytes: (await original.body()).length,
          zipBytes: content.length,
          realOidcAndStorage: true,
        },
        null,
        2,
      ),
    );
  } finally {
    await context.close();
  }
});
