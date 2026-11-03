import { Link } from 'react-router-dom';
import { TASK_PHASES, type Task } from '../../api/types';
import { StatusBadge } from '../../components/StatusBadge';
import { labelStatus } from '../../i18n/labels';

export { TASK_PHASES } from '../../api/types';
export function TaskBoard({ projectId, tasks }: { projectId: string; tasks: Task[] }) {
  return (
    <div
      aria-label="任务状态看板，可横向滚动查看全部状态"
      className="task-board"
      role="region"
      tabIndex={0}
    >
      {TASK_PHASES.map((phase) => (
        <section
          className="board-column"
          data-phase={phase}
          aria-label={labelStatus(phase)}
          key={phase}
        >
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
