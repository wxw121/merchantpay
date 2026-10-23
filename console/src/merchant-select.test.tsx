import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MerchantSelect } from "./merchant-select";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("searches beyond the first hundred merchants and returns the selected ID", async () => {
  const onChange = vi.fn();
  const fetcher = vi.fn(
    async (path: string) =>
      new Response(
        JSON.stringify(
          path.includes("q=merchant-101")
            ? [{ id: "merchant-101", name: "第一百零一家商户" }]
            : Array.from({ length: 100 }, (_, index) => ({
                id: `merchant-${index}`,
                name: `商户 ${index}`,
              })),
        ),
      ),
  );
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MerchantSelect onChange={onChange} aria-label="选择商户" />
    </QueryClientProvider>,
  );
  fireEvent.change(screen.getByRole("combobox", { name: "选择商户" }), {
    target: { value: "merchant-101" },
  });
  await waitFor(() =>
    expect(
      fetcher.mock.calls.some(([path]) => path.includes("q=merchant-101")),
    ).toBe(true),
  );
  fireEvent.click(await screen.findByText("第一百零一家商户"));
  expect(onChange).toHaveBeenCalledWith("merchant-101");
});

it("loads the selected merchant by exact ID when it is outside the current search page", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn(
      async (path: string) =>
        new Response(
          JSON.stringify(
            path === "/api/console/merchants/merchant-101"
              ? { id: "merchant-101", name: "已选远端商户" }
              : [],
          ),
        ),
    ),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MerchantSelect value="merchant-101" />
    </QueryClientProvider>,
  );
  expect(await screen.findByText("已选远端商户")).toBeVisible();
});
