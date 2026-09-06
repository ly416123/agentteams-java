import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementAgentSpecPage } from '../../src/features/management/ManagementAgentSpecPage';

const mocks = vi.hoisted(() => ({
  listAgentSpecs: vi.fn().mockResolvedValue([
    {
      id: 'spec-1',
      name: 'research-agent',
      runtime: 'qwenpaw',
      workerType: 'EXECUTOR',
      modelProvider: 'local',
      modelName: 'qwen',
      teamRef: null,
      desiredState: 'RUNNING',
      lifecycleStatus: 'DRAFT',
      spec: '{}',
      version: 0,
      tenantId: 'tenant-1',
      projectId: 'project-1',
    },
  ]),
  createAgentSpec: vi.fn().mockResolvedValue({ id: 'spec-2' }),
  publishAgentSpec: vi.fn().mockResolvedValue({ lifecycleStatus: 'PUBLISHED' }),
  deactivateAgentSpec: vi.fn().mockResolvedValue({ lifecycleStatus: 'DISABLED' }),
}));

vi.mock('../../src/api/managementCatalog', () => ({ ...mocks }));

function renderPage() {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementAgentSpecPage projectId="project-1" />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Management AgentSpec page', () => {
  it('creates, publishes and deactivates an AgentSpec', async () => {
    renderPage();
    expect(await screen.findByRole('heading', { name: '智能体规格' })).toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('内部名称'), 'new-agent');
    await userEvent.type(screen.getByLabelText('模型服务商'), 'local');
    await userEvent.type(screen.getByLabelText('模型名称'), 'qwen');
    fireEvent.change(screen.getByLabelText('规格 JSON'), { target: { value: '{"mode":"safe"}' } });
    await userEvent.click(screen.getByRole('button', { name: '创建智能体规格' }));
    expect(mocks.createAgentSpec).toHaveBeenCalledWith('project-1', {
      name: 'new-agent',
      runtime: 'qwenpaw',
      workerType: 'EXECUTOR',
      modelProvider: 'local',
      modelName: 'qwen',
      teamRef: undefined,
      desiredState: 'RUNNING',
      spec: { mode: 'safe' },
    });
    await userEvent.click(screen.getByRole('button', { name: '发布' }));
    expect(mocks.publishAgentSpec).toHaveBeenCalledWith('project-1', 'spec-1');
    await userEvent.click(screen.getByRole('button', { name: '停用' }));
    expect(mocks.deactivateAgentSpec).toHaveBeenCalledWith('project-1', 'spec-1');
  });
});
