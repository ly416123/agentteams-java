import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ErrorState } from '../../src/components/ErrorState';
import { StatusBadge } from '../../src/components/StatusBadge';
import { VersionConflictModal } from '../../src/components/VersionConflictModal';

describe('state components', () => {
  it('maps service phases to readable status badges', () => {
    render(<StatusBadge phase="RUNNING" />);

    expect(screen.getByText('执行中')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveAttribute('data-tone', 'info');
  });

  it.each([
    ['PROVISIONING', '准备中'],
    ['BUSY', '忙碌'],
    ['OFFLINE', '离线'],
    ['IN_PROGRESS', '进行中'],
    ['OPEN', '未处理'],
  ])('maps common %s status', (phase, label) => {
    render(<StatusBadge phase={phase} />);
    expect(screen.getByRole('status')).toHaveTextContent(label);
  });

  it('does not expose an unknown internal status code', () => {
    render(<StatusBadge phase="NEW_BACKEND_STATE" />);

    expect(screen.getByRole('status')).toHaveTextContent('未知状态');
    expect(screen.getByRole('status')).not.toHaveTextContent('NEW_BACKEND_STATE');
  });

  it('maps published lifecycle statuses to localized labels', () => {
    render(
      <div>
        <StatusBadge phase="REVIEWING" />
        <StatusBadge phase="PUBLISHED" />
        <StatusBadge phase="DEPRECATED" />
        <StatusBadge phase="ROLLED_BACK" />
      </div>,
    );
    expect(screen.getByText('审核中')).toBeInTheDocument();
    expect(screen.getByText('已发布')).toBeInTheDocument();
    expect(screen.getByText('已弃用')).toBeInTheDocument();
    expect(screen.getByText('已回滚')).toBeInTheDocument();
  });

  it('explains retryable dependency errors', () => {
    render(
      <ErrorState
        error={{ status: 503, message: 'Worker unavailable' }}
        onRetry={() => undefined}
      />,
    );

    expect(screen.getByText('依赖暂不可用')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });

  it('localizes typed error codes and keeps the correlation id visible', () => {
    render(
      <ErrorState
        error={{ code: 'FORBIDDEN', message: 'raw backend detail', correlationId: 'corr-1' }}
      />,
    );

    expect(screen.getByText('无权执行此操作')).toBeInTheDocument();
    expect(screen.queryByText('raw backend detail')).not.toBeInTheDocument();
    expect(screen.getByText('关联 ID：corr-1')).toBeInTheDocument();
  });

  it('offers explicit login and refresh actions for auth and conflict errors', () => {
    const onLogin = vi.fn();
    const onRetry = vi.fn();
    const { rerender } = render(
      <ErrorState error={{ status: 401, message: 'token expired' }} onLogin={onLogin} />,
    );

    expect(screen.getByText('登录已失效')).toBeInTheDocument();
    screen.getByRole('button', { name: '重新登录' }).click();
    expect(onLogin).toHaveBeenCalledOnce();

    rerender(<ErrorState error={{ status: 409, message: 'version changed' }} onRetry={onRetry} />);
    expect(screen.getByText('资源版本冲突')).toBeInTheDocument();
    screen.getByRole('button', { name: '刷新后重试' }).click();
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('keeps the pending action visible during version conflict confirmation', () => {
    const onConfirm = vi.fn();
    render(
      <VersionConflictModal
        open
        actionLabel="取消任务"
        description="任务版本已变化"
        onCancel={() => undefined}
        onConfirm={onConfirm}
      />,
    );

    expect(screen.getByText('任务版本已变化')).toBeInTheDocument();
    screen.getByRole('button', { name: '仍然取消任务' }).click();
    expect(onConfirm).toHaveBeenCalledOnce();
  });
});
