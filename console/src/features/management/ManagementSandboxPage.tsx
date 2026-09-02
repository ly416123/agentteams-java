import { useQuery } from '@tanstack/react-query';
import { listSandboxes } from '../../api/sandboxes';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

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
          <p className="eyebrow">OPERATIONS / SANDBOXES</p>
          <h1>Sandbox 运维</h1>
          <p className="page-subtitle">
            查看 Attempt 级 Sandbox 的 profile、生命周期、回收时间和脱敏失败信息。
          </p>
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
        <EmptyState title="暂无 Sandbox" description="当前作用域还没有可展示的 Sandbox。" />
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
                  Task<strong>{sandbox.taskId}</strong>
                </span>
                <span>
                  Attempt<strong>{sandbox.attemptId}</strong>
                </span>
                <span>
                  Endpoint Ref<strong>{sandbox.endpointRef || '—'}</strong>
                </span>
                <span>
                  Version<strong>{sandbox.version}</strong>
                </span>
                <span>
                  Requested<strong>{formatDate(sandbox.requestedAt)}</strong>
                </span>
                <span>
                  Expires<strong>{formatDate(sandbox.expiresAt)}</strong>
                </span>
                <span>
                  Last observed<strong>{formatDate(sandbox.lastObservedAt)}</strong>
                </span>
              </div>
              {sandbox.failureCode && (
                <p className="error-text">
                  {sandbox.failureCode}: {sandbox.redactedFailureMessage || '无更多信息'}
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
