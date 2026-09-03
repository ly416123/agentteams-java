import { Link, NavLink, Outlet } from 'react-router-dom';
import { ProjectSwitcher } from '../features/projects/ProjectSwitcher';
import { useAuth } from '../auth/AuthProvider';

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

const navItems = [
  { to: 'overview', label: '概览', icon: '◈' },
  { to: 'tasks', label: 'Tasks', icon: '✓' },
  { to: 'scheduled-tasks', label: '定时任务', icon: '◷' },
  { to: 'teams', label: 'Teams', icon: '◇' },
  { to: 'workers', label: 'Workers', icon: '⬡' },
  { to: 'conversations', label: '对话', icon: '◌' },
  { to: 'templates', label: 'Templates', icon: '▤' },
  { to: 'agentspecs', label: 'Agent Specs', icon: '◆' },
  { to: 'models', label: 'Models', icon: '◉' },
  { to: 'mcp', label: 'MCP', icon: '⌘' },
  { to: 'skills', label: 'Skills', icon: '✦' },
  { to: 'usage', label: 'Usage', icon: '▥' },
  { to: 'budgets', label: 'Budgets', icon: '◫' },
  { to: 'alerts', label: 'Alerts', icon: '!' },
  { to: 'audit', label: 'Audit', icon: '≡' },
  { to: 'memory', label: 'Memory', icon: '◌' },
  { to: 'sandboxes', label: 'Sandboxes', icon: '□' },
  { to: 'artifacts', label: 'Artifacts', icon: '◇' },
];

export function ConsoleLayout() {
  const auth = useAuth();
  return (
    <div className="console-shell">
      <aside className="sidebar">
        <Link className="brand" to="overview">
          <span className="brand-mark">A</span>
          <span>AgentTeams</span>
        </Link>
        <p className="sidebar-caption">OPERATIONS CONSOLE</p>
        <nav className="main-nav" aria-label="主导航">
          {navItems.map((item) => (
            <NavLink
              className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
              to={item.to}
              key={item.to}
            >
              <span>{item.icon}</span>
              {item.label}
            </NavLink>
          ))}
          <NavLink
            className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
            to="/settings/identity"
          >
            <span>⚙</span>
            身份管理
          </NavLink>
          <NavLink
            className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
            to="/settings/organizations"
          >
            <span>◎</span>
            组织管理
          </NavLink>
          <NavLink
            className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
            to="/settings/integrations"
          >
            <span>⌁</span>
            集成凭据
          </NavLink>
          <NavLink
            className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
            to="/settings/roles"
          >
            <span>♙</span>
            角色与权限
          </NavLink>
          <NavLink
            className={({ isActive }) => (isActive ? 'nav-item nav-item--active' : 'nav-item')}
            to="/settings/projects"
          >
            <span>▦</span>
            Project 管理
          </NavLink>
        </nav>
        <div className="sidebar-footer">
          <span className="status-dot status-dot--online" />
          Control Plane 已连接
        </div>
      </aside>
      <div className="console-body">
        <header className="topbar">
          <ProjectSwitcher />
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
    </div>
  );
}
