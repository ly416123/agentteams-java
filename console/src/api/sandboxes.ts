import { apiClient, type HttpClient } from './httpClient';

export type SandboxMetadata = {
  id: string;
  taskId: string;
  attemptId: string;
  profile: string;
  status: string;
  endpointRef: string | null;
  requestedAt: string;
  expiresAt: string | null;
  lastObservedAt: string | null;
  failureCode: string | null;
  redactedFailureMessage: string | null;
  version: number;
};

export function listSandboxes(projectId: string, client: HttpClient = apiClient) {
  return client.request<SandboxMetadata[]>('/api/v1/sandboxes', { query: { projectId } });
}
