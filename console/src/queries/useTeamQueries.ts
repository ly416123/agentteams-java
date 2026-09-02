import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addMember,
  createTeam,
  createRevision,
  deployRevision,
  getPolicy,
  getDeployment,
  getTeam,
  listDeployments,
  listMembers,
  listRevisions,
  listTeams,
  publishRevision,
  removeMember,
  retryDeployment,
  reviewRevision,
  rollbackTeam,
  updatePolicy,
  type TeamFilters,
} from '../api/teams';
import { queryKeys } from './queryKeys';
import { normalizeCursorPage } from '../api/types';
export function useTeams(projectId: string, filters: TeamFilters) {
  return useQuery({
    queryKey: queryKeys.teams(projectId, filters),
    queryFn: () => listTeams(projectId, filters),
    enabled: Boolean(projectId),
    select: normalizeCursorPage,
  });
}
export function useTeam(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.team(projectId, teamId),
    queryFn: () => getTeam(projectId, teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamMembers(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamMembers(projectId, teamId),
    queryFn: () => listMembers(projectId, teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamPolicy(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamPolicy(projectId, teamId),
    queryFn: () => getPolicy(projectId, teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamRevisions(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamRevisions(projectId, teamId),
    queryFn: () => listRevisions(projectId, teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamDeployments(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamDeployments(projectId, teamId),
    queryFn: () => listDeployments(projectId, teamId),
    enabled: Boolean(projectId && teamId),
  });
}

export function useTeamDeployment(projectId: string, teamId: string, deploymentId: string) {
  return useQuery({
    queryKey: [...queryKeys.teamDeployments(projectId, teamId), deploymentId],
    queryFn: () => getDeployment(projectId, teamId, deploymentId),
    enabled: Boolean(projectId && teamId && deploymentId),
  });
}
export function useCreateTeam(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => createTeam(projectId, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['teams', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}
export function useUpdateTeamPolicy(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof updatePolicy>[2]) => updatePolicy(projectId, teamId, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: queryKeys.teamPolicy(projectId, teamId) });
      client.invalidateQueries({ queryKey: queryKeys.team(projectId, teamId) });
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}
export function useAddTeamMember(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { agentId: string; role: string }) => addMember(projectId, teamId, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: queryKeys.teamMembers(projectId, teamId) });
      client.invalidateQueries({ queryKey: queryKeys.team(projectId, teamId) });
    },
  });
}
export function useRemoveTeamMember(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (agentId: string) => removeMember(projectId, teamId, agentId),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: queryKeys.teamMembers(projectId, teamId) });
      client.invalidateQueries({ queryKey: queryKeys.team(projectId, teamId) });
    },
  });
}
function invalidateTeamLifecycle(
  client: ReturnType<typeof useQueryClient>,
  projectId: string,
  teamId: string,
) {
  client.invalidateQueries({ queryKey: queryKeys.teamRevisions(projectId, teamId) });
  client.invalidateQueries({ queryKey: queryKeys.teamDeployments(projectId, teamId) });
  client.invalidateQueries({ queryKey: queryKeys.team(projectId, teamId) });
}
export function useCreateTeamRevision(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof createRevision>[2]) =>
      createRevision(projectId, teamId, body),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
export function useReviewTeamRevision(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { revision: number; expectedVersion: number }) =>
      reviewRevision(projectId, teamId, input.revision, input.expectedVersion),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
export function usePublishTeamRevision(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { revision: number; expectedVersion: number }) =>
      publishRevision(projectId, teamId, input.revision, input.expectedVersion),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
export function useDeployTeamRevision(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (input: { revision: number; body: Parameters<typeof deployRevision>[3] }) =>
      deployRevision(projectId, teamId, input.revision, input.body),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
export function useRetryTeamDeployment(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (deploymentId: string) => retryDeployment(projectId, teamId, deploymentId),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
export function useRollbackTeam(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof rollbackTeam>[2]) => rollbackTeam(projectId, teamId, body),
    onSuccess: () => invalidateTeamLifecycle(client, projectId, teamId),
  });
}
