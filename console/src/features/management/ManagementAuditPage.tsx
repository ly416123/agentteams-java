import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { listAuditEvents, type AuditFilters } from '../../api/audit';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

export function ManagementAuditPage({ projectId }: { projectId: string }) {
  const [draftFilters, setDraftFilters] = useState({
    actor: '',
    action: '',
    resourceType: '',
    resourceId: '',
  });
  const [appliedFilters, setAppliedFilters] = useState<AuditFilters>({});
  const [before, setBefore] = useState<string>();
  const [history, setHistory] = useState<Array<string | undefined>>([]);
  const query = useQuery({
    queryKey: ['management-audit-events', projectId, appliedFilters, before],
    queryFn: () => {
      const filters = before ? { ...appliedFilters, before } : appliedFilters;
      return Object.keys(filters).length === 0
        ? listAuditEvents(projectId)
        : listAuditEvents(projectId, filters);
    },
    enabled: Boolean(projectId),
  });

  function applyFilters() {
    const next = Object.fromEntries(
      Object.entries(draftFilters).filter(([, value]) => value.trim() !== ''),
    ) as AuditFilters;
    setAppliedFilters(next);
    setBefore(undefined);
    setHistory([]);
  }

  function nextPage() {
    const last = query.data?.at(-1);
    if (!last) return;
    setHistory([...history, before]);
    setBefore(last.occurredAt);
  }

  function previousPage() {
    if (history.length === 0) return;
    const nextHistory = [...history];
    const previousBefore = nextHistory.pop();
    setHistory(nextHistory);
    setBefore(previousBefore);
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">GOVERNANCE / AUDIT</p>
          <h1>审计事件</h1>
          <p className="page-subtitle">
            查看当前 Project 的操作审计元数据；敏感属性由后端统一脱敏。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void query.refetch()}>
          刷新
        </button>
      </div>
      <section className="panel" aria-label="审计筛选">
        <div className="section-heading">
          <div>
            <p className="eyebrow">FILTERS</p>
            <h2>筛选与分页</h2>
          </div>
          <span className="muted">结果仍按当前 Project 作用域过滤</span>
        </div>
        <div className="form-grid">
          <label>
            操作者
            <input
              value={draftFilters.actor}
              onChange={(event) => setDraftFilters({ ...draftFilters, actor: event.target.value })}
              placeholder="可选"
            />
          </label>
          <label>
            动作
            <input
              value={draftFilters.action}
              onChange={(event) => setDraftFilters({ ...draftFilters, action: event.target.value })}
              placeholder="可选"
            />
          </label>
          <label>
            资源类型
            <input
              value={draftFilters.resourceType}
              onChange={(event) =>
                setDraftFilters({ ...draftFilters, resourceType: event.target.value })
              }
              placeholder="可选"
            />
          </label>
          <label>
            资源 ID
            <input
              value={draftFilters.resourceId}
              onChange={(event) =>
                setDraftFilters({ ...draftFilters, resourceId: event.target.value })
              }
              placeholder="可选"
            />
          </label>
          <button className="button button--primary" onClick={applyFilters}>
            应用筛选
          </button>
        </div>
      </section>
      {query.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : !query.data?.length ? (
        <EmptyState title="暂无审计事件" description="当前作用域还没有可展示的操作记录。" />
      ) : (
        <section className="panel">
          <div className="table-wrap">
            <table className="resource-table">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>操作者</th>
                  <th>动作</th>
                  <th>资源</th>
                  <th>属性</th>
                </tr>
              </thead>
              <tbody>
                {query.data.map((event) => (
                  <tr key={event.id}>
                    <td>{new Date(event.occurredAt).toLocaleString('zh-CN')}</td>
                    <td>{event.actor}</td>
                    <td>{event.action}</td>
                    <td>
                      {event.resourceType} / {event.resourceId}
                    </td>
                    <td>
                      {Object.entries(event.attributes)
                        .map(([key, value]) => `${key}: ${value}`)
                        .join(' · ') || '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}
      {!query.isLoading && !query.isError && query.data?.length ? (
        <div className="button-row">
          <button
            className="button button--ghost"
            onClick={previousPage}
            disabled={!history.length}
          >
            上一页
          </button>
          <button
            className="button button--ghost"
            onClick={nextPage}
            disabled={query.data.length < 100}
          >
            下一页
          </button>
        </div>
      ) : null}
    </div>
  );
}
