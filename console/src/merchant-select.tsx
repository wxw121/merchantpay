import { useEffect, useState } from "react";
import type { CSSProperties } from "react";
import { useQuery } from "@tanstack/react-query";
import { Button, Select } from "antd";
import { api, queryString } from "./lib/api";
import type { Row } from "./lib/api";

export function MerchantSelect({
  value,
  onChange,
  disabled,
  allowAll = false,
  id,
  style,
  "aria-label": label = "商户",
}: {
  value?: string;
  onChange?: (value: string) => void;
  disabled?: boolean;
  allowAll?: boolean;
  id?: string;
  style?: CSSProperties;
  "aria-label"?: string;
}) {
  const [search, setSearch] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim()), 250);
    return () => clearTimeout(timer);
  }, [search]);
  const result = useQuery({
    queryKey: ["merchants", "options", query],
    queryFn: () =>
      api<Row[]>(`/api/console/merchants?${queryString({ q: query })}`),
    enabled: !disabled,
  });
  const selected = useQuery({
    queryKey: ["merchants", "selected", value],
    queryFn: () =>
      api<Row>(`/api/console/merchants/${encodeURIComponent(value!)}`),
    enabled: !!value,
  });
  const rows = new Map((result.data ?? []).map((row) => [String(row.id), row]));
  if (selected.data) rows.set(String(selected.data.id), selected.data);
  return (
    <Select<string>
      id={id}
      aria-label={label}
      value={value}
      disabled={disabled}
      style={{ width: "100%", ...style }}
      placeholder="搜索商户名称或编号"
      showSearch
      filterOption={false}
      onSearch={setSearch}
      onChange={(next) => {
        setSearch("");
        onChange?.(next);
      }}
      loading={result.isFetching || selected.isFetching}
      notFoundContent={
        result.error ? (
          <Button onClick={() => void result.refetch()}>加载失败，重试</Button>
        ) : (
          "暂无匹配商户"
        )
      }
      options={[
        ...(allowAll ? [{ value: "", label: "全部商户" }] : []),
        ...Array.from(rows.values(), (row) => ({
          value: String(row.id),
          label: String(row.name),
        })),
      ]}
    />
  );
}
