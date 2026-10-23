import { afterEach, expect, it, vi } from "vitest";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App, ConfigProvider } from "antd";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { Workspace } from "../context";
import { PaymentDetailPage } from "./Payments";
import { Reconciliation } from "./Reconciliation";
import { Records } from "./Records";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it.each(["close", "retry", "replay"])(
  "shows immediate pending feedback and prevents duplicate %s",
  async (action) => {
    let finish!: (response: Response) => void;
    const pending = new Promise<Response>((resolve) => {
      finish = resolve;
    });
    const batch = {
      id: "batch-1",
      merchantId: "merchant-a",
      businessDay: "2026-10-19",
      version: 1,
      status: "FAILED",
      actualCount: 1,
      expectedCount: 1,
      actualAmountMinor: "100",
      expectedAmountMinor: "100",
      processedLines: 1,
    };
    vi.stubGlobal("fetch", async (path: string, options?: RequestInit) => {
      if (options?.method === "POST") return pending;
      let body: unknown;
      if (path.startsWith("/api/console/payments/"))
        body = {
          payment: {
            id: "pending",
            merchantId: "merchant-a",
            merchantOrderNo: "pending-1",
            amountMinor: "100",
            feeMinor: "0",
            currency: "CNY",
            status: "UNKNOWN",
            refundedMinor: "0",
            reservedMinor: "0",
            refundedFeeMinor: "0",
            channel: "MOCK_A",
            createdAt: "2026-10-19T00:00:00Z",
            updatedAt: "2026-10-19T00:00:00Z",
          },
          refunds: [],
          journals: [],
          entries: [],
          tasks: [],
          conflicts: [],
          attempts: [],
          nextCursors: {},
        };
      else if (path.startsWith("/api/console/batches/batch-1"))
        body = { batch, issues: [], nextCursor: null };
      else if (path.startsWith("/api/console/merchants/"))
        body = { id: "merchant-a", name: "商户A", enabled: true };
      else
        body = {
          items:
            action === "retry"
              ? [batch]
              : [
                  {
                    id: "task-1",
                    merchantId: "merchant-a",
                    kind: "CHANNEL_PAYMENT",
                    status: "DEAD",
                    attempts: 10,
                    dueAt: "2026-10-19T00:00:00Z",
                    createdAt: "2026-10-19T00:00:00Z",
                  },
                ],
          nextCursor: null,
          from: "2026-10-01T00:00:00Z",
          until: "2026-10-20T00:00:00Z",
        };
      return new Response(JSON.stringify(body));
    });
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false, gcTime: 0 } },
    });
    render(
      <QueryClientProvider client={client}>
        <Workspace.Provider
          value={{
            merchant: "merchant-a",
            session: {
              actor: "feedback",
              merchantId: "merchant-a",
              roles: ["ADMIN"],
              csrfToken: "test",
              csrfHeader: "X-CSRF-TOKEN",
            },
          }}
        >
          <ConfigProvider theme={{ token: { motion: false } }}>
            <App>
              <MemoryRouter initialEntries={["/payments/pending"]}>
                <Routes>
                  <Route
                    path="/payments/:id"
                    element={
                      action === "close" ? (
                        <PaymentDetailPage />
                      ) : action === "retry" ? (
                        <Reconciliation />
                      ) : (
                        <Records resource="tasks" />
                      )
                    }
                  />
                </Routes>
              </MemoryRouter>
            </App>
          </ConfigProvider>
        </Workspace.Provider>
      </QueryClientProvider>,
    );
    try {
      let button: HTMLElement;
      let waiting: string;
      if (action === "close") {
        fireEvent.click(
          await screen.findByRole("button", { name: "请求关闭" }),
        );
        const dialog = await screen.findByRole("dialog", {
          name: "请求关闭支付",
        });
        await waitFor(() => expect(dialog).toBeVisible());
        button = within(dialog).getByRole("button", { name: /^(OK|确\s*定)$/ });
        waiting = "正在提交关闭请求";
      } else if (action === "retry") {
        fireEvent.click(
          await screen.findByRole("button", { name: /2026-10-19/ }),
        );
        button = await screen.findByRole("button", { name: "从检查点续跑" });
        waiting = "正在安排续跑";
      } else {
        button = await screen.findByRole("button", { name: "重放" });
        waiting = "正在安排重放";
      }
      fireEvent.click(button);
      await waitFor(() => expect(button).toBeDisabled());
      expect(screen.getByText(waiting)).toBeVisible();
    } finally {
      finish(new Response("{}"));
      client.clear();
    }
  },
);
