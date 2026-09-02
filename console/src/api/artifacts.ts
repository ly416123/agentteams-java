import { apiClient, type HttpClient } from './httpClient';

export type ArtifactMetadata = {
  id: string;
  taskId: string;
  attemptId: string;
  name: string;
  contentType: string;
  sizeBytes: number;
  sha256: string;
  status: string;
  metadata: string;
  createdAt: string;
  version: number;
};

export type ArtifactRetentionPolicy = {
  projectId: string;
  configured: boolean;
  successfulTaskRetentionSeconds: number;
  failedTaskRetentionSeconds: number;
  temporaryUploadRetentionSeconds: number;
  legalHold: boolean;
  version: number;
};

export type ArtifactRetentionPolicyUpdate = Omit<
  ArtifactRetentionPolicy,
  'projectId' | 'configured' | 'version'
> & {
  expectedVersion: number;
};

export function listArtifacts(projectId: string, client: HttpClient = apiClient) {
  return client.request<ArtifactMetadata[]>('/api/v1/artifacts', { query: { projectId } });
}

export function listArtifactRetentionPolicy(projectId: string, client: HttpClient = apiClient) {
  return client.request<ArtifactRetentionPolicy>('/api/v1/artifacts/retention', {
    query: { projectId },
  });
}

export function updateArtifactRetentionPolicy(
  projectId: string,
  update: ArtifactRetentionPolicyUpdate,
  client: HttpClient = apiClient,
) {
  return client.request<ArtifactRetentionPolicy>('/api/v1/artifacts/retention', {
    method: 'PUT',
    query: { projectId },
    body: update,
  });
}
