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
});
