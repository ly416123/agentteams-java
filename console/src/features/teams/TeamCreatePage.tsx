import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useCreateTeam } from '../../queries/useTeamQueries';
import { ErrorState } from '../../components/ErrorState';

const steps = ['基本信息', 'Leader', '成员 Agent', '调度策略'];
export function TeamCreatePage({ projectId }: { projectId: string }) {
  const [step, setStep] = useState(0);
  const [form, setForm] = useState({
    name: '',
    displayName: '',
    maxConcurrentTasks: 4,
    requireHumanApproval: false,
  });
  const create = useCreateTeam(projectId);
  const navigate = useNavigate();
  const next = () => setStep((value) => Math.min(value + 1, steps.length - 1));
  const submit = () =>
    create.mutate(form, { onSuccess: (team) => navigate(`/${projectId}/teams/${team.id}`) });
  return (
    <div className="page narrow-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">NEW RESOURCE</p>
          <h1>创建 Team</h1>
          <p>分步完成团队基本信息、成员与运行策略。</p>
        </div>
      </div>
      <div className="stepper">
        {steps.map((label, index) => (
          <div className={index <= step ? 'step step--active' : 'step'} key={label}>
            <span>{index + 1}</span>
            {label}
          </div>
        ))}
      </div>
      <section className="panel form-panel">
        {step === 0 && (
          <>
            <h2>基本信息</h2>
            <label>
              显示名称
              <input
                aria-label="显示名称"
                value={form.displayName}
                onChange={(event) =>
                  setForm({
                    ...form,
                    displayName: event.target.value,
                    name: event.target.value.toLowerCase().replace(/\s+/g, '-'),
                  })
                }
              />
            </label>
            <label>
              内部名称
              <input
                aria-label="内部名称"
                value={form.name}
                onChange={(event) => setForm({ ...form, name: event.target.value })}
              />
            </label>
          </>
        )}
        {step === 1 && (
          <>
            <h2>选择 Leader</h2>
            <p className="muted">可在创建后从 Worker 列表中绑定 Leader Agent。</p>
            <select aria-label="Leader Agent">
              <option>稍后设置</option>
            </select>
          </>
        )}
        {step === 2 && (
          <>
            <h2>选择成员 Agent</h2>
            <p className="muted">先创建 Team，之后可以在成员页签添加 Agent。</p>
            <div className="info-box">成员配置可以在 Team 详情中继续完成。</div>
          </>
        )}
        {step === 3 && (
          <>
            <h2>调度策略</h2>
            <label>
              最大并发任务数
              <input
                type="number"
                min="1"
                value={form.maxConcurrentTasks}
                onChange={(event) =>
                  setForm({ ...form, maxConcurrentTasks: Number(event.target.value) })
                }
              />
            </label>
            <label className="checkbox">
              <input
                type="checkbox"
                checked={form.requireHumanApproval}
                onChange={(event) =>
                  setForm({ ...form, requireHumanApproval: event.target.checked })
                }
              />
              需要人工审批
            </label>
          </>
        )}
        {create.isError && <ErrorState error={create.error} />}
      </section>
      <div className="form-actions">
        <button
          className="button button--ghost"
          onClick={() => (step ? setStep(step - 1) : navigate(`/${projectId}/teams`))}
        >
          上一步
        </button>
        {step < steps.length - 1 ? (
          <button className="button button--primary" onClick={next}>
            下一步
          </button>
        ) : (
          <button className="button button--primary" disabled={create.isPending} onClick={submit}>
            {create.isPending ? '创建中…' : '创建 Team'}
          </button>
        )}
      </div>
    </div>
  );
}
