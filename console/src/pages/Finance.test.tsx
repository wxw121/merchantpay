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
import { MemoryRouter } from "react-router-dom";
import { Workspace } from "../context";
import { Finance } from "./Finance";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
const proposal = {
  id: "adj-one",
  merchantId: "merchant-a",
  version: 7,
  status: "DRAFT",
  direction: "INCREASE",
  amountMinor: "900000001",
  proposer: "one",
  lastEditor: "one",
  reason: "核对渠道",
  reviews: [],
};
function setup(
  actor: string,
  roles: string[],
  fail = false,
  fixture: Record<string, unknown> = proposal,
  tab = "adjustments",
) {
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  vi.stubGlobal("fetch", async (path: string, options?: RequestInit) => {
    if (options?.method === "POST") {
      writes.push({
        path,
        body: JSON.parse(String(options.body)) as Record<string, unknown>,
      });
      return new Response(
        JSON.stringify(
          fail
            ? {
                code: "FINANCE_CONFLICT",
                message: "版本已变化，请刷新后重新核对",
              }
            : { ...proposal, status: "APPROVED" },
        ),
        { status: fail ? 409 : 200 },
      );
    }
    return new Response(
      JSON.stringify(path.includes("/adj-one") ? fixture : [fixture]),
    );
  });
  render(
    <QueryClientProvider
      client={
        new QueryClient({
          defaultOptions: { queries: { retry: false, gcTime: 0 } },
        })
      }
    >
      <Workspace.Provider
        value={{
          merchant: "merchant-a",
          session: {
            actor,
            roles,
            merchantId: "",
            csrfToken: "test",
            csrfHeader: "X-CSRF-TOKEN",
          },
        }}
      >
        <ConfigProvider theme={{ token: { motion: false } }}>
          <App>
            <MemoryRouter initialEntries={[`/finance?tab=${tab}`]}>
              <Finance />
            </MemoryRouter>
          </App>
        </ConfigProvider>
      </Workspace.Provider>
    </QueryClientProvider>,
  );
  return writes;
}
it("allows an evidence-only investigation for an unknown payment root", async () => {
  const writes = setup(
    "one",
    ["FINANCE"],
    false,
    {
      id: "adj-one",
      merchantId: "merchant-a",
      version: 1,
      status: "OPEN",
      paymentId: null,
      issue: { issueType: "CHANNEL_ONLY", aggregateId: "external-id" },
    },
    "cases",
  );
  fireEvent.click(await screen.findByRole("button", { name: "adj-one" }));
  fireEvent.click(await screen.findByRole("button", { name: "指派与调查" }));
  const dialogs = await screen.findAllByRole("dialog");
  const modal = dialogs.at(-1)!;
  fireEvent.change(within(modal).getByLabelText("调查证据"), {
    target: { value: "已核对：渠道重复外部行，无本地支付" },
  });
  fireEvent.click(within(modal).getByRole("button", { name: /提\s*交/ }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]!.body).toMatchObject({
    expectedVersion: 1,
    evidence: "已核对：渠道重复外部行，无本地支付",
  });
});
it("shows exact string amounts and prevents self approval", async () => {
  setup("one", ["FINANCE"]);
  fireEvent.click(await screen.findByRole("button", { name: "adj-one" }));
  const drawer = await screen.findByRole("dialog");
  expect(
    within(drawer).queryByRole("button", { name: "审批方案" }),
  ).not.toBeInTheDocument();
  expect(screen.getAllByText("9,000,000.01").length).toBeGreaterThan(0);
});
it("refreshes independent approval while the detail drawer stays open", async () => {
  const fixture = { ...proposal };
  setup("two", ["FINANCE"], false, fixture);
  fireEvent.click(await screen.findByRole("button", { name: "adj-one" }));
  const drawer = await screen.findByRole("dialog");
  await within(drawer).findByRole("button", { name: "审批方案" });
  fixture.status = "APPROVED";
  fireEvent.click(within(drawer).getByRole("button", { name: "刷新详情" }));
  expect(
    await within(drawer).findByRole("button", { name: "执行已批准方案" }),
  ).toBeVisible();
});
it("submits the displayed version and keeps a version conflict visible", async () => {
  const writes = setup("two", ["FINANCE"], true);
  fireEvent.click(await screen.findByRole("button", { name: "adj-one" }));
  fireEvent.click(await screen.findByRole("button", { name: "审批方案" }));
  const dialogs = await screen.findAllByRole("dialog");
  const modal = dialogs[dialogs.length - 1]!;
  fireEvent.change(within(modal).getByLabelText("操作原因"), {
    target: { value: "已独立核对" },
  });
  fireEvent.click(within(modal).getByRole("button", { name: /提\s*交/ }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]!.body).toMatchObject({
    expectedVersion: 7,
    approve: true,
    reason: "已独立核对",
  });
  expect(
    await within(modal).findByText("版本已变化，请刷新后重新核对"),
  ).toBeVisible();
});
it("keeps finance mutation controls unavailable to ADMIN alone", async () => {
  setup("admin", ["ADMIN"]);
  fireEvent.click(await screen.findByRole("button", { name: "adj-one" }));
  const drawer = await screen.findByRole("dialog");
  expect(
    within(drawer).queryByRole("button", { name: "修改方案" }),
  ).not.toBeInTheDocument();
  expect(
    within(drawer).queryByRole("button", { name: "审批方案" }),
  ).not.toBeInTheDocument();
});
