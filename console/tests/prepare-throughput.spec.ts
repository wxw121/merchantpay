import { test, expect } from "@playwright/test";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { login } from "./support";

test("prepare isolated throughput merchants through the real admin API", async ({
  browser,
}) => {
  test.skip(
    process.env.RUN_THROUGHPUT_PREPARE !== "1",
    "Opt-in isolated load preparation",
  );
  test.setTimeout(120_000);
  const folder = new URL("../../.data/container/", import.meta.url);
  const file = new URL("throughput-credentials.json", folder);
  type Credential = { merchantId: string; keyId: string; secret: string };
  let credentials: Credential[] = [];
  try {
    credentials = JSON.parse(await readFile(file, "utf8"));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  const context = await browser.newContext({
    baseURL: "http://127.0.0.1:15173",
  });
  try {
    const page = await context.newPage();
    await login(page, "admin");
    const response = await page.request.get("/api/console/session");
    expect(response.status()).toBe(200);
    const session = await response.json();
    expect(session.roles).toContain("ADMIN");
    const headers = { [session.csrfHeader]: session.csrfToken };
    await mkdir(folder, { recursive: true });
    for (let index = 0; index < 32; index++) {
      const merchantId = `perf-opt-20261021-${String(index).padStart(2, "0")}`;
      const existing = credentials.find(
        (value) => value.merchantId === merchantId,
      );
      if (existing) {
        const keys = await page.request.get(
          `/api/console/merchants/${merchantId}/keys`,
        );
        expect(keys.status()).toBe(200);
        const active = (await keys.json()).some(
          (value: { id: string; enabled: boolean; expiresAt?: string }) =>
            value.id === existing.keyId && value.enabled,
        );
        if (!active)
          throw new Error("Saved throughput credential is no longer active");
        continue;
      }
      const created = await page.request.post("/api/console/merchants", {
        headers,
        data: {
          id: merchantId,
          name: `隔离吞吐测试 ${index}`,
          enabled: true,
          notificationUrl: null,
        },
      });
      expect(created.ok()).toBe(true);
      const issued = await page.request.post(
        `/api/console/merchants/${merchantId}/keys`,
        { headers },
      );
      expect(issued.status()).toBe(200);
      const key = await issued.json();
      if (
        key.merchantId !== merchantId ||
        typeof key.keyId !== "string" ||
        typeof key.secret !== "string" ||
        key.secret.length < 32
      )
        throw new Error("Invalid issued throughput credential");
      credentials.push(key);
      await writeFile(file, JSON.stringify(credentials));
    }
    expect(credentials.length).toBe(32);
  } finally {
    await context.close();
  }
});
