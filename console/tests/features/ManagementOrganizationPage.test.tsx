import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementOrganizationPage } from '../../src/features/management/ManagementOrganizationPage';

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

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementOrganizationPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management organization page', () => {
  it('creates an organization and a tenant in the selected organization', async () => {
    renderPage();
    expect(
      await screen.findByRole('heading', { name: 'Organization 与 Tenant' }),
    ).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('组织名称'), 'Research');
    await userEvent.click(screen.getByRole('button', { name: '创建 Organization' }));
    expect(mocks.createManagementOrganization).toHaveBeenCalledWith({ name: 'Research' });

    await userEvent.selectOptions(screen.getByLabelText('Organization'), 'org-1');
    await userEvent.type(screen.getByLabelText('Tenant 名称'), 'Production');
    await userEvent.click(screen.getByRole('button', { name: '创建 Tenant' }));
    expect(mocks.createManagementTenant).toHaveBeenCalledWith('org-1', { name: 'Production' });
    expect(await screen.findByText('Tenant 已创建')).toBeInTheDocument();
  });

  it('suspends an organization and its tenant with the current version', async () => {
    mocks.listManagementTenants.mockResolvedValueOnce([
      { id: 'tenant-1', organizationId: 'org-1', name: 'Production', status: 'ACTIVE', version: 5 },
    ]);
    renderPage();

    await userEvent.click(await screen.findByRole('button', { name: '暂停 Organization' }));
    expect(mocks.updateManagementOrganizationStatus).toHaveBeenCalledWith('org-1', {
      expectedVersion: 0,
      status: 'SUSPENDED',
    });

    await userEvent.click(await screen.findByRole('button', { name: '暂停 Tenant' }));
    expect(mocks.updateManagementTenantStatus).toHaveBeenCalledWith('tenant-1', {
      expectedVersion: 5,
      status: 'SUSPENDED',
    });
  });
});
