import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { useCreateTask } from '../../queries/useTaskQueries';
import { useProjects } from '../../queries/useProjectQueries';
import { useTeams } from '../../queries/useTeamQueries';

export function TaskCreatePage({ projectId }: { projectId: string }) {
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [teamId, setTeamId] = useState('');
  const [workerId, setWorkerId] = useState('');
  const [formError, setFormError] = useState('');
  const projects = useProjects();
  const teams = useTeams(projectId, {});
  const create = useCreateTask(projectId);
  const navigate = useNavigate();
  const project = projects.data?.items.find((item) => item.id === projectId);
  const selectedTeam = teams.data?.items.find((team) => team.id === teamId);
  const submit = () => {
    if (!project || !selectedTeam) {
      setFormError('必须先选择真实 Project 和 Team，才能生成带作用域的任务。');
      return;
    }
    setFormError('');
    create.mutate(
      {
        title,
        description,
        spec: {
          scope: { tenant: project.tenantId, project: project.name, team: selectedTeam.name },
          teamId: selectedTeam.id,
          ...(workerId.trim() ? { workerId: workerId.trim() } : {}),
        },
      },
      { onSuccess: (task) => navigate(`/${projectId}/tasks/${task.id}`) },
    );
  };
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
        <label>
          Team
          <select
            aria-label="任务 Team"
            value={teamId}
            onChange={(event) => setTeamId(event.target.value)}
            disabled={teams.isLoading || teams.isError}
          >
            <option value="">请选择 Team</option>
            {teams.data?.items.map((team) => (
              <option value={team.id} key={team.id}>
                {team.displayName}（{team.name}）
              </option>
            ))}
          </select>
        </label>
        <label>
          Worker（可选）
          <input
            aria-label="任务 Worker"
            placeholder="填写后端可识别的 Worker ID"
            value={workerId}
            onChange={(event) => setWorkerId(event.target.value)}
          />
        </label>
        {!project && !projects.isLoading && (
          <p className="error-text">当前 Project 不在可见 Project 列表中，无法安全创建任务。</p>
        )}
        {teams.isError && <ErrorState error={teams.error} onRetry={() => void teams.refetch()} />}
        {formError && <p className="error-text">{formError}</p>}
        {create.isError && <ErrorState error={create.error} onRetry={submit} />}
      </section>
      <div className="form-actions">
        <button className="button button--ghost" onClick={() => navigate(`/${projectId}/tasks`)}>
          取消
        </button>
        <button
          className="button button--primary"
          disabled={!title || !selectedTeam || create.isPending}
          onClick={submit}
        >
          {create.isPending ? '创建中…' : '创建任务'}
        </button>
      </div>
    </div>
  );
}
