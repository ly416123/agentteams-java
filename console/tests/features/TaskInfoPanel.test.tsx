import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { TaskInfoPanel } from '../../src/features/tasks/TaskInfoPanel';
import type { TaskProcessEvent } from '../../src/api/types';

vi.mock('../../src/queries/useTaskQueries', () => ({
  useTaskTree: () => ({
    data: [
      { taskId: 'root', parentTaskId: null, sequence: 0, status: 'RUNNING', dependencyIds: [], updatedAt: '2026-09-08T00:00:00Z' },
      { taskId: 'child-a', parentTaskId: 'root', sequence: 1, status: 'PENDING', dependencyIds: [], updatedAt: '2026-09-08T00:01:00Z' },
    ],
  }),
  useTaskDecisions: () => ({
    data: [{ id: 'd1', selectedAction: 'create_task', goalSummary: '创建任务', createdAt: '2026-09-08T00:00:00Z' }],
  }),
  useTaskResult: () => ({
    data: { status: 'SUCCEEDED', summary: '完成', artifacts: [{ name: 'output.md', storageRef: 'tasks/x', contentType: 'text/markdown', sizeBytes: 12, sha256: 'ab' }] },
  }),
}));

function renderPanel(processEvents: TaskProcessEvent[]) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <MemoryRouter>
      <QueryClientProvider client={client}>
        <TaskInfoPanel
          projectId="p1"
          taskId="root"
          runId="r1"
          task={{ id: 'root', title: 'T', phase: 'RUNNING', priority: 5, source: { conversationId: 'conv-1' } } as never}
          processEvents={processEvents}
        />
      </QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('TaskInfoPanel', () => {
  it('renders dag nodes for the current run', () => {
    renderPanel([]);
    expect(screen.getByTestId('task-dag')).toBeInTheDocument();
    expect(screen.getAllByTestId('task-dag-node').length).toBeGreaterThanOrEqual(2);
  });

  it('renders process events with semantic labels on the events tab', () => {
    renderPanel([{
      eventId: 'e1', taskId: 'root', runId: 'r1', sequence: 1, eventType: 'tool.called',
      visibility: 'REQUESTER', occurredAt: '2026-09-08T00:02:00Z', correlationId: 'c1',
      payload: '{"tool":"web_search"}',
    }]);
    expect(screen.getByText('调用工具')).toBeInTheDocument();
    expect(screen.getByText(/web_search/)).toBeInTheDocument();
  });

  it('links back to the source conversation on the details tab', async () => {
    const user = userEvent.setup();
    renderPanel([]);
    await user.click(screen.getByRole('tab', { name: '详情' }));
    const backlink = screen.getByTestId('source-backlink');
    expect(within(backlink).getByRole('link', { name: /打开来源会话/ })).toHaveAttribute(
      'href',
      expect.stringContaining('conv-1'),
    );
  });

  it('switches to artifacts tab showing manifest entries', async () => {
    const user = userEvent.setup();
    renderPanel([]);
    await user.click(screen.getByRole('tab', { name: '成果物' }));
    expect(screen.getByText('output.md')).toBeInTheDocument();
  });
});
