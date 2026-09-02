import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createWorkerTemplate,
  createWorkerTemplateRevision,
  instantiateWorkerTemplate,
  listWorkerTemplates,
  publishWorkerTemplateRevision,
  type WorkerTemplateRevision,
} from '../../api/managementCatalog';
import { ErrorState } from '../../components/ErrorState';
import { EmptyState } from '../../components/EmptyState';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementTemplatePage({ projectId }: { projectId: string }) {
  const queryClient = useQueryClient();
  const templates = useQuery({
    queryKey: ['worker-templates'],
    queryFn: () => listWorkerTemplates(projectId),
  });
  const [notice, setNotice] = useState<Notice>();
  const [template, setTemplate] = useState({ name: '', displayName: '' });
  const [revision, setRevision] = useState({ templateId: '', specJson: '{}', actor: '' });
  const [lastRevision, setLastRevision] = useState<WorkerTemplateRevision>();

  const refresh = () => queryClient.invalidateQueries({ queryKey: ['worker-templates'] });
  const create = useMutation({
    mutationFn: () => createWorkerTemplate(projectId, template),
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'Worker Template 已创建' });
      setTemplate({ name: '', displayName: '' });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createRevision = useMutation({
    mutationFn: () =>
      createWorkerTemplateRevision(projectId, revision.templateId, {
        specJson: revision.specJson,
        actor: revision.actor,
      }),
    onSuccess: (value) => {
      setLastRevision(value);
      setNotice({ kind: 'success', text: `Revision ${value.revision} 已创建` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const publish = useMutation({
    mutationFn: (value: WorkerTemplateRevision) =>
      publishWorkerTemplateRevision(projectId, value.templateId, value.revision, value.version),
    onSuccess: (value) => {
      setLastRevision(value);
      setNotice({ kind: 'success', text: `Revision ${value.revision} 已发布` });
      void refresh();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const instantiate = useMutation({
    mutationFn: (value: { templateId: string; revision: number }) =>
      instantiateWorkerTemplate(projectId, value.templateId, value.revision),
    onSuccess: (value) =>
      setNotice({ kind: 'success', text: `已创建实例 ${value.id}，等待 Worker Ready` }),
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submitTemplate(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }

  function submitRevision(event: FormEvent) {
    event.preventDefault();
    createRevision.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / TEMPLATES</p>
          <h1>Worker Templates</h1>
          <p className="page-subtitle">
            管理模板和版本；只有显式实例化已发布 Revision 才会进入 Worker 供给链路。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void templates.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <div className="content-grid">
        <form className="form-panel" onSubmit={submitTemplate}>
          <h2>创建模板</h2>
          <label htmlFor="template-name">内部名称</label>
          <input
            id="template-name"
            value={template.name}
            onChange={(event) => setTemplate({ ...template, name: event.target.value })}
            required
          />
          <label htmlFor="template-display-name">显示名称</label>
          <input
            id="template-display-name"
            value={template.displayName}
            onChange={(event) => setTemplate({ ...template, displayName: event.target.value })}
            required
          />
          <button className="button button--primary" type="submit" disabled={create.isPending}>
            {create.isPending ? '创建中…' : '创建模板'}
          </button>
        </form>

        <form className="form-panel" onSubmit={submitRevision}>
          <h2>创建 Revision</h2>
          <label htmlFor="revision-template">模板</label>
          <select
            id="revision-template"
            value={revision.templateId}
            onChange={(event) => setRevision({ ...revision, templateId: event.target.value })}
            required
          >
            <option value="">选择模板</option>
            {(templates.data || []).map((item) => (
              <option value={item.id} key={item.id}>
                {item.displayName} ({item.name})
              </option>
            ))}
          </select>
          <label htmlFor="revision-spec">Worker Spec JSON</label>
          <textarea
            id="revision-spec"
            rows={6}
            value={revision.specJson}
            onChange={(event) => setRevision({ ...revision, specJson: event.target.value })}
            required
          />
          <label htmlFor="revision-actor">变更说明人</label>
          <input
            id="revision-actor"
            value={revision.actor}
            onChange={(event) => setRevision({ ...revision, actor: event.target.value })}
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={createRevision.isPending}
          >
            {createRevision.isPending ? '保存中…' : '创建 Revision'}
          </button>
          {lastRevision && (
            <div className="info-box">
              Revision {lastRevision.revision} · {lastRevision.status}
              <button
                className="button button--small"
                type="button"
                disabled={publish.isPending}
                onClick={() => publish.mutate(lastRevision)}
              >
                发布此 Revision
              </button>
            </div>
          )}
        </form>
      </div>

      {templates.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : templates.isError ? (
        <ErrorState error={templates.error} onRetry={() => void templates.refetch()} />
      ) : !templates.data?.length ? (
        <EmptyState
          title="暂无 Worker Template"
          description="先创建模板，再创建和发布 Revision。"
        />
      ) : (
        <div className="content-grid">
          {templates.data.map((item) => (
            <article className="panel" key={item.id}>
              <div className="panel-heading">
                <div>
                  <h2>{item.displayName}</h2>
                  <p className="muted-text">{item.name}</p>
                </div>
                <span className="status-badge">
                  {item.currentPublishedRevision
                    ? `已发布 v${item.currentPublishedRevision}`
                    : '未发布'}
                </span>
              </div>
              <p className="muted-text">
                Project {item.projectId} · version {item.version}
              </p>
              {item.currentPublishedRevision && (
                <button
                  className="button button--primary"
                  disabled={instantiate.isPending}
                  onClick={() =>
                    instantiate.mutate({
                      templateId: item.id,
                      revision: item.currentPublishedRevision as number,
                    })
                  }
                >
                  显式实例化 Worker
                </button>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
