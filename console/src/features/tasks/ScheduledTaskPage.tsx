import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  cancelScheduledTaskRun,
  listScheduledTaskRuns,
  listScheduledTasks,
  updateScheduledTask,
  type ScheduledTask,
  type ScheduledTaskRun,
  type ScheduleScope,
} from '../../api/scheduledTasks';
import { listManagementOrganizations } from '../../api/management';
import { useProjects } from '../../queries/useProjectQueries';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

export function ScheduledTaskPage({ projectId }: { projectId: string }) {
  const projects = useProjects();
  const [organizationId, setOrganizationId] = useState('');
  const [schedules, setSchedules] = useState<ScheduledTask[]>([]);
  const [selectedId, setSelectedId] = useState('');
  const [runs, setRuns] = useState<ScheduledTaskRun[]>([]);
  const [error, setError] = useState<unknown>();
  const [busy, setBusy] = useState(false);
  const project = projects.data?.items.find((item) => item.id === projectId);
  const scope: ScheduleScope | undefined = useMemo(
    () =>
      organizationId && project
        ? { organizationId, tenantId: project.tenantId, projectId: project.name }
        : undefined,
    [organizationId, project, projectId],
  );

  useEffect(() => {
    let active = true;
    void listManagementOrganizations()
      .then((items) => active && setOrganizationId(items[0]?.id || ''))
      .catch((nextError) => active && setError(nextError));
    return () => {
      active = false;
    };
  }, []);

  useEffect(() => {
    if (!scope) return;
    let active = true;
    setError(undefined);
    void listScheduledTasks(scope)
      .then((items) => {
        if (!active) return;
        setSchedules(items);
        setSelectedId((current) => current || items[0]?.id || '');
      })
      .catch((nextError) => active && setError(nextError));
    return () => {
      active = false;
    };
  }, [scope]);

  useEffect(() => {
    if (!scope || !selectedId) return;
    let active = true;
    void listScheduledTaskRuns(selectedId, scope)
      .then((items) => active && setRuns(items))
      .catch((nextError) => active && setError(nextError));
    return () => {
      active = false;
    };
  }, [scope, selectedId]);

  if (projects.isError || error) {
    return (
      <div className="page">
        <ErrorState error={error || projects.error} />
      </div>
    );
  }
  if (projects.isLoading || !scope) {
    return (
      <div className="page">
        <div className="panel loading-block">加载定时任务…</div>
      </div>
    );
  }
  const selected = schedules.find((item) => item.id === selectedId);
  const runAction = async (action: 'pause' | 'resume') => {
    if (!selected || !scope) return;
    setBusy(true);
    try {
      const updated = await updateScheduledTask(selected.id, action, scope);
      setSchedules((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (nextError) {
      setError(nextError);
    } finally {
      setBusy(false);
    }
  };
  const cancelRun = async (run: ScheduledTaskRun) => {
    if (!scope || !selected) return;
    setBusy(true);
    try {
      const updated = await cancelScheduledTaskRun(selected.id, run.id, scope);
      setRuns((current) => current.map((item) => (item.id === updated.id ? updated : item)));
    } catch (nextError) {
      setError(nextError);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="page">
      <div className="detail-heading">
        <div>
          <p className="eyebrow">SCHEDULED TASKS</p>
          <h1>定时任务</h1>
          <p>查看调度定义、每次执行结果，并区分暂停调度与取消当前运行。</p>
        </div>
        <span className="version-pill">组织 {organizationId}</span>
      </div>
      {Boolean(error) && <ErrorState error={error} />}
      {!schedules.length ? (
        <div className="panel">
          <p className="muted-text">当前 Project 暂无定时任务。</p>
        </div>
      ) : (
        <div className="content-grid">
          <section className="panel">
            <div className="section-heading">
              <h2>调度定义</h2>
            </div>
            <div className="stack-list">
              {schedules.map((item) => (
                <button
                  className={`stack-list__item schedule-card ${item.id === selectedId ? 'schedule-card--selected' : ''}`}
                  key={item.id}
                  onClick={() => setSelectedId(item.id)}
                >
                  <div>
                    <strong>{item.title || item.name}</strong>
                    <div className="muted-text">
                      {item.cronExpression} · {item.timeZone}
                    </div>
                  </div>
                  <StatusBadge phase={item.enabled ? 'RUNNING' : 'PAUSED'} />
                </button>
              ))}
            </div>
          </section>
          <section className="panel">
            {selected ? (
              <>
                <div className="section-heading">
                  <div>
                    <p className="eyebrow">SCHEDULE RUNS</p>
                    <h2>{selected.title || selected.name}</h2>
                  </div>
                  <button
                    className="button button--ghost"
                    disabled={busy}
                    onClick={() => void runAction(selected.enabled ? 'pause' : 'resume')}
                  >
                    {selected.enabled ? '暂停调度' : '恢复调度'}
                  </button>
                </div>
                {!runs.length ? (
                  <p className="muted-text">尚未触发执行。</p>
                ) : (
                  <div className="stack-list">
                    {runs.map((run) => (
                      <article className="stack-list__item" key={run.id}>
                        <div>
                          <strong>{run.status}</strong>
                          <div className="muted-text">
                            {new Date(run.occurrenceAt).toLocaleString('zh-CN')}
                          </div>
                        </div>
                        <div>
                          {run.resultSummary || run.resultStatus || run.taskPhase || '等待结果'}
                        </div>
                        <div className="form-actions">
                          <Link
                            className="button button--ghost button--small"
                            to={`/${projectId}/tasks/${run.taskId}`}
                          >
                            查看任务
                          </Link>
                          {['TRIGGERED', 'RUNNING', 'RECOVERY_REQUIRED'].includes(run.status) && (
                            <button
                              className="button button--danger button--small"
                              disabled={busy}
                              onClick={() => void cancelRun(run)}
                            >
                              终止本次执行
                            </button>
                          )}
                        </div>
                      </article>
                    ))}
                  </div>
                )}
              </>
            ) : (
              <p className="muted-text">选择一个调度定义。</p>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
