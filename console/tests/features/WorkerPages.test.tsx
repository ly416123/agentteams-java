import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../src/api/httpClient';
import {
  getWorker,
  listOperations,
  listWorkers,
  rolloutWorker,
  workerAction,
} from '../../src/api/workers';
import { WorkerListPage } from '../../src/features/workers/WorkerListPage';
import { WorkerDetailPage } from '../../src/features/workers/WorkerDetailPage';
import { WorkerOperationPanel } from '../../src/features/workers/WorkerOperationPanel';

vi.mock('../../src/api/workers', () => ({
  listWorkers: vi.fn().mockResolvedValue([
    {
      id: 'worker-1',
      name: '分析 Worker',
      phase: 'READY',
      runtime: 'FAKE',
      createdAt: '2026-08-29T01:00:00Z',
      updatedAt: '2026-08-29T02:00:00Z',
      version: 5,
      capabilities: ['reports', 'search'],
      currentTaskId: 'task-1',
      imageVersion: 'v1.4.0',
      imageDigest: 'sha256:worker-1',
      configVersion: 'cfg-12',
      configRevision: 'cfg-12',
      secretGeneration: 'secret-12',
      previousStableSpec: '{"runtime":"FAKE"}',
      lastHeartbeat: '2026-08-29T02:00:00Z',
    },
  ]),
  getWorker: vi.fn().mockResolvedValue({
    id: 'worker-1',
    name: '分析 Worker',
    phase: 'READY',
    runtime: 'FAKE',
    createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 5,
    capabilities: ['reports', 'search'],
    currentTaskId: 'task-1',
    imageVersion: 'v1.4.0',
    imageDigest: 'sha256:worker-1',
    configVersion: 'cfg-12',
    configRevision: 'cfg-12',
    secretGeneration: 'secret-12',
    previousStableSpec: '{"runtime":"FAKE"}',
    lastHeartbeat: '2026-08-29T02:00:00Z',
  }),
  listOperations: vi.fn().mockResolvedValue([
    {
      id: 'op-1',
      agentId: 'worker-1',
      type: 'DRAIN',
      status: 'SUCCEEDED',
      createdAt: '2026-08-29T01:00:00Z',
      updatedAt: '2026-08-29T02:00:00Z',
      version: 1,
    },
  ]),
  workerAction: vi.fn().mockResolvedValue({
    id: 'op-2',
    agentId: 'worker-1',
    type: 'DRAIN',
    status: 'PENDING',
    createdAt: '2026-08-29T02:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 1,
  }),
  rolloutWorker: vi.fn().mockResolvedValue({
    id: 'op-3',
    agentId: 'worker-1',
    type: 'ROLLOUT',
    status: 'PENDING',
    createdAt: '2026-08-29T02:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 1,
  }),
  rollbackWorker: vi.fn().mockResolvedValue({
    id: 'op-4',
    agentId: 'worker-1',
    type: 'ROLLBACK',
    status: 'PENDING',
    createdAt: '2026-08-29T02:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 1,
  }),
}));

