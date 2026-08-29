import { useState } from 'react';
import { Link } from 'react-router-dom';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ResourceTable } from '../../components/ResourceTable';
import { StatusBadge } from '../../components/StatusBadge';
import { useWorkers } from '../../queries/useWorkerQueries';

export function WorkerListPage({ projectId }: { projectId: string }) {
  const [search, setSearch] = useState('');
  const [phase, setPhase] = useState('');
  const workers = useWorkers(projectId, { search, phase });
  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">RESOURCE / WORKERS</p>
          <h1>Workers</h1>
          <p>查看 Agent 运行时、健康状态和当前执行上下文。</p>
        </div>
      </div>
      <div className="toolbar">
        <input
          placeholder="搜索 Worker"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
        <select
          aria-label="Worker 状态"
          value={phase}
          onChange={(event) => setPhase(event.target.value)}
        >
          <option value="">全部状态</option>
          <option value="READY">就绪</option>
          <option value="UNHEALTHY">异常</option>
          <option value="DRAINING">排空中</option>
        </select>
        <button className="button button--ghost" onClick={() => void workers.refetch()}>
          刷新
        </button>
      </div>
      {workers.isLoading ? (
        <div className="panel loading-block">加载 Worker…</div>
      ) : workers.isError ? (
        <ErrorState error={workers.error} onRetry={() => void workers.refetch()} />
      ) : workers.data?.length ? (
        <ResourceTable
          items={workers.data}
          columns={[
            {
              key: 'name',
              header: '名称',
              render: (worker) => (
                <Link className="resource-link" to={`/${projectId}/workers/${worker.id}`}>
                  <strong>{worker.name}</strong>
                  <small>{worker.id}</small>
                </Link>
              ),
            },
            { key: 'runtime', header: 'Runtime', render: (worker) => worker.runtime },
            {
              key: 'phase',
              header: '连接状态',
              render: (worker) => <StatusBadge phase={worker.phase} />,
            },
            {
              key: 'capabilities',
              header: '能力',
              render: (worker) =>
                (worker.capabilities || []).map((item) => (
                  <span className="tag" key={item}>
                    {item}
                  </span>
                )),
            },
            { key: 'task', header: '当前任务', render: (worker) => worker.currentTaskId || '空闲' },
            {
              key: 'heartbeat',
              header: '最近心跳',
              render: (worker) =>
                worker.lastHeartbeat ? new Date(worker.lastHeartbeat).toLocaleString('zh-CN') : '—',
            },
          ]}
        />
      ) : (
        <EmptyState title="暂无 Worker" description="Worker 注册后会出现在这里。" />
      )}
    </div>
  );
}
