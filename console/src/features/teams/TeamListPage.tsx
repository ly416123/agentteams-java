import { useState } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ResourceTable } from '../../components/ResourceTable';
import { StatusBadge } from '../../components/StatusBadge';
import { useTeams } from '../../queries/useTeamQueries';

export function TeamListPage({ projectId }: { projectId: string }) {
  const [search, setSearch] = useState('');
  const [status, setStatus] = useState('');
  const teams = useTeams(projectId, { search, status });
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">RESOURCE / TEAMS</p>
          <h1>Teams</h1>
          <p>组织 Agent 协同工作，并管理调度策略与发布版本。</p>
        </div>
        <Link className="button button--primary" to={`/${projectId}/teams/new`}>
          创建 Team
        </Link>
      </div>
      <div className="toolbar">
        <input
          placeholder="搜索 Team"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <select
          aria-label="Team 状态"
          value={status}
          onChange={(event) => setStatus(event.target.value)}
        >
          <option value="">全部状态</option>
          <option value="ACTIVE">活跃</option>
          <option value="DRAFT">草稿</option>
        </select>
        <button className="button button--ghost" onClick={() => void teams.refetch()}>
          刷新
        </button>
      </div>
      {teams.isLoading ? (
        <div className="panel loading-block">加载 Team…</div>
      ) : teams.isError ? (
        <ErrorState error={teams.error} onRetry={() => void teams.refetch()} />
      ) : teams.data?.length ? (
        <ResourceTable
          items={teams.data}
          columns={[
            {
              key: 'name',
              header: '名称',
              render: (team) => (
                <Link className="resource-link" to={`/${projectId}/teams/${team.id}`}>
                  <strong>{team.displayName}</strong>
                  <small>{team.name}</small>
                </Link>
              ),
            },
            { key: 'leader', header: 'Leader', render: (team) => team.leaderAgentId || '未设置' },
            {
              key: 'agents',
              header: 'Agent / 成员',
              render: (team) => `${team.agentCount ?? '—'} / ${team.memberCount ?? '—'}`,
            },
            {
              key: 'concurrency',
              header: '并发上限',
              render: (team) => team.maxConcurrentTasks ?? '—',
            },
            {
              key: 'status',
              header: '状态',
              render: (team) => <StatusBadge phase={team.status} />,
            },
            {
              key: 'updated',
              header: '更新时间',
              render: (team) => new Date(team.updatedAt).toLocaleDateString('zh-CN'),
            },
          ]}
        />
      ) : (
        <EmptyState
          title="还没有 Team"
          description="创建第一个 Team，开始编排 Agent。"
          action={
            <Link className="button button--primary" to={`/${projectId}/teams/new`}>
              创建 Team
            </Link>
          }
        />
      )}
    </div>
  );
}
