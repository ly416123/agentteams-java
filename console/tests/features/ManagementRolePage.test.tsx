import { render, screen } from '@testing-library/react';
import { renderAuthorized } from '../fixtures/authorizedRender';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementRolePage } from '../../src/features/management/ManagementRolePage';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';
import { ApiError } from '../../src/api/httpClient';

vi.mock('../../src/api/authorization', () => ({
  getEffectiveAuthorization: vi
    .fn()
    .mockResolvedValue({ permissions: ['project:read', 'project:member:manage'], assignments: [] }),
  listManagementRoleAssignments: vi.fn().mockResolvedValue([
    {
      id: 'a-1',
      subject: 'platform-root',
      scopeType: 'PLATFORM',
      role: 'PLATFORM_ADMIN',
      status: 'ACTIVE',
      version: 1,
    },
  ]),
  grantPlatformAdministrator: vi.fn().mockResolvedValue({ id: 'a-2' }),
  grantOrganizationAdministrator: vi.fn().mockResolvedValue({ id: 'a-3' }),
  revokeManagementAdministrator: vi.fn().mockResolvedValue(undefined),
}));

vi.mock('../../src/api/projects', () => ({
  getProjectRole: vi
    .fn()
    .mockResolvedValue({ projectId: 'p-1', subject: 'owner', role: 'ADMIN', allowed: true }),
  listProjects: vi.fn().mockResolvedValue({
    items: [
      {
        id: 'p-1',
        name: '研发项目',
        tenantId: 't-1',
        status: 'ACTIVE',
        createdBy: 'owner',
        version: 4,
      },
    ],
  }),
  listProjectMembers: vi
    .fn()
    .mockResolvedValue([
      { projectId: 'p-1', subject: 'alice', role: 'DEVELOPER', status: 'ACTIVE', version: 2 },
    ]),
  changeProjectMemberRole: vi.fn().mockResolvedValue(undefined),
  transferProjectOwner: vi.fn().mockResolvedValue(undefined),
  listProjectRolePermissions: vi
    .fn()
    .mockResolvedValue([{ role: 'DEVELOPER', permissions: ['PROJECT_READ', 'TASK_CREATE'] }]),
}));

