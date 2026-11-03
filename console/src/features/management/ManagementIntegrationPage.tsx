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
import { StatusBadge } from '../../components/StatusBadge';
import { labelStatus } from '../../i18n/labels';
import { PermissionGate } from '../../auth/PermissionGate';
import { IdDisplay } from '../../components/IdDisplay';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';

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
  const [pendingRevoke, setPendingRevoke] = useState<Credential>();
  const refreshIntegrations = () =>
    queryClient.invalidateQueries({
      queryKey: ['management-integrations', selectedOrganizationId],
    });
  const refreshCredentials = () =>
    queryClient.invalidateQueries({ queryKey: ['integration-credentials', selectedIntegrationId] });
  const createIntegration = useMutation({
    mutationFn: () =>
      createManagementIntegration(selectedOrganizationId, { name: integrationName }),
    onSuccess: async () => {
      setIntegrationName('');
      await refreshIntegrations();
      setNotice({ kind: 'success', text: '集成已创建' });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createCredential = useMutation({
    mutationFn: () => createManagementCredential(selectedIntegrationId, credential),
    onSuccess: async () => {
      setCredential({ label: '', credentialRef: '' });
      await refreshCredentials();
      setNotice({ kind: 'success', text: '凭据引用已登记' });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const rotate = useMutation({
    mutationFn: (item: Credential) =>
      rotateManagementCredential(item.id, {
        expectedVersion: item.version,
        credentialRef: rotateRef,
      }),
    onSuccess: async () => {
      setRotateRef('');
      await refreshCredentials();
      setNotice({ kind: 'success', text: '凭据已轮换' });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const revoke = useMutation({
    mutationFn: (item: Credential) =>
      revokeManagementCredential(item.id, { expectedVersion: item.version }),
    onSuccess: async () => {
      await refreshCredentials();
      setNotice({ kind: 'success', text: '凭据已撤销' });
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
          <p className="eyebrow">管理 / 集成</p>
          <h1>集成与凭据</h1>
          <p className="page-subtitle">
            管理外部集成和凭据引用。页面只处理凭据引用，不接收或展示 Secret 明文。
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
      <details className="form-disclosure">
        <summary>新增集成或凭据引用</summary>
        <div className="content-grid">
          <PermissionGate permission="integration:write">
            <form className="form-panel" onSubmit={submitIntegration}>
              <h2>创建集成</h2>
              <label htmlFor="integration-organization">组织</label>
              <select
                id="integration-organization"
                value={selectedOrganizationId}
                onChange={(event) => setOrganizationId(event.target.value)}
                required
              >
                <option value="">选择组织</option>
                {(organizations.data || []).map((item) => (
                  <option value={item.id} key={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <label htmlFor="integration-name">集成名称</label>
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
                创建集成
              </button>
            </form>
          </PermissionGate>
          <PermissionGate permission="credential:manage">
            <form className="form-panel" onSubmit={submitCredential}>
              <h2>登记凭据引用</h2>
              <label htmlFor="credential-integration">集成</label>
              <select
                id="credential-integration"
                value={selectedIntegrationId}
                onChange={(event) => setIntegrationId(event.target.value)}
                required
              >
                <option value="">选择集成</option>
                {(integrations.data || []).map((item) => (
                  <option value={item.id} key={item.id}>
                    {item.name}
                  </option>
                ))}
              </select>
              <label htmlFor="credential-label">凭据标签</label>
              <input
                id="credential-label"
                value={credential.label}
                onChange={(event) => setCredential({ ...credential, label: event.target.value })}
                required
              />
              <label htmlFor="credential-ref">凭据引用</label>
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
                登记凭据引用
              </button>
            </form>
          </PermissionGate>
        </div>
      </details>

      {organizations.isLoading || integrations.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : organizations.isError || integrations.isError ? (
        <ErrorState
          error={organizations.error || integrations.error}
          onRetry={() => void organizations.refetch()}
        />
      ) : !integrations.data?.length ? (
        <EmptyState title="暂无集成" description="创建集成后可登记凭据引用。" />
      ) : (
        <div className="content-grid">
          {integrations.data.map((integration) => (
            <article className="panel" key={integration.id}>
              <div className="panel-heading">
                <div>
                  <h2>{integration.name}</h2>
                  <p className="muted-text">
                    <IdDisplay value={integration.id} />
                  </p>
                </div>
                <StatusBadge phase={integration.status} />
              </div>
              {integration.id === selectedIntegrationId && (
                <div className="stack-list" aria-label={`${integration.name} 的凭据`}>
                  {credentials.isLoading ? (
                    <span className="muted-text">凭据加载中…</span>
                  ) : credentials.data?.length ? (
                    credentials.data.map((item) => (
                      <div className="stack-list__item" key={item.id}>
                        <span>
                          {item.label} · {item.accessKeyId} · {labelStatus(item.status)}
                        </span>
                        <div className="form-actions">
                          <PermissionGate permission="credential:rotate">
                            <button
                              className="button button--small"
                              disabled={rotate.isPending || !rotateRef}
                              onClick={() => rotate.mutate(item)}
                            >
                              轮换
                            </button>
                          </PermissionGate>
                          <PermissionGate permission="credential:revoke">
                            <button
                              className="button button--small button--ghost"
                              disabled={revoke.isPending || item.status === 'REVOKED'}
                              onClick={() => setPendingRevoke(item)}
                            >
                              撤销
                            </button>
                          </PermissionGate>
                        </div>
                      </div>
                    ))
                  ) : (
                    <span className="muted-text">暂无凭据</span>
                  )}
                  <PermissionGate permission="credential:rotate">
                    <label htmlFor="credential-rotate-ref">轮换凭据引用</label>
                    <input
                      id="credential-rotate-ref"
                      value={rotateRef}
                      onChange={(event) => setRotateRef(event.target.value)}
                      placeholder="secret://..."
                    />
                  </PermissionGate>
                </div>
              )}
            </article>
          ))}
        </div>
      )}
      <ActionConfirmModal
        open={Boolean(pendingRevoke)}
        actionLabel="撤销凭据"
        impact={
          pendingRevoke
            ? `目标凭据：${pendingRevoke.label}（${pendingRevoke.accessKeyId}）。撤销后引用它的运行时将无法继续使用。`
            : ''
        }
        onCancel={() => setPendingRevoke(undefined)}
        onConfirm={() => {
          if (pendingRevoke) revoke.mutate(pendingRevoke);
          setPendingRevoke(undefined);
        }}
      />
    </div>
  );
}
