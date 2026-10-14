import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { listArtifactQualityEvents, type ArtifactQualityFilters } from '../../api/artifactQuality';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

export function ArtifactQualityLedgerPage({ projectId }: { projectId: string }) {
  const [draft, setDraft] = useState<ArtifactQualityFilters>({ page: 0, pageSize: 20 });
  const [startDate, setStartDate] = useState('');
  const [endDate, setEndDate] = useState('');
  const [dateError, setDateError] = useState('');
  const [filters, setFilters] = useState<ArtifactQualityFilters>({ page: 0, pageSize: 20 });
  const query = useQuery({
    queryKey: ['artifact-quality-events', projectId, filters],
    queryFn: () => listArtifactQualityEvents(projectId, filters),
    enabled: Boolean(projectId),
  });

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (startDate && endDate && startDate > endDate) {
      setDateError('结束日期不能早于开始日期');
      return;
    }
    setDateError('');
    setFilters({ ...draft, ...dateRange(startDate, endDate), page: 0, pageSize: filters.pageSize ?? 20 });
  }

  function resetFilters() {
    const reset = { page: 0, pageSize: 20 };
    setDraft(reset);
    setStartDate('');
    setEndDate('');
    setDateError('');
    setFilters(reset);
  }

  const pageCount = Math.max(1, Math.ceil((query.data?.total ?? 0) / (query.data?.pageSize ?? filters.pageSize ?? 20)));
  const currentPage = query.data?.page ?? filters.page ?? 0;

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">质量 / 台账</p>
          <h1>成果物质量优化台账</h1>
          <p className="page-subtitle">仅展示有来源记录的质量检查与优化事实；无记录表示暂无质量证据。</p>
        </div>
        <button className="button button--ghost" onClick={() => void query.refetch()}>刷新</button>
      </div>

      <form className="panel quality-filters" onSubmit={submit} aria-label="质量台账筛选">
        <label>Task ID<input value={draft.taskId ?? ''} onChange={(event) => setDraft({ ...draft, taskId: event.target.value || undefined })} /></label>
        <label>成果物<input value={draft.artifactName ?? ''} onChange={(event) => setDraft({ ...draft, artifactName: event.target.value || undefined })} /></label>
        <label>状态<select value={draft.status ?? ''} onChange={(event) => setDraft({ ...draft, status: event.target.value || undefined })}>
          <option value="">全部状态</option><option value="PASSED">通过</option><option value="FAILED">未通过</option><option value="INCONCLUSIVE">未知</option>
        </select></label>
        <label>问题类型<input value={draft.issueType ?? ''} onChange={(event) => setDraft({ ...draft, issueType: event.target.value || undefined })} /></label>
        <label>开始日期<input aria-label="开始日期" type="date" max={endDate || undefined} value={startDate} onChange={(event) => setStartDate(event.target.value)} /></label>
        <label>结束日期<input aria-label="结束日期" type="date" min={startDate || undefined} value={endDate} onChange={(event) => setEndDate(event.target.value)} /></label>
        {dateError && <p className="quality-date-error" role="alert">{dateError}</p>}
        <button className="button button--primary" type="submit">筛选</button>
        <button className="button button--ghost" type="button" onClick={resetFilters}>重置</button>
      </form>

      {query.isLoading ? <div className="panel loading-block">加载质量台账…</div> : query.isError ? (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      ) : query.data ? (
        <section className="panel quality-ledger" aria-label="质量事件">
          <div className="section-heading quality-ledger-heading"><div><h2>质量记录</h2><span className="muted-text">共 {query.data.total} 条</span></div>
            <label className="quality-page-size">每页条数<select aria-label="每页条数" value={filters.pageSize ?? 20} onChange={(event) => setFilters({ ...filters, page: 0, pageSize: Number(event.target.value) })}>
              <option value={20}>20 条</option><option value={50}>50 条</option><option value={100}>100 条</option>
            </select></label>
          </div>
          {query.data.events.length ? <div className="table-scroll"><table>
            <thead><tr><th>时间</th><th>Task / 成果物</th><th>检查 / 类型</th><th>结果</th><th>来源与可信度</th><th>事实摘要</th></tr></thead>
            <tbody>{query.data.events.map((item) => <tr key={item.id}>
              <td>{formatTime(item.occurredAt)}</td>
              <td>{item.taskId ? <Link to={`/${projectId}/tasks/${item.taskId}`}>{item.taskId}</Link> : <span className="muted-text">历史任务未关联</span>}
                <div className="muted-text">{item.artifactName ?? '未关联成果物'}</div></td>
              <td><strong>{item.categoryCode ?? item.eventType}</strong><div className="muted-text">{item.checkItem ?? '—'}</div></td>
              <td><span className={`quality-status quality-status--${item.status.toLowerCase()}`}>{statusLabel(item.status)}</span>
                {item.score != null && <div className="muted-text">得分 {item.score}</div>}</td>
              <td>{sourceLabel(item.sourceType)}<div className="muted-text">{confidenceLabel(item.confidence)}</div>
                {item.actionSource && <div className="muted-text">优化来源：{item.actionSource}</div>}</td>
              <td>{item.summary}</td>
            </tr>)}</tbody>
          </table></div> : <EmptyState title="暂无质量证据" description="当前 Project 与筛选条件下没有可核验的质量记录；这不代表成果物已通过检查。" />}
          <nav className="quality-pagination" aria-label="质量台账分页">
            <button className="button button--ghost" disabled={currentPage === 0} onClick={() => setFilters({ ...filters, page: Math.max(0, currentPage - 1) })}>上一页</button>
            <span>第 {currentPage + 1} / {pageCount} 页</span>
            <button className="button button--ghost" disabled={currentPage + 1 >= pageCount} onClick={() => setFilters({ ...filters, page: currentPage + 1 })}>下一页</button>
          </nav>
        </section>
      ) : <EmptyState title="暂无质量证据" description="当前 Project 与筛选条件下没有可核验的质量记录；这不代表成果物已通过检查。" />}
    </div>
  );
}

function dateRange(start: string, end: string) {
  const range: Pick<ArtifactQualityFilters, 'from' | 'to'> = {};
  if (start) range.from = localDateStart(start);
  if (end) range.to = localDateEnd(end);
  return range;
}
function localDateStart(value: string) {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day).toISOString();
}
function localDateEnd(value: string) {
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day, 23, 59, 59, 999).toISOString();
}
function formatTime(value: string) { return new Date(value).toLocaleString(); }
function sourceLabel(value: string) { return value === 'HISTORICAL' ? '历史记录' : value === 'LIVE' ? '系统记录' : '来源未知'; }
function confidenceLabel(value: string) { return value === 'HIGH' ? '高可信' : value === 'MEDIUM' ? '中可信' : '低可信'; }
function statusLabel(value: string) { return value === 'PASSED' ? '通过' : value === 'FAILED' ? '未通过' : value === 'INCONCLUSIVE' ? '未知' : value; }
