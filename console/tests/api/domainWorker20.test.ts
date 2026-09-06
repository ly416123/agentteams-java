import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  getWorker,
  listOperations,
  listWorkers,
  rollbackWorker,
  rolloutWorker,
  workerAction,
} from '../../src/api/workers';

function client(response: unknown = { items: [], hasMore: false }) {
  return {
    request: vi.fn().mockResolvedValue(response),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('worker domain API contracts: 20 cases', () => {
  it('lists workers with default filters', async () => {
    const http = client();
    await listWorkers('project-1', {}, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: { projectId: 'project-1', q: undefined, status: undefined, cursor: undefined },
    });
  });

  it('filters workers by search text', async () => {
    const http = client();
    await listWorkers('project-1', { search: '分析' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: { projectId: 'project-1', q: '分析', status: undefined, cursor: undefined },
    });
  });

  it('filters workers by lifecycle phase', async () => {
    const http = client();
    await listWorkers('project-1', { phase: 'READY' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: { projectId: 'project-1', q: undefined, status: 'READY', cursor: undefined },
    });
  });

  it('filters workers by runtime', async () => {
    const http = client();
    await listWorkers('project-1', { runtime: 'QWENPAW' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: {
        projectId: 'project-1',
        q: undefined,
        status: undefined,
        runtime: 'QWENPAW',
        cursor: undefined,
      },
    });
  });

  it('combines search, phase and cursor filters', async () => {
    const http = client();
    await listWorkers(
      'project-1',
      { search: 'worker', phase: 'BUSY', runtime: 'OPENAI', cursor: 'cursor-2' },
      http,
    );
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents', {
      query: {
        projectId: 'project-1',
        q: 'worker',
        status: 'BUSY',
        runtime: 'OPENAI',
        cursor: 'cursor-2',
      },
    });
  });

  it('gets worker detail within a project', async () => {
    const http = client();
    await getWorker('project-1', 'worker-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1', {
      query: { projectId: 'project-1' },
    });
  });

  it('lists worker operations without a cursor', async () => {
    const http = client();
    await listOperations('project-1', 'worker-1', {}, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations', {
      query: { projectId: 'project-1' },
    });
  });

  it('lists worker operations from a cursor', async () => {
    const http = client();
    await listOperations('project-1', 'worker-1', { cursor: 'op-cursor' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations', {
      query: { projectId: 'project-1', cursor: 'op-cursor' },
    });
  });

  it('requests a drain operation', async () => {
    const http = client();
    await workerAction('project-1', 'worker-1', 'drain', 5, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations/drain', {
      method: 'POST',
      body: { expectedVersion: 5 },
      query: { projectId: 'project-1' },
    });
  });

  it('requests a terminate operation', async () => {
    const http = client();
    await workerAction('project-1', 'worker-1', 'terminate', 6, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations/terminate', {
      method: 'POST',
      body: { expectedVersion: 6 },
      query: { projectId: 'project-1' },
    });
  });

  it('allows zero as an operation expected version', async () => {
    const http = client();
    await workerAction('project-1', 'worker-1', 'drain', 0, http);
    expect(http.request.mock.calls[0][1].body).toEqual({ expectedVersion: 0 });
  });

  it('rolls out a worker with stable specification fields', async () => {
    const http = client();
    const body = {
      expectedVersion: 2,
      imageDigest: 'sha256:image',
      runtime: 'QWENPAW',
      configRevision: 'cfg-2',
      secretGeneration: 'secret-3',
      previousStableSpec: '{"runtime":"QWENPAW"}',
    };
    await rolloutWorker('project-1', 'worker-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations/rollout', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('rolls out a worker with a new image digest', async () => {
    const http = client();
    const body = {
      expectedVersion: 9,
      imageDigest: 'sha256:new',
      runtime: 'OPENAI',
      configRevision: 'cfg-9',
      secretGeneration: 'secret-9',
      previousStableSpec: '{}',
    };
    await rolloutWorker('project-1', 'worker-9', body, http);
    expect(http.request.mock.calls[0][0]).toBe('/api/v1/agents/worker-9/operations/rollout');
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('rolls back a worker operation', async () => {
    const http = client();
    await rollbackWorker('project-1', 'worker-1', 'op-1', 7, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/agents/worker-1/operations/op-1/rollback', {
      method: 'POST',
      body: { expectedVersion: 7 },
      query: { projectId: 'project-1' },
    });
  });

  it('normalizes an array worker response', async () => {
    const http = client([{ id: 'worker-1' }]);
    const result = await listWorkers('project-1', {}, http);
    expect(result.items).toEqual([{ id: 'worker-1' }]);
    expect(result.hasMore).toBe(false);
  });

  it('preserves a worker page next cursor', async () => {
    const http = client({ items: [{ id: 'worker-1' }], nextCursor: 'next', hasMore: true });
    const result = await listWorkers('project-1', {}, http);
    expect(result.nextCursor).toBe('next');
    expect(result.hasMore).toBe(true);
  });

  it('normalizes an array operations response', async () => {
    const http = client([{ id: 'operation-1' }]);
    const result = await listOperations('project-1', 'worker-1', {}, http);
    expect(result.items).toEqual([{ id: 'operation-1' }]);
  });

  it('preserves an operations page cursor', async () => {
    const http = client({ items: [{ id: 'operation-1' }], nextCursor: 'next-op', hasMore: true });
    const result = await listOperations('project-1', 'worker-1', {}, http);
    expect(result.nextCursor).toBe('next-op');
    expect(result.hasMore).toBe(true);
  });

  it('keeps project scope on rollout requests', async () => {
    const http = client();
    const body = {
      expectedVersion: 1,
      imageDigest: 'sha256:x',
      runtime: 'QWENPAW',
      configRevision: '1',
      secretGeneration: '1',
      previousStableSpec: '{}',
    };
    await rolloutWorker('project-a', 'worker-a', body, http);
    expect(http.request.mock.calls[0][1].query).toEqual({ projectId: 'project-a' });
  });

  it('uses the operation id in rollback path', async () => {
    const http = client();
    await rollbackWorker('project-1', 'worker-1', 'rollback-op-42', 4, http);
    expect(http.request.mock.calls[0][0]).toContain('/operations/rollback-op-42/rollback');
  });
});
