import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { WorkerListPage } from '../../src/features/workers/WorkerListPage';
import { WorkerDetailPage } from '../../src/features/workers/WorkerDetailPage';

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
      configVersion: 'cfg-12',
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
    configVersion: 'cfg-12',
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
});
