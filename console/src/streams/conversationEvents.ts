import { apiClient, type HttpClient } from '../api/httpClient';

export type ConversationEvent = {
  id?: string;
  type: string;
  data: string;
  payload: Record<string, unknown>;
};

function parseFrame(frame: string): ConversationEvent | null {
  let id: string | undefined;
  let type = 'message.delta';
  const data: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('id:')) id = line.slice(3).trim();
    else if (line.startsWith('event:')) type = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  }
  if (!data.length) return null;
  const raw = data.join('\n');
  let payload: Record<string, unknown> = {};
  try {
    payload = JSON.parse(raw) as Record<string, unknown>;
  } catch {
    payload = { text: raw };
  }
  return { id, type, data: raw, payload };
}

export function createConversationEventParser() {
  let buffer = '';
  const consume = () => {
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    return frames.map(parseFrame).filter((event): event is ConversationEvent => Boolean(event));
  };
  return {
    push(chunk: string) {
      buffer += chunk;
      return consume();
    },
    finish() {
      const event = parseFrame(buffer);
      buffer = '';
      return event ? [event] : [];
    },
  };
}

export function conversationReconnectDelay(attempt: number) {
  return Math.min(30_000, 250 * 2 ** attempt);
}

export async function streamConversationEvents(
  id: string,
  options: {
    client?: HttpClient;
    maxReconnectAttempts?: number;
    sleep?: (ms: number) => Promise<void>;
    signal?: AbortSignal;
    onEvent: (event: ConversationEvent) => void;
    onState?: (state: 'connecting' | 'connected' | 'reconnecting' | 'error') => void;
  },
) {
  const client = options.client ?? apiClient;
  const maxAttempts = options.maxReconnectAttempts ?? 5;
  const sleep =
    options.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
  const internalController = new AbortController();
  const signal = options.signal ?? internalController.signal;
  let after: string | undefined;
  for (let attempt = 0; attempt <= maxAttempts; attempt += 1) {
    options.onState?.(attempt ? 'reconnecting' : 'connecting');
    try {
      const response = await client.requestStream(`/api/v1/conversations/${id}/events`, {
        query: { after },
        headers: { Accept: 'text/event-stream', ...(after ? { 'Last-Event-ID': after } : {}) },
        signal,
      });
      options.onState?.('connected');
      if (!response.body) throw new Error('事件流没有响应体');
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      const parser = createConversationEventParser();
      try {
        while (true) {
          const next = await reader.read();
          if (next.done) break;
          for (const event of parser.push(decoder.decode(next.value, { stream: true }))) {
            after = event.id || after;
            options.onEvent(event);
          }
        }
        for (const event of [...parser.push(decoder.decode()), ...parser.finish()]) {
          after = event.id || after;
          options.onEvent(event);
        }
      } finally {
        reader.releaseLock();
      }
      if (attempt === maxAttempts) throw new Error('事件流已结束');
    } catch (error) {
      if (signal.aborted) return;
      if (attempt === maxAttempts) {
        options.onState?.('error');
        throw error;
      }
      await sleep(conversationReconnectDelay(attempt));
    }
  }
}
