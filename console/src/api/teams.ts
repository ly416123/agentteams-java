import { apiClient, type HttpClient } from './httpClient';
import type { Page, Team, TeamDeployment, TeamMember, TeamPolicy, TeamRevision } from './types';

export type TeamFilters = { search?: string; status?: string; cursor?: string };
const query = (projectId: string, filters: TeamFilters = {}) => ({ projectId, ...filters });

export function listTeams(
  projectId: string,
  filters?: TeamFilters,
  client: HttpClient = apiClient,
) {
  return client.request<Page<Team> | Team[]>('/api/v1/teams', { query: query(projectId, filters) });
}
export function getTeam(teamId: string, client: HttpClient = apiClient) {
  return client.request<Team>(`/api/v1/teams/${teamId}`);
}
export function createTeam(
  projectId: string,
  body: Record<string, unknown>,
  client: HttpClient = apiClient,
) {
  return client.request<Team>('/api/v1/teams', { method: 'POST', body: { ...body, projectId } });
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
