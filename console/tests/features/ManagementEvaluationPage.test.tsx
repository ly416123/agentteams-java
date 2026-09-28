import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementEvaluationPage } from '../../src/features/management/ManagementEvaluationPage';

vi.mock('../../src/api/evaluations', () => ({
  getEvaluationRun: vi.fn().mockResolvedValue({
    id: 'run-1',
    suiteId: 'suite-1',
    caseId: 'case-1',
    taskId: 'task-1',
    executionRunId: 'execution-1',
    evaluatorVersion: 'deterministic-v1',
    status: 'FAILED',
    createdAt: '2026-09-28T08:00:00Z',
    completedAt: '2026-09-28T08:01:00Z',
  }),
  getEvaluationReport: vi.fn().mockResolvedValue({
    report: {
      id: 'report-1',
      evaluationRunId: 'run-1',
      aggregateScore: 0.5,
      dimensionScores: { phase: 1, artifact_integrity: 0 },
      evaluationStatus: 'FAILED',
      taskSuccess: true,
      createdAt: '2026-09-28T08:01:00Z',
    },
    results: [
      {
        id: 'result-1',
        evaluationRunId: 'run-1',
        metric: 'artifact_integrity',
        score: 0,
        threshold: 1,
        status: 'FAILED',
        evidenceRefs: ['result-manifest:execution-1', 'https://storage.example/a?token=secret'],
        findingSummary: 'artifact metadata is incomplete; token=should-not-render',
        createdAt: '2026-09-28T08:01:00Z',
      },
    ],
  }),
}));

function renderPage() {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter>
        <ManagementEvaluationPage projectId="project-1" runId="run-1" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('Management evaluation page', () => {
  it('keeps evaluation status separate from Task success and redacts unsafe evidence', async () => {
    renderPage();

    expect(await screen.findByRole('heading', { name: '评测详情' })).toBeInTheDocument();
    expect(await screen.findByText('Evaluation 状态')).toBeInTheDocument();
    expect(screen.getByText('Task success')).toBeInTheDocument();
    expect(screen.getAllByText('失败').length).toBeGreaterThan(0);
    expect(screen.getByText('是')).toBeInTheDocument();
    expect(screen.getByText('0.500')).toBeInTheDocument();
    expect(screen.getByText(/artifact metadata is incomplete/)).toBeInTheDocument();
    expect(screen.getByText('result-manifest:execution-1')).toBeInTheDocument();
    expect(screen.queryByText(/token|secret|storage\.example/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /storage/ })).not.toBeInTheDocument();
  });

  it('shows the permission error state without leaking report details', async () => {
    const evaluations = await import('../../src/api/evaluations');
    vi.mocked(evaluations.getEvaluationRun).mockRejectedValueOnce({ status: 403, code: 'FORBIDDEN' });
    renderPage();

    expect(await screen.findByRole('heading', { name: '无权访问' })).toBeInTheDocument();
    expect(screen.queryByText('artifact metadata is incomplete')).not.toBeInTheDocument();
  });
});
