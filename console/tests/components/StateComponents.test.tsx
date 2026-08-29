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