function renderWithQuery(ui: React.ReactNode) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Worker pages', () => {
  it('passes the current Project scope to the worker query', async () => {
    vi.mocked(listWorkers).mockClear();

    renderWithQuery(<WorkerListPage projectId="p-1" />);
    await screen.findByText('分析 Worker');

    expect(listWorkers).toHaveBeenCalledWith('p-1', {
      search: '',
      phase: '',
      cursor: undefined,
    });
  });

  it('lists runtime, capabilities, heartbeat and current task', async () => {
    renderWithQuery(<WorkerListPage projectId="p-1" />);
    expect(await screen.findByText('分析 Worker')).toBeInTheDocument();
    expect(screen.getByText('FAKE')).toBeInTheDocument();
    expect(screen.getByText('reports')).toBeInTheDocument();
  });

  it('surfaces rollout failure and operator/gateway observations for diagnosis', async () => {
    vi.mocked(listOperations).mockResolvedValueOnce({
      items: [
        {
          id: 'failed-rollout',
          agentId: 'worker-1',
          type: 'ROLLOUT',
          status: 'FAILED',
          requestedSpecDigest: 'sha256:new',
          createdAt: '2026-08-29T01:00:00Z',
          updatedAt: '2026-08-29T02:00:00Z',
          version: 3,
          failureCategory: 'IMAGE_PULL_BACKOFF',
          operatorReady: false,
          gatewayOnline: false,
          observationsMatch: false,
        },
      ],
      hasMore: false,
    });

    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);

    expect(await screen.findByText(/失败原因：IMAGE_PULL_BACKOFF/)).toBeInTheDocument();
    expect(screen.getByText(/Operator：未就绪/)).toBeInTheDocument();
    expect(screen.getByText(/Gateway：离线/)).toBeInTheDocument();
    expect(screen.getByText(/观测结果：不匹配/)).toBeInTheDocument();
  });

  it('offers lifecycle actions with a version and operation history', async () => {
    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);
    expect(await screen.findByText('分析 Worker')).toBeInTheDocument();
    expect(screen.getByText('v1.4.0')).toBeInTheDocument();
    expect(screen.getByText('Drain')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Drain' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('将停止接收新任务');
    await userEvent.click(screen.getByRole('button', { name: '确认 Drain' }));
    expect(screen.getByText('操作已提交')).toBeInTheDocument();
    expect(screen.getByText('DRAIN')).toBeInTheDocument();
  });

  it('submits rollout with the current worker version', async () => {
    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);
    await screen.findByText('分析 Worker');
    await userEvent.click(screen.getByRole('button', { name: 'Rollout' }));
    await userEvent.click(screen.getByRole('button', { name: '确认 Rollout' }));
    expect(await screen.findByText('操作已提交')).toBeInTheDocument();
    expect(rolloutWorker).toHaveBeenCalledWith(
      'p-1',
      'worker-1',
      expect.objectContaining({ expectedVersion: 5, imageDigest: 'sha256:worker-1' }),
    );
    expect(vi.mocked(rolloutWorker).mock.calls.at(-1)?.[2]).not.toHaveProperty('owner');
  });

  it('requires rollout metadata instead of fabricating missing values', async () => {
    vi.mocked(rolloutWorker).mockClear();
    renderWithQuery(
      <WorkerOperationPanel
        projectId="p-1"
        worker={{
          id: 'worker-2',
          name: '未配置 Worker',
          phase: 'READY',
          runtime: 'FAKE',
          createdAt: '2026-08-29T01:00:00Z',
          updatedAt: '2026-08-29T02:00:00Z',
          version: 8,
        }}
        onRefresh={async () => ({})}
      />,
    );

    expect(
      screen.getByText(
        'Rollout 提交已禁用：镜像 Digest、配置 Revision、Secret Generation、稳定规格快照均需提供真实值。',
      ),
    ).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Rollout' })).toBeDisabled();
    expect(rolloutWorker).not.toHaveBeenCalled();
  });

  it('refreshes the latest worker version before retrying a conflict', async () => {
    vi.mocked(getWorker)
      .mockImplementationOnce(async () => ({
        id: 'worker-1',
        name: '分析 Worker',
        phase: 'READY',
        runtime: 'FAKE',
        createdAt: '2026-08-29T01:00:00Z',
        updatedAt: '2026-08-29T02:00:00Z',
        version: 5,
      }))
      .mockImplementationOnce(async () => ({
        id: 'worker-1',
        name: '分析 Worker',
        phase: 'READY',
        runtime: 'FAKE',
        createdAt: '2026-08-29T01:00:00Z',
        updatedAt: '2026-08-29T03:00:00Z',
        version: 6,
      }));
    vi.mocked(workerAction)
      .mockRejectedValueOnce(new ApiError(409, { message: 'version changed' }))
      .mockResolvedValueOnce({
        id: 'op-5',
        agentId: 'worker-1',
        type: 'DRAIN',
        status: 'PENDING',
        createdAt: '2026-08-29T03:00:00Z',
        updatedAt: '2026-08-29T03:00:00Z',
        version: 1,
      });

    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);
    await screen.findByText('分析 Worker');
    await userEvent.click(screen.getByRole('button', { name: 'Drain' }));
    await userEvent.click(screen.getByRole('button', { name: '确认 Drain' }));
    await screen.findByText('资源状态已更新');
    await userEvent.click(screen.getByRole('button', { name: '仍然继续操作' }));

    expect(workerAction).toHaveBeenLastCalledWith('p-1', 'worker-1', 'drain', 6);
  });

  it('follows the backend worker phase matrix for lifecycle buttons', () => {
    renderWithQuery(
      <WorkerOperationPanel
        projectId="p-1"
        worker={{
          id: 'worker-draining',
          name: '排空 Worker',
          phase: 'DRAINING',
          runtime: 'FAKE',
          createdAt: '2026-08-29T01:00:00Z',
          updatedAt: '2026-08-29T02:00:00Z',
          version: 5,
        }}
        operations={[]}
        onRefresh={async () => ({})}
      />,
    );
    expect(screen.getByRole('button', { name: 'Drain' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Terminate' })).toBeEnabled();
    expect(screen.getByRole('button', { name: 'Rollout' })).toBeDisabled();
  });
});
