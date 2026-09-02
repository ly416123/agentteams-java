import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { TeamCreatePage } from '../../src/features/teams/TeamCreatePage';
import { TeamDetailPage } from '../../src/features/teams/TeamDetailPage';
import { TeamListPage } from '../../src/features/teams/TeamListPage';
import {
  addMember,
  createRevision,
  deployRevision,
  listDeployments,
  listRevisions,
  publishRevision,
  removeMember,
  retryDeployment,
  reviewRevision,
  rollbackTeam,
  updatePolicy,
} from '../../src/api/teams';

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
  addMember: vi.fn().mockResolvedValue({
    id: 'm-2',
    teamId: 'team-1',
    agentId: 'worker-2',
    role: 'MEMBER',
    status: 'ACTIVE',
    joinedAt: '2026-08-29T01:00:00Z',
    updatedAt: '2026-08-29T02:00:00Z',
    version: 1,
  }),
  removeMember: vi.fn().mockResolvedValue(undefined),
  updatePolicy: vi.fn().mockResolvedValue({
    teamId: 'team-1',
    maxConcurrentTasks: 10,
    requireHumanApproval: false,
    allowedRuntimes: ['FAKE'],
    requiredCapabilities: ['reports'],
    updatedAt: '2026-08-29T02:00:00Z',
    version: 3,
  }),
  createRevision: vi.fn().mockResolvedValue({
    teamId: 'team-1',
    revision: 4,
    leaderAgentId: 'worker-1',
    overlayJson: '{}',
    digest: 'sha256:new',
    status: 'DRAFT',
    version: 0,
    memberAgentIds: ['worker-1'],
  }),
  reviewRevision: vi.fn().mockResolvedValue({}),
  publishRevision: vi.fn().mockResolvedValue({}),
  deployRevision: vi.fn().mockResolvedValue({}),
  retryDeployment: vi.fn().mockResolvedValue(undefined),
  rollbackTeam: vi.fn().mockResolvedValue({}),
}));

