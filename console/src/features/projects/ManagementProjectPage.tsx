import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { createProject, listProjects } from '../../api/projects';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';
import { queryKeys } from '../../queries/queryKeys';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementProjectPage() {
  const queryClient = useQueryClient();
  const projects = useQuery({ queryKey: queryKeys.projects, queryFn: () => listProjects() });
  const [name, setName] = useState('');
  const [notice, setNotice] = useState<Notice>();
  const create = useMutation({
    mutationFn: () => createProject({ name }),
    onSuccess: () => {
      setName('');
      setNotice({ kind: 'success', text: '项目已创建' });
      void queryClient.invalidateQueries({ queryKey: queryKeys.projects });
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
          <p className="eyebrow">管理 / 项目</p>
          <h1>项目管理</h1>
          <p className="page-subtitle">
            创建和查看当前租户内可访问的项目。项目创建只建立业务作用域，不会自动部署工作节点 Pod。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void projects.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <form className="panel form-panel" onSubmit={submit}>
        <h2>创建项目</h2>
        <label htmlFor="management-project-name">项目名称</label>
        <input
          id="management-project-name"
          value={name}
          onChange={(event) => setName(event.target.value)}
          required
        />
        <button className="button button--primary" type="submit" disabled={create.isPending}>
          {create.isPending ? '创建中…' : '创建项目'}
        </button>
      </form>
      {projects.isLoading ? (
        <div className="panel loading-block">加载项目…</div>
      ) : projects.isError ? (
        <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />
      ) : !projects.data?.items.length ? (
        <EmptyState title="暂无项目" description="创建后，项目会出现在当前作用域列表中。" />
      ) : (
        <section className="content-grid" aria-label="项目列表">
          {projects.data.items.map((project) => (
            <article className="panel" key={project.id}>
              <div className="panel-heading">
                <div>
                  <h2>{project.name}</h2>
                  <p className="muted-text">租户 {project.tenantId}</p>
                </div>
                <StatusBadge phase={project.status} />
              </div>
              <p className="muted-text">项目 ID {project.id}</p>
              <p className="muted-text">创建者 {project.createdBy}</p>
            </article>
          ))}
        </section>
      )}
    </div>
  );
}
