import { renderAuthorized } from '../fixtures/authorizedRender';
import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementAgentSpecPage } from '../../src/features/management/ManagementAgentSpecPage';
import { ApiError } from '../../src/api/httpClient';

const mocks = vi.hoisted(() => ({
  listAgentSpecs: vi.fn().mockResolvedValue([
    {
      id: 'spec-1',
      name: 'research-agent',
      runtime: 'agentscope',
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

function renderPage(permissions?: readonly string[]) {
  return renderAuthorized(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>
        <ManagementAgentSpecPage projectId="project-1" />
      </QueryClientProvider>
    </MemoryRouter>,
    undefined,
    permissions,
  );
}

describe('Management AgentSpec page', () => {
  it('hides AgentSpec writes and lifecycle actions from readers', async () => {
    renderPage(['agent-spec:read']);
    expect(await screen.findByRole('heading', { name: '智能体规格' })).toBeInTheDocument();
    expect(await screen.findByText('research-agent')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '创建智能体规格' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '发布' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '停用' })).not.toBeInTheDocument();
    expect(mocks.createAgentSpec).not.toHaveBeenCalled();
    expect(mocks.publishAgentSpec).not.toHaveBeenCalled();
    expect(mocks.deactivateAgentSpec).not.toHaveBeenCalled();
  });

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
      runtime: 'agentscope',
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

  it('shows a generic safe message when the server denies AgentSpec creation', async () => {
    mocks.createAgentSpec.mockRejectedValueOnce(
      new ApiError(403, { code: 'FORBIDDEN', message: 'private project configuration' }),
    );
    renderPage();
    await screen.findByRole('heading', { name: '智能体规格' });
    await userEvent.type(screen.getByLabelText('内部名称'), 'denied-agent');
    await userEvent.type(screen.getByLabelText('模型服务商'), 'local');
    await userEvent.type(screen.getByLabelText('模型名称'), 'qwen');
    fireEvent.change(screen.getByLabelText('规格 JSON'), { target: { value: '{}' } });
    await userEvent.click(screen.getByRole('button', { name: '创建智能体规格' }));

    expect(await screen.findByText('当前账号没有权限执行此操作。')).toHaveAttribute(
      'role',
      'status',
    );
    expect(screen.queryByText(/private project configuration/)).not.toBeInTheDocument();
  });
});
