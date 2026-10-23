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
import { App } from "antd";
import { Link, MemoryRouter, Route, Routes } from "react-router-dom";
import { Workspace } from "../context";
import { Cashier, PaymentDetailPage } from "./Payments";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function setup(status = "SUCCEEDED", pending?: Promise<Response>) {
  const fetcher = vi.fn(async (path: string, options?: RequestInit) => {
    if (options?.method === "POST" && pending) return pending;
    const url = new URL(path, "http://localhost");
    const older = url.searchParams.has("journalsCursor");
    const olderRefunds = url.searchParams.has("refundsCursor");
    const id = url.pathname.endsWith("p2") ? "p2" : "p1";
    const journal = older ? "journal-older" : "journal-current";
    return new Response(
      JSON.stringify({
        payment: {
          id,
          merchantId: "merchant-a",
          merchantOrderNo: `order-${id}`,
          amountMinor: "100",
          feeMinor: "0",
          currency: "CNY",
          status,
          refundedMinor: "0",
          reservedMinor: "0",
          refundedFeeMinor: "0",
          channel: "MOCK_A",
          channelReference: `ref-${id}`,
          notificationUrl: null,
          createdAt: "2026-10-01T00:00:00Z",
          updatedAt: "2026-10-01T00:00:00Z",
        },
        refunds: [
          {
            id: olderRefunds ? "refund-older" : "refund-current",
            merchantRefundNo: olderRefunds ? "old-refund" : "current-refund",
            amountMinor: "1",
            refundedFeeMinor: "0",
            status: "SUCCEEDED",
            createdAt: "2026-10-01T00:00:00Z",
          },
        ],
        journals: [
          {
            id: journal,
            eventId: journal,
            sourceType: "PAYMENT",
            createdAt: "2026-10-01T00:00:00Z",
          },
        ],
        entries: [
          {
            id: `${journal}-debit`,
            journalId: journal,
            accountCode: "CHANNEL_RECEIVABLE",
            side: "DEBIT",
            amountMinor: "100",
          },
          {
            id: `${journal}-credit`,
            journalId: journal,
            accountCode: "MERCHANT_PAYABLE",
            side: "CREDIT",
            amountMinor: "100",
          },
        ],
        attempts: [],
        tasks: [],
        conflicts: [],
        nextCursors: {
          journals: older ? null : "older-journals",
          refunds: olderRefunds ? null : "older-refunds",
          attempts: null,
          tasks: null,
          conflicts: null,
        },
      }),
    );
  });
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Workspace.Provider
        value={{
          merchant: "merchant-a",
          session: {
            actor: "review-test",
            merchantId: "merchant-a",
            roles: ["AUDITOR"],
            csrfToken: "test",
            csrfHeader: "X-CSRF-TOKEN",
          },
        }}
      >
        <App>
          <MemoryRouter initialEntries={["/payments/p1"]}>
            <Link to="/payments/p2">打开另一笔支付</Link>
            <Routes>
              <Route path="/payments/:id" element={<PaymentDetailPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </Workspace.Provider>
    </QueryClientProvider>,
  );
  return fetcher;
}

it("shows and disables an in-flight channel query before the server replies", async () => {
  let finish!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    finish = resolve;
  });
  setup("UNKNOWN", pending);
  const button = await screen.findByRole("button", { name: "主动查单" });
  try {
    fireEvent.click(button);
    await waitFor(() => expect(button).toBeDisabled());
    expect(screen.getByText("正在安排查单")).toBeVisible();
  } finally {
    finish(new Response("{}"));
  }
  await waitFor(() => expect(button).not.toBeDisabled());
});

it("turns whole journal pages, keeps each collection's cursor, and can return to the previous page", async () => {
  const fetcher = setup();
  await screen.findByRole("heading", { name: "支付详情" });
  fireEvent.click(screen.getByRole("tab", { name: /账务分录/ }));
  const entries = screen.getByRole("tabpanel", { name: /账务分录/ });
  expect(within(entries).getAllByText("journal-current")).toHaveLength(2);
  fireEvent.click(within(entries).getByRole("button", { name: "凭证下一页" }));
  await waitFor(() =>
    expect(within(entries).getAllByText("journal-older")).toHaveLength(2),
  );
  expect(within(entries).getAllByRole("row")).toHaveLength(3);
  expect(
    within(entries).getByRole("button", { name: "凭证下一页" }),
  ).toBeDisabled();
  expect(String(fetcher.mock.calls.at(-1)?.[0])).toContain(
    "journalsCursor=older-journals",
  );
  fireEvent.click(screen.getByRole("tab", { name: /退款记录/ }));
  const refunds = screen.getByRole("tabpanel", { name: /退款记录/ });
  fireEvent.click(within(refunds).getByRole("button", { name: "退款下一页" }));
  await within(refunds).findByText("old-refund");
  const combined = String(fetcher.mock.calls.at(-1)?.[0]);
  expect(combined).toContain("journalsCursor=older-journals");
  expect(combined).toContain("refundsCursor=older-refunds");
  fireEvent.click(within(refunds).getByRole("button", { name: "退款上一页" }));
  await within(refunds).findByText("current-refund");
  expect(String(fetcher.mock.calls.at(-1)?.[0])).not.toContain(
    "refundsCursor=",
  );
});

it("resets evidence cursors when navigating to a different payment", async () => {
  const fetcher = setup();
  await screen.findByRole("heading", { name: "支付详情" });
  fireEvent.click(screen.getByRole("tab", { name: /账务分录/ }));
  fireEvent.click(screen.getByRole("button", { name: "凭证下一页" }));
  await waitFor(() =>
    expect(String(fetcher.mock.calls.at(-1)?.[0])).toContain("journalsCursor="),
  );
  fireEvent.click(screen.getByRole("link", { name: "打开另一笔支付" }));
  await screen.findAllByText("order-p2");
  const path = String(fetcher.mock.calls.at(-1)?.[0]);
  expect(path).toContain("/payments/p2?");
  expect(path).not.toContain("Cursor=");
});

