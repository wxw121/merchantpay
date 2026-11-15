import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  App,
  Button,
  Descriptions,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import { api, dateTime, post, queryString } from "../lib/api";
import { ErrorNotice, PageTitle } from "../components";
import { useWorkspace } from "../context";

interface AggregateRow {
  label: string;
  payments: number | string;
  succeeded: number | string;
  succeeded_minor: number | string;
  refunded_minor: number | string;
  success_rate_pct: number | string | null;
  refund_rate_pct: number | string | null;
  latency_samples: number | string;
  avg_success_latency_ms: number | string | null;
  p99_success_latency_ms: number | string | null;
  latest_source_updated_at: string;
}
interface Report {
  generation: string;
  dimension: "DAY" | "CHANNEL";
  from: string;
  until: string;
  observedAt: string;
  consistency: string;
  sourceWatermarkStatus: "UNVERIFIED";
  rows: AggregateRow[];
}
const percent = (value: number | string | null) =>
  value === null ? "—" : `${Number(value).toFixed(2)}%`;
const milliseconds = (value: number | string | null) =>
  value === null ? "—" : `${Math.round(Number(value))} ms`;
const total = (
  rows: AggregateRow[],
  key: "payments" | "succeeded" | "succeeded_minor" | "refunded_minor",
) => rows.reduce((sum, row) => sum + BigInt(row[key]), 0n);
const totalPercent = (part: bigint, whole: bigint) =>
  whole === 0n
    ? "—"
    : `${Number((part * 10_000n + whole / 2n) / whole) / 100}%`;
interface PreRecon {
  generation: string;
  consistency: string;
  stream: {
    succeeded: number | string;
    succeededMinor: number | string;
    latestSourceUpdatedAt: string;
  } | null;
  bill: {
    id: string;
    status: string;
    paymentRows: number | string;
    paymentMinor: number | string;
  } | null;
  countDelta: number | null;
  minorDelta: number | null;
}
interface Publication {
  generation: string;
  status: string;
  evidenceHash: string | null;
  publishedAt: string | null;
}
interface SourceProgress {
  physicalShard: number;
  allocatedSequence: number | string;
  firstUnprojectedSequence: number | string | null;
  noMissingAtRead: boolean;
  hasPendingBatch: boolean;
  hasQuarantine: boolean;
  observedAt: string;
}
interface ProjectionProgress {
  observedAt: string;
  sources: SourceProgress[];
}

