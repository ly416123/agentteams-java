import type { ReactNode } from 'react';

export function RawDataDisclosure({
  value,
  label = '查看原始数据',
}: {
  value: unknown;
  label?: string;
}) {
  let serialized: string;
  try {
    serialized =
      typeof value === 'string' ? value : (JSON.stringify(value, null, 2) ?? String(value));
  } catch {
    serialized = String(value);
  }
  return <Disclosure label={label}>{serialized}</Disclosure>;
}

export function Disclosure({ label, children }: { label: string; children: ReactNode }) {
  return (
    <details className="raw-data-disclosure">
      <summary>{label}</summary>
      <div className="raw-data-disclosure__content">{children}</div>
    </details>
  );
}
