import { renderAuthorized } from '../fixtures/authorizedRender';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagementOrganizationPage } from '../../src/features/management/ManagementOrganizationPage';
import { ApiError } from '../../src/api/httpClient';

const mocks = vi.hoisted(() => ({
  listManagementOrganizations: vi
    .fn()
    .mockResolvedValue([{ id: 'org-1', name: 'Platform', status: 'ACTIVE', version: 0 }]),
  createManagementOrganization: vi.fn().mockResolvedValue({
    id: 'org-2',
    name: 'Research',
    status: 'ACTIVE',
    version: 0,
  }),
  listManagementTenants: vi.fn().mockResolvedValue([]),
  createManagementTenant: vi.fn().mockResolvedValue({
    id: 'tenant-1',
    organizationId: 'org-1',
    name: 'Production',
    status: 'ACTIVE',
    version: 0,
  }),
  updateManagementOrganizationStatus: vi.fn().mockResolvedValue({
    id: 'org-1',
    name: 'Platform',
    status: 'SUSPENDED',
    version: 1,
  }),
  updateManagementTenantStatus: vi.fn().mockResolvedValue({
    id: 'tenant-1',
    organizationId: 'org-1',
    name: 'Production',
    status: 'SUSPENDED',
    version: 1,
  }),
}));

vi.mock('../../src/api/management', () => ({ ...mocks }));

function renderPage(permissions?: readonly string[]) {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementOrganizationPage />
      </QueryClientProvider>
    </MemoryRouter>,
    undefined,
    permissions,
  );
}

describe('Management organization page', () => {
  beforeEach(() => vi.clearAllMocks());

  it('shows a generic safe message when the server denies organization creation', async () => {
    mocks.createManagementOrganization.mockRejectedValueOnce(
      new ApiError(403, {
        code: 'FORBIDDEN',
        message: 'restricted organization metadata',
      }),
    );
    renderPage();
    await screen.findByRole('heading', { name: '组织与租户' });
    await userEvent.click(screen.getByText('新建组织或租户'));
    await userEvent.type(screen.getByLabelText('组织名称'), 'Denied organization');
    await userEvent.click(screen.getByRole('button', { name: '创建组织' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/restricted organization metadata/)).not.toBeInTheDocument();
  });

  it('shows organization data but no lifecycle controls to read-only administrators', async () => {
    renderPage(['organization:read']);
    expect(await screen.findByRole('heading', { name: '组织与租户' })).toBeInTheDocument();
    expect(await screen.findByText('Platform')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建组织' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建租户' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '暂停组织' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '暂停租户' })).not.toBeInTheDocument();
    expect(mocks.createManagementOrganization).not.toHaveBeenCalled();
    expect(mocks.createManagementTenant).not.toHaveBeenCalled();
    expect(mocks.updateManagementOrganizationStatus).not.toHaveBeenCalled();
    expect(mocks.updateManagementTenantStatus).not.toHaveBeenCalled();
  });

  it('creates an organization and a tenant in the selected organization', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '组织与租户' })).toBeInTheDocument();
    await userEvent.click(screen.getByText('新建组织或租户'));
    await userEvent.type(screen.getByLabelText('组织名称'), 'Research');
    await userEvent.click(screen.getByRole('button', { name: '创建组织' }));
    expect(mocks.createManagementOrganization).toHaveBeenCalledWith({ name: 'Research' });

    await userEvent.selectOptions(screen.getByLabelText('组织'), 'org-1');
    await userEvent.type(screen.getByLabelText('租户名称'), 'Production');
    await userEvent.click(screen.getByRole('button', { name: '创建租户' }));
    expect(mocks.createManagementTenant).toHaveBeenCalledWith('org-1', { name: 'Production' });
    expect(await screen.findByText('租户已创建')).toBeInTheDocument();
  });

  it('suspends an organization and its tenant with the current version', async () => {
    mocks.listManagementTenants.mockResolvedValueOnce([
      { id: 'tenant-1', organizationId: 'org-1', name: 'Production', status: 'ACTIVE', version: 5 },
    ]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '暂停组织' }));
    expect(mocks.updateManagementOrganizationStatus).toHaveBeenCalledWith('org-1', {
      expectedVersion: 0,
      status: 'SUSPENDED',
    });

    await userEvent.click(await screen.findByRole('button', { name: '暂停租户' }));
    expect(mocks.updateManagementTenantStatus).toHaveBeenCalledWith('tenant-1', {
      expectedVersion: 5,
      status: 'SUSPENDED',
    });
  });
});
