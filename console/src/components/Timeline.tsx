import type { ReactNode } from 'react';
import { formatDateTime } from '../utils/formatters';

export type TimelineItem = {
  id: string;
  title: string;
  description?: ReactNode;
  time?: string;
  tone?: string;
};

export function Timeline({ items }: { items: TimelineItem[] }) {
  if (!items.length) return <p className="muted">暂无运行记录</p>;
  return (
    <ol className="timeline">
      {items.map((item) => (
        <li key={item.id} className="timeline-item">
          <span className={`timeline-dot timeline-dot--${item.tone || 'neutral'}`} />
          <div>
            <strong>{item.title}</strong>
            {item.description && <p>{item.description}</p>}
            {item.time && <time>{formatDateTime(item.time)}</time>}
          </div>
        </li>
      ))}
    </ol>
  );
}