function renderWithQuery(ui: React.ReactNode) {
  return render(
    <MemoryRouter>
      <QueryClientProvider
        client={
          new QueryClient({
            defaultOptions: { queries: { retry: false } },
          })
        }
      >
        {ui}
      </QueryClientProvider>
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
    expect(await screen.findByText('Deployment #d-1')).toBeInTheDocument();
    expect(screen.getByText('Revision 3')).toBeInTheDocument();
    expect(screen.getByText('就绪')).toBeInTheDocument();
    for (const tab of ['成员 Agent', '策略', '运行记录']) {
      await userEvent.click(screen.getByRole('tab', { name: tab }));
      expect(screen.getByRole('tab', { name: tab })).toHaveAttribute('aria-selected', 'true');
    }
  });

  it('adds and removes Team members through the management controls', async () => {
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);
    await userEvent.click(await screen.findByRole('tab', { name: '成员 Agent' }));
    await userEvent.type(screen.getByLabelText('Worker / Agent ID'), 'worker-2');
    await userEvent.click(screen.getByRole('button', { name: '添加成员' }));
    expect(addMember).toHaveBeenCalledWith('p-1', 'team-1', {
      agentId: 'worker-2',
      role: 'MEMBER',
    });
    await userEvent.click(screen.getByRole('button', { name: '移除' }));
    expect(removeMember).toHaveBeenCalledWith('p-1', 'team-1', 'worker-1');
  });

  it('edits the Team scheduling policy with the current version', async () => {
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);
    await userEvent.click(await screen.findByRole('tab', { name: '策略' }));
    const concurrency = screen.getByLabelText('最大并发任务数');
    await userEvent.clear(concurrency);
    await userEvent.type(concurrency, '10');
    await userEvent.click(screen.getByRole('button', { name: '保存策略' }));
    expect(updatePolicy).toHaveBeenCalledWith('p-1', 'team-1', {
      maxConcurrentTasks: 10,
      requireHumanApproval: true,
      allowedRuntimes: ['FAKE'],
      requiredCapabilities: ['reports'],
      expectedVersion: 2,
    });
  });

  it('creates, publishes, deploys and rolls back a Team revision with guarded inputs', async () => {
    vi.mocked(listRevisions).mockResolvedValue([
      {
        teamId: 'team-1',
        revision: 4,
        leaderAgentId: 'worker-1',
        overlayJson: '{}',
        digest: 'sha256:draft',
        status: 'DRAFT',
        createdBy: 'admin',
        createdAt: '2026-08-29T02:00:00Z',
        version: 2,
        memberAgentIds: ['worker-1'],
      },
    ]);
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);
    await userEvent.click(await screen.findByRole('tab', { name: '版本与部署' }));
    await userEvent.type(screen.getByLabelText('Leader Agent ID'), 'worker-1');
    await userEvent.type(screen.getByLabelText('成员 Agent ID'), 'worker-1');
    await userEvent.click(screen.getByRole('button', { name: '创建 Revision 草稿' }));
    expect(createRevision).toHaveBeenCalledWith('p-1', 'team-1', {
      leaderAgentId: 'worker-1',
      memberAgentIds: ['worker-1'],
      overlayJson: '{}',
      actor: undefined,
    });
    await userEvent.click(screen.getByRole('button', { name: '提交审核' }));
    expect(reviewRevision).toHaveBeenCalledWith('p-1', 'team-1', 4, 2);
    await userEvent.click(screen.getByRole('button', { name: '发布' }));
    expect(publishRevision).toHaveBeenCalledWith('p-1', 'team-1', 4, 2);
  });

  it('submits deployment members and exposes retry for failed deployments', async () => {
    vi.mocked(listRevisions).mockResolvedValue([
      {
        teamId: 'team-1',
        revision: 3,
        leaderAgentId: 'worker-1',
        overlayJson: '{}',
        digest: 'sha256:published',
        status: 'PUBLISHED',
        createdBy: 'admin',
        createdAt: '2026-08-29T02:00:00Z',
        version: 4,
        memberAgentIds: ['worker-1'],
      },
    ]);
    vi.mocked(listDeployments).mockResolvedValue([
      {
        id: 'd-failed',
        teamId: 'team-1',
        teamRevision: 3,
        status: 'FAILED',
        members: [],
        createdAt: '2026-08-29T02:00:00Z',
      },
    ]);
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);
    await userEvent.click(await screen.findByRole('tab', { name: '版本与部署' }));
    await userEvent.click(screen.getByRole('button', { name: '选择部署' }));
    await userEvent.click(screen.getByRole('button', { name: '提交 Deployment' }));
    expect(deployRevision).toHaveBeenCalledWith('p-1', 'team-1', 3, {
      members: [{ agentId: 'worker-1', baseManifest: '{}' }],
      actor: undefined,
    });
    await userEvent.click(screen.getByRole('button', { name: '重试' }));
    expect(retryDeployment).toHaveBeenCalledWith('p-1', 'team-1', 'd-failed');
    await userEvent.click(screen.getByRole('button', { name: '创建回滚草稿' }));
    expect(rollbackTeam).toHaveBeenCalledWith('p-1', 'team-1', {
      targetRevision: 3,
      expectedVersion: 4,
    });
  });

  it('shows an empty state when the Team has no deployments', async () => {
    vi.mocked(listDeployments).mockResolvedValueOnce([]);
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);

    await userEvent.click(await screen.findByRole('tab', { name: '版本与部署' }));
    expect(await screen.findByText('暂无部署')).toBeInTheDocument();
    expect(screen.getByText(/发布 Team 版本后/)).toBeInTheDocument();
  });

  it('shows the API error and retry action when deployment loading fails', async () => {
    vi.mocked(listDeployments).mockRejectedValue(new Error('deployment request failed'));
    renderWithQuery(<TeamDetailPage projectId="p-1" teamId="team-1" />);

    await userEvent.click(await screen.findByRole('tab', { name: '版本与部署' }));
    expect(await screen.findByText('加载失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '重试' })).toBeInTheDocument();
  });
});
