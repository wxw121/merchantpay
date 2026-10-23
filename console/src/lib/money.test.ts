import { describe, expect, it } from "vitest";
import { formatMinor, parseYuan } from "./money";

describe("minor-unit money contract", () => {
  it("formats amounts without losing precision beyond the JS safe integer limit", () => {
    expect(formatMinor("9007199254740993")).toBe("90,071,992,547,409.93");
    expect(formatMinor("1")).toBe("0.01");
    expect(formatMinor("-2982")).toBe("-29.82");
  });

  it("converts valid yuan input to canonical minor-unit strings", () => {
    expect(parseYuan("100.01")).toBe("10001");
    expect(parseYuan("0.01")).toBe("1");
    expect(parseYuan("10000000.00")).toBe("1000000000");
  });

  it.each(["1.234", "1e2", "-1", "0.00", "10000000.01", "", " 1", "01.00"])(
    "rejects ambiguous, nonpositive, or excessive money input: %s",
    (value) => {
      expect(() => parseYuan(value)).toThrow();
    },
  );
});
