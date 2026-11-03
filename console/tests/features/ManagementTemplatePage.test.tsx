import { renderAuthorized } from '../fixtures/authorizedRender';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementTemplatePage } from '../../src/features/management/ManagementTemplatePage';
import { ApiError } from '../../src/api/httpClient';

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
  instantiateWorkerTemplate: vi.fn().mockResolvedValue({
    id: 'instance-1',
    templateId: 'template-1',
    workerId: 'worker-1',
    status: 'SUCCEEDED',
  }),
}));

vi.mock('../../src/api/managementCatalog', () => ({
  ...mocks,
}));

function renderPage(permissions?: readonly string[]) {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementTemplatePage projectId="project-1" />
      </QueryClientProvider>
    </MemoryRouter>,
    undefined,
    permissions,
  );
}

describe('Management template page', () => {
  it('hides template writes, publish and instantiate controls from readers', async () => {
    renderPage(['agent-spec:read']);
    expect(await screen.findByRole('heading', { name: '工作节点模板' })).toBeInTheDocument();
    await userEvent.click(screen.getByText('新建模板或版本'));

    expect(await screen.findByText('Research Worker')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建模板' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建版本' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '发布此版本' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '显式实例化工作节点' })).not.toBeInTheDocument();
    expect(mocks.createWorkerTemplate).not.toHaveBeenCalled();
    expect(mocks.publishWorkerTemplateRevision).not.toHaveBeenCalled();
    expect(mocks.instantiateWorkerTemplate).not.toHaveBeenCalled();
  });

  it('creates a template, creates and publishes a revision, and explicitly instantiates it', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '工作节点模板' })).toBeInTheDocument();

    await userEvent.type(screen.getByLabelText('内部名称'), 'new-worker');
    await userEvent.type(screen.getByLabelText('显示名称'), 'New Worker');
    await userEvent.click(screen.getByRole('button', { name: '创建模板' }));
    expect(mocks.createWorkerTemplate).toHaveBeenCalledWith('project-1', {
      name: 'new-worker',
      displayName: 'New Worker',
      workerType: 'EXECUTOR',
    });

    await userEvent.selectOptions(screen.getByLabelText('模板'), 'template-1');
    await userEvent.clear(screen.getByLabelText('工作节点规格 JSON'));
    fireEvent.change(screen.getByLabelText('工作节点规格 JSON'), {
      target: { value: '{"runtime":"agentscope"}' },
    });
    await userEvent.click(screen.getByRole('button', { name: '创建版本' }));
    expect(mocks.createWorkerTemplateRevision).toHaveBeenCalledWith('project-1', 'template-1', {
      specJson: '{"runtime":"agentscope"}',
      actor: '',
    });

    await userEvent.click(await screen.findByRole('button', { name: '发布此版本' }));
    expect(mocks.publishWorkerTemplateRevision).toHaveBeenCalledWith(
      'project-1',
      'template-1',
      3,
      0,
    );

    await userEvent.click(screen.getByRole('button', { name: '显式实例化工作节点' }));
    expect(mocks.instantiateWorkerTemplate).toHaveBeenCalledWith('project-1', 'template-1', 2);
    expect(await screen.findByText('已创建实例 instance-1，等待工作节点就绪')).toBeInTheDocument();
    expect(await screen.findByText('实例化成功 · 工作节点 worker-1')).toBeInTheDocument();
  });

  it('shows a failed instantiation result on the template card', async () => {
    mocks.instantiateWorkerTemplate.mockResolvedValueOnce({
      id: 'failed-instance-1',
      templateId: 'template-1',
      status: 'FAILED',
    });
    renderPage();
    await userEvent.click(await screen.findByRole('button', { name: '显式实例化工作节点' }));

    expect(await screen.findByText('实例化失败 · 实例 failed-instance-1')).toBeInTheDocument();
  });

  it('shows a generic safe message when the server denies template instantiation', async () => {
    mocks.instantiateWorkerTemplate.mockRejectedValueOnce(
      new ApiError(403, { code: 'FORBIDDEN', message: 'template scope denied for org b' }),
    );
    renderPage();
    await screen.findByRole('heading', { name: '工作节点模板' });
    await userEvent.click(await screen.findByRole('button', { name: '显式实例化工作节点' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/template scope denied/)).not.toBeInTheDocument();
  });
});
