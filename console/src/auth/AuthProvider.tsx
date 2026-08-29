import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { User } from 'oidc-client-ts';
import { createOidcUserManager, saveReturnTo } from './oidc';
import { setMemoryAccessToken } from './memoryToken';

type AuthManager = {
  getUser: () => Promise<User | null>;
  signinRedirect: (args?: { state?: unknown }) => Promise<void>;
  signoutRedirect?: () => Promise<void>;
  signinCallback?: () => Promise<User | undefined>;
};

type AuthContextValue = {
  status: 'loading' | 'authenticated' | 'unauthenticated';
  error?: unknown;
  user?: User;
  accessToken?: string;
  login: (returnTo?: string) => Promise<void>;
  logout: () => Promise<void>;
  completeLogin: () => Promise<User | undefined>;
};

const AuthContext = createContext<AuthContextValue | null>(null);

export function AuthProvider({
  children,
  manager: providedManager,
}: {
  children: ReactNode;
  manager?: AuthManager;
}) {
  const [defaultManager] = useState<AuthManager>(() => createOidcUserManager());
  const manager = providedManager || defaultManager;
  const [status, setStatus] = useState<AuthContextValue['status']>('loading');
  const [user, setUser] = useState<User>();
  const [error, setError] = useState<unknown>();

  useEffect(() => {
    let active = true;
    manager
      .getUser()
      .then((existing) => {
        if (!active) return;
        setError(undefined);
        setUser(existing || undefined);
        setMemoryAccessToken(existing && !existing.expired ? existing.access_token : undefined);
        setStatus(existing && !existing.expired ? 'authenticated' : 'unauthenticated');
      })
      .catch((nextError) => {
        if (!active) return;
        setMemoryAccessToken(undefined);
        setUser(undefined);
        setError(nextError);
        setStatus('unauthenticated');
      });
    return () => {
      active = false;
    };
  }, [manager]);

  useEffect(() => {
    const handleUnauthorized = () => {
      setMemoryAccessToken(undefined);
      setUser(undefined);
      setStatus('unauthenticated');
    };
    window.addEventListener('agentteams:unauthorized', handleUnauthorized);
    return () => window.removeEventListener('agentteams:unauthorized', handleUnauthorized);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      status,
      error,
      user,
      accessToken: user?.access_token,
      login: async (returnTo = '/') => {
        saveReturnTo(returnTo);
        await manager.signinRedirect({ state: { returnTo } });
      },
      logout: async () => {
        setMemoryAccessToken(undefined);
        setUser(undefined);
        setStatus('unauthenticated');
        await manager.signoutRedirect?.();
      },
      completeLogin: async () => {
        if (!manager.signinCallback) return undefined;
        const next = await manager.signinCallback();
        if (next) {
          setMemoryAccessToken(next.access_token);
          setUser(next);
          setStatus('authenticated');
        }
        return next;
      },
    }),
    [manager, status, user, error],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used within AuthProvider');
  return value;
}
