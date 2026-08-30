import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage, type Task, type TaskPhase } from './types';
export { streamTaskEvents } from './taskEvents';

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
  spec: { scope: TaskScope; teamId: string; workerId?: string; [key: string]: unknown };
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
