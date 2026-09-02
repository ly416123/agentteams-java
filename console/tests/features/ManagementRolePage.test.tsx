import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementRolePage } from '../../src/features/management/ManagementRolePage';

vi.mock('../../src/api/projects', () => ({
  listProjects: vi.fn().mockResolvedValue({
    items: [{ id: 'p-1', name: '研发项目', tenantId: 't-1', status: 'ACTIVE', createdBy: 'owner' }],
  }),
  listProjectMembers: vi
    .fn()
    .mockResolvedValue([
      { projectId: 'p-1', subject: 'alice', role: 'DEVELOPER', status: 'ACTIVE', version: 2 },
    ]),
  changeProjectMemberRole: vi.fn().mockResolvedValue(undefined),
  listProjectRolePermissions: vi
    .fn()
    .mockResolvedValue([{ role: 'DEVELOPER', permissions: ['PROJECT_READ', 'TASK_CREATE'] }]),
}));

describe('Management role page', () => {
  it('lists project members and changes a role with the member version', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ManagementRolePage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '角色与权限' })).toBeInTheDocument();
    expect(await screen.findByText('alice')).toBeInTheDocument();
    expect(screen.getAllByText('TASK_CREATE').length).toBeGreaterThan(0);
    const { listProjectRolePermissions } = await import('../../src/api/projects');
    expect(listProjectRolePermissions).toHaveBeenCalledWith('p-1');

    await userEvent.selectOptions(screen.getByLabelText('alice 的角色'), 'OPERATOR');
    await userEvent.click(screen.getByRole('button', { name: '保存 alice 的角色' }));

    const { changeProjectMemberRole } = await import('../../src/api/projects');
    expect(changeProjectMemberRole).toHaveBeenCalledWith('p-1', 'alice', {
      role: 'OPERATOR',
      expectedMembershipVersion: 2,
    });
    expect(await screen.findByText('角色已更新')).toBeInTheDocument();
  });
});
