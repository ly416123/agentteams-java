import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { ApiError } from '../../api/httpClient';
import {
  useTask,
  useTaskAction,
  useTaskEvents,
  useTaskExecution,
  useTaskProcessEvents,
  useTaskRuns,
  useTaskCheckpoints,
  useTaskRecovery,
} from '../../queries/useTaskQueries';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import { VersionConflictModal } from '../../components/VersionConflictModal';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';
import { labelSource, labelStatus, labelType } from '../../i18n/labels';
import { TaskExecutionObservability } from './TaskExecutionObservability';
import { TaskInfoPanel, mergeTaskTimelines } from './TaskInfoPanel';

type TaskActionName = 'queue' | 'cancel' | 'retry' | 'pause' | 'approve' | 'reject';
const actionLabels: Record<TaskActionName, string> = {
  queue: '排队执行',
  cancel: '取消任务',
  retry: '重试任务',
  pause: '暂停任务',
  approve: '批准任务',
  reject: '拒绝任务',
};
const actionImpacts: Record<TaskActionName, string> = {
  queue: '任务将进入执行队列，符合条件时会被 Worker 接收。',
  cancel: '将停止任务执行，并使后续 Worker 不再领取此任务。',
  retry: '将基于当前任务配置重新创建一次执行尝试。',
  pause: '任务将暂停调度，当前执行可能等待恢复。',
  approve: '批准后任务将可以继续进入执行流程。',
  reject: '任务将被拒绝，后续不会再进入执行流程。',
};

