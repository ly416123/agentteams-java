import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage, type Task } from './types';
import { parseTaskEventStream } from './taskEvents';

export type TaskFilters = {
  q?: string;
  phase?: string;
  teamId?: string;
  workerId?: string;
  creator?: string;
  from?: string;
  to?: string;
  cursor?: string;
};
export function listTasks(
  projectId: string,
  filters: TaskFilters = {},
  client: HttpClient = apiClient,
) {
  return client
    .request<CursorPage<Task> | Task[]>('/api/v1/tasks', { query: { projectId, ...filters } })
    .then(normalizeCursorPage);
}
export function getTask(taskId: string, client: HttpClient = apiClient) {
  return client.request<Task>(`/api/v1/tasks/${taskId}`);
}
export function listTaskEvents(taskId: string, client: HttpClient = apiClient) {
  return client
    .requestText(`/api/v1/tasks/${taskId}/events`, {
      headers: { Accept: 'text/event-stream, application/json' },
    })
    .then((payload) => parseTaskEventStream(payload));
}
export function createTask(
  projectId: string,
  body: Record<string, unknown>,
  client: HttpClient = apiClient,
) {
  return client.request<Task>('/api/v1/tasks', { method: 'POST', body: { ...body, projectId } });
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
