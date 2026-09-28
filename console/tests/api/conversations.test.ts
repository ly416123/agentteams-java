import { describe, expect, it, vi } from 'vitest';
import {
  cancelConversation,
  createConversation,
  getConversation,
  getConversationHistory,
  getConversationTimeline,
  listConversations,
  sendConversationMessage,
} from '../../src/api/conversations';
import type { HttpClient } from '../../src/api/httpClient';

function client() {
  return {
    request: vi.fn(),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('conversation API', () => {
  it('creates a conversation in the requested project and team context', async () => {
    const http = client();
    http.request.mockResolvedValue({ id: 'c-1', version: 0 });

    await createConversation(
      { projectId: 'p-1', teamId: 'team-1', workerId: 'worker-1', taskId: 'task-1' },
      http,
      'create-key',
    );

    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations', {
      method: 'POST',
      body: { projectId: 'p-1', teamId: 'team-1', workerId: 'worker-1', taskId: 'task-1' },
      headers: { 'Idempotency-Key': 'create-key' },
    });
  });

  it('sends messages and cancels with stable idempotency keys', async () => {
    const http = client();
    http.request.mockResolvedValue({});

    await sendConversationMessage(
      'c-1',
      { content: '继续执行', expectedVersion: 2 },
      http,
      'message-key',
    );
    await cancelConversation('c-1', { expectedVersion: 3 }, http, 'cancel-key');

    expect(http.request).toHaveBeenNthCalledWith(1, '/api/v1/conversations/c-1/messages', {
      method: 'POST',
      body: { content: '继续执行', expectedVersion: 2 },
      headers: { 'Idempotency-Key': 'message-key' },
    });
    expect(http.request).toHaveBeenNthCalledWith(2, '/api/v1/conversations/c-1/cancel', {
      method: 'POST',
      body: { expectedVersion: 3 },
      headers: { 'Idempotency-Key': 'cancel-key' },
    });
  });

  it('reads a conversation from the independent conversation contract', async () => {
    const http = client();
    http.request.mockResolvedValue({ id: 'c-1', version: 4 });

    await getConversation('c-1', http);

    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/c-1');
  });

  it('reads durable conversation history for reload recovery', async () => {
    const http = client();
    await getConversationHistory('c-1', http);

    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/c-1/history');
  });

  it('lists conversations for the current project', async () => {
    const http = client();
    http.request.mockResolvedValue({ items: [], hasMore: false });
    await listConversations('p-1', { cursor: 'next' }, http);

    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations', {
      query: { projectId: 'p-1', cursor: 'next' },
    });
  });

  it('reads the unified timeline with visibility-safe references', async () => {
    const http = client();
    http.request.mockResolvedValue([
      {
        itemId: 'm-1',
        itemType: 'CONVERSATION_MESSAGE',
        visibility: 'PUBLIC',
        payloadRef: 'conversation-message:m-1',
      },
    ]);

    const timeline = await getConversationTimeline('c-1', { after: 2, limit: 50 }, http);

    expect(timeline[0].itemType).toBe('CONVERSATION_MESSAGE');
    expect(http.request).toHaveBeenCalledWith('/api/v1/conversations/c-1/timeline', {
      query: { after: 2, limit: 50 },
    });
  });
});
