import { lazy, Suspense, useState, useEffect } from "react";
import { Link, NavLink, Route, Routes, useLocation } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { App as AntApp, Button, Result, Skeleton, Tag } from "antd";
import {
  DashboardOutlined,
  TransactionOutlined,
  BookOutlined,
  ReconciliationOutlined,
  TeamOutlined,
  ScheduleOutlined,
  AuditOutlined,
  DownloadOutlined,
  LogoutOutlined,
  MenuOutlined,
} from "@ant-design/icons";
import { api, ApiError, setCsrf, setActionConfirmation } from "./lib/api";
import type { Session } from "./lib/api";
import { MerchantSelect } from "./merchant-select";
import { Workspace } from "./context";
import { ErrorNotice } from "./components";
const Overview = lazy(() =>
  import("./pages/Overview").then((page) => ({ default: page.Overview })),
);
const Payments = lazy(() =>
  import("./pages/Payments").then((page) => ({ default: page.Payments })),
);
const PaymentDetailPage = lazy(() =>
  import("./pages/Payments").then((page) => ({
    default: page.PaymentDetailPage,
  })),
);
const Cashier = lazy(() =>
  import("./pages/Payments").then((page) => ({ default: page.Cashier })),
);
const Reconciliation = lazy(() =>
  import("./pages/Reconciliation").then((page) => ({
    default: page.Reconciliation,
  })),
);
const Records = lazy(() =>
  import("./pages/Records").then((page) => ({ default: page.Records })),
);
const Finance = lazy(() =>
  import("./pages/Finance").then((page) => ({ default: page.Finance })),
);
const Merchants = lazy(() =>
  import("./pages/Merchants").then((page) => ({ default: page.Merchants })),
);
const Operations = lazy(() =>
  import("./pages/V2").then((p) => ({ default: p.Operations })),
);
const ReadModel = lazy(() =>
  import("./pages/V2").then((p) => ({ default: p.ReadModel })),
);
const Balances = lazy(() =>
  import("./pages/V2").then((p) => ({ default: p.Balances })),
);
const SecurityCenter = lazy(() =>
  import("./pages/V2").then((p) => ({ default: p.SecurityCenter })),
);
const navigation = [
  {
    path: "/operations",
    label: "运行与渠道",
    icon: <ScheduleOutlined aria-hidden="true" />,
  },
  {
    path: "/read-model",
    label: "独立查询投影",
    icon: <BookOutlined aria-hidden="true" />,
  },
  {
    path: "/balances",
    label: "账务检查点",
    icon: <BookOutlined aria-hidden="true" />,
  },
  {
    path: "/security",
    label: "安全中心",
    icon: <AuditOutlined aria-hidden="true" />,
  },
  {
    path: "/finance",
    label: "调账与结算",
    icon: <BookOutlined aria-hidden="true" />,
  },
  {
    path: "/",
    label: "业务概览",
    icon: <DashboardOutlined aria-hidden="true" />,
  },
  {
    path: "/payments",
    label: "支付与退款",
    icon: <TransactionOutlined aria-hidden="true" />,
  },
  {
    path: "/journals",
    label: "账务凭证",
    icon: <BookOutlined aria-hidden="true" />,
  },
  {
    path: "/reconciliation",
    label: "对账与差错",
    icon: <ReconciliationOutlined aria-hidden="true" />,
  },
  {
    path: "/merchants",
    label: "商户与接入",
    icon: <TeamOutlined aria-hidden="true" />,
  },
  {
    path: "/tasks",
    label: "任务与通知",
    icon: <ScheduleOutlined aria-hidden="true" />,
  },
  {
    path: "/exports",
    label: "导出文件",
    icon: <DownloadOutlined aria-hidden="true" />,
  },
  {
    path: "/audit",
    label: "操作审计",
    icon: <AuditOutlined aria-hidden="true" />,
  },
];
export default function App() {
  const session = useQuery({
    queryKey: ["session"],
    queryFn: async () => {
      const value = await api<Session>("/api/console/session");
      setCsrf(value);
      return value;
    },
    refetchInterval: 240_000,
  });
  const [selected, setSelected] = useState("");
  const [mobileMenu, setMobileMenu] = useState(false);
  const { message, modal } = AntApp.useApp();
  useEffect(() => {
    setActionConfirmation(
      (action) =>
        new Promise<boolean>((resolve) => {
          let text = action.body;
          try {
            text = JSON.stringify(JSON.parse(action.body), null, 2);
          } catch {
            /* Empty request. */
          }
          modal.confirm({
            title: "确认本次操作",
            content: (
              <>
                <p>
                  {action.method} {action.path}
                </p>
                <pre
                  style={{
                    maxHeight: 260,
                    overflow: "auto",
                    whiteSpace: "pre-wrap",
                  }}
                >
                  {text || "无请求正文"}
                </pre>
                <p>
                  确认有效至{" "}
                  {new Date(action.expiresAt).toLocaleTimeString("zh-CN")}
                  。页面内容变化后请取消并重新核对。
                </p>
              </>
            ),
            okText: "确认执行",
            cancelText: "取消",
            onOk: () => {
              resolve(true);
            },
            onCancel: () => {
              resolve(false);
            },
          });
        }),
    );
    return () => setActionConfirmation(null);
  }, [modal]);
  const location = useLocation();
  if (session.isPending)
    return (
      <div className="startup">
        <Skeleton active paragraph={{ rows: 5 }} />
      </div>
    );
  if (session.error instanceof ApiError && session.error.status === 401)
    return (
      <main className="login-page">
        <div className="wordmark">
          M<span>erchant</span>Pay<span className="brand-dot">.</span>
        </div>
        <div className="login-grid">
          <div>
            <Tag>V2 · 支付与账务工作台</Tag>
            <h1>
              每笔交易，
              <br />
              都有据可查。
            </h1>
            <p>
              从支付受理到退款、记账与对账，
              <br />
              在同一工作台追踪完整业务记录。
            </p>
            <Button
              size="large"
              type="primary"
              href="/oauth2/authorization/keycloak"
            >
              登录工作台
            </Button>
          </div>
          <aside>
            <div className="login-rule">01 / 支付</div>
            <div className="login-rule">02 / 账务</div>
            <div className="login-rule">03 / 对账</div>
            <p>
              本地演示使用独立模拟渠道。
              <br />
              交易不产生真实资金流转。
            </p>
          </aside>
        </div>
        <footer>商户支付与对账平台</footer>
      </main>
    );
  if (session.error || !session.data)
    return (
      <div className="startup">
        <ErrorNotice
          error={session.error ?? new Error("无法加载会话")}
          retry={() => void session.refetch()}
        />
      </div>
    );
  const identity = session.data;
  const merchant = identity.merchantId || selected;
  const roleNames: Record<string, string> = {
    ADMIN: "平台管理员",
    MERCHANT: "商户",
    FINANCE: "财务",
    OPS: "运维",
    AUDITOR: "审计员",
  };
  if (identity.roles.length === 0)
    return (
      <Result
        status="403"
        title="账号尚未授权"
        subTitle="请由平台管理员为账号分配角色及商户范围。"
      />
    );
  return (
    <Workspace value={{ session: identity, merchant }}>
      <div className="app-shell">
        <aside className={`sidebar ${mobileMenu ? "sidebar-open" : ""}`}>
          <Link className="wordmark" to="/">
            MerchantPay<span className="brand-dot">.</span>
          </Link>
          <div className="sidebar-label">业务工作台</div>
          <nav aria-label="主导航">
            {navigation.map((item) => (
              <NavLink
                key={item.path}
                to={item.path}
                aria-label={item.label}
                end={item.path === "/"}
                onClick={() => setMobileMenu(false)}
              >
                <span aria-hidden="true">{item.icon}</span>
                <span>{item.label}</span>
              </NavLink>
            ))}
          </nav>
          <div className="sidebar-bottom">
            <Tag>V1 · 模拟渠道</Tag>
            <p>支付 · 账务 · 对账</p>
          </div>
        </aside>
        <div className="workspace">
          <header className="topbar">
            <div className="topbar-left">
              <Button
                className="mobile-toggle"
                type="text"
                aria-label="打开导航"
                icon={<MenuOutlined aria-hidden="true" />}
                onClick={() => setMobileMenu(!mobileMenu)}
              />
              <span className="breadcrumb">
                工作台 <span>/</span>{" "}
                {navigation.find(
                  (item) =>
                    item.path !== "/" &&
                    location.pathname.startsWith(item.path),
                )?.label ?? "业务概览"}
              </span>
            </div>
            <div className="topbar-right">
              <MerchantSelect
                aria-label="当前商户"
                value={merchant}
                onChange={setSelected}
                disabled={!!identity.merchantId}
                allowAll={!identity.merchantId}
                style={{ width: 190 }}
              />
              <span className="role-name">
                {identity.roles
                  .map((role) => roleNames[role] ?? role)
                  .join(" / ")}
              </span>
              <Button
                type="text"
                icon={<LogoutOutlined aria-hidden="true" />}
                aria-label="退出登录"
                onClick={() => {
                  const form = document.createElement("form");
                  form.method = "POST";
                  form.action = "/logout";
                  const input = document.createElement("input");
                  input.type = "hidden";
                  input.name = "_csrf";
                  input.value = identity.csrfToken;
                  form.append(input);
                  document.body.append(form);
                  form.submit();
                  void message.info("正在退出");
                }}
              />
            </div>
          </header>
          <main className="main-content">
            <Suspense fallback={<Skeleton active paragraph={{ rows: 5 }} />}>
              <Routes>
                <Route path="/" element={<Overview />} />
                <Route path="/payments" element={<Payments />} />
                <Route path="/payments/:id" element={<PaymentDetailPage />} />
                <Route path="/cashier" element={<Cashier />} />
                <Route
                  path="/journals"
                  element={<Records resource="journals" />}
                />
                <Route path="/tasks" element={<Records resource="tasks" />} />
                <Route
                  path="/exports"
                  element={<Records resource="exports" />}
                />
                <Route path="/audit" element={<Records resource="audit" />} />
                <Route path="/reconciliation" element={<Reconciliation />} />
                <Route path="/finance" element={<Finance />} />
                <Route path="/operations" element={<Operations />} />
                <Route path="/read-model" element={<ReadModel />} />
                <Route path="/balances" element={<Balances />} />
                <Route path="/security" element={<SecurityCenter />} />
                <Route path="/merchants" element={<Merchants />} />
                <Route
                  path="*"
                  element={
                    <Result
                      status="404"
                      title="页面不存在"
                      extra={<Button href="/">返回概览</Button>}
                    />
                  }
                />
              </Routes>
            </Suspense>
          </main>
          <footer className="app-footer">
            <span>商户支付与对账平台</span>
            <span>渠道环境以交易记录为准 · CNY</span>
          </footer>
        </div>
      </div>
    </Workspace>
  );
}
