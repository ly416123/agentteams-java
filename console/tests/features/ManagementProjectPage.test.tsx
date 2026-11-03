import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementProjectPage } from '../../src/features/projects/ManagementProjectPage';
import { createProject, listProjects } from '../../src/api/projects';
import { renderAuthorized } from '../fixtures/authorizedRender';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';
import { ApiError } from '../../src/api/httpClient';

vi.mock('../../src/api/projects', () => ({
  listProjects: vi.fn().mockResolvedValue({
    items: [
      {
        id: 'p-1',
        tenantId: 't-1',
        name: '研发项目',
        status: 'ACTIVE',
        createdBy: 'alice',
        version: 0,
      },
    ],
  }),
  createProject: vi.fn().mockResolvedValue({
    id: 'p-2',
    tenantId: 't-1',
    name: '新项目',
    status: 'ACTIVE',
    createdBy: 'alice',
    version: 0,
  }),
}));

describe('Management project page', () => {
  it('lists projects and creates a project through the management entry', async () => {
    renderAuthorized(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementProjectPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: '项目管理' })).toBeInTheDocument();
    expect(await screen.findByText('研发项目')).toBeInTheDocument();
    expect(await screen.findByText('活跃')).toBeInTheDocument();
    expect(screen.queryByText('ACTIVE')).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('项目名称'), '新项目');
    await userEvent.click(screen.getByRole('button', { name: '创建项目' }));

    expect(createProject).toHaveBeenCalledWith({ name: '新项目' });
    expect(await screen.findByText('项目已创建')).toBeInTheDocument();
    expect(listProjects).toHaveBeenCalled();
  });

  it('hides project creation for users without project:create', async () => {
    render(
      <AuthorizationContext.Provider
        value={{ permissions: ['project:read'], loaded: true, failed: false }}
      >
        <QueryClientProvider
          client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
        >
          <MemoryRouter>
            <ManagementProjectPage />
          </MemoryRouter>
        </QueryClientProvider>
      </AuthorizationContext.Provider>,
    );
    expect(await screen.findByRole('heading', { name: '项目管理' })).toBeInTheDocument();
    expect(screen.queryByText('创建项目')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('项目名称')).not.toBeInTheDocument();
  });

  it('shows a generic safe message when the server denies project creation', async () => {
    vi.mocked(createProject).mockRejectedValueOnce(
      new ApiError(403, { code: 'FORBIDDEN', message: 'project creation outside tenant' }),
    );
    renderAuthorized(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementProjectPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );
    await screen.findByRole('heading', { name: '项目管理' });
    await userEvent.type(screen.getByLabelText('项目名称'), 'Denied project');
    await userEvent.click(screen.getByRole('button', { name: '创建项目' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/outside tenant/)).not.toBeInTheDocument();
  });
});
