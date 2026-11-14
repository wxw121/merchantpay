import type { ReactNode } from "react";
import { render } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App, ConfigProvider } from "antd";
import { MemoryRouter } from "react-router-dom";
import { vi } from "vitest";
import { Workspace } from "../src/context";
import type { Session } from "../src/lib/api";

const operator: Session = {
  actor: "operator",
  merchantId: "",
  roles: ["OPS"],
  csrfToken: "test",
  csrfHeader: "X-CSRF-TOKEN",
};

export function renderWorkspace(
  page: ReactNode,
  { merchant = "merchant-a", session = operator, path = "/" } = {},
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  const workspace = (content: ReactNode, activeMerchant = merchant) => (
    <QueryClientProvider client={client}>
      <Workspace.Provider value={{ merchant: activeMerchant, session }}>
        <ConfigProvider theme={{ token: { motion: false } }}>
          <App>
            <MemoryRouter initialEntries={[path]}>{content}</MemoryRouter>
          </App>
        </ConfigProvider>
      </Workspace.Provider>
    </QueryClientProvider>
  );
  return { ...render(workspace(page)), workspace };
}

export function mockRequests(
  handle: (url: URL, options: RequestInit) => Response | Promise<Response>,
) {
  const fetch = vi.fn((path: string, options: RequestInit = {}) =>
    Promise.resolve(handle(new URL(path, "http://console.test"), options)),
  );
  vi.stubGlobal("fetch", fetch);
  return fetch;
}
