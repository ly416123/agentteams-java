export type ApiErrorPayload = {
  code?: string;
  message?: string;
  correlationId?: string;
  details?: Record<string, unknown>;
};

export class AgentTeamsApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly details?: Record<string, unknown>;

  constructor(status: number, payload: ApiErrorPayload) {
    super(payload.message || 'AgentTeams API request failed');
    this.name = 'AgentTeamsApiError';
    this.status = status;
    this.code = payload.code || codeForStatus(status);
    this.correlationId = payload.correlationId;
    this.details = payload.details;
  }
}

export type Project = {
  id: string;
  tenantId: string;
  name: string;
  status: string;
  createdBy: string;
};

export type Task = {
  id: string;
  title: string;
  description: string;
  phase: string;
  priority: number;
  createdAt: string;
  updatedAt: string;
  version: number;
};

export type TaskListItem = Task & {
  tenantId: string;
  projectId: string;
  team?: string;
  actor?: string;
  source?: string;
  teamId?: string;
  workerId?: string;
};

export type TaskProgressSnapshot = {
  phase: string;
  completed: number;
  total: number;
  progress: number;
  waitingReason: string;
};

export type TaskProcessEvent = {
  eventId: string;
  taskId: string;
  runId: string;
  sequence: number;
  eventType: string;
  visibility: string;
  occurredAt: string;
  correlationId: string;
  payload?: string;
  payloadRef?: string;
};

export type TaskResultManifest = {
  taskId: string;
  runId: string;
  status: string;
  summary: string;
  artifacts: Array<{
    name: string;
    storageRef: string;
    contentType: string;
    sizeBytes: number;
    sha256: string;
    version: number;
    stage: string;
    visibility: string;
  }>;
};

export type CursorPage<T> = {
  items: T[];
  nextCursor?: string | null;
  hasMore: boolean;
  serverTime: string;
};

export type CreateProjectRequest = { name: string };
export type CreateTaskRequest = {
  title: string;
  description: string;
  spec: Record<string, unknown>;
  actor?: string;
  source?: string;
};
export type LifecycleRequest = {
  expectedVersion?: number;
  actor?: string;
  source?: string;
};

export type ListProjectsParams = {
  cursor?: string;
  pageSize?: number;
  sort?: 'updatedAt' | 'createdAt';
  direction?: 'asc' | 'desc';
  status?: string;
  q?: string;
};

export type ListTasksParams = ListProjectsParams & {
  phase?: string;
  teamId?: string;
  workerId?: string;
  actor?: string;
  from?: string;
  to?: string;
};

export type AgentTeamsClientOptions = {
  baseUrl?: string;
  accessToken?: string | (() => string | undefined);
  fetcher?: typeof fetch;
  maxRetries?: number;
  retryDelayMs?: number;
};

type RequestOptions = {
  method?: 'GET' | 'POST';
  body?: unknown;
  query?: Record<string, string | number | undefined>;
  idempotencyKey?: string;
  retrySafe?: boolean;
};

export class AgentTeamsClient {
  private readonly baseUrl: string;
  private readonly accessToken?: string | (() => string | undefined);
  private readonly fetcher: typeof fetch;
  private readonly maxRetries: number;
  private readonly retryDelayMs: number;

  constructor(options: AgentTeamsClientOptions = {}) {
    this.baseUrl = (options.baseUrl || 'http://localhost:8080').replace(/\/$/, '');
    this.accessToken = options.accessToken;
    this.fetcher = options.fetcher || fetch;
    this.maxRetries = options.maxRetries ?? 2;
    this.retryDelayMs = options.retryDelayMs ?? 100;
  }

  listProjects(params: ListProjectsParams = {}) {
    return this.request<CursorPage<Project>>('/api/v1/projects', { query: params });
  }

  createProject(body: CreateProjectRequest, options: { idempotencyKey?: string; retrySafe?: boolean } = {}) {
    return this.request<Project>('/api/v1/projects', {
      method: 'POST',
      body,
      idempotencyKey: options.idempotencyKey,
      retrySafe: options.retrySafe,
    });
  }

