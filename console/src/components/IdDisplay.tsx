import { useState } from 'react';

/** Shows a short identifier by default while keeping the full value available on demand. */
export function IdDisplay({
  value,
  className = '',
}: {
  value?: string | null;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);
  const [expanded, setExpanded] = useState(false);
  if (!value) return <span className={`id-display ${className}`.trim()}>—</span>;
  const fullValue = value;
  const canCopy = Boolean((navigator as Navigator & { clipboard?: Clipboard }).clipboard);
  const shortValue =
    fullValue.length > 16 ? `${fullValue.slice(0, 8)}…${fullValue.slice(-4)}` : fullValue;

  async function copyValue() {
    if (!navigator.clipboard?.writeText) return;
    try {
      await navigator.clipboard.writeText(fullValue);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1400);
    } catch {
      setCopied(false);
    }
  }

  return (
    <span className={`id-display ${className}`.trim()} title={value}>
      <code>{shortValue}</code>
      <button
        aria-label={copied ? '已复制完整 ID' : '复制完整 ID'}
        className="id-display__copy"
        disabled={!canCopy}
        onClick={() => void copyValue()}
        title={canCopy ? '复制完整 ID' : '展开后可选择并复制完整 ID'}
        type="button"
      >
        {copied ? '已复制' : '复制'}
      </button>
      <button
        aria-expanded={expanded}
        aria-label={expanded ? '收起完整 ID' : '查看完整 ID'}
        className="id-display__copy"
        onClick={() => setExpanded((current) => !current)}
        type="button"
      >
        {expanded ? '收起' : '完整'}
      </button>
      {expanded && <code className="id-display__full">{fullValue}</code>}
    </span>
  );
}
