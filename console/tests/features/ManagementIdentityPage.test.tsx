import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { updateManagementUserStatus } from '../../src/api/management';
import { ManagementIdentityPage } from '../../src/features/management/ManagementIdentityPage';

vi.mock('../../src/api/management', () => ({
  createManagementUser: vi.fn().mockResolvedValue({ id: 'u-1', status: 'ACTIVE' }),
  listManagementUsers: vi
    .fn()
    .mockResolvedValue([
      { id: 'u-1', subject: 'alice', displayName: 'Alice', status: 'ACTIVE', version: 2 },
    ]),
  updateManagementUserStatus: vi.fn().mockResolvedValue({
    id: 'u-1',
    subject: 'alice',
    displayName: 'Alice',
    status: 'DISABLED',
    version: 3,
  }),
  upsertOrganizationMembership: vi.fn().mockResolvedValue({ role: 'ADMIN' }),
  upsertExternalIdentity: vi.fn().mockResolvedValue({ status: 'ACTIVE' }),
  initializeProvisionedUser: vi.fn().mockResolvedValue({ status: 'ACTIVE' }),
  updateProvisionedUser: vi.fn().mockResolvedValue({ status: 'ACTIVE' }),
  disableProvisionedUser: vi.fn().mockResolvedValue({ status: 'DISABLED' }),
  listProvisionedUserMemberships: vi.fn().mockResolvedValue([]),
}));

describe('Management identity page', () => {
  it('submits internal user, membership and external identity forms', async () => {
    render(
      <MemoryRouter>
        <ManagementIdentityPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole('heading', { name: '身份与权限管理' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('内部用户 Subject'), 'alice');
    await userEvent.type(screen.getByLabelText('内部用户名称'), 'Alice');
    await userEvent.click(screen.getByRole('button', { name: '创建内部用户' }));
    expect(await screen.findByText('内部用户已创建')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('组织 ID'), 'org-1');
    await userEvent.type(screen.getByLabelText('成员 Subject'), 'alice');
    await userEvent.click(screen.getByRole('button', { name: '保存组织成员角色' }));
    expect(await screen.findByText('组织成员角色已保存')).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('Integration ID'), 'int-1');
    await userEvent.type(screen.getByLabelText('组织 ID（身份映射）'), 'org-1');
    await userEvent.type(screen.getByLabelText('外部组织 ID'), 'acme');
    await userEvent.type(screen.getByLabelText('外部用户 ID'), 'ding-1');
    await userEvent.type(
      screen.getByLabelText('内部用户 UUID'),
      '11111111-1111-1111-1111-111111111111',
    );
    await userEvent.click(screen.getByRole('button', { name: '保存外部用户映射' }));
    expect(await screen.findByText('外部用户映射已保存')).toBeInTheDocument();
  });

  it('submits the four external-user lifecycle operations', async () => {
    render(
      <MemoryRouter>
        <ManagementIdentityPage />
      </MemoryRouter>,
    );

    await userEvent.type(screen.getByLabelText('生命周期 Integration ID'), 'int-1');
    await userEvent.type(screen.getByLabelText('生命周期外部组织 ID'), 'acme');
    await userEvent.type(screen.getByLabelText('生命周期外部用户 ID'), 'ding-1');
    await userEvent.type(screen.getByLabelText('生命周期用户名称'), 'Alice');
    await userEvent.click(screen.getByRole('button', { name: '初始化外部用户' }));
    expect(await screen.findByText('外部用户已初始化')).toBeInTheDocument();

    await userEvent.clear(screen.getByLabelText('生命周期用户名称'));
    await userEvent.type(screen.getByLabelText('生命周期用户名称'), 'Alice Updated');
    await userEvent.click(screen.getByRole('button', { name: '更新外部用户' }));
    expect(await screen.findByText('外部用户已更新')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '查询 Membership' }));
    expect(await screen.findByText('Membership 查询完成')).toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: '停用外部用户' }));
    expect(await screen.findByText('外部用户已停用')).toBeInTheDocument();
  });

  it('disables an internal user with the current version', async () => {
    render(
      <MemoryRouter>
        <ManagementIdentityPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText(/Alice · alice/)).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '停用内部用户 Alice' }));

    expect(updateManagementUserStatus).toHaveBeenCalledWith('u-1', {
      expectedVersion: 2,
      status: 'DISABLED',
    });
    expect(await screen.findByText('内部用户已停用')).toBeInTheDocument();
  });
});
