import { useCallback, useEffect, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createTask,
  getTask,
  getTaskExecution,
  getTaskRuns,
  getTaskCheckpoints,
  getTaskRecovery,
  listTasks,
  streamTaskEvents,
  taskAction,
  type TaskFilters,
} from '../api/tasks';
import type { TaskEvent } from '../api/types';
import { normalizeCursorPage } from '../api/types';
import { taskEventReconnectDelay } from '../api/taskEvents';
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
export function useTaskExecution(projectId: string, taskId: string) {
  return useQuery({
    queryKey: ['task-execution', projectId, taskId],
    queryFn: () => getTaskExecution(taskId),
    enabled: Boolean(projectId && taskId),
  });
}
export function useTaskRuns(projectId: string, taskId: string) {
  return useQuery({
    queryKey: ['task-runs', projectId, taskId],
    queryFn: () => getTaskRuns(taskId),
    enabled: Boolean(projectId && taskId),
    refetchInterval: 5_000,
  });
}
export function useTaskCheckpoints(projectId: string, taskId: string, runId: string) {
  return useQuery({
    queryKey: ['task-checkpoints', projectId, taskId, runId],
    queryFn: () => getTaskCheckpoints(taskId, runId),
    enabled: Boolean(projectId && taskId && runId),
  });
}
export function useTaskRecovery(projectId: string, taskId: string) {
  return useQuery({
    queryKey: ['task-recovery', projectId, taskId],
    queryFn: () => getTaskRecovery(taskId),
    enabled: Boolean(projectId && taskId),
    refetchInterval: 5_000,
  });
}
export function useTaskEvents(projectId: string, taskId: string) {
  const [restart, setRestart] = useState(0);
  const [state, setState] = useState<{
    data: TaskEvent[];
    isLoading: boolean;
    isError: boolean;
    error?: unknown;
    connectionState: 'connecting' | 'connected' | 'reconnecting';
  }>({
    data: [],
    isLoading: Boolean(projectId && taskId),
    isError: false,
    connectionState: 'connecting',
  });
  const eventsRef = useRef<TaskEvent[]>([]);
  const cursorRef = useRef<string>();
  const resourceRef = useRef<string>();

  useEffect(() => {
    if (!projectId || !taskId) return;
    const controller = new AbortController();
    let reconnectTimer: ReturnType<typeof setTimeout> | undefined;
    let retryAttempt = 0;
    let active = true;
    const resource = `${projectId}:${taskId}`;
    if (resourceRef.current !== resource) {
      eventsRef.current = [];
      cursorRef.current = undefined;
      resourceRef.current = resource;
    }
    setState({
      data: eventsRef.current,
      isLoading: true,
      isError: false,
      connectionState: 'connecting',
    });

    const scheduleReconnect = () => {
      if (!active) return;
      const delay = taskEventReconnectDelay(retryAttempt);
      retryAttempt += 1;
      setState((current) => ({ ...current, connectionState: 'reconnecting' }));
      reconnectTimer = setTimeout(connect, delay);
    };
    const connect = async () => {
      try {
        await streamTaskEvents(taskId, {
          after: cursorRef.current,
          signal: controller.signal,
          onEvents: (incoming) => {
            if (!incoming.length || !active) return;
            retryAttempt = 0;
            const byId = new Map(eventsRef.current.map((event) => [event.id, event]));
            incoming.forEach((event) => byId.set(event.id, event));
            eventsRef.current = [...byId.values()];
            const lastEvent = incoming[incoming.length - 1];
            cursorRef.current = lastEvent.cursor || lastEvent.id;
            setState({
              data: eventsRef.current,
              isLoading: false,
              isError: false,
              connectionState: 'connected',
            });
          },
        });
        scheduleReconnect();
      } catch (error) {
        if (!active || controller.signal.aborted) return;
        setState((current) => ({
          ...current,
          isLoading: false,
          isError: true,
          error,
          connectionState: 'reconnecting',
        }));
        scheduleReconnect();
      }
    };
    void connect();
    return () => {
      active = false;
      controller.abort();
      if (reconnectTimer) clearTimeout(reconnectTimer);
    };
  }, [projectId, taskId, restart]);

  const refetch = useCallback(async () => {
    setRestart((value) => value + 1);
  }, []);
  return { ...state, refetch, data: state.data };
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
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}
export function useCreateTask(projectId: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: Parameters<typeof createTask>[0]) => createTask(body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['tasks', projectId] });
      client.invalidateQueries({ queryKey: queryKeys.overview(projectId) });
    },
  });
}
