export function formatMinor(value: string): string {
  if (!/^-?(0|[1-9]\d*)$/.test(value)) throw new Error("金额格式无效");
  const amount = BigInt(value);
  const absolute = amount < 0n ? -amount : amount;
  return `${amount < 0n ? "-" : ""}${(absolute / 100n).toLocaleString("en-US")}.${(absolute % 100n).toString().padStart(2, "0")}`;
}

export function parseYuan(value: string): string {
  if (!/^(0|[1-9]\d{0,7})(\.\d{1,2})?$/.test(value))
    throw new Error("请输入最多两位小数的正金额");
  const [yuan, fraction = ""] = value.split(".");
  const minor = BigInt(yuan!) * 100n + BigInt(fraction.padEnd(2, "0"));
  if (minor < 1n || minor > 1_000_000_000n)
    throw new Error("金额范围为 0.01～10,000,000.00 元");
  return minor.toString();
}
