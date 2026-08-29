import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getWorker,
  listOperations,
  listWorkers,
  workerAction,
  type WorkerFilters,
} from '../api/workers';
import { queryKeys } from './queryKeys';
function items<T>(data: T[] | { items: T[] } | undefined): T[] {
  return Array.isArray(data) ? data : data?.items || [];
}
export function useWorkers(projectId: string, filters: WorkerFilters) {
  return useQuery({
    queryKey: queryKeys.workers(projectId, filters),
    queryFn: () => listWorkers(projectId, filters),
    enabled: Boolean(projectId),
    select: items,
  });
}
export function useWorker(projectId: string, workerId: string) {
  return useQuery({
    queryKey: queryKeys.worker(projectId, workerId),
    queryFn: () => getWorker(workerId),
    enabled: Boolean(projectId && workerId),
  });
}
export function useWorkerOperations(projectId: string, workerId: string) {
  return useQuery({
    queryKey: queryKeys.workerOperations(projectId, workerId),
    queryFn: () => listOperations(workerId),
    enabled: Boolean(projectId && workerId),
    refetchInterval: 10000,
  });
}
export function useWorkerAction(projectId: string, workerId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      action,
      expectedVersion,
    }: {
      action: Parameters<typeof workerAction>[1];
      expectedVersion: number;
    }) => workerAction(workerId, action, expectedVersion),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['workers', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.worker(projectId, workerId) });
      client.invalidateQueries({ queryKey: queryKeys.workerOperations(projectId, workerId) });
    },
  });
}
