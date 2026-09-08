import type { TaskProcessEvent } from '../../api/types';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { labelDecision, labelType } from '../../i18n/labels';
import {
  useTaskDecisions,
  useTaskProgress,
  useTaskResult,
  useTaskTree,
} from '../../queries/useTaskQueries';

export function TaskExecutionObservability({
  projectId,
  taskId,
  runId,
  processEvents,
}: {
  projectId: string;
  taskId: string;
  runId: string;
  /** 过程事件流由页面级 useTaskProcessEvents 持有并注入，避免同页双路 SSE 连接。 */
  processEvents: {
    data: TaskProcessEvent[];
    isError: boolean;
    error?: unknown;
    connectionState: 'connecting' | 'connected' | 'reconnecting';
    refetch: () => Promise<unknown>;
  };
}) {
  const progress = useTaskProgress(projectId, taskId, runId);
  const tree = useTaskTree(projectId, taskId, runId);
  const decisions = useTaskDecisions(projectId, taskId, runId);
  const result = useTaskResult(projectId, taskId, runId);

  if (!runId) {
    return (
      <section className="panel" aria-label="执行观测">
        <p className="eyebrow">执行观测</p>
        <h2>等待执行运行记录</h2>
        <p className="muted-text">任务开始执行后，这里会显示实时过程、进度、决策和成果物。</p>
      </section>
    );
  }

  return (
    <>
      <section className="panel" aria-label="执行总览">
        <div className="section-heading">
          <div>
            <p className="eyebrow">执行观测 / 运行 {runId}</p>
            <h2>执行总览</h2>
          </div>
          {progress.data && <StatusBadge phase={progress.data.phase} />}
        </div>
        {progress.isError ? (
          <ErrorState error={progress.error} onRetry={() => void progress.refetch()} />
        ) : progress.data ? (
          <div className="execution-overview">
            <div className="progress-summary">
              <strong>{progress.data.progress}%</strong>
              <span>
                已完成 {progress.data.completed} / {progress.data.total} 个执行单元
              </span>
            </div>
            <div className="progress-track" aria-label="任务执行进度">
              <span style={{ width: `${progress.data.progress}%` }} />
            </div>
            {progress.data.waitingReason && (
              <div className="info-box" role="status">
                当前等待：{progress.data.waitingReason}
              </div>
            )}
          </div>
        ) : (
          <div className="loading-block">加载执行进度…</div>
        )}
      </section>

      <section className="panel" aria-label="实时执行过程">
        <div className="section-heading">
          <div>
            <p className="eyebrow">可回放事件流</p>
            <h2>实时执行过程</h2>
          </div>
          <span className="muted-text">
            {processEvents.connectionState === 'connected'
              ? '已连接'
              : processEvents.connectionState === 'reconnecting'
                ? '正在重连'
                : '连接中'}
          </span>
        </div>
        {processEvents.isError && (
          <ErrorState error={processEvents.error} onRetry={() => void processEvents.refetch()} />
        )}
        {!processEvents.data.length ? (
          <p className="muted-text">暂无过程事件，系统会在收到 Worker 进度后更新。</p>
        ) : (
          <ol className="timeline" data-testid="task-process-events">
            {processEvents.data.map((event) => (
              <li className="timeline-item" key={event.eventId}>
                <span className="timeline-dot timeline-dot--info" />
                <div>
                  <strong>{labelType(event.eventType)}</strong>
                  <p>{summarizePayload(event.payload, event.payloadRef)}</p>
                  <time>
                    {new Date(event.occurredAt).toLocaleString('zh-CN')} · 序号 {event.sequence}
                  </time>
                </div>
              </li>
            ))}
          </ol>
        )}
      </section>

      <section className="panel" aria-label="任务分解">
        <div className="section-heading">
          <div>
            <p className="eyebrow">执行拓扑</p>
            <h2>任务分解</h2>
          </div>
        </div>
        {tree.isError ? (
          <ErrorState error={tree.error} onRetry={() => void tree.refetch()} />
        ) : !tree.data?.length ? (
          <p className="muted-text">当前运行没有任务分解节点。</p>
        ) : (
          <div className="stack-list">
            {tree.data.map((node) => (
              <article className="stack-list__item" key={`${node.taskId}-${node.sequence}`}>
                <div>
                  <strong>{node.parentTaskId ? '子任务' : '主任务'} · {node.taskId}</strong>
                  <div className="muted-text">
                    {node.parentTaskId ? `父任务 ${node.parentTaskId}` : '根任务'} · 更新于{' '}
                    {new Date(node.updatedAt).toLocaleString('zh-CN')}
                  </div>
                </div>
                <StatusBadge phase={node.status} />
                {node.dependencyIds.length > 0 && (
                  <div className="muted-text">依赖：{node.dependencyIds.join('、')}</div>
                )}
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="panel" aria-label="决策记录">
        <div className="section-heading">
          <div>
            <p className="eyebrow">可审计摘要</p>
            <h2>决策记录</h2>
          </div>
        </div>
        {decisions.isError ? (
          <ErrorState error={decisions.error} onRetry={() => void decisions.refetch()} />
        ) : !decisions.data?.length ? (
          <p className="muted-text">当前运行还没有可展示的决策记录。</p>
        ) : (
          <div className="stack-list">
            {decisions.data.map((decision) => (
              <article className="stack-list__item" key={decision.id}>
                <strong>{decision.goalSummary}</strong>
                <div>采取动作：{decision.selectedAction}</div>
                {decision.evidenceSummary && <div>依据：{decision.evidenceSummary}</div>}
                {decision.constraintsSummary && <div>约束：{decision.constraintsSummary}</div>}
                <div className="muted-text">
                  {decision.confidence == null ? '未提供置信度' : `置信度 ${(decision.confidence * 100).toFixed(0)}%`} ·{' '}
                  {labelDecision(decision.visibility)} · {new Date(decision.createdAt).toLocaleString('zh-CN')}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>

      <section className="panel" aria-label="运行结果与成果物">
        <div className="section-heading">
          <div>
            <p className="eyebrow">终态记录</p>
            <h2>运行结果与成果物</h2>
          </div>
          {result.data && <StatusBadge phase={result.data.status} />}
        </div>
        {result.isError ? (
          <p className="muted-text">运行尚未产生终态结果，完成后会自动展示结果 Manifest。</p>
        ) : result.data ? (
          <>
            <p>{result.data.summary}</p>
            {!result.data.artifacts.length ? (
              <p className="muted-text">本次运行没有登记成果物。</p>
            ) : (
              <div className="table-wrap">
                <table className="resource-table">
                  <thead>
                    <tr>
                      <th>名称</th>
                      <th>类型</th>
                      <th>大小</th>
                      <th>阶段</th>
                      <th>校验值</th>
                      <th>操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.data.artifacts.map((artifact) => (
                      <tr key={`${artifact.name}-${artifact.version}`}>
                        <td><strong>{artifact.name}</strong></td>
                        <td>{artifact.contentType}</td>
                        <td>{formatBytes(artifact.sizeBytes)}</td>
                        <td>{artifact.stage}</td>
                        <td><code>{artifact.sha256}</code></td>
                        <td>
                          {artifact.downloadUrl ? (
                            <a className="button button--ghost" href={artifact.downloadUrl}
                              target="_blank" rel="noreferrer">下载</a>
                          ) : (
                            <span className="muted-text">不可下载</span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </>
        ) : (
          <div className="loading-block">加载运行结果…</div>
        )}
      </section>
    </>
  );
}

function summarizePayload(payload?: string | null, payloadRef?: string | null) {
  if (!payload) return payloadRef ? `详细内容引用：${payloadRef}` : '过程事件已记录';
  try {
    const value = JSON.parse(payload) as Record<string, unknown>;
    const summary = value.message || value.summary || value.reason || value.status;
    if (typeof summary === 'string') return summary;
    return JSON.stringify(value);
  } catch {
    return payload;
  }
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MB`;
}
