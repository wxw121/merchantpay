import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  App,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
} from "antd";
import { api, ApiError, dateTime, field, post, queryString } from "../lib/api";
import type { Row } from "../lib/api";
import {
  ErrorNotice,
  Money,
  PageTitle,
  Status,
  timeColumn,
} from "../components";
import { useWorkspace } from "../context";

type Tab = "cases" | "adjustments" | "settlements";
type Action =
  | "investigate"
  | "propose"
  | "revise"
  | "review"
  | "execute"
  | "create"
  | "rebuild"
  | "cancel";
interface Detail {
  record: Row;
  issue?: Row;
  reviews: Row[];
  lines: Row[];
}
interface Values {
  reason: string;
  assignee?: string;
  paymentId?: string;
  evidence?: string;
  direction?: string;
  amountMinor?: string;
  approve?: boolean;
  cutoff?: string;
}
const titles: Record<Action, string> = {
  investigate: "指派与调查",
  propose: "提出处理方案",
  revise: "修改方案",
  review: "审批方案",
  execute: "执行已批准方案",
  create: "创建模拟结算",
  rebuild: "重建结算来源",
  cancel: "取消结算",
};
const directions: Record<string, string> = {
  INCREASE: "增记商户应付",
  DECREASE: "减记商户应付",
  NONE: "无需调账",
};
export function Finance() {
  const { session, merchant } = useWorkspace();
  const client = useQueryClient();
  const { message } = App.useApp();
  const [params, setParams] = useSearchParams();
  const rawTab = params.get("tab");
  const tab: Tab =
    rawTab === "adjustments" || rawTab === "settlements" ? rawTab : "cases";
  const id = params.get("id");
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Error | null>(null);
  const canWrite = session.roles.includes("FINANCE");
  const list = useQuery({
    queryKey: ["finance", "list", tab, merchant],
    queryFn: () =>
      api<Row[]>(
        `/api/console/finance/${tab}?${queryString({ merchantId: merchant })}`,
      ),
  });
  const detail = useQuery({
    queryKey: ["finance", "detail", tab, id, merchant],
    enabled: !!id,
    queryFn: async () => {
      const raw = await api<Record<string, unknown>>(
        `/api/console/finance/${tab}/${encodeURIComponent(id!)}`,
      );
      const { issue, reviews, lines, ...record } = raw;
      return {
        record,
        issue,
        reviews: reviews ?? [],
        lines: lines ?? [],
      } as Detail;
    },
  });
  const row = detail.data?.record;
  const editable =
    !!row && ["DRAFT", "APPROVED", "REJECTED"].includes(field(row, "status"));
  const canReview =
    !!row &&
    field(row, "status") === "DRAFT" &&
    session.actor !== row.proposer &&
    session.actor !== row.lastEditor;
  const openAction = (next: Action) => {
    setFailure(null);
    setAction(next);
  };
  const refresh = () => client.invalidateQueries({ queryKey: ["finance"] });
  async function submit(values: Values) {
    if (!action) return;
    setBusy(true);
    setFailure(null);
    try {
      const expectedVersion = Number(row?.version);
      let path = `/api/console/finance/${tab}/${encodeURIComponent(id ?? "")}/${action}`;
      let body: Record<string, unknown> = { ...values, expectedVersion };
      if (action === "create") {
        path = "/api/console/finance/settlements";
        body = {
          merchantId: merchant,
          cutoff: new Date(values.cutoff!).toISOString(),
          reason: values.reason,
        };
      }
      if (action === "propose")
        path = `/api/console/finance/cases/${encodeURIComponent(id!)}/proposals`;
      if (action === "rebuild")
        body.cutoff = new Date(values.cutoff!).toISOString();
      const result = await post<Row>(path, body);
      setAction(null);
      await refresh();
      if (action === "propose" || action === "create")
        setParams({
          tab: action === "create" ? "settlements" : "adjustments",
          id: field(result, "id"),
        });
      void message.success("操作已保存");
    } catch (error) {
      setFailure(error as Error);
      if (error instanceof ApiError && error.status === 409) await refresh();
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <PageTitle
        title="调账与结算"
        description="从对账差异发起处理，由另一名财务人员审批。结算仅模拟付款，不产生真实资金流转。"
      >
        <Button onClick={() => void refresh()}>刷新</Button>
        {canWrite && tab === "settlements" && (
          <Button
            type="primary"
            disabled={!merchant}
            onClick={() => openAction("create")}
          >
            创建模拟结算
          </Button>
        )}
      </PageTitle>
      {tab === "settlements" && !merchant && (
        <Alert type="info" showIcon title="创建结算前，请在顶部选择商户。" />
      )}
      <Tabs
        activeKey={tab}
        onChange={(key) => setParams({ tab: key })}
        items={[
          { key: "cases", label: "差异案件" },
          { key: "adjustments", label: "调账方案" },
          { key: "settlements", label: "模拟结算" },
        ]}
      />
      {list.error ? (
        <ErrorNotice error={list.error} retry={() => void list.refetch()} />
      ) : (
        <Table<Row>
          loading={list.isPending}
          rowKey="id"
          dataSource={list.data}
          pagination={{ pageSize: 20 }}
          scroll={{ x: 900 }}
          columns={[
            {
              title: "编号",
              dataIndex: "id",
              width: 280,
              render: (value: string) => (
                <Button
                  type="link"
                  onClick={() => setParams({ tab, id: value })}
                >
                  {value}
                </Button>
              ),
            },
            { title: "商户", dataIndex: "merchantId" },
            {
              title: "状态",
              dataIndex: "status",
              render: (value: string) => <Status value={value} />,
            },
            { title: "版本", dataIndex: "version" },
            ...(tab === "cases"
              ? [{ title: "负责人", dataIndex: "assignedTo" }]
              : [
                  {
                    title:
                      tab === "settlements" ? "净额 / 元" : "调账金额 / 元",
                    key: "amount",
                    render: (_: unknown, item: Row) => (
                      <Money
                        value={field(
                          item,
                          tab === "settlements" ? "netMinor" : "amountMinor",
                        )}
                      />
                    ),
                  },
                ]),
            timeColumn,
          ]}
        />
      )}
      <Drawer
        title={
          tab === "cases"
            ? "差异案件"
            : tab === "adjustments"
              ? "调账方案"
              : "模拟结算详情"
        }
        open={!!id}
        size={920}
        extra={
          <Button
            loading={detail.isFetching}
            onClick={() => void detail.refetch()}
          >
            刷新详情
          </Button>
        }
        onClose={() => setParams({ tab })}
      >
        {detail.error && (
          <ErrorNotice
            error={detail.error}
            retry={() => void detail.refetch()}
          />
        )}
        {row && (
          <>
            <Descriptions
              column={2}
              items={[
                {
                  key: "id",
                  label: "编号",
                  children: field(row, "id"),
                  span: 2,
                },
                {
                  key: "merchant",
                  label: "商户",
                  children: field(row, "merchantId"),
                },
                {
                  key: "version",
                  label: "当前版本",
                  children: field(row, "version"),
                },
                {
                  key: "status",
                  label: "状态",
                  children: <Status value={field(row, "status")} />,
                },
                {
                  key: "root",
                  label: "关联支付",
                  children: row.paymentId ? (
                    <Link
                      to={`/payments/${encodeURIComponent(field(row, "paymentId"))}`}
                    >
                      {field(row, "paymentId")}
                    </Link>
                  ) : (
                    "—"
                  ),
                },
                {
                  key: "reason",
                  label: tab === "cases" ? "调查证据" : "方案原因",
                  children:
                    field(row, tab === "cases" ? "evidence" : "reason") || "—",
                  span: 2,
                },
                ...(tab === "cases"
                  ? [
                      {
                        key: "assignee",
                        label: "负责人",
                        children: field(row, "assignedTo") || "未指派",
                      },
                    ]
                  : [
                      {
                        key: "proposer",
                        label: "提议人",
                        children: field(row, "proposer"),
                      },
                      {
                        key: "reviewer",
                        label: "审批人",
                        children: field(row, "reviewer") || "未审批",
                      },
                      {
                        key: "amount",
                        label:
                          tab === "settlements"
                            ? "净额 / 元"
                            : (directions[field(row, "direction")] ??
                              "金额 / 元"),
                        children: (
                          <Money
                            value={field(
                              row,
                              tab === "settlements"
                                ? "netMinor"
                                : "amountMinor",
                            )}
                          />
                        ),
                      },
                    ]),
                ...(tab === "settlements"
                  ? [
                      {
                        key: "cutoff",
                        label: "来源截止时间",
                        children: dateTime(field(row, "cutoffAt")),
                      },
                      {
                        key: "hash",
                        label: "来源摘要",
                        children: (
                          <span className="break-all">
                            {field(row, "sourceHash")}
                          </span>
                        ),
                        span: 2,
                      },
                    ]
                  : []),
              ]}
            />
            {detail.data?.issue && (
              <Alert
                type="info"
                showIcon
                title={`原始差异：${field(detail.data.issue, "issueType")} · ${field(detail.data.issue, "aggregateId")}`}
                description={
                  <pre className="raw-line">
                    {field(detail.data.issue, "rawLine") ||
                      "本地单边，无渠道原始行"}
                  </pre>
                }
              />
            )}
            {tab === "settlements" && (
              <Alert
                type="info"
                showIcon
                title="正数净额仅记录模拟付款；零或负数结转至下一次结算。"
              />
            )}
            {canWrite && (
              <Space wrap style={{ marginBlock: 20 }}>
                {tab === "cases" && row.status !== "RESOLVED" && (
                  <Button onClick={() => openAction("investigate")}>
                    指派与调查
                  </Button>
                )}
                {tab === "cases" && row.status === "INVESTIGATED" && (
                  <Button type="primary" onClick={() => openAction("propose")}>
                    提出处理方案
                  </Button>
                )}
                {tab === "adjustments" && editable && (
                  <Button onClick={() => openAction("revise")}>修改方案</Button>
                )}
                {tab === "settlements" && editable && (
                  <>
                    <Button onClick={() => openAction("rebuild")}>
                      重建结算来源
                    </Button>
                    <Button danger onClick={() => openAction("cancel")}>
                      取消结算
                    </Button>
                  </>
                )}
                {tab !== "cases" && canReview && (
                  <Button type="primary" onClick={() => openAction("review")}>
                    审批方案
                  </Button>
                )}
                {tab !== "cases" && row.status === "APPROVED" && (
                  <Button type="primary" onClick={() => openAction("execute")}>
                    {tab === "settlements" ? "执行模拟结算" : "执行已批准方案"}
                  </Button>
                )}
              </Space>
            )}
            {detail.data?.reviews.length !== 0 && (
              <Table<Row>
                rowKey="id"
                dataSource={detail.data?.reviews}
                pagination={false}
                columns={[
                  { title: "审批版本", dataIndex: "objectVersion" },
                  { title: "审批人", dataIndex: "reviewer" },
                  {
                    title: "决定",
                    dataIndex: "decision",
                    render: (value: string) => <Status value={value} />,
                  },
                  { title: "原因", dataIndex: "reason" },
                ]}
              />
            )}
            {tab === "settlements" && (
              <Table<Row>
                rowKey="entryId"
                dataSource={detail.data?.lines}
                pagination={{ pageSize: 10 }}
                scroll={{ x: 700 }}
                columns={[
                  { title: "来源分录", dataIndex: "entryId" },
                  {
                    title: "关联支付",
                    dataIndex: "paymentId",
                    render: (value: string) => (
                      <Link to={`/payments/${encodeURIComponent(value)}`}>
                        {value}
                      </Link>
                    ),
                  },
                  {
                    title: "计入金额 / 元",
                    dataIndex: "signedMinor",
                    render: (value: string) => <Money value={value} />,
                  },
                  { title: "前次结转", dataIndex: "priorSettlementId" },
                ]}
              />
            )}
          </>
        )}
      </Drawer>
      <Modal
        title={action ? titles[action] : ""}
        open={!!action}
        footer={null}
        onCancel={() => {
          if (!busy) setAction(null);
        }}
        closable={!busy}
        keyboard={!busy}
        mask={{ closable: !busy }}
        destroyOnHidden
      >
        {failure && <ErrorNotice error={failure} />}
        {action && (
          <Form<Values>
            key={`${action}:${id}:${row?.version}`}
            layout="vertical"
            disabled={busy}
            initialValues={{
              assignee: row?.assignedTo || session.actor,
              paymentId: row?.paymentId,
              evidence: row?.evidence,
              direction:
                row?.direction || (row?.paymentId ? "INCREASE" : "NONE"),
              amountMinor:
                row?.amountMinor ?? (row?.paymentId ? undefined : "0"),
              approve: true,
              cutoff:
                new Date(Date.now() - 1000).toISOString().slice(0, 19) + "Z",
            }}
            onFinish={(values) => void submit(values)}
          >
            {action === "investigate" && (
              <>
                <Form.Item
                  name="assignee"
                  label="负责人身份标识"
                  rules={[{ required: true }]}
                >
                  <Input maxLength={160} />
                </Form.Item>
                <Form.Item
                  name="paymentId"
                  label="关联支付编号（未知可留空，仅能无需调账关闭）"
                >
                  <Input />
                </Form.Item>
                <Form.Item
                  name="evidence"
                  label="调查证据"
                  rules={[{ required: true }]}
                >
                  <Input.TextArea maxLength={2000} rows={4} />
                </Form.Item>
              </>
            )}
            {["propose", "revise"].includes(action) && (
              <>
                <Form.Item
                  name="direction"
                  label="处理方式"
                  rules={[{ required: true }]}
                >
                  <Select
                    options={Object.entries(directions).map(
                      ([value, label]) => ({ value, label }),
                    )}
                  />
                </Form.Item>
                <Form.Item
                  name="amountMinor"
                  label="金额 / 分（无需调账填0）"
                  dependencies={["direction"]}
                  rules={[
                    { required: true },
                    ({ getFieldValue }) => ({
                      validator: async (_, value: unknown) => {
                        if (
                          typeof value !== "string" ||
                          !(getFieldValue("direction") === "NONE"
                            ? value === "0"
                            : /^[1-9][0-9]{0,9}$/.test(value) &&
                              BigInt(value) <= 1000000000n)
                        )
                          throw new Error(
                            "请输入限额内的整数分；无需调账填写0",
                          );
                      },
                    }),
                  ]}
                >
                  <Input inputMode="numeric" />
                </Form.Item>
              </>
            )}
            {action === "review" && (
              <Form.Item
                name="approve"
                label="审批决定"
                rules={[{ required: true }]}
              >
                <Select
                  options={[
                    { value: true, label: "批准当前版本" },
                    { value: false, label: "驳回，修改后重审" },
                  ]}
                />
              </Form.Item>
            )}
            {["create", "rebuild"].includes(action) && (
              <Form.Item
                name="cutoff"
                label="来源截止时间（含时区的 ISO 时间）"
                rules={[
                  { required: true },
                  {
                    validator: async (_, value: string) => {
                      if (
                        !/([zZ]|[+-]\d\d:\d\d)$/.test(value) ||
                        !Number.isFinite(Date.parse(value))
                      )
                        throw new Error("请输入含时区的有效时间");
                    },
                  },
                ]}
              >
                <Input placeholder="2026-10-31T15:00:00+08:00" />
              </Form.Item>
            )}
            {action === "execute" && tab === "settlements" && (
              <Alert
                type="warning"
                showIcon
                title="确认执行模拟结算；不会调用银行或支付真实资金。"
              />
            )}
            {action !== "investigate" && (
              <Form.Item
                name="reason"
                label="操作原因"
                rules={[{ required: true, whitespace: true }]}
              >
                <Input.TextArea rows={3} maxLength={2000} />
              </Form.Item>
            )}
            <Button
              type="primary"
              htmlType="submit"
              loading={busy}
              disabled={busy}
              block
            >
              {busy ? "正在提交" : "提交"}
            </Button>
          </Form>
        )}
      </Modal>
    </>
  );
}
