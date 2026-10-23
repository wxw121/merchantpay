import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import {
  keepPreviousData,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import {
  App,
  Alert,
  Button,
  Descriptions,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tabs,
  Timeline,
  Skeleton,
  Result,
} from "antd";
import {
  ArrowLeftOutlined,
  PlusOutlined,
  ReloadOutlined,
} from "@ant-design/icons";
import type { Row, Payment, PaymentDetail, EvidenceResource } from "../lib/api";
import { api, post, queryString, field, dateTime } from "../lib/api";
import { parseYuan, formatMinor } from "../lib/money";
import { canRefund, statusLabel } from "../lib/status";
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
interface PaymentInput {
  merchantId: string;
  merchantOrderNo: string;
  amount: string;
  channel: string;
  scenario: string;
}
function PaymentForm({ onCreated }: { onCreated: (payment: Payment) => void }) {
  const { merchant } = useWorkspace();
  const { message } = App.useApp();
  const [form] = Form.useForm<PaymentInput>();
  const [submitting, setSubmitting] = useState(false);
  useEffect(() => {
    form.setFieldValue("merchantId", merchant || undefined);
  }, [form, merchant]);
  return (
    <Form<PaymentInput>
      form={form}
      layout="vertical"
      initialValues={{
        merchantId: merchant || undefined,
        merchantOrderNo: `demo-${Date.now()}`,
        channel: "MOCK_A",
        scenario: "",
      }}
      onFinish={async (values) => {
        setSubmitting(true);
        try {
          const result = await post<Payment>(
            `/api/console/payments?${queryString({ merchantId: values.merchantId })}`,
            {
              merchantOrderNo: values.scenario + values.merchantOrderNo,
              amountMinor: parseYuan(values.amount),
              currency: "CNY",
              channel: values.channel,
            },
          );
          onCreated(result);
        } catch (error) {
          void message.error(
            error instanceof Error ? error.message : "受理失败",
          );
        } finally {
          setSubmitting(false);
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
      <Form.Item
        label="商户支付业务号"
        name="merchantOrderNo"
        rules={[
          {
            required: true,
            max: 90,
            message: "请输入不超过 90 字符的唯一业务号",
          },
        ]}
        extra="相同业务号和参数重试会返回同一支付单。"
      >
        <Input autoComplete="off" />
      </Form.Item>
      <Form.Item
        label="支付金额 / CNY 元"
        name="amount"
        rules={[
          { required: true, message: "请输入金额" },
          {
            validator: (_, value: string) => {
              try {
                parseYuan(value ?? "");
                return Promise.resolve();
              } catch (error) {
                return Promise.reject(error);
              }
            },
          },
        ]}
      >
        <Input
          inputMode="decimal"
          placeholder="例如 100.00"
          autoComplete="off"
        />
      </Form.Item>
      <Form.Item label="模拟渠道" name="channel">
        <Select
          options={[
            { value: "MOCK_A", label: "模拟渠道 A" },
            { value: "MOCK_B", label: "模拟渠道 B" },
          ]}
        />
      </Form.Item>
      <Form.Item label="模拟场景" name="scenario">
        <Select
          options={[
            { value: "", label: "正常成功" },
            { value: "lost:", label: "扣款成功后响应丢失" },
            { value: "pending:", label: "持续处理中，可请求关闭" },
            { value: "fail:", label: "渠道明确失败" },
          ]}
        />
      </Form.Item>
      <Button
        block
        size="large"
        htmlType="submit"
        type="primary"
        loading={submitting}
        disabled={submitting}
        aria-busy={submitting}
      >
        {submitting ? "正在提交支付" : "提交模拟支付"}
      </Button>
    </Form>
  );
}
export function Payments() {
  const { session } = useWorkspace();
  const navigate = useNavigate();
  const [creating, setCreating] = useState(false);
  const [status, setStatus] = useState<string>();
  const [tab, setTab] = useState("payments");
  return (
    <>
      <PageTitle
        title="支付与退款"
        description="支付受理后持续追踪渠道结果；退款额度以原支付单为边界。"
      >
        {session.roles.some((role) => ["ADMIN", "MERCHANT"].includes(role)) && (
          <Button
            type="primary"
            icon={<PlusOutlined aria-hidden="true" />}
            onClick={() => setCreating(true)}
          >
            发起模拟支付
          </Button>
        )}
        <Link to="/cashier">
          <Button>收银演示</Button>
        </Link>
      </PageTitle>
      <div className="list-toolbar">
        <Tabs
          activeKey={tab}
          onChange={(value) => {
            setTab(value);
            setStatus(undefined);
          }}
          items={[
            { key: "payments", label: "支付记录" },
            { key: "refunds", label: "退款记录" },
          ]}
        />
        <Select
          aria-label="状态筛选"
          allowClear
          placeholder="全部状态"
          style={{ width: 160 }}
          value={status}
          onChange={setStatus}
          options={(tab === "payments"
            ? [
                "PROCESSING",
                "UNKNOWN",
                "CLOSE_PENDING",
                "SUCCEEDED",
                "FAILED",
                "CLOSED",
              ]
            : ["PROCESSING", "UNKNOWN", "SUCCEEDED", "FAILED"]
          ).map((value) => ({ value, label: statusLabel(value) }))}
        />
      </div>
      <PagedTable
        resource={tab}
        status={status}
        columns={
          tab === "payments"
            ? [
                {
                  title: "商户业务号 / 支付号",
                  key: "order",
                  width: 260,
                  render: (_, row) => (
                    <Link className="record-link" to={`/payments/${row.id}`}>
                      <strong>{field(row, "merchantOrderNo")}</strong>
                      <small>{field(row, "id")}</small>
                    </Link>
                  ),
                },
                { title: "商户", dataIndex: "merchantId", width: 130 },
                {
                  title: "支付金额 / 元",
                  dataIndex: "amountMinor",
                  align: "right",
                  width: 140,
                  render: (value: string) => <Money value={value} />,
                },
                {
                  title: "已退 / 元",
                  dataIndex: "refundedMinor",
                  align: "right",
                  width: 120,
                  render: (value: string) => <Money value={value} />,
                },
                { title: "渠道", dataIndex: "channel", width: 110 },
                {
                  title: "状态",
                  dataIndex: "status",
                  width: 130,
                  render: (value: string) => <Status value={value} />,
                },
                timeColumn,
              ]
            : [
                {
                  title: "退款业务号",
                  dataIndex: "merchantRefundNo",
                  width: 200,
                },
                {
                  title: "原支付号",
                  dataIndex: "paymentId",
                  width: 260,
                  render: (value: string) => (
                    <Link to={`/payments/${value}`}>{value}</Link>
                  ),
                },
                {
                  title: "金额 / 元",
                  dataIndex: "amountMinor",
                  align: "right",
                  render: (value: string) => <Money value={value} />,
                },
                {
                  title: "退还手续费 / 元",
                  dataIndex: "refundedFeeMinor",
                  align: "right",
                  render: (value: string) => <Money value={value} />,
                },
                {
                  title: "状态",
                  dataIndex: "status",
                  render: (value: string) => <Status value={value} />,
                },
                timeColumn,
              ]
        }
      />
      <Modal
        open={creating}
        title="发起模拟支付"
        footer={null}
        onCancel={() => setCreating(false)}
        destroyOnHidden
      >
        <Alert
          type="info"
          showIcon
          title="此操作使用模拟渠道，不产生真实扣款。"
          style={{ marginBottom: 20 }}
        />
        <PaymentForm
          onCreated={(payment) => {
            setCreating(false);
            void navigate(`/payments/${payment.id}`);
          }}
        />
      </Modal>
    </>
  );
}
export function PaymentDetailPage() {
  const { id = "" } = useParams();
  const { merchant } = useWorkspace();
  return <PaymentDetailContent key={JSON.stringify([id, merchant])} />;
}
function PaymentDetailContent() {
  const { id = "" } = useParams();
  const { merchant, session } = useWorkspace();
  const { message } = App.useApp();
  const client = useQueryClient();
  const [history, setHistory] = useState<
    Partial<Record<EvidenceResource, (string | null)[]>>
  >({});
  const cursors = Object.fromEntries(
    Object.entries(history).map(([resource, pages]) => [
      resource + "Cursor",
      pages.at(-1),
    ]),
  );
  const result = useQuery({
    queryKey: ["payment", id, merchant, cursors],
    queryFn: () =>
      api<PaymentDetail>(
        `/api/console/payments/${encodeURIComponent(id)}?${queryString({ merchantId: merchant, ...cursors })}`,
      ),
    placeholderData: keepPreviousData,
    refetchInterval: 2_000,
  });
  const [refundOpen, setRefundOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [closeOpen, setCloseOpen] = useState(false);
  const [querying, setQuerying] = useState(false);
  const [closing, setClosing] = useState(false);
  const refresh = () => {
    setHistory({});
    void client.invalidateQueries({ queryKey: ["payment", id, merchant] });
    void client.invalidateQueries({ queryKey: ["list"] });
    void client.invalidateQueries({ queryKey: ["dashboard"] });
  };
  if (result.isPending) return <Skeleton active paragraph={{ rows: 10 }} />;
  if (result.error || !result.data)
    return <ErrorNotice error={result.error ?? new Error("支付单不存在")} />;
  const {
    payment,
    refunds,
    entries,
    journals,
    attempts,
    tasks,
    conflicts,
    nextCursors,
  } = result.data;
  const pager = (resource: EvidenceResource, label: string) => (
    <Space style={{ marginTop: 16 }}>
      <Button
        aria-label={`${label}上一页`}
        disabled={(history[resource]?.length ?? 1) < 2 || result.isFetching}
        onClick={() =>
          setHistory((previous) => ({
            ...previous,
            [resource]: previous[resource]!.slice(0, -1),
          }))
        }
      >
        上一页
      </Button>
      <span className="secondary">第 {history[resource]?.length ?? 1} 页</span>
      <Button
        aria-label={`${label}下一页`}
        disabled={!nextCursors[resource] || result.isFetching}
        onClick={() =>
          setHistory((previous) => ({
            ...previous,
            [resource]: [
              ...(previous[resource] ?? [null]),
              nextCursors[resource],
            ],
          }))
        }
      >
        下一页
      </Button>
    </Space>
  );
  const available = (
    BigInt(payment.amountMinor) -
    BigInt(payment.refundedMinor) -
    BigInt(payment.reservedMinor)
  ).toString();
  const mutable = session.roles.some((role) =>
    ["ADMIN", "MERCHANT"].includes(role),
  );
  const timeline = [
    {
      time: payment.createdAt,
      title: "支付请求已受理",
      content: payment.merchantOrderNo,
    },
    ...attempts.map((row) => ({
      time: field(row, "updatedAt"),
      title: `渠道${field(row, "operation").includes("REFUND") ? "退款" : "支付"} · ${statusLabel(field(row, "status"))}`,
      content: field(row, "requestKey"),
    })),
    ...journals.map((row) => ({
      time: field(row, "createdAt"),
      title: `凭证已过账 · ${field(row, "sourceType") === "PAYMENT" ? "支付" : "退款"}`,
      content: field(row, "eventId"),
    })),
    ...tasks
      .filter((row) => !field(row, "kind").startsWith("CHANNEL_"))
      .map((row) => ({
        time: field(row, "updatedAt"),
        title: `${field(row, "kind").startsWith("PROCESS_") ? "渠道通知处理" : "商户通知"} · ${statusLabel(field(row, "status"))}`,
        content: field(row, "eventId"),
      })),
  ].sort((a, b) => a.time.localeCompare(b.time));
  return (
    <>
      <Link className="back-link" to="/payments">
        <ArrowLeftOutlined aria-hidden="true" /> 支付记录
      </Link>
      <PageTitle title="支付详情" description={payment.id}>
        <Status value={payment.status} />
        <Button icon={<ReloadOutlined aria-hidden="true" />} onClick={refresh}>
          刷新
        </Button>
        {!["SUCCEEDED", "FAILED", "CLOSED"].includes(payment.status) && (
          <Button
            loading={querying}
            disabled={querying}
            aria-busy={querying}
            onClick={async () => {
              setQuerying(true);
              try {
                await post(
                  `/api/console/payments/${id}/query?${queryString({ merchantId: merchant })}`,
                );
                void message.success("已安排渠道查单");
                refresh();
              } catch (error) {
                void message.error((error as Error).message);
              } finally {
                setQuerying(false);
              }
            }}
          >
            {querying ? "正在安排查单" : "主动查单"}
          </Button>
        )}
        {mutable &&
          ["PROCESSING", "UNKNOWN", "CLOSE_PENDING"].includes(
            payment.status,
          ) && <Button onClick={() => setCloseOpen(true)}>请求关闭</Button>}
        {canRefund(session.roles, payment.status) && BigInt(available) > 0n && (
          <Button type="primary" onClick={() => setRefundOpen(true)}>
            申请退款
          </Button>
        )}
      </PageTitle>
      {payment.status === "UNKNOWN" && (
        <Alert
          className="section-gap"
          type="warning"
          showIcon
          title="结果未知，仍在查证"
          description={`最近业务更新时间：${dateTime(payment.updatedAt)}。请保留原业务号，不要通过新订单重复付款。查单任务到达重试上限后，可在任务页查看原因并重放。`}
        />
      )}
      <section className="detail-summary">
        <div className="payment-amount">
          <span>原支付金额 / CNY</span>
          <strong>
            <Money value={payment.amountMinor} />
          </strong>
          <Status value={payment.status} />
        </div>
        <Descriptions
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={[
            {
              key: "order",
              label: "商户业务号",
              children: payment.merchantOrderNo,
            },
            { key: "merchant", label: "商户", children: payment.merchantId },
            { key: "channel", label: "模拟渠道", children: payment.channel },
            {
              key: "fee",
              label: "原手续费",
              children: `${formatMinor(payment.feeMinor)} 元`,
            },
            {
              key: "refunded",
              label: "已成功退款",
              children: `${formatMinor(payment.refundedMinor)} 元`,
            },
            {
              key: "reserved",
              label: "处理中预占",
              children: `${formatMinor(payment.reservedMinor)} 元`,
            },
            {
              key: "available",
              label: "可退金额",
              children: `${formatMinor(available)} 元`,
            },
            {
              key: "ref",
              label: "渠道流水号",
              children: payment.channelReference ?? "等待确认",
            },
            {
              key: "created",
              label: "受理时间",
              children: dateTime(payment.createdAt),
            },
          ]}
        />
      </section>
      <Tabs
        items={[
          {
            key: "timeline",
            label: "业务时间线",
            children: (
              <div className="timeline-panel">
                <p className="secondary">
                  时间线汇总各标签页当前页的记录；较早记录可在相应标签页翻阅。
                </p>
                <Timeline
                  items={timeline.map((event, index) => ({
                    key: index,
                    title: (
                      <span className="secondary">{dateTime(event.time)}</span>
                    ),
                    content: (
                      <div>
                        <strong>{event.title}</strong>
                        <p className="break-all secondary">{event.content}</p>
                      </div>
                    ),
                  }))}
                />
              </div>
            ),
          },
          {
            key: "refunds",
            label: `退款记录（本页 ${refunds.length} 笔）`,
            children: (
              <>
                <Table<Row>
                  rowKey="id"
                  dataSource={refunds}
                  pagination={false}
                  scroll={{ x: 700 }}
                  columns={[
                    { title: "退款业务号", dataIndex: "merchantRefundNo" },
                    {
                      title: "金额 / 元",
                      dataIndex: "amountMinor",
                      align: "right",
                      render: (value: string) => <Money value={value} />,
                    },
                    {
                      title: "退手续费 / 元",
                      dataIndex: "refundedFeeMinor",
                      align: "right",
                      render: (value: string) => <Money value={value} />,
                    },
                    {
                      title: "状态",
                      dataIndex: "status",
                      render: (value: string) => <Status value={value} />,
                    },
                    timeColumn,
                  ]}
                />
                {pager("refunds", "退款")}
              </>
            ),
          },
          {
            key: "entries",
            label: `账务分录（本页 ${entries.length} 条）`,
            children: (
              <>
                <p className="secondary">
                  本页展示 {journals.length} 个完整凭证的全部分录。每页最多 50
                  个凭证，可翻页查看较早记录。
                </p>
                <Table<Row>
                  rowKey="id"
                  dataSource={entries}
                  pagination={false}
                  scroll={{ x: 700 }}
                  columns={[
                    { title: "凭证号", dataIndex: "journalId" },
                    {
                      title: "科目",
                      dataIndex: "accountCode",
                      render: (value: string) =>
                        ({
                          CHANNEL_RECEIVABLE: "渠道应收",
                          MERCHANT_PAYABLE: "商户应付",
                          FEE_REVENUE: "手续费收入",
                        })[value] ?? value,
                    },
                    {
                      title: "借 / 贷",
                      dataIndex: "side",
                      render: (value: string) =>
                        value === "DEBIT" ? "借" : "贷",
                    },
                    {
                      title: "金额 / 元",
                      dataIndex: "amountMinor",
                      align: "right",
                      render: (value: string) => <Money value={value} />,
                    },
                  ]}
                />
                {pager("journals", "凭证")}
              </>
            ),
          },
          {
            key: "attempts",
            label: "渠道尝试",
            children: (
              <>
                <Table<Row>
                  rowKey="id"
                  dataSource={attempts}
                  pagination={false}
                  scroll={{ x: 700 }}
                  columns={[
                    { title: "稳定请求键", dataIndex: "requestKey" },
                    { title: "操作", dataIndex: "operation" },
                    {
                      title: "状态",
                      dataIndex: "status",
                      render: (value: string) => <Status value={value} />,
                    },
                    timeColumn,
                  ]}
                />
                {pager("attempts", "渠道尝试")}
              </>
            ),
          },
          {
            key: "tasks",
            label: "通知与任务",
            children: (
              <>
                <Table<Row>
                  rowKey="id"
                  dataSource={tasks}
                  pagination={false}
                  scroll={{ x: 700 }}
                  columns={[
                    { title: "事件", dataIndex: "eventId" },
                    {
                      title: "状态",
                      dataIndex: "status",
                      render: (value: string) => <Status value={value} />,
                    },
                    { title: "尝试次数", dataIndex: "attempts" },
                    { title: "下次执行", dataIndex: "dueAt", render: dateTime },
                    { title: "最近错误", dataIndex: "lastError" },
                  ]}
                />
                {pager("tasks", "任务")}
              </>
            ),
          },
          {
            key: "conflicts",
            label: `矛盾证据（本页 ${conflicts.length} 条）`,
            children: (
              <>
                <Table<Row>
                  rowKey="id"
                  dataSource={conflicts}
                  pagination={false}
                  columns={[
                    { title: "关联单号", dataIndex: "aggregateId" },
                    { title: "说明", dataIndex: "detail" },
                    timeColumn,
                  ]}
                />
                {pager("conflicts", "矛盾证据")}
              </>
            ),
          },
        ]}
      />
      <Modal
        title="申请部分 / 全额退款"
        open={refundOpen}
        onCancel={() => setRefundOpen(false)}
        footer={null}
        destroyOnHidden
      >
        <p>
          当前可退 <Money value={available} />{" "}
          元。申请后额度立即预占，渠道结果未知时继续保留。
        </p>
        <Form<{ amount: string; merchantRefundNo: string }>
          layout="vertical"
          initialValues={{ merchantRefundNo: `refund-${Date.now()}` }}
          onFinish={async (values) => {
            setSubmitting(true);
            try {
              await post(
                `/api/console/payments/${id}/refunds?${queryString({ merchantId: merchant })}`,
                {
                  merchantRefundNo: values.merchantRefundNo,
                  amountMinor: parseYuan(values.amount),
                },
              );
              void message.success("退款已受理，等待渠道结果");
              setRefundOpen(false);
              refresh();
            } catch (error) {
              void message.error((error as Error).message);
            } finally {
              setSubmitting(false);
            }
          }}
        >
          <Form.Item
            name="merchantRefundNo"
            label="商户退款业务号"
            rules={[{ required: true, max: 100 }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="amount"
            label="退款金额 / 元"
            rules={[
              { required: true },
              {
                validator: (_, value: string) => {
                  try {
                    if (BigInt(parseYuan(value ?? "")) > BigInt(available))
                      throw new Error("超过可退金额");
                    return Promise.resolve();
                  } catch (error) {
                    return Promise.reject(error);
                  }
                },
              },
            ]}
          >
            <Input inputMode="decimal" placeholder="例如 30.00" />
          </Form.Item>
          <Button
            htmlType="submit"
            type="primary"
            loading={submitting}
            disabled={submitting}
            aria-busy={submitting}
            block
          >
            {submitting ? "正在提交退款" : "提交退款申请"}
          </Button>
        </Form>
      </Modal>
      <Modal
        title="请求关闭支付"
        open={closeOpen}
        confirmLoading={closing}
        okText={closing ? "正在提交关闭请求" : "确定"}
        okButtonProps={{ disabled: closing, "aria-busy": closing }}
        cancelButtonProps={{ disabled: closing }}
        closable={!closing}
        keyboard={!closing}
        mask={{ closable: !closing }}
        onCancel={() => {
          if (!closing) setCloseOpen(false);
        }}
        onOk={async () => {
          setClosing(true);
          try {
            await post(
              `/api/console/payments/${id}/close?${queryString({ merchantId: merchant })}`,
            );
            void message.success("关闭请求已受理，等待渠道确认");
            setCloseOpen(false);
            refresh();
          } catch (error) {
            void message.error((error as Error).message);
          } finally {
            setClosing(false);
          }
        }}
      >
        <p>
          关闭必须由渠道确认。如果渠道已成功扣款，系统将保留成功结果并完成记账。
        </p>
      </Modal>
    </>
  );
}
export function Cashier() {
  const [payment, setPayment] = useState<Payment | null>(null);
  const result = useQuery({
    queryKey: ["cashier", payment?.id],
    queryFn: () => api<PaymentDetail>(`/api/console/payments/${payment!.id}`),
    enabled: !!payment,
    refetchInterval: (query) =>
      query.state.data &&
      ["SUCCEEDED", "FAILED", "CLOSED"].includes(
        query.state.data.payment.status,
      )
        ? false
        : 1_000,
  });
  const current = result.data?.payment ?? payment;
  return (
    <div className="cashier">
      <PageTitle
        title="模拟收银台"
        description="CNY · 独立模拟渠道，不产生真实扣款。"
      />
      {!current ? (
        <PaymentForm onCreated={setPayment} />
      ) : (
        <>
          <Result
            status={
              current.status === "SUCCEEDED"
                ? "success"
                : current.status === "FAILED"
                  ? "error"
                  : "info"
            }
            title={statusLabel(current.status)}
            subTitle={current.merchantOrderNo}
          />
          <div className="cashier-total">
            <Money value={current.amountMinor} />
            <small>元</small>
          </div>
          <Space>
            <Link to={`/payments/${current.id}`}>
              <Button>查看交易详情</Button>
            </Link>
            <Button onClick={() => setPayment(null)}>创建另一笔支付</Button>
          </Space>
          {result.error && <ErrorNotice error={result.error} />}
        </>
      )}
    </div>
  );
}
