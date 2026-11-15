import { afterEach, expect, it, vi } from "vitest";
import {
  act,
  cleanup,
  fireEvent,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { mockRequests, renderWorkspace } from "../../tests/workspace";
import { History } from "./History";

const archive = {
  id: "archive-1",
  merchantId: "merchant-a",
  paymentId: "payment/1",
  logicalBucket: 22,
  physicalShard: 2,
  routeEpoch: 8,
  sourceHash: "source-digest",
  objectHash: "object-digest",
  factCount: 2,
  objectBytes: 582,
  archivedBy: "operator",
  reason: "周期归档",
  createdAt: "2026-10-01T00:00:00Z",
  tier: "COLD",
};
const snapshot = {
  archive,
  facts: [
    {
      kind: "PAYMENT",
      factId: "payment/1",
      occurredMicros: 1790812800000000,
      payloadHash: "payment-digest",
      payloadJson: '{"status":"SUCCEEDED"}',
    },
    {
      kind: "EVENT",
      factId: "event/1",
      occurredMicros: 1790812800000001,
      payloadHash: "event-digest",
      payloadJson: '{"type":"PAYMENT_SUCCEEDED"}',
    },
  ],
  consistency: "VERIFIED",
};
const range = {
  from: "2026-09-25T00:00:00Z",
  until: "2026-10-02T00:00:00Z",
};
const recentPath = "/api/console/history/payments";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("preserves the archive window across cursor pages and opens a verified object", async () => {
  const fetch = mockRequests((url) => {
    if (url.pathname === recentPath) {
      return Response.json(
        url.searchParams.has("cursor")
          ? { ...range, items: [], nextCursor: null }
          : { ...range, items: [archive], nextCursor: "second page" },
      );
    }
    if (url.pathname === recentPath + "/payment%2F1")
      return Response.json(snapshot);
    throw new Error(`Unexpected request: ${url}`);
  });
  renderWorkspace(<History />);

  await screen.findByText("payment/1");
  fireEvent.click(screen.getByRole("button", { name: "下一页" }));
  await waitFor(() =>
    expect(screen.queryByText("payment/1")).not.toBeInTheDocument(),
  );
  expect(fetch).toHaveBeenLastCalledWith(
    recentPath +
      "?merchantId=merchant-a&from=2026-09-25T00%3A00%3A00Z&until=2026-10-02T00%3A00%3A00Z&cursor=second+page",
    expect.anything(),
  );
  expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "返回最新" }));
  fireEvent.click(await screen.findByRole("button", { name: "核对对象" }));

  expect(await screen.findByText("object-digest")).toBeVisible();
  expect(screen.getByText("source-digest")).toBeVisible();
  expect(
    screen.getByRole("link", { name: "查看当前权威支付" }),
  ).toHaveAttribute("href", "/payments/payment%2F1");
  const eventRow = screen.getByText("event/1").closest("tr")!;
  fireEvent.click(
    within(eventRow).getByRole("button", { name: /expand row/i }),
  );
  expect(screen.getByText('{"type":"PAYMENT_SUCCEEDED"}')).toBeVisible();
});

it("ignores a delayed archive lookup after switching merchants", async () => {
  let resolve!: (response: Response) => void;
  const pending = new Promise<Response>((finish) => {
    resolve = finish;
  });
  const fetch = mockRequests((url) => {
    if (url.pathname === recentPath)
      return Response.json({ ...range, items: [], nextCursor: null });
    if (url.pathname === recentPath + "/by-business/order%2F1") return pending;
    throw new Error(`Unexpected request: ${url}`);
  });
  const ui = renderWorkspace(<History />);
  fireEvent.click(screen.getByRole("radio", { name: "商户业务号" }));
  const input = screen.getByRole("textbox", { name: "商户业务号" });
  fireEvent.change(input, { target: { value: " order/1 " } });
  fireEvent.keyDown(input, { key: "Enter", code: "Enter", keyCode: 13 });
  expect(fetch).toHaveBeenCalledWith(
    recentPath + "/by-business/order%2F1?merchantId=merchant-a",
    expect.anything(),
  );
  ui.rerender(ui.workspace(<History />, "merchant-b"));
  await act(async () => {
    resolve(Response.json(snapshot));
  });

  expect(input).toHaveValue("");
  expect(screen.getByRole("button", { name: "核对归档" })).toBeDisabled();
  expect(screen.queryByText("object-digest")).toBeNull();
});

it("requires a matching archived event and explicit stopped writers before retiring it", async () => {
  const writes: { path: string; body: unknown }[] = [];
  const fetch = mockRequests((url, options) => {
    if (url.pathname === recentPath)
      return Response.json({ ...range, items: [archive], nextCursor: null });
    if (url.pathname === recentPath + "/payment%2F1")
      return Response.json(snapshot);
    if (
      url.pathname === recentPath + "/payment%2F1/events/event%2F1/retire" &&
      options.method === "POST"
    ) {
      writes.push({
        path: url.pathname,
        body: JSON.parse(String(options.body)),
      });
      return Response.json({ eventId: "event/1", state: "COLD" });
    }
    throw new Error(`Unexpected request: ${url}`);
  });
  renderWorkspace(<History />);
  fireEvent.click(await screen.findByRole("button", { name: "核对对象" }));
  const input = await screen.findByRole("textbox", { name: "待退役事件号" });
  const retire = screen.getByRole("button", { name: "按事件号退役" });
  fireEvent.change(input, { target: { value: "payment/1" } });
  expect(retire).toBeDisabled();
  fireEvent.change(input, { target: { value: " event/1 " } });
  fireEvent.click(retire);
  const dialog = within(
    await screen.findByRole("dialog", { name: "退役热事件" }),
  );
  fireEvent.change(dialog.getByLabelText("退役原因"), {
    target: { value: "已核对归档与回执" },
  });
  fireEvent.click(dialog.getByRole("button", { name: /确认退役/ }));
  await waitFor(() => expect(dialog.getByText("须确认已停写")).toBeVisible());
  expect(
    fetch.mock.calls.filter(([, options]) => options?.method === "POST"),
  ).toHaveLength(0);
  fireEvent.click(
    dialog.getByRole("checkbox", { name: "我已确认来源停写并排空" }),
  );
  fireEvent.click(dialog.getByRole("button", { name: /确认退役/ }));

  expect(await screen.findByText("事件 event/1 已退役为 COLD")).toBeVisible();
  expect(writes).toEqual([
    {
      path: recentPath + "/payment%2F1/events/event%2F1/retire",
      body: {
        merchantId: "merchant-a",
        reason: "已核对归档与回执",
        writersStopped: true,
      },
    },
  ]);
});
