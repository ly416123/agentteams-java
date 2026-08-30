import { ApiError } from '../api/httpClient';
import type { ApiErrorShape } from '../api/types';

export function ErrorState({
  error,
  onRetry,
  onLogin,
  onBack,
  title: customTitle,
  message: customMessage,
  backLabel = '返回',
}: {
  error: unknown;
  onRetry?: () => void;
  onLogin?: () => void;
  onBack?: () => void;
  title?: string;
  message?: string;
  backLabel?: string;
}) {
  const value = error as Partial<ApiErrorShape>;
  const status = error instanceof ApiError ? error.status : value.status;
  const title =
    customTitle ||
    (status === 401
      ? '登录已失效'
      : status === 403
        ? '无权访问'
        : status === 409
          ? '资源版本冲突'
          : status === 429
            ? '请求受到限流'
            : status === 503
              ? '依赖暂不可用'
              : '加载失败');
  const message =
    customMessage ||
    (status === 401
      ? '登录凭证已过期，请重新登录后继续。'
      : status === 403
        ? '当前账号没有访问此资源的权限。'
        : status === 409
          ? '资源已被其他操作更新，请刷新后重试。'
          : value.message || '请稍后重试。');
  return (
    <div className="state-card state-card--error" role="alert">
      <span className="state-icon">!</span>
      <div>
        <h3>{title}</h3>
        <p>{message}</p>
      </div>
      {onBack ? (
        <button onClick={onBack}>{backLabel}</button>
      ) : status === 401 ? (
        <button onClick={onLogin || (() => window.location.assign('/login'))}>重新登录</button>
      ) : status === 403 ? null : (
        onRetry && <button onClick={onRetry}>{status === 409 ? '刷新后重试' : '重试'}</button>
      )}
    </div>
  );
}
