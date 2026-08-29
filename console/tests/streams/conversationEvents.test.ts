import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  conversationReconnectDelay,
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

  it('reconnects with both after and Last-Event-ID from the last event cursor', async () => {
    const requestStream = vi
      .fn()
      .mockResolvedValueOnce(
        response('id: 7\nevent: task.created\ndata: {"message":"created"}\n\n'),
      )
      .mockResolvedValueOnce(
        response('id: 8\nevent: task.updated\ndata: {"message":"updated"}\n\n'),
      );
    const client = { requestStream } as unknown as HttpClient;
    const events: string[] = [];
    const states: string[] = [];

    await expect(
      streamConversationEvents('c-1', {
        client,
        maxReconnectAttempts: 1,
        sleep: async () => undefined,
        onEvent: (event) => events.push(event.id || ''),
        onState: (state) => states.push(state),
      }),
    ).rejects.toThrow('事件流已结束');

    expect(events).toEqual(['7', '8']);
    expect(requestStream).toHaveBeenNthCalledWith(1, '/api/v1/conversations/c-1/events', {
      query: { after: undefined },
      headers: { Accept: 'text/event-stream' },
      signal: expect.any(AbortSignal),
    });
    expect(requestStream).toHaveBeenNthCalledWith(2, '/api/v1/conversations/c-1/events', {
      query: { after: '7' },
      headers: { Accept: 'text/event-stream', 'Last-Event-ID': '7' },
      signal: expect.any(AbortSignal),
    });
    expect(states).toContain('connecting');
    expect(states).toContain('connected');
    expect(states).toContain('reconnecting');
    expect(states).toContain('error');
  });

  it('uses capped exponential reconnect delays', () => {
    expect(conversationReconnectDelay(0)).toBe(250);
    expect(conversationReconnectDelay(3)).toBe(2000);
    expect(conversationReconnectDelay(20)).toBe(30000);
  });
});
