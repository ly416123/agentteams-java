import { apiClient, type HttpClient } from './httpClient';

export type ArtifactQualityEvent = {
  id: string;
  taskId: string | null;
  runId: string | null;
  sequence: number;
  eventType: string;
  artifactName: string | null;
  fileId: string | null;
  manifestRef: string | null;
  sha256: string | null;
  categoryCode: string | null;
  checkItem: string | null;
  actionSource: string | null;
  status: string;
  score: number | null;
  scoreDelta: number | null;
  summary: string;
  sourceType: string;
  confidence: string;
  occurredAt: string;
};

export type ArtifactQualityFilters = {
  taskId?: string;
  artifactName?: string;
  status?: string;
  issueType?: string;
  from?: string;
  to?: string;
  page?: number;
  pageSize?: number;
};

export type ArtifactQualityEventPage = {
  events: ArtifactQualityEvent[];
  total: number;
  page: number;
  pageSize: number;
};

export function listArtifactQualityEvents(
  projectId: string,
  filters: ArtifactQualityFilters = {},
  client: HttpClient = apiClient,
) {
  return client.request<ArtifactQualityEventPage>('/api/v1/artifact-quality/events', {
    query: { projectId, page: 0, pageSize: 20, ...filters },
  });
}
