import { apiClient, type HttpClient } from './httpClient';
import { normalizeCursorPage, type CursorPage } from './types';

export type Conversation = {
  id?: string;
  sessionId?: string;
  projectId?: string;
  teamId?: string;
  workerId?: string;
  taskId?: string;
  status: string;
  version?: number;
};

export type ConversationSummary = {
  sessionId: string;
  context: { project: string; team: string; worker?: string | null; task?: string | null };
  status: string;
  version?: number;
  createdAt: string;
  updatedAt: string;
  lastMessage?: string | null;
};

export type ConversationMessageResponse = {
  session?: { version?: number };
  sessionId?: string;
  idempotencyKey?: string;
  events?: Array<{ id: number; event: string; data: unknown }>;
};

export type ConversationHistory = {
  messages: Array<{
    idempotencyKey: string;
    content: string;
    startCursor: number;
    endCursor?: number;
  }>;
  events: Array<{ id: number; event: string; data: unknown }>;
};

export function createConversation(
  context: {
    projectId: string;
    teamId: string;
    workerId?: string;
    taskId?: string;
    sessionId?: string;
  },
  client: HttpClient = apiClient,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return client.request<Conversation>('/api/v1/conversations', {
    method: 'POST',
    body: context,
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function getConversation(id: string, client: HttpClient = apiClient) {
  return client.request<Conversation>(`/api/v1/conversations/${id}`);
}

export function listConversations(
  projectId: string,
  filters: { cursor?: string; pageSize?: number } = {},
  client: HttpClient = apiClient,
) {
  return client
    .request<CursorPage<ConversationSummary> | ConversationSummary[]>('/api/v1/conversations', {
      query: { projectId, cursor: filters.cursor, pageSize: filters.pageSize },
    })
    .then(normalizeCursorPage);
}

export function getConversationHistory(id: string, client: HttpClient = apiClient) {
  return client.request<ConversationHistory>(`/api/v1/conversations/${id}/history`);
}

export function sendConversationMessage(
  id: string,
  body: { content: string; expectedVersion?: number },
  client: HttpClient = apiClient,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return client.request<ConversationMessageResponse>(`/api/v1/conversations/${id}/messages`, {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}

export function cancelConversation(
  id: string,
  body: { expectedVersion?: number } = {},
  client: HttpClient = apiClient,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return client.request<Conversation>(`/api/v1/conversations/${id}/cancel`, {
    method: 'POST',
    body,
    headers: { 'Idempotency-Key': idempotencyKey },
  });
}
