import { expect, type Page, type APIRequestContext } from "@playwright/test";
import { readFileSync } from "node:fs";
import { createHash, createHmac, randomUUID } from "node:crypto";

export const environment = Object.fromEntries(
  readFileSync(new URL("../../.env", import.meta.url), "utf8")
    .split(/\r?\n/)
    .filter((line) => line.includes("="))
    .map((line) => [
      line.slice(0, line.indexOf("=")),
      line.slice(line.indexOf("=") + 1),
    ]),
);
export async function login(page: Page, username: string) {
  await page.goto("/");
  await page.getByRole("link", { name: "登录工作台" }).click();
  await page.getByRole("textbox", { name: /Username|用户名/i }).fill(username);
  await page
    .locator("input#password")
    .fill(username === "admin" ? "admin" : environment.DEMO_PASSWORD!);
  await page.getByRole("button", { name: /Sign In|登录/i }).click();
  await expect(
    page.getByRole("heading", { name: "业务概览", exact: true }),
  ).toBeVisible();
}
export async function consolePost(page: Page, path: string, data: unknown) {
  const session = await (await page.request.get("/api/console/session")).json();
  return page.request.post(path, {
    data,
    headers: { [session.csrfHeader]: session.csrfToken },
  });
}
export async function signed(
  request: APIRequestContext,
  method: "GET" | "POST",
  path: string,
  data?: unknown,
) {
  const keys = JSON.parse(
    readFileSync(
      new URL(
        "../../.data/platform/local-api-credentials.json",
        import.meta.url,
      ),
      "utf8",
    ),
  );
  const key = keys.find(
    (value: { merchantId: string }) => value.merchantId === "merchant-a",
  );
  const body = data === undefined ? "" : JSON.stringify(data);
  const timestamp = String(Math.floor(Date.now() / 1000)),
    nonce = randomUUID();
  const canonical = [
    timestamp,
    nonce,
    method,
    path,
    createHash("sha256").update(body).digest("hex"),
  ].join("\n");
  return request.fetch("http://127.0.0.1:8080" + path, {
    method,
    data: method === "POST" ? body : undefined,
    headers: {
      "Content-Type": "application/json",
      "X-Key-Id": key.keyId,
      "X-Timestamp": timestamp,
      "X-Nonce": nonce,
      "X-Signature": createHmac("sha256", key.secret)
        .update(canonical)
        .digest("hex"),
    },
  });
}
