export type LabelKind =
  | 'status'
  | 'phase'
  | 'severity'
  | 'source'
  | 'type'
  | 'role'
  | 'runtime'
  | 'decision'
  | 'errorCode';

export type LabelMeta = { label: string; tone: string; priority: number };

const STATUS: Record<string, LabelMeta> = {
  READY: { label: '就绪', tone: 'success', priority: 20 },
  REVIEWING: { label: '审核中', tone: 'warning', priority: 30 },
  PUBLISHED: { label: '已发布', tone: 'success', priority: 20 },
  DEPRECATED: { label: '已弃用', tone: 'neutral', priority: 60 },
  ROLLED_BACK: { label: '已回滚', tone: 'warning', priority: 50 },
  PROVISIONING: { label: '准备中', tone: 'info', priority: 30 },
  BUSY: { label: '忙碌', tone: 'info', priority: 30 },
  OFFLINE: { label: '离线', tone: 'neutral', priority: 70 },
  SUCCEEDED: { label: '已完成', tone: 'success', priority: 10 },
  COMPLETED: { label: '已完成', tone: 'success', priority: 10 },
  ACTIVE: { label: '活跃', tone: 'success', priority: 10 },
  RUNNING: { label: '执行中', tone: 'info', priority: 30 },
  IN_PROGRESS: { label: '进行中', tone: 'info', priority: 30 },
  EXECUTION: { label: '执行阶段', tone: 'info', priority: 30 },
  QUEUED: { label: '排队中', tone: 'info', priority: 40 },
  PAUSED: { label: '已暂停', tone: 'warning', priority: 50 },
  ASSIGNED: { label: '已分配', tone: 'info', priority: 30 },
  ACCEPTED: { label: '已接收', tone: 'info', priority: 30 },
  CREATED: { label: '已创建', tone: 'neutral', priority: 20 },
  DRAFT: { label: '草稿', tone: 'neutral', priority: 40 },
  PENDING: { label: '待处理', tone: 'warning', priority: 40 },
  CONNECTING: { label: '连接中', tone: 'warning', priority: 30 },
  DRAINING: { label: '排空中', tone: 'warning', priority: 50 },
  FAILED: { label: '失败', tone: 'danger', priority: 90 },
  UNHEALTHY: { label: '异常', tone: 'danger', priority: 80 },
  CANCELLED: { label: '已取消', tone: 'neutral', priority: 70 },
  REJECTED: { label: '已拒绝', tone: 'danger', priority: 80 },
  BLOCKED: { label: '已阻塞', tone: 'danger', priority: 80 },
  WAITING: { label: '等待处理', tone: 'warning', priority: 50 },
  TERMINATED: { label: '已终止', tone: 'neutral', priority: 70 },
  DRAINED: { label: '已排空', tone: 'neutral', priority: 60 },
  UPDATING: { label: '更新中', tone: 'info', priority: 30 },
  UPDATE_FAILED: { label: '更新失败', tone: 'danger', priority: 90 },
  ROLLBACK: { label: '回滚中', tone: 'warning', priority: 50 },
  OPEN: { label: '未处理', tone: 'warning', priority: 60 },
  RESOLVED: { label: '已解决', tone: 'success', priority: 10 },
  DEGRADED: { label: '降级', tone: 'warning', priority: 70 },
  HEALTHY: { label: '健康', tone: 'success', priority: 10 },
  ERROR: { label: '错误', tone: 'danger', priority: 90 },
  INFO: { label: '提示', tone: 'info', priority: 20 },
  WARNING: { label: '警告', tone: 'warning', priority: 60 },
  CRITICAL: { label: '严重', tone: 'danger', priority: 90 },
  STALE: { label: '已过期', tone: 'warning', priority: 70 },
  SENT: { label: '已发送', tone: 'success', priority: 10 },
  DISABLED: { label: '已禁用', tone: 'neutral', priority: 70 },
  DELETED: { label: '已删除', tone: 'neutral', priority: 90 },
  SUSPENDED: { label: '已暂停', tone: 'warning', priority: 70 },
  TRIGGERED: { label: '已触发', tone: 'info', priority: 30 },
  RECOVERY_REQUIRED: { label: '需要恢复', tone: 'danger', priority: 90 },
  NOT_ATTEMPTED: { label: '尚未尝试', tone: 'neutral', priority: 40 },
  CONNECTED: { label: '已连接', tone: 'success', priority: 10 },
  RETIRED: { label: '已停用', tone: 'neutral', priority: 70 },
  CLEAN: { label: '正常', tone: 'success', priority: 10 },
  REVIEW_REQUIRED: { label: '需要审核', tone: 'warning', priority: 60 },
  RATE_LIMITED: { label: '触发限流', tone: 'warning', priority: 70 },
  REVOKED: { label: '已撤销', tone: 'neutral', priority: 70 },
  APPROVED: { label: '已批准', tone: 'success', priority: 10 },
  SUCCESS: { label: '成功', tone: 'success', priority: 10 },
  AVAILABLE: { label: '可用', tone: 'success', priority: 10 },
  UNAVAILABLE: { label: '不可用', tone: 'danger', priority: 80 },
  ENABLED: { label: '已启用', tone: 'success', priority: 10 },
  UNDER_BUDGET: { label: '预算内', tone: 'success', priority: 10 },
  SOFT_LIMIT: { label: '接近预算上限', tone: 'warning', priority: 60 },
  HARD_LIMIT: { label: '超过预算上限', tone: 'danger', priority: 90 },
  INSUFFICIENT_DATA: { label: '数据不足', tone: 'neutral', priority: 50 },
  UNPRICED: { label: '未定价', tone: 'warning', priority: 60 },
};

