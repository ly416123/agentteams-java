import { statusMeta } from '../i18n/labels';

export function StatusBadge({ phase }: { phase: string }) {
  const status = statusMeta(phase);
  const unknown = status.label === '未知状态';
  return (
    <span
      className={`status-badge status-badge--${status.tone}`}
      data-tone={status.tone}
      role="status"
      title={
        unknown
          ? '后端返回了暂未识别的状态，请检查服务端状态说明。'
          : phase.trim().toUpperCase() === 'UNKNOWN'
            ? '数据来源返回 UNKNOWN，当前状态尚未确认；请检查最近观测记录。'
            : undefined
      }
    >
      <span className="status-dot" aria-hidden="true" />
      {status.label}
    </span>
  );
}
