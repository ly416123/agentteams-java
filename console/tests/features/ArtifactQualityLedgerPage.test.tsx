import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { listArtifactQualityEvents } from '../../src/api/artifactQuality';
import { ArtifactQualityLedgerPage } from '../../src/features/quality/ArtifactQualityLedgerPage';

vi.mock('../../src/api/artifactQuality', () => ({
  listArtifactQualityEvents: vi.fn().mockResolvedValue({
    events: [{
      id: 'event-1', taskId: 'task-1', runId: 'run-1', sequence: 1,
      eventType: 'ARTIFACT_CHECK', artifactName: 'report.pdf', fileId: 'file-1',
      manifestRef: 'manifest-1', sha256: 'a'.repeat(64), categoryCode: 'pdf_metadata',
      checkItem: 'metadata_present', actionSource: null, status: 'FAILED', score: null,
      scoreDelta: null, summary: 'PDF 元数据检查未通过', sourceType: 'HISTORICAL',
      confidence: 'HIGH', occurredAt: '2026-10-10T03:00:00Z',
    }], total: 1, page: 0, pageSize: 20,
  }),
}));

describe('ArtifactQualityLedgerPage', () => {
  it('shows source and confidence, links to the task, and excludes private source references', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter>
      <ArtifactQualityLedgerPage projectId="project-1" />
    </MemoryRouter></QueryClientProvider>);

    expect(await screen.findByText('PDF 元数据检查未通过')).toBeInTheDocument();
    expect(screen.getByText('历史记录')).toBeInTheDocument();
    expect(screen.getByText('高可信')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'task-1' })).toHaveAttribute('href', '/project-1/tasks/task-1');
    expect(screen.queryByText(/history:sha256/)).not.toBeInTheDocument();
    await waitFor(() => expect(listArtifactQualityEvents).toHaveBeenCalledWith(
      'project-1', { page: 0, pageSize: 20 },
    ));
    expect(screen.getByLabelText('开始日期')).toHaveAttribute('type', 'date');
    expect(screen.getByLabelText('结束日期')).toHaveAttribute('type', 'date');
    expect(screen.getByText('共 1 条')).toBeInTheDocument();
  });

  it('explains when no quality evidence is available', async () => {
    vi.mocked(listArtifactQualityEvents).mockResolvedValueOnce({ events: [], total: 0, page: 0, pageSize: 20 });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter>
      <ArtifactQualityLedgerPage projectId="project-1" />
    </MemoryRouter></QueryClientProvider>);
    expect(await screen.findByText('暂无质量证据')).toBeInTheDocument();
  });

  it('sends the task, artifact, status, issue type, and time filters', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter>
      <ArtifactQualityLedgerPage projectId="project-1" />
    </MemoryRouter></QueryClientProvider>);
    await screen.findByText('PDF 元数据检查未通过');
    fireEvent.change(screen.getByLabelText('Task ID'), { target: { value: 'task-2' } });
    fireEvent.change(screen.getByLabelText('成果物'), { target: { value: 'final.pdf' } });
    fireEvent.change(screen.getByLabelText('状态'), { target: { value: 'FAILED' } });
    fireEvent.change(screen.getByLabelText('问题类型'), { target: { value: 'pdf_metadata' } });
    fireEvent.change(screen.getByLabelText('开始日期'), { target: { value: '2026-10-01' } });
    fireEvent.change(screen.getByLabelText('结束日期'), { target: { value: '2026-10-12' } });
    fireEvent.click(screen.getByRole('button', { name: '筛选' }));

    await waitFor(() => expect(listArtifactQualityEvents).toHaveBeenLastCalledWith('project-1', {
      page: 0, pageSize: 20, taskId: 'task-2', artifactName: 'final.pdf', status: 'FAILED',
      issueType: 'pdf_metadata', from: new Date(2026, 9, 1).toISOString(),
      to: new Date(2026, 9, 12, 23, 59, 59, 999).toISOString(),
    }));
  });

  it('paginates the full result set and resets all filters', async () => {
    vi.mocked(listArtifactQualityEvents).mockImplementation(async (_projectId, filters) => ({
      events: [{
        id: 'event-1', taskId: 'task-1', runId: 'run-1', sequence: 1,
        eventType: 'ARTIFACT_CHECK', artifactName: 'report.pdf', fileId: 'file-1',
        manifestRef: 'manifest-1', sha256: 'a'.repeat(64), categoryCode: 'pdf_metadata',
        checkItem: 'metadata_present', actionSource: null, status: 'FAILED', score: null,
        scoreDelta: null, summary: 'PDF 元数据检查未通过', sourceType: 'HISTORICAL',
        confidence: 'HIGH', occurredAt: '2026-10-10T03:00:00Z',
      }], total: 41, page: filters?.page ?? 0, pageSize: filters?.pageSize ?? 20,
    }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter>
      <ArtifactQualityLedgerPage projectId="project-1" />
    </MemoryRouter></QueryClientProvider>);
    await screen.findByText('共 41 条');
    expect(screen.getByText(/第\s*1\s*\/\s*3\s*页/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '下一页' }));
    await waitFor(() => expect(listArtifactQualityEvents).toHaveBeenLastCalledWith(
      'project-1', { page: 1, pageSize: 20 },
    ));
    expect(await screen.findByText(/第\s*2\s*\/\s*3\s*页/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('每页条数'), { target: { value: '50' } });
    await waitFor(() => expect(listArtifactQualityEvents).toHaveBeenLastCalledWith(
      'project-1', { page: 0, pageSize: 50 },
    ));
    fireEvent.change(screen.getByLabelText('Task ID'), { target: { value: 'task-2' } });
    fireEvent.click(screen.getByRole('button', { name: '重置' }));
    await waitFor(() => expect(listArtifactQualityEvents).toHaveBeenLastCalledWith(
      'project-1', { page: 0, pageSize: 20 },
    ));
  });

  it('shows a retryable error state when the query fails', async () => {
    vi.mocked(listArtifactQualityEvents).mockRejectedValueOnce(Object.assign(new Error('offline'), { status: 503 }));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><MemoryRouter>
      <ArtifactQualityLedgerPage projectId="project-1" />
    </MemoryRouter></QueryClientProvider>);
    expect(await screen.findByRole('alert')).toHaveTextContent('依赖暂不可用');
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });
});
