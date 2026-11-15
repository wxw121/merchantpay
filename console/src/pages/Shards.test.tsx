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
import { Workspace } from "../context";
import { Shards } from "./Shards";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function setup(
  roles = ["OPS"],
  merchantId = "",
  fail = false,
  existingPlan = false,
  reversePlan = false,
) {
  const fixture = {
    routes: [{ bucket: 2, physicalShard: 0, epoch: 7 }],
    physicalShards: [
      { id: 0, name: "core-0", state: "CURRENT_CORE" },
      { id: 1, name: "core-1", state: "UNCONFIGURED" },
    ],
    executionEnabled: false,
  };
  const plans = existingPlan
    ? [
        {
          id: "plan-one",
          requestKey: "key-one",
          bucket: 2,
          sourceShard: reversePlan ? 1 : 0,
          targetShard: reversePlan ? 0 : 1,
          sourceEpoch: 7,
          version: 9,
          state: "PLANNED",
          proposer: "ops",
          reason: "准备",
          createdAt: "2026-11-02T00:00:00Z",
          updatedAt: "2026-11-02T00:00:00Z",
        },
      ]
    : [];
  const writes: { path: string; body: Record<string, unknown> }[] = [];
  const reads: string[] = [];
  vi.stubGlobal("fetch", async (path: string, options?: RequestInit) => {
    if (options?.method === "POST") {
      writes.push({
        path,
        body: JSON.parse(String(options.body)) as Record<string, unknown>,
      });
      return new Response(
        JSON.stringify(
          fail
            ? { code: "SHARD_PLAN_CONFLICT", message: "计划版本已变化" }
            : { id: "saved-plan", state: "PLANNED" },
        ),
        { status: fail ? 409 : 200 },
      );
    }
    reads.push(path);
    return new Response(
      JSON.stringify(
        path.includes("/plans")
          ? path.includes("state=CANCELLED")
            ? []
            : plans
          : fixture,
      ),
    );
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
            actor: "ops",
            roles,
            merchantId,
            csrfHeader: "X-CSRF-TOKEN",
            csrfToken: "test",
          },
        }}
      >
        <ConfigProvider theme={{ token: { motion: false } }}>
          <App>
            <Shards />
          </App>
        </ConfigProvider>
      </Workspace.Provider>
    </QueryClientProvider>,
  );
  return { fixture, writes, reads, client };
}

it("shows planning capability and keeps the originally reviewed epoch after refresh", async () => {
  const { fixture, writes, client } = setup();
  expect(await screen.findByText(/搬迁和回迁按计划与停写证据/)).toBeVisible();
  const prepare = await screen.findByRole("button", { name: "为桶2准备迁移" });
  await waitFor(() => expect(prepare).toBeEnabled());
  fireEvent.click(prepare);
  fixture.routes[0]!.epoch = 8;
  await client.invalidateQueries({ queryKey: ["v3", "shards", "directory"] });
  const modal = await screen.findByRole("dialog");
  fireEvent.change(within(modal).getByLabelText("操作原因"), {
    target: { value: "核对后准备" },
  });
  fireEvent.click(within(modal).getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]!.body).toMatchObject({
    bucket: 2,
    targetShard: 1,
    expectedEpoch: 7,
    reason: "核对后准备",
  });
  expect(writes[0]!.body.requestKey).toEqual(expect.any(String));
});

it("submits one reverse stage only after stopped-writer confirmation", async () => {
  const { writes } = setup(["OPS"], "", false, true, true);
  fireEvent.click(await screen.findByRole("button", { name: "回迁下一阶段" }));
  const modal = await screen.findByRole("dialog");
  fireEvent.change(within(modal).getByLabelText("操作原因"), {
    target: { value: "停写后回迁" },
  });
  fireEvent.click(within(modal).getByText("我已确认全部来源停写并排空"));
  fireEvent.click(within(modal).getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    path: "/api/console/shards/plans/plan-one/reverse/next",
    body: { expectedVersion: 9, reason: "停写后回迁", writersStopped: true },
  });
});

it("submits the displayed cancellation version and leaves conflicts visible", async () => {
  const { writes } = setup(["OPS"], "", true, true);
  fireEvent.click(
    await screen.findByRole("button", { name: "取消计划plan-one" }),
  );
  const modal = await screen.findByRole("dialog");
  fireEvent.change(within(modal).getByLabelText("操作原因"), {
    target: { value: "撤销准备" },
  });
  fireEvent.click(within(modal).getByRole("button", { name: /保\s*存/ }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    path: "/api/console/shards/plans/plan-one/cancel",
    body: { expectedVersion: 9 },
  });
  await waitFor(() => expect(screen.getByText("计划版本已变化")).toBeVisible());
  expect(screen.getByRole("dialog")).toBeVisible();
});

it("keeps buckets with open plans disabled while showing cancelled history", async () => {
  const { reads } = setup(["OPS"], "", false, true);
  const prepare = await screen.findByRole("button", { name: "为桶2准备迁移" });
  await waitFor(() => expect(prepare).toBeDisabled());
  fireEvent.mouseDown(screen.getByRole("combobox", { name: "计划状态" }));
  fireEvent.click(await screen.findByText("已取消"));
  await waitFor(() =>
    expect(reads).toContain("/api/console/shards/plans?state=CANCELLED"),
  );
  expect(prepare).toBeDisabled();
});

it("tenant-scoped operators cannot fetch platform routing data", () => {
  const { reads } = setup(["OPS"], "merchant-a");
  expect(screen.getByText("需要平台管理员或运维权限")).toBeVisible();
  expect(reads).toHaveLength(0);
});
