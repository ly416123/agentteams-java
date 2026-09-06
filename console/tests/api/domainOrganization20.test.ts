import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  createManagementCredential,
  createManagementIntegration,
  createManagementOrganization,
  createManagementTenant,
  createManagementUser,
  disableProvisionedUser,
  listIntegrationCredentials,
  listManagementIntegrations,
  listManagementOrganizations,
  listManagementTenants,
  listManagementUsers,
  listOrganizationMemberships,
  listProvisionedUserMemberships,
  revokeManagementCredential,
  rotateManagementCredential,
  updateManagementOrganizationStatus,
  updateManagementTenantStatus,
  updateManagementUserStatus,
  updateProvisionedUser,
  upsertExternalIdentity,
  upsertOrganizationMembership,
} from '../../src/api/management';

function client() {
  return {
    request: vi.fn().mockResolvedValue({}),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('organization domain API contracts: 20 cases', () => {
  it.each([
    [
      'lists organizations',
      (http: HttpClient) => listManagementOrganizations(http),
      ['/api/v1/management/organizations'],
    ],
    [
      'creates an organization',
      (http: HttpClient) => createManagementOrganization({ name: '研发组织' }, http),
      ['/api/v1/management/organizations', { method: 'POST', body: { name: '研发组织' } }],
    ],
    [
      'lists tenants for an organization',
      (http: HttpClient) => listManagementTenants('org-1', http),
      ['/api/v1/management/organizations/org-1/tenants'],
    ],
    [
      'creates a tenant',
      (http: HttpClient) => createManagementTenant('org-1', { name: '生产租户' }, http),
      [
        '/api/v1/management/organizations/org-1/tenants',
        { method: 'POST', body: { name: '生产租户' } },
      ],
    ],
    [
      'suspends an organization with its version',
      (http: HttpClient) =>
        updateManagementOrganizationStatus(
          'org-1',
          { expectedVersion: 3, status: 'SUSPENDED' },
          http,
        ),
      [
        '/api/v1/management/organizations/org-1/status',
        { method: 'POST', body: { expectedVersion: 3, status: 'SUSPENDED' } },
      ],
    ],
    [
      'suspends a tenant with its version',
      (http: HttpClient) =>
        updateManagementTenantStatus('tenant-1', { expectedVersion: 3, status: 'SUSPENDED' }, http),
      [
        '/api/v1/management/tenants/tenant-1/status',
        { method: 'POST', body: { expectedVersion: 3, status: 'SUSPENDED' } },
      ],
    ],
    [
      'creates an internal user',
      (http: HttpClient) => createManagementUser({ subject: 'alice', displayName: 'Alice' }, http),
      [
        '/api/v1/management/users',
        { method: 'POST', body: { subject: 'alice', displayName: 'Alice' } },
      ],
    ],
    [
      'lists internal users',
      (http: HttpClient) => listManagementUsers(http),
      ['/api/v1/management/users'],
    ],
    [
      'disables an internal user',
      (http: HttpClient) =>
        updateManagementUserStatus('user-1', { expectedVersion: 1, status: 'DISABLED' }, http),
      [
        '/api/v1/management/users/user-1/status',
        { method: 'POST', body: { expectedVersion: 1, status: 'DISABLED' } },
      ],
    ],
    [
      'lists organization memberships',
      (http: HttpClient) => listOrganizationMemberships('org-1', http),
      ['/api/v1/management/organizations/org-1/memberships'],
    ],
    [
      'upserts a developer membership',
      (http: HttpClient) =>
        upsertOrganizationMembership('org-1', { subject: 'alice', role: 'DEVELOPER' }, http),
      [
        '/api/v1/management/organizations/org-1/memberships',
        { method: 'POST', body: { subject: 'alice', role: 'DEVELOPER' } },
      ],
    ],
  ])('%s', async (_name, run, expected) => {
    const http = client();
    await run(http);
    expect(http.request).toHaveBeenCalledWith(...expected);
  });

  it('covers credential rotation and revocation', async () => {
    const http = client();
    await rotateManagementCredential(
      'credential-1',
      { expectedVersion: 2, credentialRef: 'secret://rotated' },
      http,
    );
    await revokeManagementCredential('credential-1', { expectedVersion: 3 }, http);
    expect(http.request).toHaveBeenNthCalledWith(
      1,
      '/api/v1/management/credentials/credential-1/rotate',
      {
        method: 'POST',
        body: { expectedVersion: 2, credentialRef: 'secret://rotated' },
      },
    );
    expect(http.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/management/credentials/credential-1/revoke',
      {
        method: 'POST',
        body: { expectedVersion: 3 },
      },
    );
  });

  it('covers organization status transitions', async () => {
    const http = client();
    await updateManagementOrganizationStatus(
      'org-1',
      { expectedVersion: 4, status: 'DELETED' },
      http,
    );
    expect(http.request).toHaveBeenCalledWith('/api/v1/management/organizations/org-1/status', {
      method: 'POST',
      body: { expectedVersion: 4, status: 'DELETED' },
    });
  });

  it('covers tenant status transitions', async () => {
    const http = client();
    await updateManagementTenantStatus('tenant-1', { expectedVersion: 4, status: 'DELETED' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/management/tenants/tenant-1/status', {
      method: 'POST',
      body: { expectedVersion: 4, status: 'DELETED' },
    });
  });

  it('covers integration creation and listing', async () => {
    const http = client();
    await createManagementIntegration('org-1', { name: '钉钉' }, http);
    await listManagementIntegrations('org-1', http);
    expect(http.request).toHaveBeenNthCalledWith(
      1,
      '/api/v1/management/organizations/org-1/integrations',
      {
        method: 'POST',
        body: { name: '钉钉' },
      },
    );
    expect(http.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/management/organizations/org-1/integrations',
    );
  });

  it('covers credential creation and listing', async () => {
    const http = client();
    await createManagementCredential(
      'integration-1',
      { label: 'primary', credentialRef: 'secret://primary' },
      http,
    );
    await listIntegrationCredentials('integration-1', http);
    expect(http.request).toHaveBeenNthCalledWith(
      1,
      '/api/v1/management/integrations/integration-1/credentials',
      {
        method: 'POST',
        body: { label: 'primary', credentialRef: 'secret://primary' },
      },
    );
    expect(http.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/management/integrations/integration-1/credentials',
    );
  });

  it('covers provisioned user initialization and external identity upsert', async () => {
    const http = client();
    const provisionedUser = {
      externalOrganizationId: '外部组织',
      externalUserId: '外部用户',
      displayName: '用户',
    };
    const identity = {
      organizationId: 'org-1',
      internalUserId: 'user-1',
      externalOrganizationId: 'external-org',
      externalUserId: 'external-user',
    };
    const { initializeProvisionedUser } = await import('../../src/api/management');
    await initializeProvisionedUser('integration-1', provisionedUser, http);
    await upsertExternalIdentity('integration-1', identity, http);
    expect(http.request).toHaveBeenNthCalledWith(
      1,
      '/api/v1/management/integrations/integration-1/provisioned-users',
      {
        method: 'POST',
        body: provisionedUser,
      },
    );
    expect(http.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/management/integrations/integration-1/external-identities',
      {
        method: 'POST',
        body: identity,
      },
    );
  });

  it('updates a provisioned user with encoded external identifiers', async () => {
    const http = client();
    await updateProvisionedUser(
      'integration-1',
      'external org',
      'user/one',
      { displayName: '新用户' },
      http,
    );
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/management/integrations/integration-1/provisioned-users/user%2Fone?externalOrganizationId=external%20org',
      { method: 'PUT', body: { displayName: '新用户' } },
    );
  });

  it('disables a provisioned user with encoded external identifiers', async () => {
    const http = client();
    await disableProvisionedUser('integration-1', 'external org', 'user/one', http);
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/management/integrations/integration-1/provisioned-users/user%2Fone/disable?externalOrganizationId=external%20org',
      { method: 'POST' },
    );
  });

  it('lists provisioned user memberships in external organization scope', async () => {
    const http = client();
    await listProvisionedUserMemberships('integration-1', 'external org', 'user/one', http);
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/management/integrations/integration-1/provisioned-users/user%2Fone/memberships',
      { query: { externalOrganizationId: 'external org' } },
    );
  });
});
