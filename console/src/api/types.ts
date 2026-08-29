export type ProjectId = string;

export type ApiErrorShape = {
  status?: number;
  code?: string;
  message: string;
  details?: Record<string, unknown>;
};

export type CursorPage<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore?: boolean;
  total?: number;
  serverTime?: string;
};

export type Page<T> = CursorPage<T>;

export function normalizeCursorPage<T>(value: CursorPage<T> | T[]): CursorPage<T> {
  if (Array.isArray(value)) {
    return { items: value, hasMore: false };
  }
  return {
    ...value,
    items: value.items || [],
    hasMore: value.hasMore ?? Boolean(value.nextCursor),
  };
}

export type Project = {
  id: string;
  tenantId: string;
  name: string;
  status: string;
  createdBy: string;
};

export type Team = {
  id: string;
  name: string;
  displayName: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  leaderAgentId?: string;
  agentCount?: number;
  memberCount?: number;
  maxConcurrentTasks?: number;
};

export type TeamMember = {
  id: string;
  teamId: string;
  agentId: string;
  role: string;
  status: string;
  joinedAt: string;
  updatedAt: string;
  version: number;
  runtime?: string;
  capabilities?: string[];
};

export type TeamPolicy = {
  teamId: string;
  maxConcurrentTasks: number;
  requireHumanApproval: boolean;
  allowedRuntimes: string[];
  requiredCapabilities: string[];
  updatedAt: string;
  version: number;
};

export type TeamRevision = {
  teamId: string;
  revision: number;
  leaderAgentId?: string;
  overlayJson: string;
  digest: string;
  status: string;
  rollbackOfRevision?: number;
  createdBy: string;
  createdAt: string;
  version: number;
  memberAgentIds: string[];
};

export type TeamDeployment = {
  id: string;
  teamId: string;
  teamRevision: number;
  status: string;
  members: Array<{ agentId: string; baseManifest: string; taskOverlay: string }>;
  createdAt: string;
};

export type Task = {
  id: string;
  title: string;
  description: string;
  phase: TaskPhase;
  priority: number;
  createdAt: string;
  updatedAt: string;
  version: number;
  teamId?: string;
  workerId?: string;
  creator?: string;
  summary?: string;
};

export const TASK_PHASES = [
  'DRAFT',
  'QUEUED',
  'PAUSED',
  'ASSIGNED',
  'ACCEPTED',
  'RUNNING',
  'SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'REJECTED',
] as const;

export type TaskPhase = (typeof TASK_PHASES)[number];

export type DashboardGroup = {
  provider: string | null;
  model: string | null;
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  estimatedCostUsd: number;
  averageLatencyMillis: number;
  dimension?: string | null;
  dimensionValue?: string | null;
};

export type DashboardSummary = {
  from: string;
  to: string;
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  estimatedCostUsd: number;
  averageLatencyMillis: number;
  byProviderModel: DashboardGroup[];
  groupBy?: string | null;
  groups: DashboardGroup[];
};

export type TaskEvent = {
  id: string;
  cursor?: string;
  phase?: string;
  type: string;
  message: string;
  createdAt?: string;
};

export type Worker = {
  id: string;
  name: string;
  phase: string;
  runtime: string;
  createdAt: string;
  updatedAt: string;
  version: number;
  capabilities?: string[];
  currentTaskId?: string;
  imageVersion?: string;
  imageDigest?: string;
  configVersion?: string;
  configRevision?: string;
  secretGeneration?: string;
  previousStableSpec?: string;
  lastHeartbeat?: string;
  unavailableReason?: string;
};

export type WorkerOperation = {
  id: string;
  agentId: string;
  type: string;
  status: string;
  requestedSpecDigest?: string;
  correlationId?: string;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type Overview = {
  tasks: {
    total: number | null;
    queued: number | null;
    running: number | null;
    succeeded: number | null;
    failed: number | null;
  };
  workers: {
    ready: number | null;
    connecting: number | null;
    unhealthy: number | null;
    draining: number | null;
  };
  teams: { total: number | null; active: number | null };
  recentTasks: Task[];
  alerts: Array<{ id: string; severity: string; message: string; createdAt: string }>;
  usage?: DashboardSummary;
  errors?: Partial<Record<'summary' | 'alerts' | 'teams' | 'tasks' | 'workers', unknown>>;
  metricsUnavailable?: boolean;
};
