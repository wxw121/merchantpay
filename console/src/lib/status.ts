const labels: Record<string, string> = {
  OPEN: "待调查",
  INVESTIGATED: "已调查",
  RESOLVED: "已解决",
  DRAFT: "待审批",
  APPROVED: "已批准",
  REJECTED: "已驳回",
  EXECUTED: "已执行",
  MOCK_PAID: "已模拟付款",
  CARRIED_FORWARD: "已结转",
  CANCELLED: "已取消",
  PROCESSING: "处理中",
  UNKNOWN: "结果未知",
  CLOSE_PENDING: "关闭确认中",
  SUCCEEDED: "成功",
  FAILED: "明确失败",
  CLOSED: "已关闭",
  PENDING: "待处理",
  RUNNING: "执行中",
  DONE: "已完成",
  DEAD: "重试耗尽",
  QUEUED: "已排队",
  VALIDATING: "校验中",
  MATCHING: "匹配中",
  COMPLETE: "已完成",
  QUARANTINED: "文件已隔离",
  DELIVERED: "已送达",
  SENDING: "发送中",
  PREPARED: "请求已保存",
  RECEIVED: "已接收",
  PROCESSED: "已处理",
  CONFLICT: "证据冲突",
};
export function statusLabel(status: string): string {
  return labels[status] ?? status;
}
export function canRefund(roles: string[], status: string): boolean {
  return (
    status === "SUCCEEDED" &&
    roles.some((role) => ["ADMIN", "MERCHANT"].includes(role))
  );
}
