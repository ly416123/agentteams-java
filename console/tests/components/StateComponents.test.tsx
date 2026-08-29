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
