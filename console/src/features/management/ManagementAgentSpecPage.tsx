import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createAgentSpec,
  deactivateAgentSpec,
  listAgentSpecs,
  publishAgentSpec,
} from '../../api/managementCatalog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementAgentSpecPage({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const specs = useQuery({
    queryKey: ['agent-specs', projectId],
    queryFn: () => listAgentSpecs(projectId),
  });
  const [notice, setNotice] = useState<Notice>();
  const [form, setForm] = useState({
    name: '',
    runtime: 'qwenpaw',
    modelProvider: '',
    modelName: '',
    teamRef: '',
    desiredState: 'RUNNING',
    spec: '{}',
  });
  const refresh = () => queryClient.invalidateQueries({ queryKey: ['agent-specs'] });
  const create = useMutation({
    mutationFn: () => {
      let spec: unknown;
      try {
        spec = JSON.parse(form.spec);
      } catch {
        throw new Error('Spec JSON 格式无效');
      }
      return createAgentSpec(projectId, { ...form, teamRef: form.teamRef || undefined, spec });
    },
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'AgentSpec 已创建' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const publish = useMutation({
    mutationFn: (id: string) => publishAgentSpec(projectId, id),
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'AgentSpec 已发布' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const deactivate = useMutation({
    mutationFn: (id: string) => deactivateAgentSpec(projectId, id),
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'AgentSpec 已停用' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / AGENT SPECS</p>
          <h1>Agent Specs</h1>
          <p className="page-subtitle">
            管理 AgentSpec 生命周期。发布只改变配置状态，Worker 部署必须通过显式实例化操作完成。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void specs.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <form className="panel form-panel" onSubmit={submit}>
        <h2>创建 AgentSpec</h2>
        <div className="form-grid">
          <label>
            内部名称
            <input
              value={form.name}
              onChange={(event) => setForm({ ...form, name: event.target.value })}
              required
            />
          </label>
          <label>
            Runtime
            <input
              value={form.runtime}
              onChange={(event) => setForm({ ...form, runtime: event.target.value })}
              required
            />
          </label>
          <label>
            Model Provider
            <input
              value={form.modelProvider}
              onChange={(event) => setForm({ ...form, modelProvider: event.target.value })}
              required
            />
          </label>
          <label>
            Model Name
            <input
              value={form.modelName}
              onChange={(event) => setForm({ ...form, modelName: event.target.value })}
              required
            />
          </label>
          <label>
            Team Ref（可选）
            <input
              value={form.teamRef}
              onChange={(event) => setForm({ ...form, teamRef: event.target.value })}
            />
          </label>
        </div>
        <label>
          Spec JSON
          <textarea
            rows={5}
            value={form.spec}
            onChange={(event) => setForm({ ...form, spec: event.target.value })}
            required
          />
        </label>
        <button className="button button--primary" type="submit" disabled={create.isPending}>
          {create.isPending ? '创建中…' : '创建 AgentSpec'}
        </button>
      </form>

      {specs.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : specs.isError ? (
        <ErrorState error={specs.error} onRetry={() => void specs.refetch()} />
      ) : !specs.data?.length ? (
        <EmptyState title="暂无 AgentSpec" description="创建后可继续审核、发布或停用。" />
      ) : (
        <div className="content-grid">
          {specs.data.map((spec) => (
            <article className="panel" key={spec.id}>
              <div className="panel-heading">
                <div>
                  <h2>{spec.name}</h2>
                  <p className="muted-text">
                    {spec.runtime} · {spec.modelProvider}/{spec.modelName}
                  </p>
                </div>
                <span className="status-badge">{spec.lifecycleStatus}</span>
              </div>
              <p className="muted-text">
                Project {spec.projectId} · version {spec.version}
                {spec.teamRef ? ` · Team ${spec.teamRef}` : ' · Project scope'}
              </p>
              <div className="form-actions">
                <button
                  className="button button--primary"
                  disabled={publish.isPending || spec.lifecycleStatus === 'PUBLISHED'}
                  onClick={() => publish.mutate(spec.id)}
                >
                  发布
                </button>
                <button
                  className="button button--ghost"
                  disabled={deactivate.isPending || spec.lifecycleStatus === 'DISABLED'}
                  onClick={() => deactivate.mutate(spec.id)}
                >
                  停用
                </button>
              </div>
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
