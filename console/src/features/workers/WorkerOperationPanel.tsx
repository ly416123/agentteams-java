import { useState } from 'react';
import type { Worker, WorkerOperation } from '../../api/types';
import { ApiError } from '../../api/httpClient';
import {
  useWorkerAction,
  useWorkerRollback,
  useWorkerRollout,
} from '../../queries/useWorkerQueries';
import type { WorkerRolloutRequest } from '../../api/workers';
import { VersionConflictModal } from '../../components/VersionConflictModal';

export function WorkerOperationPanel({
  projectId,
  worker,
  operations = [],
}: {
  projectId: string;
  worker: Worker;
  operations?: WorkerOperation[];
}) {
  const action = useWorkerAction(projectId, worker.id);
  const rollout = useWorkerRollout(projectId, worker.id);
  const rollback = useWorkerRollback(projectId, worker.id);
  const [conflict, setConflict] = useState(false);
  const [message, setMessage] = useState('');
  const [retry, setRetry] = useState<(() => void) | null>(null);
  const failedRollout = operations.find(
    (operation) => operation.type === 'ROLLOUT' && operation.status === 'FAILED',
  );
  const pending = action.isPending || rollout.isPending || rollback.isPending;
  const handleError = (error: unknown, retryAction: () => void) => {
    if (error instanceof ApiError && error.status === 409) {
      setRetry(() => retryAction);
      setConflict(true);
    }
  };
  const run = (name: 'drain' | 'terminate') =>
    action.mutate(
      { action: name, expectedVersion: worker.version },
      {
        onSuccess: () => setMessage('操作已提交'),
        onError: (error) => handleError(error, () => run(name)),
      },
    );
  const submitRollout = () => {
    const body: WorkerRolloutRequest = {
      expectedVersion: worker.version,
      imageDigest: worker.imageDigest || worker.imageVersion || `worker-${worker.id}`,
      runtime: worker.runtime,
      configRevision: worker.configRevision || worker.configVersion || `worker-${worker.version}`,
      secretGeneration: worker.secretGeneration || `worker-${worker.version}`,
      previousStableSpec: worker.previousStableSpec || '{}',
      owner: 'console',
      correlationId: globalThis.crypto?.randomUUID?.() || `${Date.now()}-${worker.id}`,
    };
    rollout.mutate(body, {
      onSuccess: () => setMessage('操作已提交'),
      onError: (error) => handleError(error, submitRollout),
    });
  };
  const submitRollback = () => {
    if (!failedRollout) return;
    rollback.mutate(
      { operationId: failedRollout.id, expectedVersion: failedRollout.version },
      {
        onSuccess: () => setMessage('操作已提交'),
        onError: (error) => handleError(error, submitRollback),
      },
    );
  };
  const unavailable = worker.phase !== 'READY';
  return (
    <>
      <section className="panel operation-panel">
        <div className="section-heading">
          <div>
            <p className="eyebrow">LIFECYCLE CONTROL</p>
            <h2>操作面板</h2>
          </div>
          <span className="version-pill">版本 {worker.version}</span>
        </div>
        {unavailable && (
          <div className="info-box">
            {worker.unavailableReason ||
              `Worker 当前为「${worker.phase}」，依赖 Worker 的操作已禁用。`}
          </div>
        )}
        <div className="operation-actions">
          <button
            className="button button--ghost"
            disabled={unavailable || pending}
            onClick={() => run('drain')}
          >
            Drain
          </button>
          <button
            className="button button--danger"
            disabled={unavailable || pending}
            onClick={() => run('terminate')}
          >
            Terminate
          </button>
          <button
            className="button button--ghost"
            disabled={unavailable || pending}
            onClick={submitRollout}
          >
            Rollout
          </button>
          <button
            className="button button--ghost"
            disabled={unavailable || pending || !failedRollout}
            onClick={submitRollback}
          >
            Rollback
          </button>
        </div>
        {message && <p className="success-text">{message}</p>}
      </section>
      <VersionConflictModal
        open={conflict}
        actionLabel="继续操作"
        onCancel={() => setConflict(false)}
        onConfirm={() => {
          setConflict(false);
          const retryAction = retry;
          setRetry(null);
          retryAction?.();
        }}
      />
    </>
  );
}
