import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementTemplatePage } from '../../src/features/management/ManagementTemplatePage';

const mocks = vi.hoisted(() => ({
  listWorkerTemplates: vi.fn().mockResolvedValue([
    {
      id: 'template-1',
      tenantId: 'tenant-1',
      projectId: 'project-1',
      name: 'research-worker',
      displayName: 'Research Worker',
      currentPublishedRevision: 2,
      version: 3,
    },
  ]),
  createWorkerTemplate: vi.fn().mockResolvedValue({ id: 'template-2' }),
  createWorkerTemplateRevision: vi.fn().mockResolvedValue({
    templateId: 'template-1',
    revision: 3,
    status: 'DRAFT',
    version: 0,
  }),
  publishWorkerTemplateRevision: vi.fn().mockResolvedValue({
    templateId: 'template-1',
    revision: 3,
    status: 'PUBLISHED',
    version: 1,
  }),
  instantiateWorkerTemplate: vi.fn().mockResolvedValue({ id: 'instance-1' }),
}));

vi.mock('../../src/api/managementCatalog', () => ({
  ...mocks,
}));

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementTemplatePage projectId="project-1" />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management template page', () => {
  it('creates a template, creates and publishes a revision, and explicitly instantiates it', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: 'Worker Templates' })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('内部名称'), 'new-worker');
    await userEvent.type(screen.getByLabelText('显示名称'), 'New Worker');
    await userEvent.click(screen.getByRole('button', { name: '创建模板' }));
    expect(mocks.createWorkerTemplate).toHaveBeenCalledWith('project-1', {
      name: 'new-worker',
      displayName: 'New Worker',
      workerType: 'EXECUTOR',
    });

    await userEvent.selectOptions(screen.getByLabelText('模板'), 'template-1');
    await userEvent.clear(screen.getByLabelText('Worker Spec JSON'));
    fireEvent.change(screen.getByLabelText('Worker Spec JSON'), {
      target: { value: '{"runtime":"qwenpaw"}' },
    });
    await userEvent.click(screen.getByRole('button', { name: '创建 Revision' }));
    expect(mocks.createWorkerTemplateRevision).toHaveBeenCalledWith('project-1', 'template-1', {
      specJson: '{"runtime":"qwenpaw"}',
      actor: '',
    });

    await userEvent.click(await screen.findByRole('button', { name: '发布此 Revision' }));
    expect(mocks.publishWorkerTemplateRevision).toHaveBeenCalledWith(
      'project-1',
      'template-1',
      3,
      0,
    );

    await userEvent.click(screen.getByRole('button', { name: '显式实例化 Worker' }));
    expect(mocks.instantiateWorkerTemplate).toHaveBeenCalledWith('project-1', 'template-1', 2);
    expect(await screen.findByText('已创建实例 instance-1，等待 Worker Ready')).toBeInTheDocument();
  });
});
