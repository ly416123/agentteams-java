import type { ReactNode } from 'react';

export type ResourceColumn<T> = { key: string; header: string; render: (item: T) => ReactNode };

export function ResourceTable<T extends { id: string }>({
  columns,
  items,
  empty,
}: {
  columns: ResourceColumn<T>[];
  items: T[];
  empty?: ReactNode;
}) {
  if (!items.length && empty) return <>{empty}</>;
  return (
    <div className="table-wrap">
      <table className="resource-table">
        <thead>
          <tr>
            {columns.map((column) => (
              <th key={column.key}>{column.header}</th>
            ))}
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              {columns.map((column) => (
                <td key={column.key}>{column.render(item)}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
