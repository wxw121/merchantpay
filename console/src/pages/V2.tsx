import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Result,
  Select,
  Space,
  Switch,
  Table,
  Tabs,
  Tag,
} from "antd";
import { api, dateTime, field, post, queryString } from "../lib/api";
import type { Row } from "../lib/api";
import { useWorkspace } from "../context";
import { ErrorNotice, Money, PageTitle, Status } from "../components";

function columns(names: Record<string, string>) {
  return Object.entries(names).map(([key, title]) => ({
    title,
    dataIndex: key,
    key,
    render: (value: unknown) =>
      value === null || value === undefined
        ? "—"
        : typeof value === "object"
          ? JSON.stringify(value)
          : String(value),
  }));
}
function Rows({
  rows,
  names,
  actions,
}: {
  rows: Row[];
  names: Record<string, string>;
  actions?: (row: Row) => React.ReactNode;
}) {
  return (
    <div className="data-panel">
      <Table<Row>
        size="small"
        rowKey={(r) =>
          [r.id, r.channel, r.queueClass, r.status, r.state, r.holdType]
            .filter((v) => v !== null && v !== undefined)
            .join(":")
        }
        dataSource={rows}
        columns={[
          ...columns(names),
          ...(actions
            ? [
                {
                  title: "操作",
                  key: "actions",
                  render: (_: unknown, row: Row) => actions(row),
                },
              ]
            : []),
        ]}
        pagination={false}
        scroll={{ x: 900 }}
      />
    </div>
  );
}
function useAction() {
  const { message } = App.useApp();
  const client = useQueryClient();
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Error | null>(null);
  return {
    busy,
    failure,
    run: async (path: string, body?: unknown) => {
      setBusy(true);
      setFailure(null);
      try {
        await post(path, body);
        await client.invalidateQueries({ queryKey: ["v2"] });
        void message.success("操作已提交，请核对最新状态");
        return true;
      } catch (error) {
        setFailure(error as Error);
        return false;
      } finally {
        setBusy(false);
      }
    },
  };
}
const platformOperator = (roles: string[], merchantId: string) =>
  !merchantId && roles.some((r) => ["ADMIN", "OPS"].includes(r));

