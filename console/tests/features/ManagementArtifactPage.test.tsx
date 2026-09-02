import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementArtifactPage } from '../../src/features/management/ManagementArtifactPage';

vi.mock('../../src/api/artifacts', () => ({
  listArtifacts: vi.fn().mockResolvedValue([
    {
      id: 'artifact-1',
      taskId: 'task-1',
      attemptId: 'attempt-1',
      name: 'report.pdf',
      contentType: 'application/pdf',
      sizeBytes: 2048,
      sha256: 'abc123',
      status: 'COMPLETED',
      metadata: '{}',
      createdAt: '2026-08-29T03:00:00Z',
      version: 1,
    },
  ]),
  listArtifactRetentionPolicy: vi.fn().mockResolvedValue({
    projectId: 'p-1',
    configured: true,
    successfulTaskRetentionSeconds: 86400,
    failedTaskRetentionSeconds: 259200,
    temporaryUploadRetentionSeconds: 7200,
    legalHold: false,
    version: 2,
  }),
  updateArtifactRetentionPolicy: vi.fn().mockResolvedValue({
    projectId: 'p-1',
    configured: true,
    successfulTaskRetentionSeconds: 172800,
    failedTaskRetentionSeconds: 259200,
    temporaryUploadRetentionSeconds: 7200,
    legalHold: true,
    version: 3,
  }),
}));

describe('Management artifact page', () => {
  it('renders scoped artifact metadata without exposing a download shortcut', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ManagementArtifactPage projectId="p-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Artifacts' })).toBeInTheDocument();
    expect(await screen.findByText('report.pdf')).toBeInTheDocument();
    expect(screen.getByText('abc123')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: '下载' })).not.toBeInTheDocument();

    const { listArtifacts } = await import('../../src/api/artifacts');
    expect(listArtifacts).toHaveBeenCalledWith('p-1');
  });

  it('shows and updates the project retention policy with the current version', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={queryClient}>
        <MemoryRouter>
          <ManagementArtifactPage projectId="p-1" />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole('heading', { name: 'Artifact 保留策略' })).toBeInTheDocument();
    fireEvent.change(await screen.findByLabelText('成功任务保留（秒）'), {
      target: { value: '172800' },
    });
    fireEvent.click(await screen.findByRole('button', { name: '保存保留策略' }));

    const { updateArtifactRetentionPolicy } = await import('../../src/api/artifacts');
    await waitFor(() =>
      expect(updateArtifactRetentionPolicy).toHaveBeenCalledWith('p-1', {
        successfulTaskRetentionSeconds: 172800,
        failedTaskRetentionSeconds: 259200,
        temporaryUploadRetentionSeconds: 7200,
        legalHold: false,
        expectedVersion: 2,
      }),
    );
  });
});
