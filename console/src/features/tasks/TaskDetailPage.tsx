import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError } from '../../api/httpClient';
import { useTask, useTaskAction, useTaskEvents } from '../../queries/useTaskQueries';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import { VersionConflictModal } from '../../components/VersionConflictModal';

export function TaskDetailPage({ projectId, taskId }: { projectId: string; taskId: string }) {
  const task = useTask(projectId, taskId);
  const events = useTaskEvents(projectId, taskId);
  const action = useTaskAction(projectId, taskId);
  const [conflict, setConflict] = useState(false);
  const [submitted, setSubmitted] = useState(false);
  if (task.isLoading)
    return (
      <div className="page">
        <div className="panel loading-block">加载任务…</div>
      </div>
    );
  if (task.isError || !task.data)
    return (
      <div className="page">
        <ErrorState error={task.error} />
      </div>
    );
  const runAction = (name: 'queue' | 'cancel' | 'retry' | 'pause' | 'approve' | 'reject') =>
    action.mutate(
      { action: name, expectedVersion: task.data.version },
      {
        onSuccess: () => setSubmitted(true),
        onError: (error) => {
          if (error instanceof ApiError && error.status === 409) setConflict(true);
        },
      },
    );
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
        <button className="button button--danger" onClick={() => runAction('cancel')}>
          取消任务
        </button>
        {task.data.phase === 'FAILED' && (
          <button className="button button--ghost" onClick={() => runAction('retry')}>
            重试
          </button>
        )}
      </div>
      <div className="content-grid">
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">LIFECYCLE</p>
              <h2>状态时间线</h2>
            </div>
          </div>
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
        actionLabel="取消任务"
        description="任务在操作前已被其他请求更新，当前表单内容仍保留。"
        onCancel={() => setConflict(false)}
        onConfirm={() => {
          setConflict(false);
          runAction('cancel');
        }}
      />
    </div>
  );
}
