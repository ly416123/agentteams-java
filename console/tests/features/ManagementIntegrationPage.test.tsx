import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementIntegrationPage } from '../../src/features/management/ManagementIntegrationPage';

const mocks = vi.hoisted(() => ({
  listManagementOrganizations: vi
    .fn()
    .mockResolvedValue([{ id: 'org-1', name: 'Platform', status: 'ACTIVE', version: 0 }]),
  listManagementIntegrations: vi.fn().mockResolvedValue([
    {
      id: 'integration-1',
      organizationId: 'org-1',
      name: 'DingTalk',
      status: 'ACTIVE',
      version: 0,
    },
  ]),
  createManagementIntegration: vi.fn().mockResolvedValue({ id: 'integration-2' }),
  listIntegrationCredentials: vi.fn().mockResolvedValue([
    {
      id: 'credential-1',
      integrationId: 'integration-1',
      label: 'primary',
      accessKeyId: 'AKIA-1',
      version: 2,
      status: 'ACTIVE',
    },
  ]),
  createManagementCredential: vi.fn().mockResolvedValue({ id: 'credential-2' }),
  rotateManagementCredential: vi
    .fn()
    .mockResolvedValue({ id: 'credential-1', version: 3, status: 'ACTIVE' }),
  revokeManagementCredential: vi
    .fn()
    .mockResolvedValue({ id: 'credential-1', version: 3, status: 'REVOKED' }),
}));

vi.mock('../../src/api/management', () => ({ ...mocks }));

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementIntegrationPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management integration page', () => {
  it('creates an integration, registers a credential reference, rotates and revokes it', async () => {
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
    renderPage();
    expect(
      await screen.findByRole('heading', { name: 'Integrations 与 Credentials' }),
    ).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Integration 名称'), 'Search');
    await userEvent.click(screen.getByRole('button', { name: '创建 Integration' }));
    expect(mocks.createManagementIntegration).toHaveBeenCalledWith('org-1', { name: 'Search' });

    await userEvent.type(screen.getByLabelText('Credential Label'), 'secondary');
    await userEvent.type(screen.getByLabelText('Credential Ref'), 'secret://integration/secondary');
    await userEvent.click(screen.getByRole('button', { name: '登记 Credential Ref' }));
    expect(mocks.createManagementCredential).toHaveBeenCalledWith('integration-1', {
      label: 'secondary',
      credentialRef: 'secret://integration/secondary',
    });

    await userEvent.type(
      screen.getByLabelText('轮换 Credential Ref'),
      'secret://integration/rotated',
    );
    await userEvent.click(screen.getByRole('button', { name: '轮换' }));
    expect(mocks.rotateManagementCredential).toHaveBeenCalledWith('credential-1', {
      expectedVersion: 2,
      credentialRef: 'secret://integration/rotated',
    });
    await userEvent.click(screen.getByRole('button', { name: '撤销' }));
    expect(mocks.revokeManagementCredential).toHaveBeenCalledWith('credential-1', {
      expectedVersion: 2,
    });
    expect(confirm).toHaveBeenCalledWith(
      '确认撤销 Credential？撤销后引用它的运行时将无法继续使用。',
    );
    confirm.mockRestore();
  });
});
