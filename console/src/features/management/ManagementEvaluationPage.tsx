import { useQuery } from '@tanstack/react-query';
import { Link, useSearchParams } from 'react-router-dom';
import {
  getEvaluationReport,
  getEvaluationRun,
  listEvaluationRuns,
} from '../../api/evaluations';
import type { EvaluationReport, EvaluationReportResponse, EvaluationResult, EvaluationRun } from '../../api/types';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

const SENSITIVE_REF = /token|password|cookie|secret|authorization|x-amz-|presign|[?&=]/i;
const SENSITIVE_TEXT = /token|password|cookie|secret|authorization|x-amz-|presign/i;
const EXECUTION_METRICS = new Set([
  'phase', 'required_tool', 'forbidden_tool', 'failed_tool', 'tool_order', 'tool_count',
  'dependency_success', 'dependency_handoff', 'retry_limit', 'run_status',
]);

export function ManagementEvaluationPage({ projectId, runId }: { projectId: string; runId?: string }) {
  const [searchParams] = useSearchParams();
  const resolvedRunId = runId || searchParams.get('runId') || '';
  const evaluationRun = useQuery({
    queryKey: ['management-evaluation-run', projectId, resolvedRunId],
    queryFn: () => getEvaluationRun(resolvedRunId),
    enabled: Boolean(projectId && resolvedRunId),
  });
  const evaluationRuns = useQuery({
    queryKey: ['management-evaluation-runs', projectId],
    queryFn: () => listEvaluationRuns(projectId),
    enabled: Boolean(projectId && !resolvedRunId),
  });
  const report = useQuery({
    queryKey: ['management-evaluation-report', projectId, resolvedRunId],
    queryFn: () => getEvaluationReport(resolvedRunId),
    enabled: Boolean(projectId && resolvedRunId),
  });

  if (!resolvedRunId) {
    return (
      <div className="page">
        <div className="page-heading">
          <div>
            <p className="eyebrow">质量 / 评测</p>
            <h1>评测</h1>
          </div>
        </div>
        {evaluationRuns.isLoading ? (
          <div className="panel loading-block">加载评测运行…</div>
        ) : evaluationRuns.isError ? (
          <ErrorState error={evaluationRuns.error} onRetry={() => void evaluationRuns.refetch()} />
        ) : evaluationRuns.data && runsFromResponse(evaluationRuns.data).length ? (
          <div className="content-grid">
            {runsFromResponse(evaluationRuns.data).map((run) => (
              <article className="panel" key={run.id}>
                <div className="section-heading">
                  <div><p className="eyebrow">Evaluation Run</p><h2>{run.id}</h2></div>
                  <StatusBadge phase={run.status} />
                </div>
                <p className="muted-text">Task {run.taskId} · {run.evaluatorVersion}</p>
                <Link className="button button--ghost" to={`/${projectId}/evaluations/${run.id}`}>
                  查看详情
                </Link>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState title="暂无评测运行" description="当前 Project 尚未产生可查看的评测运行。" />
        )}
      </div>
    );
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">质量 / 评测</p>
          <h1>评测详情</h1>
          <p className="page-subtitle">评测结论与 Task 生命周期状态独立记录，评测失败不会回写 Task。</p>
        </div>
        <button
          className="button button--ghost"
          onClick={() => {
            void evaluationRun.refetch();
            void report.refetch();
          }}
        >
          刷新
        </button>
      </div>
      {evaluationRun.isLoading || report.isLoading ? (
        <div className="panel loading-block">加载评测报告…</div>
      ) : evaluationRun.isError ? (
        <ErrorState error={evaluationRun.error} onRetry={() => void evaluationRun.refetch()} />
      ) : report.isError ? (
        <ErrorState error={report.error} onRetry={() => void report.refetch()} />
      ) : evaluationRun.data && report.data ? (
        <EvaluationDetails
          projectId={projectId}
          run={evaluationRun.data}
          response={report.data}
        />
      ) : null}
    </div>
  );
}

function EvaluationDetails({
  projectId,
  run,
  response,
}: {
  projectId: string;
  run: EvaluationRun;
  response: EvaluationReportResponse;
}) {
  const { report, results } = normalizeReport(response);
  return (
    <>
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">Evaluation Run</p>
            <h2>{run.id}</h2>
          </div>
          <StatusBadge phase={run.status} />
        </div>
        <div className="detail-list">
          <span>Suite<strong>{run.suiteId}</strong></span>
          <span>Case<strong>{run.caseId}</strong></span>
          <span>Task<strong><Link to={`/${projectId}/tasks/${run.taskId}`}>{run.taskId}</Link></strong></span>
          <span>Execution Run<strong>{run.executionRunId}</strong></span>
          <span>Evaluator<strong>{run.evaluatorVersion}</strong></span>
        </div>
      </section>

      <section className="content-grid">
        <article className="panel">
          <div className="section-heading">
            <h2>状态关系</h2>
          </div>
          <div className="usage-summary">
            <div>
              <span>Evaluation 状态</span>
              <strong><StatusBadge phase={report.evaluationStatus} /></strong>
            </div>
            <div>
              <span>Task success</span>
              <strong>
                <StatusBadge phase={report.taskSuccess ? 'SUCCEEDED' : 'FAILED'} />
                <span className="muted-text">{report.taskSuccess ? '是' : '否'}</span>
              </strong>
            </div>
            <GateSummary label="Execution gate" status={report.executionGateStatus}
              inconclusiveReasons={inconclusiveReasons(results, true)} />
            <GateSummary label="Result gate" status={report.resultGateStatus}
              inconclusiveReasons={inconclusiveReasons(results, false)} />
            <div>
              <span>aggregateScore</span>
              <strong>{formatScore(report.aggregateScore)}</strong>
            </div>
          </div>
        </article>

        <article className="panel">
          <div className="section-heading"><h2>维度分数</h2></div>
          {!Object.keys(report.dimensionScores).length ? (
            <p className="muted-text">暂无维度分数。</p>
          ) : (
            <div className="detail-list">
              {Object.entries(report.dimensionScores).map(([dimension, score]) => (
                <span key={dimension}>{dimension}<strong>{formatScore(score)}</strong></span>
              ))}
            </div>
          )}
        </article>
      </section>

      <section className="panel">
        <div className="section-heading">
          <div><p className="eyebrow">Deterministic checks</p><h2>评测结果</h2></div>
        </div>
        {!results.length ? (
          <p className="muted-text">暂无逐项评测结果。</p>
        ) : (
          <div className="content-grid">
            {results.map((result) => <EvaluationResultCard key={result.id} projectId={projectId} result={result} />)}
          </div>
        )}
      </section>
    </>
  );
}

function GateSummary({ label, status, inconclusiveReasons = [] }: {
  label: string;
  status?: string | null;
  inconclusiveReasons?: string[];
}) {
  if (!status) {
    return <div><span>{label}</span><strong className="muted-text">旧报告未记录此门禁</strong></div>;
  }
  return (
    <div>
      <span>{label}</span>
      <strong>
        <StatusBadge phase={status} />
      </strong>
      {inconclusiveReasons.length ? <ul className="muted-text">
        {inconclusiveReasons.map((reason, index) => <li key={`${index}-${reason}`}>{reason}</li>)}
      </ul> : null}
    </div>
  );
}

function inconclusiveReasons(results: EvaluationResult[], executionGate: boolean) {
  return results.filter((result) => {
    if (result.status !== 'INCONCLUSIVE' || result.metric === 'semantic') return false;
    const metricType = result.metric.split(':', 1)[0];
    return EXECUTION_METRICS.has(metricType) === executionGate;
  }).map((result) => safeFinding(result.findingSummary)).filter(Boolean);
}

function EvaluationResultCard({ projectId, result }: { projectId: string; result: EvaluationResult }) {
  const finding = safeFinding(result.findingSummary);
  const refs = result.evidenceRefs.filter(isSafeEvidenceRef);
  return (
    <article className="panel panel--nested">
      <div className="section-heading">
        <h3>{result.metric}</h3>
        <StatusBadge phase={result.status} />
      </div>
      <div className="detail-list">
        <span>分数<strong>{formatScore(result.score)}</strong></span>
        <span>阈值<strong>{formatScore(result.threshold)}</strong></span>
      </div>
      {finding ? <p>{finding}</p> : null}
      {refs.length ? (
        <div>
          <p className="eyebrow">证据引用</p>
          <ul>
            {refs.map((ref) => <li key={ref}><Link to={evidenceLink(projectId, ref)}>{ref}</Link></li>)}
          </ul>
        </div>
      ) : (
        <p className="muted-text">无可展示的安全证据引用。</p>
      )}
    </article>
  );
}

function normalizeReport(response: EvaluationReportResponse): { report: EvaluationReport; results: EvaluationResult[] } {
  if ('report' in response) return { report: response.report, results: response.results || [] };
  return { report: response, results: [] };
}

function isSafeEvidenceRef(value: string) {
  return Boolean(value) && !SENSITIVE_REF.test(value) && /^[a-z0-9:_-]+$/i.test(value);
}

function safeFinding(value: string | null | undefined) {
  if (!value) return '';
  const index = value.search(SENSITIVE_TEXT);
  return (index >= 0 ? value.slice(0, index) : value).trim();
}

function evidenceLink(projectId: string, ref: string) {
  if (ref.startsWith('task-snapshot:')) return `/${projectId}/tasks/${ref.slice('task-snapshot:'.length)}`;
  if (ref.startsWith('result-manifest:')) return `/${projectId}/artifacts?runId=${encodeURIComponent(ref.slice('result-manifest:'.length))}`;
  if (ref.startsWith('artifact:')) return `/${projectId}/artifacts?sha256=${encodeURIComponent(ref.slice('artifact:'.length))}`;
  return `/${projectId}/tasks?evidenceRef=${encodeURIComponent(ref)}`;
}

function formatScore(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : value.toFixed(3);
}

function runsFromResponse(value: Awaited<ReturnType<typeof listEvaluationRuns>>) {
  return Array.isArray(value) ? value : value.items || [];
}
