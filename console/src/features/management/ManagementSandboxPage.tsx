import { useQuery } from '@tanstack/react-query';
import { listSandboxes } from '../../api/sandboxes';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { labelErrorCode } from '../../i18n/labels';
import { IdDisplay } from '../../components/IdDisplay';
import { formatDateTime, isExpired } from '../../utils/formatters';

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
        <EmptyState
          title="暂无沙箱记录"
          description="当前项目没有沙箱观测数据。执行任务并确认沙箱适配器已接入后，记录会显示在这里。"
        />
      ) : (
        <div className="content-grid">
          {sandboxes.data.map((sandbox) => (
            <article className="panel" key={sandbox.id}>
              <div className="panel-heading">
                <div>
                  <p className="eyebrow">{sandbox.profile}</p>
                  <h2>
                    <IdDisplay value={sandbox.id} />
                  </h2>
                </div>
                <StatusBadge phase={sandbox.status} />
              </div>
              <div className="detail-list">
                <span>
                  任务
                  <strong>
                    <IdDisplay value={sandbox.taskId} />
                  </strong>
                </span>
                <span>
                  尝试
                  <strong>
                    <IdDisplay value={sandbox.attemptId} />
                  </strong>
                </span>
                <span>
                  端点引用<strong>{sandbox.endpointRef || '—'}</strong>
                </span>
                <span>
                  版本<strong>{sandbox.version}</strong>
                </span>
                <span>
                  请求时间<strong>{formatDateTime(sandbox.requestedAt)}</strong>
                </span>
                <span>
                  到期时间
                  <strong>
                    {sandbox.expiresAt ? formatDateTime(sandbox.expiresAt) : '未设置到期时间'}
                  </strong>
                </span>
                <span>
                  最近观测<strong>{formatDateTime(sandbox.lastObservedAt)}</strong>
                </span>
              </div>
              {isExpired(sandbox.expiresAt) && (
                <p className="warning-text">
                  沙箱已过期 · 后端状态：
                  <StatusBadge phase={sandbox.status} />
                </p>
              )}
              {!sandbox.lastObservedAt && (
                <p className="muted-text">暂无状态观测记录，请检查工作节点或沙箱适配器连接。</p>
              )}
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
