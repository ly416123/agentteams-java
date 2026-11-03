import { apiClient, type HttpClient } from './httpClient';

export type EffectiveAuthorization = {
  permissions: string[];
  assignments: Array<{
    subject: string;
    scopeType: 'PLATFORM' | 'ORGANIZATION';
    organizationId?: string;
    role: string;
    status: string;
  }>;
};

export function getEffectiveAuthorization(projectId?: string, client: HttpClient = apiClient) {
  return client.request<EffectiveAuthorization>('/api/v1/management/authorizations/permissions', {
    query: { projectId },
  });
}

export type ManagementRoleAssignment = {
  id: string;
  subject: string;
  scopeType: 'PLATFORM' | 'ORGANIZATION';
  organizationId?: string;
  role: string;
  status: string;
  version: number;
};

export function listManagementRoleAssignments(
  scopeType: 'PLATFORM' | 'ORGANIZATION',
  organizationId?: string,
  client: HttpClient = apiClient,
) {
  return client.request<ManagementRoleAssignment[]>(
    '/api/v1/management/authorizations/assignments',
    {
      query: { scopeType, organizationId },
    },
  );
}

export function grantPlatformAdministrator(subject: string, client: HttpClient = apiClient) {
  return client.request<ManagementRoleAssignment>(
    '/api/v1/management/authorizations/platform/admins',
    {
      method: 'POST',
      body: { subject },
    },
  );
}

export function grantOrganizationAdministrator(
  organizationId: string,
  subject: string,
  client: HttpClient = apiClient,
) {
  return client.request<ManagementRoleAssignment>(
    `/api/v1/management/authorizations/organizations/${encodeURIComponent(organizationId)}/admins`,
    { method: 'POST', body: { subject } },
  );
}

export function revokeManagementAdministrator(
  assignment: ManagementRoleAssignment,
  client: HttpClient = apiClient,
) {
  const path =
    assignment.scopeType === 'PLATFORM'
      ? `/api/v1/management/authorizations/platform/admins/${encodeURIComponent(assignment.subject)}/revoke`
      : `/api/v1/management/authorizations/organizations/${encodeURIComponent(assignment.organizationId || '')}/admins/${encodeURIComponent(assignment.subject)}/revoke`;
  return client.request<void>(path, {
    method: 'POST',
    body: { expectedVersion: assignment.version },
  });
}
