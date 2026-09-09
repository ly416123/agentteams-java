import type { HttpClient } from './httpClient';
import { apiClient } from './httpClient';
import type { TaskProcessEvent } from './types';

function processEventFromPayload(
  payload: Record<string, unknown>,
  frameId?: string,
  frameType?: string,
): TaskProcessEvent | null {
  const eventId = typeof payload.eventId === 'string' ? payload.eventId : frameId;
  const taskId = typeof payload.taskId === 'string' ? payload.taskId : undefined;
  const runId = typeof payload.runId === 'string' ? payload.runId : undefined;
  const sequence = typeof payload.sequence === 'number' ? payload.sequence : Number(frameId);
  if (!eventId || !taskId || !runId || !Number.isFinite(sequence)) return null;
  return {
    eventId,
    taskId,
    runId,
    sequence,
    eventType: typeof payload.eventType === 'string' ? payload.eventType : frameType || 'PROCESS',
    visibility: typeof payload.visibility === 'string' ? payload.visibility : 'REQUESTER',
    occurredAt:
      typeof payload.occurredAt === 'string' ? payload.occurredAt : new Date().toISOString(),
    correlationId: typeof payload.correlationId === 'string' ? payload.correlationId : '',
    payload: typeof payload.payload === 'string' ? payload.payload : null,
    payloadRef: typeof payload.payloadRef === 'string' ? payload.payloadRef : null,
  };
}

function parseFrame(frame: string): TaskProcessEvent | null {
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
    return processEventFromPayload(
      JSON.parse(data.join('\n')) as Record<string, unknown>,
      id,
      type,
    );
  } catch {
    return null;
  }
}

export function createTaskProcessEventParser() {
  let buffer = '';
  const parseCompleteFrames = () => {
    const frames = buffer.split(/\r?\n\r?\n/);
    buffer = frames.pop() || '';
    return frames.map(parseFrame).filter((event): event is TaskProcessEvent => Boolean(event));
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

export type TaskProcessEventStreamOptions = {
  after?: number;
  client?: HttpClient;
  signal?: AbortSignal;
  onEvents: (events: TaskProcessEvent[]) => void;
};

export async function streamTaskProcessEvents(
  taskId: string,
  runId: string,
  { after, client = apiClient, signal, onEvents }: TaskProcessEventStreamOptions,
) {
  const response = await client.requestStream(
    `/api/v1/tasks/${taskId}/runs/${runId}/process-events/stream`,
    {
      query: { visibility: 'REQUESTER' },
      headers: {
        Accept: 'text/event-stream',
        ...(after !== undefined ? { 'Last-Event-ID': String(after) } : {}),
      },
      signal,
    },
  );
  if (!response.body) return;
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = createTaskProcessEventParser();
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
