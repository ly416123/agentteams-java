import { describe, expect, it, vi } from 'vitest';
import type { HttpClient } from '../../src/api/httpClient';
import {
  addMember,
  createRevision,
  createTeam,
  deployRevision,
  getDeployment,
  getPolicy,
  getTeam,
  listDeployments,
  listMembers,
  listRevisions,
  listTeams,
  publishRevision,
  removeMember,
  retryDeployment,
  reviewRevision,
  rollbackTeam,
  updatePolicy,
} from '../../src/api/teams';

function client() {
  return {
    request: vi.fn().mockResolvedValue({ items: [], hasMore: false }),
    requestText: vi.fn(),
    requestStream: vi.fn(),
  } as unknown as HttpClient & { request: ReturnType<typeof vi.fn> };
}

describe('team domain API contracts: 20 cases', () => {
  it('lists teams by search within a project', async () => {
    const http = client();
    await listTeams('project-1', { search: '平台' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/page', {
      query: { projectId: 'project-1', q: '平台', status: undefined, cursor: undefined },
    });
  });

  it('lists teams by status within a project', async () => {
    const http = client();
    await listTeams('project-1', { status: 'ACTIVE' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/page', {
      query: { projectId: 'project-1', q: undefined, status: 'ACTIVE', cursor: undefined },
    });
  });

  it('lists teams from a cursor', async () => {
    const http = client();
    await listTeams('project-1', { cursor: 'cursor-2' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/page', {
      query: { projectId: 'project-1', q: undefined, status: undefined, cursor: 'cursor-2' },
    });
  });

  it('gets a team detail', async () => {
    const http = client();
    await getTeam('project-1', 'team-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1', {
      query: { projectId: 'project-1' },
    });
  });

  it('creates a team with the project scope', async () => {
    const http = client();
    const body = { name: 'platform', displayName: '平台团队' };
    await createTeam('project-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('lists team members', async () => {
    const http = client();
    await listMembers('project-1', 'team-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/members', {
      query: { projectId: 'project-1' },
    });
  });

  it('adds a leader member', async () => {
    const http = client();
    await addMember('project-1', 'team-1', { agentId: 'worker-1', role: 'LEADER' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/members', {
      method: 'POST',
      body: { agentId: 'worker-1', role: 'LEADER' },
      query: { projectId: 'project-1' },
    });
  });

  it('adds an executor member', async () => {
    const http = client();
    await addMember('project-1', 'team-1', { agentId: 'worker-2', role: 'MEMBER' }, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/members', {
      method: 'POST',
      body: { agentId: 'worker-2', role: 'MEMBER' },
      query: { projectId: 'project-1' },
    });
  });

  it('removes a member by agent id', async () => {
    const http = client();
    await removeMember('project-1', 'team-1', 'worker-2', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/members/worker-2', {
      method: 'DELETE',
      query: { projectId: 'project-1' },
    });
  });

  it('gets the scheduling policy', async () => {
    const http = client();
    await getPolicy('project-1', 'team-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/policy', {
      query: { projectId: 'project-1' },
    });
  });

  it('updates the scheduling policy with expected version', async () => {
    const http = client();
    const body = {
      maxConcurrentTasks: 8,
      requireHumanApproval: true,
      allowedRuntimes: ['QWENPAW'],
      requiredCapabilities: ['reports'],
      expectedVersion: 4,
    };
    await updatePolicy('project-1', 'team-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/policy', {
      method: 'PUT',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('lists team revisions', async () => {
    const http = client();
    await listRevisions('project-1', 'team-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/revisions', {
      query: { projectId: 'project-1' },
    });
  });

  it('creates a revision draft with members and overlay', async () => {
    const http = client();
    const body = {
      leaderAgentId: 'worker-1',
      memberAgentIds: ['worker-1', 'worker-2'],
      overlayJson: '{}',
      actor: 'alice',
    };
    await createRevision('project-1', 'team-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/revisions', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('reviews a revision with expected version', async () => {
    const http = client();
    await reviewRevision('project-1', 'team-1', 3, 2, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/revisions/3/review', {
      method: 'POST',
      body: { expectedVersion: 2 },
      query: { projectId: 'project-1' },
    });
  });

  it('publishes a revision with expected version', async () => {
    const http = client();
    await publishRevision('project-1', 'team-1', 3, 3, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/revisions/3/publish', {
      method: 'POST',
      body: { expectedVersion: 3 },
      query: { projectId: 'project-1' },
    });
  });

  it('deploys a published revision with member manifests', async () => {
    const http = client();
    const body = {
      members: [{ agentId: 'worker-1', baseManifest: '{}', taskOverlay: '{"mode":"safe"}' }],
      actor: 'alice',
    };
    await deployRevision('project-1', 'team-1', 3, body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/revisions/3/deployments', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });

  it('lists deployments for a team', async () => {
    const http = client();
    await listDeployments('project-1', 'team-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/deployments', {
      query: { projectId: 'project-1' },
    });
  });

  it('gets a deployment detail', async () => {
    const http = client();
    await getDeployment('project-1', 'team-1', 'deployment-1', http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/deployments/deployment-1', {
      query: { projectId: 'project-1' },
    });
  });

  it('retries a failed deployment', async () => {
    const http = client();
    await retryDeployment('project-1', 'team-1', 'deployment-1', http);
    expect(http.request).toHaveBeenCalledWith(
      '/api/v1/teams/team-1/deployments/deployment-1/retry',
      { method: 'POST', query: { projectId: 'project-1' } },
    );
  });

  it('rolls a team back to a published revision', async () => {
    const http = client();
    const body = { targetRevision: 2, expectedVersion: 7, actor: 'operator' };
    await rollbackTeam('project-1', 'team-1', body, http);
    expect(http.request).toHaveBeenCalledWith('/api/v1/teams/team-1/rollback', {
      method: 'POST',
      body,
      query: { projectId: 'project-1' },
    });
  });
});
