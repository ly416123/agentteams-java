import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTask,
  getTask,
  listTaskEvents,
  listTasks,
  taskAction,
  type TaskFilters,
} from '../api/tasks';
import { normalizeCursorPage } from '../api/types';
import { queryKeys } from './queryKeys';
export function useTasks(
  projectId: string,
  filters: TaskFilters,
  view: 'board' | 'table',
  cursor?: string,
) {
  return useQuery({
    queryKey: queryKeys.tasks(projectId, filters, view, cursor),
    queryFn: () => listTasks(projectId, { ...filters, cursor }),
    enabled: Boolean(projectId),
    select: normalizeCursorPage,
  });
}
export function useTask(projectId: string, taskId: string) {
  return useQuery({
    queryKey: queryKeys.task(projectId, taskId),
    queryFn: () => getTask(taskId),
    enabled: Boolean(projectId && taskId),
  });
}
export function useTaskEvents(projectId: string, taskId: string) {
  return useQuery({
    queryKey: queryKeys.taskEvents(projectId, taskId),
    queryFn: () => listTaskEvents(taskId),
    enabled: Boolean(projectId && taskId),
    refetchInterval: 10000,
  });
}
export function useTaskAction(projectId: string, taskId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      action,
      expectedVersion,
    }: {
      action: Parameters<typeof taskAction>[1];
      expectedVersion: number;
    }) => taskAction(taskId, action, expectedVersion),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['tasks', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.task(projectId, taskId) });
    },
  });
}
export function useCreateTask(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => createTask(projectId, body),
    onSuccess: () => client.invalidateQueries({ queryKey: ['tasks', projectId] }),
  });
}
