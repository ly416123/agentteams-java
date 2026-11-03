export type ConsolePermission =
  | 'model:read'
  | 'model:write'
  | 'agent-spec:read'
  | 'agent-spec:write'
  | 'agent-spec:review'
  | 'agent-spec:publish'
  | 'agent-spec:instantiate'
  | 'agent-spec:deploy'
  | 'skill:read'
  | 'skill:write'
  | 'skill:review'
  | 'skill:publish'
  | 'skill:package:upload'
  | 'mcp:read'
  | 'mcp:write'
  | 'memory:read'
  | 'memory:govern'
  | 'memory:export'
  | 'sandbox:read'
  | 'usage:read'
  | 'usage:export'
  | 'quota:read'
  | 'quota:write'
  | 'audit:read'
  | 'artifact:read'
  | 'artifact:write'
  | 'task:read'
  | 'task:create'
  | 'task:cancel'
  | 'task:retry'
  | 'task:pause'
  | 'task:approve'
  | 'task:reject'
  | 'team:read'
  | 'team:write'
  | 'agent:read'
  | 'agent:write'
  | 'admin:user:read'
  | 'admin:user:write'
  | 'identity:read'
  | 'organization:read'
  | 'organization:write'
  | 'platform:organization:create'
  | 'project:create'
  | 'integration:read'
  | 'integration:write'
  | 'credential:read'
  | 'credential:manage'
  | 'credential:rotate'
  | 'credential:revoke'
  | 'identity-mapping:read'
  | 'identity-mapping:write'
  | 'external-user:manage'
  | 'provisioning-policy:manage'
  | 'dashboard:write';
export type ConsoleRoutePermission = ConsolePermission | 'project:read' | 'project:member:manage';

export type NavigationPermission =
  ConsolePermission | 'project:read' | 'project:member:manage' | 'task:read' | 'team:read';

export type PermissionPrincipal = { permissions?: readonly string[] };

export function parsePermissionsClaim(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string');
  if (typeof value === 'string') return value.split(/[ ,]+/).filter(Boolean);
  return [];
}

export function hasPermission(
  principal: PermissionPrincipal | undefined,
  permission: string,
): boolean {
  return principal?.permissions?.includes(permission) === true;
}

export function visibleForNavigation(
  principal: PermissionPrincipal | undefined,
  key: string,
): boolean {
  const required = navigationPermission[key];
  return required !== undefined && hasPermission(principal, required);
}

export function visibleForNavigationState(
  principal: PermissionPrincipal | undefined,
  key: string,
  permissionsLoaded: boolean,
  permissionsFailed: boolean,
): boolean {
  return permissionsLoaded && !permissionsFailed && visibleForNavigation(principal, key);
}

export const managementRoutePermissions: Record<string, ConsoleRoutePermission> = {
  identity: 'identity:read',
  organizations: 'organization:read',
  integrations: 'integration:read',
  roles: 'project:read',
  projects: 'project:read',
  templates: 'agent-spec:read',
  agentspecs: 'agent-spec:read',
  models: 'model:read',
  mcp: 'mcp:read',
  skills: 'skill:read',
  usage: 'usage:read',
  budgets: 'quota:read',
  alerts: 'usage:read',
  audit: 'audit:read',
  memory: 'memory:read',
  sandboxes: 'sandbox:read',
  artifacts: 'artifact:read',
  evaluations: 'agent-spec:read',
  quality: 'artifact:read',
};

export const navigationPermission: Record<string, NavigationPermission> = {
  overview: 'task:read',
  tasks: 'task:read',
  'scheduled-tasks': 'task:read',
  teams: 'team:read',
  workers: 'agent:read',
  conversations: 'task:read',
  templates: 'agent-spec:read',
  agentspecs: 'agent-spec:read',
  models: 'model:read',
  mcp: 'mcp:read',
  skills: 'skill:read',
  memory: 'memory:read',
  sandboxes: 'sandbox:read',
  usage: 'usage:read',
  budgets: 'quota:read',
  alerts: 'usage:read',
  audit: 'audit:read',
  artifacts: 'artifact:read',
  evaluations: 'agent-spec:read',
  quality: 'artifact:read',
  identity: 'identity:read',
  organizations: 'organization:read',
  integrations: 'integration:read',
  roles: 'project:member:manage',
  projects: 'project:read',
};
