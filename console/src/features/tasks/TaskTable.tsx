import { Link } from 'react-router-dom';
import type { Task } from '../../api/types';
import { ResourceTable } from '../../components/ResourceTable';
import { StatusBadge } from '../../components/StatusBadge';

export function TaskTable({ projectId, tasks }: { projectId: string; tasks: Task[] }) {
  return (
    <ResourceTable
      items={tasks}
      columns={[
        {
          key: 'title',
          header: '任务',
          render: (task) => (
            <Link className="resource-link" to={`/${projectId}/tasks/${task.id}`}>
              <strong>{task.title}</strong>
              <small>{task.description}</small>
            </Link>
          ),
        },
        { key: 'phase', header: '状态', render: (task) => <StatusBadge phase={task.phase} /> },
        { key: 'priority', header: '优先级', render: (task) => `P${task.priority}` },
        { key: 'team', header: 'Team', render: (task) => task.teamId || '—' },
        { key: 'worker', header: 'Worker', render: (task) => task.workerId || '待分配' },
        {
          key: 'updated',
          header: '更新时间',
          render: (task) => new Date(task.updatedAt).toLocaleString('zh-CN'),
        },
      ]}
    />
  );
}