const ROLE: Record<string, string> = {
  OWNER: '所有者',
  ADMIN: '管理员',
  OPERATOR: '运维人员',
  DEVELOPER: '开发者',
  AUDITOR: '审计员',
  VIEWER: '查看者',
  LEADER: '主代理',
  EXECUTOR: '执行代理',
  REQUESTER: '请求方',
  USER: '用户',
  ASSISTANT: '助手',
  MEMBER: '成员',
};

const TYPE: Record<string, string> = {
  ROLLOUT: '发布',
  DRAIN: '排空',
  TERMINATE: '终止',
  SCALE: '扩缩容',
  TASK: '任务',
  EXECUTION: '执行',
  INTERNAL: '内部',
  EXTERNAL: '外部',
  SYSTEM: '系统',
  USER: '用户',
  MESSAGE: '消息',
  WORKER: '工作节点',
  EXECUTOR: '执行工作节点',
  LEADER: '负责人工作节点',
  OPENAI_COMPATIBLE: 'OpenAI 兼容',
  STREAMABLE_HTTP: 'Streamable HTTP',
  SAFE: '安全',
  BLOCKED: '已拦截',
  ALLOWED: '已允许',
  DENIED: '已拒绝',
  TASK_CANCEL: '取消任务',
  TASK_CREATE: '创建任务',
  TASK_UPDATE: '更新任务',
  TASK_RETRY: '重试任务',
  CREDENTIAL_NOT_CONFIGURED: '凭据未配置',
  CONNECTION_FAILED: '连接失败',
  TASK_STARTED: '任务已启动',
  TASK_COMPLETED: '任务已完成',
  TASK_FAILED: '任务失败',
  ORGANIZATION: '组织',
  TENANT: '租户',
  PROJECT: '项目',
  TEAM: '团队',
  AGENT: '代理',
  MODEL: '模型',
  INTEGRATION: '集成',
  SKILL: '技能',
  SANDBOX: '沙箱',
  ARTIFACT: '制品',
  PRIVATE: '私有',
  PUBLIC: '公开',
  SSE: 'SSE',
  'TASK.CREATED': '任务已创建',
  'TASK.UPDATED': '任务已更新',
  'TASK.COMPLETED': '任务已完成',
  'TASK.FAILED': '任务失败',
  'TASK.PROCESS': '任务处理中',
  'TASK.RESULT': '任务结果',
  // 过程事件流（task_process_events.event_type）的小写点号类型。
  'TASK.STARTED': '任务启动',
  'TASK.PROGRESS': '任务执行中',
  'TASK.PLANNED': '任务规划',
  'TASK.CHECKPOINT': '任务检查点',
  PROGRESS: '进度更新',
  TOOL_CALL: '工具调用',
  TOOL_RESULT: '工具结果',
  CHECKPOINT: '检查点',
  WAITING: '等待处理',
  HUMAN_INTERVENTION: '人工介入',
  DECISION: '执行决策',
  APPROVAL: '审批记录',
  SUBTASK_CREATED: '创建子任务',
};

