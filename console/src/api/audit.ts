import { apiClient, type HttpClient } from './httpClient';

export type AuditEvent = {
  id: string;
  actor: string;
  action: string;
  resourceType: string;
  resourceId: string;
  attributes: Record<string, string>;
  occurredAt: string;
};

export type AuditFilters = {
  actor?: string;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  before?: string;
};

export function listAuditEvents(
  projectId: string,
  filters?: AuditFilters,
  client: HttpClient = apiClient,
) {
  return client.request<AuditEvent[]>('/api/v1/audit-events', {
    query: { projectId, limit: 100, ...filters },
  });
}
