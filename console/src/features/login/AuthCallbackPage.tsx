import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { useAuth } from '../../auth/AuthProvider';
import { consumeReturnTo } from '../../auth/oidc';

export function AuthCallbackPage() {
  const auth = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const started = useRef(-1);
  const [attempt, setAttempt] = useState(0);
  const [error, setError] = useState<unknown>();
  useEffect(() => {
    if (started.current === attempt) return;
    started.current = attempt;
    setError(undefined);
    auth
      .completeLogin()
      .then((user) => {
        if (!user) throw new Error('OIDC 登录回调未返回用户');
        navigate(consumeReturnTo(user.state, location.state?.from || '/'), { replace: true });
      })
      .catch((nextError) => {
        setError(nextError);
      });
  }, [attempt, auth, location.state, navigate]);
  if (error)
    return (
      <div className="login-page">
        <ErrorState
          error={error}
          title="OIDC 登录失败"
          message="登录回调无效或已过期，请重试或返回登录页。"
          onRetry={() => setAttempt((value) => value + 1)}
          onBack={() => navigate('/login', { replace: true })}
          backLabel="返回登录"
        />
      </div>
    );
  return <div className="loading-screen">正在完成登录…</div>;
}
