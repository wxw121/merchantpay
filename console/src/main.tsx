import React from "react";
import ReactDOM from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ConfigProvider, App as AntApp } from "antd";
import zhCN from "antd/locale/zh_CN";
import App from "./App";
import "./style.css";
const client = new QueryClient({
  defaultOptions: {
    queries: { retry: false, staleTime: 2_000, refetchOnWindowFocus: true },
  },
});
ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <QueryClientProvider client={client}>
      <ConfigProvider
        locale={zhCN}
        theme={{
          token: {
            colorPrimary: "#002FA7",
            borderRadius: 3,
            fontFamily:
              '"Helvetica Neue", Arial, "PingFang SC", "Microsoft YaHei", sans-serif',
            colorBgLayout: "#F7F7F8",
          },
        }}
      >
        <AntApp>
          <BrowserRouter>
            <App />
          </BrowserRouter>
        </AntApp>
      </ConfigProvider>
    </QueryClientProvider>
  </React.StrictMode>,
);
