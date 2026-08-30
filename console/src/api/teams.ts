import { apiClient, type HttpClient } from './httpClient';
import {
  normalizeCursorPage,
  type CursorPage,
  type Team,
  type TeamDeployment,
  type TeamMember,
  type TeamPolicy,
  type TeamRevision,
} from './types';

export type TeamFilters = { search?: string; status?: string; cursor?: string };

export function listTeams(
  projectId: string,
  filters?: TeamFilters,
  client: HttpClient = apiClient,
) {
  void projectId;
  const input = filters || {};
  return client
    .request<CursorPage<Team> | Team[]>('/api/v1/teams/page', {
      query: { q: input.search, status: input.status, cursor: input.cursor },
    })
    .then(normalizeCursorPage);
}
export function getTeam(teamId: string, client: HttpClient = apiClient) {
  return client.request<Team>(`/api/v1/teams/${teamId}`);
}
export function createTeam(
  projectId: string,
  body: Record<string, unknown>,
  client: HttpClient = apiClient,
) {
  void projectId;
  return client.request<Team>('/api/v1/teams', { method: 'POST', body });
}
export function listMembers(teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamMember[]>(`/api/v1/teams/${teamId}/members`);
}
export function getPolicy(teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamPolicy>(`/api/v1/teams/${teamId}/policy`);
}
export function updatePolicy(
  teamId: string,
  body: Omit<TeamPolicy, 'teamId' | 'updatedAt' | 'version'> & { expectedVersion: number },
  client: HttpClient = apiClient,
) {
  return client.request<TeamPolicy>(`/api/v1/teams/${teamId}/policy`, { method: 'PUT', body });
}
export function listRevisions(teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamRevision[]>(`/api/v1/teams/${teamId}/revisions`);
}

export function listDeployments(teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamDeployment[]>(`/api/v1/teams/${teamId}/deployments`);
}

export function getDeployment(
  teamId: string,
  deploymentId: string,
  client: HttpClient = apiClient,
) {
  return client.request<TeamDeployment>(`/api/v1/teams/${teamId}/deployments/${deploymentId}`);
}
