import { useAuth } from '../../auth/AuthProvider';
import { useLocation } from 'react-router-dom';

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const returnTo = typeof location.state?.from === 'string' ? location.state.from : '/';
  return (
    <div className="login-page">
      <div className="login-card">
        <LinkBrand />
        <p className="eyebrow">SECURE ACCESS</p>
        <h1>登录控制台</h1>
        <p>使用组织的 OIDC 账号登录，访问已授权的 Project。</p>
        <button
          className="button button--primary button--wide"
          onClick={() => void auth.login(returnTo)}
          disabled={auth.status === 'loading'}
        >
          使用组织账号登录
        </button>
        <small>登录后将返回你上次访问的页面。Access Token 仅保存在当前会话内存中。</small>
      </div>
    </div>
  );
}

function LinkBrand() {
  return (
    <div className="login-brand">
      <span className="brand-mark">A</span>
      <strong>AgentTeams</strong>
    </div>
  );
}
