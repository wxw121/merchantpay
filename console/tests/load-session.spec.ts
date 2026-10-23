import { test, expect } from "@playwright/test";
import { mkdir, writeFile } from "node:fs/promises";
import { login } from "./support";

test("save a real merchant session for the isolated load driver", async ({
  browser,
}) => {
  test.skip(process.env.RUN_LOAD_SESSION !== "1", "Opt-in load preparation");
  const baseURL = "http://127.0.0.1:15173";
  const context = await browser.newContext({ baseURL });
  try {
    const page = await context.newPage();
    await login(page, "merchant-a");
    const response = await page.request.get("/api/console/session");
    expect(response.status()).toBe(200);
    const session = await response.json();
    expect(session.csrfToken).toBeTruthy();
    const cookies = await context.cookies(baseURL);
    expect(cookies.length).toBeGreaterThan(0);
    const folder = new URL("../../.data/acceptance/", import.meta.url);
    await mkdir(folder, { recursive: true });
    await writeFile(
      new URL("console-session.json", folder),
      JSON.stringify({
        cookie: cookies
          .map((cookie) => `${cookie.name}=${cookie.value}`)
          .join("; "),
        csrf: session.csrfToken,
        csrfHeader: session.csrfHeader,
      }),
    );
  } finally {
    await context.close();
  }
});
