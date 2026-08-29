type Props = {
  open: boolean;
  actionLabel: string;
  impact: string;
  onCancel: () => void;
  onConfirm: () => void;
};

export function ActionConfirmModal({ open, actionLabel, impact, onCancel, onConfirm }: Props) {
  if (!open) return null;
  const confirmLabel = /^[A-Za-z]/.test(actionLabel) ? `确认 ${actionLabel}` : `确认${actionLabel}`;
  return (
    <div className="modal-backdrop" role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="action-confirm-title">
        <p className="eyebrow">CONFIRM ACTION</p>
        <h2 id="action-confirm-title">{confirmLabel}</h2>
        <p>{impact}</p>
        <div className="modal-actions">
          <button className="button button--ghost" onClick={onCancel}>
            返回
          </button>
          <button className="button button--danger" onClick={onConfirm}>
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
