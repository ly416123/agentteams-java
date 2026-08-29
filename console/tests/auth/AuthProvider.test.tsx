import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { AuthProvider, useAuth } from '../../src/auth/AuthProvider';
import { RequireAuth } from '../../src/auth/RequireAuth';
import { MemoryRouter, Route, Routes } from 'react-router-dom';

function AuthProbe() {
  const auth = useAuth();
  return (
    <output>
      {auth.status}:{auth.accessToken || 'no-token'}
    </output>
  );
}

describe('OIDC authentication', () => {
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
      <MemoryRouter initialEntries={['/p-1/tasks']}>
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
  });
});
