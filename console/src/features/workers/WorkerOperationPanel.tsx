import { useState } from 'react';
import type { Worker, WorkerOperation } from '../../api/types';
import { ApiError } from '../../api/httpClient';
import {
  useWorkerAction,
  useWorkerRollback,
  useWorkerRollout,
} from '../../queries/useWorkerQueries';
import type { WorkerRolloutRequest } from '../../api/workers';
import { ErrorState } from '../../components/ErrorState';
import { VersionConflictModal } from '../../components/VersionConflictModal';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';

type ConfirmedAction = 'drain' | 'terminate' | 'rollout' | 'rollback';
const actionLabels: Record<ConfirmedAction, string> = {
  drain: 'Drain',
  terminate: 'Terminate',
  rollout: 'Rollout',
  rollback: 'Rollback',
};
const actionImpacts: Record<ConfirmedAction, string> = {
  drain: 'Worker 将停止接收新任务，并等待当前任务完成或被接管。',
  terminate: 'Worker 将被终止，当前任务会中断且不会再接收新任务。',
  rollout: 'Worker 将切换到新镜像与配置，期间可能短暂不可用。',
  rollback: 'Worker 将回滚到失败 Rollout 的稳定规格，当前版本可能被替换。',
};

export function WorkerOperationPanel({
  projectId,
  worker,
  operations = [],
  onRefresh,
}: {
  projectId: string;
  worker: Worker;
  operations?: WorkerOperation[];
  onRefresh: () => Promise<{ worker?: Worker; operations?: WorkerOperation[] }>;
}) {
  const action = useWorkerAction(projectId, worker.id);
  const rollout = useWorkerRollout(projectId, worker.id);
  const rollback = useWorkerRollback(projectId, worker.id);
  const [conflict, setConflict] = useState(false);
  const [message, setMessage] = useState('');
  const [retry, setRetry] = useState<
    ((latest: { worker?: Worker; operations?: WorkerOperation[] }) => void) | null
  >(null);
  const [formError, setFormError] = useState('');
  const [refreshing, setRefreshing] = useState(false);
  const [confirmation, setConfirmation] = useState<ConfirmedAction | null>(null);
  const [rolloutForm, setRolloutForm] = useState({
    imageDigest: worker.imageDigest || '',
    configRevision: worker.configRevision || '',
    secretGeneration: worker.secretGeneration || '',
    previousStableSpec: worker.previousStableSpec || '',
  });
  const failedRollout = operations.find(
    (operation) => operation.type === 'ROLLOUT' && operation.status === 'FAILED',
  );
  const pending = action.isPending || rollout.isPending || rollback.isPending || refreshing;
  const handleError = (
    error: unknown,
    retryAction: (latest: { worker?: Worker; operations?: WorkerOperation[] }) => void,
  ) => {
    setRetry(() => retryAction);
    if (error instanceof ApiError && error.status === 409) {
      setConflict(true);
    }
  };
  const run = (name: 'drain' | 'terminate', currentWorker = worker) =>
    action.mutate(
      { action: name, expectedVersion: currentWorker.version },
      {
        onSuccess: () => setMessage('操作已提交'),
        onError: (error) =>
          handleError(error, (latest) => {
            if (latest.worker) run(name, latest.worker);
            else setFormError('无法读取 Worker 最新版本，请刷新后重试。');
          }),
      },
    );
  const submitRollout = (currentWorker = worker) => {
    if (!rolloutReady) {
      setFormError('镜像 Digest、配置 Revision、Secret Generation、稳定规格快照均为必填项。');
      return;
    }
    setFormError('');
    const body: WorkerRolloutRequest = {
      expectedVersion: currentWorker.version,
      imageDigest: rolloutForm.imageDigest,
      runtime: currentWorker.runtime,
      configRevision: rolloutForm.configRevision,
      secretGeneration: rolloutForm.secretGeneration,
      previousStableSpec: rolloutForm.previousStableSpec,
    };
    rollout.mutate(body, {
      onSuccess: () => setMessage('操作已提交'),
      onError: (error) =>
        handleError(error, (latest) => {
          if (latest.worker) submitRollout(latest.worker);
          else setFormError('无法读取 Worker 最新版本，请刷新后重试。');
        }),
    });
  };
  const rolloutReady = Object.values(rolloutForm).every((value) => value.trim().length > 0);
  const confirmAction = () => {
    if (!confirmation) return;
    const selected = confirmation;
    setConfirmation(null);
    if (selected === 'drain' || selected === 'terminate') run(selected);
    if (selected === 'rollout') submitRollout();
    if (selected === 'rollback') submitRollback();
  };
  const submitRollback = (currentOperation = failedRollout) => {
    if (!currentOperation) return;
    setFormError('');
    rollback.mutate(
      { operationId: currentOperation.id, expectedVersion: currentOperation.version },
      {
        onSuccess: () => setMessage('操作已提交'),
        onError: (error) =>
          handleError(error, (latest) => {
            const latestOperation = latest.operations?.find(
              (operation) => operation.type === 'ROLLOUT' && operation.status === 'FAILED',
            );
            if (latestOperation) submitRollback(latestOperation);
            else setFormError('无法读取失败 Rollout 的最新版本，请刷新后重试。');
          }),
      },
    );
  };
  const confirmConflict = async () => {
    setRefreshing(true);
    const latest = await onRefresh();
    setRefreshing(false);
    setConflict(false);
    const retryAction = retry;
    setRetry(null);
    if (retryAction && (latest.worker || latest.operations)) retryAction(latest);
    else setFormError('无法刷新 Worker 最新状态，请稍后重试。');
  };
  const unavailable = worker.phase !== 'READY';
  const mutationError = rollout.error || rollback.error || action.error;
  const retryLatest = async () => {
    setRefreshing(true);
    try {
      const latest = await onRefresh();
      retry?.(latest);
    } finally {
      setRefreshing(false);
    }
  };
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
            onClick={() => setConfirmation('drain')}
          >
            Drain
          </button>
          <button
            className="button button--danger"
            disabled={unavailable || pending}
            onClick={() => setConfirmation('terminate')}
          >
            Terminate
          </button>
          <button
            className="button button--ghost"
            disabled={unavailable || pending || !rolloutReady}
            onClick={() => setConfirmation('rollout')}
          >
            Rollout
          </button>
          <button
            className="button button--ghost"
            disabled={unavailable || pending || !failedRollout}
            onClick={() => setConfirmation('rollback')}
          >
            Rollback
          </button>
        </div>
        {mutationError && !conflict && (
          <ErrorState error={mutationError} onRetry={() => void retryLatest()} />
        )}
        <div className="rollout-form" aria-label="Rollout 参数">
          <label>
            镜像 Digest
            <input
              required
              aria-label="镜像 Digest"
              value={rolloutForm.imageDigest}
              onChange={(event) =>
                setRolloutForm({ ...rolloutForm, imageDigest: event.target.value })
              }
            />
          </label>
          <label>
            配置 Revision
            <input
              required
              aria-label="配置 Revision"
              value={rolloutForm.configRevision}
              onChange={(event) =>
                setRolloutForm({ ...rolloutForm, configRevision: event.target.value })
              }
            />
          </label>
          <label>
            Secret Generation
            <input
              required
              aria-label="Secret Generation"
              value={rolloutForm.secretGeneration}
              onChange={(event) =>
                setRolloutForm({ ...rolloutForm, secretGeneration: event.target.value })
              }
            />
          </label>
          <label>
            稳定规格快照
            <textarea
              required
              aria-label="稳定规格快照"
              value={rolloutForm.previousStableSpec}
              onChange={(event) =>
                setRolloutForm({ ...rolloutForm, previousStableSpec: event.target.value })
              }
            />
          </label>
        </div>
        {!rolloutReady && (
          <p className="error-text">
            Rollout 提交已禁用：镜像 Digest、配置 Revision、Secret
            Generation、稳定规格快照均需提供真实值。
          </p>
        )}
        {formError && <p className="error-text">{formError}</p>}
        {message && <p className="success-text">{message}</p>}
      </section>
      <VersionConflictModal
        open={conflict}
        actionLabel="继续操作"
        onCancel={() => setConflict(false)}
        onConfirm={() => void confirmConflict()}
      />
      <ActionConfirmModal
        open={Boolean(confirmation)}
        actionLabel={confirmation ? actionLabels[confirmation] : '操作'}
        impact={confirmation ? actionImpacts[confirmation] : ''}
        onCancel={() => setConfirmation(null)}
        onConfirm={confirmAction}
      />
    </>
  );
}
