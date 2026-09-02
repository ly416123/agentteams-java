import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/httpClient';
import { ErrorState } from '../../components/ErrorState';
import { EmptyState } from '../../components/EmptyState';

type Props = { title: string; eyebrow: string; description: string; endpoint: string };

/** Read-only operational view shared by catalog domains until their domain-specific forms land. */
export function ManagementCatalogPage({ title, eyebrow, description, endpoint }: Props) {
  const query = useQuery({
    queryKey: ['management-catalog', endpoint],
    queryFn: () => apiClient.request<unknown>(endpoint),
  });
  const rows = Array.isArray(query.data) ? query.data : query.data ? [query.data] : [];
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">{eyebrow}</p>
          <h1>{title}</h1>
          <p className="page-subtitle">{description}</p>
        </div>
        <button className="button button--ghost" onClick={() => void query.refetch()}>
          刷新
        </button>
      </div>
      {query.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : rows.length === 0 ? (
        <EmptyState title="暂无数据" description="当前作用域没有可展示的资源。" />
      ) : (
        <div className="content-grid">
          {rows.map((row, index) => (
            <article
              className="panel"
              key={typeof row === 'object' && row !== null && 'id' in row ? String(row.id) : index}
            >
              <pre className="json-preview">{JSON.stringify(row, null, 2)}</pre>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