export function Analytics() {
  const { merchant, session } = useWorkspace();
  const [dimension, setDimension] = useState<"DAY" | "CHANNEL">("DAY");
  const [day, setDay] = useState(() =>
    new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Shanghai",
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date(Date.now() - 86_400_000)),
  );
  const [channel, setChannel] = useState("MOCK");
  const [preReconRequest, setPreReconRequest] = useState(0);
  const canPreview = session.roles.some((role) =>
    ["ADMIN", "OPS", "FINANCE", "AUDITOR"].includes(role),
  );
  const canPublish =
    !session.merchantId &&
    session.roles.some((role) => ["ADMIN", "OPS"].includes(role));
  const canInspectPublication = !session.merchantId && canPreview;
  const canInspectSources =
    !session.merchantId &&
    session.roles.some((role) => ["ADMIN", "OPS"].includes(role));
  const { message } = App.useApp();
  const [evidenceText, setEvidenceText] = useState("");
  const [publicationGeneration, setPublicationGeneration] = useState("");
  const [publication, setPublication] = useState<Publication | null>(null);
  const [publicationBusy, setPublicationBusy] = useState(false);
  const [publicationFailure, setPublicationFailure] = useState<Error | null>(
    null,
  );
  const report = useQuery({
    queryKey: ["v3", "analytics", merchant, dimension],
    queryFn: () =>
      api<Report>(
        `/api/console/analytics/payments?${queryString({
          merchantId: merchant,
          dimension,
        })}`,
      ),
    enabled: !!merchant,
    refetchInterval: 60_000,
  });
  const preRecon = useQuery({
    queryKey: [
      "v3",
      "pre-reconciliation",
      merchant,
      day,
      channel,
      preReconRequest,
    ],
    queryFn: () =>
      api<PreRecon>(
        `/api/console/analytics/pre-reconciliation?${queryString({
          merchantId: merchant,
          day,
          channel,
        })}`,
      ),
    enabled: !!merchant && canPreview && preReconRequest > 0,
  });
  const [sourceRequest, setSourceRequest] = useState(0);
  const sources = useQuery({
    queryKey: ["v3", "analytics", "source-progress", sourceRequest],
    queryFn: () =>
      api<ProjectionProgress>("/api/console/read-model/status?deep=true"),
    enabled: canInspectSources && sourceRequest > 0,
  });

  return (
    <>
      <PageTitle
        title="交易分析"
        description="查看当前服务代中的支付趋势和渠道汇总。"
      />
      <Alert
        type="info"
        showIcon
        title="分析结果为最终一致"
        description="数据来自独立查询投影。每行的来源更新时间反映已观察到的记录，不代表所有来源事件都已追平；资金及对账结论以权威记录为准。"
      />
      {!merchant && (
        <Alert
          type="warning"
          showIcon
          title="请选择商户后查看分析"
          style={{ marginTop: 16 }}
        />
      )}
      <Space wrap style={{ marginTop: 20, marginBottom: 16 }}>
        <span>汇总维度</span>
        <Select
          aria-label="汇总维度"
          value={dimension}
          onChange={setDimension}
          options={[
            { value: "DAY", label: "按日" },
            { value: "CHANNEL", label: "按渠道" },
          ]}
          style={{ width: 140 }}
        />
        <Button onClick={() => void report.refetch()} disabled={!merchant}>
          刷新
        </Button>
        {report.data && <Tag>服务代 {report.data.generation}</Tag>}
      </Space>
      {report.error && (
        <ErrorNotice error={report.error} retry={() => void report.refetch()} />
      )}
      {report.data && (
        <>
          <p>
            范围：{dateTime(report.data.from)} 至 {dateTime(report.data.until)}
            ；查询时间：{dateTime(report.data.observedAt)}。
          </p>
          <Descriptions
            bordered
            size="small"
            column={4}
            items={[
              {
                key: "successRate",
                label: "成功率（成功单 / 支付单）",
                children: totalPercent(
                  total(report.data.rows, "succeeded"),
                  total(report.data.rows, "payments"),
                ),
              },
              {
                key: "refundRate",
                label: "退款率（已退金额 / 成功金额）",
                children: totalPercent(
                  total(report.data.rows, "refunded_minor"),
                  total(report.data.rows, "succeeded_minor"),
                ),
              },
              {
                key: "amount",
                label: "成功金额 / 分",
                children: total(report.data.rows, "succeeded_minor").toString(),
              },
              {
                key: "watermark",
                label: "来源水位",
                children: "未验证",
              },
            ]}
          />
          <p style={{ marginTop: 8 }}>
            来源水位状态：{report.data.sourceWatermarkStatus}
            。表中只显示本组已观察记录的最新来源更新时间；它不能证明四库
            CDC、查询投影已追平。
            延迟只统计记录了首次成功时间的支付单，老数据未回填时样本数可能少于成功单数。
          </p>
        </>
      )}
      <Table<AggregateRow>
        rowKey="label"
        loading={report.isPending && !!merchant}
        dataSource={report.data?.rows ?? []}
        pagination={{ pageSize: 20 }}
        columns={[
          { title: dimension === "DAY" ? "日期" : "渠道", dataIndex: "label" },
          { title: "支付单数", dataIndex: "payments" },
          ...(dimension === "CHANNEL"
            ? [
                {
                  title: "渠道单量占比",
                  dataIndex: "payments",
                  render: (value: number | string) =>
                    totalPercent(
                      BigInt(value),
                      total(report.data?.rows ?? [], "payments"),
                    ),
                },
              ]
            : []),
          { title: "成功单数", dataIndex: "succeeded" },
          {
            title: "成功率",
            dataIndex: "success_rate_pct",
            render: percent,
          },
          { title: "成功金额 / 分", dataIndex: "succeeded_minor" },
          { title: "已退金额 / 分", dataIndex: "refunded_minor" },
          {
            title: "退款率（金额）",
            dataIndex: "refund_rate_pct",
            render: percent,
          },
          { title: "延迟样本数", dataIndex: "latency_samples" },
          {
            title: "平均成功延迟",
            dataIndex: "avg_success_latency_ms",
            render: milliseconds,
          },
          {
            title: "成功延迟 P99（近似）",
            dataIndex: "p99_success_latency_ms",
            render: milliseconds,
          },
          {
            title: "本组最新来源更新时间",
            dataIndex: "latest_source_updated_at",
          },
        ]}
      />
      {canInspectSources && (
        <section style={{ marginTop: 28 }}>
          <h2>四库来源进度</h2>
          <p>
            按需检查各物理库的已分配事件序号、首个未投影序号及积压状态。检查发生在不同数据库的独立快照中，不能证明
            ClickHouse 已追平或形成全局水位。
          </p>
          <Button
            loading={sources.isFetching}
            onClick={() => setSourceRequest((value) => value + 1)}
          >
            检查来源进度
          </Button>
          {sources.error && (
            <ErrorNotice
              error={sources.error}
              retry={() => void sources.refetch()}
            />
          )}
          {sources.data && (
            <>
              <p>检查时间：{dateTime(sources.data.observedAt)}</p>
              <Table<SourceProgress>
                rowKey="physicalShard"
                pagination={false}
                dataSource={sources.data.sources}
                columns={[
                  { title: "物理库", dataIndex: "physicalShard" },
                  { title: "已分配事件序号", dataIndex: "allocatedSequence" },
                  {
                    title: "首个未投影序号",
                    dataIndex: "firstUnprojectedSequence",
                    render: (value: number | string | null) => value ?? "无",
                  },
                  {
                    title: "当前快照无缺口",
                    dataIndex: "noMissingAtRead",
                    render: (value: boolean) => (value ? "是" : "否"),
                  },
                  {
                    title: "待处理或失败批次",
                    dataIndex: "hasPendingBatch",
                    render: (value: boolean) => (value ? "有" : "无"),
                  },
                  {
                    title: "隔离事件",
                    dataIndex: "hasQuarantine",
                    render: (value: boolean) => (value ? "有" : "无"),
                  },
                ]}
              />
            </>
          )}
        </section>
      )}
      {canPreview && (
        <section style={{ marginTop: 28 }}>
          <h2>渠道支付预对账</h2>
          <Alert
            type="warning"
            showIcon
            title="仅供提前排查"
            description="流指标可能迟到或回补；差额不能作为封版或资金结论。该功能需单独启用 Flink 分析开关。"
          />
          <Space wrap style={{ marginTop: 16, marginBottom: 16 }}>
            <Input
              aria-label="预对账日期"
              type="date"
              value={day}
              onChange={(event) => setDay(event.target.value)}
              style={{ width: 160 }}
            />
            <Input
              aria-label="预对账渠道"
              value={channel}
              onChange={(event) => setChannel(event.target.value.toUpperCase())}
              maxLength={32}
              style={{ width: 160 }}
            />
            <Button
              disabled={!merchant || !channel || !day}
              loading={preRecon.isFetching}
              onClick={() => setPreReconRequest((value) => value + 1)}
            >
              查询预对账
            </Button>
          </Space>
          {preRecon.error && (
            <ErrorNotice
              error={preRecon.error}
              retry={() => void preRecon.refetch()}
            />
          )}
          {preRecon.data && (
            <Descriptions
              bordered
              column={2}
              size="small"
              items={[
                {
                  key: "generation",
                  label: "服务代",
                  children: preRecon.data.generation,
                },
                {
                  key: "status",
                  label: "状态",
                  children: "初步数据，尚未验证",
                },
                {
                  key: "streamCount",
                  label: "流侧成功笔数",
                  children: preRecon.data.stream?.succeeded ?? "未就绪",
                },
                {
                  key: "billCount",
                  label: "渠道账单成功笔数",
                  children: preRecon.data.bill?.paymentRows ?? "未就绪",
                },
                {
                  key: "countDelta",
                  label: "笔数差",
                  children: preRecon.data.countDelta ?? "无法比较",
                },
                {
                  key: "streamMinor",
                  label: "流侧成功金额 / 分",
                  children: preRecon.data.stream?.succeededMinor ?? "未就绪",
                },
                {
                  key: "sourceTime",
                  label: "本组最新来源更新时间",
                  children:
                    preRecon.data.stream?.latestSourceUpdatedAt ?? "未就绪",
                },
                {
                  key: "billMinor",
                  label: "渠道账单成功金额 / 分",
                  children: preRecon.data.bill?.paymentMinor ?? "未就绪",
                },
                {
                  key: "minorDelta",
                  label: "金额差 / 分",
                  children: preRecon.data.minorDelta ?? "无法比较",
                },
              ]}
            />
          )}
        </section>
      )}
      {canInspectPublication && (
        <section style={{ marginTop: 28 }}>
          <h2>分析代发布</h2>
          <Alert
            type="warning"
            showIcon
            title="只发布经独立环境签名的固定水位证据"
            description="发布状态只证明记录的停写水位。来源、路由或服务代变化后状态会变为 STALE；不能用于财务封版。"
          />
          <Space wrap style={{ marginTop: 16, marginBottom: 16 }}>
            <Input
              aria-label="查询分析代"
              value={publicationGeneration}
              onChange={(event) =>
                setPublicationGeneration(event.target.value.trim())
              }
              placeholder="服务代号"
              style={{ width: 230 }}
            />
            <Button
              loading={publicationBusy}
              disabled={!publicationGeneration}
              onClick={async () => {
                setPublicationBusy(true);
                setPublicationFailure(null);
                try {
                  setPublication(
                    await api<Publication>(
                      `/api/console/analytics/publications/${encodeURIComponent(publicationGeneration)}`,
                    ),
                  );
                } catch (error) {
                  setPublicationFailure(error as Error);
                } finally {
                  setPublicationBusy(false);
                }
              }}
            >
              查询发布状态
            </Button>
          </Space>
          {canPublish && (
            <>
              <p>
                粘贴 <code>publish_analytics.py</code> 输出文件的完整
                JSON。服务端验证签名、四库位置、路由和服务代。
              </p>
              <Input.TextArea
                aria-label="分析代签名证据"
                value={evidenceText}
                onChange={(event) => setEvidenceText(event.target.value)}
                rows={5}
                placeholder={'{"payload":"...","signature":"..."}'}
              />
              <Button
                type="primary"
                style={{ marginTop: 12 }}
                loading={publicationBusy}
                disabled={!evidenceText.trim()}
                onClick={async () => {
                  setPublicationBusy(true);
                  setPublicationFailure(null);
                  try {
                    const parsed: unknown = JSON.parse(evidenceText);
                    if (
                      !parsed ||
                      typeof parsed !== "object" ||
                      typeof (parsed as Record<string, unknown>).payload !==
                        "string" ||
                      typeof (parsed as Record<string, unknown>).signature !==
                        "string"
                    )
                      throw new Error(
                        "证据文件须包含 payload 和 signature 字符串",
                      );
                    const signed = parsed as {
                      payload: string;
                      signature: string;
                    };
                    const result = await post<Publication>(
                      "/api/console/analytics/publications",
                      { payload: signed.payload, signature: signed.signature },
                    );
                    setPublication(result);
                    setPublicationGeneration(result.generation);
                    void message.success("分析代发布证据已记录");
                  } catch (error) {
                    setPublicationFailure(error as Error);
                  } finally {
                    setPublicationBusy(false);
                  }
                }}
              >
                提交签名证据
              </Button>
            </>
          )}
          {publicationFailure && <ErrorNotice error={publicationFailure} />}
          {publication && (
            <Descriptions
              bordered
              size="small"
              column={2}
              items={[
                {
                  key: "generation",
                  label: "分析代",
                  children: publication.generation,
                },
                {
                  key: "status",
                  label: "当前状态",
                  children: publication.status,
                },
                {
                  key: "hash",
                  label: "证据 SHA-256",
                  children: publication.evidenceHash ?? "—",
                  span: 2,
                },
                {
                  key: "time",
                  label: "发布时间",
                  children: publication.publishedAt
                    ? dateTime(publication.publishedAt)
                    : "—",
                },
              ]}
            />
          )}
        </section>
      )}
    </>
  );
}
