import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { listProjects } from '../../src/api/projects';
import { ProjectProvider } from '../../src/auth/ProjectContext';
import { ProjectSwitcher } from '../../src/features/projects/ProjectSwitcher';

vi.mock('../../src/api/projects', () => ({
  listProjects: vi.fn().mockResolvedValue([
    { id: 'p-1', name: '平台工程', tenantId: 't-1', status: 'ACTIVE', createdBy: 'admin' },
    { id: 'p-2', name: '研究项目', tenantId: 't-1', status: 'ACTIVE', createdBy: 'admin' },
  ]),
}));

describe('ProjectSwitcher', () => {
  it('switches project and removes resource caches from the previous context', async () => {
    const queryClient = new QueryClient();
    queryClient.setQueryData(['tasks', 'p-1', { phase: 'RUNNING' }], { items: ['stale'] });
    render(
      <MemoryRouter initialEntries={['/p-1/overview']}>
        <QueryClientProvider client={queryClient}>
          <ProjectProvider projectId="p-1">
            <ProjectSwitcher />
          </ProjectProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );

    await screen.findByRole('option', { name: '研究项目' });
    await userEvent.selectOptions(screen.getByRole('combobox'), 'p-2');
    expect(screen.getByRole('combobox')).toHaveValue('p-2');
    expect(queryClient.getQueryData(['tasks', 'p-1', { phase: 'RUNNING' }])).toBeUndefined();
    expect(listProjects).toHaveBeenCalledOnce();
  });
});
