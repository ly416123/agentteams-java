import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import type React from 'react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { ManagementMemoryPage } from '../../src/features/management/ManagementMemoryPage';
import { ManagementSandboxPage } from '../../src/features/management/ManagementSandboxPage';

const mocks = vi.hoisted(() => ({
  listMemoryMetadata: vi.fn().mockResolvedValue([
    {
      id: 'memory-1',
      policy: {
        scope: 'USER_PRIVATE',
        projectId: 'project-1',
        teamId: null,
        taskId: null,
        subjectId: 'user-1',
        sensitivity: 'NORMAL',
        consent: 'CONFIRMED',
      },
      source: 'conversation',
      expiresAt: '2026-09-02T00:00:00Z',
      createdAt: '2026-09-01T00:00:00Z',
      updatedAt: '2026-09-01T00:00:00Z',
      version: 1,
      governanceStatus: 'ACTIVE',
    },
  ]),
  governMemory: vi.fn().mockResolvedValue({ governanceStatus: 'FROZEN' }),
  listSandboxes: vi.fn().mockResolvedValue([
    {
      id: 'sandbox-1',
      taskId: 'task-1',
      attemptId: 'attempt-1',
      profile: 'HARDENED',
      status: 'READY',
      endpointRef: 'sandbox://attempt-1',
      requestedAt: '2026-09-01T00:00:00Z',
      expiresAt: '2026-09-01T01:00:00Z',
      lastObservedAt: '2026-09-01T00:02:00Z',
      failureCode: null,
      redactedFailureMessage: null,
      version: 1,
    },
  ]),
}));

vi.mock('../../src/api/memory', () => ({ ...mocks }));
vi.mock('../../src/api/sandboxes', () => ({ listSandboxes: mocks.listSandboxes }));

function renderPage(page: React.ReactNode) {
  return render(
    <QueryClientProvider
      client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}
    >
      <MemoryRouter>{page}</MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('management memory and sandbox pages', () => {
  it('renders memory policy metadata and sends a reasoned governance action', async () => {
    renderPage(<ManagementMemoryPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: 'Memory 治理' })).toBeInTheDocument();
    expect(await screen.findByText('USER_PRIVATE')).toBeInTheDocument();
    expect(screen.getByText('user-1')).toBeInTheDocument();
    expect(screen.queryByText(/private summary/)).not.toBeInTheDocument();
    await userEvent.type(screen.getByLabelText('治理原因'), 'compliance review');
    await userEvent.click(screen.getByRole('button', { name: '冻结' }));
    expect(mocks.governMemory).toHaveBeenCalledWith('memory-1', 'FREEZE', 'compliance review');
  });

  it('renders attempt-scoped sandbox metadata without workspace contents', async () => {
    renderPage(<ManagementSandboxPage projectId="project-1" />);
    expect(await screen.findByRole('heading', { name: 'Sandbox 运维' })).toBeInTheDocument();
    expect(await screen.findByText('HARDENED')).toBeInTheDocument();
    expect(screen.getByText('attempt-1')).toBeInTheDocument();
    expect(screen.getByText('sandbox://attempt-1')).toBeInTheDocument();
    expect(screen.queryByText(/workspace contents|secret/i)).not.toBeInTheDocument();
    expect(mocks.listSandboxes).toHaveBeenCalledWith('project-1');
  });
});
