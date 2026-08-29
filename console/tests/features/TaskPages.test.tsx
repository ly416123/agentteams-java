import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { TaskPage } from '../../src/features/tasks/TaskPage';
import { TaskDetailPage } from '../../src/features/tasks/TaskDetailPage';

vi.mock('../../src/api/tasks', () => ({
  listTasks: vi.fn().mockResolvedValue([
    {
      id: 'task-1',
      title: '生成周报',
      description: '汇总本周运行数据',
      phase: 'RUNNING',
      priority: 2,
      createdAt: '2026-08-29T01:00:00Z',
      updatedAt: '2026-08-29T02:00:00Z',
      version: 4,
      teamId: 'team-1',
      workerId: 'worker-1',
    },
  ]),
  getTask: vi.fn().mockResolvedValue({
    id: 'task-1',
    title: '生成周报',
    description: '汇总本周运行数据',
    phase: 'RUNNING',
    priority: 2,
    createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 4,
    teamId: 'team-1',
    workerId: 'worker-1',
  }),
  listTaskEvents: vi
    .fn()
    .mockResolvedValue([
      { id: 'e-1', type: 'task.created', message: '任务已创建', createdAt: '2026-08-29T01:00:00Z' },
    ]),
  taskAction: vi.fn().mockResolvedValue({
    id: 'task-1',
    title: '生成周报',
    description: '汇总本周运行数据',
    phase: 'CANCELLED',
    priority: 2,
    createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 5,
    teamId: 'team-1',
    workerId: 'worker-1',
  }),
}));

function renderWithQuery(ui: React.ReactNode) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Task pages', () => {
  it('switches between board and table views with filters', async () => {
    renderWithQuery(<TaskPage projectId="p-1" />);
    expect(await screen.findByText('生成周报')).toBeInTheDocument();
    expect(screen.getAllByText('执行中').length).toBeGreaterThan(0);
    await userEvent.click(screen.getByRole('button', { name: '列表视图' }));
    expect(screen.getByRole('table')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('搜索任务')).toBeInTheDocument();
  });

  it('shows task timeline and versioned lifecycle actions', async () => {
    renderWithQuery(<TaskDetailPage projectId="p-1" taskId="task-1" />);
    expect(await screen.findByText('生成周报')).toBeInTheDocument();
    expect(screen.getByText('任务已创建')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消任务' })).toBeInTheDocument();
    expect(screen.getByText('版本 4')).toBeInTheDocument();
  });
});
