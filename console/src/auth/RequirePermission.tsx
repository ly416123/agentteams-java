import { useLocation } from 'react-router-dom';
import type { ReactNode } from 'react';
import { useAuth } from './AuthProvider';
import { useAuthorization } from './AuthorizationProvider';
import { hasPermission, type ConsoleRoutePermission } from './permissions';

export function RequirePermission({
  permission,
  children,
}: {
  permission: ConsoleRoutePermission;
  children: ReactNode;
}) {
  const auth = useAuth();
  const authorization = useAuthorization();
  const location = useLocation();
  if (auth.status === 'loading' || authorization.loaded === false) {
    return (
      <div className="panel loading-block" role="status">
        正在加载权限…
      </div>
    );
  }
  if (authorization.failed) {
    return (
      <main className="page">
        <section className="panel" role="alert">
          <h1>权限暂不可用</h1>
          <p>暂时无法确认当前账号的访问权限，请稍后重试。</p>
          <button className="button" onClick={() => window.location.reload()}>
            重试
          </button>
        </section>
      </main>
    );
  }
  if (!hasPermission({ permissions: authorization.permissions }, permission)) {
    return (
      <main className="page">
        <section className="panel" role="alert">
          <h1>无权访问</h1>
          <p>当前账号没有访问此功能的权限。</p>
          <a
            className="button"
            href={location.pathname.startsWith('/settings') ? '/console' : './overview'}
          >
            返回工作台
          </a>
        </section>
      </main>
    );
  }
  return <>{children}</>;
}
