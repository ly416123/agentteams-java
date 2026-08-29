import { Link } from 'react-router-dom';
import { TASK_PHASES, type Task } from '../../api/types';
import { StatusBadge } from '../../components/StatusBadge';

export { TASK_PHASES } from '../../api/types';
export function TaskBoard({ projectId, tasks }: { projectId: string; tasks: Task[] }) {
  return (
    <div className="task-board">
      {TASK_PHASES.map((phase) => (
        <section className="board-column" data-phase={phase} aria-label={phase} key={phase}>
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
