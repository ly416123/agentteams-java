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
    expect(screen.getByText('15')).toBeInTheDocument();
    expect(screen.getByText('5')).toBeInTheDocument();
    expect(screen.getAllByText('2')).toHaveLength(2);
    expect(screen.getByText('3 个执行中')).toBeInTheDocument();
    expect(screen.getByText('2 个失败')).toBeInTheDocument();
    expect(screen.getByText('1 个连接中')).toBeInTheDocument();
    expect(screen.getByText('共 3 个 Team')).toBeInTheDocument();
    expect(screen.getByText('Worker 心跳延迟')).toBeInTheDocument();
    expect(screen.getByText('运行概览')).toBeInTheDocument();
  });

  it('shows an explicit unavailable state for resource metrics without backend aggregates', async () => {
    const { getOverview: mockedGetOverview } =
      await vi.importMock<typeof import('../../src/api/overview')>('../../src/api/overview');
    mockedGetOverview.mockResolvedValueOnce({
      tasks: { total: null, queued: null, running: null, succeeded: null, failed: null },
      workers: { ready: null, connecting: null, unhealthy: null, draining: null },
      teams: { total: null, active: null },
      recentTasks: [],
      alerts: [],
      errors: { resources: { status: 503, message: 'resource aggregation unavailable' } },
      metricsUnavailable: true,
    });
    render(
      <MemoryRouter>
        <QueryClientProvider client={new QueryClient()}>
          <OverviewPage projectId="p-1" />
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(await screen.findAllByText('后端尚未提供聚合统计')).toHaveLength(4);
    expect(screen.getAllByRole('alert')).toHaveLength(4);
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
        if (path === '/api/v1/dashboard/resources') {
          return Promise.reject({ status: 503, message: 'resource aggregation unavailable' });
        }
        if (path === '/api/v1/teams/page') return Promise.resolve({ items: [{ id: 'team-1' }] });
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
    expect(overview.errors?.resources).toMatchObject({
      status: 503,
      message: 'resource aggregation unavailable',
    });
    expect(overview.recentTasks).toHaveLength(1);
  });

  it('uses project resource aggregates instead of paginated first-page counts', async () => {
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
        if (path === '/api/v1/dashboard/resources') {
          return Promise.resolve({
            tasks: { total: 24, queued: 4, running: 3, succeeded: 15, failed: 2 },
            workers: { ready: 5, connecting: 1, unhealthy: 0, draining: 1 },
            teams: { total: 3, active: 2 },
          });
        }
        if (path === '/api/v1/teams/page') return Promise.resolve({ items: [{ id: 'team-1' }] });
        if (path === '/api/v1/agents') return Promise.resolve({ items: [{ id: 'worker-1' }] });
        return Promise.resolve({ items: [{ id: 'task-1', phase: 'RUNNING' }] });
      }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    const { getOverview: realGetOverview } =
      await vi.importActual<typeof import('../../src/api/overview')>('../../src/api/overview');
    const overview = await realGetOverview('p-1', client as never);

    expect(overview.tasks).toEqual({ total: 24, queued: 4, running: 3, succeeded: 15, failed: 2 });
    expect(overview.workers).toEqual({ ready: 5, connecting: 1, unhealthy: 0, draining: 1 });
    expect(overview.teams).toEqual({ total: 3, active: 2 });
  });

  it('keeps resource cards unavailable and exposes the resource error when aggregation fails', async () => {
    const client = {
      request: vi.fn((path: string) => {
        if (path === '/api/v1/dashboard/resources') {
          return Promise.reject({ status: 503, message: 'resource aggregation unavailable' });
        }
        if (path === '/api/v1/dashboard/alerts') return Promise.resolve([]);
        return Promise.resolve({ items: [] });
      }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    const { getOverview: realGetOverview } =
      await vi.importActual<typeof import('../../src/api/overview')>('../../src/api/overview');
    const overview = await realGetOverview('p-1', client as never);

    expect(overview.metricsUnavailable).toBe(true);
    expect(overview.errors?.resources).toMatchObject({
      status: 503,
      message: 'resource aggregation unavailable',
    });
    expect(overview.tasks.total).toBeNull();
    expect(overview.workers.ready).toBeNull();
    expect(overview.teams.active).toBeNull();
  });

  it('fails closed instead of rendering partial data when dashboard scope is forbidden', async () => {
    const client = {
      request: vi.fn((path: string) => {
        if (path === '/api/v1/dashboard/summary') return Promise.reject({ status: 403 });
        return Promise.resolve({ items: [] });
      }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };
    const { getOverview: realGetOverview } =
      await vi.importActual<typeof import('../../src/api/overview')>('../../src/api/overview');

    await expect(realGetOverview('project-b', client as never)).rejects.toMatchObject({
      status: 403,
    });
  });
});
