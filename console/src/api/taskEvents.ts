import type { TaskEvent } from './types';

function eventFromPayload(payload: Record<string, unknown>, id?: string, type?: string): TaskEvent {
  return {
    id: id || String(payload.id || `${type || 'task.event'}-${Date.now()}`),
    type: type || String(payload.type || 'task.event'),
    phase: typeof payload.phase === 'string' ? payload.phase : undefined,
    message: typeof payload.message === 'string' ? payload.message : JSON.stringify(payload),
    createdAt: typeof payload.createdAt === 'string' ? payload.createdAt : undefined,
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

  return trimmed
    .split(/\r?\n\r?\n/)
    .map((frame) => {
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
        const payload = JSON.parse(data.join('\n')) as Record<string, unknown>;
        return eventFromPayload(payload, id, type);
      } catch {
        return null;
      }
    })
    .filter((event): event is TaskEvent => Boolean(event));
}
