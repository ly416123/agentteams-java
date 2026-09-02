import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  conversationReconnectDelay,
  conversationEventText,
  createConversationEventParser,
  streamConversationEvents,
} from '../../src/streams/conversationEvents';

function response(body: string) {
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

describe('conversation SSE client', () => {
  it('parses id, event, and multi-line data without evaluating payloads', () => {
    const parser = createConversationEventParser();

    expect(
      parser.push(
        'id: 7\nevent: message.delta\ndata: {"delta":"<b>hello</b>",\ndata: "unsafe": true}\n\n',
      ),
    ).toEqual([
      {
        id: '7',
        type: 'message.delta',
        data: '{"delta":"<b>hello</b>",\n"unsafe": true}',
        payload: { delta: '<b>hello</b>', unsafe: true },
      },
    ]);
  });

  it('treats a clean finite SSE response as a successful completion', async () => {
    const requestStream = vi
      .fn()
      .mockResolvedValueOnce(
        response('id: 7\nevent: task.created\ndata: {"message":"created"}\n\n'),
      );
    const client = { requestStream } as unknown as HttpClient;
    const events: string[] = [];
    const states: string[] = [];

    await streamConversationEvents('c-1', {
      client,
      maxReconnectAttempts: 1,
      sleep: async () => undefined,
      onEvent: (event) => events.push(event.id || ''),
      onState: (state) => states.push(state),
    });

    expect(events).toEqual(['7']);
    expect(requestStream).toHaveBeenNthCalledWith(1, '/api/v1/conversations/c-1/events', {
      query: { after: undefined },
      headers: { Accept: 'text/event-stream' },
      signal: expect.any(AbortSignal),
    });
    expect(states).toContain('connecting');
    expect(states).toContain('connected');
    expect(states).not.toContain('error');
  });

  it('reconnects after a transport failure using the last event cursor', async () => {
    const interrupted = new Response(
      new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode('id: 7\nevent: task.created\ndata: {"message":"created"}\n\n'),
          );
          setTimeout(() => controller.error(new Error('connection reset')), 0);
        },
      }),
      { status: 200, headers: { 'Content-Type': 'text/event-stream' } },
    );
    const requestStream = vi
      .fn()
      .mockResolvedValueOnce(interrupted)
      .mockResolvedValueOnce(
        response('id: 8\nevent: task.updated\ndata: {"message":"updated"}\n\n'),
      );
    const client = { requestStream } as unknown as HttpClient;
    const events: string[] = [];

    await streamConversationEvents('c-1', {
      client,
      maxReconnectAttempts: 1,
      sleep: async () => undefined,
      onEvent: (event) => events.push(event.id || ''),
    });

    expect(events).toEqual(['7', '8']);
    expect(requestStream).toHaveBeenNthCalledWith(2, '/api/v1/conversations/c-1/events', {
      query: { after: '7' },
      headers: { Accept: 'text/event-stream', 'Last-Event-ID': '7' },
      signal: expect.any(AbortSignal),
    });
  });

  it('polls again after a normal close when live mode is enabled', async () => {
    const controller = new AbortController();
    const requestStream = vi
      .fn()
      .mockResolvedValue(response('id: 9\nevent: task.updated\ndata: {}\n\n'));
    const client = { requestStream } as unknown as HttpClient;
    await streamConversationEvents('c-1', {
      client,
      keepAlive: true,
      sleep: async () => controller.abort(),
      signal: controller.signal,
      onEvent: () => undefined,
    });
    expect(requestStream).toHaveBeenCalledTimes(1);
  });

  it('normalizes QwenPaw content blocks without exposing protocol booleans', () => {
    expect(
      conversationEventText({
        type: 'message.delta',
        data: '',
        payload: { delta: true, content: [{ type: 'text', text: 'hello' }] },
      }),
    ).toBe('hello');
    expect(
      conversationEventText({
        type: 'message.delta',
        data: '',
        payload: { content: [{ text: 'one' }, { text: 'two' }] },
      }),
    ).toBe('onetwo');
  });

  it('uses capped exponential reconnect delays', () => {
    expect(conversationReconnectDelay(0)).toBe(250);
    expect(conversationReconnectDelay(3)).toBe(2000);
    expect(conversationReconnectDelay(20)).toBe(30000);
  });
});
