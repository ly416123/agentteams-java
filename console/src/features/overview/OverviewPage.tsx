import { useQuery } from '@tanstack/react-query';
import { getOverview } from '../../api/overview';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { queryKeys } from '../../queries/queryKeys';

export function OverviewPage({ projectId }: { projectId: string }) {
  const overview = useQuery({
    queryKey: queryKeys.overview(projectId),
    queryFn: () => getOverview(projectId),
    enabled: Boolean(projectId),
  });
  if (overview.isLoading)
    return (
      <div className="page">
        <div className="page-heading">
          <p className="eyebrow">PROJECT OVERVIEW</p>
          <h1>运行概览</h1>
        </div>
        <div className="metric-grid">
          {[1, 2, 3, 4].map((item) => (
            <div className="metric-card skeleton" key={item} />
          ))}
        </div>
      </div>
    );
  if (overview.isError)
    return (
      <div className="page">
        <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
      </div>
    );
  if (!overview.data)
    return (
      <div className="page">
        <EmptyState title="暂无概览数据" description="当前 Project 还没有可展示的运行数据。" />
      </div>
    );
  const { tasks, workers, teams, alerts, errors, metricsUnavailable } = overview.data;
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">PROJECT OVERVIEW</p>
          <h1>运行概览</h1>
          <p>掌握当前 Project 的资源健康与任务进展。</p>
        </div>
        <button className="button button--ghost" onClick={() => void overview.refetch()}>
          刷新数据
        </button>
      </div>
      <section className="metric-grid" aria-label="核心指标">
        <Metric
          label="任务总量"
          value={tasks.total}
          detail={`${formatCount(tasks.running)} 个执行中`}
          tone="info"
          error={errors?.tasks}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="已完成"
          value={tasks.succeeded}
          detail={`${formatCount(tasks.failed)} 个失败`}
          tone="success"
          error={errors?.tasks}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="可用 Worker"
          value={workers.ready}
          detail={`${formatCount(workers.connecting)} 个连接中`}
          tone="success"
          error={errors?.workers}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="活跃 Team"
          value={teams.active}
          detail={`共 ${formatCount(teams.total)} 个 Team`}
          tone="neutral"
          error={errors?.teams}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
      </section>
      <div className="content-grid">
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">HEALTH SIGNALS</p>
              <h2>当前告警</h2>
            </div>
            <span className="muted">{alerts.length} 条</span>
          </div>
          {errors?.alerts ? (
            <ErrorState error={errors.alerts} onRetry={() => void overview.refetch()} />
          ) : alerts.length ? (
            <div className="alert-list">
              {alerts.map((alert) => (
                <div className="alert-row" key={alert.id}>
                  <StatusBadge phase={alert.severity} />
                  <div>
                    <strong>{alert.message}</strong>
                    <time>{new Date(alert.createdAt).toLocaleString('zh-CN')}</time>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="一切正常" description="当前没有需要处理的告警。" />
          )}
          {errors?.summary !== undefined && (
            <ErrorState error={errors.summary} onRetry={() => void overview.refetch()} />
          )}
        </section>
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">TASK FLOW</p>
              <h2>任务分布</h2>
            </div>
          </div>
          <div className="distribution">
            <Distribution label="排队中" value={tasks.queued} total={tasks.total} />
            <Distribution label="执行中" value={tasks.running} total={tasks.total} />
            <Distribution label="失败" value={tasks.failed} total={tasks.total} />
          </div>
        </section>
      </div>
    </div>
  );
}

function Metric({
  label,
  value,
  detail,
  tone,
  error,
  unavailable,
  onRetry,
}: {
  label: string;
  value: number | null;
  detail: string;
  tone: string;
  error?: unknown;
  unavailable?: boolean;
  onRetry: () => void;
}) {
  return (
    <div className="metric-card">
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{unavailable ? '—' : formatCount(value)}</strong>
      <span className={`metric-detail metric-detail--${tone}`}>
        {unavailable ? '后端尚未提供聚合统计' : detail}
      </span>
      {error !== undefined && <ErrorState error={error} onRetry={onRetry} />}
    </div>
  );
}
function Distribution({
  label,
  value,
  total,
}: {
  label: string;
  value: number | null;
  total: number | null;
}) {
  const percent = total && value !== null ? Math.round((value / total) * 100) : 0;
  return (
    <div className="distribution-row">
      <div>
        <span>{label}</span>
        <strong>{formatCount(value)}</strong>
      </div>
      <div className="progress">
        <span style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function formatCount(value: number | null) {
  return value === null ? '—' : value;
}
