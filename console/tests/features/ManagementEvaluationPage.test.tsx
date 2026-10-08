import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
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
      executionGateStatus: 'INCONCLUSIVE',
      resultGateStatus: 'FAILED',
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
      {
        id: 'result-2',
        evaluationRunId: 'run-1',
        metric: 'dependency_handoff',
        score: null,
        threshold: null,
        status: 'INCONCLUSIVE',
        evidenceRefs: ['task-tree:task-1'],
        findingSummary: '证据不完整：依赖交接结果缺失',
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
    expect(screen.getByText('Execution gate')).toBeInTheDocument();
    expect(screen.getByText('Result gate')).toBeInTheDocument();
    expect(screen.getAllByText('证据不完整')).toHaveLength(2);
    expect(within(screen.getByText('Execution gate').parentElement!).getByText('证据不完整：依赖交接结果缺失')).toBeInTheDocument();
    expect(within(screen.getByText('Result gate').parentElement!).queryByText('证据不完整：依赖交接结果缺失')).not.toBeInTheDocument();
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

  it('labels gates missing from a legacy report without deriving a passing status', async () => {
    const evaluations = await import('../../src/api/evaluations');
    vi.mocked(evaluations.getEvaluationReport).mockResolvedValueOnce({
      report: {
        id: 'legacy-report',
        evaluationRunId: 'run-1',
        aggregateScore: 1,
        dimensionScores: {},
        evaluationStatus: 'PASSED',
        taskSuccess: true,
        createdAt: '2026-09-28T08:01:00Z',
      },
      results: [],
    });
    renderPage();

    expect(await screen.findAllByText('旧报告未记录此门禁')).toHaveLength(2);
  });

  it('shows result assertion incompleteness under the result gate', async () => {
    const evaluations = await import('../../src/api/evaluations');
    vi.mocked(evaluations.getEvaluationReport).mockResolvedValueOnce({
      report: {
        id: 'result-incomplete-report',
        evaluationRunId: 'run-1',
        aggregateScore: null,
        dimensionScores: {},
        evaluationStatus: 'INCONCLUSIVE',
        taskSuccess: true,
        executionGateStatus: 'PASSED',
        resultGateStatus: 'INCONCLUSIVE',
        createdAt: '2026-09-28T08:01:00Z',
      },
      results: [{
        id: 'result-pdf',
        evaluationRunId: 'run-1',
        metric: 'artifact_pdf',
        score: null,
        threshold: null,
        status: 'INCONCLUSIVE',
        evidenceRefs: [],
        findingSummary: 'PDF bytes are unavailable',
        createdAt: '2026-09-28T08:01:00Z',
      }],
    });
    renderPage();

    expect(await screen.findAllByText('PDF bytes are unavailable')).toHaveLength(2);
    expect(within(screen.getByText('Result gate').parentElement!).getByText('PDF bytes are unavailable')).toBeInTheDocument();
  });
});
