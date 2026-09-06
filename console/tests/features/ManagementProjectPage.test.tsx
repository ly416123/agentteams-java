import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementProjectPage } from '../../src/features/projects/ManagementProjectPage';
import { createProject, listProjects } from '../../src/api/projects';

vi.mock('../../src/api/projects', () => ({
  listProjects: vi.fn().mockResolvedValue({
    items: [{ id: 'p-1', tenantId: 't-1', name: '研发项目', status: 'ACTIVE', createdBy: 'alice' }],
  }),
  createProject: vi.fn().mockResolvedValue({
    id: 'p-2',
    tenantId: 't-1',
    name: '新项目',
    status: 'ACTIVE',
    createdBy: 'alice',
  }),
}));

describe('Management project page', () => {
  it('lists projects and creates a project through the management entry', async () => {
    render(
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
});
