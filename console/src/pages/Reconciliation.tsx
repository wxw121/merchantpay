import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  App,
  Alert,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
} from "antd";
import { DownloadOutlined, UploadOutlined } from "@ant-design/icons";
import { api, dateTime, field, post } from "../lib/api";
import type { BatchDetail, Row } from "../lib/api";
import { useWorkspace } from "../context";
import { MerchantSelect } from "../merchant-select";
import {
  ErrorNotice,
  Money,
  PageTitle,
  PagedTable,
  Status,
  timeColumn,
} from "../components";
const issueNames: Record<string, string> = {
  CHANNEL_ONLY: "渠道单边",
  LOCAL_ONLY: "本地成功但账单缺失",
  AMOUNT_MISMATCH: "金额不符",
  FEE_MISMATCH: "手续费不符",
  STATUS_MISMATCH: "状态不符",
  CHANNEL_SUCCESS_LOCAL_UNKNOWN: "渠道成功、本地待确认",
  DUPLICATE_CHANNEL_RECORD: "渠道重复记录",
  REFERENCE_MISMATCH: "渠道流水不符",
};
interface BillInput {
  merchantId: string;
  channel: string;
  day: string;
  version: number;
}
export function Reconciliation() {
  const { session, merchant } = useWorkspace();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState("pull");
  const [file, setFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [retrying, setRetrying] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const [cursor, setCursor] = useState<string | null>(null);
  const detail = useQuery({
    queryKey: ["batch", selected, cursor],
    queryFn: () =>
      api<BatchDetail>(
        `/api/console/batches/${selected}${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`,
      ),
    enabled: !!selected,
    refetchInterval: 3_000,
  });
  const canWrite = session.roles.some((role) =>
    ["ADMIN", "FINANCE", "MERCHANT"].includes(role),
  );
  return (
    <>
      <PageTitle
        title="对账与差错"
        description="保留原始账单，校验完整性后匹配支付和退款记录。差错识别不会自动改账。"
      >
        {canWrite && (
          <>
            <Button
              icon={<UploadOutlined aria-hidden="true" />}
              onClick={() => {
                setFile(null);
                setMode("upload");
                setOpen(true);
              }}
            >
              上传账单
            </Button>
            <Button
              type="primary"
              icon={<DownloadOutlined aria-hidden="true" />}
              onClick={() => {
                setFile(null);
                setMode("pull");
                setOpen(true);
              }}
            >
              拉取模拟账单
            </Button>
          </>
        )}
      </PageTitle>
      <PagedTable
        resource="batches"
        columns={[
          {
            title: "业务日 / 版本",
            key: "day",
            width: 180,
            render: (_, row) => (
              <Button
                type="link"
                onClick={() => {
                  setCursor(null);
                  setSelected(field(row, "id"));
                }}
              >
                {field(row, "businessDay")} · v{field(row, "version")}
              </Button>
            ),
          },
          { title: "商户", dataIndex: "merchantId" },
          { title: "渠道", dataIndex: "channel" },
          {
            title: "状态",
            dataIndex: "status",
            render: (value: string) => <Status value={value} />,
          },
          {
            title: "已校验 / 声明笔数",
            key: "progress",
            render: (_, row) =>
              `${field(row, "actualCount")} / ${field(row, "expectedCount")}`,
          },
          {
            title: "账单金额 / 元",
            dataIndex: "actualAmountMinor",
            align: "right",
            render: (value: string) => <Money value={value} />,
          },
          timeColumn,
        ]}
      />
      <Modal
        title={mode === "pull" ? "拉取模拟渠道账单" : "上传原始账单"}
        open={open}
        onCancel={() => {
          if (busy) return;
          setFile(null);
          setOpen(false);
        }}
        closable={!busy}
        keyboard={!busy}
        mask={{ closable: !busy }}
        footer={null}
        destroyOnHidden
      >
        <Form<BillInput>
          disabled={busy}
          layout="vertical"
          initialValues={{
            merchantId: merchant || undefined,
            channel: "MOCK_A",
            day: new Intl.DateTimeFormat("en-CA", {
              timeZone: "Asia/Shanghai",
            }).format(new Date()),
            version: 1,
          }}
          onFinish={async (values) => {
            setBusy(true);
            try {
              let result: { id: string };
              if (mode === "pull")
                result = await post("/api/console/reconciliation/pull", values);
              else {
                if (!file) throw new Error("请选择 CSV 账单");
                if (file.size > 128 * 1024 * 1024)
                  throw new Error("账单超过 128 MiB");
                const digest = await crypto.subtle.digest(
                  "SHA-256",
                  await file.arrayBuffer(),
                );
                const sha256 = Array.from(new Uint8Array(digest), (byte) =>
                  byte.toString(16).padStart(2, "0"),
                ).join("");
                const body = new FormData();
                body.set("file", file);
                body.set("merchantId", values.merchantId);
                body.set("channel", values.channel);
                body.set("day", values.day);
                body.set("version", String(values.version));
                body.set("sha256", sha256);
                result = await api("/api/console/reconciliation/upload", {
                  method: "POST",
                  body,
                });
              }
              setFile(null);
              setOpen(false);
              setCursor(null);
              setSelected(result.id);
              void client.invalidateQueries({ queryKey: ["list"] });
              void message.success("账单已接收，后台校验与对账中");
            } catch (error) {
              void message.error((error as Error).message);
            } finally {
              setBusy(false);
            }
          }}
        >
          <Form.Item
            label="商户"
            name="merchantId"
            rules={[{ required: true, message: "请选择商户" }]}
          >
            <MerchantSelect disabled={!!merchant} />
          </Form.Item>
          <Form.Item label="渠道" name="channel">
            <Select
              options={[
                { value: "MOCK_A", label: "模拟渠道 A" },
                { value: "MOCK_B", label: "模拟渠道 B" },
              ]}
            />
          </Form.Item>
          <Form.Item
            label="业务日期 / Asia/Shanghai"
            name="day"
            rules={[{ required: true }]}
          >
            <Input type="date" />
          </Form.Item>
          <Form.Item
            label="账单版本"
            name="version"
            rules={[{ required: true }]}
            extra="同版本同内容重复提交返回同一批次。修正内容后须递增版本。"
          >
            <InputNumber min={1} precision={0} />
          </Form.Item>
          {mode === "upload" && (
            <Form.Item label="CSV 文件">
              <Input
                key={String(open)}
                type="file"
                accept=".csv,text/csv"
                onChange={(event) => setFile(event.target.files?.[0] ?? null)}
              />
              <p className="secondary">
                可先拉取模拟账单并下载原始文件作为格式示例。最大 128
                MiB、一百万行。
              </p>
            </Form.Item>
          )}
          <Button
            htmlType="submit"
            type="primary"
            loading={busy}
            disabled={busy}
            aria-busy={busy}
            block
          >
            {busy
              ? "正在提交账单"
              : mode === "pull"
                ? "拉取并开始对账"
                : "上传并开始校验"}
          </Button>
        </Form>
      </Modal>
      <Drawer
        title="对账批次与差错"
        open={!!selected}
        onClose={() => setSelected(null)}
        size="large"
        styles={{ wrapper: { width: "min(1050px, 100vw)" } }}
      >
        {detail.error && <ErrorNotice error={detail.error} />}{" "}
        {detail.data && (
          <>
            <Space className="section-gap" wrap>
              <Status value={field(detail.data.batch, "status")} />
              <Button
                href={`/api/console/batches/${selected}/original`}
                icon={<DownloadOutlined aria-hidden="true" />}
              >
                下载原始账单
              </Button>
              {field(detail.data.batch, "status") === "FAILED" &&
                session.roles.some((role) =>
                  ["ADMIN", "FINANCE", "OPS"].includes(role),
                ) && (
                  <Button
                    loading={retrying}
                    disabled={retrying}
                    aria-busy={retrying}
                    onClick={async () => {
                      setRetrying(true);
                      try {
                        await post(`/api/console/batches/${selected}/retry`);
                        void detail.refetch();
                      } catch (error) {
                        void message.error((error as Error).message);
                      } finally {
                        setRetrying(false);
                      }
                    }}
                  >
                    {retrying ? "正在安排续跑" : "从检查点续跑"}
                  </Button>
                )}
            </Space>
            <Descriptions
              column={2}
              items={[
                { key: "id", label: "批次编号", children: selected },
                {
                  key: "merchant",
                  label: "商户",
                  children: field(detail.data.batch, "merchantId"),
                },
                {
                  key: "day",
                  label: "业务日",
                  children: field(detail.data.batch, "businessDay"),
                },
                {
                  key: "version",
                  label: "版本",
                  children: field(detail.data.batch, "version"),
                },
                {
                  key: "count",
                  label: "校验笔数",
                  children: `${field(detail.data.batch, "actualCount")} / ${field(detail.data.batch, "expectedCount")}`,
                },
                {
                  key: "checkpoint",
                  label: "持久化检查点",
                  children: `${field(detail.data.batch, "processedLines")} 行`,
                },
                {
                  key: "snapshot",
                  label: "本地快照时间",
                  children: dateTime(field(detail.data.batch, "snapshotAt")),
                },
                {
                  key: "hash",
                  label: "SHA-256",
                  children: (
                    <span className="break-all">
                      {field(detail.data.batch, "sha256")}
                    </span>
                  ),
                },
              ]}
            />
            {detail.data.batch.errorDetail && (
              <Alert
                type="warning"
                showIcon
                title={field(detail.data.batch, "errorDetail")}
              />
            )}
            <Tabs
              items={[
                {
                  key: "issues",
                  label: "已发布差错",
                  children: (
                    <>
                      <Table<Row>
                        rowKey="id"
                        dataSource={detail.data.issues}
                        pagination={false}
                        scroll={{ x: 950 }}
                        expandable={{
                          expandedRowRender: (row) => (
                            <pre className="raw-line">
                              {field(row, "rawLine") ||
                                "本地单边，无渠道原始行"}
                            </pre>
                          ),
                        }}
                        columns={[
                          {
                            title: "差错类型",
                            dataIndex: "issueType",
                            width: 190,
                            render: (value: string) =>
                              issueNames[value] ?? value,
                          },
                          {
                            title: "业务标识",
                            dataIndex: "aggregateId",
                            width: 260,
                          },
                          {
                            title: "渠道 / 本地状态",
                            key: "statuses",
                            width: 220,
                            render: (_, row) => (
                              <>
                                {field(row, "channelStatus") || "—"} /{" "}
                                {field(row, "localStatus") || "—"}
                              </>
                            ),
                          },
                          {
                            title: "渠道金额 / 元",
                            dataIndex: "channelAmountMinor",
                            align: "right",
                            render: (value: string) => <Money value={value} />,
                          },
                          {
                            title: "本地金额 / 元",
                            dataIndex: "localAmountMinor",
                            align: "right",
                            render: (value: string) => <Money value={value} />,
                          },
                          { title: "原始行", dataIndex: "lineNo" },
                        ]}
                      />
                      <div className="table-footer">
                        <span>
                          {field(detail.data.batch, "status") === "COMPLETE"
                            ? "完整账单校验通过后发布结果；原始证据保持留存。"
                            : "账单未完成，暂不发布差错。"}
                        </span>
                        <Button
                          disabled={!detail.data.nextCursor}
                          onClick={() => setCursor(detail.data!.nextCursor)}
                        >
                          下一页
                        </Button>
                      </div>
                    </>
                  ),
                },
              ]}
            />
          </>
        )}
      </Drawer>
    </>
  );
}
