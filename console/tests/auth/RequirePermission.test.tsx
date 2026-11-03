import { screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';
import { AuthorizationContext } from '../../src/auth/AuthorizationProvider';
import { RequirePermission } from '../../src/auth/RequirePermission';
import {
  managementRoutePermissions,
  type ConsoleRoutePermission,
} from '../../src/auth/permissions';
import { render } from '@testing-library/react';

vi.mock('../../src/auth/AuthProvider', () => ({
  useAuth: () => ({ status: 'authenticated' }),
}));

function renderGuard(
  loaded: boolean,
  permissions: string[],
  requiredPermission: ConsoleRoutePermission = 'admin:user:read',
) {
  return render(
    <MemoryRouter initialEntries={['/project-x/settings/identity']}>
      <AuthorizationContext.Provider value={{ loaded, permissions, failed: false }}>
        <RequirePermission permission={requiredPermission}>
          <div>敏感页面</div>
        </RequirePermission>
      </AuthorizationContext.Provider>
    </MemoryRouter>,
  );
}

describe('RequirePermission', () => {
  it('keeps protected content hidden while permissions are loading', () => {
    renderGuard(false, ['admin:user:read']);
    expect(screen.getByRole('status')).toHaveTextContent('正在加载权限');
    expect(screen.queryByText('敏感页面')).not.toBeInTheDocument();
  });

  it('shows a safe denial for direct routes without the required permission', () => {
    renderGuard(true, []);
    expect(screen.getByRole('alert')).toHaveTextContent('无权访问');
    expect(screen.queryByText('敏感页面')).not.toBeInTheDocument();
  });

  it('shows a friendly retry state when permission lookup itself fails', () => {
    render(
      <MemoryRouter initialEntries={['/project-x/settings/organizations']}>
        <AuthorizationContext.Provider value={{ loaded: true, permissions: [], failed: true }}>
          <RequirePermission permission="organization:read">
            <div>组织数据</div>
          </RequirePermission>
        </AuthorizationContext.Provider>
      </MemoryRouter>,
    );
    expect(screen.getByRole('alert')).toHaveTextContent('权限暂不可用');
    expect(screen.queryByText('加载失败')).not.toBeInTheDocument();
    expect(screen.queryByText('组织数据')).not.toBeInTheDocument();
  });

  it.each(Object.entries(managementRoutePermissions))(
    'denies the %s page when its server read permission is missing',
    (_route, requiredPermission) => {
      renderGuard(true, ['unrelated:permission'], requiredPermission);
      expect(screen.getByRole('alert')).toHaveTextContent('无权访问');
      expect(screen.queryByText('敏感页面')).not.toBeInTheDocument();
    },
  );

  it('renders protected content when the server grants the permission', () => {
    renderGuard(true, ['admin:user:read']);
    expect(screen.getByText('敏感页面')).toBeInTheDocument();
  });
});
