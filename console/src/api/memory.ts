import { apiClient, type HttpClient } from './httpClient';

export type MemoryMetadata = {
  id: string;
  policy: {
    scope: string;
    projectId: string | null;
    teamId: string | null;
    taskId: string | null;
    subjectId: string | null;
    sensitivity: string;
    consent: string;
  };
  source: string;
  expiresAt: string | null;
  createdAt: string;
  updatedAt: string;
  version: number;
  governanceStatus: string;
};

export function listMemoryMetadata(projectId: string, client: HttpClient = apiClient) {
  return client.request<MemoryMetadata[]>('/api/v1/memory', { query: { projectId } });
}

export function governMemory(
  memoryId: string,
  operation: 'CONFIRM' | 'REVOKE' | 'FREEZE' | 'DELETE' | 'EXPORT',
  reason: string,
  client: HttpClient = apiClient,
) {
  return client.request<MemoryMetadata | Record<string, unknown>>(
    `/api/v1/memory/${memoryId}/governance`,
    { method: 'POST', body: { operation, reason } },
  );
}
