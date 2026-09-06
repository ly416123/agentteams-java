import { useQuery } from '@tanstack/react-query';
import { listSandboxes } from '../../api/sandboxes';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { labelErrorCode } from '../../i18n/labels';

export function ManagementSandboxPage({ projectId }: { projectId: string }) {
  const sandboxes = useQuery({
    queryKey: ['management-sandboxes', projectId],
    queryFn: () => listSandboxes(projectId),
    enabled: Boolean(projectId),
  });
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">运维 / 沙箱</p>
          <h1>沙箱运维</h1>
          <p className="page-subtitle">查看尝试级沙箱的配置、生命周期、回收时间和脱敏失败信息。</p>
        </div>
        <button className="button button--ghost" onClick={() => void sandboxes.refetch()}>
          刷新
        </button>
      </div>
      {sandboxes.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : sandboxes.isError ? (
        <ErrorState error={sandboxes.error} onRetry={() => void sandboxes.refetch()} />
      ) : !sandboxes.data?.length ? (
        <EmptyState title="暂无沙箱" description="当前作用域还没有可展示的沙箱。" />
      ) : (
        <div className="content-grid">
          {sandboxes.data.map((sandbox) => (
            <article className="panel" key={sandbox.id}>
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">{sandbox.profile}</p>
                  <h2>{sandbox.id}</h2>
                </div>
                <StatusBadge phase={sandbox.status} />
              </div>
              <div className="detail-list">
                <span>
                  任务<strong>{sandbox.taskId}</strong>
                </span>
                <span>
                  尝试<strong>{sandbox.attemptId}</strong>
                </span>
                <span>
                  端点引用<strong>{sandbox.endpointRef || '—'}</strong>
                </span>
                <span>
                  版本<strong>{sandbox.version}</strong>
                </span>
                <span>
                  请求时间<strong>{formatDate(sandbox.requestedAt)}</strong>
                </span>
                <span>
                  到期时间<strong>{formatDate(sandbox.expiresAt)}</strong>
                </span>
                <span>
                  最近观测<strong>{formatDate(sandbox.lastObservedAt)}</strong>
                </span>
              </div>
              {sandbox.failureCode && (
                <p className="error-text">
                  {labelErrorCode(sandbox.failureCode)}:{' '}
                  {sandbox.redactedFailureMessage || '无更多信息'}
                </p>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

function formatDate(value: string | null) {
  return value ? new Date(value).toLocaleString('zh-CN') : '—';
}