describe('Management role page', () => {
  it('lists project members and changes a role with the member version', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderAuthorized(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '角色与权限' })).toBeInTheDocument();
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getAllByText('TASK_CREATE').length).toBeGreaterThan(0);
    expect(screen.queryByRole('button', { name: '转移所有者' })).not.toBeInTheDocument();
    const { listProjectRolePermissions } = await import('../../src/api/projects');
    expect(listProjectRolePermissions).toHaveBeenCalledWith('p-1');

    await userEvent.selectOptions(screen.getByLabelText('alice 的角色'), 'OPERATOR');
    await userEvent.click(screen.getByRole('button', { name: '保存角色' }));

    const { changeProjectMemberRole } = await import('../../src/api/projects');
    expect(changeProjectMemberRole).toHaveBeenCalledWith('p-1', 'alice', {
      role: 'OPERATOR',
      expectedMembershipVersion: 2,
    });
    expect(await screen.findByText('角色已更新')).toBeInTheDocument();
  });

  it('shows a safe denial message when the server rejects a role change', async () => {
    const { changeProjectMemberRole } = await import('../../src/api/projects');
    vi.mocked(changeProjectMemberRole).mockRejectedValueOnce(
      new ApiError(403, {
        code: 'FORBIDDEN',
        message: 'database row for subject alice is outside org b',
      }),
    );
    renderAuthorized(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    await screen.findByText('alice');
    await userEvent.selectOptions(screen.getByLabelText('alice 的角色'), 'OPERATOR');
    await userEvent.click(screen.getByRole('button', { name: '保存角色' }));

    expect(await screen.findByRole('status')).toHaveTextContent('当前账号没有权限执行此操作。');
    expect(screen.queryByText(/database row for subject/)).not.toBeInTheDocument();
  });

  it('lets only the project owner transfer ownership after confirming target and impact', async () => {
    const { getProjectRole, transferProjectOwner } = await import('../../src/api/projects');
    vi.mocked(getProjectRole).mockResolvedValueOnce({
      projectId: 'p-1',
      subject: 'owner',
      role: 'OWNER',
      allowed: true,
    });
    renderAuthorized(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await userEvent.click(await screen.findByRole('button', { name: '转移所有者' }));
    expect(await screen.findByRole('dialog')).toHaveTextContent(
      '目标成员：alice。转移后对方成为项目 OWNER，你将失去 OWNER 权限',
    );
    await userEvent.click(screen.getByRole('button', { name: '确认转移项目所有者' }));
    expect(transferProjectOwner).toHaveBeenCalledWith('p-1', {
      newOwner: 'alice',
      expectedProjectVersion: 4,
    });
    expect(await screen.findByText('项目所有者已转移')).toBeInTheDocument();
  });

  it('lists and grants platform administrator assignments', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    renderAuthorized(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    expect(await screen.findByText('平台与组织管理员')).toBeInTheDocument();
    expect(await screen.findByText(/platform-root · PLATFORM_ADMIN/)).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('Subject'), 'new-admin');
    await userEvent.click(screen.getByRole('button', { name: '授予管理员' }));
    const { grantPlatformAdministrator } = await import('../../src/api/authorization');
    expect(grantPlatformAdministrator).toHaveBeenCalledWith('new-admin');
  });

  it('does not load or show platform role grants without management role permission', async () => {
    const {
      grantPlatformAdministrator,
      listManagementRoleAssignments,
      revokeManagementAdministrator,
    } = await import('../../src/api/authorization');
    vi.mocked(listManagementRoleAssignments).mockClear();
    vi.mocked(grantPlatformAdministrator).mockClear();
    vi.mocked(revokeManagementAdministrator).mockClear();
    renderAuthorized(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
      undefined,
      ['project:read', 'project:member:manage'],
    );

    expect(await screen.findByRole('heading', { name: '角色与权限' })).toBeInTheDocument();
    expect(screen.queryByText('平台与组织管理员')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '授予管理员' })).not.toBeInTheDocument();
    expect(listManagementRoleAssignments).not.toHaveBeenCalled();
    expect(grantPlatformAdministrator).not.toHaveBeenCalled();
    expect(revokeManagementAdministrator).not.toHaveBeenCalled();
  });

  it('does not fetch or render member identities without membership management permission', async () => {
    const { getEffectiveAuthorization } = await import('../../src/api/authorization');
    const { getProjectRole, listProjectMembers, listProjectRolePermissions } =
      await import('../../src/api/projects');
    vi.mocked(getProjectRole).mockClear();
    vi.mocked(listProjectMembers).mockClear();
    vi.mocked(listProjectRolePermissions).mockClear();
    vi.mocked(getEffectiveAuthorization).mockResolvedValueOnce({
      permissions: ['project:read'],
      assignments: [],
    });
    render(
      <AuthorizationContext.Provider
        value={{ permissions: ['project:read'], loaded: true, failed: false }}
      >
        <QueryClientProvider
          client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
          <MemoryRouter>
            <ManagementRolePage />
          </MemoryRouter>
        </QueryClientProvider>
      </AuthorizationContext.Provider>,
    );
    expect(await screen.findByText('项目成员身份信息仅对项目管理员开放。')).toBeInTheDocument();
    expect(screen.queryByText('alice')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('alice 的角色')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '保存角色' })).not.toBeInTheDocument();
    expect(screen.queryByText('平台与组织管理员')).not.toBeInTheDocument();
    expect(screen.queryByText('有效权限矩阵')).not.toBeInTheDocument();
    expect(listProjectMembers).not.toHaveBeenCalled();
    expect(listProjectRolePermissions).not.toHaveBeenCalled();
    expect(getProjectRole).not.toHaveBeenCalled();
  });
});
