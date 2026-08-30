import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../../src/auth/AuthProvider';
import { RequireAuth } from '../../src/auth/RequireAuth';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import userEvent from '@testing-library/user-event';
import { LoginPage } from '../../src/features/login/LoginPage';
import { oidcSettings, RETURN_TO_STORAGE_KEY, shouldDisablePkce } from '../../src/auth/oidc';
import { AuthCallbackPage } from '../../src/features/login/AuthCallbackPage';

function AuthProbe() {
  const auth = useAuth();
  return (
    <output>
      {auth.status}:{auth.accessToken || 'no-token'}
    </output>
  );
}

describe('OIDC authentication', () => {
  it('uses the public runtime OIDC configuration injected by deployment', () => {
    window.__AGENTTEAMS_CONFIG__ = {
      oidcIssuer: 'http://keycloak.test/realms/agentteams',
      oidcClientId: 'agentteams-api',
    };

    expect(oidcSettings()).toMatchObject({
      authority: 'http://keycloak.test/realms/agentteams',
      client_id: 'agentteams-api',
    });
    delete window.__AGENTTEAMS_CONFIG__;
  });

  it('disables PKCE only for the local HTTP LAN demo surface', () => {
    expect(shouldDisablePkce({ protocol: 'http:', hostname: '192.168.1.16' })).toBe(true);
    expect(shouldDisablePkce({ protocol: 'http:', hostname: 'localhost' })).toBe(false);
    expect(shouldDisablePkce({ protocol: 'https:', hostname: '192.168.1.16' })).toBe(false);
  });

  it('keeps the access token in provider memory only', async () => {
    const manager = {
      getUser: vi
        .fn()
        .mockResolvedValue({ access_token: 'secret-token', profile: { sub: 'user-1' } }),
      signinRedirect: vi.fn(),
      signoutRedirect: vi.fn(),
    };

    render(
      <AuthProvider manager={manager}>
        <AuthProbe />
      </AuthProvider>,
    );

    await waitFor(() =>
      expect(screen.getByRole('status')).toHaveTextContent('authenticated:secret-token'),
    );
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it('redirects an unauthenticated user to login with the original path', async () => {
    const manager = { getUser: vi.fn().mockResolvedValue(null), signinRedirect: vi.fn() };
    render(
      <MemoryRouter initialEntries={['/p-1/tasks?phase=RUNNING#timeline']}>
        <AuthProvider manager={manager}>
          <Routes>
            <Route
              path="*"
              element={
                <RequireAuth>
                  <span>console</span>
                </RequireAuth>
              }
            />
            <Route path="/login" element={<span>login</span>} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('login')).toBeInTheDocument();
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBe('/p-1/tasks?phase=RUNNING#timeline');
  });

  it('passes the complete return route through OIDC state and session storage', async () => {
    const manager = {
      getUser: vi.fn().mockResolvedValue(null),
      signinRedirect: vi.fn().mockResolvedValue(undefined),
    };
    render(
      <MemoryRouter
        initialEntries={[{ pathname: '/login', state: { from: '/p-1/tasks?x=1#events' } }]}
      >
        <AuthProvider manager={manager}>
          <LoginPage />
        </AuthProvider>
      </MemoryRouter>,
    );

    await userEvent.click(await screen.findByRole('button', { name: '使用组织账号登录' }));
    expect(manager.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/p-1/tasks?x=1#events' },
    });
    expect(sessionStorage.getItem(RETURN_TO_STORAGE_KEY)).toBe('/p-1/tasks?x=1#events');
  });

  it('surfaces callback failures instead of leaving the callback page loading forever', async () => {
    const manager = {
      getUser: vi.fn().mockResolvedValue(null),
      signinRedirect: vi.fn(),
      signinCallback: vi.fn().mockRejectedValue(new Error('invalid callback')),
    };

    render(
      <MemoryRouter>
        <AuthProvider manager={manager}>
          <AuthCallbackPage />
        </AuthProvider>
      </MemoryRouter>,
    );

    expect(await screen.findByText('OIDC 登录失败')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '返回登录' })).toBeInTheDocument();
  });
});
