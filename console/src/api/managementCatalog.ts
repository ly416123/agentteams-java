import { apiClient, type HttpClient } from './httpClient';

export type WorkerTemplate = {
  id: string;
  tenantId: string;
  projectId: string;
  name: string;
  displayName: string;
  currentPublishedRevision: number | null;
  version: number;
  createdAt: string;
  updatedAt: string;
};

export type WorkerTemplateRevision = {
  templateId: string;
  revision: number;
  specJson: string;
  digest: string;
  status: string;
  createdBy: string;
  version: number;
};

export type WorkerTemplateInstance = {
  id: string;
  templateId: string;
  templateRevision: number;
  agentSpecId: string;
  workerId: string;
  status: string;
  currentTemplateRevision: number;
  version: number;
};

export function listWorkerTemplates(projectId: string, client: HttpClient = apiClient) {
  return client.request<WorkerTemplate[]>('/api/v1/worker-templates', { query: { projectId } });
}

export function createWorkerTemplate(
  projectId: string,
  body: { name: string; displayName: string },
  client: HttpClient = apiClient,
) {
  return client.request<WorkerTemplate>('/api/v1/worker-templates', {
    method: 'POST',
    body,
    query: { projectId },
  });
}

export function createWorkerTemplateRevision(
  projectId: string,
  templateId: string,
  body: { specJson: string; actor?: string },
  client: HttpClient = apiClient,
) {
  return client.request<WorkerTemplateRevision>(
    `/api/v1/worker-templates/${templateId}/revisions`,
    { method: 'POST', body, query: { projectId } },
  );
}

export function publishWorkerTemplateRevision(
  projectId: string,
  templateId: string,
  revision: number,
  expectedVersion: number,
  client: HttpClient = apiClient,
) {
  return client.request<WorkerTemplateRevision>(
    `/api/v1/worker-templates/${templateId}/revisions/${revision}/publish`,
    { method: 'POST', body: { expectedVersion }, query: { projectId } },
  );
}

export function instantiateWorkerTemplate(
  projectId: string,
  templateId: string,
  revision: number,
  client: HttpClient = apiClient,
) {
  return client.request<WorkerTemplateInstance>(
    `/api/v1/worker-templates/${templateId}/revisions/${revision}/instances`,
    { method: 'POST', query: { projectId } },
  );
}

export type AgentSpec = {
  id: string;
  name: string;
  runtime: string;
  modelProvider: string;
  modelName: string;
  teamRef: string | null;
  desiredState: string;
  lifecycleStatus: string;
  spec: string;
  version: number;
  tenantId: string;
  projectId: string;
};

export function listAgentSpecs(projectId: string, client: HttpClient = apiClient) {
  return client.request<AgentSpec[]>('/api/v1/agent-specs', { query: { projectId } });
}

export function createAgentSpec(
  projectId: string,
  body: {
    name: string;
    runtime: string;
    modelProvider: string;
    modelName: string;
    teamRef?: string;
    desiredState: string;
    spec: unknown;
  },
  client: HttpClient = apiClient,
) {
  return client.request<AgentSpec>('/api/v1/agent-specs', {
    method: 'POST',
    body,
    query: { projectId },
  });
}

export function publishAgentSpec(projectId: string, id: string, client: HttpClient = apiClient) {
  return client.request<AgentSpec>(`/api/v1/agent-specs/${id}/publish`, {
    method: 'POST',
    query: { projectId },
  });
}

export function deactivateAgentSpec(projectId: string, id: string, client: HttpClient = apiClient) {
  return client.request<AgentSpec>(`/api/v1/agent-specs/${id}/deactivate`, {
    method: 'POST',
    query: { projectId },
  });
}

export type McpServer = {
  id: string;
  name: string;
  transport: string;
  endpoint: string;
  credentialConfigured: boolean;
  enabled: boolean;
  healthStatus: string;
  lastCheckedAt: string | null;
  version: number;
};

export type McpDiscovery = {
  serverId: string;
  serverRevision: number;
  status: string;
  toolsDigest: string;
  healthyInstances: number;
  freshInstances: number;
  latestObservedAt: string | null;
  failureCategories: string[];
};

export type McpHealthProbeResult = {
  status: string;
  category: string;
  checkedAt: string;
  detail: string | null;
  latencyMillis: number;
};

export function listMcpServers(client: HttpClient = apiClient) {
  return client.request<McpServer[]>('/api/v1/mcp-servers');
}

export function createMcpServer(
  body: {
    name: string;
    transport: string;
    endpoint: string;
    credentialRef?: string;
    enabled: boolean;
  },
  client: HttpClient = apiClient,
) {
  return client.request<McpServer>('/api/v1/mcp-servers', { method: 'POST', body });
}

export function updateMcpServer(
  id: string,
  body: {
    name: string;
    transport: string;
    endpoint: string;
    credentialRef?: string;
    enabled: boolean;
    expectedVersion: number;
  },
  client: HttpClient = apiClient,
) {
  return client.request<McpServer>(`/api/v1/mcp-servers/${id}`, { method: 'PUT', body });
}

export function getMcpDiscovery(id: string, client: HttpClient = apiClient) {
  return client.request<McpDiscovery>(`/api/v1/mcp-servers/${id}/discovery`);
}

export function testMcpConnection(id: string, client: HttpClient = apiClient) {
  return client.request<McpHealthProbeResult>(`/api/v1/mcp-servers/${id}/connection-test`, {
    method: 'POST',
  });
}

export function updateMcpServerHealth(
  id: string,
  body: { healthStatus: string; lastCheckedAt?: string },
  client: HttpClient = apiClient,
) {
  return client.request<McpServer>(`/api/v1/mcp-servers/${id}/health`, {
    method: 'PATCH',
    body,
  });
}

