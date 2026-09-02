import { apiClient, type HttpClient } from './httpClient';

export type ScheduledTask = {
  id: string;
  name: string;
  organizationId: string;
  tenantId: string;
  projectId?: string | null;
  cronExpression: string;
  timeZone: string;
  title: string;
  enabled: boolean;
  nextRunAt?: string | null;
  lastRunAt?: string | null;
  lastTaskId?: string | null;
  version: number;
};

export type ScheduledTaskRun = {
  id: string;
  scheduleId: string;
  taskId: string;
  executionRunId?: string | null;
  occurrenceAt: string;
  status: string;
  taskPhase?: string | null;
  resultStatus?: string | null;
  resultSummary?: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type ScheduleScope = { organizationId: string; tenantId: string; projectId?: string };

function scopeQuery(scope: ScheduleScope) {
  return Object.fromEntries(
    Object.entries(scope).filter(([, value]) => value !== undefined && value !== ''),
  );
}

export function listScheduledTasks(scope: ScheduleScope, client: HttpClient = apiClient) {
  return client.request<ScheduledTask[]>('/api/v1/scheduled-tasks', {
    query: scopeQuery(scope),
  });
}

export function listScheduledTaskRuns(
  scheduleId: string,
  scope: ScheduleScope,
  client: HttpClient = apiClient,
) {
  return client.request<ScheduledTaskRun[]>(`/api/v1/scheduled-tasks/${scheduleId}/runs`, {
    query: scopeQuery(scope),
  });
}

export function updateScheduledTask(
  scheduleId: string,
  action: 'pause' | 'resume',
  scope: ScheduleScope,
  client: HttpClient = apiClient,
) {
  return client.request<ScheduledTask>(`/api/v1/scheduled-tasks/${scheduleId}/${action}`, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: scope,
  });
}

export function cancelScheduledTaskRun(
  scheduleId: string,
  runId: string,
  scope: ScheduleScope,
  client: HttpClient = apiClient,
) {
  return client.request<ScheduledTaskRun>(
    `/api/v1/scheduled-tasks/${scheduleId}/runs/${runId}/cancel`,
    {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: scope,
    },
  );
}
