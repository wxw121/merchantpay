import { Alert, Button, Empty, Input, Space, Table, Tag } from "antd";
import { ReloadOutlined } from "@ant-design/icons";
import { Link } from "react-router-dom";
import { useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { ReactNode } from "react";
import type { ColumnsType } from "antd/es/table";
import { api, dateTime, field, queryString } from "./lib/api";
import type { Page, Row } from "./lib/api";
import { formatMinor } from "./lib/money";
import { statusLabel } from "./lib/status";
import { useWorkspace } from "./context";
export function Status({ value }: { value: string }) {
  return (
    <Tag className={`status status-${value.toLowerCase()}`}>
      {statusLabel(value)}
    </Tag>
  );
}
export function Money({ value }: { value?: string | null }) {
  return (
    <span className="money">
      {value === undefined || value === null || value === ""
        ? "—"
        : formatMinor(value)}
    </span>
  );
}
export function ErrorNotice({
  error,
  retry,
}: {
  error: Error;
  retry?: () => void;
}) {
  return (
    <Alert
      type="error"
      showIcon
      title={error.message}
      action={retry && <Button onClick={retry}>重试</Button>}
    />
  );
}
export function PageTitle({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="page-title">
      <div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <Space wrap>{children}</Space>
    </div>
  );
}
export const timeColumn = {
  title: "创建时间",
  dataIndex: "createdAt",
  key: "createdAt",
  width: 174,
  render: (value: string) => (
    <span className="secondary">{dateTime(value)}</span>
  ),
};
export function PaymentLink({
  id,
  children,
}: {
  id: string;
  children?: ReactNode;
}) {
  return (
    <Link className="record-link" to={`/payments/${encodeURIComponent(id)}`}>
      {children || id}
    </Link>
  );
}
export function PagedTable({
  resource,
  columns,
  status,
  refreshKey = 0,
}: {
  resource: string;
  columns: ColumnsType<Row>;
  status?: string;
  refreshKey?: number;
}) {
  const { merchant } = useWorkspace();
  const [dates, setDates] = useState({ from: "", until: "" });
  const [range, setRange] = useState<{ from?: string; until?: string }>({});
  const [rangeError, setRangeError] = useState("");
  const scope = JSON.stringify([
    resource,
    status,
    merchant,
    range.from,
    range.until,
  ]);
  const [paging, setPaging] = useState<{
    scope: string;
    cursors: (string | null)[];
  }>({ scope, cursors: [null] });
  useEffect(() => setPaging({ scope, cursors: [null] }), [scope]);
  const cursors = paging.scope === scope ? paging.cursors : [null];
  const setCursors = (
    update: (previous: (string | null)[]) => (string | null)[],
  ) => setPaging({ scope, cursors: update(cursors) });
  const current = cursors.at(-1);
  const result = useQuery({
    queryKey: [
      "list",
      resource,
      merchant,
      status,
      range.from,
      range.until,
      current,
      refreshKey,
    ],
    queryFn: () =>
      api<Page>(
        `/api/console/${resource}?${queryString({ merchantId: merchant, status, ...range, cursor: current })}`,
      ),
    refetchInterval: 5_000,
  });
  return (
    <div className="data-panel">
      <form
        className="list-toolbar date-filters"
        onSubmit={(event) => {
          event.preventDefault();
          if (!dates.from || !dates.until) {
            setRangeError("请同时选择开始和截止日期，或使用最近七天");
            return;
          }
          const from = new Date(`${dates.from}T00:00:00+08:00`),
            until = new Date(`${dates.until}T00:00:00+08:00`);
          const duration = until.getTime() - from.getTime();
          if (!(duration > 0 && duration <= 31 * 86_400_000)) {
            setRangeError("在线查询最多 31 天，开始日期必须早于截止日期");
            return;
          }
          setRangeError("");
          setPaging({ scope, cursors: [null] });
          setRange({ from: from.toISOString(), until: until.toISOString() });
        }}
      >
        <label>
          开始日期（北京时间，含）
          <Input
            type="date"
            aria-label="开始日期（北京时间，含）"
            value={dates.from}
            onChange={(event) =>
              setDates({ ...dates, from: event.target.value })
            }
          />
        </label>
        <label>
          截止日期（北京时间，不含）
          <Input
            type="date"
            aria-label="截止日期（北京时间，不含）"
            value={dates.until}
            onChange={(event) =>
              setDates({ ...dates, until: event.target.value })
            }
          />
        </label>
        <Button htmlType="submit" type="primary">
          查询日期范围
        </Button>
        <Button
          onClick={() => {
            setDates({ from: "", until: "" });
            setRange({});
            setRangeError("");
            setPaging({ scope, cursors: [null] });
          }}
        >
          最近七天
        </Button>
      </form>
      {rangeError && <Alert type="warning" showIcon title={rangeError} />}
      {result.error && (
        <ErrorNotice error={result.error} retry={() => void result.refetch()} />
      )}
      <Table<Row>
        rowKey={(row) => field(row, "id")}
        columns={columns}
        dataSource={result.data?.items ?? []}
        loading={result.isPending}
        pagination={false}
        scroll={{ x: 900 }}
        locale={{
          emptyText: (
            <Empty
              description="当前条件下暂无记录"
              image={Empty.PRESENTED_IMAGE_SIMPLE}
            />
          ),
        }}
      />
      <div className="table-footer">
        <span>
          第 {cursors.length} 页 · 每页最多 50 条{" "}
          {result.data && `· 截止 ${dateTime(result.data.until)}`}
        </span>
        <Space>
          <Button
            icon={<ReloadOutlined aria-hidden="true" />}
            onClick={() => void result.refetch()}
            aria-label="刷新列表"
          />
          <Button
            disabled={cursors.length === 1}
            onClick={() => setCursors((previous) => previous.slice(0, -1))}
          >
            上一页
          </Button>
          <Button
            disabled={!result.data?.nextCursor}
            onClick={() =>
              setCursors((previous) => [...previous, result.data!.nextCursor])
            }
          >
            下一页
          </Button>
        </Space>
      </div>
    </div>
  );
}
