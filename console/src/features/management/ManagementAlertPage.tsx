import { useMutation, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import {
  listDashboardAlertEvents,
  listDashboardAlertRules,
  listDashboardAlerts,
  retryDashboardAlertEvent,
  updateDashboardAlertRule,
} from '../../api/alerts';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

export function ManagementAlertPage({ projectId }: { projectId: string }) {
  const current = useQuery({
    queryKey: ['management-alerts', projectId],
    queryFn: () => listDashboardAlerts(projectId),
    enabled: Boolean(projectId),
  });
  const retry = useMutation({
    mutationFn: (eventId: string) => retryDashboardAlertEvent(eventId),
    onSuccess: () => void events.refetch(),
  });
  const events = useQuery({
    queryKey: ['management-alert-events', projectId],
    queryFn: () => listDashboardAlertEvents(projectId),
    enabled: Boolean(projectId),
  });
  const rules = useQuery({
    queryKey: ['management-alert-rules', projectId],
    queryFn: () => listDashboardAlertRules(projectId),
    enabled: Boolean(projectId),
  });

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / ALERTS</p>
          <h1>告警中心</h1>
          <p className="page-subtitle">查看当前作用域的实时评估和告警投递历史。</p>
        </div>
        <button
          className="button button--ghost"
          onClick={() => {
            void current.refetch();
            void events.refetch();
            void rules.refetch();
          }}
        >
          刷新
        </button>
      </div>
      <section className="panel">
        <div className="section-heading">
          <h2>当前告警</h2>
          <span className="muted">{current.data?.length || 0} 条</span>
        </div>
        {current.isLoading ? (
          <div className="loading-block">加载中…</div>
        ) : current.isError ? (
          <ErrorState error={current.error} onRetry={() => void current.refetch()} />
        ) : !current.data?.length ? (
          <EmptyState title="一切正常" description="当前没有需要处理的告警。" />
        ) : (
          <div className="resource-list">
            {current.data.map((alert) => (
              <div className="resource-row" key={`${alert.rule}-${alert.message}`}>
                <div>
                  <strong>{alert.message}</strong>
                  <p className="muted-text">
                    {alert.rule} · actual {alert.actual}
                  </p>
                </div>
                <StatusBadge phase={alert.severity} />
              </div>
            ))}
          </div>
        )}
      </section>
      <section className="panel">
        <div className="section-heading">
          <h2>告警规则</h2>
          <span className="muted">当前 Tenant / Project 作用域</span>
        </div>
        {rules.isLoading ? (
          <div className="loading-block">加载中…</div>
        ) : rules.isError ? (
          <ErrorState error={rules.error} onRetry={() => void rules.refetch()} />
        ) : !rules.data?.length ? (
          <EmptyState title="暂无告警规则" description="当前作用域没有可配置的告警规则。" />
        ) : (
          <div className="resource-list">
            {rules.data.map((rule) => (
              <AlertRuleEditor key={rule.rule} projectId={projectId} rule={rule} />
            ))}
          </div>
        )}
      </section>
      <section className="panel">
        <div className="section-heading">
          <h2>投递历史</h2>
        </div>
        {events.isLoading ? (
          <div className="loading-block">加载中…</div>
        ) : events.isError ? (
          <ErrorState error={events.error} onRetry={() => void events.refetch()} />
        ) : !events.data?.length ? (
          <EmptyState title="暂无投递记录" description="当前作用域还没有持久化告警事件。" />
        ) : (
          <div className="table-wrap">
            <table className="resource-table">
              <thead>
                <tr>
                  <th>规则</th>
                  <th>状态</th>
                  <th>尝试次数</th>
                  <th>重试信息</th>
                  <th>时间</th>
                </tr>
              </thead>
              <tbody>
                {events.data.map((event) => (
                  <tr key={event.id}>
                    <td>{event.rule}</td>
                    <td>{event.status}</td>
                    <td>{event.attempts}</td>
                    <td>
                      {event.lastError ? <div>{event.lastError}</div> : null}
                      {event.nextAttemptAt ? (
                        <div className="muted-text">
                          下次重试 {new Date(event.nextAttemptAt).toLocaleString('zh-CN')}
                        </div>
                      ) : event.status === 'FAILED' ? (
                        <span className="muted-text">等待调度</span>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>
                      <div>{new Date(event.updatedAt).toLocaleString('zh-CN')}</div>
                      {event.status === 'FAILED' ? (
                        <button
                          className="button button--ghost"
                          onClick={() => retry.mutate(event.id)}
                          disabled={retry.isPending}
                        >
                          {retry.isPending ? '重试中…' : '立即重试'}
                        </button>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {retry.isError ? <p role="alert">重试失败，请刷新后重试。</p> : null}
          </div>
        )}
      </section>
    </div>
  );
}

function AlertRuleEditor({
  projectId,
  rule,
}: {
  projectId: string;
  rule: { rule: string; severity: string; threshold: number; enabled: boolean; version: number };
}) {
  const [threshold, setThreshold] = useState(String(rule.threshold));
  const [enabled, setEnabled] = useState(rule.enabled);
  const mutation = useMutation({
    mutationFn: () =>
      updateDashboardAlertRule(projectId, rule.rule, {
        severity: rule.severity,
        threshold: Number(threshold),
        enabled,
        expectedVersion: rule.version,
      }),
  });

  return (
    <div className="resource-row">
      <div>
        <strong>{rule.rule}</strong>
        <p className="muted-text">
          严重级别 {rule.severity} · version {rule.version}
        </p>
      </div>
      <label>
        {rule.rule} 阈值
        <input
          type="number"
          min="0"
          step="0.0001"
          value={threshold}
          onChange={(event) => setThreshold(event.target.value)}
        />
      </label>
      <label>
        启用
        <input
          type="checkbox"
          checked={enabled}
          onChange={(event) => setEnabled(event.target.checked)}
        />
      </label>
      <button
        className="button button--primary"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
      >
        {mutation.isPending ? '保存中…' : `保存 ${rule.rule} 规则`}
      </button>
      {mutation.isError ? <span role="alert">保存失败，请刷新后重试。</span> : null}
    </div>
  );
}
