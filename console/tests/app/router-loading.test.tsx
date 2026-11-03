import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { AppRouter } from '../../src/app/router';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

vi.mock('../../src/auth/oidc', () => ({
  createOidcUserManager: () => ({
    getUser: () => new Promise(() => undefined),
    signinRedirect: vi.fn(),
  }),
  saveReturnTo: vi.fn(),
}));
vi.mock('../../src/api/authorization', () => ({
  getEffectiveAuthorization: () => Promise.resolve({ permissions: ['task:read'], assignments: [] }),
}));

vi.mock('../../src/auth/AuthProvider', async () => {
  return {
    AuthProvider: ({ children }: { children: ReactNode }) => children,
    useAuth: () => ({ status: 'authenticated', user: { profile: { name: 'Test' } } }),
  };
});
vi.mock('../../src/features/overview/OverviewPage', () => ({
  OverviewPage: () => <div>Mock overview page</div>,
}));

describe('route loading fallback', () => {
  it('keeps the console shell and loads the selected project page', async () => {
    window.history.replaceState({}, '', '/project/overview');
    render(
      <AuthorizationContext.Provider
        value={{ permissions: ['task:read'], loaded: true, failed: false }}
      >
        <QueryClientProvider client={new QueryClient()}>
          <AppRouter />
        </QueryClientProvider>
      </AuthorizationContext.Provider>,
    );

    expect(await screen.findByText('Mock overview page')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: '主导航' })).toBeInTheDocument();
  });
});
