import { describe, expect, it, vi } from 'vitest';
import {
  createEvaluationRun,
  getEvaluationReport,
  getEvaluationRun,
  listEvaluationRuns,
} from '../../src/api/evaluations';
import type { HttpClient } from '../../src/api/httpClient';

function client(request: HttpClient['request']): HttpClient {
  return {
    request,
    requestText: vi.fn(),
    requestStream: vi.fn(),
  };
}

describe('evaluation API', () => {
  it('lists scoped evaluation runs', async () => {
    const request = vi.fn().mockResolvedValue({ items: [] });
    await listEvaluationRuns('project-1', client(request));
    expect(request).toHaveBeenCalledWith('/api/v1/evaluations/runs', {
      query: { projectId: 'project-1' },
    });
  });

  it('reads a run and its report', async () => {
    const request = vi.fn().mockResolvedValue({});
    const http = client(request);
    await getEvaluationRun('run-1', http);
    await getEvaluationReport('run-1', http);
    expect(request).toHaveBeenNthCalledWith(1, '/api/v1/evaluations/runs/run-1');
    expect(request).toHaveBeenNthCalledWith(2, '/api/v1/evaluations/runs/run-1/report');
  });

  it('creates a run without allowing a client project scope override', async () => {
    const request = vi.fn().mockResolvedValue({});
    await createEvaluationRun(
      {
        suiteId: 'suite-1',
        caseId: 'case-1',
        taskId: 'task-1',
        executionRunId: 'execution-1',
        evaluatorVersion: 'deterministic-v1',
      },
      client(request),
    );
    expect(request).toHaveBeenCalledWith('/api/v1/evaluations/runs', {
      method: 'POST',
      body: {
        suiteId: 'suite-1',
        caseId: 'case-1',
        taskId: 'task-1',
        executionRunId: 'execution-1',
        evaluatorVersion: 'deterministic-v1',
      },
    });
  });
});
