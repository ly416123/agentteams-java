import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  getWorker,
  listOperations,
  listWorkers,
  rollbackWorker,
  rolloutWorker,
  workerAction,
  type WorkerFilters,
  type WorkerRolloutRequest,
} from '../api/workers';
import { queryKeys } from './queryKeys';
import { normalizeCursorPage } from '../api/types';
export function useWorkers(projectId: string, filters: WorkerFilters) {
  return useQuery({
    queryKey: queryKeys.workers(projectId, filters),
    queryFn: () => listWorkers(projectId, filters),
    enabled: Boolean(projectId),
    select: normalizeCursorPage,
  });
}
export function useWorker(projectId: string, workerId: string) {
  return useQuery({
    queryKey: queryKeys.worker(projectId, workerId),
    queryFn: () => getWorker(workerId),
    enabled: Boolean(projectId && workerId),
  });
}
export function useWorkerOperations(projectId: string, workerId: string, cursor?: string) {
  return useQuery({
    queryKey: queryKeys.workerOperations(projectId, workerId, cursor),
    queryFn: () => listOperations(workerId, { cursor }),
    enabled: Boolean(projectId && workerId),
    refetchInterval: 10000,
    select: normalizeCursorPage,
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
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}

export function useWorkerRollout(projectId: string, workerId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: WorkerRolloutRequest) => rolloutWorker(workerId, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['workers', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.worker(projectId, workerId) });
      client.invalidateQueries({ queryKey: queryKeys.workerOperations(projectId, workerId) });
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}

export function useWorkerRollback(projectId: string, workerId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      operationId,
      expectedVersion,
    }: {
      operationId: string;
      expectedVersion: number;
    }) => rollbackWorker(workerId, operationId, expectedVersion),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['workers', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.worker(projectId, workerId) });
      client.invalidateQueries({ queryKey: queryKeys.workerOperations(projectId, workerId) });
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}
