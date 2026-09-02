import { apiClient, type HttpClient } from './httpClient';

export type UsageProviderModel = {
  provider: string;
  model: string;
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  averageLatencyMillis: number;
};

export type UsageGroup = {
  provider?: string | null;
  model?: string | null;
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  averageLatencyMillis: number;
  status?: string | null;
  dimension?: string | null;
  dimensionValue?: string | null;
};

export type UsageSummary = {
  from: string;
  to: string;
  calls: number;
  failures: number;
  promptTokens: number;
  completionTokens: number;
  costUsd: number;
  averageLatencyMillis: number;
  byProviderModel: UsageProviderModel[];
  groupBy?: string;
  groups?: UsageGroup[];
  offset?: number;
  limit?: number;
  nextOffset?: number;
};

export type UsageFilters = {
  taskId?: string;
  provider?: string;
  model?: string;
};

export type UsagePage = {
  offset: number;
  limit: number;
  groupBy?: UsageGroupBy;
};

export type UsageGroupBy =
  'provider_model' | 'organization' | 'tenant' | 'project' | 'team' | 'user' | 'task' | 'worker';

export type UsageBudget = {
  id: string;
  tenantId?: string;
  projectId?: string;
  currency: string;
  periodSeconds: number;
  softThreshold: number;
  hardThreshold: number;
  forecastWindowSeconds: number;
  status: 'ACTIVE' | 'PAUSED' | string;
  version: number;
};

export type UsageBudgetEvaluation = {
  id: string;
  policyId: string;
  windowStart?: string;
  windowEnd?: string;
  actualCost: number | null;
  forecastCost: number | null;
  status: string;
  evaluatedAt: string;
};

export type UsageBudgetUpdate = {
  currency: string;
  periodSeconds: number;
  softThreshold: number;
  hardThreshold: number;
  forecastWindowSeconds: number;
  status: string;
  expectedVersion: number;
};

export function getUsageSummary(
  projectId: string,
  filters?: UsageFilters,
  page?: UsagePage,
  client: HttpClient = apiClient,
) {
  return client.request<UsageSummary>('/api/v1/usage/summary', {
    query: { projectId, groupBy: page?.groupBy ?? 'provider_model', ...filters, ...(page ?? {}) },
  });
}

export function exportUsageCsv(
  projectId: string,
  filters?: UsageFilters,
  client: HttpClient = apiClient,
) {
  return client.requestText('/api/v1/usage/export', {
    query: { projectId, groupBy: 'provider_model', limit: 1000, ...filters },
  });
}

export function listUsageBudgets(projectId: string, client: HttpClient = apiClient) {
  return client.request<UsageBudget[]>('/api/v1/usage/budgets', { query: { projectId } });
}

export function listUsageBudgetEvaluations(policyId: string, client: HttpClient = apiClient) {
  return client.request<UsageBudgetEvaluation[]>(`/api/v1/usage/budgets/${policyId}/evaluations`, {
    query: { limit: 20 },
  });
}

export function upsertUsageBudget(
  policyId: string,
  update: UsageBudgetUpdate,
  client: HttpClient = apiClient,
) {
  return client.request<UsageBudget>(`/api/v1/usage/budgets/${policyId}`, {
    method: 'PUT',
    body: update,
  });
}
