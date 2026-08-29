import { Link } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import { useWorker, useWorkerOperations } from '../../queries/useWorkerQueries';
import { WorkerOperationPanel } from './WorkerOperationPanel';

export function WorkerDetailPage({ projectId, workerId }: { projectId: string; workerId: string }) {
  const worker = useWorker(projectId, workerId);
  const operations = useWorkerOperations(projectId, workerId);
  if (worker.isLoading)
    return (
      <div className="page">
        <div className="panel loading-block">加载 Worker…</div>
      </div>
    );
  if (worker.isError || !worker.data)
    return (
      <div className="page">
        <ErrorState error={worker.error} />;
      </div>
    );
  const data = worker.data;
  return (
    <div className="page">
      <Link className="back-link" to={`/${projectId}/workers`}>
        ← 返回 Workers
      </Link>
      <div className="detail-heading">
        <div>
          <p className="eyebrow">WORKER DETAIL</p>
          <h1>{data.name}</h1>
          <p>
            {data.id} · {data.runtime}
          </p>
        </div>
        <StatusBadge phase={data.phase} />
      </div>
      <div className="content-grid">
        <section className="panel">
          <p className="eyebrow">RUNTIME</p>
          <h2>运行状态</h2>
          <div className="detail-list">
            <span>
              当前任务<strong>{data.currentTaskId || '空闲'}</strong>
            </span>
            <span>
              镜像<strong>{data.imageVersion || '—'}</strong>
            </span>
            <span>
              配置<strong>{data.configVersion || '—'}</strong>
            </span>
            <span>
              最近心跳
              <strong>
                {data.lastHeartbeat ? new Date(data.lastHeartbeat).toLocaleString('zh-CN') : '—'}
              </strong>
            </span>
          </div>
          <div className="tag-list">
            {data.capabilities?.map((capability) => (
              <span className="tag" key={capability}>
                {capability}
              </span>
            ))}
          </div>
        </section>
        <WorkerOperationPanel projectId={projectId} worker={data} />
      </div>
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">AUDIT TRAIL</p>
            <h2>操作记录</h2>
          </div>
        </div>
        <Timeline
          items={(operations.data || []).map((operation) => ({
            id: operation.id,
            title: operation.type,
            description: operation.status,
            time: operation.updatedAt,
            tone: operation.status === 'FAILED' ? 'danger' : 'success',
          }))}
        />
      </section>
    </div>
  );
}
