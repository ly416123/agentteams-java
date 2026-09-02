import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createManagementCredential,
  createManagementIntegration,
  listIntegrationCredentials,
  listManagementIntegrations,
  listManagementOrganizations,
  revokeManagementCredential,
  rotateManagementCredential,
} from '../../api/management';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;
type Credential = {
  id: string;
  integrationId: string;
  label: string;
  accessKeyId: string;
  version: number;
  status: string;
};

export function ManagementIntegrationPage() {
  const queryClient = useQueryClient();
  const organizations = useQuery({
    queryKey: ['management-organizations'],
    queryFn: () => listManagementOrganizations(),
  });
  const [organizationId, setOrganizationId] = useState('');
  const selectedOrganizationId = organizationId || organizations.data?.[0]?.id || '';
  const integrations = useQuery({
    queryKey: ['management-integrations', selectedOrganizationId],
    queryFn: () => listManagementIntegrations(selectedOrganizationId),
    enabled: Boolean(selectedOrganizationId),
  });
  const [integrationId, setIntegrationId] = useState('');
  const selectedIntegrationId = integrationId || integrations.data?.[0]?.id || '';
  const credentials = useQuery({
    queryKey: ['integration-credentials', selectedIntegrationId],
    queryFn: () => listIntegrationCredentials(selectedIntegrationId),
    enabled: Boolean(selectedIntegrationId),
  });
  const [notice, setNotice] = useState<Notice>();
  const [integrationName, setIntegrationName] = useState('');
  const [credential, setCredential] = useState({ label: '', credentialRef: '' });
  const [rotateRef, setRotateRef] = useState('');
  const refreshIntegrations = () =>
    queryClient.invalidateQueries({
      queryKey: ['management-integrations', selectedOrganizationId],
    });
  const refreshCredentials = () =>
    queryClient.invalidateQueries({ queryKey: ['integration-credentials', selectedIntegrationId] });
  const createIntegration = useMutation({
    mutationFn: () =>
      createManagementIntegration(selectedOrganizationId, { name: integrationName }),
    onSuccess: () => {
      setIntegrationName('');
      setNotice({ kind: 'success', text: 'Integration 已创建' });
      void refreshIntegrations();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createCredential = useMutation({
    mutationFn: () => createManagementCredential(selectedIntegrationId, credential),
    onSuccess: () => {
      setCredential({ label: '', credentialRef: '' });
      setNotice({ kind: 'success', text: 'Credential Ref 已登记' });
      void refreshCredentials();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const rotate = useMutation({
    mutationFn: (item: Credential) =>
      rotateManagementCredential(item.id, {
        expectedVersion: item.version,
        credentialRef: rotateRef,
      }),
    onSuccess: () => {
      setRotateRef('');
      setNotice({ kind: 'success', text: 'Credential 已轮换' });
      void refreshCredentials();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const revoke = useMutation({
    mutationFn: (item: Credential) =>
      revokeManagementCredential(item.id, { expectedVersion: item.version }),
    onSuccess: () => {
      setNotice({ kind: 'success', text: 'Credential 已撤销' });
      void refreshCredentials();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submitIntegration(event: FormEvent) {
    event.preventDefault();
    createIntegration.mutate();
  }

  function submitCredential(event: FormEvent) {
    event.preventDefault();
    createCredential.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / INTEGRATIONS</p>
          <h1>Integrations 与 Credentials</h1>
          <p className="page-subtitle">
            管理外部 Integration 和凭据引用。页面只处理 Credential Ref，不接收或展示 Secret 明文。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void organizations.refetch()}>
          刷新
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <div className="content-grid">
        <form className="form-panel" onSubmit={submitIntegration}>
          <h2>创建 Integration</h2>
          <label htmlFor="integration-organization">Organization</label>
          <select
            id="integration-organization"
            value={selectedOrganizationId}
            onChange={(event) => setOrganizationId(event.target.value)}
            required
          >
            <option value="">选择 Organization</option>
            {(organizations.data || []).map((item) => (
              <option value={item.id} key={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          <label htmlFor="integration-name">Integration 名称</label>
          <input
            id="integration-name"
            value={integrationName}
            onChange={(event) => setIntegrationName(event.target.value)}
            required
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={createIntegration.isPending}
          >
            创建 Integration
          </button>
        </form>
        <form className="form-panel" onSubmit={submitCredential}>
          <h2>登记 Credential Ref</h2>
          <label htmlFor="credential-integration">Integration</label>
          <select
            id="credential-integration"
            value={selectedIntegrationId}
            onChange={(event) => setIntegrationId(event.target.value)}
            required
          >
            <option value="">选择 Integration</option>
            {(integrations.data || []).map((item) => (
              <option value={item.id} key={item.id}>
                {item.name}
              </option>
            ))}
          </select>
          <label htmlFor="credential-label">Credential Label</label>
          <input
            id="credential-label"
            value={credential.label}
            onChange={(event) => setCredential({ ...credential, label: event.target.value })}
            required
          />
          <label htmlFor="credential-ref">Credential Ref</label>
          <input
            id="credential-ref"
            value={credential.credentialRef}
            onChange={(event) =>
              setCredential({ ...credential, credentialRef: event.target.value })
            }
            placeholder="secret://..."
            required
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={createCredential.isPending}
          >
            登记 Credential Ref
          </button>
        </form>
      </div>

      {organizations.isLoading || integrations.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : organizations.isError || integrations.isError ? (
        <ErrorState
          error={organizations.error || integrations.error}
          onRetry={() => void organizations.refetch()}
        />
      ) : !integrations.data?.length ? (
        <EmptyState
          title="暂无 Integration"
          description="创建 Integration 后可登记 Credential Ref。"
        />
      ) : (
        <div className="content-grid">
          {integrations.data.map((integration) => (
            <article className="panel" key={integration.id}>
              <div className="panel-heading">
                <div>
                  <h2>{integration.name}</h2>
                  <p className="muted-text">{integration.id}</p>
                </div>
                <span className="status-badge">{integration.status}</span>
              </div>
              {integration.id === selectedIntegrationId && (
                <div className="stack-list" aria-label={`${integration.name} 的 Credentials`}>
                  {credentials.isLoading ? (
                    <span className="muted-text">Credential 加载中…</span>
                  ) : credentials.data?.length ? (
                    credentials.data.map((item) => (
                      <div className="stack-list__item" key={item.id}>
                        <span>
                          {item.label} · {item.accessKeyId} · {item.status}
                        </span>
                        <div className="form-actions">
                          <button
                            className="button button--small"
                            disabled={rotate.isPending || !rotateRef}
                            onClick={() => rotate.mutate(item)}
                          >
                            轮换
                          </button>
                          <button
                            className="button button--small button--ghost"
                            disabled={revoke.isPending || item.status === 'REVOKED'}
                            onClick={() => {
                              if (
                                window.confirm(
                                  '确认撤销 Credential？撤销后引用它的运行时将无法继续使用。',
                                )
                              ) {
                                revoke.mutate(item);
                              }
                            }}
                          >
                            撤销
                          </button>
                        </div>
                      </div>
                    ))
                  ) : (
                    <span className="muted-text">暂无 Credential</span>
                  )}
                  <label htmlFor="credential-rotate-ref">轮换 Credential Ref</label>
                  <input
                    id="credential-rotate-ref"
                    value={rotateRef}
                    onChange={(event) => setRotateRef(event.target.value)}
                    placeholder="secret://..."
                  />
                </div>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
