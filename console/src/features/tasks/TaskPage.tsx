import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { useTasks } from '../../queries/useTaskQueries';
import { TaskBoard } from './TaskBoard';
import { TaskTable } from './TaskTable';
import { CursorPagination } from '../../components/CursorPagination';
import { TASK_PHASES, type TaskPhase } from '../../api/types';

export function TaskPage({ projectId }: { projectId: string }) {
  const [view, setView] = useState<'board' | 'table'>('board');
  const [search, setSearch] = useState('');
  const [phase, setPhase] = useState<TaskPhase | ''>('');
  const [cursor, setCursor] = useState<string | undefined>();
  const [cursorHistory, setCursorHistory] = useState<string[]>([]);
  const tasks = useTasks(projectId, { q: search, phase }, view, cursor);
  const items = tasks.data?.items || [];
  useEffect(() => {
    setCursor(undefined);
    setCursorHistory([]);
  }, [search, phase, view]);
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">RESOURCE / TASKS</p>
          <h1>Tasks</h1>
          <p>从看板或列表跟踪任务状态、执行 Worker 和运行结果。</p>
        </div>
        <Link className="button button--primary" to={`/${projectId}/tasks/new`}>
          创建任务
        </Link>
      </div>
      <div className="toolbar">
        <input
          placeholder="搜索任务"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <select
          aria-label="任务状态"
          value={phase}
          onChange={(event) => setPhase(event.target.value as TaskPhase | '')}
        >
          <option value="">全部状态</option>
          {TASK_PHASES.map((taskPhase) => (
            <option value={taskPhase} key={taskPhase}>
              {taskPhase}
            </option>
          ))}
        </select>
        <div className="view-toggle">
          <button
            className={view === 'board' ? 'button button--active' : 'button button--ghost'}
            onClick={() => setView('board')}
          >
            看板视图
          </button>
          <button
            className={view === 'table' ? 'button button--active' : 'button button--ghost'}
            onClick={() => setView('table')}
          >
            列表视图
          </button>
        </div>
        <button className="button button--ghost" onClick={() => void tasks.refetch()}>
          刷新
        </button>
      </div>
      {tasks.isLoading ? (
        <div className="panel loading-block">加载任务…</div>
      ) : tasks.isError ? (
        <ErrorState error={tasks.error} onRetry={() => void tasks.refetch()} />
      ) : !items.length ? (
        <EmptyState
          title="暂无任务"
          description="创建一个任务开始运行。"
          action={
            <Link className="button button--primary" to={`/${projectId}/tasks/new`}>
              创建任务
            </Link>
          }
        />
      ) : view === 'board' ? (
        <TaskBoard projectId={projectId} tasks={items} />
      ) : (
        <TaskTable projectId={projectId} tasks={items} />
      )}
      {!tasks.isLoading && !tasks.isError && items.length > 0 && (
        <CursorPagination
          hasPrevious={cursorHistory.length > 0}
          hasNext={Boolean(tasks.data?.nextCursor)}
          onPrevious={() => {
            const previous = cursorHistory[cursorHistory.length - 1];
            setCursorHistory((history) => history.slice(0, -1));
            setCursor(previous);
          }}
          onNext={() => {
            if (!tasks.data?.nextCursor) return;
            setCursorHistory((history) => [...history, cursor || '']);
            setCursor(tasks.data.nextCursor || undefined);
          }}
        />
      )}
    </div>
  );
}
