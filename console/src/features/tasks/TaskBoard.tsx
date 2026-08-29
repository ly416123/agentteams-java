import { Link } from 'react-router-dom';
import type { Task } from '../../api/types';
import { StatusBadge } from '../../components/StatusBadge';

const columns = ['PENDING', 'CREATED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED'];
export function TaskBoard({ projectId, tasks }: { projectId: string; tasks: Task[] }) {
  return (
    <div className="task-board">
      {columns.map((phase) => (
        <section className="board-column" key={phase}>
          <div className="board-column-heading">
            <h2>
              <StatusBadge phase={phase} />
            </h2>
            <span>{tasks.filter((task) => task.phase === phase).length}</span>
          </div>
          {tasks
            .filter((task) => task.phase === phase)
            .map((task) => (
              <Link className="task-card" key={task.id} to={`/${projectId}/tasks/${task.id}`}>
                <strong>{task.title}</strong>
                <p>{task.summary || task.description}</p>
                <StatusBadge phase={task.phase} />
              </Link>
            ))}
        </section>
      ))}
    </div>
  );
}
