import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage, type Project } from './types';

export type ProjectFilters = { cursor?: string; status?: string; q?: string };

export function listProjects(client?: HttpClient): Promise<CursorPage<Project>>;
export function listProjects(
  filters?: ProjectFilters,
  client?: HttpClient,
): Promise<CursorPage<Project>>;
export function listProjects(
  filtersOrClient: ProjectFilters | HttpClient = {},
  providedClient: HttpClient = apiClient,
) {
  const isClient = 'request' in filtersOrClient;
  const filters = isClient ? {} : filtersOrClient;
  const client = isClient ? filtersOrClient : providedClient;
  return client
    .request<CursorPage<Project> | Project[]>('/api/v1/projects', { query: filters })
    .then(normalizeCursorPage);
}
