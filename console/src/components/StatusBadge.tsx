import { statusMeta } from '../i18n/labels';

export function StatusBadge({ phase }: { phase: string }) {
  const status = statusMeta(phase);
  return (
    <span
      className={`status-badge status-badge--${status.tone}`}
      data-tone={status.tone}
      role="status"
    >
      <span className="status-dot" aria-hidden="true" />
      {status.label}
    </span>
  );
}
