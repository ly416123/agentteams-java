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
      query: { projectId, q: input.search, status: input.status, cursor: input.cursor },
    })
    .then(normalizeCursorPage);
}
export function getTeam(projectId: string, teamId: string, client: HttpClient = apiClient) {
  return client.request<Team>(`/api/v1/teams/${teamId}`, { query: { projectId } });
}
export function createTeam(
  projectId: string,
  body: Record<string, unknown>,
  client: HttpClient = apiClient,
) {
  return client.request<Team>('/api/v1/teams', { method: 'POST', body, query: { projectId } });
}
export function listMembers(projectId: string, teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamMember[]>(`/api/v1/teams/${teamId}/members`, { query: { projectId } });
}
export function addMember(
  projectId: string,
  teamId: string,
  body: { agentId: string; role: string },
  client: HttpClient = apiClient,
) {
  return client.request<TeamMember>(`/api/v1/teams/${teamId}/members`, {
    method: 'POST',
    body,
    query: { projectId },
  });
}
export function removeMember(
  projectId: string,
  teamId: string,
  agentId: string,
  client: HttpClient = apiClient,
) {
  return client.request<void>(`/api/v1/teams/${teamId}/members/${agentId}`, {
    method: 'DELETE',
    query: { projectId },
  });
}
export function getPolicy(projectId: string, teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamPolicy>(`/api/v1/teams/${teamId}/policy`, { query: { projectId } });
}
export function updatePolicy(
  projectId: string,
  teamId: string,
  body: Omit<TeamPolicy, 'teamId' | 'updatedAt' | 'version'> & { expectedVersion: number },
  client: HttpClient = apiClient,
) {
  return client.request<TeamPolicy>(`/api/v1/teams/${teamId}/policy`, {
    method: 'PUT',
    body,
    query: { projectId },
  });
}
export function listRevisions(projectId: string, teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamRevision[]>(`/api/v1/teams/${teamId}/revisions`, {
    query: { projectId },
  });
}
export function createRevision(
  projectId: string,
  teamId: string,
  body: {
    leaderAgentId: string;
    overlayJson: string;
    memberAgentIds: string[];
    actor?: string;
  },
  client: HttpClient = apiClient,
) {
  return client.request<TeamRevision>(`/api/v1/teams/${teamId}/revisions`, {
    method: 'POST',
    body,
    query: { projectId },
  });
}
export function reviewRevision(
  projectId: string,
  teamId: string,
  revision: number,
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<TeamRevision>(`/api/v1/teams/${teamId}/revisions/${revision}/review`, {
    method: 'POST',
    body: { expectedVersion },
    query: { projectId },
  });
}
export function publishRevision(
  projectId: string,
  teamId: string,
  revision: number,
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<TeamRevision>(`/api/v1/teams/${teamId}/revisions/${revision}/publish`, {
    method: 'POST',
    body: { expectedVersion },
    query: { projectId },
  });
}
export function deployRevision(
  projectId: string,
  teamId: string,
  revision: number,
  body: {
    members: Array<{ agentId: string; baseManifest: string; taskOverlay?: string }>;
    actor?: string;
  },
  client: HttpClient = apiClient,
) {
  return client.request<TeamDeployment>(
    `/api/v1/teams/${teamId}/revisions/${revision}/deployments`,
    {
      method: 'POST',
      body,
      query: { projectId },
    },
  );
}

export function listDeployments(projectId: string, teamId: string, client: HttpClient = apiClient) {
  return client.request<TeamDeployment[]>(`/api/v1/teams/${teamId}/deployments`, {
    query: { projectId },
  });
}

export function getDeployment(
  projectId: string,
  teamId: string,
  deploymentId: string,
  client: HttpClient = apiClient,
) {
  return client.request<TeamDeployment>(`/api/v1/teams/${teamId}/deployments/${deploymentId}`, {
    query: { projectId },
  });
}
export function retryDeployment(
  projectId: string,
  teamId: string,
  deploymentId: string,
  client: HttpClient = apiClient,
) {
  return client.request<void>(`/api/v1/teams/${teamId}/deployments/${deploymentId}/retry`, {
    method: 'POST',
    query: { projectId },
  });
}
export function rollbackTeam(
  projectId: string,
  teamId: string,
  body: { targetRevision: number; expectedVersion: number; actor?: string },
  client: HttpClient = apiClient,
) {
  return client.request<TeamRevision>(`/api/v1/teams/${teamId}/rollback`, {
    method: 'POST',
    body,
    query: { projectId },
  });
}
