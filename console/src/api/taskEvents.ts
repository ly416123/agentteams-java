import type { HttpClient } from './httpClient';
import { apiClient } from './httpClient';
import type { TaskEvent } from './types';

function eventFromPayload(payload: Record<string, unknown>, id?: string, type?: string): TaskEvent {
  const eventId = id || (typeof payload.id === 'string' ? payload.id : undefined);
  const cursor = typeof payload.cursor === 'string' ? payload.cursor : eventId;
  return {
    id: eventId || `${type || 'task.event'}-${Date.now()}`,
    cursor,
    type: type || String(payload.type || 'task.event'),
    phase: typeof payload.phase === 'string' ? payload.phase : undefined,
    message: typeof payload.message === 'string' ? payload.message : JSON.stringify(payload),
    createdAt: typeof payload.createdAt === 'string' ? payload.createdAt : undefined,
  };
}

function parseFrame(frame: string): TaskEvent | null {
  let id: string | undefined;
  let type: string | undefined;
  const data: string[] = [];
  frame.split(/\r?\n/).forEach((line) => {
    if (line.startsWith('id:')) id = line.slice(3).trim();
    else if (line.startsWith('event:')) type = line.slice(6).trim();
    else if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
  });
  if (!data.length) return null;
  try {
    return eventFromPayload(JSON.parse(data.join('\n')) as Record<string, unknown>, id, type);
  } catch {
    return null;
  }
}

export function createTaskEventParser() {
  let buffer = '';
  const parseCompleteFrames = () => {
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    return frames.map(parseFrame).filter((event): event is TaskEvent => Boolean(event));
  };
  return {
    push(chunk: string) {
      buffer += chunk;
      return parseCompleteFrames();
    },
    finish() {
      if (!buffer.trim()) return [];
      const event = parseFrame(buffer);
      buffer = '';
      return event ? [event] : [];
    },
  };
}

export function parseTaskEventStream(text: string): TaskEvent[] {
  const trimmed = text.trim();
  if (!trimmed) return [];
  try {
    const json = JSON.parse(trimmed) as unknown;
    if (Array.isArray(json)) return json as TaskEvent[];
    if (json && typeof json === 'object' && 'items' in json) {
      return ((json as { items: TaskEvent[] }).items || []) as TaskEvent[];
    }
  } catch {
    // The payload is an SSE stream; parse its frames below.
  }
  const parser = createTaskEventParser();
  return [...parser.push(text), ...parser.finish()];
}

export type TaskEventStreamOptions = {
  after?: string;
  client?: HttpClient;
  signal?: AbortSignal;
  onEvents: (events: TaskEvent[]) => void;
};

export function taskEventReconnectDelay(attempt: number) {
  return Math.min(30_000, 250 * 2 ** attempt);
}

export async function streamTaskEvents(
  taskId: string,
  { after, client = apiClient, signal, onEvents }: TaskEventStreamOptions,
) {
  const response = await client.requestStream(`/api/v1/tasks/${taskId}/events`, {
    query: { after },
    headers: {
      Accept: 'text/event-stream',
      ...(after ? { 'Last-Event-ID': after } : {}),
    },
    signal,
  });
  if (!response.body) return;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = createTaskEventParser();
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      const events = parser.push(decoder.decode(value, { stream: true }));
      if (events.length) onEvents(events);
    }
    const events = [...parser.push(decoder.decode()), ...parser.finish()];
    if (events.length) onEvents(events);
  } finally {
    reader.releaseLock();
  }
}
