import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { TeamCreatePage } from '../../src/features/teams/TeamCreatePage';
import { TeamDetailPage } from '../../src/features/teams/TeamDetailPage';
import { TeamListPage } from '../../src/features/teams/TeamListPage';

vi.mock('../../src/api/teams', () => ({
  listTeams: vi.fn().mockResolvedValue([
    {
      id: 'team-1',
      name: 'platform',
      displayName: '平台 Team',
      status: 'ACTIVE',
      createdAt: '2026-08-29T01:00:00Z',
      updatedAt: '2026-08-29T02:00:00Z',
      version: 3,
      leaderAgentId: 'worker-1',
      agentCount: 2,
      memberCount: 2,
      maxConcurrentTasks: 8,
    },
  ]),
  getTeam: vi.fn().mockResolvedValue({
    id: 'team-1',
    name: 'platform',
    displayName: '平台 Team',
    status: 'ACTIVE',
    createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 3,
    leaderAgentId: 'worker-1',
    agentCount: 2,
    memberCount: 2,
    maxConcurrentTasks: 8,
  }),
  listMembers: vi.fn().mockResolvedValue([
    {
      id: 'm-1',
      teamId: 'team-1',
      agentId: 'worker-1',
      role: 'LEADER',
      status: 'ACTIVE',
      joinedAt: '2026-08-29T01:00:00Z',
      updatedAt: '2026-08-29T02:00:00Z',
      version: 1,
      runtime: 'FAKE',
      capabilities: ['reports'],
    },
  ]),
  getPolicy: vi.fn().mockResolvedValue({
    teamId: 'team-1',
    maxConcurrentTasks: 8,
    requireHumanApproval: true,
    allowedRuntimes: ['FAKE'],
    requiredCapabilities: ['reports'],
    updatedAt: '2026-08-29T02:00:00Z',
    version: 2,
  }),
  listRevisions: vi.fn().mockResolvedValue([
    {
      teamId: 'team-1',
      revision: 3,
      digest: 'sha256:abc',
      status: 'PUBLISHED',
      createdBy: 'admin',
      createdAt: '2026-08-29T02:00:00Z',
      version: 1,
      memberAgentIds: ['worker-1'],
    },
  ]),
  listDeployments: vi.fn().mockResolvedValue([
    {
      id: 'd-1',
      teamId: 'team-1',
      teamRevision: 3,
      status: 'READY',
      members: [],
      createdAt: '2026-08-29T02:00:00Z',
    },
  ]),
  createTeam: vi.fn().mockResolvedValue({
    id: 'team-1',
    name: 'platform',
    displayName: '平台 Team',
    status: 'ACTIVE',
    createdAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 3,
  }),
}));

function renderWithQuery(ui: React.ReactNode) {
  return render(
    <MemoryRouter>
      <QueryClientProvider client={new QueryClient()}>{ui}</QueryClientProvider>
    </MemoryRouter>,
  );
}

describe('Team pages', () => {
  it('filters Team resources and exposes create entry', async () => {
    renderWithQuery(<TeamListPage projectId="p-1" />);
    expect(await screen.findByText('平台 Team')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('搜索 Team')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '创建 Team' })).toBeInTheDocument();
  });

  it('walks through the Team creation steps', async () => {
    renderWithQuery(<TeamCreatePage projectId="p-1" />);
    const next = screen.getByRole('button', { name: '下一步' });
    await userEvent.type(screen.getByLabelText('显示名称'), '新 Team');
    await userEvent.click(next);
    expect(screen.getByText('选择 Leader')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    await userEvent.click(screen.getByRole('button', { name: '下一步' }));
    expect(screen.getByRole('heading', { name: '调度策略' })).toBeInTheDocument();
  });

  it('shows members, policy, versions, explicit deployment boundary and runs in detail tabs', async () => {
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);
    expect(await screen.findByText('平台 Team')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('tab', { name: '版本与部署' }));
    expect(screen.getByText(/后端暂未提供部署列表接口/)).toBeInTheDocument();
    for (const tab of ['成员 Agent', '策略', '运行记录']) {
      await userEvent.click(screen.getByRole('tab', { name: tab }));
      expect(screen.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
    }
  });
});
