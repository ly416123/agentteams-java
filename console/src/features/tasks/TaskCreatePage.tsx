import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { useCreateTask } from '../../queries/useTaskQueries';

export function TaskCreatePage({ projectId }: { projectId: string }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const create = useCreateTask(projectId);
  const navigate = useNavigate();
  const submit = () =>
    create.mutate(
      { title, description, spec: {} },
      { onSuccess: (task) => navigate(`/${projectId}/tasks/${task.id}`) },
    );
  return (
    <div className="page narrow-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">NEW RESOURCE</p>
          <h1>创建任务</h1>
          <p>提交任务后，服务端会根据 Team 策略完成调度。</p>
        </div>
      </div>
      <section className="panel form-panel">
        <label>
          任务标题
          <input
            aria-label="任务标题"
            value={title}
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <label>
          任务说明
          <textarea
            aria-label="任务说明"
            rows={5}
            value={description}
            onChange={(event) => setDescription(event.target.value)}
          />
        </label>
        {create.isError && <ErrorState error={create.error} />}
      </section>
      <div className="form-actions">
        <button className="button button--ghost" onClick={() => navigate(`/${projectId}/tasks`)}>
          取消
        </button>
        <button
          className="button button--primary"
          disabled={!title || create.isPending}
          onClick={submit}
        >
          {create.isPending ? '创建中…' : '创建任务'}
        </button>
      </div>
    </div>
  );
}
