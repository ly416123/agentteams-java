import { renderAuthorized } from '../fixtures/authorizedRender';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ManagementIntegrationPage } from '../../src/features/management/ManagementIntegrationPage';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';
import { ApiError } from '../../src/api/httpClient';

const mocks = vi.hoisted(() => ({
  credentialState: {
    current: {
      id: 'credential-1',
      integrationId: 'integration-1',
      label: 'primary',
      accessKeyId: 'AKIA-1',
      version: 2,
      status: 'ACTIVE',
    },
  },
  credentialListCallCount: 0,
  credentialRefreshes: [] as Array<() => void>,
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
  listIntegrationCredentials: vi.fn().mockImplementation(async () => {
    mocks.credentialListCallCount += 1;
    if (mocks.credentialListCallCount <= 2) {
      return [mocks.credentialState.current];
    }
    return new Promise((resolve) => {
      mocks.credentialRefreshes.push(() => resolve([mocks.credentialState.current]));
    });
  }),
  createManagementCredential: vi.fn().mockResolvedValue({ id: 'credential-2' }),
  rotateManagementCredential: vi.fn().mockImplementation(async () => {
    mocks.credentialState.current = {
      ...mocks.credentialState.current,
      version: 3,
    };
    return mocks.credentialState.current;
  }),
  revokeManagementCredential: vi.fn().mockImplementation(async () => {
    mocks.credentialState.current = {
      ...mocks.credentialState.current,
      version: 4,
      status: 'REVOKED',
    };
    return mocks.credentialState.current;
  }),
}));

vi.mock('../../src/api/management', () => ({ ...mocks }));

function renderPage() {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementIntegrationPage />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management integration page', () => {
  beforeEach(() => {
    mocks.credentialListCallCount = 0;
    mocks.credentialRefreshes.length = 0;
    mocks.credentialState.current = {
      id: 'credential-1',
      integrationId: 'integration-1',
      label: 'primary',
      accessKeyId: 'AKIA-1',
      version: 2,
      status: 'ACTIVE',
    };
  });

  it('shows a generic safe message when the server denies an integration write', async () => {
    mocks.createManagementIntegration.mockRejectedValueOnce(
      new ApiError(403, {
        code: 'FORBIDDEN',
        message: 'internal organization record is restricted',
      }),
    );
    renderPage();
    await screen.findByRole('heading', { name: '集成与凭据' });
    await userEvent.click(screen.getByText('新增集成或凭据引用'));
    await userEvent.type(screen.getByLabelText('集成名称'), 'Denied integration');
    await userEvent.click(screen.getByRole('button', { name: '创建集成' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/internal organization record/)).not.toBeInTheDocument();
  });

  it('creates an integration, registers a credential reference, rotates and revokes it', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '集成与凭据' })).toBeInTheDocument();
    await userEvent.click(screen.getByText('新增集成或凭据引用'));
    await userEvent.type(screen.getByLabelText('集成名称'), 'Search');
    await userEvent.click(screen.getByRole('button', { name: '创建集成' }));
    expect(mocks.createManagementIntegration).toHaveBeenCalledWith('org-1', { name: 'Search' });

    await userEvent.type(screen.getByLabelText('凭据标签'), 'secondary');
    await userEvent.type(screen.getByLabelText('凭据引用'), 'secret://integration/secondary');
    await userEvent.click(screen.getByRole('button', { name: '登记凭据引用' }));
    expect(mocks.createManagementCredential).toHaveBeenCalledWith('integration-1', {
      label: 'secondary',
      credentialRef: 'secret://integration/secondary',
    });

    await userEvent.type(screen.getByLabelText('轮换凭据引用'), 'secret://integration/rotated');
    await userEvent.click(screen.getByRole('button', { name: '轮换' }));
    expect(mocks.rotateManagementCredential).toHaveBeenCalledWith('credential-1', {
      expectedVersion: 2,
      credentialRef: 'secret://integration/rotated',
    });
    await waitFor(() => expect(mocks.credentialRefreshes).toHaveLength(1));
    expect(screen.queryByText('凭据已轮换')).not.toBeInTheDocument();
    mocks.credentialRefreshes.shift()?.();
    expect(await screen.findByText('凭据已轮换')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '撤销' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent(
      '目标凭据：primary（AKIA-1）。撤销后引用它的运行时将无法继续使用。',
    );
    await userEvent.click(screen.getByRole('button', { name: '确认撤销凭据' }));
    expect(mocks.revokeManagementCredential).toHaveBeenCalledWith('credential-1', {
      expectedVersion: 3,
    });
    await waitFor(() => expect(mocks.credentialRefreshes).toHaveLength(1));
    mocks.credentialRefreshes.shift()?.();
    expect(await screen.findByText('凭据已撤销')).toBeInTheDocument();
    expect(await screen.findByText('primary · AKIA-1 · 已撤销')).toBeInTheDocument();
  });

  it('does not render credential creation or rotation inputs for read-only users', async () => {
    render(
      <AuthorizationContext.Provider
        value={{
          permissions: ['integration:read', 'credential:read'],
          loaded: true,
          failed: false,
        }}
      >
        <MemoryRouter>
          <QueryClientProvider client={new QueryClient()}>
            <ManagementIntegrationPage />
          </QueryClientProvider>
        </MemoryRouter>
      </AuthorizationContext.Provider>,
    );
    expect(await screen.findByRole('heading', { name: '集成与凭据' })).toBeInTheDocument();
    expect(screen.queryByLabelText('凭据标签')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('凭据引用')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('轮换凭据引用')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '撤销' })).not.toBeInTheDocument();
  });

  it('shows credential registration only to credential managers and rotation only to rotators', async () => {
    mocks.credentialListCallCount = 0;
    mocks.credentialState.current = {
      id: 'credential-1',
      integrationId: 'integration-1',
      label: 'primary',
      accessKeyId: 'AKIA-1',
      version: 2,
      status: 'ACTIVE',
    };
    mocks.listIntegrationCredentials.mockResolvedValue([mocks.credentialState.current]);
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <AuthorizationContext.Provider
            value={{
              permissions: ['integration:read', 'credential:read', 'credential:manage'],
              loaded: true,
              failed: false,
            }}
          >
            <ManagementIntegrationPage />
          </AuthorizationContext.Provider>
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '登记凭据引用' })).toBeInTheDocument();
    expect(await screen.findByText(/primary · AKIA-1/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '轮换' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '撤销' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('轮换凭据引用')).not.toBeInTheDocument();
  });
});
