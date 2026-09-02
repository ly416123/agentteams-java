import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import {
  exportUsageCsv,
  getUsageSummary,
  listUsageBudgets,
  type UsageBudget,
  type UsageFilters,
  type UsageGroup,
  type UsageGroupBy,
  type UsageProviderModel,
} from '../../api/usage';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

export function ManagementUsagePage({ projectId }: { projectId: string }) {
  const pageSize = 20;
  const [draftFilters, setDraftFilters] = useState<Required<UsageFilters>>({
    taskId: '',
    provider: '',
    model: '',
  });
  const [appliedFilters, setAppliedFilters] = useState<UsageFilters>({});
  const [pageOffset, setPageOffset] = useState(0);
  const [groupBy, setGroupBy] = useState<UsageGroupBy>('provider_model');
  const [exporting, setExporting] = useState(false);
  const [exportError, setExportError] = useState<unknown>();
  const summary = useQuery({
    queryKey: ['management-usage-summary', projectId, appliedFilters, groupBy, pageOffset],
    queryFn: () =>
      getUsageSummary(
        projectId,
        appliedFilters,
        groupBy === 'provider_model'
          ? { offset: pageOffset, limit: pageSize }
          : { offset: pageOffset, limit: pageSize, groupBy },
      ),
    enabled: Boolean(projectId),
  });
  const budgets = useQuery({
    queryKey: ['management-usage-budgets', projectId],
    queryFn: () => listUsageBudgets(projectId),
    enabled: Boolean(projectId),
  });

  function refresh() {
    void summary.refetch();
    void budgets.refetch();
  }

  function applyFilters() {
    const next = Object.fromEntries(
      Object.entries(draftFilters).filter(([, value]) => value.trim() !== ''),
    ) as UsageFilters;
    setAppliedFilters(next);
    setPageOffset(0);
  }

  async function downloadCsv() {
    setExporting(true);
    setExportError(undefined);
    try {
      const csv = await exportUsageCsv(projectId, appliedFilters);
      const createObjectUrl = URL.createObjectURL;
      if (typeof createObjectUrl !== 'function') return;
      const url = createObjectUrl.call(URL, new Blob([csv], { type: 'text/csv;charset=utf-8' }));
      const link = document.createElement('a');
      link.href = url;
      link.download = 'usage.csv';
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      setExportError(error);
    } finally {
      setExporting(false);
    }
  }

  const dimensionGrouping = summary.data?.groupBy && summary.data.groupBy !== 'provider_model';
  const usageRows: UsageTableRow[] = dimensionGrouping
    ? (summary.data?.groups ?? []).map(toDimensionRow)
    : (summary.data?.byProviderModel ?? []).map(toProviderModelRow);

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / USAGE</p>
          <h1>Usage 与费用</h1>
          <p className="page-subtitle">
            查看当前 Project
            的模型调用、估算成本和预算状态。成本可能包含未定价调用，不等同于最终账单。
          </p>
        </div>
        <div className="button-row">
          <button className="button button--ghost" onClick={refresh}>
            刷新数据
          </button>
          <button
            className="button button--ghost"
            onClick={() => void downloadCsv()}
            disabled={exporting}
          >
            {exporting ? '导出中…' : '导出 CSV'}
          </button>
        </div>
      </div>
      {exportError ? (
        <ErrorState
          error={exportError}
          title="无法导出 Usage"
          message="当前账号没有 usage:export 权限，无法导出。"
        />
      ) : null}

      <section className="panel usage-filters" aria-label="Usage 筛选">
        <div className="section-heading">
          <div>
            <p className="eyebrow">FILTERS</p>
            <h2>多维筛选</h2>
          </div>
          <span className="muted">数据库仍按当前 Project 作用域过滤</span>
        </div>
        <div className="form-grid">
          <label>
            Task ID
            <input
              value={draftFilters.taskId}
              onChange={(event) => setDraftFilters({ ...draftFilters, taskId: event.target.value })}
              placeholder="可选"
            />
          </label>
          <label>
            Provider
            <input
              value={draftFilters.provider}
              onChange={(event) =>
                setDraftFilters({ ...draftFilters, provider: event.target.value })
              }
              placeholder="可选"
            />
          </label>
          <label>
            Model
            <input
              value={draftFilters.model}
              onChange={(event) => setDraftFilters({ ...draftFilters, model: event.target.value })}
              placeholder="可选"
            />
          </label>
          <label>
            分组维度
            <select
              value={groupBy}
              onChange={(event) => {
                setGroupBy(event.target.value as UsageGroupBy);
                setPageOffset(0);
              }}
            >
              <option value="provider_model">Provider / Model</option>
              <option value="organization">Organization</option>
              <option value="tenant">Tenant</option>
              <option value="project">Project</option>
              <option value="team">Team</option>
              <option value="user">User</option>
              <option value="task">Task</option>
              <option value="worker">Worker</option>
            </select>
          </label>
          <button className="button button--primary" onClick={applyFilters}>
            应用筛选
          </button>
        </div>
      </section>

      {summary.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : summary.isError ? (
        <ErrorState error={summary.error} onRetry={refresh} />
      ) : summary.data ? (
        <>
          <section className="metric-grid" aria-label="使用量指标">
            <Metric label="调用次数" value={String(summary.data.calls)} />
            <Metric label="失败次数" value={String(summary.data.failures)} />
            <Metric label="估算成本" value={`$${summary.data.costUsd.toFixed(4)}`} />
            <Metric
              label="平均延迟"
              value={`${Math.round(summary.data.averageLatencyMillis)} ms`}
            />
          </section>
          <div className="content-grid">
            <section className="panel">
              <div className="section-heading">
                <div>
                  <p className="eyebrow">
                    {dimensionGrouping
                      ? `GROUP BY ${summary.data?.groupBy?.toUpperCase()}`
                      : 'PROVIDER / MODEL'}
                  </p>
                  <h2>{dimensionGrouping ? '分组调用明细' : '模型调用明细'}</h2>
                </div>
                <span className="muted">{formatRange(summary.data.from, summary.data.to)}</span>
              </div>
              {usageRows.length === 0 ? (
                <EmptyState
                  title="暂无模型调用"
                  description="当前时间范围内没有可展示的调用记录。"
                />
              ) : (
                <div className="table-wrap">
                  <table className="resource-table">
                    <thead>
                      <tr>
                        <th>{dimensionGrouping ? '分组' : 'Provider / Model'}</th>
                        <th>调用</th>
                        <th>失败</th>
                        <th>Tokens</th>
                        <th>估算成本</th>
                      </tr>
                    </thead>
                    <tbody>
                      {usageRows.map((item) => (
                        <tr key={item.label}>
                          <td>{item.label}</td>
                          <td>{item.calls}</td>
                          <td>{item.failures}</td>
                          <td>{item.promptTokens + item.completionTokens}</td>
                          <td>${item.costUsd.toFixed(4)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              <div className="button-row usage-pagination" aria-label="Usage 分页">
                <button
                  className="button button--ghost"
                  onClick={() => setPageOffset(Math.max(0, pageOffset - pageSize))}
                  disabled={pageOffset === 0}
                >
                  上一页
                </button>
                <span className="muted">第 {Math.floor(pageOffset / pageSize) + 1} 页</span>
                <button
                  className="button button--ghost"
                  onClick={() => setPageOffset(summary.data?.nextOffset ?? pageOffset)}
                  disabled={summary.data?.nextOffset === undefined}
                >
                  下一页
                </button>
              </div>
            </section>
            <BudgetPanel query={budgets} onRetry={() => void budgets.refetch()} />
          </div>
        </>
      ) : (
        <EmptyState title="暂无 Usage 数据" description="当前 Project 还没有可展示的使用量记录。" />
      )}
    </div>
  );
}

type UsageTableRow = Pick<
  UsageProviderModel,
  'calls' | 'failures' | 'promptTokens' | 'completionTokens' | 'costUsd'
> & {
  label: string;
};

function toDimensionRow(item: UsageGroup): UsageTableRow {
  return {
    label: item.dimensionValue ?? 'unknown',
    calls: item.calls,
    failures: item.failures,
    promptTokens: item.promptTokens,
    completionTokens: item.completionTokens,
    costUsd: item.costUsd,
  };
}

function toProviderModelRow(item: UsageProviderModel): UsageTableRow {
  return {
    label: `${item.provider} / ${item.model}`,
    calls: item.calls,
    failures: item.failures,
    promptTokens: item.promptTokens,
    completionTokens: item.completionTokens,
    costUsd: item.costUsd,
  };
}

function BudgetPanel({ query, onRetry }: { query: BudgetQuery; onRetry: () => void }) {
  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">BUDGET CONTROL</p>
          <h2>预算策略</h2>
        </div>
      </div>
      {query.isLoading ? (
        <div className="loading-block">加载中…</div>
      ) : query.isError ? (
        <ErrorState error={query.error} onRetry={onRetry} />
      ) : !query.data?.length ? (
        <EmptyState title="暂无预算策略" description="当前 Project 尚未配置预算阈值。" />
      ) : (
        <div className="resource-list">
          {query.data.map((budget) => (
            <div className="resource-row" key={budget.id}>
              <div>
                <strong>
                  {budget.currency} · {budget.status}
                </strong>
                <p className="muted-text">
                  软阈值 {budget.softThreshold} · 硬阈值 {budget.hardThreshold} · version{' '}
                  {budget.version}
                </p>
              </div>
              <StatusBadge phase={budget.status} />
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

type BudgetQuery = {
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  data?: UsageBudget[];
};

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="metric-card">
      <span className="metric-label">{label}</span>
      <strong className="metric-value">{value}</strong>
      <span className="metric-detail metric-detail--neutral">当前作用域</span>
    </div>
  );
}

function formatRange(from: string, to: string) {
  return `${new Date(from).toLocaleString('zh-CN')} — ${new Date(to).toLocaleString('zh-CN')}`;
}
