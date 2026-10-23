import { expect, test } from "@playwright/test";
import { login } from "./support";

for (const username of ["admin", "merchant-a"]) {
  test(`identity runtime console login: ${username}`, async ({ browser }) => {
    test.skip(
      process.env.RUN_IDENTITY_CONSOLE !== "1",
      "Opt-in identity upgrade check",
    );
    const baseURL =
      process.env.IDENTITY_CONSOLE_BASE_URL ?? "http://127.0.0.1:5173";
    const context = await browser.newContext({ baseURL });
    try {
      const page = await context.newPage();
      await login(page, username);
      const response = await page.request.get("/api/console/session");
      expect(response.status()).toBe(200);
      const session = await response.json();
      expect(session.roles).toContain(
        username === "admin" ? "ADMIN" : "MERCHANT",
      );
      expect(session.merchantId).toBe(username === "admin" ? "" : "merchant-a");
      expect(session.csrfToken).toBeTruthy();
      const dashboard = await page.request.get("/api/console/dashboard");
      expect(dashboard.status()).toBe(200);
    } finally {
      await context.close();
    }
  });
}