function setupCashier(initialMerchant: string) {
  const submissions: { path: string; body: unknown }[] = [];
  const payment = {
    id: "cashier-payment",
    merchantId: "merchant-a",
    merchantOrderNo: "created-before-switch",
    amountMinor: "1234",
    feeMinor: "0",
    currency: "CNY",
    status: "SUCCEEDED",
    refundedMinor: "0",
    reservedMinor: "0",
    refundedFeeMinor: "0",
    channel: "MOCK_B",
    channelReference: "cashier-reference",
    notificationUrl: null,
    createdAt: "2026-10-01T00:00:00Z",
    updatedAt: "2026-10-01T00:00:00Z",
  };
  const merchants = [
    { id: "merchant-a", name: "商户 A", enabled: true },
    { id: "merchant-b", name: "商户 B", enabled: true },
  ];
  vi.stubGlobal("fetch", async (path: string, options?: RequestInit) => {
    const url = new URL(path, "http://localhost");
    let body: unknown;
    if (options?.method === "POST") {
      submissions.push({ path, body: JSON.parse(String(options.body)) });
      body = payment;
    } else if (url.pathname === "/api/console/merchants") body = merchants;
    else if (url.pathname.startsWith("/api/console/merchants/"))
      body = merchants.find((merchant) => url.pathname.endsWith(merchant.id));
    else
      body = {
        payment,
        refunds: [],
        journals: [],
        entries: [],
        attempts: [],
        tasks: [],
        conflicts: [],
        nextCursors: {
          refunds: null,
          journals: null,
          attempts: null,
          tasks: null,
          conflicts: null,
        },
      };
    return new Response(JSON.stringify(body));
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const view = (merchant: string) => (
    <QueryClientProvider client={client}>
      <Workspace.Provider
        value={{
          merchant,
          session: {
            actor: "cashier-test",
            merchantId: "",
            roles: ["ADMIN"],
            csrfToken: "test",
            csrfHeader: "X-CSRF-TOKEN",
          },
        }}
      >
        <App>
          <MemoryRouter>
            <Cashier />
          </MemoryRouter>
        </App>
      </Workspace.Provider>
    </QueryClientProvider>
  );
  const rendered = render(view(initialMerchant));
  return {
    submissions,
    switchMerchant: (merchant: string) => rendered.rerender(view(merchant)),
  };
}

it.each([
  { initial: "", next: "merchant-a", expected: "商户 A" },
  { initial: "merchant-a", next: "merchant-b", expected: "商户 B" },
  { initial: "merchant-a", next: "", expected: "" },
])(
  "keeps cashier input and submits the selected merchant after $initial -> $next",
  async ({ initial, next, expected }) => {
    const { submissions, switchMerchant } = setupCashier(initial);
    fireEvent.change(screen.getByLabelText("商户支付业务号"), {
      target: { value: "kept-business-number" },
    });
    fireEvent.change(screen.getByLabelText("支付金额 / CNY 元"), {
      target: { value: "12.34" },
    });
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "模拟渠道" }));
    fireEvent.click(await screen.findByText("模拟渠道 B"));
    fireEvent.mouseDown(screen.getByRole("combobox", { name: "模拟场景" }));
    fireEvent.click(await screen.findByText("持续处理中，可请求关闭"));

    switchMerchant(next);
    const merchant = screen.getByRole("combobox", { name: "商户" });
    if (next) {
      expect(merchant).toBeDisabled();
      await waitFor(() =>
        expect(merchant.closest(".ant-select")).toHaveTextContent(expected),
      );
    } else {
      expect(merchant).toBeEnabled();
      expect(merchant.closest(".ant-select")).toHaveTextContent(
        "搜索商户名称或编号",
      );
      fireEvent.click(screen.getByRole("button", { name: "提交模拟支付" }));
      await screen.findByText("请选择商户");
      expect(submissions).toHaveLength(0);
      fireEvent.mouseDown(merchant);
      fireEvent.click(await screen.findByText("商户 B"));
    }

    expect(screen.getByLabelText("商户支付业务号")).toHaveValue(
      "kept-business-number",
    );
    expect(screen.getByLabelText("支付金额 / CNY 元")).toHaveValue("12.34");
    fireEvent.click(screen.getByRole("button", { name: "提交模拟支付" }));
    await waitFor(() => expect(submissions).toHaveLength(1));
    expect(submissions[0]).toEqual({
      path: `/api/console/payments?merchantId=${next || "merchant-b"}`,
      body: {
        merchantOrderNo: "pending:kept-business-number",
        amountMinor: "1234",
        currency: "CNY",
        channel: "MOCK_B",
      },
    });
  },
);

it("keeps the created cashier result when the workspace merchant changes", async () => {
  const { switchMerchant } = setupCashier("merchant-a");
  fireEvent.change(screen.getByLabelText("支付金额 / CNY 元"), {
    target: { value: "12.34" },
  });
  fireEvent.click(screen.getByRole("button", { name: "提交模拟支付" }));
  await screen.findByText("created-before-switch");
  switchMerchant("merchant-b");
  expect(screen.getByText("created-before-switch")).toBeVisible();
  expect(screen.getByRole("button", { name: "创建另一笔支付" })).toBeVisible();
  expect(screen.queryByLabelText("商户支付业务号")).not.toBeInTheDocument();
});
