import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTeam,
  getPolicy,
  getTeam,
  listDeployments,
  listMembers,
  listRevisions,
  listTeams,
  updatePolicy,
  type TeamFilters,
} from '../api/teams';
import { queryKeys } from './queryKeys';

function items<T>(data: T[] | { items: T[] } | undefined): T[] {
  return Array.isArray(data) ? data : data?.items || [];
}
export function useTeams(projectId: string, filters: TeamFilters) {
  return useQuery({
    queryKey: queryKeys.teams(projectId, filters),
    queryFn: () => listTeams(projectId, filters),
    enabled: Boolean(projectId),
    select: items,
  });
}
export function useTeam(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.team(projectId, teamId),
    queryFn: () => getTeam(teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamMembers(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamMembers(projectId, teamId),
    queryFn: () => listMembers(teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamPolicy(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamPolicy(projectId, teamId),
    queryFn: () => getPolicy(teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamRevisions(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamRevisions(projectId, teamId),
    queryFn: () => listRevisions(teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useTeamDeployments(projectId: string, teamId: string) {
  return useQuery({
    queryKey: queryKeys.teamDeployments(projectId, teamId),
    queryFn: () => listDeployments(teamId),
    enabled: Boolean(projectId && teamId),
  });
}
export function useCreateTeam(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => createTeam(projectId, body),
    onSuccess: () => client.invalidateQueries({ queryKey: ['teams', projectId] }),
  });
}
export function useUpdateTeamPolicy(projectId: string, teamId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof updatePolicy>[1]) => updatePolicy(teamId, body),
    onSuccess: () =>
      client.invalidateQueries({ queryKey: queryKeys.teamPolicy(projectId, teamId) }),
  });
}
