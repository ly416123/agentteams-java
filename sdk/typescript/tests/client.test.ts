import { describe, expect, it, vi } from 'vitest';
import { AgentTeamsApiError, AgentTeamsClient } from '../src/client';

function jsonResponse(status: number, body: unknown, headers?: Record<string, string>) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

describe('AgentTeamsClient', () => {
  it('sends bearer authentication and an idempotency key for writes', async () => {
    const fetcher = vi.fn().mockResolvedValue(jsonResponse(201, { id: 'project-1' }));
    const client = new AgentTeamsClient({
      baseUrl: 'https://agentteams.example',
      accessToken: 'token-1',
      fetcher,
    });

    await client.createProject({ name: 'Demo' });

    const request = fetcher.mock.calls[0][0] as Request;
    expect(request.url).toBe('https://agentteams.example/api/v1/projects');
    expect(request.headers.get('Authorization')).toBe('Bearer token-1');
    expect(request.headers.get('Idempotency-Key')).toMatch(/^sdk-/);
  });

  it('retries a transient GET but does not retry a write by default', async () => {
    const getFetcher = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(503, { code: 'TEMPORARY', message: 'retry' }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [], hasMore: false, serverTime: '2026-08-31T00:00:00Z' }));
    const getClient = new AgentTeamsClient({ fetcher: getFetcher, retryDelayMs: 0 });
    await expect(getClient.listProjects()).resolves.toMatchObject({ items: [] });
    expect(getFetcher).toHaveBeenCalledTimes(2);

    const postFetcher = vi.fn().mockResolvedValue(jsonResponse(503, { code: 'TEMPORARY', message: 'retry' }));
    const postClient = new AgentTeamsClient({ fetcher: postFetcher, retryDelayMs: 0 });
    await expect(postClient.createProject({ name: 'Demo' })).rejects.toBeInstanceOf(AgentTeamsApiError);
    expect(postFetcher).toHaveBeenCalledTimes(1);
  });

  it('only retries a write when the caller opts into safe idempotency', async () => {
    const fetcher = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(503, { code: 'TEMPORARY', message: 'retry' }))
      .mockResolvedValueOnce(jsonResponse(200, { id: 'task-1' }));
    const client = new AgentTeamsClient({ fetcher, retryDelayMs: 0 });

    await expect(client.cancelTask('task-1', { expectedVersion: 3 }, { retrySafe: true })).resolves.toEqual({
      id: 'task-1',
    });
    expect(fetcher).toHaveBeenCalledTimes(2);
  });

  it('maps structured API errors without exposing response bodies in the error message', async () => {
    const fetcher = vi.fn().mockResolvedValue(
      jsonResponse(409, {
        code: 'VERSION_CONFLICT',
        message: '资源版本冲突',
        correlationId: 'corr-1',
        details: { expectedVersion: 3 },
      }),
    );
    const client = new AgentTeamsClient({ fetcher });

    await expect(client.cancelTask('task-1', { expectedVersion: 3 })).rejects.toMatchObject({
      status: 409,
      code: 'VERSION_CONFLICT',
      correlationId: 'corr-1',
    });
  });

  it('reads task progress, result manifest and replayable process events', async () => {
    const fetcher = vi.fn()
      .mockResolvedValueOnce(jsonResponse(200, { phase: 'EXECUTION', completed: 2, total: 4, progress: 50, waitingReason: '' }))
      .mockResolvedValueOnce(jsonResponse(200, { taskId: 'task-1', runId: 'run-1', status: 'SUCCEEDED', summary: 'done', artifacts: [] }))
      .mockResolvedValueOnce(jsonResponse(200, [{ eventId: 'event-1', taskId: 'task-1', runId: 'run-1', sequence: 1,
        eventType: 'PROGRESS', visibility: 'REQUESTER', occurredAt: '2026-08-31T00:00:00Z', correlationId: 'corr-1',
        payload: '{"progress":50}', payloadRef: null }]));
    const client = new AgentTeamsClient({ baseUrl: 'https://agentteams.example', fetcher });

    await expect(client.getTaskProgress('task-1', 'run-1')).resolves.toMatchObject({ progress: 50 });
    await expect(client.getTaskResult('task-1', 'run-1')).resolves.toMatchObject({ status: 'SUCCEEDED' });
    await expect(client.listTaskProcessEvents('task-1', 'run-1', { after: 0 })).resolves.toHaveLength(1);

    expect((fetcher.mock.calls[2][0] as Request).url)
      .toBe('https://agentteams.example/api/v1/tasks/task-1/runs/run-1/process-events?after=0');
  });
});
