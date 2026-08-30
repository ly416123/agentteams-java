import { apiClient, type HttpClient } from './httpClient';

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
