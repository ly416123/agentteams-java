import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { OverviewPage } from '../../src/features/overview/OverviewPage';

vi.mock('../../src/api/overview', () => ({
  getOverview: vi.fn().mockResolvedValue({
    tasks: { total: 24, queued: 4, running: 3, succeeded: 15, failed: 2 },
    workers: { ready: 5, connecting: 1, unhealthy: 0, draining: 1 },
    teams: { total: 3, active: 2 },
    recentTasks: [],
    alerts: [
      {
        id: 'a-1',
        severity: 'WARNING',
        message: 'Worker 心跳延迟',
        createdAt: '2026-08-29T02:00:00Z',
      },
    ],
  }),
}));

describe('OverviewPage', () => {
  it('renders project metrics and actionable alert summary', async () => {
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <OverviewPage projectId="p-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText('24')).toBeInTheDocument();
    expect(screen.getByText('Worker 心跳延迟')).toBeInTheDocument();
    expect(screen.getByText('运行概览')).toBeInTheDocument();
  });

  it('does not treat a paginated first page as global counts and isolates card failures', async () => {
    const client = {
      request: vi.fn((path: string) => {
        if (path === '/api/v1/dashboard/summary') {
          return Promise.resolve({
            from: '2026-08-29T00:00:00Z',
            to: '2026-08-29T02:00:00Z',
            calls: 10,
            failures: 1,
            promptTokens: 1,
            completionTokens: 2,
            estimatedCostUsd: 0.1,
            averageLatencyMillis: 3,
            byProviderModel: [],
            groups: [],
          });
        }
        if (path === '/api/v1/dashboard/alerts') return Promise.resolve([]);
        if (path === '/api/v1/teams/page') return Promise.resolve({ items: [{ id: 'team-1' }] });
        if (path === '/api/v1/agents')
          return Promise.reject({ status: 503, message: 'agents down' });
        return Promise.resolve({ items: [{ id: 'task-1', phase: 'RUNNING' }] });
      }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    const { getOverview: realGetOverview } =
      await vi.importActual<typeof import('../../src/api/overview')>('../../src/api/overview');
    const overview = await realGetOverview('p-1', client as never);

    expect(overview.tasks.total).toBeNull();
    expect(overview.tasks.running).toBeNull();
    expect(overview.workers.ready).toBeNull();
    expect(overview.errors?.workers).toMatchObject({ status: 503 });
    expect(overview.recentTasks).toHaveLength(1);
  });
});
