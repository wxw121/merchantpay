import { expect, it } from "vitest";
import { statusLabel, canRefund } from "./status";
it("keeps an unknown payment distinct from a confirmed failure", () => {
  expect(statusLabel("UNKNOWN")).toBe("结果未知");
  expect(statusLabel("FAILED")).toBe("明确失败");
});
it("offers refunds only for successful payments and authorized roles", () => {
  expect(canRefund(["MERCHANT"], "SUCCEEDED")).toBe(true);
  expect(canRefund(["ADMIN"], "SUCCEEDED")).toBe(true);
  expect(canRefund(["AUDITOR"], "SUCCEEDED")).toBe(false);
  expect(canRefund(["MERCHANT"], "UNKNOWN")).toBe(false);
});
