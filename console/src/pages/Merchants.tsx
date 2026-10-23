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
  Modal,
  Space,
  Switch,
  Table,
  Tag,
} from "antd";
import { CopyOutlined, KeyOutlined, PlusOutlined } from "@ant-design/icons";
import { api, field, post, queryString } from "../lib/api";
import type { Row } from "../lib/api";
import { useWorkspace } from "../context";
import { ErrorNotice, PageTitle, timeColumn } from "../components";
interface MerchantInput {
  id: string;
  name: string;
  enabled: boolean;
  notificationUrl: string;
}
interface Key {
  keyId: string;
  merchantId: string;
  secret: string;
}
export function Merchants() {
  const { session, merchant } = useWorkspace();
  const client = useQueryClient();
  const { message } = App.useApp();
  const [search, setSearch] = useState("");
  const [editing, setEditing] = useState<MerchantInput | null>(null);
  const [keysFor, setKeysFor] = useState<string | null>(null);
  const [newKey, setNewKey] = useState<Key | null>(null);
  const [confirmRotate, setConfirmRotate] = useState(false);
  const [busy, setBusy] = useState(false);
  const directory = useQuery({
    queryKey: ["merchants", search],
    queryFn: () =>
      api<Row[]>(`/api/console/merchants?${queryString({ q: search })}`),
  });
  const keys = useQuery({
    queryKey: ["keys", keysFor],
    queryFn: () => api<Row[]>(`/api/console/merchants/${keysFor}/keys`),
    enabled: !!keysFor,
  });
  const admin = session.roles.includes("ADMIN");
  const canKeys = session.roles.some((role) =>
    ["ADMIN", "MERCHANT"].includes(role),
  );
  return (
    <>
      <PageTitle
        title="商户与接入"
        description="管理商户状态、通知地址和 API 凭证。商户停用后拒绝新业务，历史交易仍可查证。"
      >
        {admin && (
          <Button
            type="primary"
            icon={<PlusOutlined aria-hidden="true" />}
            onClick={() =>
              setEditing({
                id: "",
                name: "",
                enabled: true,
                notificationUrl: "",
              })
            }
          >
            创建商户
          </Button>
        )}
      </PageTitle>
      <div className="list-toolbar">
        <Input.Search
          aria-label="搜索商户"
          placeholder="商户编号或名称"
          allowClear
          onSearch={setSearch}
          style={{ maxWidth: 340 }}
        />
        <span className="secondary">
          每次最多显示 100 家，请按名称或编号搜索
        </span>
      </div>
      {directory.error && <ErrorNotice error={directory.error} />}
      <div className="data-panel">
        <Table<Row>
          rowKey="id"
          loading={directory.isPending}
          dataSource={(directory.data ?? []).filter(
            (row) => !merchant || row.id === merchant,
          )}
          pagination={false}
          scroll={{ x: 900 }}
          columns={[
            {
              title: "商户名称 / 编号",
              key: "name",
              width: 240,
              render: (_, row) => (
                <div className="record-link">
                  <strong>{field(row, "name")}</strong>
                  <small>{field(row, "id")}</small>
                </div>
              ),
            },
            {
              title: "状态",
              dataIndex: "enabled",
              render: (value: boolean) => (
                <Tag color={value ? "blue" : "default"}>
                  {value ? "启用" : "已停用"}
                </Tag>
              ),
            },
            {
              title: "通知地址",
              dataIndex: "notificationUrl",
              width: 300,
              render: (value: string) => (
                <span className="break-all secondary">{value || "未配置"}</span>
              ),
            },
            timeColumn,
            {
              title: "操作",
              key: "actions",
              width: 160,
              render: (_, row) => (
                <Space>
                  {admin && (
                    <Button
                      type="link"
                      onClick={() =>
                        setEditing({
                          id: field(row, "id"),
                          name: field(row, "name"),
                          enabled: Boolean(row.enabled),
                          notificationUrl: field(row, "notificationUrl"),
                        })
                      }
                    >
                      配置
                    </Button>
                  )}
                  {canKeys && (
                    <Button
                      type="link"
                      icon={<KeyOutlined aria-hidden="true" />}
                      onClick={() => setKeysFor(field(row, "id"))}
                    >
                      凭证
                    </Button>
                  )}
                </Space>
              ),
            },
          ]}
        />
      </div>
      <section className="integration-note">
        <h2>开放 API 接入</h2>
        <Descriptions
          column={1}
          items={[
            {
              key: "url",
              label: "支付受理",
              children: <code>POST /api/v1/payments</code>,
            },
            {
              key: "signature",
              label: "签名头",
              children: (
                <code>X-Key-Id · X-Timestamp · X-Nonce · X-Signature</code>
              ),
            },
            {
              key: "money",
              label: "金额契约",
              children:
                'CNY 分，以十进制整数字符串传输；例如 100.00 元传 "10000"。',
            },
            {
              key: "idempotency",
              label: "幂等规则",
              children:
                "商户业务号与有效参数一致时返回原支付单。网络异常后保留业务号并使用新 nonce 重试。",
            },
          ]}
        />
      </section>
      <Modal
        title={editing?.id ? "配置商户" : "创建商户"}
        open={!!editing}
        onCancel={() => setEditing(null)}
        footer={null}
        destroyOnHidden
      >
        {editing && (
          <Form<MerchantInput>
            key={editing.id || "new"}
            layout="vertical"
            initialValues={editing}
            onFinish={async (values) => {
              setBusy(true);
              try {
                await post("/api/console/merchants", values);
                void message.success("商户配置已保存");
                setEditing(null);
                void client.invalidateQueries({ queryKey: ["merchants"] });
              } catch (error) {
                void message.error((error as Error).message);
              } finally {
                setBusy(false);
              }
            }}
          >
            <Form.Item
              label="商户编号"
              name="id"
              rules={[
                {
                  required: true,
                  pattern: /^[a-zA-Z0-9_-]{1,64}$/,
                  message: "使用 1～64 位字母、数字、下划线或连字符",
                },
              ]}
            >
              <Input disabled={!!editing.id} />
            </Form.Item>
            <Form.Item
              label="商户名称"
              name="name"
              rules={[{ required: true, max: 100 }]}
            >
              <Input />
            </Form.Item>
            <Form.Item
              label="新业务启用"
              name="enabled"
              valuePropName="checked"
            >
              <Switch />
            </Form.Item>
            <Form.Item
              label="商户通知地址"
              name="notificationUrl"
              extra="使用公网 HTTPS 地址。本地演示接收地址由开发环境单独允许。"
            >
              <Input placeholder="https://merchant.example/payments" />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy} block>
              保存配置
            </Button>
          </Form>
        )}
      </Modal>
      <Drawer
        title={`接口凭证 · ${keysFor ?? ""}`}
        open={!!keysFor}
        onClose={() => setKeysFor(null)}
        size="large"
      >
        <p className="secondary">
          凭证正文仅在创建时显示。轮换后旧密钥保留一小时过渡期，也可立即撤销。
        </p>
        <Button
          type="primary"
          icon={<KeyOutlined aria-hidden="true" />}
          onClick={() => setConfirmRotate(true)}
        >
          生成 / 轮换密钥
        </Button>
        {keys.error && <ErrorNotice error={keys.error} />}
        <Table<Row>
          rowKey="id"
          dataSource={keys.data ?? []}
          loading={keys.isPending}
          pagination={false}
          columns={[
            { title: "凭证编号", dataIndex: "id" },
            {
              title: "状态",
              dataIndex: "enabled",
              render: (value: boolean) => (value ? "启用" : "已撤销"),
            },
            {
              title: "过期时间",
              dataIndex: "expiresAt",
              render: (value: string) =>
                value ? new Date(value).toLocaleString("zh-CN") : "无固定期限",
            },
            {
              title: "操作",
              key: "revoke",
              render: (_, row) =>
                row.enabled && (
                  <Button
                    type="link"
                    danger
                    onClick={() =>
                      Modal.confirm({
                        title: "立即撤销此凭证？",
                        content:
                          "撤销后使用该凭证的 API 请求会被拒绝，请先完成接入方切换。",
                        onOk: async () => {
                          await post(
                            `/api/console/merchants/${keysFor}/keys/${row.id}/revoke`,
                          );
                          void keys.refetch();
                        },
                      })
                    }
                  >
                    撤销
                  </Button>
                ),
            },
          ]}
        />
      </Drawer>
      <Modal
        title="生成新接口密钥"
        open={confirmRotate}
        onCancel={() => setConfirmRotate(false)}
        confirmLoading={busy}
        onOk={async () => {
          setBusy(true);
          try {
            const result = await post<Key>(
              `/api/console/merchants/${keysFor}/keys`,
            );
            setNewKey(result);
            setConfirmRotate(false);
            void keys.refetch();
          } catch (error) {
            void message.error((error as Error).message);
          } finally {
            setBusy(false);
          }
        }}
      >
        <p>新密钥生成后立即可用；此前无固定期限的密钥将在一小时后失效。</p>
      </Modal>
      <Modal
        title="保存接口密钥"
        open={!!newKey}
        footer={
          <Button type="primary" onClick={() => setNewKey(null)}>
            已保存，关闭
          </Button>
        }
        onCancel={() => setNewKey(null)}
        destroyOnHidden
      >
        <Alert
          type="warning"
          showIcon
          title="密钥正文仅在此次显示，请妥善保管。"
        />
        <p>凭证编号</p>
        <Input readOnly value={newKey?.keyId} />
        <p>密钥正文</p>
        <Input.TextArea readOnly value={newKey?.secret} autoSize />
        <Button
          icon={<CopyOutlined aria-hidden="true" />}
          onClick={() => {
            if (newKey)
              void navigator.clipboard
                .writeText(JSON.stringify(newKey))
                .then(() => message.success("已复制到剪贴板"));
          }}
        >
          复制接入凭证
        </Button>
      </Modal>
    </>
  );
}
