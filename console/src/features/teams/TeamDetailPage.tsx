import { useState } from 'react';
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
} from '../../queries/useTeamQueries';

const tabs = ['概览', '成员 Agent', '策略', '版本与部署', '运行记录'] as const;
export function TeamDetailPage({ projectId, teamId }: { projectId: string; teamId: string }) {
  const [tab, setTab] = useState<(typeof tabs)[number]>('概览');
  const team = useTeam(projectId, teamId);
  const members = useTeamMembers(projectId, teamId);
  const policy = useTeamPolicy(projectId, teamId);
  const revisions = useTeamRevisions(projectId, teamId);
  const deployments = useTeamDeployments(projectId, teamId);
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
          <h2>成员 Agent</h2>
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
                  </small>
                </div>
                <StatusBadge phase={member.status} />
              </div>
            ))
          ) : (
            <EmptyState title="暂无成员" description="为 Team 添加 Agent 后会显示在这里。" />
          )}
        </section>
      )}
      {tab === '策略' && (
        <section className="panel">
          <h2>调度策略</h2>
          {policy.isLoading ? (
            <div className="loading-block">加载策略…</div>
          ) : policy.isError ? (
            <ErrorState error={policy.error} onRetry={() => void policy.refetch()} />
          ) : policy.data ? (
            <div className="detail-list">
              <span>
                最大并发<strong>{policy.data.maxConcurrentTasks}</strong>
              </span>
              <span>
                人工审批<strong>{policy.data.requireHumanApproval ? '需要' : '不需要'}</strong>
              </span>
              <span>
                允许 Runtime<strong>{policy.data.allowedRuntimes.join('、') || '不限'}</strong>
              </span>
              <span>
                能力要求<strong>{policy.data.requiredCapabilities.join('、') || '无'}</strong>
              </span>
            </div>
          ) : (
            <EmptyState title="暂无策略" description="当前 Team 尚未配置调度策略。" />
          )}
        </section>
      )}
      {tab === '版本与部署' && (
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
                </div>
              ))
            ) : (
              <EmptyState title="暂无版本" description="Team 发布版本会显示在这里。" />
            )}
          </section>
          <section className="panel">
            <h2>部署</h2>
            {deployments.isLoading ? (
              <div className="loading-block">加载部署…</div>
            ) : deployments.isError ? (
              <ErrorState error={deployments.error} onRetry={() => void deployments.refetch()} />
            ) : deployments.data?.length ? (
              deployments.data.map((deployment) => (
                <div className="member-row" key={deployment.id}>
                  <strong>Revision {deployment.teamRevision}</strong>
                  <StatusBadge phase={deployment.status} />
                </div>
              ))
            ) : (
              <EmptyState title="暂无部署" description="Team 部署记录会显示在这里。" />
            )}
          </section>
        </div>
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
