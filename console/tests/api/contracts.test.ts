import { describe, expect, it, vi } from 'vitest';
import { getDashboardSummary } from '../../src/api/overview';
import { listProjects } from '../../src/api/projects';
import { listTasks } from '../../src/api/tasks';

describe('API contracts', () => {
  it('requests the DashboardSummaryController endpoint', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ calls: 4 }),
      requestText: vi.fn(),
    };

    await getDashboardSummary(client);

    expect(client.request).toHaveBeenCalledWith('/api/v1/dashboard/summary');
  });

  it('normalizes the Project cursor page before the UI consumes it', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({
        items: [{ id: 'p-1', name: '平台工程' }],
        nextCursor: 'cursor-2',
      }),
      requestText: vi.fn(),
    };

    const page = await listProjects(client);

    expect(page.items).toHaveLength(1);
    expect(page.nextCursor).toBe('cursor-2');
  });

  it('uses q for Task search to match the server query contract', async () => {
    const client = { request: vi.fn().mockResolvedValue({ items: [] }), requestText: vi.fn() };

    await listTasks('p-1', { q: 'weekly report' }, client);

    expect(client.request).toHaveBeenCalledWith('/api/v1/tasks', {
      query: { projectId: 'p-1', q: 'weekly report' },
    });
  });
});
