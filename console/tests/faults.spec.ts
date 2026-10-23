import { test, expect } from "@playwright/test";
import { signed } from "./support";

test("lost payment/refund responses recover and pending payment closes without a success posting", async ({
  request,
}) => {
  const created = await signed(request, "POST", "/api/v1/payments", {
    merchantOrderNo: `lost:e2e-${Date.now()}`,
    amountMinor: "10000",
    currency: "CNY",
    channel: "MOCK_B",
  });
  expect(created.status()).toBe(202);
  const payment = await created.json();
  await expect
    .poll(
      async () =>
        (
          await (
            await signed(request, "GET", `/api/v1/payments/${payment.id}`)
          ).json()
        ).status,
      { timeout: 30_000 },
    )
    .toBe("SUCCEEDED");
  const refunded = await signed(
    request,
    "POST",
    `/api/v1/payments/${payment.id}/refunds`,
    { merchantRefundNo: `lost:refund-${Date.now()}`, amountMinor: "3000" },
  );
  expect(refunded.status()).toBe(202);
  const refund = await refunded.json();
  await expect
    .poll(
      async () =>
        (
          await (
            await signed(request, "GET", `/api/v1/refunds/${refund.id}`)
          ).json()
        ).status,
      { timeout: 30_000 },
    )
    .toBe("SUCCEEDED");
  const pending = await (
    await signed(request, "POST", "/api/v1/payments", {
      merchantOrderNo: `pending:e2e-${Date.now()}`,
      amountMinor: "100",
      currency: "CNY",
      channel: "MOCK_A",
    })
  ).json();
  expect(
    (
      await signed(request, "POST", `/api/v1/payments/${pending.id}/close`, {})
    ).status(),
  ).toBe(202);
  await expect
    .poll(
      async () =>
        (
          await (
            await signed(request, "GET", `/api/v1/payments/${pending.id}`)
          ).json()
        ).status,
    )
    .toBe("CLOSED");
});