export function Operations() {
  const { session, merchant } = useWorkspace();
  const operator = platformOperator(session.roles, session.merchantId);
  const [tab, setTab] = useState("summary");
  const [recoveryStage, setRecoveryStage] = useState("ALL");
  const [editing, setEditing] = useState<Row | null>(null);
  const [routing, setRouting] = useState(false);
  const [action, setAction] = useState<{ path: string; title: string } | null>(
    null,
  );
  const command = useAction();
  const summary = useQuery({
    queryKey: ["v2", "operations"],
    queryFn: () =>
      api<{
        queues: Row[];
        channelFailures: Row[];
        projection: Row[];
        holds: Row[];
        observedAt: string;
        redisAdmission: { fallbackCount: string };
      }>("/api/console/operations/summary"),
    enabled: operator,
    refetchInterval: 30_000,
  });
  const channels = useQuery({
    queryKey: ["v2", "channels"],
    queryFn: () => api<Row[]>("/api/console/channel-controls"),
    enabled: operator,
  });
  const list = useQuery({
    queryKey: ["v2", "ops-list", tab, merchant, recoveryStage],
    queryFn: () =>
      api<Row[]>(
        tab === "quarantine"
          ? "/api/console/events/quarantine"
          : tab === "dead-tasks"
            ? "/api/console/operations/dead-tasks"
            : `/api/console/operations/${tab}?${queryString({ merchantId: merchant, ...(tab === "unknown" ? { stage: recoveryStage } : {}) })}`,
      ),
    enabled:
      operator &&
      ["unknown", "reservations", "dead-tasks", "quarantine"].includes(tab) &&
      (!["unknown", "reservations"].includes(tab) || !!merchant),
  });
  const routes = useQuery({
    queryKey: ["v2", "routes", merchant],
    queryFn: () =>
      api<Row[]>(
        `/api/console/merchants/${encodeURIComponent(merchant)}/routes`,
      ),
    enabled: operator && !!merchant,
  });
  if (!operator)
    return <Result status="403" title="需要平台管理员或运维权限" />;
  return (
    <>
      <PageTitle
        title="运行与渠道"
        description="关注未知结果、退款占款和持久化任务。未知交易保留原渠道与业务号，查证后再恢复处理。"
      >
        <Button onClick={() => void summary.refetch()}>刷新观察</Button>
      </PageTitle>
      {[
        summary.error,
        channels.error,
        list.error,
        routes.error,
        command.failure,
      ]
        .filter(Boolean)
        .map((error, i) => (
          <ErrorNotice key={i} error={error as Error} />
        ))}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: "summary", label: "运行概况" },
          { key: "unknown", label: "未知交易" },
          { key: "reservations", label: "退款占款" },
          { key: "dead-tasks", label: "失败任务" },
          { key: "quarantine", label: "事件隔离" },
          { key: "channels", label: "渠道与路由" },
        ]}
      />
      {tab === "unknown" && (
        <Space direction="vertical">
          <Alert
            type="info"
            title="2分钟告警，30分钟转人工关注，24小时后持续长期查证。未知退款始终保留预占。"
          />
          <Select
            aria-label="查证阶段"
            value={recoveryStage}
            onChange={setRecoveryStage}
            options={[
              { value: "ALL", label: "全部阶段" },
              { value: "PRIORITY", label: "优先查证（2分钟内）" },
              { value: "ALERT", label: "已告警（2至30分钟）" },
              { value: "MANUAL", label: "人工关注（30分钟至24小时）" },
              { value: "LONG_TERM", label: "长期查证（24小时以上）" },
            ]}
          />
        </Space>
      )}
      {tab === "summary" && summary.data && (
        <>
          <p>
            观察时间：{dateTime(summary.data.observedAt)} · Redis 降级累计{" "}
            {summary.data.redisAdmission.fallbackCount} 次
          </p>
          <h2>工作队列</h2>
          <Rows
            rows={summary.data.queues}
            names={{
              queueClass: "队列",
              status: "状态",
              total: "任务数",
              oldestDue: "最早到期",
            }}
          />
          <h2>近十五分钟渠道失败</h2>
          <Rows
            rows={summary.data.channelFailures}
            names={{ channel: "渠道", total: "次数", latest: "最近发生" }}
          />
          <h2>查询投递与结算冻结</h2>
          <Rows
            rows={summary.data.projection}
            names={{ state: "状态", total: "任务数", oldest: "最早来源" }}
          />
          <Rows
            rows={summary.data.holds}
            names={{ holdType: "冻结原因", total: "商户数" }}
          />
        </>
      )}
      {["unknown", "reservations"].includes(tab) && !merchant && (
        <Alert type="info" title="请选择商户以查询交易" />
      )}
      {["unknown", "reservations"].includes(tab) && merchant && (
        <Rows
          rows={list.data ?? []}
          names={{
            id: "业务编号",
            operation: "业务类型",
            channel: "原渠道",
            status: "状态",
            amountMinor: "金额 / 分",
            ...(tab === "unknown"
              ? {
                  unknownSince: "首次未知时间",
                  unknownAgeSeconds: "未知时长 / 秒",
                  recoveryStage: "查证阶段",
                }
              : {}),
            updatedAt: "更新时间",
          }}
          actions={(r) => (
            <Link to={`/payments/${field(r, "paymentId")}`}>
              查看权威支付详情
            </Link>
          )}
        />
      )}
      {tab === "dead-tasks" && (
        <Rows
          rows={list.data ?? []}
          names={{
            kind: "任务",
            aggregateId: "业务编号",
            merchantId: "商户",
            attempts: "尝试次数",
            retryStartedAt: "本轮重试开始",
            lastError: "最近错误",
            updatedAt: "更新时间",
          }}
          actions={(r) => (
            <Button
              disabled={command.busy}
              onClick={() =>
                setAction({
                  path: `/api/console/tasks/${r.id}/replay`,
                  title: "重放原任务",
                })
              }
            >
              查证后重放
            </Button>
          )}
        />
      )}
      {tab === "quarantine" && (
        <Rows
          rows={list.data ?? []}
          names={{
            id: "隔离编号",
            topic: "事件来源",
            reason: "原因",
            status: "状态",
            createdAt: "发生时间",
          }}
          actions={(r) => (
            <Button
              disabled={command.busy || r.status === "REPLAYED"}
              onClick={() =>
                setAction({
                  path: `/api/console/events/quarantine/${r.id}/replay`,
                  title: "重放隔离事件",
                })
              }
            >
              核对后重放
            </Button>
          )}
        />
      )}
      {tab === "channels" && (
        <>
          <Rows
            rows={channels.data ?? []}
            names={{
              channel: "渠道",
              enabled: "启用",
              active: "当前请求",
              maxInflight: "并发上限",
              maxPerSecond: "每秒上限",
              failures: "连续失败",
              openUntil: "熔断截止",
              version: "版本",
            }}
            actions={(r) => (
              <Button onClick={() => setEditing(r)}>调整控制</Button>
            )}
          />
          <h2>当前商户路由</h2>
          {merchant ? (
            <>
              <Rows
                rows={routes.data ?? []}
                names={{
                  channel: "渠道",
                  priority: "优先级",
                  enabled: "启用",
                  version: "版本",
                }}
              />
              <Button onClick={() => setRouting(true)}>配置路由</Button>
            </>
          ) : (
            <Alert type="info" title="请选择商户后配置路由" />
          )}
        </>
      )}
      <Modal
        title="调整渠道控制"
        open={!!editing}
        footer={null}
        destroyOnHidden
        onCancel={() => setEditing(null)}
      >
        {editing && (
          <Form
            key={field(editing, "channel")}
            layout="vertical"
            initialValues={{ ...editing, expectedVersion: editing.version }}
            onFinish={async (values) => {
              if (
                await command.run(
                  `/api/console/channel-controls/${editing.channel}`,
                  values,
                )
              )
                setEditing(null);
            }}
          >
            <Form.Item hidden name="expectedVersion">
              <Input />
            </Form.Item>
            {(
              [
                ["maxInflight", "并发上限", 256],
                ["maxPerSecond", "每秒请求上限", 10000],
                ["failureThreshold", "连续失败熔断阈值", 100],
                ["openSeconds", "熔断时长 / 秒", 600],
              ] as const
            ).map(([name, label, max]) => (
              <Form.Item
                key={name}
                name={name}
                label={label}
                rules={[{ required: true }]}
              >
                <InputNumber min={1} max={max} />
              </Form.Item>
            ))}
            <Form.Item name="enabled" label="启用渠道" valuePropName="checked">
              <Switch />
            </Form.Item>
            <Form.Item
              name="reason"
              label="调整原因"
              rules={[{ required: true, max: 1000 }]}
            >
              <Input.TextArea />
            </Form.Item>
            <Button htmlType="submit" loading={command.busy}>
              提交调整
            </Button>
          </Form>
        )}
      </Modal>
      <Modal
        title="配置商户路由"
        open={routing}
        footer={null}
        destroyOnHidden
        onCancel={() => setRouting(false)}
      >
        <Form
          layout="vertical"
          initialValues={{ priority: 10, enabled: true }}
          onFinish={async (values) => {
            const old = routes.data?.find((r) => r.channel === values.channel);
            if (
              await command.run(
                `/api/console/merchants/${encodeURIComponent(merchant)}/routes/${values.channel}`,
                {
                  expectedVersion: old?.version ?? 0,
                  priority: values.priority,
                  enabled: values.enabled,
                  reason: values.reason,
                },
              )
            )
              setRouting(false);
          }}
        >
          <Form.Item name="channel" label="渠道" rules={[{ required: true }]}>
            <Select
              options={(channels.data ?? []).map((r) => ({
                value: field(r, "channel"),
                label: field(r, "channel"),
              }))}
            />
          </Form.Item>
          <Form.Item name="priority" label="优先级（数字越小越优先）">
            <InputNumber min={0} max={1000} />
          </Form.Item>
          <Form.Item name="enabled" label="启用" valuePropName="checked">
            <Switch />
          </Form.Item>
          <Form.Item
            name="reason"
            label="调整原因"
            rules={[{ required: true, max: 1000 }]}
          >
            <Input.TextArea />
          </Form.Item>
          <Button htmlType="submit" loading={command.busy}>
            提交路由
          </Button>
        </Form>
      </Modal>
      <Modal
        title={action?.title}
        open={!!action}
        footer={null}
        destroyOnHidden
        onCancel={() => setAction(null)}
      >
        <Alert
          type="warning"
          title="保留原业务身份，重放不会改选渠道。请先核对原始证据。"
        />
        <Form
          onFinish={async (v) => {
            if (action && (await command.run(action.path, v))) setAction(null);
          }}
        >
          <Form.Item
            name="reason"
            label="查证说明"
            rules={[{ required: true, max: 1000 }]}
          >
            <Input.TextArea />
          </Form.Item>
          <Button htmlType="submit" loading={command.busy}>
            提交重放
          </Button>
        </Form>
      </Modal>
    </>
  );
}

