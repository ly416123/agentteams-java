import { useEffect } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { useAuth } from '../../auth/AuthProvider';

export function AuthCallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  useEffect(() => {
    auth
      .completeLogin()
      .then(() => navigate(location.state?.from || '/', { replace: true }))
      .catch(() => undefined);
  }, [auth, location.state, navigate]);
  if (auth.status === 'unauthenticated')
    return (
      <div className="login-page">
        <ErrorState error={{ status: 401, message: 'OIDC 登录回调无效，请重新登录。' }} />
      </div>
    );
  return <div className="loading-screen">正在完成登录…</div>;
}
