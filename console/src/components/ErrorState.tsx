import { ApiError } from '../api/httpClient';
import type { ApiErrorShape } from '../api/types';

export function ErrorState({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const value = error as Partial<ApiErrorShape>;
  const status = error instanceof ApiError ? error.status : value.status;
  const title =
    status === 403
      ? '无权访问'
      : status === 429
        ? '请求受到限流'
        : status === 503
          ? '依赖暂不可用'
          : '加载失败';
  const message =
    status === 403 ? '当前账号没有访问此资源的权限。' : value.message || '请稍后重试。';
  return (
    <div className="state-card state-card--error" role="alert">
      <span className="state-icon">!</span>
      <div>
        <h3>{title}</h3>
        <p>{message}</p>
      </div>
      {onRetry && status !== 403 && <button onClick={onRetry}>重试</button>}
    </div>
  );
}
