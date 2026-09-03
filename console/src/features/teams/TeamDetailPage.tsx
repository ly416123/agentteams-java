import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import {
  useTeam,
  useTeamDeployments,
  useTeamMembers,
  useTeamPolicy,
  useTeamRevisions,
  useAddTeamMember,
  useCreateTeamRevision,
  useDeployTeamRevision,
  usePublishTeamRevision,
  useRemoveTeamMember,
  useRetryTeamDeployment,
  useReviewTeamRevision,
  useRollbackTeam,
  useUpdateTeamPolicy,
} from '../../queries/useTeamQueries';
import { useWorkers } from '../../queries/useWorkerQueries';

const tabs = ['概览', '成员 Agent', '策略', '版本与部署', '运行记录'] as const;
export function TeamDetailPage({ projectId, teamId }: { projectId: string; teamId: string }) {
  const [tab, setTab] = useState<(typeof tabs)[number]>('概览');
  const team = useTeam(projectId, teamId);
  const members = useTeamMembers(projectId, teamId);
  const workers = useWorkers(projectId, { search: '', phase: '', cursor: undefined });
  const policy = useTeamPolicy(projectId, teamId);
  const revisions = useTeamRevisions(projectId, teamId);
  const deployments = useTeamDeployments(projectId, teamId);
  const addMember = useAddTeamMember(projectId, teamId);
  const removeMember = useRemoveTeamMember(projectId, teamId);
  const updatePolicy = useUpdateTeamPolicy(projectId, teamId);
  const createRevision = useCreateTeamRevision(projectId, teamId);
  const reviewRevision = useReviewTeamRevision(projectId, teamId);
  const publishRevision = usePublishTeamRevision(projectId, teamId);
  const deployRevision = useDeployTeamRevision(projectId, teamId);
  const retryDeployment = useRetryTeamDeployment(projectId, teamId);
  const rollbackTeam = useRollbackTeam(projectId, teamId);
  const [memberForm, setMemberForm] = useState({ agentId: '', role: 'MEMBER' });
  const [lastRemovedAgentId, setLastRemovedAgentId] = useState<string>();
  const [policyForm, setPolicyForm] = useState({
    maxConcurrentTasks: '',
    requireHumanApproval: false,
    allowedRuntimes: '',
    requiredCapabilities: '',
  });
  const [revisionForm, setRevisionForm] = useState({
    leaderAgentId: '',
    memberAgentIds: [] as string[],
    overlayJson: '{}',
    actor: '',
  });
  const [deploymentForm, setDeploymentForm] = useState({ revision: '', members: '[]', actor: '' });
  const [lifecycleNotice, setLifecycleNotice] = useState<string>();
  useEffect(() => {
    if (!policy.data) return;
    setPolicyForm({
      maxConcurrentTasks: String(policy.data.maxConcurrentTasks),
      requireHumanApproval: policy.data.requireHumanApproval,
      allowedRuntimes: policy.data.allowedRuntimes.join(', '),
      requiredCapabilities: policy.data.requiredCapabilities.join(', '),
    });
  }, [policy.data]);
  const savePolicy = () => {
    if (!policy.data) return;
    updatePolicy.mutate({
      maxConcurrentTasks: Number(policyForm.maxConcurrentTasks),
      requireHumanApproval: policyForm.requireHumanApproval,
      allowedRuntimes: splitValues(policyForm.allowedRuntimes),
      requiredCapabilities: splitValues(policyForm.requiredCapabilities),
      expectedVersion: policy.data.version,
    });
  };
  const lifecycleError =
    createRevision.error ||
    reviewRevision.error ||
    publishRevision.error ||
    deployRevision.error ||
    retryDeployment.error ||
    rollbackTeam.error;
  const submitRevision = (event: FormEvent) => {
    event.preventDefault();
    createRevision.mutate(
      {
        leaderAgentId: revisionForm.leaderAgentId,
        memberAgentIds: revisionForm.memberAgentIds,
        overlayJson: revisionForm.overlayJson,
        actor: revisionForm.actor || undefined,
      },
      {
        onSuccess: () => {
          setLifecycleNotice('Team Revision 草稿已创建');
          setRevisionForm({ leaderAgentId: '', memberAgentIds: [], overlayJson: '{}', actor: '' });
        },
      },
    );
  };
  const submitDeployment = (event: FormEvent) => {
    event.preventDefault();
    let members: Array<{ agentId: string; baseManifest: string; taskOverlay?: string }>;
    try {
      const parsed = JSON.parse(deploymentForm.members) as unknown;
      if (!Array.isArray(parsed)) throw new Error('members 必须是数组');
      members = parsed as Array<{ agentId: string; baseManifest: string; taskOverlay?: string }>;
    } catch {
      setLifecycleNotice('Deployment members JSON 格式无效');
      return;
    }
    deployRevision.mutate(
      {
        revision: Number(deploymentForm.revision),
        body: { members, actor: deploymentForm.actor || undefined },
      },
      { onSuccess: () => setLifecycleNotice('Deployment 已提交') },
    );
  };
  if (team.isLoading)
    return (
      <div className="page">
        <div className="loading-block panel">加载 Team…</div>
      </div>
    );
  if (team.isError || !team.data)
    return (
      <div className="page">
        <ErrorState error={team.error} onRetry={() => void team.refetch()} />
      </div>
    );
  const workerItems = workers.data?.items || [];
  const workerById = new Map(workerItems.map((worker) => [worker.id, worker]));
  const activeMemberIds = (members.data || [])
    .filter((member) => member.status === 'ACTIVE')
    .map((member) => member.agentId);
  const availableWorkers = workerItems.filter((worker) => !activeMemberIds.includes(worker.id));
  const activeTeamWorkers = activeMemberIds
    .map((agentId) => workerById.get(agentId))
    .filter((worker): worker is (typeof workerItems)[number] => Boolean(worker));
  const leaderTeamWorkers = activeTeamWorkers.filter((worker) => worker.workerType === 'LEADER');
  return (
    <div className="page">
      <Link className="back-link" to={`/${projectId}/teams`}>
        ← 返回 Teams
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">TEAM DETAIL</p>
          <h1>{team.data.displayName}</h1>
          <p>
            {team.data.name} · 版本 {team.data.version}
          </p>
        </div>
        <StatusBadge phase={team.data.status} />
      </div>
      <div className="tabs" role="tablist">
        {tabs.map((item) => (
          <button
            className={item === tab ? 'tab tab--active' : 'tab'}
            role="tab"
            aria-selected={item === tab}
            onClick={() => setTab(item)}
            key={item}
          >
            {item}
          </button>
        ))}
      </div>
      {tab === '概览' && (
        <section className="detail-grid">
          <div className="panel">
            <p className="eyebrow">SUMMARY</p>
            <h2>资源摘要</h2>
            <div className="detail-list">
              <span>
                Leader<strong>{team.data.leaderAgentId || '未设置'}</strong>
              </span>
              <span>
                Agent 数量<strong>{team.data.agentCount ?? '—'}</strong>
              </span>
              <span>
                成员数量<strong>{team.data.memberCount ?? '—'}</strong>
              </span>
              <span>
                并发上限<strong>{team.data.maxConcurrentTasks ?? '—'}</strong>
              </span>
            </div>
          </div>
        </section>
      )}
      {tab === '成员 Agent' && (
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">TEAM MEMBERS</p>
              <h2>成员 Agent</h2>
            </div>
          </div>
          <form
            className="form-panel form-panel--inline"
            onSubmit={(event: FormEvent) => {
              event.preventDefault();
              addMember.mutate(memberForm, {
                onSuccess: () => setMemberForm({ agentId: '', role: 'MEMBER' }),
              });
            }}
          >
            <label>
              Worker / Agent
              <select
                aria-label="Worker / Agent"
                value={memberForm.agentId}
                onChange={(event) => setMemberForm({ ...memberForm, agentId: event.target.value })}
                required
              >
                <option value="">选择 Worker</option>
                {availableWorkers
                  .filter(
                    (worker) => memberForm.role !== 'LEADER' || worker.workerType === 'LEADER',
                  )
                  .map((worker) => (
                    <option value={worker.id} key={worker.id}>
                      {worker.name} · {worker.workerType || 'EXECUTOR'}
                    </option>
                  ))}
              </select>
            </label>
            <label>
              角色
              <select
                aria-label="成员角色"
                value={memberForm.role}
                onChange={(event) => {
                  const role = event.target.value;
                  setMemberForm({
                    agentId:
                      role === 'LEADER' &&
                      workerById.get(memberForm.agentId)?.workerType !== 'LEADER'
                        ? ''
                        : memberForm.agentId,
                    role,
                  });
                }}
              >
                <option value="MEMBER">成员</option>
                <option value="LEADER">Leader</option>
              </select>
            </label>
            <button className="button button--primary" type="submit" disabled={addMember.isPending}>
              {addMember.isPending ? '添加中…' : '添加成员'}
            </button>
          </form>
          {addMember.isError && (
            <ErrorState error={addMember.error} onRetry={() => addMember.mutate(memberForm)} />
          )}
          {removeMember.isError && (
            <ErrorState
              error={removeMember.error}
              onRetry={() => lastRemovedAgentId && removeMember.mutate(lastRemovedAgentId)}
            />
          )}
          {members.isLoading ? (
            <div className="loading-block">加载成员…</div>
          ) : members.isError ? (
            <ErrorState error={members.error} onRetry={() => void members.refetch()} />
          ) : members.data?.length ? (
            members.data.map((member) => (
              <div className="member-row" key={member.id}>
                <div>
                  <strong>{member.agentId}</strong>
                  <small>
                    {member.runtime || 'runtime 未知'} · {(member.capabilities || []).join('、')}
                    {workerById.get(member.agentId)?.workerType
                      ? ` · ${workerById.get(member.agentId)?.workerType}`
                      : ''}
                  </small>
                </div>
                <StatusBadge phase={member.status} />
                {member.status === 'ACTIVE' && (
                  <button
                    className="button button--small button--ghost"
                    type="button"
                    disabled={removeMember.isPending}
                    onClick={() => {
                      setLastRemovedAgentId(member.agentId);
                      removeMember.mutate(member.agentId);
                    }}
                  >
                    移除
                  </button>
                )}
              </div>
            ))
          ) : (
            <EmptyState title="暂无成员" description="为 Team 添加 Agent 后会显示在这里。" />
          )}
        </section>
      )}
      {tab === '策略' && (
        <section className="panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">SCHEDULING POLICY</p>
              <h2>调度策略</h2>
            </div>
            {policy.data && <span className="version-pill">版本 {policy.data.version}</span>}
          </div>
          {policy.isLoading ? (
            <div className="loading-block">加载策略…</div>
          ) : policy.isError ? (
            <ErrorState error={policy.error} onRetry={() => void policy.refetch()} />
          ) : policy.data ? (
            <form
              className="form-panel"
              onSubmit={(event: FormEvent) => {
                event.preventDefault();
                savePolicy();
              }}
            >
              <label>
                最大并发任务数
                <input
                  type="number"
                  min="1"
                  required
                  aria-label="最大并发任务数"
                  value={policyForm.maxConcurrentTasks}
                  onChange={(event) =>
                    setPolicyForm({ ...policyForm, maxConcurrentTasks: event.target.value })
                  }
                />
              </label>
              <label className="checkbox">
                <input
                  type="checkbox"
                  checked={policyForm.requireHumanApproval}
                  onChange={(event) =>
                    setPolicyForm({ ...policyForm, requireHumanApproval: event.target.checked })
                  }
                />
                需要人工审批
              </label>
              <label>
                允许 Runtime（逗号分隔）
                <input
                  aria-label="允许 Runtime"
                  value={policyForm.allowedRuntimes}
                  onChange={(event) =>
                    setPolicyForm({ ...policyForm, allowedRuntimes: event.target.value })
                  }
                />
              </label>
              <label>
                能力要求（逗号分隔）
                <input
                  aria-label="能力要求"
                  value={policyForm.requiredCapabilities}
                  onChange={(event) =>
                    setPolicyForm({ ...policyForm, requiredCapabilities: event.target.value })
                  }
                />
              </label>
              {updatePolicy.isError && (
                <ErrorState error={updatePolicy.error} onRetry={savePolicy} />
              )}
              <button
                className="button button--primary"
                type="submit"
                disabled={updatePolicy.isPending}
              >
                {updatePolicy.isPending ? '保存中…' : '保存策略'}
              </button>
            </form>
          ) : (
            <EmptyState title="暂无策略" description="当前 Team 尚未配置调度策略。" />
          )}
        </section>
      )}
      {tab === '版本与部署' && (
        <>
          {lifecycleNotice && (
            <div className="success-text" role="status">
              {lifecycleNotice}
            </div>
          )}
          {lifecycleError && (
            <ErrorState error={lifecycleError} onRetry={() => void revisions.refetch()} />
          )}
          <section className="panel form-panel">
            <div className="section-heading">
              <div>
                <p className="eyebrow">REVISION CONTROL</p>
                <h2>创建 Revision 草稿</h2>
              </div>
              <span className="muted-text">发布不会自动部署 Worker</span>
            </div>
            <form onSubmit={submitRevision}>
              <div className="form-grid">
                <label>
                  Leader Worker
                  <select
                    aria-label="Leader Worker"
                    value={revisionForm.leaderAgentId}
                    onChange={(event) =>
                      setRevisionForm({ ...revisionForm, leaderAgentId: event.target.value })
                    }
                    required
                  >
                    <option value="">选择 Leader Worker</option>
                    {leaderTeamWorkers.map((worker) => (
                      <option value={worker.id} key={worker.id}>
                        {worker.name} · {worker.id}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  成员 Worker（可多选）
                  <select
                    aria-label="成员 Worker"
                    multiple
                    size={Math.min(Math.max(activeTeamWorkers.length, 2), 6)}
                    value={revisionForm.memberAgentIds}
                    onChange={(event) =>
                      setRevisionForm({
                        ...revisionForm,
                        memberAgentIds: Array.from(
                          event.target.selectedOptions,
                          (option) => option.value,
                        ),
                      })
                    }
                    required
                  >
                    {activeTeamWorkers.map((worker) => (
                      <option value={worker.id} key={worker.id}>
                        {worker.name} · {worker.workerType || 'EXECUTOR'}
                      </option>
                    ))}
                  </select>
                </label>
                <label>
                  变更说明人
                  <input
                    value={revisionForm.actor}
                    onChange={(event) =>
                      setRevisionForm({ ...revisionForm, actor: event.target.value })
                    }
                  />
                </label>
              </div>
              <label>
                Overlay JSON
                <textarea
                  rows={4}
                  value={revisionForm.overlayJson}
                  onChange={(event) =>
                    setRevisionForm({ ...revisionForm, overlayJson: event.target.value })
                  }
                  required
                />
              </label>
              <button
                className="button button--primary"
                type="submit"
                disabled={createRevision.isPending}
              >
                {createRevision.isPending ? '创建中…' : '创建 Revision 草稿'}
              </button>
            </form>
          </section>
          <div className="content-grid">
            <section className="panel">
              <h2>版本</h2>
              {revisions.isLoading ? (
                <div className="loading-block">加载版本…</div>
              ) : revisions.isError ? (
                <ErrorState error={revisions.error} onRetry={() => void revisions.refetch()} />
              ) : revisions.data?.length ? (
                revisions.data.map((revision) => (
                  <div className="member-row" key={revision.revision}>
                    <div>
                      <strong>Revision {revision.revision}</strong>
                      <small>{revision.digest}</small>
                    </div>
                    <StatusBadge phase={revision.status} />
                    <div className="form-actions">
                      {revision.status === 'DRAFT' && (
                        <button
                          className="button button--small button--ghost"
                          type="button"
                          disabled={reviewRevision.isPending}
                          onClick={() =>
                            reviewRevision.mutate(
                              { revision: revision.revision, expectedVersion: revision.version },
                              { onSuccess: () => setLifecycleNotice('Revision 已提交审核') },
                            )
                          }
                        >
                          提交审核
                        </button>
                      )}
                      {(revision.status === 'DRAFT' || revision.status === 'REVIEWING') && (
                        <button
                          className="button button--small button--primary"
                          type="button"
                          disabled={publishRevision.isPending}
                          onClick={() =>
                            publishRevision.mutate(
                              { revision: revision.revision, expectedVersion: revision.version },
                              { onSuccess: () => setLifecycleNotice('Revision 已发布') },
                            )
                          }
                        >
                          发布
                        </button>
                      )}
                      {revision.status === 'PUBLISHED' && (
                        <>
                          <button
                            className="button button--small button--ghost"
                            type="button"
                            onClick={() =>
                              setDeploymentForm({
                                revision: String(revision.revision),
                                members: JSON.stringify(
                                  revision.memberAgentIds.map((agentId) => ({
                                    agentId,
                                    baseManifest: '{}',
                                  })),
                                ),
                                actor: '',
                              })
                            }
                          >
                            选择部署
                          </button>
                          <button
                            className="button button--small button--ghost"
                            type="button"
                            disabled={rollbackTeam.isPending}
                            onClick={() =>
                              rollbackTeam.mutate(
                                {
                                  targetRevision: revision.revision,
                                  expectedVersion: revision.version,
                                },
                                { onSuccess: () => setLifecycleNotice('回滚草稿已创建') },
                              )
                            }
                          >
                            创建回滚草稿
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                ))
              ) : (
                <EmptyState title="暂无版本" description="Team 发布版本会显示在这里。" />
              )}
            </section>
            <section className="panel">
              <h2>部署</h2>
              <form className="form-panel" onSubmit={submitDeployment}>
                <label>
                  Revision
                  <input
                    type="number"
                    min="1"
                    aria-label="部署 Revision"
                    value={deploymentForm.revision}
                    onChange={(event) =>
                      setDeploymentForm({ ...deploymentForm, revision: event.target.value })
                    }
                    required
                  />
                </label>
                <label>
                  成员部署 JSON
                  <textarea
                    rows={4}
                    aria-label="成员部署 JSON"
                    value={deploymentForm.members}
                    onChange={(event) =>
                      setDeploymentForm({ ...deploymentForm, members: event.target.value })
                    }
                    required
                  />
                </label>
                <p className="muted-text">
                  每个成员必须包含 agentId 和 baseManifest，且集合必须与已发布 Revision 一致。
                </p>
                <button
                  className="button button--primary"
                  type="submit"
                  disabled={deployRevision.isPending}
                >
                  {deployRevision.isPending ? '提交中…' : '提交 Deployment'}
                </button>
              </form>
              {deployments.isLoading ? (
                <div className="loading-block">加载部署…</div>
              ) : deployments.isError ? (
                <ErrorState error={deployments.error} onRetry={() => void deployments.refetch()} />
              ) : deployments.data?.length ? (
                deployments.data.map((deployment) => (
                  <div className="member-row" key={deployment.id}>
                    <div>
                      <strong>Deployment #{deployment.id}</strong>
                      <small>
                        Revision {deployment.teamRevision} ·{' '}
                        {new Date(deployment.createdAt).toLocaleString('zh-CN')}
                      </small>
                    </div>
                    <StatusBadge phase={deployment.status} />
                    {deployment.status === 'FAILED' && (
                      <button
                        className="button button--small button--ghost"
                        type="button"
                        disabled={retryDeployment.isPending}
                        onClick={() =>
                          retryDeployment.mutate(deployment.id, {
                            onSuccess: () => setLifecycleNotice('Deployment 重试已提交'),
                          })
                        }
                      >
                        重试
                      </button>
                    )}
                  </div>
                ))
              ) : (
                <EmptyState
                  title="暂无部署"
                  description="发布 Team 版本后，deployment 状态会显示在这里。"
                />
              )}
            </section>
          </div>
        </>
      )}
      {tab === '运行记录' && (
        <section className="panel">
          <h2>运行记录</h2>
          <Timeline items={[]} />
        </section>
      )}
    </div>
  );
}

function splitValues(value: string) {
  return value
    .split(',')
    .map((item) => item.trim())
    .filter(Boolean);
}
