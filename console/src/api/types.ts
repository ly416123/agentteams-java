export type ProjectId = string;

export type ApiErrorShape = {
  status?: number;
  code?: string;
  message: string;
  details?: Record<string, unknown>;
};

export type Page<T> = {
  items: T[];
  nextCursor?: string;
  total?: number;
  serverTime?: string;
};

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
  phase: string;
  priority: number;
  createdAt: string;
  updatedAt: string;
  version: number;
  teamId?: string;
  workerId?: string;
  creator?: string;
  summary?: string;
};

export type TaskEvent = {
  id: string;
  phase?: string;
  type: string;
  message: string;
  createdAt: string;
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
  configVersion?: string;
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
  tasks: { total: number; queued: number; running: number; succeeded: number; failed: number };
  workers: { ready: number; connecting: number; unhealthy: number; draining: number };
  teams: { total: number; active: number };
  recentTasks: Task[];
  alerts: Array<{ id: string; severity: string; message: string; createdAt: string }>;
};
