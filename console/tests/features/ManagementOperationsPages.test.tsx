import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementAlertPage } from '../../src/features/management/ManagementAlertPage';
import { ManagementAuditPage } from '../../src/features/management/ManagementAuditPage';
import { ManagementBudgetPage } from '../../src/features/management/ManagementBudgetPage';

vi.mock('../../src/api/alerts', () => ({
  listDashboardAlerts: vi
    .fn()
    .mockResolvedValue([
      { rule: 'COST', severity: 'WARNING', actual: 12.5, message: 'estimated cost exceeded' },
    ]),
  listDashboardAlertEvents: vi.fn().mockResolvedValue([
    {
      id: 'event-1',
      rule: 'COST',
      severity: 'WARNING',
      actual: 12.5,
      status: 'SENT',
      attempts: 1,
      createdAt: '2026-09-01T08:00:00Z',
      updatedAt: '2026-09-01T08:01:00Z',
      message: 'estimated cost exceeded',
    },
    {
      id: 'event-2',
      rule: 'FAILURE_RATE',
      severity: 'CRITICAL',
      actual: 0.4,
      status: 'FAILED',
      attempts: 2,
      nextAttemptAt: '2026-09-01T08:05:00Z',
      lastError: 'receiver unavailable',
      createdAt: '2026-09-01T08:02:00Z',
      updatedAt: '2026-09-01T08:04:00Z',
      message: 'failure rate exceeded',
    },
  ]),
  listDashboardAlertRules: vi
    .fn()
    .mockResolvedValue([
      { rule: 'COST', severity: 'WARNING', threshold: 100, enabled: true, version: 3 },
    ]),
  updateDashboardAlertRule: vi.fn().mockResolvedValue({
    rule: 'COST',
    severity: 'WARNING',
    threshold: 25,
    enabled: true,
    version: 4,
  }),
  retryDashboardAlertEvent: vi.fn().mockResolvedValue({
    id: 'event-2',
    rule: 'FAILURE_RATE',
    severity: 'CRITICAL',
    actual: 0.4,
    status: 'SENT',
    attempts: 3,
    createdAt: '2026-09-01T08:02:00Z',
    updatedAt: '2026-09-01T08:06:00Z',
    message: 'failure rate exceeded',
  }),
}));

vi.mock('../../src/api/audit', () => ({
  listAuditEvents: vi.fn().mockResolvedValue([
    {
      id: 'audit-1',
      actor: 'user-1',
      action: 'TASK_CANCEL',
      resourceType: 'TASK',
      resourceId: 'task-1',
      attributes: { reason: 'operator-requested' },
      occurredAt: '2026-09-01T08:00:00Z',
    },
  ]),
}));

vi.mock('../../src/api/usage', async () => {
  const actual = await vi.importActual<typeof import('../../src/api/usage')>('../../src/api/usage');
  return {
    ...actual,
    listUsageBudgets: vi.fn().mockResolvedValue([
      {
        id: 'budget-1',
        currency: 'USD',
        periodSeconds: 86400,
        softThreshold: 10,
        hardThreshold: 20,
        forecastWindowSeconds: 3600,
        status: 'ACTIVE',
        version: 3,
      },
    ]),
    listUsageBudgetEvaluations: vi.fn().mockResolvedValue([
      {
        id: 'evaluation-1',
        policyId: 'budget-1',
        actualCost: 12.5,
        forecastCost: 15,
        status: 'SOFT_LIMIT',
        evaluatedAt: '2026-09-01T08:00:00Z',
      },
    ]),
    upsertUsageBudget: vi.fn().mockResolvedValue({
      id: 'budget-1',
      currency: 'USD',
      periodSeconds: 86400,
      softThreshold: 15,
      hardThreshold: 25,
      forecastWindowSeconds: 3600,
      status: 'ACTIVE',
      version: 4,
    }),
  };
});

function renderPage(page: React.ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter>{page}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('management operations pages', () => {
  it('renders budget evaluations for the current project', async () => {
    renderPage(<ManagementBudgetPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '预算与预测' })).toBeInTheDocument();
    expect(await screen.findByText('接近预算上限')).toBeInTheDocument();
    const { listUsageBudgets, listUsageBudgetEvaluations } = await import('../../src/api/usage');
    expect(listUsageBudgets).toHaveBeenCalledWith('project-1');
    expect(listUsageBudgetEvaluations).toHaveBeenCalledWith('budget-1');
  });

  it('writes a budget policy with the current version guard', async () => {
    renderPage(<ManagementBudgetPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '预算与预测' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: 'USD 预算' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('软阈值'), { target: { value: '15' } });
    fireEvent.change(screen.getByLabelText('硬阈值'), { target: { value: '25' } });
    fireEvent.click(screen.getByRole('button', { name: '保存预算' }));

    const { upsertUsageBudget } = await import('../../src/api/usage');
    await waitFor(() =>
      expect(upsertUsageBudget).toHaveBeenCalledWith('budget-1', {
        currency: 'USD',
        periodSeconds: 86400,
        softThreshold: 15,
        hardThreshold: 25,
        forecastWindowSeconds: 3600,
        status: 'ACTIVE',
        expectedVersion: 3,
      }),
    );
  });

  it('renders current alerts and durable delivery history', async () => {
    renderPage(<ManagementAlertPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '告警中心' })).toBeInTheDocument();
    expect(await screen.findByText('estimated cost exceeded')).toBeInTheDocument();
    expect(screen.getByText('SENT')).toBeInTheDocument();
    expect(screen.getByText('receiver unavailable')).toBeInTheDocument();
    expect(screen.getByText(/下次重试/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '立即重试' }));
    const { retryDashboardAlertEvent } = await import('../../src/api/alerts');
    await waitFor(() => expect(retryDashboardAlertEvent).toHaveBeenCalledWith('event-2'));
    const { listDashboardAlertEvents } = await import('../../src/api/alerts');
    expect(listDashboardAlertEvents).toHaveBeenCalledWith('project-1');
  });

  it('updates a project-scoped alert rule with the current version guard', async () => {
    renderPage(<ManagementAlertPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '告警中心' })).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: '告警规则' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('COST 阈值'), { target: { value: '25' } });
    fireEvent.click(screen.getByRole('button', { name: '保存 COST 规则' }));

    const { updateDashboardAlertRule } = await import('../../src/api/alerts');
    await waitFor(() =>
      expect(updateDashboardAlertRule).toHaveBeenCalledWith('project-1', 'COST', {
        severity: 'WARNING',
        threshold: 25,
        enabled: true,
        expectedVersion: 3,
      }),
    );
  });

  it('renders scoped, redacted audit event metadata', async () => {
    renderPage(<ManagementAuditPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '审计事件' })).toBeInTheDocument();
    expect(await screen.findByText('TASK_CANCEL')).toBeInTheDocument();
    expect(screen.getByText(/operator-requested/)).toBeInTheDocument();
    const { listAuditEvents } = await import('../../src/api/audit');
    expect(listAuditEvents).toHaveBeenCalledWith('project-1');
  });

  it('filters audit events through the scoped management query', async () => {
    renderPage(<ManagementAuditPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: '审计事件' })).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('操作者'), { target: { value: 'operator' } });
    fireEvent.change(screen.getByLabelText('动作'), { target: { value: 'worker.update' } });
    fireEvent.click(screen.getByRole('button', { name: '应用筛选' }));

    const { listAuditEvents } = await import('../../src/api/audit');
    await waitFor(() =>
      expect(listAuditEvents).toHaveBeenCalledWith('project-1', {
        actor: 'operator',
        action: 'worker.update',
      }),
    );
  });
});
