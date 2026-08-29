import { apiClient, type HttpClient } from './httpClient';
import type { Overview } from './types';

export function getOverview(projectId: string, client: HttpClient = apiClient) {
  return client.request<Overview>('/api/v1/dashboard/overview', { query: { projectId } });
}
