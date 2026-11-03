import { createContext, useContext, type ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { getEffectiveAuthorization } from '../api/authorization';
import { useAuth } from './AuthProvider';
import { useLocation } from 'react-router-dom';

export type AuthorizationContextValue = {
  permissions: readonly string[];
  loaded: boolean;
  failed: boolean;
};
export const AuthorizationContext = createContext<AuthorizationContextValue>({
  permissions: [],
  loaded: false,
  failed: false,
});

export function AuthorizationProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  const firstSegment = location.pathname.split('/').filter(Boolean)[0];
  const projectId =
    firstSegment && !['settings', 'console', 'login', 'auth'].includes(firstSegment)
      ? firstSegment
      : undefined;
  const result = useQuery({
    queryKey: ['effective-authorization', auth.user?.profile.sub, projectId],
    queryFn: () => getEffectiveAuthorization(projectId),
    enabled: auth.status === 'authenticated',
    retry: false,
    staleTime: 30_000,
  });
  return (
    <AuthorizationContext.Provider
      value={{
        permissions: result.data?.permissions || [],
        loaded: result.isFetched,
        failed: result.isError,
      }}
    >
      {children}
    </AuthorizationContext.Provider>
  );
}

export function useAuthorization() {
  return useContext(AuthorizationContext);
}
