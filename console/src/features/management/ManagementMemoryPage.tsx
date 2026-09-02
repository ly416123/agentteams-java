import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { governMemory, listMemoryMetadata, type MemoryMetadata } from '../../api/memory';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

type Operation = 'CONFIRM' | 'REVOKE' | 'FREEZE' | 'DELETE' | 'EXPORT';

export function ManagementMemoryPage({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const memories = useQuery({
    queryKey: ['management-memory', projectId],
    queryFn: () => listMemoryMetadata(projectId),
    enabled: Boolean(projectId),
  });
  const mutation = useMutation({
    mutationFn: ({
      memoryId,
      operation,
      reason,
    }: {
      memoryId: string;
      operation: Operation;
      reason: string;
    }) => governMemory(memoryId, operation, reason),
    onSuccess: () =>
      void queryClient.invalidateQueries({ queryKey: ['management-memory', projectId] }),
  });

  function operate(memory: MemoryMetadata, operation: Operation) {
    const reason =
      (
        document.getElementById('memory-governance-reason') as HTMLInputElement | null
      )?.value.trim() || '';
    if (!reason) return;
    if (operation === 'DELETE' && !window.confirm('确认删除该 Memory 的治理记录？')) return;
    mutation.mutate({ memoryId: memory.id, operation, reason });
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">GOVERNANCE / MEMORY</p>
          <h1>Memory 治理</h1>
          <p className="page-subtitle">
            只展示策略、治理状态和来源元数据；不展示记忆内容、原始历史或 Secret。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void memories.refetch()}>
          刷新
        </button>
      </div>
      <div className="form-panel">
        <label htmlFor="memory-governance-reason">治理原因</label>
        <input id="memory-governance-reason" placeholder="例如：compliance review" required />
        <p className="muted-text">
          治理操作会记录操作者、原因和幂等键；私人 Memory 仍按 subjectId 隔离。
        </p>
      </div>
      {memories.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : memories.isError ? (
        <ErrorState error={memories.error} onRetry={() => void memories.refetch()} />
      ) : !memories.data?.length ? (
        <EmptyState title="暂无 Memory" description="当前作用域没有可展示的 Memory 元数据。" />
      ) : (
        <div className="content-grid">
          {memories.data.map((memory) => (
            <MemoryCard
              key={memory.id}
              memory={memory}
              onOperate={operate}
              pending={mutation.isPending}
            />
          ))}
        </div>
      )}
    </div>
  );
}

function MemoryCard({
  memory,
  onOperate,
  pending,
}: {
  memory: MemoryMetadata;
  onOperate: (memory: MemoryMetadata, operation: Operation) => void;
  pending: boolean;
}) {
  const policy = memory.policy;
  return (
    <article className="panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">{policy.scope}</p>
          <h2>{memory.id}</h2>
        </div>
        <StatusBadge phase={memory.governanceStatus} />
      </div>
      <div className="detail-list">
        <span>
          Subject<strong>{policy.subjectId || '共享策略'}</strong>
        </span>
        <span>
          Project<strong>{policy.projectId || '—'}</strong>
        </span>
        <span>
          Team<strong>{policy.teamId || '—'}</strong>
        </span>
        <span>
          Task<strong>{policy.taskId || '—'}</strong>
        </span>
        <span>
          Sensitivity<strong>{policy.sensitivity}</strong>
        </span>
        <span>
          Consent<strong>{policy.consent}</strong>
        </span>
        <span>
          Source<strong>{memory.source}</strong>
        </span>
        <span>
          Version<strong>{memory.version}</strong>
        </span>
      </div>
      <p className="muted-text">
        过期时间：{memory.expiresAt ? new Date(memory.expiresAt).toLocaleString('zh-CN') : '未设置'}
      </p>
      <div className="button-row">
        <button
          className="button button--ghost"
          disabled={pending}
          onClick={() => onOperate(memory, 'CONFIRM')}
        >
          确认
        </button>
        <button
          className="button button--ghost"
          disabled={pending}
          onClick={() => onOperate(memory, 'REVOKE')}
        >
          撤回同意
        </button>
        <button
          className="button button--ghost"
          disabled={pending}
          onClick={() => onOperate(memory, 'FREEZE')}
        >
          冻结
        </button>
        <button
          className="button button--ghost"
          disabled={pending}
          onClick={() => onOperate(memory, 'EXPORT')}
        >
          导出元数据
        </button>
        <button
          className="button button--danger"
          disabled={pending}
          onClick={() => onOperate(memory, 'DELETE')}
        >
          删除
        </button>
      </div>
    </article>
  );
}
