import { useMutation, useQuery } from '@tanstack/react-query';
import {
  listArtifactRetentionPolicy,
  listArtifacts,
  updateArtifactRetentionPolicy,
  type ArtifactRetentionPolicy,
} from '../../api/artifacts';
import { useEffect, useState } from 'react';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

export function ManagementArtifactPage({ projectId }: { projectId: string }) {
  const artifacts = useQuery({
    queryKey: ['artifacts', projectId],
    queryFn: () => listArtifacts(projectId),
    enabled: Boolean(projectId),
  });
  const retention = useQuery({
    queryKey: ['artifact-retention-policy', projectId],
    queryFn: () => listArtifactRetentionPolicy(projectId),
    enabled: Boolean(projectId),
  });

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">OPERATIONS / ARTIFACTS</p>
          <h1>Artifacts</h1>
          <p className="page-subtitle">
            查看当前 Project 作用域内的产物元数据、Attempt 归属、状态和 SHA-256 校验和。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void artifacts.refetch()}>
          刷新
        </button>
      </div>
      <RetentionPolicyCard
        policy={retention.data}
        isLoading={retention.isLoading}
        isError={retention.isError}
        error={retention.error}
        onRetry={() => void retention.refetch()}
        onSaved={() => void retention.refetch()}
      />
      {artifacts.isLoading ? (
        <div className="panel loading-block">加载产物…</div>
      ) : artifacts.isError ? (
        <ErrorState error={artifacts.error} onRetry={() => void artifacts.refetch()} />
      ) : !artifacts.data?.length ? (
        <EmptyState title="暂无产物" description="当前作用域还没有可展示的产物。" />
      ) : (
        <div className="content-grid">
          {artifacts.data.map((artifact) => (
            <article className="panel" key={artifact.id}>
              <div className="section-heading">
                <div>
                  <p className="eyebrow">{artifact.contentType}</p>
                  <h2>{artifact.name}</h2>
                </div>
                <StatusBadge phase={artifact.status} />
              </div>
              <div className="detail-list">
                <span>
                  Artifact<strong>{artifact.id}</strong>
                </span>
                <span>
                  Task<strong>{artifact.taskId}</strong>
                </span>
                <span>
                  Attempt<strong>{artifact.attemptId}</strong>
                </span>
                <span>
                  大小<strong>{artifact.sizeBytes.toLocaleString()} bytes</strong>
                </span>
                <span>
                  SHA-256<strong>{artifact.sha256}</strong>
                </span>
              </div>
              <p className="muted-text">下载仍需通过 Task/Attempt 资源授权，不在此页绕过权限。</p>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}

function RetentionPolicyCard({
  policy,
  isLoading,
  isError,
  error,
  onRetry,
  onSaved,
}: {
  policy?: ArtifactRetentionPolicy;
  isLoading: boolean;
  isError: boolean;
  error: unknown;
  onRetry: () => void;
  onSaved: () => void;
}) {
  const [successful, setSuccessful] = useState('');
  const [failed, setFailed] = useState('');
  const [temporary, setTemporary] = useState('');
  const [legalHold, setLegalHold] = useState(false);

  useEffect(() => {
    if (!policy) return;
    setSuccessful(String(policy.successfulTaskRetentionSeconds));
    setFailed(String(policy.failedTaskRetentionSeconds));
    setTemporary(String(policy.temporaryUploadRetentionSeconds));
    setLegalHold(policy.legalHold);
  }, [policy]);

  const mutation = useMutation({
    mutationFn: () =>
      updateArtifactRetentionPolicy(policy?.projectId || '', {
        successfulTaskRetentionSeconds: Number(successful),
        failedTaskRetentionSeconds: Number(failed),
        temporaryUploadRetentionSeconds: Number(temporary),
        legalHold,
        expectedVersion: policy?.version || 0,
      }),
    onSuccess: onSaved,
  });

  return (
    <section className="panel">
      <div className="section-heading">
        <div>
          <p className="eyebrow">GOVERNANCE / RETENTION</p>
          <h2>Artifact 保留策略</h2>
        </div>
        <span className="muted-text">version {policy?.version ?? '—'}</span>
      </div>
      {isLoading ? (
        <p className="muted-text">加载保留策略…</p>
      ) : isError ? (
        <ErrorState error={error} onRetry={onRetry} />
      ) : (
        <>
          <div className="form-grid">
            <label>
              成功任务保留（秒）
              <input
                type="number"
                min="0"
                value={successful}
                onChange={(event) => setSuccessful(event.target.value)}
              />
            </label>
            <label>
              失败任务保留（秒）
              <input
                type="number"
                min="0"
                value={failed}
                onChange={(event) => setFailed(event.target.value)}
              />
            </label>
            <label>
              临时上传保留（秒）
              <input
                type="number"
                min="0"
                value={temporary}
                onChange={(event) => setTemporary(event.target.value)}
              />
            </label>
            <label>
              <input
                type="checkbox"
                checked={legalHold}
                onChange={(event) => setLegalHold(event.target.checked)}
              />
              法律保留（Legal hold）
            </label>
          </div>
          <p className="muted-text">
            未配置项目覆盖时使用平台默认值；策略只影响后端保留清理，不暴露产物内容。
          </p>
          <button
            className="button button--primary"
            onClick={() => mutation.mutate()}
            disabled={mutation.isPending || !policy}
          >
            {mutation.isPending ? '保存中…' : '保存保留策略'}
          </button>
          {mutation.isError ? <p role="alert">保留策略保存失败，请刷新后重试。</p> : null}
        </>
      )}
    </section>
  );
}
