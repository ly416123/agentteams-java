import { useAuth } from '../../auth/AuthProvider';
import { useLocation } from 'react-router-dom';
import { useState } from 'react';
import { ErrorState } from '../../components/ErrorState';

export function LoginPage() {
  const auth = useAuth();
  const location = useLocation();
  const [loginError, setLoginError] = useState<unknown>();
  const returnTo = typeof location.state?.from === 'string' ? location.state.from : '/';
  const error = loginError || auth.error;
  if (error)
    return (
      <div className="login-page">
        <ErrorState
          error={error}
          title="登录服务不可用"
          message="无法初始化或跳转到组织登录，请重试。"
          onRetry={() => window.location.reload()}
        />
      </div>
    );
  return (
    <div className="login-page">
      <div className="login-card">
        <LinkBrand />
        <p className="eyebrow">SECURE ACCESS</p>
        <h1>登录控制台</h1>
        <p>使用组织的 OIDC 账号登录，访问已授权的 Project。</p>
        <button
          className="button button--primary button--wide"
          onClick={() => {
            setLoginError(undefined);
            void auth.login(returnTo).catch(setLoginError);
          }}
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
