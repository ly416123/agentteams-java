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
  const [summaryResult, alertsResult, teamsResult, tasksResult, workersResult] =
    await Promise.allSettled([
      getDashboardSummary(client),
      listDashboardAlerts(client),
      listTeams(projectId, {}, client),
      listTasks(projectId, {}, client),
      listWorkers(projectId, {}, client),
    ]);
  const summary = summaryResult.status === 'fulfilled' ? summaryResult.value : undefined;
  const alerts = alertsResult.status === 'fulfilled' ? alertsResult.value : [];
  const teams = teamsResult.status === 'fulfilled' ? teamsResult.value : undefined;
  const tasks = tasksResult.status === 'fulfilled' ? tasksResult.value : undefined;
  const errors: Overview['errors'] = {};
  if (summaryResult.status === 'rejected') errors.summary = summaryResult.reason;
  if (alertsResult.status === 'rejected') errors.alerts = alertsResult.reason;
  if (teamsResult.status === 'rejected') errors.teams = teamsResult.reason;
  if (tasksResult.status === 'rejected') errors.tasks = tasksResult.reason;
  if (workersResult.status === 'rejected') errors.workers = workersResult.reason;
  const taskItems = tasks?.items || [];
  return {
    tasks: {
      total: tasks?.total ?? null,
      queued: null,
      running: null,
      succeeded: null,
      failed: null,
    },
    workers: {
      ready: null,
      connecting: null,
      unhealthy: null,
      draining: null,
    },
    teams: {
      total: teams?.total ?? null,
      active: null,
    },
    recentTasks: taskItems.slice(0, 5),
    alerts: alerts.map((alert, index) => ({
      id: `${alert.rule}-${index}`,
      severity: alert.severity,
      message: alert.message,
      createdAt: summary?.to || new Date().toISOString(),
    })),
    usage: summary,
    errors: Object.keys(errors).length ? errors : undefined,
  };
}
