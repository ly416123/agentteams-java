import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  cancelConversation,
  createConversation,
  getConversation,
  getConversationHistory,
  listConversations,
  sendConversationMessage,
} from '../../src/api/conversations';

function client(response: unknown = {}) {
  return {
    request: vi.fn().mockResolvedValue(response),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('conversation domain API contracts: 20 cases', () => {
  it('creates a conversation with project and team', async () => {
    const http = client();
    await createConversation({ projectId: 'project-1', teamId: 'team-1' }, http, 'create-1');
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations', {
      method: 'POST',
      body: { projectId: 'project-1', teamId: 'team-1' },
      headers: { 'Idempotency-Key': 'create-1' },
    });
  });

  it('creates a conversation with worker and task context', async () => {
    const http = client();
    const context = {
      projectId: 'project-1',
      teamId: 'team-1',
      workerId: 'worker-1',
      taskId: 'task-1',
    };
    await createConversation(context, http, 'create-2');
    expect(http.request.mock.calls[0][1].body).toEqual(context);
  });

  it('creates a conversation with a caller session id', async () => {
    const http = client();
    const context = { projectId: 'project-1', teamId: 'team-1', sessionId: 'session-1' };
    await createConversation(context, http, 'create-3');
    expect(http.request.mock.calls[0][1].body).toEqual(context);
  });

  it('uses a supplied create idempotency key', async () => {
    const http = client();
    await createConversation({ projectId: 'p', teamId: 't' }, http, 'stable-create-key');
    expect(http.request.mock.calls[0][1].headers).toEqual({
      'Idempotency-Key': 'stable-create-key',
    });
  });

  it('gets a conversation by id', async () => {
    const http = client();
    await getConversation('conversation-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/conversation-1');
  });

  it('lists conversations by project', async () => {
    const http = client({ items: [], hasMore: false });
    await listConversations('project-1', {}, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations', {
      query: { projectId: 'project-1', cursor: undefined, pageSize: undefined },
    });
  });

  it('lists conversations from a cursor', async () => {
    const http = client({ items: [], nextCursor: 'cursor-2', hasMore: true });
    await listConversations('project-1', { cursor: 'cursor-2' }, http);
    expect(http.request.mock.calls[0][1].query).toEqual({
      projectId: 'project-1',
      cursor: 'cursor-2',
      pageSize: undefined,
    });
  });

  it('lists conversations with a page size', async () => {
    const http = client({ items: [], hasMore: false });
    await listConversations('project-1', { pageSize: 50 }, http);
    expect(http.request.mock.calls[0][1].query).toEqual({
      projectId: 'project-1',
      cursor: undefined,
      pageSize: 50,
    });
  });

  it('lists conversations with cursor and page size together', async () => {
    const http = client({ items: [], hasMore: true });
    await listConversations('project-1', { cursor: 'cursor-3', pageSize: 25 }, http);
    expect(http.request.mock.calls[0][1].query).toEqual({
      projectId: 'project-1',
      cursor: 'cursor-3',
      pageSize: 25,
    });
  });

  it('normalizes an array conversation response', async () => {
    const http = client([{ sessionId: 'session-1', status: 'ACTIVE' }]);
    const page = await listConversations('project-1', {}, http);
    expect(page.items).toEqual([{ sessionId: 'session-1', status: 'ACTIVE' }]);
    expect(page.hasMore).toBe(false);
  });

  it('preserves conversation pagination metadata', async () => {
    const http = client({ items: [{ sessionId: 'session-1' }], nextCursor: 'next', hasMore: true });
    const page = await listConversations('project-1', {}, http);
    expect(page.nextCursor).toBe('next');
    expect(page.hasMore).toBe(true);
  });

  it('gets durable conversation history', async () => {
    const http = client();
    await getConversationHistory('conversation-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/conversation-1/history');
  });

  it('sends a message with stable idempotency', async () => {
    const http = client();
    await sendConversationMessage('conversation-1', { content: '你好' }, http, 'message-1');
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/conversation-1/messages', {
      method: 'POST',
      body: { content: '你好' },
      headers: { 'Idempotency-Key': 'message-1' },
    });
  });

  it('sends a message with expected version', async () => {
    const http = client();
    const body = { content: '继续执行', expectedVersion: 4 };
    await sendConversationMessage('conversation-1', body, http, 'message-2');
    expect(http.request.mock.calls[0][1].body).toEqual(body);
  });

  it('preserves message content as plain input', async () => {
    const http = client();
    await sendConversationMessage(
      'conversation-1',
      { content: '<script>alert(1)</script>' },
      http,
      'message-3',
    );
    expect(http.request.mock.calls[0][1].body.content).toBe('<script>alert(1)</script>');
  });

  it('cancels a conversation with default body', async () => {
    const http = client();
    await cancelConversation('conversation-1', {}, http, 'cancel-1');
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/conversation-1/cancel', {
      method: 'POST',
      body: {},
      headers: { 'Idempotency-Key': 'cancel-1' },
    });
  });

  it('cancels a conversation with expected version', async () => {
    const http = client();
    await cancelConversation('conversation-1', { expectedVersion: 5 }, http, 'cancel-2');
    expect(http.request.mock.calls[0][1].body).toEqual({ expectedVersion: 5 });
  });

  it('uses a stable cancellation idempotency key', async () => {
    const http = client();
    await cancelConversation('conversation-2', { expectedVersion: 8 }, http, 'stable-cancel-key');
    expect(http.request.mock.calls[0][1].headers).toEqual({
      'Idempotency-Key': 'stable-cancel-key',
    });
  });

  it('keeps project scope on conversation listing', async () => {
    const http = client({ items: [], hasMore: false });
    await listConversations('project-scope', {}, http);
    expect(http.request.mock.calls[0][1].query.projectId).toBe('project-scope');
  });

  it('keeps the conversation id in message paths', async () => {
    const http = client();
    await sendConversationMessage('conversation-path', { content: '内容' }, http, 'message-path');
    expect(http.request.mock.calls[0][0]).toBe('/api/v1/conversations/conversation-path/messages');
  });
});
