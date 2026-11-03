import { describe, expect, it } from 'vitest';
import {
  hasPermission,
  navigationPermission,
  parsePermissionsClaim,
  visibleForNavigation,
} from '../../src/auth/permissions';

describe('console permissions', () => {
  it('parses array and delimited string permission claims', () => {
    expect(parsePermissionsClaim(['model:read', 'audit:read'])).toEqual([
      'model:read',
      'audit:read',
    ]);
    expect(parsePermissionsClaim('model:read audit:read')).toEqual(['model:read', 'audit:read']);
  });

  it('denies when the permission claim is missing or malformed', () => {
    expect(parsePermissionsClaim(undefined)).toEqual([]);
    expect(parsePermissionsClaim({ role: 'ADMIN' })).toEqual([]);
    expect(hasPermission({ permissions: parsePermissionsClaim(undefined) }, 'model:write')).toBe(
      false,
    );
  });

  it('uses a strict read permission check for sensitive settings routes', async () => {
    const { managementRoutePermissions, navigationPermission } =
      await import('../../src/auth/permissions');
    expect(managementRoutePermissions.identity).toBe('identity:read');
    expect(managementRoutePermissions.integrations).toBe('integration:read');
    expect(managementRoutePermissions.roles).toBe('project:read');
    expect(navigationPermission.roles).toBe('project:member:manage');
    for (const [route, permission] of Object.entries(managementRoutePermissions)) {
      if (route !== 'roles') expect(navigationPermission[route]).toBe(permission);
    }
  });

  it('filters the same navigation inventory by each role permission set and hides unclassified items', () => {
    const viewer = { permissions: ['project:read', 'task:read', 'team:read'] };
    expect(visibleForNavigation(viewer, 'tasks')).toBe(true);
    expect(visibleForNavigation(viewer, 'teams')).toBe(true);
    expect(visibleForNavigation(viewer, 'models')).toBe(false);
    expect(visibleForNavigation(viewer, 'audit')).toBe(false);
    expect(visibleForNavigation(viewer, 'unclassified-admin-page')).toBe(false);

    const projectAdmin = {
      permissions: ['task:read', 'model:read', 'audit:read', 'organization:read'],
    };
    expect(visibleForNavigation(projectAdmin, 'models')).toBe(true);
    expect(visibleForNavigation(projectAdmin, 'audit')).toBe(true);
    expect(visibleForNavigation(projectAdmin, 'organizations')).toBe(true);
    expect(visibleForNavigation(projectAdmin, 'integrations')).toBe(false);
    expect(visibleForNavigation(projectAdmin, 'roles')).toBe(false);
  });

  it('keeps organization-only administrators inside organization management navigation', () => {
    const organizationAdmin = {
      permissions: ['identity:read', 'organization:read', 'integration:read'],
    };
    expect(visibleForNavigation(organizationAdmin, 'identity')).toBe(true);
    expect(visibleForNavigation(organizationAdmin, 'organizations')).toBe(true);
    expect(visibleForNavigation(organizationAdmin, 'integrations')).toBe(true);
    expect(visibleForNavigation(organizationAdmin, 'projects')).toBe(false);
    expect(visibleForNavigation(organizationAdmin, 'roles')).toBe(false);
    expect(visibleForNavigation(organizationAdmin, 'models')).toBe(false);
  });

  it('matches complete navigation snapshots for every project and management role', () => {
    const projectReadPermissions = [
      'project:read',
      'task:read',
      'team:read',
      'agent:read',
      'agent-spec:read',
      'model:read',
      'mcp:read',
      'skill:read',
      'memory:read',
      'sandbox:read',
      'usage:read',
      'quota:read',
      'artifact:read',
    ];
    const expectedProjectNavigation = [
      'overview',
      'tasks',
      'scheduled-tasks',
      'teams',
      'workers',
      'conversations',
      'templates',
      'agentspecs',
      'models',
      'mcp',
      'skills',
      'memory',
      'sandboxes',
      'usage',
      'budgets',
      'alerts',
      'artifacts',
      'evaluations',
      'quality',
      'projects',
    ];
    const navigationKeys = Object.keys(navigationPermission);
    const snapshot = (permissions: string[]) =>
      navigationKeys.filter((key) => visibleForNavigation({ permissions }, key)).sort();

    for (const role of ['VIEWER', 'DEVELOPER', 'OPERATOR']) {
      expect(snapshot(projectReadPermissions), role).toEqual([...expectedProjectNavigation].sort());
    }
    for (const role of ['ADMIN', 'OWNER']) {
      expect(
        snapshot([...projectReadPermissions, 'audit:read', 'project:member:manage']),
        role,
      ).toEqual([...expectedProjectNavigation, 'audit', 'roles'].sort());
    }
    expect(snapshot(['identity:read', 'organization:read', 'integration:read'])).toEqual(
      ['identity', 'organizations', 'integrations'].sort(),
    );
    expect(visibleForNavigation({ permissions: ['project:member:manage'] }, 'roles')).toBe(true);
    expect(
      snapshot([
        ...projectReadPermissions,
        'audit:read',
        'identity:read',
        'organization:read',
        'integration:read',
        'admin:user:read',
        'project:create',
        'credential:read',
      ]),
    ).toEqual(
      [...expectedProjectNavigation, 'audit', 'identity', 'organizations', 'integrations'].sort(),
    );
  });
});
