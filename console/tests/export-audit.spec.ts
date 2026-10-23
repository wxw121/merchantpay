import { test, expect } from "@playwright/test";
import { login, consolePost } from "./support";

test("platform export download is visible in its owning merchant audit", async ({
  page,
}) => {
  await login(page, "admin");
  const session = await (await page.request.get("/api/console/session")).json();
  const created = await consolePost(page, "/api/console/exports", {
    merchantId: "merchant-b",
    resource: "payments",
  });
  expect(created.status()).toBe(202);
  const { id } = await created.json();
  await expect
    .poll(async () => {
      const response = await page.request.get(
        "/api/console/exports?merchantId=merchant-b",
      );
      expect(response.status()).toBe(200);
      const jobs = await response.json();
      return jobs.items.find(
        (job: { id: string; status: string }) => job.id === id,
      )?.status;
    })
    .toBe("COMPLETE");
  const download = await page.request.get(
    `/api/console/exports/${id}/download`,
  );
  expect(download.status()).toBe(200);
  expect((await download.body()).subarray(0, 2).toString()).toBe("PK");

  for (const merchant of ["merchant-b", "merchant-a", ""]) {
    const response = await page.request.get(
      `/api/console/audit${merchant ? `?merchantId=${merchant}` : ""}`,
    );
    expect(response.status()).toBe(200);
    const audit = await response.json();
    const record = audit.items.find(
      (row: { operation: string; resourceId: string }) =>
        row.operation === "EXPORT_DOWNLOAD" && row.resourceId === id,
    );
    if (merchant === "merchant-a") expect(record).toBeUndefined();
    else
      expect(record).toMatchObject({
        merchantId: "merchant-b",
        actor: session.actor,
      });
  }
});