const SOURCE: Record<string, string> = {
  API: '接口',
  CONSOLE: '管理界面',
  SCHEDULE: '调度',
  SYSTEM: '系统',
  USER: '用户',
  WORKER: '工作节点',
  NATS: '消息总线',
  OUTBOX: '事件发件箱',
  UNKNOWN: '未知来源',
};

const RUNTIME: Record<string, string> = {
  QWENPAW: 'QwenPaw',
  OPENAI: 'OpenAI',
  ANTHROPIC: 'Anthropic',
};
const DECISION: Record<string, string> = {
  REQUESTER: '请求方可见',
  INTERNAL_ONLY: '仅内部可见',
  CLEAN: '正常',
  REVIEW_REQUIRED: '需要审核',
  ALLOWED: '已允许',
  BLOCKED: '已拦截',
  DENIED: '已拒绝',
};
const ERROR_CODE: Record<string, string> = {
  REQUEST_FAILED: '请求失败',
  FORBIDDEN: '无权执行此操作',
  UNAUTHENTICATED: '未登录',
  NOT_FOUND: '资源不存在',
  UNAVAILABLE_DEPENDENCY: '依赖服务不可用',
  CONFLICT: '数据版本冲突',
  RATE_LIMITED: '请求过于频繁',
};

function normalized(value: unknown): string {
  return typeof value === 'string' ? value.trim().toUpperCase() : '';
}

export function labelStatus(value: unknown): string {
  const key = normalized(value);
  return STATUS[key]?.label ?? (key ? '未知状态' : '未知状态');
}

export function statusMeta(value: unknown): LabelMeta {
  const key = normalized(value);
  return STATUS[key] ?? { label: '未知状态', tone: 'neutral', priority: 999 };
}

export function labelPhase(value: unknown): string {
  return labelStatus(value);
}
export function labelSeverity(value: unknown): string {
  return labelStatus(value);
}
export function labelSource(value: unknown): string {
  return SOURCE[normalized(value)] ?? (value ? '未知来源' : '未知来源');
}
export function labelType(value: unknown): string {
  return TYPE[normalized(value)] ?? (value ? '未知类型' : '未知类型');
}
export function labelRole(value: unknown): string {
  return ROLE[normalized(value)] ?? (value ? '未知角色' : '未知角色');
}
export function labelRuntime(value: unknown): string {
  return RUNTIME[normalized(value)] ?? '未知运行时';
}
export function labelDecision(value: unknown): string {
  return DECISION[normalized(value)] ?? '未知决策';
}
export function labelErrorCode(value: unknown): string {
  return ERROR_CODE[normalized(value)] ?? '未知错误';
}
export function labelAction(value: unknown): string {
  return labelType(value);
}

export const STATUS_LABELS = STATUS;

// 兼容页面和测试的语义化命名；所有映射仍由本文件维护。
export function labelForStatus(value: unknown): string {
  return labelStatus(value);
}
export function labelForRole(value: unknown): string {
  return labelRole(value);
}
export function labelForType(value: unknown): string {
  return labelType(value);
}
export function labelForErrorCode(value: unknown): string {
  return labelErrorCode(value);
}
export function statusPresentation(value: unknown): Pick<LabelMeta, 'label' | 'tone'> {
  const { label, tone } = statusMeta(value);
  return { label, tone };
}
