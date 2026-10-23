import { webcrypto } from "node:crypto";
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
import { Reconciliation } from "./Reconciliation";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

function setup() {
  const uploads: FormData[] = [];
  vi.stubGlobal("crypto", webcrypto);
  vi.stubGlobal("fetch", async (path: string, options?: RequestInit) => {
    let body: unknown;
    if (options?.method === "POST") {
      uploads.push(options.body as FormData);
      body = { id: "new-batch" };
    } else if (path.startsWith("/api/console/merchants/"))
      body = { id: "merchant-a", name: "商户 A", enabled: true };
    else if (path.startsWith("/api/console/batches/new-batch"))
      body = {
        batch: {
          id: "new-batch",
          merchantId: "merchant-a",
          channel: "MOCK_A",
          businessDay: "2026-10-18",
          version: 1,
          status: "RECEIVED",
          actualCount: 0,
          expectedCount: 1,
          processedLines: 0,
          snapshotAt: null,
          sha256: "test-digest",
          errorDetail: null,
        },
        issues: [],
        nextCursor: null,
      };
    else
      body = {
        items: [],
        nextCursor: null,
        from: "2026-10-11T00:00:00Z",
        until: "2026-10-18T00:00:00Z",
      };
    return new Response(JSON.stringify(body));
  });
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <Workspace.Provider
        value={{
          merchant: "merchant-a",
          session: {
            actor: "reconciliation-test",
            merchantId: "",
            roles: ["ADMIN"],
            csrfToken: "test",
            csrfHeader: "X-CSRF-TOKEN",
          },
        }}
      >
        <ConfigProvider theme={{ token: { motion: false } }}>
          <App>
            <MemoryRouter>
              <Reconciliation />
            </MemoryRouter>
          </App>
        </ConfigProvider>
      </Workspace.Provider>
    </QueryClientProvider>,
  );
  return uploads;
}

async function openUpload() {
  fireEvent.click(screen.getByRole("button", { name: "上传账单" }));
  const dialog = await screen.findByRole("dialog");
  await waitFor(() => expect(dialog).toBeVisible());
  const input = dialog.querySelector<HTMLInputElement>('input[type="file"]')!;
  return { dialog, input };
}

function billFile(name: string, read?: () => Promise<ArrayBuffer>) {
  const file = new File(["bill evidence\n"], name, { type: "text/csv" });
  Object.defineProperty(file, "arrayBuffer", {
    value:
      read ?? (async () => new TextEncoder().encode("bill evidence\n").buffer),
  });
  return file;
}

it("requires a new file after cancelling and reopening an upload", async () => {
  const uploads = setup();
  const first = await openUpload();
  fireEvent.change(first.input, {
    target: { files: [billFile("cancelled.csv")] },
  });
  fireEvent.click(within(first.dialog).getByRole("button", { name: "Close" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

  const reopened = await openUpload();
  expect(reopened.input.files).toHaveLength(0);
  fireEvent.click(
    within(reopened.dialog).getByRole("button", { name: "上传并开始校验" }),
  );
  await screen.findByText("请选择 CSV 账单");
  expect(uploads).toHaveLength(0);
  await waitFor(() => expect(reopened.dialog).toBeVisible());
});

it("requires a new file after a successful upload and reopening", async () => {
  const uploads = setup();
  const first = await openUpload();
  fireEvent.change(first.input, {
    target: { files: [billFile("completed.csv")] },
  });
  fireEvent.click(
    within(first.dialog).getByRole("button", { name: "上传并开始校验" }),
  );
  await waitFor(() => expect(uploads).toHaveLength(1));
  expect((uploads[0]!.get("file") as File).name).toBe("completed.csv");
  await waitFor(() => expect(first.dialog).not.toBeInTheDocument());
  const drawer = screen.getByRole("dialog", { name: "对账批次与差错" });
  fireEvent.click(within(drawer).getByRole("button", { name: "Close" }));
  await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

  const reopened = await openUpload();
  expect(reopened.input.files).toHaveLength(0);
  fireEvent.click(
    within(reopened.dialog).getByRole("button", { name: "上传并开始校验" }),
  );
  await screen.findByText("请选择 CSV 账单");
  expect(uploads).toHaveLength(1);
});

it("keeps the selected file visible until an in-flight upload finishes", async () => {
  const uploads = setup();
  let finishReading!: (content: ArrayBuffer) => void;
  const reading = new Promise<ArrayBuffer>((resolve) => {
    finishReading = resolve;
  });
  const { dialog, input } = await openUpload();
  fireEvent.change(input, {
    target: { files: [billFile("in-flight.csv", () => reading)] },
  });
  fireEvent.click(
    within(dialog).getByRole("button", { name: "上传并开始校验" }),
  );
  await waitFor(() => expect(input).toBeDisabled());
  expect(within(dialog).queryByRole("button", { name: "Close" })).toBeNull();
  fireEvent.keyDown(dialog, { key: "Escape", keyCode: 27 });
  expect(dialog).toBeVisible();
  expect(input.files?.[0]?.name).toBe("in-flight.csv");

  finishReading(new TextEncoder().encode("bill evidence\n").buffer);
  await waitFor(() => expect(uploads).toHaveLength(1));
  expect((uploads[0]!.get("file") as File).name).toBe("in-flight.csv");
});
