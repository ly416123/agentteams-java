import { apiClient, type HttpClient } from './httpClient';
import {
  normalizeCursorPage,
  type CursorPage,
  type Task,
  type TaskDecisionRecord,
  type TaskPhase,
  type TaskProcessEvent,
  type TaskProgressSnapshot,
  type TaskResultManifest,
  type TaskTreeNode,
} from './types';
export { streamTaskEvents } from './taskEvents';
export { createTaskProcessEventParser, streamTaskProcessEvents } from './taskProcessEvents';
export type { TaskProcessEventStreamOptions } from './taskProcessEvents';

export type TaskFilters = {
  q?: string;
  phase?: TaskPhase | '';
  teamId?: string;
  workerId?: string;
  creator?: string;
  from?: string;
  to?: string;
  cursor?: string;
};
export type TaskScope = { tenant: string; project: string; team: string };
export type CreateTaskRequest = {
  title: string;
  description: string;
  taskType?: string;
  spec: { scope: TaskScope; teamId: string; workerId?: string; [key: string]: unknown };
};

export type TaskExecution = {
  attempt: {
    id: string;
    taskId: string;
    leaseId: string;
    phase: string;
    leaseExpiresAt: string;
    completedAt?: string | null;
    actor: string;
    source: string;
    failureCode?: string | null;
    createdAt: string;
    updatedAt: string;
    version: number;
  };
  assignment?: {
    id: string;
    taskId: string;
    attemptId: string;
    agentId: string;
    phase: string;
    assignedAt: string;
    acceptedAt?: string | null;
    releasedAt?: string | null;
    version: number;
  } | null;
  lease?: {
    id: string;
    agentId: string;
    taskAttemptId: string;
    acquiredAt: string;
    expiresAt: string;
    releasedAt?: string | null;
    status: string;
    version: number;
  } | null;
};
export type TaskRun = {
  id: string;
  taskId: string;
  status: string;
  startedAt?: string | null;
  completedAt?: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  resultStatus?: string | null;
  resultSummary?: string | null;
};
export type TaskCheckpoint = {
  id: string;
  taskId: string;
  runId: string;
  attemptId?: string | null;
  stepKey: string;
  idempotencyKey: string;
  status: string;
  checkpointRef: string;
  createdAt: string;
  updatedAt: string;
  version: number;
};
export type TaskRecoveryState = {
  taskId: string;
  recoveryCount: number;
  maxRecoveryAttempts: number;
  status: 'READY' | 'RECOVERY_REQUIRED' | string;
  lastReason?: string | null;
  nextAttemptAt?: string | null;
  lastRecoveredAt?: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
};

function taskQuery(projectId: string, filters: TaskFilters) {
  return Object.fromEntries(
    Object.entries({ projectId, ...filters })
      .map(([key, value]) => [key === 'creator' ? 'actor' : key, value])
      .filter(([, value]) => value !== undefined && value !== ''),
  );
}

export function listTasks(
  projectId: string,
  filters: TaskFilters = {},
  client: HttpClient = apiClient,
) {
  return client
    .request<CursorPage<Task> | Task[]>('/api/v1/tasks', { query: taskQuery(projectId, filters) })
    .then(normalizeCursorPage);
}
export function getTask(taskId: string, client: HttpClient = apiClient) {
  return client.request<Task>(`/api/v1/tasks/${taskId}`);
}
export function getTaskExecution(taskId: string, client: HttpClient = apiClient) {
  return client.request<TaskExecution[]>(`/api/v1/tasks/${taskId}/execution`);
}
export function getTaskRuns(taskId: string, client: HttpClient = apiClient) {
  return client.request<TaskRun[]>(`/api/v1/tasks/${taskId}/runs`);
}
export function getTaskProcessEvents(
  taskId: string,
  runId: string,
  after = 0,
  client: HttpClient = apiClient,
) {
  return client.request<TaskProcessEvent[]>(`/api/v1/tasks/${taskId}/runs/${runId}/process-events`, {
    query: { after, visibility: 'REQUESTER' },
  });
}
export function getTaskProgress(taskId: string, runId: string, client: HttpClient = apiClient) {
  return client.request<TaskProgressSnapshot>(`/api/v1/tasks/${taskId}/runs/${runId}/progress`, {
    query: { phase: 'EXECUTION' },
  });
}
export function getTaskTree(taskId: string, runId: string, client: HttpClient = apiClient) {
  return client.request<TaskTreeNode[]>(`/api/v1/tasks/${taskId}/runs/${runId}/tree`);
}
export function getTaskDecisions(taskId: string, runId: string, client: HttpClient = apiClient) {
  return client.request<TaskDecisionRecord[]>(`/api/v1/tasks/${taskId}/runs/${runId}/decisions`, {
    query: { visibility: 'REQUESTER' },
  });
}
export function getTaskResult(taskId: string, runId: string, client: HttpClient = apiClient) {
  return client.request<TaskResultManifest>(`/api/v1/tasks/${taskId}/runs/${runId}/result`, {
    query: { visibility: 'REQUESTER' },
  });
}
export function getTaskCheckpoints(taskId: string, runId: string, client: HttpClient = apiClient) {
  return client.request<TaskCheckpoint[]>(`/api/v1/tasks/${taskId}/runs/${runId}/checkpoints`);
}
export function getTaskRecovery(taskId: string, client: HttpClient = apiClient) {
  return client
    .request<TaskRecoveryState | null | undefined>(`/api/v1/tasks/${taskId}/recovery`)
    .then((value) => value ?? null);
}
export function createTask(body: CreateTaskRequest, client: HttpClient = apiClient) {
  if (
    !body.spec ||
    typeof body.spec !== 'object' ||
    !body.spec.scope ||
    !body.spec.teamId ||
    !body.spec.scope.tenant ||
    !body.spec.scope.project ||
    !body.spec.scope.team
  ) {
    return Promise.reject(new Error('spec.scope 和 spec.teamId 是必填项'));
  }
  return client.request<Task>('/api/v1/tasks', { method: 'POST', body });
}
export function taskAction(
  taskId: string,
  action: 'queue' | 'cancel' | 'retry' | 'pause' | 'approve' | 'reject',
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<Task>(`/api/v1/tasks/${taskId}/${action}`, {
    method: 'POST',
    body: { expectedVersion },
  });
}
