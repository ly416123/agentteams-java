import { apiClient, type HttpClient } from './httpClient';

export type ManagementUser = {
  id: string;
  subject: string;
  displayName: string;
  status: string;
  version: number;
};

export type OrganizationMembership = {
  organizationId: string;
  subject: string;
  role: string;
};

export type ManagementOrganization = {
  id: string;
  name: string;
  status: string;
  version: number;
};

export type ManagementTenant = {
  id: string;
  organizationId: string;
  name: string;
  status: string;
  version: number;
};

export type ManagementIntegration = {
  id: string;
  organizationId: string;
  name: string;
  status: string;
  version: number;
};

export function listManagementOrganizations(client: HttpClient = apiClient) {
  return client.request<ManagementOrganization[]>('/api/v1/management/organizations');
}

export function createManagementOrganization(
  body: { name: string },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementOrganization>('/api/v1/management/organizations', {
    method: 'POST',
    body,
  });
}

export function listManagementTenants(organizationId: string, client: HttpClient = apiClient) {
  return client.request<ManagementTenant[]>(
    `/api/v1/management/organizations/${organizationId}/tenants`,
  );
}

export function createManagementTenant(
  organizationId: string,
  body: { name: string },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementTenant>(
    `/api/v1/management/organizations/${organizationId}/tenants`,
    { method: 'POST', body },
  );
}

export function updateManagementOrganizationStatus(
  id: string,
  body: { expectedVersion: number; status: 'ACTIVE' | 'SUSPENDED' | 'DELETED' },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementOrganization>(`/api/v1/management/organizations/${id}/status`, {
    method: 'POST',
    body,
  });
}

export function updateManagementTenantStatus(
  id: string,
  body: { expectedVersion: number; status: 'ACTIVE' | 'SUSPENDED' | 'DELETED' },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementTenant>(`/api/v1/management/tenants/${id}/status`, {
    method: 'POST',
    body,
  });
}

export type ExternalIdentity = {
  id: string;
  integrationId: string;
  organizationId: string;
  internalUserId: string;
  externalOrganizationId: string;
  externalUserId: string;
  status: string;
};

export function createManagementUser(
  body: { subject: string; displayName: string },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementUser>('/api/v1/management/users', { method: 'POST', body });
}

export function listManagementUsers(client: HttpClient = apiClient) {
  return client.request<ManagementUser[]>('/api/v1/management/users');
}

export function updateManagementUserStatus(
  id: string,
  body: { expectedVersion: number; status: 'ACTIVE' | 'DISABLED' },
  client: HttpClient = apiClient,
) {
  return client.request<ManagementUser>(`/api/v1/management/users/${id}/status`, {
    method: 'POST',
    body,
  });
}

export function listOrganizationMemberships(
  organizationId: string,
  client: HttpClient = apiClient,
) {
  return client.request<OrganizationMembership[]>(
    `/api/v1/management/organizations/${organizationId}/memberships`,
  );
}

export function createManagementIntegration(
  organizationId: string,
  body: { name: string },
  client: HttpClient = apiClient,
) {
  return client.request<{ id: string; organizationId: string; name: string; status: string }>(
    `/api/v1/management/organizations/${organizationId}/integrations`,
    { method: 'POST', body },
  );
}

export function listManagementIntegrations(organizationId: string, client: HttpClient = apiClient) {
  return client.request<ManagementIntegration[]>(
    `/api/v1/management/organizations/${organizationId}/integrations`,
  );
}

export function listIntegrationCredentials(integrationId: string, client: HttpClient = apiClient) {
  return client.request<
    Array<{
      id: string;
      integrationId: string;
      label: string;
      accessKeyId: string;
      version: number;
      status: string;
    }>
  >(`/api/v1/management/integrations/${integrationId}/credentials`);
}

export function createManagementCredential(
  integrationId: string,
  body: { label: string; credentialRef: string },
  client: HttpClient = apiClient,
) {
  return client.request(`/api/v1/management/integrations/${integrationId}/credentials`, {
    method: 'POST',
    body,
  });
}

export function rotateManagementCredential(
  credentialId: string,
  body: { expectedVersion: number; credentialRef: string },
  client: HttpClient = apiClient,
) {
  return client.request(`/api/v1/management/credentials/${credentialId}/rotate`, {
    method: 'POST',
    body,
  });
}

export function revokeManagementCredential(
  credentialId: string,
  body: { expectedVersion: number },
  client: HttpClient = apiClient,
) {
  return client.request(`/api/v1/management/credentials/${credentialId}/revoke`, {
    method: 'POST',
    body,
  });
}

export function upsertOrganizationMembership(
  organizationId: string,
  body: { subject: string; role: string },
  client: HttpClient = apiClient,
) {
  return client.request<OrganizationMembership>(
    `/api/v1/management/organizations/${organizationId}/memberships`,
    { method: 'POST', body },
  );
}

export function upsertExternalIdentity(
  integrationId: string,
  body: {
    organizationId: string;
    internalUserId: string;
    externalOrganizationId: string;
    externalUserId: string;
  },
  client: HttpClient = apiClient,
) {
  return client.request<ExternalIdentity>(
    `/api/v1/management/integrations/${integrationId}/external-identities`,
    { method: 'POST', body },
  );
}

export type ProvisionedUser = {
  integrationId: string;
  externalOrganizationId: string;
  externalUserId: string;
  displayName: string;
  status: string;
  internalUserId: string;
};

export type ProvisionedMembership = {
  scopeType: string;
  scopeId: string;
  scopeName: string;
  role: string;
};

export function initializeProvisionedUser(
  integrationId: string,
  body: { externalOrganizationId: string; externalUserId: string; displayName: string },
  client: HttpClient = apiClient,
) {
  return client.request<ProvisionedUser>(
    `/api/v1/management/integrations/${integrationId}/provisioned-users`,
    { method: 'POST', body },
  );
}

export function updateProvisionedUser(
  integrationId: string,
  externalOrganizationId: string,
  externalUserId: string,
  body: { displayName: string },
  client: HttpClient = apiClient,
) {
  return client.request<ProvisionedUser>(
    `/api/v1/management/integrations/${integrationId}/provisioned-users/${encodeURIComponent(externalUserId)}?externalOrganizationId=${encodeURIComponent(externalOrganizationId)}`,
    { method: 'PUT', body },
  );
}

export function disableProvisionedUser(
  integrationId: string,
  externalOrganizationId: string,
  externalUserId: string,
  client: HttpClient = apiClient,
) {
  return client.request<ProvisionedUser>(
    `/api/v1/management/integrations/${integrationId}/provisioned-users/${encodeURIComponent(externalUserId)}/disable?externalOrganizationId=${encodeURIComponent(externalOrganizationId)}`,
    { method: 'POST' },
  );
}

export function listProvisionedUserMemberships(
  integrationId: string,
  externalOrganizationId: string,
  externalUserId: string,
  client: HttpClient = apiClient,
) {
  return client.request<ProvisionedMembership[]>(
    `/api/v1/management/integrations/${integrationId}/provisioned-users/${encodeURIComponent(externalUserId)}/memberships`,
    { query: { externalOrganizationId } },
  );
}
