import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  Alert,
  App,
  Button,
  Checkbox,
  Descriptions,
  Form,
  Input,
  Modal,
  Radio,
  Space,
  Table,
  Tag,
} from "antd";
import { Link } from "react-router-dom";
import { api, dateTime, post, queryString } from "../lib/api";
import { useWorkspace } from "../context";
import { ErrorNotice, PageTitle } from "../components";

interface Archive {
  id: string;
  merchantId: string;
  paymentId: string;
  logicalBucket: number;
  physicalShard: number;
  routeEpoch: number;
  sourceHash: string;
  objectHash: string;
  factCount: number;
  objectBytes: number;
  archivedBy: string;
  reason: string;
  createdAt: string;
  tier: string;
}
interface Fact {
  kind: string;
  factId: string;
  occurredMicros: number;
  payloadHash: string;
  payloadJson: string;
}
interface Snapshot {
  archive: Archive;
  facts: Fact[];
  consistency: string;
}
interface ArchivePage {
  items: Archive[];
  nextCursor: string | null;
  from: string;
  until: string;
}

export function History() {
  const { merchant, session } = useWorkspace();
  const operator =
    !session.merchantId &&
    session.roles.some((role) => ["ADMIN", "OPS"].includes(role));
  const { message } = App.useApp();
  const [kind, setKind] = useState<"payment" | "business">("payment");
  const [value, setValue] = useState("");
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [failure, setFailure] = useState<Error | null>(null);
  const [busy, setBusy] = useState(false);
  const [retiring, setRetiring] = useState(false);
  const [retireEvent, setRetireEvent] = useState<string | null>(null);
  const [retireEventId, setRetireEventId] = useState("");
  const [retiredEvent, setRetiredEvent] = useState<string | null>(null);
  const requestVersion = useRef(0);
  const [pageRequest, setPageRequest] = useState<{
    from?: string;
    until?: string;
    cursor?: string;
  }>({});
  const recent = useQuery({
    queryKey: ["v3", "history", merchant, pageRequest],
    queryFn: () =>
      api<ArchivePage>(
        `/api/console/history/payments?${queryString({
          merchantId: merchant,
          from: pageRequest.from,
          until: pageRequest.until,
          cursor: pageRequest.cursor,
        })}`,
      ),
    enabled: !!merchant,
  });
  useEffect(() => {
    requestVersion.current++;
    setPageRequest({});
    setSnapshot(null);
    setFailure(null);
    setBusy(false);
    setValue("");
    setRetireEvent(null);
    setRetireEventId("");
    setRetiredEvent(null);
  }, [merchant]);

  async function load(
    selectedKind: "payment" | "business",
    selectedValue: string,
  ) {
    if (!merchant || !selectedValue.trim()) return;
    const version = ++requestVersion.current;
    setBusy(true);
    setFailure(null);
    setSnapshot(null);
    setRetireEvent(null);
    setRetireEventId("");
    try {
      const path =
        selectedKind === "payment"
          ? `/api/console/history/payments/${encodeURIComponent(selectedValue.trim())}`
          : `/api/console/history/payments/by-business/${encodeURIComponent(selectedValue.trim())}`;
      const result = await api<Snapshot>(
        `${path}?${queryString({ merchantId: merchant })}`,
      );
      if (version === requestVersion.current) setSnapshot(result);
    } catch (error) {
      if (version === requestVersion.current) setFailure(error as Error);
    } finally {
      if (version === requestVersion.current) setBusy(false);
    }
  }
  const search = () => load(kind, value);

  return (
    <>
      <PageTitle
        title="历史数据工作台"
        description="按商户和支付根核对 Parquet 历史副本。"
      />
      <Alert
        type="info"
        showIcon
        title="归档对象按完整性校验读取"
        description="支付、退款、账务权威数据仍在交易库；已退役事件可从经校验的归档对象读取。"
      />
      {retiredEvent && (
        <Alert
          type="success"
          showIcon
          style={{ marginTop: 12 }}
          title={`事件 ${retiredEvent} 已退役为 COLD`}
        />
      )}
      {!merchant && (
        <Alert
          type="warning"
          title="请选择商户后查询"
          style={{ marginTop: 16 }}
        />
      )}
      <Space wrap style={{ marginTop: 20, marginBottom: 20 }}>
        <Radio.Group
          value={kind}
          onChange={(event) => {
            setKind(event.target.value as "payment" | "business");
            setValue("");
            setSnapshot(null);
          }}
          options={[
            { label: "支付号", value: "payment" },
            { label: "商户业务号", value: "business" },
          ]}
        />
        <Input
          aria-label={kind === "payment" ? "支付号" : "商户业务号"}
          value={value}
          onChange={(event) => setValue(event.target.value)}
          onPressEnter={() => void search()}
          placeholder="输入完整编号"
          style={{ width: 280 }}
        />
        <Button
          type="primary"
          loading={busy}
          disabled={!merchant || !value.trim()}
          onClick={() => void search()}
        >
          核对归档
        </Button>
      </Space>
      {failure && <ErrorNotice error={failure} retry={() => void search()} />}
      <h2>最近归档</h2>
      <p>
        按归档时间查询最近 7 天，每页最多 50
        条；跨四库仅展示当前目录的权威桶清单。
      </p>
      {recent.error && (
        <ErrorNotice error={recent.error} retry={() => void recent.refetch()} />
      )}
      <Table<Archive>
        rowKey="id"
        loading={recent.isPending}
        dataSource={recent.data?.items ?? []}
        pagination={false}
        columns={[
          { title: "归档时间", dataIndex: "createdAt", render: dateTime },
          { title: "支付号", dataIndex: "paymentId" },
          { title: "逻辑桶", dataIndex: "logicalBucket" },
          { title: "事实数", dataIndex: "factCount" },
          { title: "层级", dataIndex: "tier" },
          {
            title: "操作",
            render: (_, row) => (
              <Button
                type="link"
                onClick={() => {
                  setKind("payment");
                  setValue(row.paymentId);
                  void load("payment", row.paymentId);
                }}
              >
                核对对象
              </Button>
            ),
          },
        ]}
      />
      <Space style={{ marginTop: 12 }}>
        <Button
          disabled={!recent.data?.nextCursor || recent.isFetching}
          onClick={() =>
            recent.data &&
            setPageRequest({
              from: recent.data.from,
              until: recent.data.until,
              cursor: recent.data.nextCursor ?? undefined,
            })
          }
        >
          下一页
        </Button>
        <Button
          disabled={!pageRequest.cursor}
          onClick={() => setPageRequest({})}
        >
          返回最新
        </Button>
      </Space>
      {snapshot && (
        <>
          <Space style={{ marginBottom: 12 }}>
            <Tag color="blue">{snapshot.archive.tier}</Tag>
            <Tag color="orange">{snapshot.consistency}</Tag>
            <Link
              to={`/payments/${encodeURIComponent(snapshot.archive.paymentId)}`}
            >
              查看当前权威支付
            </Link>
          </Space>
          <Descriptions
            bordered
            size="small"
            column={2}
            items={[
              {
                key: "payment",
                label: "支付号",
                children: snapshot.archive.paymentId,
              },
              {
                key: "bucket",
                label: "逻辑桶",
                children: snapshot.archive.logicalBucket,
              },
              {
                key: "source",
                label: "物理来源 / epoch",
                children: `${snapshot.archive.physicalShard} / ${snapshot.archive.routeEpoch}`,
              },
              {
                key: "count",
                label: "事实数",
                children: snapshot.archive.factCount,
              },
              {
                key: "bytes",
                label: "对象字节数",
                children: snapshot.archive.objectBytes,
              },
              {
                key: "time",
                label: "归档时间",
                children: dateTime(snapshot.archive.createdAt),
              },
              {
                key: "actor",
                label: "归档人",
                children: snapshot.archive.archivedBy,
              },
              {
                key: "reason",
                label: "归档原因",
                children: snapshot.archive.reason,
                span: 2,
              },
              {
                key: "hash",
                label: "对象 SHA-256",
                children: snapshot.archive.objectHash,
                span: 2,
              },
              {
                key: "sourceHash",
                label: "来源 SHA-256",
                children: snapshot.archive.sourceHash,
                span: 2,
              },
            ]}
          />
          <h2>历史事实</h2>
          <p>
            对象内共 {snapshot.facts.length} 条；页面仅展示前 100
            条。正文按事实展开查看。
          </p>
          {operator && (
            <Space wrap style={{ marginBottom: 12 }}>
              <Input
                aria-label="待退役事件号"
                value={retireEventId}
                onChange={(event) => setRetireEventId(event.target.value)}
                placeholder="输入归档内完整事件号"
                style={{ width: 290 }}
              />
              <Button
                danger
                disabled={
                  retiring ||
                  !snapshot.facts.some(
                    (fact) =>
                      fact.kind === "EVENT" &&
                      fact.factId === retireEventId.trim(),
                  )
                }
                onClick={() => {
                  setFailure(null);
                  setRetireEvent(retireEventId.trim());
                }}
              >
                按事件号退役
              </Button>
            </Space>
          )}
          <Table<Fact>
            rowKey={(fact) => `${fact.kind}:${fact.factId}`}
            dataSource={snapshot.facts.slice(0, 100)}
            pagination={{ pageSize: 20 }}
            expandable={{
              expandedRowRender: (fact) => (
                <pre
                  style={{ whiteSpace: "pre-wrap", overflowWrap: "anywhere" }}
                >
                  {fact.payloadJson}
                </pre>
              ),
            }}
            columns={[
              { title: "类型", dataIndex: "kind" },
              { title: "事实编号", dataIndex: "factId" },
              { title: "发生时间（微秒）", dataIndex: "occurredMicros" },
              {
                title: "内容 SHA-256",
                dataIndex: "payloadHash",
                ellipsis: true,
              },
              {
                title: "操作",
                render: (_, fact) =>
                  operator && fact.kind === "EVENT" ? (
                    <Button
                      danger
                      size="small"
                      disabled={retiring}
                      aria-label={`退役事件${fact.factId}`}
                      onClick={() => {
                        setFailure(null);
                        setRetireEvent(fact.factId);
                      }}
                    >
                      退役热事件
                    </Button>
                  ) : null,
              },
            ]}
          />
        </>
      )}
      <Modal
        title="退役热事件"
        open={!!retireEvent}
        footer={null}
        destroyOnHidden
        onCancel={() => {
          if (!retiring) setRetireEvent(null);
        }}
      >
        {retireEvent && snapshot && merchant && (
          <>
            <Alert
              type="warning"
              showIcon
              title="核对来源停写、回执和归档对象后执行"
              description="服务端将再次验证30天终态、完整对象、事件回执和当前路由；失败时不会删除热事件。"
            />
            <Descriptions
              column={1}
              size="small"
              style={{ marginTop: 12 }}
              items={[
                {
                  key: "payment",
                  label: "支付号",
                  children: snapshot.archive.paymentId,
                },
                { key: "event", label: "事件号", children: retireEvent },
                {
                  key: "archive",
                  label: "归档号",
                  children: snapshot.archive.id,
                },
              ]}
            />
            {failure && <ErrorNotice error={failure} />}
            <Form
              layout="vertical"
              key={retireEvent}
              onFinish={async (values: {
                reason: string;
                writersStopped: boolean;
              }) => {
                setRetiring(true);
                setFailure(null);
                try {
                  const result = await post<{ eventId: string; state: string }>(
                    `/api/console/history/payments/${encodeURIComponent(snapshot.archive.paymentId)}` +
                      `/events/${encodeURIComponent(retireEvent)}/retire`,
                    {
                      merchantId: merchant,
                      reason: values.reason,
                      writersStopped: values.writersStopped === true,
                    },
                  );
                  setRetiredEvent(result.eventId);
                  setRetireEvent(null);
                  void message.success(`事件已处于 ${result.state} 状态`);
                } catch (error) {
                  setFailure(error as Error);
                } finally {
                  setRetiring(false);
                }
              }}
            >
              <Form.Item
                name="reason"
                label="退役原因"
                rules={[{ required: true, whitespace: true, max: 1000 }]}
              >
                <Input.TextArea />
              </Form.Item>
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
                <Checkbox>我已确认来源停写并排空</Checkbox>
              </Form.Item>
              <Button
                type="primary"
                danger
                htmlType="submit"
                loading={retiring}
              >
                确认退役
              </Button>
            </Form>
          </>
        )}
      </Modal>
    </>
  );
}
