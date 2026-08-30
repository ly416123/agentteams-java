import { apiClient, type HttpClient } from './httpClient';
import { listTasks } from './tasks';
import type { DashboardResources, DashboardSummary, Overview } from './types';

export function getDashboardSummary(projectId: string, client: HttpClient = apiClient) {
  return client.request<DashboardSummary>('/api/v1/dashboard/summary', {
    query: { projectId },
  });
}

export function getDashboardResources(projectId: string, client: HttpClient = apiClient) {
  return client.request<DashboardResources>('/api/v1/dashboard/resources', {
    query: { projectId },
  });
}

type DashboardAlert = { rule: string; severity: string; actual: number; message: string };

function listDashboardAlerts(client: HttpClient = apiClient) {
  return client.request<DashboardAlert[]>('/api/v1/dashboard/alerts');
}

export async function getOverview(
  projectId: string,
  client: HttpClient = apiClient,
): Promise<Overview> {
  const [summaryResult, alertsResult, resourcesResult, tasksResult] = await Promise.allSettled([
    getDashboardSummary(projectId, client),
    listDashboardAlerts(client),
    getDashboardResources(projectId, client),
    listTasks(projectId, {}, client),
  ]);
  const summary = summaryResult.status === 'fulfilled' ? summaryResult.value : undefined;
  const alerts = alertsResult.status === 'fulfilled' ? alertsResult.value : [];
  const resources = resourcesResult.status === 'fulfilled' ? resourcesResult.value : undefined;
  const tasks = tasksResult.status === 'fulfilled' ? tasksResult.value : undefined;
  const errors: Overview['errors'] = {};
  if (summaryResult.status === 'rejected') errors.summary = summaryResult.reason;
  if (alertsResult.status === 'rejected') errors.alerts = alertsResult.reason;
  if (resourcesResult.status === 'rejected') errors.resources = resourcesResult.reason;
  if (tasksResult.status === 'rejected') errors.tasks = tasksResult.reason;
  if (summaryResult.status === 'rejected' && isForbidden(summaryResult.reason)) {
    throw summaryResult.reason;
  }
  const taskItems = tasks?.items || [];
  return {
    tasks: {
      total: resources?.tasks.total ?? null,
      queued: resources?.tasks.queued ?? null,
      running: resources?.tasks.running ?? null,
      succeeded: resources?.tasks.succeeded ?? null,
      failed: resources?.tasks.failed ?? null,
    },
    workers: {
      ready: resources?.workers.ready ?? null,
      connecting: resources?.workers.connecting ?? null,
      unhealthy: resources?.workers.unhealthy ?? null,
      draining: resources?.workers.draining ?? null,
    },
    teams: {
      total: resources?.teams.total ?? null,
      active: resources?.teams.active ?? null,
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
    metricsUnavailable: resourcesResult.status !== 'fulfilled',
  };
}

function isForbidden(error: unknown) {
  return typeof error === 'object' && error !== null && 'status' in error && error.status === 403;
}
