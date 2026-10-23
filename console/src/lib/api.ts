export interface Session {
  actor: string;
  merchantId: string;
  roles: string[];
  csrfToken: string;
  csrfHeader: string;
}
export type Row = Record<string, string | number | boolean | null>;
export interface Page {
  items: Row[];
  nextCursor: string | null;
  from: string;
  until: string;
}
export interface Payment {
  id: string;
  merchantId: string;
  merchantOrderNo: string;
  amountMinor: string;
  feeMinor: string;
  currency: string;
  status: string;
  refundedMinor: string;
  reservedMinor: string;
  refundedFeeMinor: string;
  channel: string;
  channelReference: string | null;
  notificationUrl: string | null;
  createdAt: string;
  updatedAt: string;
}
export type EvidenceResource =
  "refunds" | "journals" | "attempts" | "tasks" | "conflicts";
export interface PaymentDetail {
  payment: Payment;
  refunds: Row[];
  journals: Row[];
  entries: Row[];
  attempts: Row[];
  tasks: Row[];
  conflicts: Row[];
  nextCursors: Record<EvidenceResource, string | null>;
}
export interface BatchDetail {
  batch: Row;
  issues: Row[];
  nextCursor: string | null;
}
export class ApiError extends Error {
  constructor(
    public code: string,
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
let csrf: Pick<Session, "csrfToken" | "csrfHeader"> | null = null;
export function setCsrf(session: Session) {
  csrf = session;
}
export async function api<T>(
  path: string,
  options: RequestInit = {},
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body && !(options.body instanceof FormData))
    headers.set("Content-Type", "application/json");
  if (options.method && options.method !== "GET" && csrf)
    headers.set(csrf.csrfHeader, csrf.csrfToken);
  const response = await fetch(path, {
    ...options,
    headers,
    credentials: "include",
  });
  if (!response.ok) {
    const error = (await response.json().catch(() => ({
      code: "NETWORK_ERROR",
      message: `请求失败（${response.status}）`,
    }))) as { code: string; message: string };
    throw new ApiError(error.code, error.message, response.status);
  }
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}
export const post = <T>(path: string, body?: unknown) =>
  api<T>(path, {
    method: "POST",
    body: body === undefined ? undefined : JSON.stringify(body),
  });
export function queryString(values: Record<string, string | undefined | null>) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(values))
    if (value) query.set(key, value);
  return query.toString();
}
export const field = (row: Row, key: string) =>
  row[key] === null || row[key] === undefined ? "" : String(row[key]);
export const dateTime = (value: string) =>
  value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
