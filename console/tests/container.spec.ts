import { expect, test } from "@playwright/test";
import { login } from "./support";

test("container console completes OIDC and uses its own authenticated backend", async ({
  browser,
}) => {
  test.skip(
    process.env.RUN_CONTAINER_E2E !== "1",
    "Requires independent container deployment",
  );
  const context = await browser.newContext({
    baseURL: "http://127.0.0.1:15173",
    viewport: { width: 1440, height: 900 },
  });
  try {
    const page = await context.newPage();
    await login(page, "admin");
    const response = await page.request.get(
      "http://127.0.0.1:15173/api/console/session",
    );
    expect(response.status()).toBe(200);
    const session = await response.json();
    expect(session.roles).toContain("ADMIN");
    expect(new URL(page.url()).port).toBe("15173");
    await expect(
      page.getByRole("navigation", { name: "主导航" }),
    ).toBeVisible();
    await page.screenshot({
      path: "../.work/release/container-console.png",
      fullPage: true,
    });
  } finally {
    await context.close();
  }
});