interface ProjectionPage {
  items: Row[];
  nextCursor: string;
  generation: string;
  from: string;
  until: string;
  observedAt: string;
}
interface ProjectionStatus {
  enabled: boolean;
  control: {
    activeGeneration: string | null;
    buildingGeneration: string | null;
  };
  generations: Row[];
  pending: number;
  quarantined: number;
  deadBatches: Row[];
}
export function ReadModel() {
  const { merchant, session } = useWorkspace();
  const operator = platformOperator(session.roles, session.merchantId);
  const [page, setPage] = useState<Record<string, string>>({});
  const [action, setAction] = useState<{
    path: string;
    title: string;
    promote?: boolean;
  } | null>(null);
  const command = useAction();
  useEffect(() => setPage({}), [merchant]);
  const data = useQuery({
    queryKey: ["v2", "projection-payments", merchant, page],
    queryFn: () =>
      api<ProjectionPage>(
        `/api/console/read-model/payments?${queryString({ merchantId: merchant, ...page })}`,
      ),
    enabled: !!merchant,
  });
  const status = useQuery({
    queryKey: ["v2", "projection-status"],
    queryFn: () => api<ProjectionStatus>("/api/console/read-model/status"),
    enabled: operator,
    refetchInterval: 30_000,
  });
  const errors = useQuery({
    queryKey: ["v2", "projection-errors"],
    queryFn: () => api<Row[]>("/api/console/read-model/errors"),
    enabled: operator,
  });
  return (
    <>
      <PageTitle
        title="独立查询投影"
        description="这里的交易列表最终一致，可能延迟；正式调账与结算始终使用 MySQL 权威记录。"
      >
        <Button
          onClick={() => {
            setPage({});
            void data.refetch();
          }}
        >
          重新查询
        </Button>
      </PageTitle>
      {!merchant && (
        <Alert type="info" title="请选择商户查询近七天记录（单次最多31天）" />
      )}
      {[data.error, status.error, errors.error, command.failure]
        .filter(Boolean)
        .map((e, i) => (
          <ErrorNotice key={i} error={e as Error} />
        ))}
      {data.data && (
        <>
          <Alert
            type="info"
            title={`投影代 ${data.data.generation} · 观察时间 ${dateTime(data.data.observedAt)}`}
            description={`创建时间 ${dateTime(data.data.from)} 至 ${dateTime(data.data.until)}；观察时间不代表所有事件已追平。`}
          />
          <Rows
            rows={data.data.items}
            names={{
              merchantOrderNo: "商户业务号",
              status: "投影状态",
              amountMinor: "金额 / 分",
              channel: "渠道",
              updatedAt: "来源更新时间",
            }}
            actions={(r) => <Link to={`/payments/${r.id}`}>查权威详情</Link>}
          />
          <Button
            disabled={!data.data.nextCursor}
            onClick={() =>
              setPage({
                after: data.data!.nextCursor,
                generation: data.data!.generation,
                from: data.data!.from,
                until: data.data!.until,
              })
            }
          >
            下一页
          </Button>
        </>
      )}
      {operator && status.data && (
        <>
          <h2>构建与投递</h2>
          <p>
            待投递 {status.data.pending} · 待处理隔离 {status.data.quarantined}
          </p>
          <Button
            disabled={!!status.data.control.buildingGeneration}
            onClick={() =>
              setAction({
                path: "/api/console/read-model/rebuild",
                title: "创建新的投影代",
              })
            }
          >
            新建重建任务
          </Button>
          <Button
            onClick={() =>
              setAction({
                path: "/api/console/read-model/recover-source-facts",
                title: "从权威事实恢复缺失投影（每次最多32条）",
              })
            }
          >
            恢复缺失来源事实
          </Button>
          <Rows
            rows={status.data.generations}
            names={{
              id: "投影代",
              state: "状态",
              scanComplete: "扫描完成",
              createdAt: "创建时间",
              activatedAt: "激活时间",
            }}
            actions={(r) =>
              r.id === status.data!.control.buildingGeneration ? (
                <Button
                  onClick={() =>
                    setAction({
                      path: `/api/console/read-model/generations/${r.id}/promote`,
                      title: "核对并激活投影代",
                      promote: true,
                    })
                  }
                >
                  停止写入后核对切换
                </Button>
              ) : null
            }
          />
          <h2>失败的查询投递</h2>
          <Rows
            rows={status.data.deadBatches ?? []}
            names={{
              id: "投递编号",
              paymentId: "支付编号",
              attempts: "次数",
              lastError: "错误",
            }}
            actions={(r) => (
              <Button
                onClick={() =>
                  setAction({
                    path: `/api/console/read-model/batches/${r.id}/retry`,
                    title: "重试持久化投递",
                  })
                }
              >
                核对后重试
              </Button>
            )}
          />
          <h2>投影事件隔离</h2>
          <Rows
            rows={errors.data ?? []}
            names={{
              id: "隔离编号",
              state: "状态",
              reason: "原因",
              createdAt: "发生时间",
            }}
            actions={(r) =>
              r.state === "QUARANTINED" ? (
                <Space>
                  <Button
                    onClick={() =>
                      setAction({
                        path: `/api/console/read-model/errors/${r.id}/replay`,
                        title: "重放来源事件",
                      })
                    }
                  >
                    重放
                  </Button>
                  <Button
                    onClick={() =>
                      setAction({
                        path: `/api/console/read-model/errors/${r.id}/dismiss`,
                        title: "确认隔离数据不是有效来源事件",
                      })
                    }
                  >
                    审计后忽略
                  </Button>
                </Space>
              ) : null
            }
          />
        </>
      )}
      <Modal
        title={action?.title}
        open={!!action}
        footer={null}
        destroyOnHidden
        onCancel={() => setAction(null)}
      >
        <Form
          layout="vertical"
          onFinish={async (values) => {
            if (action && (await command.run(action.path, values)))
              setAction(null);
          }}
        >
          {action?.promote && (
            <>
              <Alert
                type="warning"
                title="必须在维护窗口停止并排空业务写入、消费者与投影工作进程。核对期间保持停止，不能仅凭扫描完成切换。"
              />
              <Form.Item
                name="writersStopped"
                valuePropName="checked"
                rules={[
                  {
                    validator: (_, v) =>
                      v
                        ? Promise.resolve()
                        : Promise.reject(new Error("请先停止写入并完成排空")),
                  },
                ]}
              >
                <Checkbox>我已停止写入及消费，并排空全部投递</Checkbox>
              </Form.Item>
            </>
          )}
          <Form.Item
            name="reason"
            label="操作原因"
            rules={[{ required: true, max: 1000 }]}
          >
            <Input.TextArea />
          </Form.Item>
          <Button htmlType="submit" loading={command.busy}>
            提交核对
          </Button>
        </Form>
      </Modal>
    </>
  );
}

