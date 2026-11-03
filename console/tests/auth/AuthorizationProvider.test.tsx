import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { Link, MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthorizationProvider, useAuthorization } from '../../src/auth/AuthorizationProvider';
import { AuthProvider } from '../../src/auth/AuthProvider';

const getPermissions = vi.hoisted(() => vi.fn());
vi.mock('../../src/api/authorization', async () => {
  const actual = await vi.importActual<typeof import('../../src/api/authorization')>(
    '../../src/api/authorization',
  );
  return { ...actual, getEffectiveAuthorization: getPermissions };
});

function PermissionProbe() {
  const authorization = useAuthorization();
  return (
    <output>
      {authorization.permissions.join(',') || 'no-permissions'}:{String(authorization.failed)}
    </output>
  );
}

describe('AuthorizationProvider', () => {
  it('refreshes effective permissions when the authorization query is invalidated', async () => {
    getPermissions
      .mockReset()
      .mockResolvedValueOnce({ permissions: ['task:read'], assignments: [] });
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const manager = {
      getUser: vi.fn().mockResolvedValue({ access_token: 'token', profile: { sub: 'alice' } }),
      signinRedirect: vi.fn(),
    };
    render(
      <MemoryRouter initialEntries={['/project-a/overview']}>
        <QueryClientProvider client={queryClient}>
          <AuthProvider manager={manager}>
            <AuthorizationProvider>
              <PermissionProbe />
            </AuthorizationProvider>
          </AuthProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText('task:read:false')).toBeInTheDocument();
    getPermissions.mockResolvedValue({ permissions: ['model:read'], assignments: [] });
    await queryClient.invalidateQueries({
      queryKey: ['effective-authorization', 'alice', 'project-a'],
    });
    expect(await screen.findByText('model:read:false')).toBeInTheDocument();
    await waitFor(() => expect(getPermissions).toHaveBeenLastCalledWith('project-a'));
  });

  it('loads a fresh permission scope after navigation changes projects', async () => {
    getPermissions.mockReset().mockImplementation(async (projectId?: string) => ({
      permissions: [projectId === 'project-b' ? 'model:read' : 'task:read'],
      assignments: [],
    }));
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const manager = {
      getUser: vi.fn().mockResolvedValue({ access_token: 'token', profile: { sub: 'alice' } }),
      signinRedirect: vi.fn(),
    };
    render(
      <MemoryRouter initialEntries={['/project-a/overview']}>
        <QueryClientProvider client={queryClient}>
          <AuthProvider manager={manager}>
            <AuthorizationProvider>
              <PermissionProbe />
              <Link to="/project-b/overview">另一个项目</Link>
            </AuthorizationProvider>
          </AuthProvider>
        </QueryClientProvider>
      </MemoryRouter>,
    );
    expect(await screen.findByText('task:read:false')).toBeInTheDocument();
    await screen.getByRole('link', { name: '另一个项目' }).click();
    expect(await screen.findByText('model:read:false')).toBeInTheDocument();
  });
});
