import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/httpClient';
import { useTask, useTaskAction, useTaskEvents } from '../../queries/useTaskQueries';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import { VersionConflictModal } from '../../components/VersionConflictModal';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';

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
  const action = useTaskAction(projectId, taskId);
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
        ← 返回 Tasks
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">TASK DETAIL</p>
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
      <div className="content-grid">
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">LIFECYCLE</p>
              <h2>状态时间线</h2>
            </div>
          </div>
          {events.isError && (
            <ErrorState error={events.error} onRetry={() => void events.refetch()} />
          )}
          <Timeline
            items={(events.data || []).map((event) => ({
              id: event.id,
              title: event.type,
              description: event.message,
              time: event.createdAt,
              tone: event.phase?.toLowerCase(),
            }))}
          />
        </section>
        <section className="panel">
          <p className="eyebrow">EXECUTION CONTEXT</p>
          <h2>执行信息</h2>
          <div className="detail-list">
            <span>
              Team<strong>{task.data.teamId || '未绑定'}</strong>
            </span>
            <span>
              Worker<strong>{task.data.workerId || '待分配'}</strong>
            </span>
            <span>
              优先级<strong>P{task.data.priority}</strong>
            </span>
            <span>
              创建时间<strong>{new Date(task.data.createdAt).toLocaleString('zh-CN')}</strong>
            </span>
          </div>
        </section>
      </div>
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
