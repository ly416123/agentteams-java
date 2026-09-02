import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementUsagePage } from '../../src/features/management/ManagementUsagePage';

vi.mock('../../src/api/usage', () => ({
  getUsageSummary: vi.fn().mockResolvedValue({
    from: '2026-08-31T00:00:00Z',
    to: '2026-09-01T00:00:00Z',
    calls: 12,
    failures: 1,
    promptTokens: 1200,
    completionTokens: 800,
    costUsd: 0.42,
    averageLatencyMillis: 320,
    byProviderModel: [
      {
        provider: 'local',
        model: 'qwen',
        calls: 12,
        failures: 1,
        promptTokens: 1200,
        completionTokens: 800,
        costUsd: 0.42,
        averageLatencyMillis: 320,
      },
    ],
    offset: 0,
    limit: 20,
    nextOffset: 20,
  }),
  listUsageBudgets: vi.fn().mockResolvedValue([
    {
      id: 'budget-1',
      currency: 'USD',
      periodSeconds: 86400,
      softThreshold: 1,
      hardThreshold: 2,
      forecastWindowSeconds: 3600,
      status: 'ACTIVE',
      version: 2,
    },
  ]),
  exportUsageCsv: vi.fn().mockResolvedValue('provider,model,calls\nlocal,qwen,12\n'),
}));

describe('Management usage page', () => {
  it('renders scoped usage, pricing caveat, model breakdown and budget status', async () => {
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementUsagePage projectId="project-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Usage 与费用' })).toBeInTheDocument();
    expect((await screen.findAllByText('12')).length).toBeGreaterThan(0);
    expect(screen.getAllByText('$0.4200').length).toBeGreaterThan(0);
    expect(screen.getAllByText(/估算成本/).length).toBeGreaterThan(0);
    expect(screen.getByText('local / qwen')).toBeInTheDocument();
    expect(screen.getByText('预算策略')).toBeInTheDocument();
    expect(screen.getByText('USD · ACTIVE')).toBeInTheDocument();

    const { getUsageSummary, listUsageBudgets } = await import('../../src/api/usage');
    expect(getUsageSummary).toHaveBeenCalledWith('project-1', {}, { offset: 0, limit: 20 });
    expect(listUsageBudgets).toHaveBeenCalledWith('project-1');
  });

  it('applies operational filters and requests a scoped CSV export', async () => {
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementUsagePage projectId="project-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.change(await screen.findByLabelText('Task ID'), { target: { value: 'task-1' } });
    fireEvent.change(screen.getByLabelText('Provider'), { target: { value: 'deepseek' } });
    fireEvent.change(screen.getByLabelText('Model'), { target: { value: 'deepseek-chat' } });
    fireEvent.click(screen.getByRole('button', { name: '应用筛选' }));

    const { getUsageSummary, exportUsageCsv } = await import('../../src/api/usage');
    await waitFor(() =>
      expect(getUsageSummary).toHaveBeenCalledWith(
        'project-1',
        {
          taskId: 'task-1',
          provider: 'deepseek',
          model: 'deepseek-chat',
        },
        { offset: 0, limit: 20 },
      ),
    );

    fireEvent.click(screen.getByRole('button', { name: '导出 CSV' }));
    await waitFor(() =>
      expect(exportUsageCsv).toHaveBeenCalledWith('project-1', {
        taskId: 'task-1',
        provider: 'deepseek',
        model: 'deepseek-chat',
      }),
    );
  });

  it('moves between usage pages using the server-provided next offset', async () => {
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementUsagePage projectId="project-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '下一页' }));

    const { getUsageSummary } = await import('../../src/api/usage');
    await waitFor(() =>
      expect(getUsageSummary).toHaveBeenCalledWith('project-1', {}, { offset: 20, limit: 20 }),
    );
    expect(screen.getByText('第 2 页')).toBeInTheDocument();
  });

  it('requests an operational aggregation dimension from the management API', async () => {
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementUsagePage projectId="project-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(screen.getByRole('option', { name: 'User' })).toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText('分组维度'), { target: { value: 'team' } });

    const { getUsageSummary } = await import('../../src/api/usage');
    await waitFor(() =>
      expect(getUsageSummary).toHaveBeenCalledWith(
        'project-1',
        {},
        { offset: 0, limit: 20, groupBy: 'team' },
      ),
    );
  });

  it('explains when the independent export permission is missing', async () => {
    const { exportUsageCsv } = await import('../../src/api/usage');
    vi.mocked(exportUsageCsv).mockRejectedValueOnce({ status: 403 });
    render(
      <QueryClientProvider
        client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
      >
        <MemoryRouter>
          <ManagementUsagePage projectId="project-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    fireEvent.click(await screen.findByRole('button', { name: '导出 CSV' }));
    expect(
      await screen.findByText('当前账号没有 usage:export 权限，无法导出。'),
    ).toBeInTheDocument();
  });
});
