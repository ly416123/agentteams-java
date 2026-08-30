import { describe, expect, it, vi } from 'vitest';
import { getDashboardSummary } from '../../src/api/overview';
import { listProjects } from '../../src/api/projects';
import { createTask, listTasks } from '../../src/api/tasks';
import { getDeployment, listDeployments, listTeams } from '../../src/api/teams';
import { listOperations, listWorkers } from '../../src/api/workers';

describe('API contracts', () => {
  it('requests the DashboardSummaryController endpoint', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ calls: 4 }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
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
      requestStream: vi.fn(),
    };

    const page = await listProjects(client);

    expect(page.items).toHaveLength(1);
    expect(page.nextCursor).toBe('cursor-2');
  });

  it('uses projectId and q for Task search to preserve Project scope', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ items: [] }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await listTasks('p-1', { q: 'weekly report' }, client);

    expect(client.request).toHaveBeenCalledWith('/api/v1/tasks', {
      query: { projectId: 'p-1', q: 'weekly report' },
    });
  });

  it('uses projectId with Worker filters to preserve Project scope', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ items: [] }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await listWorkers('p-1', { search: 'analysis', phase: 'READY', cursor: 'cursor-1' }, client);

    expect(client.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: {
        projectId: 'p-1',
        q: 'analysis',
        status: 'READY',
        cursor: 'cursor-1',
      },
    });
  });

  it('places task scope and scheduling references inside spec without a top-level projectId', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ id: 'task-1' }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await createTask(
      {
        title: '生成周报',
        description: '汇总数据',
        spec: {
          scope: { tenant: 'tenant-a', project: 'project-a', team: 'platform' },
          teamId: 'team-1',
          workerId: 'worker-1',
        },
      },
      client,
    );

    expect(client.request).toHaveBeenCalledWith('/api/v1/tasks', {
      method: 'POST',
      body: {
        title: '生成周报',
        description: '汇总数据',
        spec: {
          scope: { tenant: 'tenant-a', project: 'project-a', team: 'platform' },
          teamId: 'team-1',
          workerId: 'worker-1',
        },
      },
    });
    expect(client.request.mock.calls[0][1].body).not.toHaveProperty('projectId');
  });

  it('rejects task creation with an empty spec before making a request', async () => {
    const client = {
      request: vi.fn(),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await expect(
      createTask({ title: '无作用域任务', description: '', spec: {} as never }, client),
    ).rejects.toThrow('spec.scope');
    expect(client.request).not.toHaveBeenCalled();
  });

  it('parses worker operations as a cursor page and forwards the cursor', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({
        items: [{ id: 'op-1' }],
        nextCursor: 'cursor-2',
        hasMore: true,
      }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    const page = await listOperations('worker-1', { cursor: 'cursor-1' }, client);

    expect(page.items).toEqual([{ id: 'op-1' }]);
    expect(page.nextCursor).toBe('cursor-2');
    expect(client.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations', {
      query: { cursor: 'cursor-1' },
    });
  });

  it('uses the management API Team cursor endpoint and q filter', async () => {
    const client = {
      request: vi.fn().mockResolvedValue({ items: [], nextCursor: null, hasMore: false }),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await listTeams('p-1', { search: 'platform' }, client);

    expect(client.request).toHaveBeenCalledWith('/api/v1/teams/page', {
      query: { q: 'platform' },
    });
  });

  it('requests real Team deployment list and detail endpoints', async () => {
    const client = {
      request: vi.fn().mockResolvedValue([]),
      requestText: vi.fn(),
      requestStream: vi.fn(),
    };

    await listDeployments('team-1', client);
    await getDeployment('team-1', 'deployment-1', client);

    expect(client.request).toHaveBeenNthCalledWith(1, '/api/v1/teams/team-1/deployments');
    expect(client.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/teams/team-1/deployments/deployment-1',
    );
  });
});
