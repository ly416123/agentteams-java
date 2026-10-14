import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaskCreatePage } from '../../src/features/tasks/TaskCreatePage';

const { mutate, useCreateTask, useProjects, useTeams, useAuth } = vi.hoisted(() => ({
  mutate: vi.fn(),
  useCreateTask: vi.fn(),
  useProjects: vi.fn(),
  useTeams: vi.fn(),
  useAuth: vi.fn(),
}));

vi.mock('../../src/queries/useTaskQueries', () => ({ useCreateTask }));
vi.mock('../../src/queries/useProjectQueries', () => ({ useProjects }));
vi.mock('../../src/queries/useTeamQueries', () => ({ useTeams }));
vi.mock('../../src/auth/AuthProvider', () => ({ useAuth }));

function tokenWithClaims(claims: Record<string, unknown>) {
  const payload = btoa(JSON.stringify(claims)).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
  return `header.${payload}.signature`;
}

describe('TaskCreatePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    useCreateTask.mockReturnValue({ mutate, isError: false, isPending: false });
    useProjects.mockReturnValue({
      data: { items: [{ id: 'project-id', tenantId: 'tenant-a', name: 'project-a' }] },
      isLoading: false,
    });
    useTeams.mockReturnValue({
      data: {
        items: [{ id: 'team-uuid', name: 'l5-agentteams-eval', displayName: 'L5 evaluation' }],
      },
      isLoading: false,
      isError: false,
    });
    useAuth.mockReturnValue({
      accessToken: tokenWithClaims({ tenant: 'tenant-a', project: 'project-a', team: 'team-a' }),
    });
  });

  it('uses the token team scope while keeping the selected team ID for scheduling', async () => {
    render(
      <MemoryRouter>
        <TaskCreatePage projectId="project-id" />
      </MemoryRouter>,
    );

    await userEvent.type(screen.getByRole('textbox', { name: '任务标题' }), '双 MCP 验证');
    await userEvent.selectOptions(screen.getByRole('combobox', { name: '任务团队' }), 'team-uuid');
    await userEvent.click(screen.getByRole('button', { name: '创建任务' }));

    await waitFor(() => expect(mutate).toHaveBeenCalledOnce());
    expect(mutate.mock.calls[0][0]).toMatchObject({
      spec: {
        scope: { tenant: 'tenant-a', project: 'project-a', team: 'team-a' },
        teamId: 'team-uuid',
      },
    });
  });
});
