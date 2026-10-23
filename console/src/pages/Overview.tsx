import { useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Skeleton, Tag } from "antd";
import { ArrowRightOutlined, ReloadOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import * as echarts from "echarts/core";
import { LineChart } from "echarts/charts";
import { GridComponent, TooltipComponent } from "echarts/components";
import { CanvasRenderer } from "echarts/renderers";
import { api, field, queryString, dateTime } from "../lib/api";
import type { Row } from "../lib/api";
import { useWorkspace } from "../context";
import {
  ErrorNotice,
  Money,
  PageTitle,
  PagedTable,
  Status,
} from "../components";
echarts.use([LineChart, GridComponent, TooltipComponent, CanvasRenderer]);
interface Dashboard {
  totals: Row;
  trend: Row[];
  from: string;
  until: string;
}
interface ChannelHealth {
  channel: string;
  state: string;
  pendingCallbacks: number | null;
  pendingPayments: number | null;
  oldestCallbackAt: string;
  observedAt: string;
}
function ChannelHealthPanel() {
  const { merchant } = useWorkspace();
  const result = useQuery({
    queryKey: ["channels", merchant],
    queryFn: () =>
      api<ChannelHealth[]>(
        `/api/console/channels?${queryString({ merchantId: merchant })}`,
      ),
    refetchInterval: 15000,
  });
  return (
    <section className="channel-strip" aria-label="模拟渠道状态">
      {result.error && <ErrorNotice error={result.error} />}{" "}
      {result.isPending ? (
        <span className="secondary">正在读取渠道状态…</span>
      ) : (
        result.data?.map((channel) => (
          <div key={channel.channel}>
            <strong>
              {channel.channel === "MOCK_A" ? "模拟渠道 A" : "模拟渠道 B"}
            </strong>
            <Tag color={channel.state === "AVAILABLE" ? "blue" : "default"}>
              {channel.state === "AVAILABLE" ? "连接正常" : "暂不可达"}
            </Tag>
            <span>待回调 {channel.pendingCallbacks ?? "—"} 笔</span>
            <small>观测于 {dateTime(channel.observedAt)}</small>
          </div>
        ))
      )}
    </section>
  );
}
function Trend({ rows }: { rows: Row[] }) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!ref.current) return;
    const chart = echarts.init(ref.current);
    chart.setOption({
      animation: false,
      color: ["#002FA7"],
      grid: { top: 25, right: 25, bottom: 30, left: 45 },
      tooltip: { trigger: "axis" },
      xAxis: {
        type: "category",
        data: rows.map((row) => field(row, "day")),
        axisLine: { lineStyle: { color: "#D9DBE2" } },
        axisTick: { show: false },
      },
      yAxis: {
        type: "value",
        minInterval: 1,
        splitLine: { lineStyle: { color: "#ECEDEF" } },
      },
      series: [
        {
          name: "受理笔数",
          type: "line",
          data: rows.map((row) => Number(row.totalCount)),
          smooth: false,
          symbolSize: 6,
          lineStyle: { width: 2 },
          areaStyle: { color: "#002FA7", opacity: 0.035 },
        },
      ],
    });
    const observer = new ResizeObserver(() => chart.resize());
    observer.observe(ref.current);
    return () => {
      observer.disconnect();
      chart.dispose();
    };
  }, [rows]);
  return (
    <div
      className="trend-chart"
      ref={ref}
      role="img"
      aria-label="按 UTC 日期统计的最近七天支付受理笔数"
    />
  );
}
export function Overview() {
  const { merchant } = useWorkspace();
  const result = useQuery({
    queryKey: ["dashboard", merchant],
    queryFn: () =>
      api<Dashboard>(
        `/api/console/dashboard?${queryString({ merchantId: merchant })}`,
      ),
    refetchInterval: 10_000,
  });
  const totals = result.data?.totals;
  return (
    <>
      <PageTitle
        title="业务概览"
        description="最近七天的支付、退款与待查证记录。"
      >
        <Button
          icon={<ReloadOutlined aria-hidden="true" />}
          onClick={() => void result.refetch()}
        >
          刷新
        </Button>
        <Button type="primary" href="/payments">
          发起模拟支付
        </Button>
      </PageTitle>
      {result.error && <ErrorNotice error={result.error} />}
      <div className="metric-grid">
        <div className="metric">
          <span>成功收款金额 / 元</span>
          <strong>
            {totals ? <Money value={field(totals, "paidMinor")} /> : "—"}
          </strong>
          <small>
            {totals ? `${totals.succeededCount} 笔成功交易` : "加载中"}
          </small>
        </div>
        <div className="metric">
          <span>累计退款金额 / 元</span>
          <strong>
            {totals ? <Money value={field(totals, "refundedMinor")} /> : "—"}
          </strong>
          <small>已成功退款，不含处理中预占</small>
        </div>
        <div className="metric">
          <span>支付受理笔数</span>
          <strong>{totals ? String(totals.totalCount) : "—"}</strong>
          <small>统计窗口内受理</small>
        </div>
        <div className="metric">
          <span>结果未知</span>
          <strong>{totals ? String(totals.unknownCount) : "—"}</strong>
          <small>持续查证，未判定为失败</small>
        </div>
      </div>
      <ChannelHealthPanel />
      <div className="overview-middle">
        <section className="chart-panel">
          <div className="panel-heading">
            <h2>支付受理趋势</h2>
            <span>UTC 日期 · 笔</span>
          </div>
          {result.isPending ? (
            <Skeleton active />
          ) : (
            <Trend rows={result.data?.trend ?? []} />
          )}
          <div className="data-watermark">
            统计截止 {result.data ? dateTime(result.data.until) : "—"} ·
            来源为独立模拟渠道
          </div>
        </section>
        <section className="workflow-panel">
          <span className="section-number">01—03</span>
          <h2>从收款到核对</h2>
          <Link to="/payments">
            <span>01</span>
            <div>
              <strong>支付与退款</strong>
              <small>追踪状态与可退额度</small>
            </div>
            <ArrowRightOutlined aria-hidden="true" />
          </Link>
          <Link to="/journals">
            <span>02</span>
            <div>
              <strong>账务凭证</strong>
              <small>关联业务与复式分录</small>
            </div>
            <ArrowRightOutlined aria-hidden="true" />
          </Link>
          <Link to="/reconciliation">
            <span>03</span>
            <div>
              <strong>对账与差错</strong>
              <small>核对渠道账单与本地记录</small>
            </div>
            <ArrowRightOutlined aria-hidden="true" />
          </Link>
          <Tag>全部交易为模拟数据</Tag>
        </section>
      </div>
      <div className="section-title">
        <h2>需要查证的支付</h2>
        <Link to="/payments">
          查看全部 <ArrowRightOutlined aria-hidden="true" />
        </Link>
      </div>
      <PagedTable
        resource="payments"
        status="UNKNOWN"
        columns={[
          {
            title: "商户业务号",
            dataIndex: "merchantOrderNo",
            render: (value: string, row: Row) => (
              <Link to={`/payments/${row.id}`}>{value}</Link>
            ),
          },
          { title: "商户", dataIndex: "merchantId" },
          {
            title: "支付金额 / 元",
            dataIndex: "amountMinor",
            align: "right",
            render: (value: string) => <Money value={value} />,
          },
          { title: "渠道", dataIndex: "channel" },
          {
            title: "状态",
            dataIndex: "status",
            render: (value: string) => <Status value={value} />,
          },
        ]}
      />
    </>
  );
}