  listTasks(params: ListTasksParams = {}) {
    return this.request<CursorPage<TaskListItem>>('/api/v1/tasks', { query: params });
  }

  getTask(taskId: string) {
    return this.request<Task>(`/api/v1/tasks/${encodeURIComponent(taskId)}`);
  }

  getTaskProgress(taskId: string, runId: string, phase = 'EXECUTION') {
    return this.request<TaskProgressSnapshot>(
      `/api/v1/tasks/${encodeURIComponent(taskId)}/runs/${encodeURIComponent(runId)}/progress`,
      { query: { phase } },
    );
  }

  getTaskResult(taskId: string, runId: string, visibility = 'REQUESTER') {
    return this.request<TaskResultManifest>(
      `/api/v1/tasks/${encodeURIComponent(taskId)}/runs/${encodeURIComponent(runId)}/result`,
      { query: { visibility } },
    );
  }

  listTaskProcessEvents(taskId: string, runId: string, params: { after?: number; visibility?: string } = {}) {
    return this.request<TaskProcessEvent[]>(
      `/api/v1/tasks/${encodeURIComponent(taskId)}/runs/${encodeURIComponent(runId)}/process-events`,
      { query: params },
    );
  }

  cancelTask(
    taskId: string,
    body: LifecycleRequest = {},
    options: { idempotencyKey?: string; retrySafe?: boolean } = {},
  ) {
    return this.request<Task>(`/api/v1/tasks/${encodeURIComponent(taskId)}/cancel`, {
      method: 'POST',
      body,
      idempotencyKey: options.idempotencyKey,
      retrySafe: options.retrySafe,
    });
  }

  private async request<T>(path: string, options: RequestOptions = {}): Promise<T> {
    const method = options.method || 'GET';
    const url = new URL(`${this.baseUrl}${path}`);
    Object.entries(options.query || {}).forEach(([key, value]) => {
      if (value !== undefined && value !== '') url.searchParams.set(key, String(value));
    });
    const headers = new Headers({ Accept: 'application/json' });
    const token = typeof this.accessToken === 'function' ? this.accessToken() : this.accessToken;
    if (token) headers.set('Authorization', `Bearer ${token}`);
    if (options.body !== undefined) headers.set('Content-Type', 'application/json');
    if (method !== 'GET') {
      headers.set('Idempotency-Key', options.idempotencyKey || `sdk-${crypto.randomUUID()}`);
    }

    for (let attempt = 0; ; attempt += 1) {
      const response = await this.fetcher(
        new Request(url.toString(), {
          method,
          headers,
          body: options.body === undefined ? undefined : JSON.stringify(options.body),
        }),
      );
      if (response.ok) {
        if (response.status === 204) return undefined as T;
        return (await response.json()) as T;
      }
      if (shouldRetry(method, response.status, options.retrySafe) && attempt < this.maxRetries) {
        await delay(this.retryDelayMs * 2 ** attempt);
        continue;
      }
      throw new AgentTeamsApiError(response.status, await readErrorPayload(response));
    }
  }
}

function shouldRetry(method: string, status: number, retrySafe = false) {
  return (method === 'GET' || retrySafe) && [429, 500, 502, 503, 504].includes(status);
}

async function readErrorPayload(response: Response): Promise<ApiErrorPayload> {
  const text = await response.text();
  if (!text) return {};
  try {
    return JSON.parse(text) as ApiErrorPayload;
  } catch {
    return { message: 'AgentTeams API request failed' };
  }
}

function codeForStatus(status: number) {
  switch (status) {
    case 401:
      return 'UNAUTHENTICATED';
    case 403:
      return 'FORBIDDEN';
    case 409:
      return 'CONFLICT';
    case 429:
      return 'RATE_LIMITED';
    case 503:
      return 'UNAVAILABLE_DEPENDENCY';
    default:
      return 'REQUEST_FAILED';
  }
}

function delay(milliseconds: number) {
  return milliseconds === 0 ? Promise.resolve() : new Promise((resolve) => setTimeout(resolve, milliseconds));
}
