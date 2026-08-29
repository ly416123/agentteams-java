export type TimelineItem = {
  id: string;
  title: string;
  description?: string;
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
            {item.time && <time>{new Date(item.time).toLocaleString('zh-CN')}</time>}
          </div>
        </li>
      ))}
    </ol>
  );
}
