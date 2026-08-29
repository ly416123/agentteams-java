import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../src/api/httpClient';
import { getWorker, rolloutWorker, workerAction } from '../../src/api/workers';
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
  it('lists runtime, capabilities, heartbeat and current task', async () => {
    renderWithQuery(<WorkerListPage projectId="p-1" />);
    expect(await screen.findByText('分析 Worker')).toBeInTheDocument();
    expect(screen.getByText('FAKE')).toBeInTheDocument();
    expect(screen.getByText('reports')).toBeInTheDocument();
  });

  it('offers lifecycle actions with a version and operation history', async () => {
    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);
    expect(await screen.findByText('分析 Worker')).toBeInTheDocument();
    expect(screen.getByText('v1.4.0')).toBeInTheDocument();
    expect(screen.getByText('Drain')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: 'Drain' }));
    expect(screen.getByText('操作已提交')).toBeInTheDocument();
    expect(screen.getByText('DRAIN')).toBeInTheDocument();
  });

  it('submits rollout with the current worker version', async () => {
    renderWithQuery(<WorkerDetailPage projectId="p-1" workerId="worker-1" />);
    await screen.findByText('分析 Worker');
    await userEvent.click(screen.getByRole('button', { name: 'Rollout' }));
    expect(await screen.findByText('操作已提交')).toBeInTheDocument();
    expect(rolloutWorker).toHaveBeenCalledWith(
      'worker-1',
      expect.objectContaining({ expectedVersion: 5 }),
    );
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

    await userEvent.click(screen.getByRole('button', { name: 'Rollout' }));

    expect(
      screen.getByText('镜像 Digest、配置 Revision、Secret Generation 均为必填项。'),
    ).toBeInTheDocument();
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
    await screen.findByText('资源状态已更新');
    await userEvent.click(screen.getByRole('button', { name: '仍然继续操作' }));

    expect(workerAction).toHaveBeenLastCalledWith('worker-1', 'drain', 6);
  });
});
