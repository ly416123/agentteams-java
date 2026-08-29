import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage, type Project } from './types';

export function listProjects(client: HttpClient = apiClient) {
  return client
    .request<CursorPage<Project> | Project[]>('/api/v1/projects')
    .then(normalizeCursorPage);
}
