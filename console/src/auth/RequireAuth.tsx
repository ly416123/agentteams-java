import { Navigate, useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { saveReturnTo } from './oidc';

export function RequireAuth({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const location = useLocation();
  if (auth.status === 'loading') return <div className="loading-screen">正在验证登录状态…</div>;
  if (auth.status === 'unauthenticated') {
    const returnTo = `${location.pathname}${location.search}${location.hash}`;
    saveReturnTo(returnTo);
    return <Navigate to="/login" replace state={{ from: returnTo }} />;
  }
  return <>{children}</>;
}