export function TaskDetailPage({ projectId, taskId }: { projectId: string; taskId: string }) {
  const task = useTask(projectId, taskId);
  const events = useTaskEvents(projectId, taskId);
  const execution = useTaskExecution(projectId, taskId);
  const runs = useTaskRuns(projectId, taskId);
  const recovery = useTaskRecovery(projectId, taskId);
  const action = useTaskAction(projectId, taskId);
  const [searchParams] = useSearchParams();
  const runId = searchParams.get('runId') || runs.data?.[0]?.id || '';
  const processEvents = useTaskProcessEvents(projectId, taskId, runId);
  const timelineItems = useMemo(
    () =>
      mergeTaskTimelines(
        (events.data || []).map((event) => ({
          id: event.id,
          title: labelType(event.type),
          description: event.message,
          time: event.createdAt,
          tone: event.phase?.toLowerCase(),
        })),
        processEvents.data || [],
      ),
    [events.data, processEvents.data],
  );
  const [conflict, setConflict] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  const [conflictAction, setConflictAction] = useState<TaskActionName | null>(null);
  const [conflictRefreshError, setConflictRefreshError] = useState<unknown>();
  const [confirmation, setConfirmation] = useState<TaskActionName | null>(null);
  if (task.isLoading)
    return (
      <div className="page">
        <div className="panel loading-block">加载任务…</div>
      </div>
    );
  if (task.isError || !task.data)
    return (
      <div className="page">
        <ErrorState error={task.error} onRetry={() => void task.refetch()} />
      </div>
    );
  const runAction = (name: TaskActionName, version = task.data.version) =>
    action.mutate(
      { action: name, expectedVersion: version },
      {
        onSuccess: () => setSubmitted(true),
        onError: (error) => {
          if (error instanceof ApiError && error.status === 409) {
            setConflictAction(name);
            setConflictRefreshError(undefined);
            setConflict(true);
          }
        },
      },
    );
  const retryLatestAction = async (name = conflictAction) => {
    if (!name) return;
    const latest = await task.refetch();
    if (latest.data) {
      setConflictRefreshError(undefined);
      runAction(name, latest.data.version);
    } else {
      setConflictRefreshError(latest.error || new Error('无法刷新任务状态'));
    }
  };
  return (
    <div className="page">
      <Link className="back-link" to={`/${projectId}/tasks`}>
        ← 返回任务
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">任务详情</p>
          <h1>{task.data.title}</h1>
          <p>{task.data.description}</p>
        </div>
        <div className="detail-actions">
          <StatusBadge phase={task.data.phase} />
          <span className="version-pill">版本 {task.data.version}</span>
        </div>
      </div>
      {submitted && <div className="toast toast--success">操作已提交</div>}
      <div className="action-bar">
        {task.data.phase === 'DRAFT' && (
          <button className="button button--primary" onClick={() => runAction('queue')}>
            排队执行
          </button>
        )}
        <button className="button button--danger" onClick={() => setConfirmation('cancel')}>
          取消任务
        </button>
        {(task.data.phase === 'QUEUED' || task.data.phase === 'PAUSED') && (
          <button className="button button--ghost" onClick={() => runAction('pause')}>
            {task.data.phase === 'PAUSED' ? '继续执行' : '暂停任务'}
          </button>
        )}
        {(task.data.phase === 'DRAFT' || task.data.phase === 'QUEUED' || task.data.phase === 'PAUSED') && (
          <>
            <button className="button button--ghost" onClick={() => runAction('approve')}>
              批准任务
            </button>
            <button className="button button--ghost" onClick={() => setConfirmation('reject')}>
              拒绝任务
            </button>
          </>
        )}
        {task.data.phase === 'FAILED' && (
          <button className="button button--ghost" onClick={() => runAction('retry')}>
            重试
          </button>
        )}
      </div>
      {action.isError && !conflict && (
        <ErrorState error={action.error} onRetry={() => void retryLatestAction()} />
      )}
      {Boolean(conflictRefreshError) && (
        <ErrorState error={conflictRefreshError} onRetry={() => void retryLatestAction()} />
      )}
      <div className="detail-layout">
        <div className="detail-layout__main">
          <section className="panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">生命周期</p>
                <h2>状态时间线</h2>
              </div>
            </div>
            {events.connectionState === 'reconnecting' && (
              <div className="info-box" role="status">
                事件流已断开，正在重连
                <button className="button button--ghost" onClick={() => void events.refetch()}>
                  手动重连
                </button>
              </div>
            )}
            {events.isError && events.connectionState !== 'reconnecting' && (
              <ErrorState error={events.error} onRetry={() => void events.refetch()} />
            )}
            {processEvents.connectionState === 'reconnecting' && (
              <div className="info-box" role="status">过程事件流已断开，正在重连</div>
            )}
            <Timeline items={timelineItems} />
          </section>
        </div>
        <aside className="detail-layout__aside">
          <TaskInfoPanel
            projectId={projectId}
            taskId={taskId}
            runId={runId}
            task={task.data}
            processEvents={processEvents.data || []}
          />
        </aside>
      </div>
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">尝试 / 分配 / 租约</p>
            <h2>执行尝试</h2>
          </div>
        </div>
        {execution.isLoading ? (
          <div className="loading-block">加载执行实体…</div>
        ) : execution.isError ? (
          <ErrorState error={execution.error} onRetry={() => void execution.refetch()} />
        ) : !execution.data?.length ? (
          <p className="muted-text">当前任务尚未产生执行尝试。</p>
        ) : (
          <div className="stack-list">
            {execution.data.map((item) => (
              <article className="stack-list__item" key={item.attempt.id}>
                <div>
                  <strong>{item.attempt.id}</strong>
                  <div className="muted-text">
                    {item.attempt.actor} · {labelSource(item.attempt.source)} · 版本{' '}
                    {item.attempt.version}
                  </div>
                </div>
                <StatusBadge phase={item.attempt.phase} />
                <div className="detail-list">
                  <span>
                    分配记录<strong>{item.assignment?.id || '未创建'}</strong>
                  </span>
                  <span>
                    租约<strong>{item.lease?.id || item.attempt.leaseId}</strong>
                  </span>
                  <span>
                    工作节点
                    <strong>{item.assignment?.agentId || item.lease?.agentId || '待分配'}</strong>
                  </span>
                  <span>
                    租约状态<strong>{labelStatus(item.lease?.status || 'UNKNOWN')}</strong>
                  </span>
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
      <TaskExecutionObservability
        projectId={projectId}
        taskId={taskId}
        runId={runId}
        processEvents={processEvents}
      />
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">运行结果</p>
            <h2>执行结果</h2>
          </div>
        </div>
        {runs.isLoading ? (
          <div className="loading-block">加载执行结果…</div>
        ) : runs.isError ? (
          <ErrorState error={runs.error} onRetry={() => void runs.refetch()} />
        ) : !runs.data?.length ? (
          <p className="muted-text">当前任务尚未产生运行记录。</p>
        ) : (
          <div className="stack-list">
            {runs.data.map((run) => (
              <article className="stack-list__item" key={run.id}>
                <div>
                  <strong>{labelStatus(run.status)}</strong>
                  <div className="muted-text">Run {run.id}</div>
                </div>
                <div>
                  {run.resultSummary || run.resultStatus || '暂无终态结果摘要'}
                  <RunCheckpoints projectId={projectId} taskId={taskId} runId={run.id} />
                </div>
                <Link
                  className="button button--ghost button--small"
                  to={`/${projectId}/tasks/${taskId}?runId=${run.id}`}
                >
                  查看运行
                </Link>
              </article>
            ))}
          </div>
        )}
      </section>
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">恢复策略</p>
            <h2>崩溃恢复</h2>
          </div>
        </div>
        {recovery.isLoading ? (
          <div className="loading-block">加载恢复状态…</div>
        ) : recovery.isError ? (
          <ErrorState error={recovery.error} onRetry={() => void recovery.refetch()} />
        ) : !recovery.data ? (
          <p className="muted-text">当前任务尚未发生租约恢复。</p>
        ) : (
          <div className="detail-list">
            <span>
              恢复状态
              <strong>
                {recovery.data.status === 'RECOVERY_REQUIRED' ? '需人工介入' : '等待重试'}
              </strong>
            </span>
            <span>
              恢复次数
              <strong>
                {recovery.data.recoveryCount} / {recovery.data.maxRecoveryAttempts}
              </strong>
            </span>
            <span>
              最近原因<strong>{recovery.data.lastReason || '未记录'}</strong>
            </span>
            <span>
              下次尝试<strong>{formatRecoveryTime(recovery.data.nextAttemptAt)}</strong>
            </span>
          </div>
        )}
      </section>
      <VersionConflictModal
        open={conflict}
        actionLabel={conflictAction ? actionLabels[conflictAction] : '继续操作'}
        description="任务在操作前已被其他请求更新，当前表单内容仍保留。"
        onCancel={() => setConflict(false)}
        onConfirm={async () => {
          if (!conflictAction) return;
          setConflict(false);
          await retryLatestAction(conflictAction);
        }}
      />
      <ActionConfirmModal
        open={Boolean(confirmation)}
        actionLabel={confirmation ? actionLabels[confirmation] : '操作'}
        impact={confirmation ? actionImpacts[confirmation] : ''}
        onCancel={() => setConfirmation(null)}
        onConfirm={() => {
          if (!confirmation) return;
          const name = confirmation;
          setConfirmation(null);
          runAction(name);
        }}
      />
    </div>
  );
}

function formatRecoveryTime(value?: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '不再自动重试';
}

function RunCheckpoints({
  projectId,
  taskId,
  runId,
}: {
  projectId: string;
  taskId: string;
  runId: string;
}) {
  const checkpoints = useTaskCheckpoints(projectId, taskId, runId);
  if (!checkpoints.data?.length) return null;
  const latest = checkpoints.data[0];
  return (
    <div className="muted-text">
      最近检查点：{latest.stepKey} · {latest.checkpointRef}
    </div>
  );
}