export function Balances() {
  const { merchant, session } = useWorkspace();
  const [open, setOpen] = useState(false);
  const command = useAction();
  const data = useQuery({
    queryKey: ["v2", "balances", merchant],
    queryFn: () =>
      api<Row[]>(
        `/api/console/balances?${queryString({ merchantId: merchant })}`,
      ),
    enabled: !!merchant,
  });
  const holds = useQuery({
    queryKey: ["v2", "holds", merchant],
    queryFn: () =>
      api<Row[]>(
        `/api/console/balances/holds?${queryString({ merchantId: merchant })}`,
      ),
    enabled: !!merchant,
  });
  return (
    <>
      <PageTitle
        title="账务检查点"
        description="扫描 MySQL 权威分录，保留来源哈希和账户余额；检查异常会冻结商户结算。"
      >
        <Button
          disabled={
            !merchant ||
            !session.roles.some((r) => ["FINANCE", "OPS"].includes(r))
          }
          onClick={() => setOpen(true)}
        >
          建立权威检查点
        </Button>
      </PageTitle>
      {!merchant && <Alert type="info" title="请选择商户" />}
      {[data.error, holds.error, command.failure]
        .filter(Boolean)
        .map((e, i) => (
          <ErrorNotice key={i} error={e as Error} />
        ))}
      <Rows
        rows={holds.data ?? []}
        names={{
          holdType: "检查类型",
          active: "当前冻结",
          reason: "原因",
          updatedAt: "更新时间",
        }}
      />
      <Table<Row>
        rowKey="id"
        dataSource={data.data ?? []}
        pagination={false}
        expandable={{
          expandedRowRender: (r) => (
            <>
              <p>来源 SHA256：{field(r, "sourceHash")}</p>
              <Descriptions
                column={1}
                items={Object.entries(
                  JSON.parse(field(r, "accountTotals") || "{}") as Record<
                    string,
                    string
                  >,
                ).map(([key, value]) => ({
                  key,
                  label: key,
                  children: <Money value={value} />,
                }))}
              />
              <p>余额以借方为正、贷方为负。{field(r, "detail")}</p>
            </>
          ),
        }}
        columns={[
          { title: "检查时间", dataIndex: "createdAt", render: dateTime },
          {
            title: "结果",
            dataIndex: "status",
            render: (v) => <Status value={v} />,
          },
          { title: "来源分录数", dataIndex: "sourceCount" },
          { title: "检查人", dataIndex: "actor" },
        ]}
      />
      <Modal
        title="建立权威检查点"
        open={open}
        footer={null}
        onCancel={() => setOpen(false)}
        destroyOnHidden
      >
        <Alert
          type="info"
          title="扫描期间会占用商户结算锁；大商户请安排维护窗口。超过预算会保留现有冻结状态。"
        />
        <Form
          onFinish={async (v) => {
            if (
              await command.run("/api/console/balances/checkpoints", {
                merchantId: merchant,
                reason: v.reason,
              })
            )
              setOpen(false);
          }}
        >
          <Form.Item
            name="reason"
            label="检查原因"
            rules={[{ required: true, max: 1000 }]}
          >
            <Input.TextArea />
          </Form.Item>
          <Button htmlType="submit" loading={command.busy}>
            提交检查
          </Button>
        </Form>
      </Modal>
    </>
  );
}