export function deleteMcpServer(id: string, client: HttpClient = apiClient) {
  return client.request<void>(`/api/v1/mcp-servers/${id}`, { method: 'DELETE' });
}

export type ModelProvider = {
  id: string;
  name: string;
  providerType: string;
  endpoint: string;
  credentialConfigured: boolean;
  enabled: boolean;
  version: number;
};

export type Model = {
  id: string;
  providerId: string;
  name: string;
  modelId: string;
  capabilities: string;
  enabled: boolean;
  version: number;
};

export function listModelProviders(client: HttpClient = apiClient) {
  return client.request<ModelProvider[]>('/api/v1/model-providers');
}

export function createModelProvider(
  body: {
    name: string;
    providerType: string;
    endpoint: string;
    credentialRef?: string;
    settings?: unknown;
    enabled: boolean;
  },
  client: HttpClient = apiClient,
) {
  return client.request<ModelProvider>('/api/v1/model-providers', { method: 'POST', body });
}

export function testModelProviderConnection(id: string, client: HttpClient = apiClient) {
  return client.request<{ status: string; classification: string; networkCallAttempted: boolean }>(
    `/api/v1/model-providers/${id}/connection-test`,
    { method: 'POST', body: { timeoutMs: 5000 } },
  );
}

export function setModelProviderEnabled(
  id: string,
  enabled: boolean,
  client: HttpClient = apiClient,
) {
  return client.request<ModelProvider>(`/api/v1/model-providers/${id}`, {
    method: 'PATCH',
    body: { enabled },
  });
}

export function deleteModelProvider(id: string, client: HttpClient = apiClient) {
  return client.request<void>(`/api/v1/model-providers/${id}`, { method: 'DELETE' });
}

export function createModel(
  providerId: string,
  body: { name: string; modelId: string; capabilities?: unknown; enabled: boolean },
  client: HttpClient = apiClient,
) {
  return client.request<Model>(`/api/v1/model-providers/${providerId}/models`, {
    method: 'POST',
    body,
  });
}

export function listModels(providerId: string, client: HttpClient = apiClient) {
  return client.request<Model[]>(`/api/v1/model-providers/${providerId}/models`);
}

export function setModelEnabled(id: string, enabled: boolean, client: HttpClient = apiClient) {
  return client.request<Model>(`/api/v1/model-providers/models/${id}`, {
    method: 'PATCH',
    body: { enabled },
  });
}

export function deleteModel(id: string, client: HttpClient = apiClient) {
  return client.request<void>(`/api/v1/model-providers/models/${id}`, { method: 'DELETE' });
}

export type ModelPrice = {
  id: string;
  provider: string;
  model: string;
  currency: string;
  inputPricePerMillionTokens: number;
  outputPricePerMillionTokens: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  lifecycleStatus: string;
  version: number;
};

export function listModelPrices(client: HttpClient = apiClient) {
  return client.request<ModelPrice[]>('/api/v1/model-prices');
}

export type Skill = {
  id: string;
  name: string;
  displayName: string;
  description: string;
  visibility: string;
  lifecycle: string;
  version: number;
};

export type SkillVersion = {
  id: string;
  skillId: string;
  version: string;
  digest: string;
  lifecycle: string;
  reviewStatus: string;
  packageUploadStatus: string;
  recordVersion: number;
};

export type SkillPackageUpload = {
  skillId: string;
  versionId: string;
  storageKey: string;
  sizeBytes: number;
  sha256: string;
  uploadUrl: string;
  downloadUrl: string;
};

export function listSkills(client: HttpClient = apiClient) {
  return client.request<Skill[]>('/api/v1/skills');
}

export function createSkill(
  body: { name: string; displayName: string; description: string; visibility: string },
  client: HttpClient = apiClient,
) {
  return client.request<Skill>('/api/v1/skills', { method: 'POST', body });
}

export function createSkillVersion(
  skillId: string,
  body: { version: string; digest: string; manifest: unknown; visibility: string },
  client: HttpClient = apiClient,
) {
  return client.request<SkillVersion>(`/api/v1/skills/${skillId}/versions`, {
    method: 'POST',
    body,
  });
}

export function reviewSkillVersion(
  skillId: string,
  versionId: string,
  status: 'APPROVED' | 'REJECTED',
  client: HttpClient = apiClient,
) {
  return client.request<SkillVersion>(`/api/v1/skills/${skillId}/versions/${versionId}/review`, {
    method: 'POST',
    body: { status },
  });
}

export function publishSkillVersion(
  skillId: string,
  versionId: string,
  client: HttpClient = apiClient,
) {
  return client.request<SkillVersion>(`/api/v1/skills/${skillId}/versions/${versionId}/publish`, {
    method: 'POST',
  });
}

export function disableSkillVersion(
  skillId: string,
  versionId: string,
  client: HttpClient = apiClient,
) {
  return client.request<SkillVersion>(`/api/v1/skills/${skillId}/versions/${versionId}/disable`, {
    method: 'POST',
  });
}

export function prepareSkillPackageUpload(
  skillId: string,
  versionId: string,
  body: { sizeBytes: number; sha256: string; contentType: string },
  client: HttpClient = apiClient,
) {
  return client.request<SkillPackageUpload>(
    `/api/v1/skills/${skillId}/versions/${versionId}/package/upload`,
    { method: 'POST', body },
  );
}

export function completeSkillPackageUpload(
  skillId: string,
  versionId: string,
  client: HttpClient = apiClient,
) {
  return client.request<SkillVersion>(
    `/api/v1/skills/${skillId}/versions/${versionId}/package/complete`,
    { method: 'POST' },
  );
}
