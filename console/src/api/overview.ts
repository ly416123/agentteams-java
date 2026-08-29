import { apiClient, type HttpClient } from './httpClient';
import { listTasks } from './tasks';
import { listTeams } from './teams';
import { listWorkers } from './workers';
import type { DashboardSummary, Overview } from './types';

export function getDashboardSummary(client: HttpClient = apiClient) {
  return client.request<DashboardSummary>('/api/v1/dashboard/summary');
}

type DashboardAlert = { rule: string; severity: string; actual: number; message: string };

function listDashboardAlerts(client: HttpClient = apiClient) {
  return client.request<DashboardAlert[]>('/api/v1/dashboard/alerts');
}

export async function getOverview(
  projectId: string,
  client: HttpClient = apiClient,
): Promise<Overview> {
  const [summary, alerts, teams, tasks, workers] = await Promise.all([
    getDashboardSummary(client),
    listDashboardAlerts(client),
    listTeams(projectId, {}, client),
    listTasks(projectId, {}, client),
    listWorkers(projectId, {}, client),
  ]);
  const taskItems = tasks.items;
  const workerItems = workers.items;
  return {
    tasks: {
      total: tasks.total ?? taskItems.length,
      queued: taskItems.filter((task) => task.phase === 'QUEUED').length,
      running: taskItems.filter((task) => task.phase === 'RUNNING').length,
      succeeded: taskItems.filter((task) => task.phase === 'SUCCEEDED').length,
      failed: taskItems.filter((task) => task.phase === 'FAILED').length,
    },
    workers: {
      ready: workerItems.filter((worker) => worker.phase === 'READY').length,
      connecting: workerItems.filter((worker) => worker.phase === 'CONNECTING').length,
      unhealthy: workerItems.filter((worker) => worker.phase === 'UNHEALTHY').length,
      draining: workerItems.filter((worker) => worker.phase === 'DRAINING').length,
    },
    teams: {
      total: teams.total ?? teams.items.length,
      active: teams.items.filter((team) => team.status === 'ACTIVE').length,
    },
    recentTasks: taskItems.slice(0, 5),
    alerts: alerts.map((alert, index) => ({
      id: `${alert.rule}-${index}`,
      severity: alert.severity,
      message: alert.message,
      createdAt: summary.to,
    })),
    usage: summary,
  };
}
