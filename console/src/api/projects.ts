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

export function createProject(body: { name: string }, client: HttpClient = apiClient) {
  return client.request<Project>('/api/v1/projects', { method: 'POST', body });
}

export type ProjectMember = {
  projectId: string;
  subject: string;
  role: string;
  status: string;
  version: number;
};

export function listProjectMembers(projectId: string, client: HttpClient = apiClient) {
  return client.request<ProjectMember[]>(`/api/v1/projects/${projectId}/members`);
}

export function changeProjectMemberRole(
  projectId: string,
  subject: string,
  body: { role: string; expectedMembershipVersion: number },
  client: HttpClient = apiClient,
) {
  return client.request<void>(
    `/api/v1/projects/${projectId}/members/${encodeURIComponent(subject)}/role`,
    {
      method: 'POST',
      body,
    },
  );
}

export type ProjectRolePermissions = { role: string; permissions: string[] };

export function listProjectRolePermissions(projectId: string, client: HttpClient = apiClient) {
  return client.request<ProjectRolePermissions[]>(
    `/api/v1/projects/${projectId}/authorization/roles`,
  );
}
