const STATUS_LABELS: Record<string, { label: string; tone: string }> = {
  READY: { label: '就绪', tone: 'success' },
  REVIEWING: { label: '审核中', tone: 'warning' },
  PUBLISHED: { label: '已发布', tone: 'success' },
  DEPRECATED: { label: '已弃用', tone: 'neutral' },
  ROLLED_BACK: { label: '已回滚', tone: 'warning' },
  PROVISIONING: { label: '准备中', tone: 'info' },
  BUSY: { label: '忙碌', tone: 'info' },
  OFFLINE: { label: '离线', tone: 'neutral' },
  SUCCEEDED: { label: '已完成', tone: 'success' },
  ACTIVE: { label: '活跃', tone: 'success' },
  RUNNING: { label: '执行中', tone: 'info' },
  IN_PROGRESS: { label: '进行中', tone: 'info' },
  QUEUED: { label: '排队中', tone: 'info' },
  PAUSED: { label: '已暂停', tone: 'warning' },
  ASSIGNED: { label: '已分配', tone: 'info' },
  ACCEPTED: { label: '已接收', tone: 'info' },
  CREATED: { label: '已创建', tone: 'neutral' },
  DRAFT: { label: '草稿', tone: 'neutral' },
  PENDING: { label: '待处理', tone: 'warning' },
  CONNECTING: { label: '连接中', tone: 'warning' },
  DRAINING: { label: '排空中', tone: 'warning' },
  FAILED: { label: '失败', tone: 'danger' },
  UNHEALTHY: { label: '异常', tone: 'danger' },
  CANCELLED: { label: '已取消', tone: 'neutral' },
  REJECTED: { label: '已拒绝', tone: 'danger' },
  TERMINATED: { label: '已终止', tone: 'neutral' },
  DRAINED: { label: '已排空', tone: 'neutral' },
  UPDATING: { label: '更新中', tone: 'info' },
  UPDATE_FAILED: { label: '更新失败', tone: 'danger' },
  ROLLBACK: { label: '回滚中', tone: 'warning' },
  WARNING: { label: '警告', tone: 'warning' },
  CRITICAL: { label: '严重', tone: 'danger' },
  OPEN: { label: '未处理', tone: 'warning' },
  RESOLVED: { label: '已解决', tone: 'success' },
  DEGRADED: { label: '降级', tone: 'warning' },
  HEALTHY: { label: '健康', tone: 'success' },
  ERROR: { label: '错误', tone: 'danger' },
  INFO: { label: '提示', tone: 'info' },
  STALE: { label: '已过期', tone: 'warning' },
  SENT: { label: '已发送', tone: 'success' },
  IGNORED: { label: '已忽略', tone: 'neutral' },
  DUPLICATE: { label: '重复', tone: 'neutral' },
  UNDER_BUDGET: { label: '预算内', tone: 'success' },
  SOFT_LIMIT: { label: '接近预算上限', tone: 'warning' },
  HARD_LIMIT: { label: '超过预算上限', tone: 'danger' },
  INSUFFICIENT_DATA: { label: '数据不足', tone: 'neutral' },
  UNPRICED: { label: '未定价', tone: 'warning' },
};

export function StatusBadge({ phase }: { phase: string }) {
  const status = STATUS_LABELS[phase.toUpperCase()] || { label: phase, tone: 'neutral' };
  return (
    <span
      className={`status-badge status-badge--${status.tone}`}
      data-tone={status.tone}
      role="status"
    >
      <span className="status-dot" aria-hidden="true" />
      {status.label}
    </span>
  );
}
