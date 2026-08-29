type Props = {
  open: boolean;
  actionLabel: string;
  description?: string;
  onCancel: () => void;
  onConfirm: () => void;
};

export function VersionConflictModal({
  open,
  actionLabel,
  description,
  onCancel,
  onConfirm,
}: Props) {
  if (!open) return null;
  return (
    <div className="modal-backdrop" role="presentation">
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="conflict-title">
        <p className="eyebrow">VERSION CONFLICT</p>
        <h2 id="conflict-title">资源状态已更新</h2>
        <p>{description || '其他操作已更新了这个资源，请确认是否基于最新状态继续。'}</p>
        <div className="modal-actions">
          <button className="button button--ghost" onClick={onCancel}>
            返回编辑
          </button>
          <button className="button button--danger" onClick={onConfirm}>
            仍然{actionLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
