import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { mockRequests, renderWorkspace } from "../../tests/workspace";
import { Analytics } from "./Analytics";

const root = "/api/console/analytics";
const observedAt = "2026-10-19T00:00:00Z";
const report = {
  generation: "gen-1",
  dimension: "DAY",
  from: "2026-10-01T00:00:00Z",
  until: observedAt,
  observedAt,
  consistency: "EVENTUAL",
  sourceWatermarkStatus: "UNVERIFIED",
  rows: [
    {
      label: "2026-10-18",
      payments: "3",
      succeeded: "2",
      succeeded_minor: "9007199254740993",
      refunded_minor: "4503599627370497",
      success_rate_pct: "66.6667",
      refund_rate_pct: "50",
      latency_samples: "2",
      avg_success_latency_ms: "10.9",
      p99_success_latency_ms: null,
      latest_source_updated_at: observedAt,
    },
    {
      label: "2026-10-19",
      payments: "1",
      succeeded: "1",
      succeeded_minor: "7",
      refunded_minor: "0",
      success_rate_pct: "100",
      refund_rate_pct: "0",
      latency_samples: "1",
      avg_success_latency_ms: "8",
      p99_success_latency_ms: "8",
      latest_source_updated_at: observedAt,
    },
  ],
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("aggregates exact minor amounts and weights channel shares by payment count", async () => {
  const fetch = mockRequests((url) => {
    if (url.pathname !== root + "/payments")
      throw new Error(`Unexpected request: ${url}`);
    return Response.json(
      url.searchParams.get("dimension") === "CHANNEL"
        ? {
            ...report,
            dimension: "CHANNEL",
            rows: report.rows.map((row, index) => ({
              ...row,
              label: `MOCK_${index}`,
            })),
          }
        : report,
    );
  });
  renderWorkspace(<Analytics />);
  expect(await screen.findByText("9007199254741000")).toBeVisible();
  expect(screen.getByText("75%")).toBeVisible();
  expect(screen.getByText("50%")).toBeVisible();
  const dayRow = screen.getByText("2026-10-18").closest("tr")!;
  expect(within(dayRow).getByText("66.67%")).toBeVisible();
  expect(within(dayRow).getByText("11 ms")).toBeVisible();
  expect(within(dayRow).getByText("—")).toBeVisible();

  fireEvent.mouseDown(screen.getByRole("combobox", { name: "汇总维度" }));
  fireEvent.click(await screen.findByText("按渠道"));
  const channel = await screen.findByText("MOCK_1");
  expect(within(channel.closest("tr")!).getByText("25%")).toBeVisible();
  expect(fetch).toHaveBeenLastCalledWith(
    root + "/payments?merchantId=merchant-a&dimension=CHANNEL",
    expect.anything(),
  );
});

it("loads source progress and pre-reconciliation only on demand", async () => {
  const fetch = mockRequests((url) => {
    if (url.pathname === root + "/payments")
      return Response.json({ ...report, rows: [] });
    if (url.pathname === root + "/pre-reconciliation") {
      return Response.json({
        generation: "recon-1",
        consistency: "EVENTUAL",
        stream: {
          succeeded: "3",
          succeededMinor: "1001",
          latestSourceUpdatedAt: observedAt,
        },
        bill: null,
        countDelta: null,
        minorDelta: null,
      });
    }
    if (
      url.pathname === "/api/console/read-model/status" &&
      url.search === "?deep=true"
    ) {
      return Response.json({
        observedAt,
        sources: [
          {
            physicalShard: 2,
            allocatedSequence: "10007",
            firstUnprojectedSequence: "10002",
            noMissingAtRead: false,
            hasPendingBatch: true,
            hasQuarantine: false,
            observedAt,
          },
        ],
      });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  renderWorkspace(<Analytics />);
  expect(await screen.findByText("服务代 gen-1")).toBeVisible();
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(screen.getAllByText("—")).toHaveLength(2);
  fireEvent.click(screen.getByRole("button", { name: "检查来源进度" }));
  const missing = await screen.findByText("10002");
  expect(within(missing.closest("tr")!).getByText("否")).toBeVisible();
  expect(within(missing.closest("tr")!).getByText("有")).toBeVisible();

  fireEvent.change(screen.getByLabelText("预对账日期"), {
    target: { value: "2026-10-18" },
  });
  fireEvent.change(screen.getByLabelText("预对账渠道"), {
    target: { value: "mock_a" },
  });
  fireEvent.click(screen.getByRole("button", { name: "查询预对账" }));
  expect(await screen.findByText("1001")).toBeVisible();
  expect(screen.getAllByText("无法比较")).toHaveLength(2);
  expect(fetch).toHaveBeenLastCalledWith(
    root +
      "/pre-reconciliation?merchantId=merchant-a&day=2026-10-18&channel=MOCK_A",
    expect.anything(),
  );
});

it("validates signed evidence locally and looks up the resulting generation", async () => {
  const writes: unknown[] = [];
  const publication = {
    generation: "gen/new",
    status: "PUBLISHED",
    evidenceHash: "recorded-hash",
    publishedAt: observedAt,
  };
  mockRequests((url, options) => {
    if (url.pathname === root + "/payments") return Response.json(report);
    if (url.pathname === root + "/publications" && options.method === "POST") {
      writes.push(JSON.parse(String(options.body)));
      return Response.json(publication);
    }
    if (url.pathname === root + "/publications/gen%2Fnew") {
      return Response.json({ ...publication, status: "STALE" });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  renderWorkspace(<Analytics />);
  const evidence = screen.getByRole("textbox", { name: "分析代签名证据" });
  const submit = screen.getByRole("button", { name: "提交签名证据" });
  expect(submit).toBeDisabled();
  fireEvent.change(evidence, {
    target: { value: '{"payload":"signed","signature":15}' },
  });
  fireEvent.click(submit);
  expect(
    await screen.findByText("证据文件须包含 payload 和 signature 字符串"),
  ).toBeVisible();
  expect(writes).toHaveLength(0);
  fireEvent.change(evidence, {
    target: { value: '{"payload":"signed","signature":"proof","extra":true}' },
  });
  fireEvent.click(submit);
  expect(await screen.findByText("recorded-hash")).toBeVisible();
  expect(writes).toEqual([{ payload: "signed", signature: "proof" }]);
  expect(screen.getByRole("textbox", { name: "查询分析代" })).toHaveValue(
    "gen/new",
  );
  const lookup = await screen.findByRole("button", { name: /查询发布状态/ });
  await waitFor(() => expect(lookup).toBeEnabled());
  fireEvent.click(lookup);
  expect(await screen.findByText("STALE", { exact: true })).toBeVisible();
});
