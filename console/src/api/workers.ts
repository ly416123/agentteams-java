import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage, type Worker, type WorkerOperation } from './types';

export type WorkerFilters = { search?: string; phase?: string; runtime?: string; cursor?: string };
export type WorkerRolloutRequest = {
  expectedVersion: number;
  imageDigest: string;
  runtime: string;
  configRevision: string;
  secretGeneration: string;
  previousStableSpec: string;
};
export function listWorkers(
  projectId: string,
  filters: WorkerFilters = {},
  client: HttpClient = apiClient,
) {
  return client
    .request<CursorPage<Worker> | Worker[]>('/api/v1/agents', {
      query: {
        projectId,
        q: filters.search,
        status: filters.phase,
        cursor: filters.cursor,
      },
    })
    .then(normalizeCursorPage);
}
export function getWorker(projectId: string, workerId: string, client: HttpClient = apiClient) {
  return client.request<Worker>(`/api/v1/agents/${workerId}`, { query: { projectId } });
}
export function listOperations(
  projectId: string,
  workerId: string,
  filters: { cursor?: string } = {},
  client: HttpClient = apiClient,
) {
  return client
    .request<CursorPage<WorkerOperation> | WorkerOperation[]>(
      `/api/v1/agents/${workerId}/operations`,
      { query: { projectId, ...filters } },
    )
    .then(normalizeCursorPage);
}
export function workerAction(
  projectId: string,
  workerId: string,
  action: 'drain' | 'terminate',
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<WorkerOperation>(`/api/v1/agents/${workerId}/operations/${action}`, {
    method: 'POST',
    body: { expectedVersion },
    query: { projectId },
  });
}
export function rolloutWorker(
  projectId: string,
  workerId: string,
  body: WorkerRolloutRequest,
  client: HttpClient = apiClient,
) {
  return client.request<WorkerOperation>(`/api/v1/agents/${workerId}/operations/rollout`, {
    method: 'POST',
    body,
    query: { projectId },
  });
}
export function rollbackWorker(
  projectId: string,
  workerId: string,
  operationId: string,
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<WorkerOperation>(
    `/api/v1/agents/${workerId}/operations/${operationId}/rollback`,
    { method: 'POST', body: { expectedVersion }, query: { projectId } },
  );
}
