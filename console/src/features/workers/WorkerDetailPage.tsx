import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { Timeline } from '../../components/Timeline';
import { useWorker, useWorkerOperations } from '../../queries/useWorkerQueries';
import { WorkerOperationPanel } from './WorkerOperationPanel';
import { CursorPagination } from '../../components/CursorPagination';
import type { WorkerOperation } from '../../api/types';

function operationDescription(operation: WorkerOperation) {
  const details = [`状态：${operation.status}`];
  if (operation.failureCategory) details.push(`失败原因：${operation.failureCategory}`);
  if (operation.operatorReady !== undefined) {
    details.push(`Operator：${operation.operatorReady ? '已就绪' : '未就绪'}`);
  }
  if (operation.gatewayOnline !== undefined) {
    details.push(`Gateway：${operation.gatewayOnline ? '在线' : '离线'}`);
  }
  if (operation.observationsMatch !== undefined) {
    details.push(`观测结果：${operation.observationsMatch ? '匹配' : '不匹配'}`);
  }
  return details.join(' · ');
}

export function WorkerDetailPage({ projectId, workerId }: { projectId: string; workerId: string }) {
  const worker = useWorker(projectId, workerId);
  const [operationCursor, setOperationCursor] = useState<string>();
  const [operationCursorHistory, setOperationCursorHistory] = useState<string[]>([]);
  const operations = useWorkerOperations(projectId, workerId, operationCursor);
  const operationItems = operations.data?.items || [];
  useEffect(() => {
    setOperationCursor(undefined);
    setOperationCursorHistory([]);
  }, [workerId]);
  if (worker.isLoading)
    return (
      <div className="page">
        <div className="panel loading-block">加载 Worker…</div>
      </div>
    );
  if (worker.isError || !worker.data)
    return (
      <div className="page">
        <ErrorState error={worker.error} onRetry={() => void worker.refetch()} />
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
            {data.id} · {data.runtime} · {data.workerType || 'EXECUTOR'}
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
        <WorkerOperationPanel
          projectId={projectId}
          worker={data}
          operations={operationItems}
          onRefresh={async () => {
            const [latestWorker, latestOperations] = await Promise.all([
              worker.refetch(),
              operations.refetch(),
            ]);
            return { worker: latestWorker.data, operations: latestOperations.data?.items };
          }}
        />
      </div>
      <section className="panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">AUDIT TRAIL</p>
            <h2>操作记录</h2>
          </div>
        </div>
        {operations.isLoading ? (
          <div className="loading-block">加载操作记录…</div>
        ) : operations.isError ? (
          <ErrorState error={operations.error} onRetry={() => void operations.refetch()} />
        ) : (
          <Timeline
            items={operationItems.map((operation) => ({
              id: operation.id,
              title: operation.type,
              description: operationDescription(operation),
              time: operation.updatedAt,
              tone: operation.status === 'FAILED' ? 'danger' : 'success',
            }))}
          />
        )}
        {!operations.isLoading && !operations.isError && operationItems.length > 0 && (
          <CursorPagination
            hasPrevious={operationCursorHistory.length > 0}
            hasNext={Boolean(operations.data?.nextCursor)}
            onPrevious={() => {
              const previous = operationCursorHistory[operationCursorHistory.length - 1];
              setOperationCursorHistory((history) => history.slice(0, -1));
              setOperationCursor(previous || undefined);
            }}
            onNext={() => {
              if (!operations.data?.nextCursor) return;
              setOperationCursorHistory((history) => [...history, operationCursor || '']);
              setOperationCursor(operations.data.nextCursor || undefined);
            }}
          />
        )}
      </section>
    </div>
  );
}
