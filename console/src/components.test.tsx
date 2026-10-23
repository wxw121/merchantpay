import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { PagedTable } from "./components";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function setup() {
  const fetcher = vi.fn(
    async (_path: string) =>
      new Response(
        JSON.stringify({
          items: [{ id: "row-1" }],
          nextCursor: "old-cursor",
          from: "2026-10-01T00:00:00Z",
          until: "2026-10-18T00:00:00Z",
        }),
      ),
  );
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <PagedTable
        resource="tasks"
        columns={[{ title: "编号", dataIndex: "id" }]}
      />
    </QueryClientProvider>,
  );
  return fetcher;
}

it("queries an older date range in Beijing time and resets its previous cursor", async () => {
  const fetcher = setup();
  await screen.findByText("row-1");
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await waitFor(() =>
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining("cursor=old-cursor"),
      expect.anything(),
    ),
  );
  fireEvent.change(screen.getByLabelText("开始日期（北京时间，含）"), {
    target: { value: "2026-10-01" },
  });
  fireEvent.change(screen.getByLabelText("截止日期（北京时间，不含）"), {
    target: { value: "2026-10-11" },
  });
  fireEvent.click(screen.getByRole("button", { name: "查询日期范围" }));
  await waitFor(() =>
    expect(fetcher).toHaveBeenLastCalledWith(
      expect.stringContaining("from=2026-09-30T16%3A00%3A00.000Z"),
      expect.anything(),
    ),
  );
  const lastUrl = String(fetcher.mock.calls.at(-1)?.[0]);
  expect(lastUrl).toContain("until=2026-10-10T16%3A00%3A00.000Z");
  expect(lastUrl).not.toContain("cursor=");
  expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "最近七天" }));
  await waitFor(() =>
    expect(fetcher).toHaveBeenLastCalledWith(
      "/api/console/tasks?",
      expect.anything(),
    ),
  );
});

it("rejects a date range longer than 31 days before making a request", async () => {
  const fetcher = setup();
  await screen.findByText("row-1");
  fireEvent.change(screen.getByLabelText("开始日期（北京时间，含）"), {
    target: { value: "2026-09-01" },
  });
  fireEvent.change(screen.getByLabelText("截止日期（北京时间，不含）"), {
    target: { value: "2026-10-18" },
  });
  fireEvent.click(screen.getByRole("button", { name: "查询日期范围" }));
  expect(
    await screen.findByText("在线查询最多 31 天，开始日期必须早于截止日期"),
  ).toBeVisible();
  expect(fetcher).toHaveBeenCalledTimes(1);
});
