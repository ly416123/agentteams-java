export function CursorPagination({
  hasPrevious,
  hasNext,
  onPrevious,
  onNext,
}: {
  hasPrevious: boolean;
  hasNext: boolean;
  onPrevious: () => void;
  onNext: () => void;
}) {
  return (
    <nav className="pagination" aria-label="分页">
      <button className="button button--ghost" disabled={!hasPrevious} onClick={onPrevious}>
        上一页
      </button>
      <button className="button button--ghost" disabled={!hasNext} onClick={onNext}>
        下一页
      </button>
    </nav>
  );
}
