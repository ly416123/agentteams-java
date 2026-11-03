import { useEffect, useMemo, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { ProjectSwitcher } from '../features/projects/ProjectSwitcher';
import { useAuth } from '../auth/AuthProvider';
import { visibleForNavigationState } from '../auth/permissions';
import { useAuthorization } from '../auth/AuthorizationProvider';
import { useProject } from '../auth/ProjectContext';
import { useQuery } from '@tanstack/react-query';
import { getEffectiveAuthorization } from '../api/authorization';

export function AppShell() {
  return (
    <div className="shell shell--public">
      <header className="public-header">
        <Link className="brand" to="/">
          <span className="brand-mark">A</span>
          <span>AgentTeams</span>
        </Link>
        <Link className="button button--primary" to="/login">
          登录
        </Link>
      </header>
      <main className="public-main">
        <section className="hero-card">
          <p className="eyebrow">OPERATIONS CONSOLE</p>
          <h1>让 Agent 团队运行得更清晰。</h1>
          <p className="hero-copy">
            在一个以 Project 为中心的工作台中，管理 Team、Task 与
            Worker，快速定位运行状态并安全执行操作。
          </p>
          <Link className="button button--primary" to="/console">
            进入控制台
          </Link>
        </section>
      </main>
    </div>
  );
}

const navGroups = [
  {
    label: '工作区',
    items: [
      { to: 'overview', label: '概览', icon: '◈' },
      { to: 'tasks', label: '任务', icon: '✓' },
      { to: 'scheduled-tasks', label: '定时任务', icon: '◷' },
      { to: 'teams', label: '团队', icon: '◇' },
      { to: 'workers', label: '工作节点', icon: '⬡' },
      { to: 'conversations', label: '对话', icon: '◌' },
    ],
  },
  {
    label: '智能体资源',
    items: [
      { to: 'templates', label: '模板', icon: '▤' },
      { to: 'agentspecs', label: 'Agent Specs', icon: '◆' },
      { to: 'models', label: '模型', icon: '◉' },
      { to: 'mcp', label: 'MCP', icon: '⌘' },
      { to: 'skills', label: '技能', icon: '✦' },
      { to: 'memory', label: '记忆', icon: '◌' },
      { to: 'sandboxes', label: '沙箱', icon: '□' },
    ],
  },
  {
    label: '治理与监控',
    items: [
      { to: 'usage', label: '用量', icon: '▥' },
      { to: 'budgets', label: '预算', icon: '◫' },
      { to: 'alerts', label: '告警', icon: '!' },
      { to: 'audit', label: '审计', icon: '≡' },
      { to: 'artifacts', label: '成果物', icon: '◇' },
      { to: 'evaluations', label: '评估', icon: '◎' },
      { to: 'quality', label: '成果质量', icon: '▤' },
    ],
  },
];

const settingsItems = [
  { to: '/settings/identity', label: '身份与权限', icon: '⚙' },
  { to: '/settings/organizations', label: '组织', icon: '◎' },
  { to: '/settings/integrations', label: '集成凭据', icon: '⌁' },
  { to: '/settings/roles', label: '角色与授权', icon: '♙' },
  { to: '/settings/projects', label: '项目', icon: '▦' },
];

export function ConsoleLayout() {
  const auth = useAuth();
  const authorization = useAuthorization();
  const { projectId: selectedProjectId } = useProject();
  const selectedProjectAuthorization = useQuery({
    queryKey: ['effective-authorization', auth.user?.profile.sub, selectedProjectId],
    queryFn: () => getEffectiveAuthorization(selectedProjectId),
    enabled: auth.status === 'authenticated' && Boolean(selectedProjectId),
    retry: false,
    staleTime: 30_000,
  });
  const permissionPrincipal = useMemo(
    () => ({
      permissions: [
        ...(authorization.failed ? [] : authorization.permissions),
        ...(selectedProjectAuthorization.data?.permissions || []),
      ],
    }),
    [
      authorization.failed,
      authorization.permissions,
      selectedProjectAuthorization.data?.permissions,
    ],
  );
  const { pathname } = useLocation();
  const navigate = useNavigate();
  const [isQuickNavOpen, setIsQuickNavOpen] = useState(false);
  const [quickNavQuery, setQuickNavQuery] = useState('');
  // 设置空间（/settings/*）不在 /:projectId 路由段内，概览等项目菜单的相对链接无法解析到项目页，
  // 统一回退到项目入口页由用户选择项目；侧边栏本身保持可见。
  const projectNavItemTarget = pathname.startsWith('/settings') ? '/console' : undefined;
  const pathParts = pathname.split('/').filter(Boolean);
  const projectId =
    pathParts[0] && !['settings', 'console', 'login', 'auth'].includes(pathParts[0])
      ? pathParts[0]
      : undefined;
  const quickNavItems = useMemo(
    () => [
      ...navGroups.flatMap((group) =>
        group.items
          .filter((item) =>
            visibleForNavigationState(
              permissionPrincipal,
              item.to,
              authorization.loaded,
              authorization.failed,
            ),
          )
          .map((item) => ({
            label: item.label,
            group: group.label,
            icon: item.icon,
            to: projectNavItemTarget
              ? '/console'
              : projectId
                ? `/${projectId}/${item.to}`
                : '/console',
          })),
      ),
      ...settingsItems
        .filter((item) =>
          visibleForNavigationState(
            permissionPrincipal,
            item.to.split('/').at(-1) || '',
            authorization.loaded,
            authorization.failed,
          ),
        )
        .map((item) => ({ ...item, group: '系统设置' })),
    ],
    [
      authorization.failed,
      authorization.loaded,
      projectId,
      projectNavItemTarget,
      permissionPrincipal,
    ],
  );
  const filteredQuickNavItems = quickNavItems.filter((item) =>
    `${item.label} ${item.group}`.toLowerCase().includes(quickNavQuery.trim().toLowerCase()),
  );

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setIsQuickNavOpen(true);
      }
      if (event.key === 'Escape') {
        setIsQuickNavOpen(false);
      }
    };
    window.addEventListener('keydown', handleShortcut);
    return () => window.removeEventListener('keydown', handleShortcut);
  }, []);

  const openQuickNav = () => {
    setQuickNavQuery('');
    setIsQuickNavOpen(true);
  };

  const closeNavigationMenus = () => {
    document
      .querySelectorAll<HTMLDetailsElement>('.console-shell .nav-group[open]')
      .forEach((group) => {
        group.open = false;
      });
  };

  return (
    <div className="console-shell">
      <aside className="sidebar" aria-label="控制台导航">
        <Link className="brand" to="overview">
          <span className="brand-mark">A</span>
          <span className="sidebar-brand-name">AgentTeams</span>
        </Link>
        <p className="sidebar-caption">OPERATIONS CONSOLE</p>
        <nav className="main-nav" aria-label="主导航">
          {navGroups.map((group) => {
            const items = group.items.filter((item) =>
              visibleForNavigationState(
                permissionPrincipal,
                item.to,
                authorization.loaded,
                authorization.failed,
              ),
            );
            if (items.length === 0) return null;
            return (
              <details
                className={`nav-group${
                  group.items.some((item) => pathname.endsWith(`/${item.to}`))
                    ? ' nav-group--active'
                    : ''
                }`}
                key={group.label}
              >
                <summary>
                  <span>{group.label}</span>
                  <span className="nav-group-chevron" aria-hidden="true">
                    ⌄
                  </span>
                </summary>
                <div className="nav-group-items">
                  {items.map((item) => (
                    <NavLink
                      className={({ isActive }) =>
                        isActive ? 'nav-item nav-item--active' : 'nav-item'
                      }
                      to={projectNavItemTarget ?? item.to}
                      key={item.to}
                      title={item.label}
                      aria-label={item.label}
                      onClick={closeNavigationMenus}
                    >
                      <span className="nav-icon" aria-hidden="true">
                        {item.icon}
                      </span>
                      <span className="nav-label">{item.label}</span>
                    </NavLink>
                  ))}
                </div>
              </details>
            );
          })}
          {settingsItems.some((item) =>
            visibleForNavigationState(
              permissionPrincipal,
              item.to.split('/').at(-1) || '',
              authorization.loaded,
              authorization.failed,
            ),
          ) && (
            <details
              className={`nav-group nav-group--settings${
                pathname.startsWith('/settings') ? ' nav-group--active' : ''
              }`}
            >
              <summary>
                <span>系统设置</span>
                <span className="nav-group-chevron" aria-hidden="true">
                  ⌄
                </span>
              </summary>
              <div className="nav-group-items">
                {settingsItems
                  .filter((item) =>
                    visibleForNavigationState(
                      permissionPrincipal,
                      item.to.split('/').at(-1) || '',
                      authorization.loaded,
                      authorization.failed,
                    ),
                  )
                  .map((item) => (
                    <NavLink
                      className={({ isActive }) =>
                        isActive ? 'nav-item nav-item--active' : 'nav-item'
                      }
                      to={item.to}
                      key={item.to}
                      title={item.label}
                      aria-label={item.label}
                      onClick={closeNavigationMenus}
                    >
                      <span className="nav-icon" aria-hidden="true">
                        {item.icon}
                      </span>
                      <span className="nav-label">{item.label}</span>
                    </NavLink>
                  ))}
              </div>
            </details>
          )}
        </nav>
        <div className="sidebar-footer">
          <span className="status-dot status-dot--online" />
          <span className="sidebar-status-label">Control Plane 已连接</span>
        </div>
      </aside>
      <div className="console-body">
        <header className="topbar">
          <div className="topbar-main">
            <ProjectSwitcher />
            <button className="console-quick-search" type="button" onClick={openQuickNav}>
              <span aria-hidden="true">⌕</span>
              <span>搜索页面与功能</span>
              <kbd>⌘ K</kbd>
            </button>
          </div>
          <div className="user-menu">
            <span className="avatar">
              {String(auth.user?.profile.name || auth.user?.profile.preferred_username || 'U')
                .slice(0, 1)
                .toUpperCase()}
            </span>
            <span>
              {auth.user?.profile.name || auth.user?.profile.preferred_username || '当前用户'}
            </span>
            <button
              className="button button--ghost button--small"
              onClick={() => void auth.logout()}
            >
              退出
            </button>
          </div>
        </header>
        <main>
          <Outlet />
        </main>
      </div>
      {isQuickNavOpen && (
        <div className="quick-nav-backdrop" onMouseDown={() => setIsQuickNavOpen(false)}>
          <section
            className="quick-nav-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="快速跳转"
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="quick-nav-input-wrap">
              <span aria-hidden="true">⌕</span>
              <input
                autoFocus
                aria-label="搜索页面与功能"
                placeholder="搜索页面与功能…"
                value={quickNavQuery}
                onChange={(event) => setQuickNavQuery(event.target.value)}
              />
              <kbd>ESC</kbd>
            </div>
            <div className="quick-nav-results" role="listbox" aria-label="跳转结果">
              {filteredQuickNavItems.length ? (
                filteredQuickNavItems.map((item) => (
                  <button
                    className="quick-nav-result"
                    type="button"
                    role="option"
                    aria-selected="false"
                    key={`${item.group}-${item.label}`}
                    onClick={() => {
                      navigate(item.to);
                      setIsQuickNavOpen(false);
                    }}
                  >
                    <span className="quick-nav-result-icon" aria-hidden="true">
                      {item.icon}
                    </span>
                    <span>{item.label}</span>
                    <small>{item.group}</small>
                  </button>
                ))
              ) : (
                <p className="quick-nav-empty">没有匹配的页面</p>
              )}
            </div>
          </section>
        </div>
      )}
    </div>
  );
}
