import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Modal,
  Result,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import { api, dateTime, post, queryString } from "../lib/api";
import { useWorkspace } from "../context";
import { ErrorNotice, PageTitle } from "../components";

interface Route {
  bucket: number;
  physicalShard: number;
  epoch: number;
}
interface PhysicalShard {
  id: number;
  name: string;
  state: string;
}
interface Directory {
  routes: Route[];
  physicalShards: PhysicalShard[];
  executionEnabled: boolean;
}
interface Plan {
  id: string;
  requestKey: string;
  bucket: number;
  sourceShard: number;
  targetShard: number;
  sourceEpoch: number;
  version: number;
  state: string;
  proposer: string;
  reason: string;
  cancelledBy: string | null;
  cancellationReason: string | null;
  createdAt: string;
  updatedAt: string;
}
interface Resolution {
  kind: string;
  bucket: number;
  physicalShard: number;
  epoch: number;
  existenceVerified: boolean;
  executionEnabled: boolean;
}
type Action =
  | { kind: "prepare"; route: Route; requestKey: string }
  | { kind: "cancel"; plan: Plan }
  | { kind: "reverse"; plan: Plan };

interface ReverseStatus {
  planId: string;
  bucket: number;
  state: string;
  table: string | null;
  copied: number;
  total: number;
}

