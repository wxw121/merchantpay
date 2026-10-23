import { useState } from "react";
import { Link } from "react-router-dom";
import { useQueryClient } from "@tanstack/react-query";
import { App, Button, Form, Input, Modal, Select, Tabs } from "antd";
import { DownloadOutlined } from "@ant-design/icons";
import type { ColumnsType } from "antd/es/table";
import { api, dateTime, field, post, queryString } from "../lib/api";
import type { Row } from "../lib/api";
import { useWorkspace } from "../context";
import { MerchantSelect } from "../merchant-select";
import { PageTitle, PagedTable, Status, timeColumn } from "../components";
const eventNames: Record<string, string> = {
  CHANNEL_PAYMENT: "渠道支付 / 查单",
  CHANNEL_REFUND: "渠道退款 / 查单",
  CHANNEL_CLOSE: "渠道关闭",
  PROCESS_INBOX: "渠道通知处理",
  PAYMENT_SUCCEEDED: "支付成功通知",
  PAYMENT_FAILED: "支付失败通知",
  PAYMENT_CLOSED: "支付关闭通知",
  REFUND_SUCCEEDED: "退款成功通知",
  REFUND_FAILED: "退款失败通知",
  RECONCILE: "账单对账",
  EXPORT: "异步导出",
};
export function Records({
  resource,
}: {
  resource: "journals" | "tasks" | "audit" | "exports";
}) {
  const { session, merchant } = useWorkspace();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [tab, setTab] = useState("tasks");
  const [creating, setCreating] = useState(false);
  const [busy, setBusy] = useState(false);
  const [replaying, setReplaying] = useState<Set<string>>(new Set());
  const info = {
    journals: [
      "账务凭证",
      "按业务事件唯一记账。点击关联支付查看完整分录与渠道证据。",
    ],
    tasks: [
      "任务与通知",
      "查看持久化任务、下次执行时间与通知投递结果。重放保留原业务身份。",
    ],
    exports: [
      "导出文件",
      "后台生成 ZIP 文件；单份 CSV 最多一百万行，超出后自动分片。",
    ],
    audit: [
      "操作审计",
      "查看商户配置、交易请求、密钥操作、任务重放和文件下载记录。",
    ],
  }[resource];
  const canReplay = (row: Row) =>
    ["DONE", "DEAD"].includes(field(row, "status")) &&
    (session.roles.some((role) => ["ADMIN", "OPS"].includes(role)) ||
      (session.roles.includes("MERCHANT") &&
        [
          "PAYMENT_SUCCEEDED",
          "PAYMENT_FAILED",
          "PAYMENT_CLOSED",
          "REFUND_SUCCEEDED",
          "REFUND_FAILED",
        ].includes(field(row, "kind"))));
  const columns: ColumnsType<Row> =
    resource === "journals"
      ? [
          { title: "凭证号", dataIndex: "id", width: 280 },
          {
            title: "事件来源",
            dataIndex: "sourceType",
            render: (value) => (value === "PAYMENT" ? "支付成功" : "退款成功"),
          },
          {
            title: "关联支付",
            dataIndex: "paymentId",
            width: 280,
            render: (id: string) => <Link to={`/payments/${id}`}>{id}</Link>,
          },
          { title: "商户", dataIndex: "merchantId" },
          { title: "币种", dataIndex: "currency" },
          timeColumn,
        ]
      : resource === "audit"
        ? [
            { title: "操作", dataIndex: "operation" },
            { title: "操作人", dataIndex: "actor", width: 230 },
            { title: "商户", dataIndex: "merchantId" },
            { title: "关联资源", dataIndex: "resourceId", width: 260 },
            { title: "详情", dataIndex: "detail", width: 260 },
            timeColumn,
          ]
        : resource === "exports"
          ? [
              { title: "导出编号", dataIndex: "id", width: 280 },
              {
                title: "内容",
                dataIndex: "resource",
                render: (value: string) =>
                  ({
                    payments: "支付记录",
                    refunds: "退款记录",
                    journals: "账务凭证",
                  })[value] ?? value,
              },
              { title: "商户", dataIndex: "merchantId" },
              {
                title: "状态",
                dataIndex: "status",
                render: (value: string) => <Status value={value} />,
              },
              { title: "已处理行数", dataIndex: "rowCount" },
              {
                title: "下载有效期",
                dataIndex: "expiresAt",
                render: (value: string) => dateTime(value),
              },
              timeColumn,
              {
                title: "文件",
                key: "download",
                render: (_, row) => (
                  <Button
                    type="link"
                    icon={<DownloadOutlined aria-hidden="true" />}
                    disabled={
                      row.status !== "COMPLETE" ||
                      !row.expiresAt ||
                      new Date(field(row, "expiresAt")).getTime() <= Date.now()
                    }
                    href={`/api/console/exports/${row.id}/download`}
                  >
                    下载 ZIP
                  </Button>
                ),
              },
            ]
          : tab === "notifications"
            ? [
                { title: "事件", dataIndex: "eventId", width: 260 },
                { title: "商户", dataIndex: "merchantId" },
                {
                  title: "投递状态",
                  dataIndex: "status",
                  render: (value: string) => <Status value={value} />,
                },
                { title: "HTTP 状态", dataIndex: "httpStatus" },
                { title: "最近错误", dataIndex: "errorDetail" },
                timeColumn,
              ]
            : [
                {
                  title: "任务类型 / 编号",
                  key: "type",
                  width: 280,
                  render: (_, row) => (
                    <div className="record-link">
                      <strong>
                        {eventNames[field(row, "kind")] ?? field(row, "kind")}
                      </strong>
                      <small>{field(row, "id")}</small>
                    </div>
                  ),
                },
                { title: "商户", dataIndex: "merchantId" },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (value: string) => <Status value={value} />,
                },
                { title: "尝试次数", dataIndex: "attempts", width: 90 },
                {
                  title: "计划时间",
                  dataIndex: "dueAt",
                  width: 175,
                  render: dateTime,
                },
                { title: "最近错误", dataIndex: "lastError", width: 250 },
                {
                  title: "操作",
                  key: "actions",
                  width: 90,
                  render: (_, row) =>
                    canReplay(row) && (
                      <Button
                        type="link"
                        loading={replaying.has(String(row.id))}
                        disabled={replaying.has(String(row.id))}
                        aria-busy={replaying.has(String(row.id))}
                        onClick={async () => {
                          setReplaying((current) =>
                            new Set(current).add(String(row.id)),
                          );
                          try {
                            await post(
                              `/api/console/tasks/${row.id}/replay?${queryString({ merchantId: merchant })}`,
                            );
                            void message.success("已安排重放");
                            void client.invalidateQueries({
                              queryKey: ["list"],
                            });
                          } catch (error) {
                            void message.error((error as Error).message);
                          } finally {
                            setReplaying((current) => {
                              const next = new Set(current);
                              next.delete(String(row.id));
                              return next;
                            });
                          }
                        }}
                      >
                        {replaying.has(String(row.id))
                          ? "正在安排重放"
                          : "重放"}
                      </Button>
                    ),
                },
              ];
  return (
    <>
      <PageTitle title={info[0]!} description={info[1]!}>
        {["journals", "exports"].includes(resource) && (
          <Button
            type="primary"
            icon={<DownloadOutlined aria-hidden="true" />}
            onClick={() => setCreating(true)}
          >
            创建导出
          </Button>
        )}
      </PageTitle>
      {resource === "tasks" && (
        <Tabs
          activeKey={tab}
          onChange={setTab}
          items={[
            { key: "tasks", label: "持久化任务" },
            { key: "notifications", label: "通知投递记录" },
          ]}
        />
      )}
      <PagedTable
        resource={resource === "tasks" ? tab : resource}
        columns={columns}
      />
      <Modal
        title="创建异步导出"
        open={creating}
        footer={null}
        onCancel={() => setCreating(false)}
        destroyOnHidden
      >
        <Form<{
          merchantId: string;
          resource: string;
          from: string;
          until: string;
        }>
          layout="vertical"
          initialValues={{
            merchantId: merchant || undefined,
            resource: resource === "journals" ? "journals" : "payments",
          }}
          onFinish={async (values) => {
            setBusy(true);
            try {
              await api("/api/console/exports", {
                method: "POST",
                body: JSON.stringify({
                  merchantId: values.merchantId,
                  resource: values.resource,
                  from: values.from
                    ? new Date(`${values.from}T00:00:00+08:00`).toISOString()
                    : null,
                  until: values.until
                    ? new Date(`${values.until}T00:00:00+08:00`).toISOString()
                    : null,
                }),
              });
              void message.success("导出任务已创建，请到导出文件页下载");
              setCreating(false);
              void client.invalidateQueries({ queryKey: ["list"] });
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
          <Form.Item label="导出内容" name="resource">
            <Select
              options={[
                { value: "payments", label: "支付记录" },
                { value: "refunds", label: "退款记录" },
                { value: "journals", label: "账务凭证" },
              ]}
            />
          </Form.Item>
          <Form.Item label="开始日期（北京时间，含）" name="from">
            <Input type="date" />
          </Form.Item>
          <Form.Item label="截止日期（北京时间，不含）" name="until">
            <Input type="date" />
          </Form.Item>
          <p className="secondary">
            未填写时导出最近七天。单次历史范围最多 365 天，文件仅包含所选商户。
          </p>
          <Button
            type="primary"
            block
            htmlType="submit"
            loading={busy}
            disabled={busy}
            aria-busy={busy}
          >
            {busy ? "正在创建导出" : "创建导出任务"}
          </Button>
        </Form>
      </Modal>
    </>
  );
}
