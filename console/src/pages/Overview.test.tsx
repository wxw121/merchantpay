import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { mockRequests, renderWorkspace } from "../../tests/workspace";
import { Overview } from "./Overview";

const chart = vi.hoisted(() => ({
  setOption: vi.fn(),
  resize: vi.fn(),
  dispose: vi.fn(),
}));
vi.mock("echarts/core", () => ({
  use: vi.fn(),
  init: vi.fn(() => chart),
}));

const interval = {
  from: "2026-11-08T00:00:00Z",
  until: "2026-11-14T00:00:00Z",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

it("loads the selected merchant, graphs UTC buckets, and refreshes the totals", async () => {
  let reads = 0;
  const fetch = mockRequests((url) => {
    expect(url.searchParams.get("merchantId")).toBe("merchant-a");
    if (url.pathname === "/api/console/dashboard") {
      reads++;
      return Response.json({
        ...interval,
        totals: {
          paidMinor: reads === 1 ? "12345" : "22345",
          refundedMinor: "500",
          succeededCount: 2,
          totalCount: 5,
          unknownCount: 1,
        },
        trend: [
          { day: "2026-11-12", totalCount: 2 },
          { day: "2026-11-13", totalCount: 3 },
        ],
      });
    }
    if (url.pathname === "/api/console/channels") {
      return Response.json([
        {
          channel: "MOCK_A",
          state: "AVAILABLE",
          pendingCallbacks: 0,
          observedAt: interval.until,
        },
        {
          channel: "MOCK_B",
          state: "DOWN",
          pendingCallbacks: null,
          observedAt: interval.until,
        },
      ]);
    }
    return Response.json({ ...interval, items: [], nextCursor: null });
  });
  const page = renderWorkspace(<Overview />);

  expect(await screen.findByText("123.45")).toBeVisible();
  expect(screen.getByText("5.00")).toBeVisible();
  expect(await screen.findByText("连接正常")).toBeVisible();
  expect(screen.getByText("暂不可达")).toBeVisible();
  expect(screen.getByText("待回调 0 笔")).toBeVisible();
  expect(screen.getByText("待回调 — 笔")).toBeVisible();
  expect(chart.setOption).toHaveBeenCalledWith(
    expect.objectContaining({
      xAxis: expect.objectContaining({ data: ["2026-11-12", "2026-11-13"] }),
      series: [expect.objectContaining({ data: [2, 3] })],
    }),
  );

  const pageTitle = screen
    .getByRole("heading", { name: "业务概览" })
    .closest(".page-title")!;
  fireEvent.click(
    within(pageTitle as HTMLElement).getByRole("button", { name: /刷\s*新/ }),
  );
  expect(await screen.findByText("223.45")).toBeVisible();
  expect(reads).toBe(2);
  expect(fetch).toHaveBeenCalledWith(
    "/api/console/dashboard?merchantId=merchant-a",
    expect.anything(),
  );
  page.unmount();
  expect(chart.dispose).toHaveBeenCalled();
});

it("shows dashboard and channel errors independently", async () => {
  mockRequests((url) => {
    if (
      url.pathname === "/api/console/dashboard" ||
      url.pathname === "/api/console/channels"
    )
      return Response.json(
        {
          message: url.pathname.endsWith("channels")
            ? "渠道暂不可用"
            : "统计暂不可用",
        },
        { status: 503 },
      );
    return Response.json({ ...interval, items: [], nextCursor: null });
  });
  renderWorkspace(<Overview />);

  expect(await screen.findByText("统计暂不可用")).toBeVisible();
  expect(await screen.findByText("渠道暂不可用")).toBeVisible();
});
