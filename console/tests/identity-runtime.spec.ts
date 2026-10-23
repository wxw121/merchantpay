import { expect, test } from "@playwright/test";
import { createHash, createPublicKey, randomBytes, verify } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { environment } from "./support";

test("restored identity keys and OIDC code flow survive the patched JVM", async ({
  browser,
}) => {
  test.skip(
    process.env.RUN_IDENTITY_PATCH !== "1",
    "Opt-in isolated identity runtime check",
  );
  const base = "http://127.0.0.1:18182";
  const redirect = "http://127.0.0.1:8080/login/oauth2/code/keycloak";
  const context = await browser.newContext();
  try {
    const response = await context.request.get(
      `${base}/realms/merchantpay/protocol/openid-connect/certs`,
    );
    expect(response.status()).toBe(200);
    const jwks = await response.json();
    const original = JSON.parse(
      await readFile(
        new URL(
          "../../.data/restores/native-v1b-20261020/identity-jwks.json",
          import.meta.url,
        ),
        "utf8",
      ),
    );
    const fingerprint = (keys: Record<string, unknown>[]) =>
      createHash("sha256")
        .update(
          JSON.stringify(
            keys
              .map((key) =>
                Object.fromEntries(
                  ["kid", "kty", "alg", "use", "n", "e", "crv", "x", "y"].map(
                    (name) => [name, key[name] ?? null],
                  ),
                ),
              )
              .sort((a, b) => String(a.kid).localeCompare(String(b.kid))),
          ),
        )
        .digest("hex");
    expect(fingerprint(jwks.keys)).toBe(fingerprint(original.keys));
    const page = await context.newPage();
    let returned: URL | undefined;
    await page.route(/^http:\/\/127\.0\.0\.1:8080\//, (route) => route.abort());
    // Capture the first redirect response: routing does not intercept its next URL.
    await page.route(
      `${base}/realms/merchantpay/login-actions/authenticate**`,
      async (route) => {
        const response = await route.fetch({ maxRedirects: 0 });
        expect(response.status()).toBe(302);
        returned = new URL(response.headers().location!);
        expect(returned.origin + returned.pathname).toBe(redirect);
        await route.fulfill({
          status: 200,
          contentType: "text/html",
          body: "Isolated identity callback captured",
        });
      },
    );
    const verifier = randomBytes(32).toString("base64url");
    const state = randomBytes(32).toString("base64url");
    const nonce = randomBytes(32).toString("base64url");
    const auth = new URL(
      `${base}/realms/merchantpay/protocol/openid-connect/auth`,
    );
    auth.search = new URLSearchParams({
      client_id: "merchantpay-console",
      response_type: "code",
      response_mode: "query",
      redirect_uri: redirect,
      scope: "openid profile",
      state,
      nonce,
      code_challenge_method: "S256",
      code_challenge: createHash("sha256").update(verifier).digest("base64url"),
    }).toString();
    await page.goto(auth.toString());
    await page
      .getByRole("textbox", { name: /Username|用户名/i })
      .fill("merchant-a");
    await page.locator("input#password").fill(environment.DEMO_PASSWORD!);
    await page.getByRole("button", { name: /Sign In|登录/i }).click();
    await expect.poll(() => returned?.searchParams.get("state")).toBe(state);
    const code = returned!.searchParams.get("code");
    expect(code).toBeTruthy();
    const form = {
      client_id: "merchantpay-console",
      grant_type: "authorization_code",
      redirect_uri: redirect,
      code: code!,
      code_verifier: verifier,
    };
    const headers = {
      Authorization: `Basic ${Buffer.from(`merchantpay-console:${environment.OIDC_CLIENT_SECRET!}`).toString("base64")}`,
    };
    const tokenResponse = await context.request.post(
      `${base}/realms/merchantpay/protocol/openid-connect/token`,
      { form, headers },
    );
    expect(tokenResponse.status()).toBe(200);
    const tokens = await tokenResponse.json();
    const checkToken = (token: string) => {
      const parts = token.split(".");
      const header = JSON.parse(Buffer.from(parts[0]!, "base64url").toString());
      expect(header.alg).toBe("RS256");
      const key = jwks.keys.find(
        (entry: { kid: string }) => entry.kid === header.kid,
      );
      expect(Boolean(key)).toBe(true);
      expect(
        verify(
          "RSA-SHA256",
          Buffer.from(`${parts[0]}.${parts[1]}`),
          createPublicKey({ key, format: "jwk" }),
          Buffer.from(parts[2]!, "base64url"),
        ),
      ).toBe(true);
      const claims = JSON.parse(Buffer.from(parts[1]!, "base64url").toString());
      expect(claims.iss).toBe(`${base}/realms/merchantpay`);
      expect(claims.preferred_username).toBe("merchant-a");
      return claims;
    };
    const access = checkToken(tokens.access_token);
    expect(access.realm_access.roles).toContain("MERCHANT");
    expect(access.merchant_id).toBe("merchant-a");
    expect(checkToken(tokens.id_token).nonce).toBe(nonce);
    const replay = await context.request.post(
      `${base}/realms/merchantpay/protocol/openid-connect/token`,
      { form, headers },
    );
    expect(replay.status()).toBe(400);
    returned = undefined;
    await context.clearCookies();
    auth.searchParams.set("prompt", "login");
    await page.goto(auth.toString());
    await page
      .getByRole("textbox", { name: /Username|用户名/i })
      .fill("merchant-a");
    await page.locator("input#password").fill(environment.DEMO_PASSWORD!);
    await page.getByRole("button", { name: /Sign In|登录/i }).click();
    await expect.poll(() => returned?.searchParams.get("state")).toBe(state);
    const wrongVerifier = await context.request.post(
      `${base}/realms/merchantpay/protocol/openid-connect/token`,
      {
        form: {
          ...form,
          code: returned!.searchParams.get("code")!,
          code_verifier: randomBytes(32).toString("base64url"),
        },
        headers,
      },
    );
    expect(wrongVerifier.status()).toBe(400);
    const rejected = await wrongVerifier.json();
    expect(rejected.error).toBe("invalid_grant");
    expect(rejected.error_description).toMatch(/PKCE/i);
    await writeFile(
      new URL("../../.work/release/keycloak25-oidc.json", import.meta.url),
      JSON.stringify(
        {
          at: new Date().toISOString(),
          passed: true,
          publicKeyMaterialSha256: fingerprint(jwks.keys),
          restoredPublicKeyMaterialPreserved: true,
          codeFlowPkce: true,
          wrongPkceVerifierRejected: true,
          signedAccessAndIdTokens: true,
          merchantRole: true,
          codeReplayRejected: true,
          sourceCallbackIntercepted: true,
          sourceFinancialWrites: 0,
        },
        null,
        2,
      ),
    );
  } finally {
    await context.close();
  }
});
