import { afterEach, expect, it, vi } from "vitest";
import { api, ApiError, post, setActionConfirmation } from "./api";
afterEach(() => {
  setActionConfirmation(null);
  vi.unstubAllGlobals();
});
const reply = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
it("asks for explicit consent and retries only the exact request with its one-use token", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      reply({ code: "CONFIRMATION_REQUIRED", message: "confirm" }, 428),
    )
    .mockResolvedValueOnce(
      reply({
        token: "one-use",
        bodyHash: "hash",
        expiresAt: "2026-10-31T00:02:00Z",
      }),
    )
    .mockResolvedValueOnce(reply({ ok: true }));
  vi.stubGlobal("fetch", fetch);
  const confirm = vi.fn().mockResolvedValue(true);
  setActionConfirmation(confirm);
  await post("/api/console/merchants", { id: "merchant-a" });
  expect(confirm).toHaveBeenCalledOnce();
  expect(fetch).toHaveBeenCalledTimes(3);
  expect(fetch.mock.calls[0]![1].body).toEqual(fetch.mock.calls[2]![1].body);
  expect(
    (fetch.mock.calls[2]![1].headers as Headers).get("X-Action-Confirmation"),
  ).toBe("one-use");
});
it("cancelling consent never retries the money action", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(
      reply({ code: "CONFIRMATION_REQUIRED", message: "confirm" }, 428),
    )
    .mockResolvedValueOnce(
      reply({
        token: "unused",
        bodyHash: "hash",
        expiresAt: "2026-10-31T00:02:00Z",
      }),
    );
  vi.stubGlobal("fetch", fetch);
  setActionConfirmation(async () => false);
  await expect(
    post("/api/console/finance/settlements/id/execute", {
      expectedVersion: 1,
      reason: "reviewed",
    }),
  ).rejects.toMatchObject({ code: "ACTION_CANCELLED" });
  expect(fetch).toHaveBeenCalledTimes(2);
});
it("MFA rejection is visible and does not auto-confirm or auto-execute", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValue(
      reply({ code: "MFA_REQUIRED", message: "step up" }, 403),
    );
  vi.stubGlobal("fetch", fetch);
  const confirm = vi.fn();
  setActionConfirmation(confirm);
  await expect(
    api("/api/console/merchants", { method: "POST", body: "{}" }),
  ).rejects.toBeInstanceOf(ApiError);
  expect(fetch).toHaveBeenCalledOnce();
  expect(confirm).not.toHaveBeenCalled();
});
