import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ApiError } from '../../src/api/httpClient';
import { getTask, streamTaskEvents, taskAction } from '../../src/api/tasks';
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
  streamTaskEvents: vi.fn().mockImplementation(async (_taskId, options) => {
    options.onEvents([
      {
        id: 'e-1',
        cursor: 'e-1',
        type: 'task.created',
        message: '任务已创建',
        createdAt: '2026-08-29T01:00:00Z',
      },
    ]);
  }),
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

  it('offers every domain TaskPhase in the status filter', async () => {
    renderWithQuery(<TaskPage projectId="p-1" />);
    const select = await screen.findByRole('combobox', { name: '任务状态' });
    expect(Array.from(select.querySelectorAll('option')).map((option) => option.value)).toEqual([
      '',
      'DRAFT',
      'QUEUED',
      'PAUSED',
      'ASSIGNED',
      'ACCEPTED',
      'RUNNING',
      'SUCCEEDED',
      'FAILED',
      'CANCELLED',
      'REJECTED',
    ]);
  });

  it('shows task timeline and versioned lifecycle actions', async () => {
    renderWithQuery(<TaskDetailPage projectId="p-1" taskId="task-1" />);
    expect(await screen.findByText('生成周报')).toBeInTheDocument();
    expect(screen.getByText('任务已创建')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '取消任务' })).toBeInTheDocument();
    expect(screen.getByText('版本 4')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '取消任务' }));
    expect(screen.getByRole('dialog')).toHaveTextContent('将停止任务执行');
    expect(screen.getByRole('button', { name: '确认取消任务' })).toBeInTheDocument();
  });

  it('refetches the latest task before retrying a conflicted action', async () => {
    vi.mocked(getTask)
      .mockImplementationOnce(async () => ({
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
      }))
      .mockImplementationOnce(async () => ({
        id: 'task-1',
        title: '生成周报',
        description: '汇总本周运行数据',
        phase: 'RUNNING',
        priority: 2,
        createdAt: '2026-08-29T01:00:00Z',
        updatedAt: '2026-08-29T03:00:00Z',
        version: 7,
        teamId: 'team-1',
        workerId: 'worker-1',
      }));
    vi.mocked(taskAction)
      .mockRejectedValueOnce(new ApiError(409, { message: 'version changed' }))
      .mockResolvedValueOnce({
        id: 'task-1',
        title: '生成周报',
        description: '汇总本周运行数据',
        phase: 'CANCELLED',
        priority: 2,
        createdAt: '2026-08-29T01:00:00Z',
        updatedAt: '2026-08-29T03:00:00Z',
        version: 8,
        teamId: 'team-1',
        workerId: 'worker-1',
      });

    renderWithQuery(<TaskDetailPage projectId="p-1" taskId="task-1" />);
    await screen.findByText('生成周报');
    await userEvent.click(screen.getByRole('button', { name: '取消任务' }));
    await userEvent.click(screen.getByRole('button', { name: '确认取消任务' }));
    await screen.findByText('资源状态已更新');
    await userEvent.click(screen.getByRole('button', { name: '仍然取消任务' }));

    expect(taskAction).toHaveBeenLastCalledWith('task-1', 'cancel', 7);
  });

  it('reconnects task events from the last cursor and merges duplicate deliveries', async () => {
    const stream = vi.mocked(streamTaskEvents);
    stream.mockReset();
    stream
      .mockImplementationOnce(async (_taskId, options) => {
        options.onEvents([
          { id: 'cursor-1', cursor: 'cursor-1', type: 'task.updated', message: '已排队' },
        ]);
      })
      .mockImplementationOnce(async (_taskId, options) => {
        expect(options.after).toBe('cursor-1');
        options.onEvents([
          { id: 'cursor-1', cursor: 'cursor-1', type: 'task.updated', message: '已排队' },
          { id: 'cursor-2', cursor: 'cursor-2', type: 'task.succeeded', message: '已完成' },
        ]);
      });

    renderWithQuery(<TaskDetailPage projectId="p-1" taskId="task-1" />);
    expect(await screen.findByText('已排队')).toBeInTheDocument();
    await waitFor(() => expect(stream).toHaveBeenCalledTimes(2), { timeout: 1000 });
    expect(screen.getByText('已完成')).toBeInTheDocument();
    expect(screen.getAllByText('已排队')).toHaveLength(1);
  });

  it('shows a visible reconnecting state after the task event stream disconnects', async () => {
    const stream = vi.mocked(streamTaskEvents);
    stream.mockReset();
    stream
      .mockImplementationOnce(async (_taskId, options) => {
        options.onEvents([
          { id: 'cursor-1', cursor: 'cursor-1', type: 'task.updated', message: '已排队' },
        ]);
      })
      .mockRejectedValueOnce(new Error('stream disconnected'));

    renderWithQuery(<TaskDetailPage projectId="p-1" taskId="task-1" />);
    expect(await screen.findByText('已排队')).toBeInTheDocument();
    await waitFor(() => expect(screen.getByText('事件流已断开，正在重连')).toBeInTheDocument(), {
      timeout: 1000,
    });
    expect(screen.getByRole('button', { name: '手动重连' })).toBeInTheDocument();
  });
});
