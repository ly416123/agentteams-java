import { useMutation, useQueries, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import {
  listUsageBudgetEvaluations,
  listUsageBudgets,
  upsertUsageBudget,
  type UsageBudget,
} from '../../api/usage';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

export function ManagementBudgetPage({ projectId }: { projectId: string }) {
  const budgets = useQuery({
    queryKey: ['management-budgets', projectId],
    queryFn: () => listUsageBudgets(projectId),
    enabled: Boolean(projectId),
  });
  const evaluations = useQueries({
    queries: (budgets.data || []).map((budget) => ({
      queryKey: ['management-budget-evaluations', budget.id],
      queryFn: () => listUsageBudgetEvaluations(budget.id),
    })),
  });

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / BUDGETS</p>
          <h1>预算与预测</h1>
          <p className="page-subtitle">查看预算阈值和最近一次评估结果；估算值不等同于最终账单。</p>
        </div>
        <button className="button button--ghost" onClick={() => void budgets.refetch()}>
          刷新
        </button>
      </div>
      {budgets.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : budgets.isError ? (
        <ErrorState error={budgets.error} onRetry={() => void budgets.refetch()} />
      ) : !budgets.data?.length ? (
        <EmptyState title="暂无预算策略" description="当前 Project 尚未配置预算阈值。" />
      ) : (
        <div className="content-grid">
          {budgets.data.map((budget, index) => {
            const evaluation = evaluations[index]?.data?.[0];
            return (
              <BudgetCard
                key={budget.id}
                budget={budget}
                evaluation={evaluation}
                onSaved={() => void budgets.refetch()}
              />
            );
          })}
        </div>
      )}
    </div>
  );
}

function BudgetCard({
  budget,
  evaluation,
  onSaved,
}: {
  budget: UsageBudget;
  evaluation?: {
    actualCost: number | null;
    forecastCost: number | null;
    status: string;
  };
  onSaved: () => void;
}) {
  const [softThreshold, setSoftThreshold] = useState(String(budget.softThreshold));
  const [hardThreshold, setHardThreshold] = useState(String(budget.hardThreshold));
  const mutation = useMutation({
    mutationFn: () =>
      upsertUsageBudget(budget.id, {
        currency: budget.currency,
        periodSeconds: budget.periodSeconds,
        softThreshold: Number(softThreshold),
        hardThreshold: Number(hardThreshold),
        forecastWindowSeconds: budget.forecastWindowSeconds,
        status: budget.status,
        expectedVersion: budget.version,
      }),
    onSuccess: onSaved,
  });

  return (
    <article className="panel">
      <div className="panel-heading">
        <div>
          <h2>{budget.currency} 预算</h2>
          <p className="muted-text">{budget.id}</p>
        </div>
        <StatusBadge phase={evaluation?.status || budget.status} />
      </div>
      <p>
        策略状态：{budget.status} · version {budget.version}
      </p>
      <div className="form-grid">
        <label>
          软阈值
          <input
            type="number"
            min="0"
            step="0.0001"
            value={softThreshold}
            onChange={(event) => setSoftThreshold(event.target.value)}
          />
        </label>
        <label>
          硬阈值
          <input
            type="number"
            min="0"
            step="0.0001"
            value={hardThreshold}
            onChange={(event) => setHardThreshold(event.target.value)}
          />
        </label>
      </div>
      <p className="muted-text">
        预算周期 {budget.periodSeconds}s · 预测窗口 {budget.forecastWindowSeconds}s
      </p>
      <button
        className="button button--primary"
        onClick={() => mutation.mutate()}
        disabled={mutation.isPending}
      >
        {mutation.isPending ? '保存中…' : '保存预算'}
      </button>
      {mutation.isError ? <p role="alert">预算保存失败，请刷新后重试。</p> : null}
      {evaluation ? (
        <div className="usage-summary">
          <div>
            <span>实际成本</span>
            <strong>{formatMoney(evaluation.actualCost)}</strong>
          </div>
          <div>
            <span>预测成本</span>
            <strong>{formatMoney(evaluation.forecastCost)}</strong>
          </div>
        </div>
      ) : (
        <p className="muted-text">暂无评估结果</p>
      )}
    </article>
  );
}

function formatMoney(value: number | null | undefined) {
  return value === null || value === undefined ? '—' : `$${value.toFixed(4)}`;
}
