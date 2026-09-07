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
  const [taskType, setTaskType] = useState('NORMAL');
  const [inputJson, setInputJson] = useState('');
  const [formError, setFormError] = useState('');
  const projects = useProjects();
  const teams = useTeams(projectId, {});
  const create = useCreateTask(projectId);
  const navigate = useNavigate();
  const project = projects.data?.items.find((item) => item.id === projectId);
  const selectedTeam = teams.data?.items.find((team) => team.id === teamId);
  const submit = () => {
    if (!project || !selectedTeam) {
      setFormError('必须先选择真实项目和团队，才能生成带作用域的任务。');
      return;
    }
    let inputPayload: Record<string, unknown> | undefined;
    if (inputJson.trim()) {
      try {
        const parsed: unknown = JSON.parse(inputJson);
        if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
          setFormError('任务输入 JSON 必须是一个 JSON 对象，例如 {"prompt":"…"}。');
          return;
        }
        inputPayload = parsed as Record<string, unknown>;
      } catch {
        setFormError('任务输入 JSON 不是合法 JSON，请检查后再提交。');
        return;
      }
    }
    setFormError('');
    create.mutate(
      {
        title,
        description,
        taskType: taskType.trim() || 'NORMAL',
        spec: {
          scope: { tenant: project.tenantId, project: project.name, team: selectedTeam.name },
          teamId: selectedTeam.id,
          ...(workerId.trim() ? { workerId: workerId.trim() } : {}),
          ...(inputPayload ? { inputJson: inputPayload } : {}),
        },
      },
      { onSuccess: (task) => navigate(`/${projectId}/tasks/${task.id}`) },
    );
  };
  return (
    <div className="page narrow-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">新建资源</p>
          <h1>创建任务</h1>
          <p>提交任务后，服务端会根据团队策略完成调度。</p>
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
          团队
          <select
            aria-label="任务团队"
            value={teamId}
            onChange={(event) => setTeamId(event.target.value)}
            disabled={teams.isLoading || teams.isError}
          >
            <option value="">请选择团队</option>
            {teams.data?.items.map((team) => (
              <option value={team.id} key={team.id}>
                {team.displayName}（{team.name}）
              </option>
            ))}
          </select>
        </label>
        <label>
          任务输入 JSON（可选）
          <textarea
            aria-label="任务输入 JSON"
            rows={4}
            placeholder='{"prompt":"按 artifacts 协议产出两个交付物"}'
            value={inputJson}
            onChange={(event) => setInputJson(event.target.value)}
          />
        </label>
        <label>
          任务类型
          <input
            aria-label="任务类型"
            placeholder="NORMAL / SCHEDULED / 自定义类型"
            value={taskType}
            onChange={(event) => setTaskType(event.target.value)}
          />
        </label>
        <label>
          工作节点（可选）
          <input
            aria-label="任务工作节点"
            placeholder="填写后端可识别的工作节点 ID"
            value={workerId}
            onChange={(event) => setWorkerId(event.target.value)}
          />
        </label>
        {!project && !projects.isLoading && (
          <p className="error-text">当前项目不在可见项目列表中，无法安全创建任务。</p>
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
