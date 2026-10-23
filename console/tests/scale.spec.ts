import { test, expect } from "@playwright/test";
import { createHash } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { login } from "./support";

test("100000 row synthetic channel-only bill matches the independent oracle", async ({
  page,
}) => {
  test.skip(
    process.env.RUN_SCALE !== "1",
    "Explicit scale scenario; creates 100000 durable evidence rows",
  );
  test.setTimeout(650_000);
  await login(page, "merchant-b");
  const session = await (await page.request.get("/api/console/session")).json();
  const day = "2026-10-01",
    count = 100_000;
  let prefix = `scale_${Date.now()}`;
  const rows = Array.from(
    { length: count },
    (_, index) =>
      `PAYMENT,${prefix}_${index},SUCCEEDED,100,1,CNY,fixture_${index}\n`,
  ).join("");
  let buffer = Buffer.from(
    `#merchantpay-bill-v1,MOCK_B,merchant-b,${day},${count},${count * 100}\noperation,aggregateId,status,amountMinor,feeMinor,currency,reference\n` +
      rows,
  );
  const started = Date.now();
  let id = process.env.SCALE_BATCH_ID;
  if (id) {
    const original = await page.request.get(
      `/api/console/batches/${id}/original`,
    );
    expect(original.ok()).toBe(true);
    buffer = await original.body();
    prefix = buffer
      .toString("utf8")
      .split("\n")[2]!
      .split(",")[1]!
      .replace(/_[0-9]+$/, "");
  }
  const digest = createHash("sha256").update(buffer).digest("hex");
  if (!id) {
    const response = await page.request.post(
      "/api/console/reconciliation/upload",
      {
        headers: { [session.csrfHeader]: session.csrfToken },
        multipart: {
          channel: "MOCK_B",
          day,
          version: String(Math.floor(Date.now() / 1000)),
          sha256: digest,
          file: { name: "synthetic-100000.csv", mimeType: "text/csv", buffer },
        },
      },
    );
    expect(response.status()).toBe(202);
    id = (await response.json()).id;
  }
  await expect
    .poll(
      async () =>
        (await (await page.request.get(`/api/console/batches/${id}`)).json())
          .batch.status,
      { timeout: 590_000, intervals: [1000, 2000, 5000] },
    )
    .toBe("COMPLETE");
  const result = await (
    await page.request.get(`/api/console/batches/${id}`)
  ).json();
  expect(Number(result.batch.actualCount)).toBe(count);
  expect(result.batch.actualAmountMinor).toBe(String(count * 100));
  // Exhaust the signed cursor, rather than inferring correctness from the first page.
  let cursor: string | null = null,
    issueCount = 0;
  const aggregates = new Set<string>();
  const mismatches: string[] = [];
  do {
    const detail = await (
      await page.request.get(
        `/api/console/batches/${id}` +
          (cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""),
      )
    ).json();
    for (const issue of detail.issues) {
      if (
        issue.issueType !== "CHANNEL_ONLY" ||
        !new RegExp(`^${prefix}_[0-9]+$`).test(issue.aggregateId)
      ) {
        if (mismatches.length < 10)
          mismatches.push(`${issue.issueType}:${issue.aggregateId}`);
      }
      aggregates.add(issue.aggregateId);
      issueCount++;
    }
    cursor = detail.nextCursor;
    if (issueCount % 10000 === 0)
      console.log(`Verified ${issueCount} bill issues`);
  } while (cursor);
  expect(issueCount).toBe(count);
  expect(aggregates.size).toBe(count);
  expect(mismatches).toEqual([]);
  const report = {
    environment: "D0 shared developer host",
    batchId: id,
    syntheticFixture: true,
    rows: count,
    amountMinor: String(count * 100),
    sha256: digest,
    fileBytes: buffer.length,
    processingMilliseconds:
      new Date(result.batch.updatedAt).getTime() -
      new Date(result.batch.createdAt).getTime(),
    totalVerificationMilliseconds: Date.now() - started,
    issueCount,
    distinctAggregates: aggregates.size,
    correctnessPassed: true,
    referenceR1Certified: false,
  };
  mkdirSync("../.work/performance", { recursive: true });
  writeFileSync(
    `../.work/performance/bill-${id}.json`,
    JSON.stringify(report, null, 2),
  );
  console.log(JSON.stringify(report));
});
