import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../../api/httpClient';
import { ErrorState } from '../../components/ErrorState';
import { EmptyState } from '../../components/EmptyState';
import { IdDisplay } from '../../components/IdDisplay';
import { RawDataDisclosure } from '../../components/RawDataDisclosure';
import { labelStatus, labelType } from '../../i18n/labels';

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
        <EmptyState
          title="暂无数据"
          description="接口已返回，但当前作用域没有资源记录。若预期有数据，请核对项目范围、访问权限和数据来源。"
        />
      ) : (
        <div className="content-grid">
          {rows.map((row, index) => (
            <article
              className="panel"
              key={typeof row === 'object' && row !== null && 'id' in row ? String(row.id) : index}
            >
              {row && typeof row === 'object' ? (
                <CatalogRecord value={row as Record<string, unknown>} />
              ) : (
                <p>{String(row)}</p>
              )}
              <RawDataDisclosure value={row} />
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

function CatalogRecord({ value }: { value: Record<string, unknown> }) {
  const title = [value.displayName, value.name, value.title, value.label].find(
    (item) => typeof item === 'string' && item.trim(),
  );
  const entries = Object.entries(value).filter(([, item]) => item !== null && item !== undefined);
  const humanizedKey = (key: string) =>
    key.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[_-]/g, ' ');
  const humanizedValue = (key: string, item: unknown) => {
    if (typeof item === 'boolean') return item ? '是' : '否';
    if (typeof item === 'string' && /status|phase|state|type|role|visibility/i.test(key)) {
      return /status|phase|state/i.test(key) ? labelStatus(item) : labelType(item);
    }
    if (typeof item === 'string' && /id$/i.test(key)) return <IdDisplay value={item} />;
    if (typeof item === 'object') return '含有详细信息';
    return String(item);
  };

  return (
    <>
      <h2>{typeof title === 'string' ? title : '资源记录'}</h2>
      <dl className="catalog-record__fields">
        {entries
          .filter(
            ([key]) => !/^id$/i.test(key) && !/json|config|payload|secret|credential/i.test(key),
          )
          .slice(0, 8)
          .map(([key, item]) => (
            <div key={key}>
              <dt>{humanizedKey(key)}</dt>
              <dd>{humanizedValue(key, item)}</dd>
            </div>
          ))}
      </dl>
      {typeof value.id === 'string' && <IdDisplay value={value.id} />}
    </>
  );
}
