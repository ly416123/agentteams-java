import { useState } from 'react';
import type { Worker } from '../../api/types';
import { ApiError } from '../../api/httpClient';
import { useWorkerAction } from '../../queries/useWorkerQueries';
import { VersionConflictModal } from '../../components/VersionConflictModal';

export function WorkerOperationPanel({ projectId, worker }: { projectId: string; worker: Worker }) {
  const action = useWorkerAction(projectId, worker.id);
  const [conflict, setConflict] = useState(false);
  const [message, setMessage] = useState('');
  const run = (name: 'drain' | 'terminate') =>
    action.mutate(
      { action: name, expectedVersion: worker.version },
      {
        onSuccess: () => setMessage('操作已提交'),
        onError: (error) => {
          if (error instanceof ApiError && error.status === 409) setConflict(true);
        },
      },
    );
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
            disabled={unavailable || action.isPending}
            onClick={() => run('drain')}
          >
            Drain
          </button>
          <button
            className="button button--danger"
            disabled={unavailable || action.isPending}
            onClick={() => run('terminate')}
          >
            Terminate
          </button>
          <button className="button button--ghost" disabled={unavailable}>
            Rollout
          </button>
          <button className="button button--ghost" disabled={unavailable}>
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
          run('drain');
        }}
      />
    </>
  );
}
