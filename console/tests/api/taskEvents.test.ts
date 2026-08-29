import { describe, expect, it, vi } from 'vitest';
import {
  createTaskEventParser,
  parseTaskEventStream,
  streamTaskEvents,
  taskEventReconnectDelay,
} from '../../src/api/taskEvents';
import type { HttpClient } from '../../src/api/httpClient';

describe('task event parser', () => {
  it('parses SSE data frames without relying on response.json', () => {
    const events = parseTaskEventStream(
      'id: e-1\nevent: task.updated\ndata: {"message":"已排队"}\n\n',
    );

    expect(events).toEqual([
      {
        id: 'e-1',
        cursor: 'e-1',
        type: 'task.updated',
        message: '已排队',
      },
    ]);
  });

  it('also accepts the JSON history representation', () => {
    expect(
      parseTaskEventStream(
        JSON.stringify([{ id: 'e-2', type: 'task.created', message: '已创建' }]),
      ),
    ).toEqual([{ id: 'e-2', type: 'task.created', message: '已创建' }]);
  });

  it('parses SSE incrementally across chunks and keeps the event cursor', () => {
    const parser = createTaskEventParser();

    expect(parser.push('id: cursor-1\nevent: task.updated\ndata: {"message":"已')).toEqual([]);
    expect(parser.push('排队","cursor":"cursor-1"}\n\n')).toEqual([
      {
        id: 'cursor-1',
        type: 'task.updated',
        message: '已排队',
        cursor: 'cursor-1',
      },
    ]);
  });

  it('opens the next stream with after and Last-Event-ID and emits chunks incrementally', async () => {
    const received: string[] = [];
    const reader = {
      reads: [
        {
          value: new TextEncoder().encode('id: cursor-2\ndata: {"message":"完成"}\n\n'),
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

    await streamTaskEvents('task-1', {
      after: 'cursor-1',
      client,
      onEvents: (events) => received.push(...events.map((event) => event.id)),
    });

    expect(client.requestStream).toHaveBeenCalledWith(
      '/api/v1/tasks/task-1/events',
      expect.objectContaining({
        query: { after: 'cursor-1' },
        headers: { Accept: 'text/event-stream', 'Last-Event-ID': 'cursor-1' },
      }),
    );
    expect(received).toEqual(['cursor-2']);
  });

  it('uses bounded exponential reconnect delays', () => {
    expect([0, 1, 2, 8].map(taskEventReconnectDelay)).toEqual([250, 500, 1000, 30_000]);
    expect(taskEventReconnectDelay(20)).toBe(30_000);
  });
});
