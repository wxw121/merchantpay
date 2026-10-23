import { readFileSync } from "node:fs";

export function clientBudget() {
  const path = process.env.WEB_PERF_RESOURCE_FILE;
  if (!path)
    throw new Error(
      "Run through scripts/browser/verify-client.ps1 to prove the client resource budget",
    );
  const sample = JSON.parse(readFileSync(path, "utf8")) as {
    at: string;
    verified: boolean;
    cpuMask: number;
    jobMemoryLimitBytes: number;
    peakJobCommittedBytes: number;
    members: {
      pid: number;
      name: string;
      inJob: boolean;
      withinBudget: boolean;
    }[];
  };
  if (
    !sample.verified ||
    !Number.isSafeInteger(sample.cpuMask) ||
    sample.cpuMask.toString(2).replaceAll("0", "").length !== 4 ||
    sample.jobMemoryLimitBytes !== 8 * 1024 ** 3 ||
    Date.now() - Date.parse(sample.at) > 3000 ||
    !sample.members.some((member) =>
      /msedge|chrome|chromium/i.test(member.name),
    ) ||
    sample.members.some((member) => !member.inJob || !member.withinBudget)
  )
    throw new Error(
      "Fresh OS proof of the browser tree's four-CPU/eight-GiB job budget is missing",
    );
  return {
    path,
    at: sample.at,
    cpuMask: sample.cpuMask,
    jobMemoryLimitBytes: sample.jobMemoryLimitBytes,
    peakJobCommittedBytes: sample.peakJobCommittedBytes,
    semantics:
      "Aggregate committed-memory ceiling for runner and browser tree; not dedicated physical RAM",
  };
}
