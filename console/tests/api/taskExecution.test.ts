import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  getTaskDecisions,
  getTaskProcessEvents,
  getTaskProgress,
  getTaskResult,
  getTaskTree,
  streamTaskProcessEvents,
} from '../../src/api/tasks';

describe('task execution observability api', () => {
  it('reads the complete requester-visible execution projection', async () => {
    const client = {
      request: vi.fn().mockResolvedValue([]),
    } as unknown as HttpClient;

    await getTaskProcessEvents('task-1', 'run-1', 7, client);
    await getTaskProgress('task-1', 'run-1', client);
    await getTaskTree('task-1', 'run-1', client);
    await getTaskDecisions('task-1', 'run-1', client);
    await getTaskResult('task-1', 'run-1', client);

    expect(client.request).toHaveBeenNthCalledWith(
      1,
      '/api/v1/tasks/task-1/runs/run-1/process-events',
      { query: { after: 7, visibility: 'REQUESTER' } },
    );
    expect(client.request).toHaveBeenNthCalledWith(
      2,
      '/api/v1/tasks/task-1/runs/run-1/progress',
      { query: { phase: 'EXECUTION' } },
    );
    expect(client.request).toHaveBeenNthCalledWith(
      3,
      '/api/v1/tasks/task-1/runs/run-1/tree',
    );
    expect(client.request).toHaveBeenNthCalledWith(
      4,
      '/api/v1/tasks/task-1/runs/run-1/decisions',
      { query: { visibility: 'REQUESTER' } },
    );
    expect(client.request).toHaveBeenNthCalledWith(
      5,
      '/api/v1/tasks/task-1/runs/run-1/result',
      { query: { visibility: 'REQUESTER' } },
    );
  });

  it('parses process-event SSE envelopes and resumes from the last sequence', async () => {
    const received: number[] = [];
    const reader = {
      reads: [
        {
          value: new TextEncoder().encode(
            'id: 8\nevent: PROGRESS\ndata: {"eventId":"event-8","taskId":"task-1","runId":"run-1","sequence":8,"eventType":"PROGRESS","visibility":"REQUESTER","occurredAt":"2026-09-07T00:00:00Z","correlationId":"corr-1","payload":"{\\"progress\\":50}"}\n\n',
          ),
          done: false,
        },
        { value: undefined, done: true },
      ],
      read() {
        return Promise.resolve(this.reads.shift() || { value: undefined, done: true });
      },
      releaseLock() {},
    };
    const client = {
      requestStream: vi.fn().mockResolvedValue({ body: { getReader: () => reader } }),
    } as unknown as HttpClient;

    await streamTaskProcessEvents('task-1', 'run-1', {
      after: 7,
      client,
      onEvents: (events) => received.push(...events.map((event) => event.sequence)),
    });

    expect(client.requestStream).toHaveBeenCalledWith(
      '/api/v1/tasks/task-1/runs/run-1/process-events/stream',
      expect.objectContaining({
        query: { visibility: 'REQUESTER' },
        headers: { Accept: 'text/event-stream', 'Last-Event-ID': '7' },
      }),
    );
    expect(received).toEqual([8]);
  });
});
