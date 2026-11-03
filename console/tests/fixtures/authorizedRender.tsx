import type { ReactNode } from 'react';
import { render, type RenderOptions } from '@testing-library/react';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';

export const adminTestPermissions = [
  'admin:user:read',
  'admin:user:write',
  'identity:read',
  'project:member:manage',
  'organization:read',
  'organization:write',
  'platform:organization:create',
  'project:create',
  'integration:read',
  'integration:write',
  'credential:read',
  'credential:manage',
  'credential:rotate',
  'credential:revoke',
  'management:role:manage',
  'identity-mapping:read',
  'identity-mapping:write',
  'external-user:manage',
  'provisioning-policy:manage',
  'project:read',
  'task:read',
  'task:create',
  'task:cancel',
  'task:retry',
  'task:pause',
  'task:approve',
  'task:reject',
  'team:read',
  'team:write',
  'agent:read',
  'agent:write',
  'model:read',
  'model:write',
  'agent-spec:read',
  'agent-spec:write',
  'mcp:read',
  'mcp:write',
  'skill:read',
  'skill:write',
  'memory:read',
  'agent-spec:review',
  'agent-spec:publish',
  'agent-spec:instantiate',
  'agent-spec:deploy',
  'skill:review',
  'skill:publish',
  'skill:package:upload',
  'memory:govern',
  'memory:export',
  'sandbox:read',
  'usage:read',
  'usage:export',
  'quota:read',
  'quota:write',
  'audit:read',
  'artifact:read',
  'artifact:write',
  'dashboard:write',
];

export function renderAuthorized(
  ui: React.ReactElement,
  options?: RenderOptions,
  permissions: readonly string[] = adminTestPermissions,
) {
  const wrapper = ({ children }: { children: ReactNode }) => (
    <AuthorizationContext.Provider value={{ permissions, loaded: true, failed: false }}>
      {children}
    </AuthorizationContext.Provider>
  );
  return render(ui, { ...options, wrapper });
}
