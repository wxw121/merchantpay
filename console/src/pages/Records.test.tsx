import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, screen, within } from "@testing-library/react";
import { mockRequests, renderWorkspace } from "../../tests/workspace";
import { Records } from "./Records";

const range = {
  from: "2026-11-08T00:00:00Z",
  until: "2026-11-14T00:00:00Z",
  nextCursor: null,
};
const merchant = {
  actor: "merchant-user",
  merchantId: "merchant-a",
  roles: ["MERCHANT"],
  csrfToken: "test",
  csrfHeader: "X-CSRF-TOKEN",
};
const tasks = [
  {
    id: "notify-1",
    merchantId: "merchant-a",
    kind: "PAYMENT_SUCCEEDED",
    status: "DEAD",
  },
  {
    id: "channel-1",
    merchantId: "merchant-a",
    kind: "CHANNEL_PAYMENT",
    status: "DEAD",
  },
  {
    id: "running-1",
    merchantId: "merchant-a",
    kind: "PAYMENT_SUCCEEDED",
    status: "RUNNING",
  },
];

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

it("allows replay of a merchant's completed notification and restricts channel or active tasks", async () => {
  const fetch = mockRequests((url, options) => {
    expect(url.searchParams.get("merchantId")).toBe("merchant-a");
    if (url.pathname === "/api/console/tasks" && !options.method)
      return Response.json({ ...range, items: tasks });
    if (
      url.pathname === "/api/console/tasks/notify-1/replay" &&
      options.method === "POST"
    )
      return Response.json({ id: "notify-1" });
    if (url.pathname === "/api/console/notifications")
      return Response.json({
        ...range,
        items: [
          {
            id: "delivery-1",
            eventId: "notice-1",
            status: "DELIVERED",
            httpStatus: 200,
          },
        ],
      });
    throw new Error(`Unexpected request: ${url}`);
  });
  renderWorkspace(<Records resource="tasks" />, { session: merchant });

  const notification = (await screen.findByText("notify-1")).closest("tr")!;
  const channel = screen.getByText("channel-1").closest("tr")!;
  const active = screen.getByText("running-1").closest("tr")!;
  expect(within(channel).queryByRole("button", { name: /重\s*放/ })).toBeNull();
  expect(within(active).queryByRole("button", { name: /重\s*放/ })).toBeNull();
  fireEvent.click(
    within(notification).getByRole("button", { name: /重\s*放/ }),
  );
  expect(await screen.findByText("已安排重放")).toBeVisible();
  expect(fetch).toHaveBeenCalledWith(
    "/api/console/tasks/notify-1/replay?merchantId=merchant-a",
    expect.objectContaining({ method: "POST" }),
  );
  fireEvent.click(screen.getByRole("tab", { name: "通知投递记录" }));
  expect(await screen.findByText("notice-1")).toBeVisible();
  expect(screen.getByText("200")).toBeVisible();
});

it("offers the ZIP download only for complete, unexpired exports", async () => {
  mockRequests((url) => {
    expect(url.pathname).toBe("/api/console/exports");
    return Response.json({
      ...range,
      items: [
        {
          id: "ready",
          resource: "payments",
          status: "COMPLETE",
          expiresAt: "2099-01-01T00:00:00Z",
        },
        {
          id: "expired",
          resource: "refunds",
          status: "COMPLETE",
          expiresAt: "2000-01-01T00:00:00Z",
        },
        {
          id: "pending",
          resource: "journals",
          status: "RUNNING",
          expiresAt: null,
        },
      ],
    });
  });
  renderWorkspace(<Records resource="exports" />);

  const ready = (await screen.findByText("ready")).closest("tr")!;
  const expired = screen.getByText("expired").closest("tr")!;
  const pending = screen.getByText("pending").closest("tr")!;
  expect(within(ready).getByRole("link", { name: "下载 ZIP" })).toHaveAttribute(
    "href",
    "/api/console/exports/ready/download",
  );
  expect(within(expired).getByText("下载 ZIP").closest("a")).toHaveAttribute(
    "aria-disabled",
    "true",
  );
  expect(within(pending).getByText("下载 ZIP").closest("a")).toHaveAttribute(
    "aria-disabled",
    "true",
  );
});