export function SecurityCenter() {
  const { merchant, session } = useWorkspace();
  const command = useAction();
  const status = useQuery({
    queryKey: ["v2", "security-status"],
    queryFn: () =>
      api<{
        confirmationsEnabled: boolean;
        mfaRequired: boolean;
        freshMfa: boolean;
        sharedSessions: boolean;
        stepUpUrl: string;
      }>("/api/console/security/status"),
    refetchInterval: 30_000,
  });
  const sessions = useQuery({
    queryKey: ["v2", "sessions"],
    queryFn: () => api<Row[]>("/api/console/security/sessions"),
  });
  const reminders = useQuery({
    queryKey: ["v2", "reminders", merchant],
    queryFn: () =>
      api<Row[]>(
        `/api/console/security/rotation-reminders?${queryString({ merchantId: merchant })}`,
      ),
    enabled:
      !!merchant &&
      session.roles.some((r) => ["ADMIN", "OPS", "MERCHANT"].includes(r)),
  });
  return (
    <>
      <PageTitle
        title="安全中心"
        description="检查双因素认证、撤销自己的会话并处理密钥轮换提醒。服务端会逐次校验高风险操作。"
      />
      {[status.error, sessions.error, reminders.error, command.failure]
        .filter(Boolean)
        .map((e, i) => (
          <ErrorNotice key={i} error={e as Error} />
        ))}
      {status.data && (
        <>
          <Space wrap>
            <Tag>
              {status.data.sharedSessions
                ? "共享会话已启用"
                : "当前未启用共享会话"}
            </Tag>
            <Tag>
              {status.data.confirmationsEnabled
                ? "操作确认已启用"
                : "当前未启用操作确认"}
            </Tag>
            <Tag>
              {status.data.mfaRequired
                ? "高权限操作强制双因素认证"
                : "当前未强制双因素认证"}
            </Tag>
            <Tag color={status.data.freshMfa ? "green" : "orange"}>
              {status.data.freshMfa ? "双因素认证在有效期内" : "需要重新认证"}
            </Tag>
          </Space>
          <p>高权限操作要求最近五分钟内完成认证。请保存未提交的内容后继续。</p>
          <Button href={status.data.stepUpUrl} type="primary">
            完成双因素认证
          </Button>
        </>
      )}
      <h2>我的登录会话</h2>
      <Rows
        rows={sessions.data ?? []}
        names={{
          createdAt: "登录时间",
          lastAccessAt: "最近访问",
          expiresAt: "到期时间",
          current: "当前会话",
        }}
        actions={(r) => (
          <Button
            danger
            disabled={command.busy}
            onClick={() =>
              void command.run(`/api/console/security/sessions/${r.id}/revoke`)
            }
          >
            撤销会话
          </Button>
        )}
      />
      <h2>超过90天的启用密钥</h2>
      {!merchant ? (
        <Alert type="info" title="选择商户查看轮换提醒" />
      ) : (
        <Rows
          rows={reminders.data ?? []}
          names={{
            id: "密钥编号",
            createdAt: "创建时间",
            expiresAt: "重叠有效期截止",
          }}
        />
      )}
      <p>
        <Link to="/merchants">前往商户与接入完成密钥轮换</Link>
      </p>
    </>
  );
}
