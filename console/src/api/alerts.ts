import { apiClient, type HttpClient } from './httpClient';

export type DashboardAlert = {
  rule: string;
  severity: string;
  actual: number;
  message: string;
};

export type DashboardAlertEvent = DashboardAlert & {
  id: string;
  status: string;
  attempts: number;
  nextAttemptAt?: string | null;
  lastError?: string | null;
  deliveredAt?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type DashboardAlertRule = {
  rule: string;
  severity: string;
  threshold: number;
  enabled: boolean;
  version: number;
};

export type DashboardAlertRuleUpdate = {
  severity: string;
  threshold: number;
  enabled: boolean;
  expectedVersion: number;
};

export function listDashboardAlerts(projectId: string, client: HttpClient = apiClient) {
  return client.request<DashboardAlert[]>('/api/v1/dashboard/alerts', { query: { projectId } });
}

export function listDashboardAlertEvents(projectId: string, client: HttpClient = apiClient) {
  return client.request<DashboardAlertEvent[]>('/api/v1/dashboard/alerts/events', {
    query: { project: projectId, limit: 50 },
  });
}

export function listDashboardAlertRules(projectId: string, client: HttpClient = apiClient) {
  return client.request<DashboardAlertRule[]>('/api/v1/dashboard/alert-rules', {
    query: { projectId },
  });
}

export function updateDashboardAlertRule(
  projectId: string,
  rule: string,
  update: DashboardAlertRuleUpdate,
  client: HttpClient = apiClient,
) {
  return client.request<DashboardAlertRule>(`/api/v1/dashboard/alert-rules/${rule}`, {
    method: 'PUT',
    query: { projectId },
    body: update,
  });
}

export function retryDashboardAlertEvent(eventId: string, client: HttpClient = apiClient) {
  return client.request<DashboardAlertEvent>(`/api/v1/dashboard/alerts/events/${eventId}/retry`, {
    method: 'POST',
  });
}