export function Shards() {
  const { session, merchant } = useWorkspace();
  const operator =
    !session.merchantId &&
    session.roles.some((role) => ["ADMIN", "OPS"].includes(role));
  const client = useQueryClient();
  const { message } = App.useApp();
  const [planState, setPlanState] = useState("PLANNED");
  const [action, setAction] = useState<Action | null>(null);
  const [busy, setBusy] = useState(false);
  const [failure, setFailure] = useState<Error | null>(null);
  const [reverseStatus, setReverseStatus] = useState<ReverseStatus | null>(
    null,
  );
  const [lookupKind, setLookupKind] = useState("PAYMENT");
  const [resolution, setResolution] = useState<Resolution | null>(null);
  const [resolving, setResolving] = useState(false);
  const [resolveError, setResolveError] = useState<Error | null>(null);
  const directory = useQuery({
    queryKey: ["v3", "shards", "directory"],
    queryFn: () => api<Directory>("/api/console/shards"),
    enabled: operator,
  });
  const openPlans = useQuery({
    queryKey: ["v3", "shards", "plans", "PLANNED"],
    queryFn: () => api<Plan[]>("/api/console/shards/plans?state=PLANNED"),
    enabled: operator,
  });
  const routedPlans = useQuery({
    queryKey: ["v3", "shards", "plans", "ROUTED"],
    queryFn: () => api<Plan[]>("/api/console/shards/plans?state=ROUTED"),
    enabled: operator,
  });
  const plans = useQuery({
    queryKey: ["v3", "shards", "plans", planState],
    queryFn: () =>
      api<Plan[]>(
        `/api/console/shards/plans?${queryString({ state: planState })}`,
      ),
    enabled: operator,
  });

  if (!operator)
    return <Result status="403" title="需要平台管理员或运维权限" />;
  const activeBuckets = new Set(
    [...(openPlans.data ?? []), ...(routedPlans.data ?? [])].map(
      (plan) => plan.bucket,
    ),
  );

  async function save(values: {
    targetShard?: number;
    reason: string;
    writersStopped?: boolean;
  }) {
    if (!action) return;
    setBusy(true);
    setFailure(null);
    try {
      if (action.kind === "prepare") {
        await post("/api/console/shards/plans", {
          requestKey: action.requestKey,
          bucket: action.route.bucket,
          targetShard: values.targetShard,
          expectedEpoch: action.route.epoch,
          reason: values.reason,
        });
      } else if (action.kind === "cancel") {
        await post(`/api/console/shards/plans/${action.plan.id}/cancel`, {
          expectedVersion: action.plan.version,
          reason: values.reason,
        });
      } else {
        const result = await post<ReverseStatus>(
          `/api/console/shards/plans/${encodeURIComponent(action.plan.id)}/reverse/next`,
          {
            expectedVersion: action.plan.version,
            reason: values.reason,
            writersStopped: values.writersStopped === true,
          },
        );
        setReverseStatus(result);
      }
      setAction(null);
      await client.invalidateQueries({ queryKey: ["v3", "shards"] });
      void message.success(
        action.kind === "reverse"
          ? "回迁阶段已提交，请核对状态后再执行下一步"
          : "迁移准备记录已保存，请核对最新目录和计划",
      );
    } catch (error) {
      setFailure(error as Error);
    } finally {
      setBusy(false);
    }
  }

  const targets =
    directory.data?.physicalShards.filter(
      (shard) =>
        action?.kind === "prepare" && shard.id !== action.route.physicalShard,
    ) ?? [];
  return (
    <>
      <PageTitle
        title="分片目录"
        description="核对逻辑桶、当前物理归属和迁移准备记录。"
      >
        <Button
          onClick={() =>
            void client.invalidateQueries({ queryKey: ["v3", "shards"] })
          }
        >
          刷新目录
        </Button>
      </PageTitle>
      <Alert
        type="info"
        showIcon
        title="搬迁和回迁按计划与停写证据逐阶段执行；每次提交后先核对目录、栅栏及资金记录。"
      />
      {reverseStatus && (
        <Alert
          type="info"
          showIcon
          style={{ marginTop: 12 }}
          title={`回迁计划 ${reverseStatus.planId}：${reverseStatus.state}`}
          description={
            reverseStatus.table
              ? `${reverseStatus.table}：${reverseStatus.copied} / ${reverseStatus.total}`
              : `逻辑桶 ${reverseStatus.bucket}；下一阶段须重新核对计划状态。`
          }
        />
      )}
      {[
        ...new Set([
          directory.error,
          openPlans.error,
          routedPlans.error,
          plans.error,
        ]),
      ]
        .filter(Boolean)
        .map((error, index) => (
          <ErrorNotice key={index} error={error as Error} />
        ))}
      <h2>当前归属</h2>
      <div className="data-panel">
        <Table<Route>
          rowKey="bucket"
          dataSource={directory.data?.routes ?? []}
          loading={directory.isPending}
          pagination={false}
          scroll={{ x: 650 }}
          columns={[
            { title: "逻辑桶", dataIndex: "bucket" },
            { title: "当前物理分片", dataIndex: "physicalShard" },
            { title: "目录版本", dataIndex: "epoch" },
            {
              title: "迁移准备",
              render: (_, route) => (
                <Button
                  aria-label={`为桶${route.bucket}准备迁移`}
                  disabled={
                    busy ||
                    openPlans.isPending ||
                    openPlans.isError ||
                    routedPlans.isPending ||
                    routedPlans.isError ||
                    activeBuckets.has(route.bucket)
                  }
                  onClick={() => {
                    setFailure(null);
                    setAction({
                      kind: "prepare",
                      route: { ...route },
                      requestKey: crypto.randomUUID(),
                    });
                  }}
                >
                  准备迁移
                </Button>
              ),
            },
          ]}
        />
      </div>
      <h2>物理分片目标</h2>
      <Table<PhysicalShard>
        rowKey="id"
        dataSource={directory.data?.physicalShards ?? []}
        pagination={false}
        columns={[
          { title: "分片编号", dataIndex: "id" },
          { title: "名称", dataIndex: "name" },
          {
            title: "配置状态",
            dataIndex: "state",
            render: (value) => (
              <Tag>{value === "CURRENT_CORE" ? "现有交易库" : "待配置"}</Tag>
            ),
          },
        ]}
      />
      <h2>归属解析</h2>
      <p>解析结果只表示桶和目录归属，不代表交易已经存在或支付成功。</p>
      <Form
        layout="inline"
        disabled={resolving}
        initialValues={{ merchantId: merchant }}
        onValuesChange={() => {
          setResolution(null);
          setResolveError(null);
        }}
        onFinish={async (values: { merchantId?: string; key: string }) => {
          setResolving(true);
          setResolveError(null);
          setResolution(null);
          try {
            setResolution(
              await api<Resolution>(
                `/api/console/shards/resolve?${queryString({ kind: lookupKind, key: values.key, merchantId: lookupKind === "BUSINESS" ? values.merchantId : undefined })}`,
              ),
            );
          } catch (error) {
            setResolveError(error as Error);
          } finally {
            setResolving(false);
          }
        }}
      >
        <Form.Item label="解析方式">
          <Select
            aria-label="解析方式"
            value={lookupKind}
            onChange={(value) => {
              setLookupKind(value);
              setResolution(null);
              setResolveError(null);
            }}
            options={[
              { value: "PAYMENT", label: "支付号" },
              { value: "REFUND", label: "退款号" },
              { value: "BUSINESS", label: "商户业务号" },
            ]}
          />
        </Form.Item>
        {lookupKind === "BUSINESS" && (
          <Form.Item
            label="商户编号"
            name="merchantId"
            rules={[{ required: true, max: 64 }]}
          >
            <Input />
          </Form.Item>
        )}
        <Form.Item
          name="key"
          label={
            lookupKind === "BUSINESS"
              ? "商户业务号"
              : lookupKind === "PAYMENT"
                ? "支付号"
                : "退款号"
          }
          rules={[{ required: true, max: 100 }]}
        >
          <Input />
        </Form.Item>
        <Form.Item>
          <Button htmlType="submit" loading={resolving}>
            解析归属
          </Button>
        </Form.Item>
      </Form>
      {resolveError && <ErrorNotice error={resolveError} />}
      {resolution && (
        <Descriptions
          items={[
            { key: "bucket", label: "逻辑桶", children: resolution.bucket },
            {
              key: "physical",
              label: "当前物理分片",
              children: resolution.physicalShard,
            },
            { key: "epoch", label: "目录版本", children: resolution.epoch },
          ]}
        />
      )}
      <h2>迁移计划</h2>
      <Space>
        <Select
          aria-label="计划状态"
          value={planState}
          onChange={setPlanState}
          options={[
            { value: "PLANNED", label: "准备中" },
            { value: "ROUTED", label: "待激活" },
            { value: "MOVED", label: "已切流" },
            { value: "CANCELLED", label: "已取消" },
          ]}
        />
        <span>最多显示所选状态最近100条记录。</span>
      </Space>
      <div className="data-panel">
        <Table<Plan>
          rowKey="id"
          dataSource={plans.data ?? []}
          loading={plans.isPending}
          pagination={false}
          scroll={{ x: 1200 }}
          expandable={{
            expandedRowRender: (plan) => (
              <Descriptions
                column={1}
                items={[
                  {
                    key: "request",
                    label: "请求键",
                    children: plan.requestKey,
                  },
                  { key: "reason", label: "准备原因", children: plan.reason },
                  {
                    key: "cancelBy",
                    label: "取消人",
                    children: plan.cancelledBy ?? "—",
                  },
                  {
                    key: "cancelReason",
                    label: "取消原因",
                    children: plan.cancellationReason ?? "—",
                  },
                ]}
              />
            ),
          }}
          columns={[
            { title: "计划编号", dataIndex: "id" },
            { title: "逻辑桶", dataIndex: "bucket" },
            { title: "来源分片", dataIndex: "sourceShard" },
            { title: "目标分片", dataIndex: "targetShard" },
            { title: "来源目录版本", dataIndex: "sourceEpoch" },
            { title: "计划版本", dataIndex: "version" },
            {
              title: "状态",
              dataIndex: "state",
              render: (value) => (
                <Tag>
                  {(
                    {
                      PLANNED: "准备中",
                      ROUTED: "待激活",
                      MOVED: "已切流",
                      CANCELLED: "已取消",
                    } as Record<string, string>
                  )[value] ?? value}
                </Tag>
              ),
            },
            { title: "操作人", dataIndex: "proposer" },
            { title: "更新时间", dataIndex: "updatedAt", render: dateTime },
            {
              title: "操作",
              render: (_, plan) => (
                <Space>
                  {plan.state === "PLANNED" && (
                    <Button
                      aria-label={`取消计划${plan.id}`}
                      disabled={busy}
                      onClick={() => {
                        setFailure(null);
                        setAction({ kind: "cancel", plan: { ...plan } });
                      }}
                    >
                      取消计划
                    </Button>
                  )}
                  {plan.sourceShard > 0 &&
                    plan.targetShard === 0 &&
                    ["PLANNED", "MOVED"].includes(plan.state) && (
                      <Button
                        disabled={busy}
                        onClick={async () => {
                          setFailure(null);
                          try {
                            setReverseStatus(
                              await api<ReverseStatus>(
                                `/api/console/shards/plans/${encodeURIComponent(plan.id)}/reverse`,
                              ),
                            );
                          } catch (error) {
                            setFailure(error as Error);
                          }
                        }}
                      >
                        查看回迁阶段
                      </Button>
                    )}
                  {plan.state === "PLANNED" &&
                    plan.sourceShard > 0 &&
                    plan.targetShard === 0 && (
                      <Button
                        danger
                        disabled={busy}
                        onClick={() => {
                          setFailure(null);
                          setAction({ kind: "reverse", plan: { ...plan } });
                        }}
                      >
                        回迁下一阶段
                      </Button>
                    )}
                </Space>
              ),
            },
          ]}
        />
      </div>
      <Modal
        title={
          action?.kind === "prepare"
            ? "准备迁移"
            : action?.kind === "reverse"
              ? "继续反向搬迁"
              : "取消迁移计划"
        }
        open={!!action}
        footer={null}
        destroyOnHidden
        onCancel={() => {
          if (!busy) setAction(null);
        }}
      >
        {action && (
          <>
            <Descriptions
              column={1}
              items={
                action.kind === "prepare"
                  ? [
                      {
                        key: "bucket",
                        label: "逻辑桶",
                        children: action.route.bucket,
                      },
                      {
                        key: "source",
                        label: "当前分片",
                        children: action.route.physicalShard,
                      },
                      {
                        key: "epoch",
                        label: "核对的目录版本",
                        children: action.route.epoch,
                      },
                    ]
                  : [
                      {
                        key: "id",
                        label: "计划编号",
                        children: action.plan.id,
                      },
                      {
                        key: "version",
                        label: "核对的计划版本",
                        children: action.plan.version,
                      },
                    ]
              }
            />
            {failure && <ErrorNotice error={failure} />}
            <Form
              key={
                action.kind === "prepare"
                  ? action.requestKey
                  : `${action.plan.id}-${action.plan.version}`
              }
              layout="vertical"
              initialValues={{ targetShard: targets[0]?.id }}
              onFinish={save}
            >
              {action.kind === "prepare" && (
                <Form.Item
                  name="targetShard"
                  label="目标分片"
                  rules={[{ required: true }]}
                >
                  <Select
                    options={targets.map((target) => ({
                      value: target.id,
                      label: `${target.name}（待完成配置与搬迁核对）`,
                    }))}
                  />
                </Form.Item>
              )}
              <Form.Item
                name="reason"
                label="操作原因"
                rules={[{ required: true, whitespace: true, max: 1000 }]}
              >
                <Input.TextArea />
              </Form.Item>
              {action.kind === "reverse" && (
                <>
                  <Alert
                    type="warning"
                    showIcon
                    style={{ marginBottom: 12 }}
                    title="确认四库业务写入、CDC 和相关 worker 已停写排空"
                    description="本操作每次只推进一个持久阶段。处理中断后应核对审计和阶段状态，再以原计划重试。"
                  />
                  <Form.Item
                    name="writersStopped"
                    valuePropName="checked"
                    rules={[
                      {
                        validator: (_, value) =>
                          value
                            ? Promise.resolve()
                            : Promise.reject(new Error("须确认已停写")),
                      },
                    ]}
                  >
                    <Checkbox>我已确认全部来源停写并排空</Checkbox>
                  </Form.Item>
                </>
              )}
              <Button htmlType="submit" loading={busy}>
                保存
              </Button>
            </Form>
          </>
        )}
      </Modal>
    </>
  );
}
