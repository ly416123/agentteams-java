import { apiClient, type HttpClient } from './httpClient';
import type {
  CursorPage,
  EvaluationReportResponse,
  EvaluationRun,
  EvaluationRunRequest,
  EvaluationRunResponse,
} from './types';

export function listEvaluationRuns(
  projectId: string,
  client: HttpClient = apiClient,
) {
  return client.request<CursorPage<EvaluationRun> | EvaluationRun[]>('/api/v1/evaluations/runs', {
    query: { projectId },
  });
}

export function getEvaluationRun(runId: string, client: HttpClient = apiClient) {
  return client.request<EvaluationRun>(`/api/v1/evaluations/runs/${encodeURIComponent(runId)}`);
}

export function createEvaluationRun(
  request: EvaluationRunRequest,
  client: HttpClient = apiClient,
) {
  return client.request<EvaluationRunResponse>('/api/v1/evaluations/runs', {
    method: 'POST',
    body: request,
  });
}

export function getEvaluationReport(runId: string, client: HttpClient = apiClient) {
  return client.request<EvaluationReportResponse>(
    `/api/v1/evaluations/runs/${encodeURIComponent(runId)}/report`,
  );
}
