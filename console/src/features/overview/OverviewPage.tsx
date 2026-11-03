import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { getOverview } from '../../api/overview';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { formatDateTime } from '../../utils/formatters';
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
          <p className="eyebrow">项目概览</p>
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
        <EmptyState title="暂无概览数据" description="当前项目还没有可展示的运行数据。" />
      </div>
    );
  const { tasks, workers, teams, alerts, errors, usage, metricsUnavailable } = overview.data;
  const healthMessage = errors?.alerts
    ? '告警信息暂不可用，请打开告警页检查。'
    : alerts.length
      ? `当前有 ${alerts.length} 条告警需要处理。`
      : '当前没有待处理告警，最近一次同步已完成。';
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">项目概览</p>
          <h1>运行概览</h1>
          <p>掌握当前项目的资源健康与任务进展。</p>
        </div>
        <div className="overview-actions">
          <Link className="button button--primary" to={`/${projectId}/tasks/new`}>
            ＋ 创建任务
          </Link>
          <button className="button button--ghost" onClick={() => void overview.refetch()}>
            刷新数据
          </button>
        </div>
      </div>
      <section
        className={`health-strip${alerts.length || errors?.alerts ? ' health-strip--warning' : ''}`}
        aria-live="polite"
        aria-label="项目健康状态"
      >
        <span className="health-strip__icon" aria-hidden="true">
          {alerts.length ? '!' : '✓'}
        </span>
        <div>
          <strong>
            {errors?.alerts ? '健康状态暂不可用' : alerts.length ? '需要关注告警' : '运行状态正常'}
          </strong>
          <p>{healthMessage}</p>
        </div>
        <Link to={`/${projectId}/alerts`}>
          查看告警 <span aria-hidden="true">→</span>
        </Link>
      </section>
      <section className="metric-grid" aria-label="核心指标">
        <Metric
          label="任务总量"
          value={tasks.total}
          detail={`${formatCount(tasks.running)} 个执行中`}
          tone="info"
          linkTo={`/${projectId}/tasks`}
          linkLabel="查看任务"
          error={errors?.resources ?? errors?.tasks}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="已完成"
          value={tasks.succeeded}
          detail={`${formatCount(tasks.failed)} 个失败`}
          tone="success"
          linkTo={`/${projectId}/tasks`}
          linkLabel="查看任务"
          error={errors?.resources ?? errors?.tasks}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="可用工作节点"
          value={workers.ready}
          detail={`${formatCount(workers.connecting)} 个连接中`}
          tone="success"
          linkTo={`/${projectId}/workers`}
          linkLabel="查看节点"
          error={errors?.resources}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
        <Metric
          label="活跃团队"
          value={teams.active}
          detail={`共 ${formatCount(teams.total)} 个团队`}
          tone="neutral"
          linkTo={`/${projectId}/teams`}
          linkLabel="查看团队"
          error={errors?.resources}
          unavailable={metricsUnavailable}
          onRetry={() => void overview.refetch()}
        />
      </section>
      <div className="content-grid">
        <section className="panel overview-panel--alerts">
          <div className="section-heading">
            <div>
              <p className="eyebrow">健康信号</p>
              <h2>当前告警</h2>
            </div>
            <Link className="panel-action" to={`/${projectId}/alerts`}>
              {alerts.length} 条 · 查看全部 <span aria-hidden="true">→</span>
            </Link>
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
                    <time>{formatDateTime(alert.createdAt)}</time>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState title="一切正常" description="当前没有需要处理的告警。" />
          )}
        </section>
        <section className="panel overview-panel--usage">
          <div className="section-heading">
            <div>
              <p className="eyebrow">模型用量</p>
              <h2>模型调用</h2>
            </div>
            <Link className="panel-action" to={`/${projectId}/usage`}>
              查看详细用量 <span aria-hidden="true">→</span>
            </Link>
          </div>
          {errors?.summary ? (
            <ErrorState error={errors.summary} onRetry={() => void overview.refetch()} />
          ) : usage ? (
            <div className="usage-summary">
              <div>
                <span>调用次数</span>
                <strong>{usage.calls}</strong>
              </div>
              <div>
                <span>失败次数</span>
                <strong>{usage.failures}</strong>
              </div>
              <div>
                <span>估算成本</span>
                <strong>${usage.estimatedCostUsd.toFixed(4)}</strong>
              </div>
            </div>
          ) : (
            <EmptyState title="暂无调用数据" description="当前时间范围内没有模型调用记录。" />
          )}
        </section>
        <section className="panel overview-panel--distribution">
          <div className="section-heading">
            <div>
              <p className="eyebrow">任务流程</p>
              <h2>任务分布</h2>
            </div>
          </div>
          <div className="distribution">
            <Distribution label="已完成" value={tasks.succeeded} total={tasks.total} />
            <Distribution label="排队中" value={tasks.queued} total={tasks.total} tone="warning" />
            <Distribution label="执行中" value={tasks.running} total={tasks.total} tone="info" />
            <Distribution label="失败" value={tasks.failed} total={tasks.total} tone="danger" />
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
  linkTo,
  linkLabel,
  error,
  unavailable,
  onRetry,
}: {
  label: string;
  value: number | null;
  detail: string;
  tone: string;
  linkTo: string;
  linkLabel: string;
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
      <Link className="metric-card__link" to={linkTo}>
        {linkLabel} <span aria-hidden="true">→</span>
      </Link>
      {error !== undefined && <ErrorState error={error} onRetry={onRetry} />}
    </div>
  );
}
function Distribution({
  label,
  value,
  total,
  tone = 'success',
}: {
  label: string;
  value: number | null;
  total: number | null;
  tone?: 'success' | 'warning' | 'info' | 'danger';
}) {
  const percent = total && value !== null ? Math.round((value / total) * 100) : 0;
  return (
    <div className="distribution-row">
      <div>
        <span>{label}</span>
        <strong>{formatCount(value)}</strong>
      </div>
      <div className={`progress progress--${tone}`}>
        <span style={{ width: `${percent}%` }} />
      </div>
    </div>
  );
}

function formatCount(value: number | null) {
  return value === null ? '—' : value;
}
