import type { ReactNode } from 'react';
import { useAuthorization } from './AuthorizationProvider';

export function PermissionGate({
  permission,
  children,
}: {
  permission: string;
  children: ReactNode;
}) {
  const authorization = useAuthorization();
  if (authorization.failed) return null;
  if (!authorization.loaded || !authorization.permissions.includes(permission)) return null;
  return <>{children}</>;
}
