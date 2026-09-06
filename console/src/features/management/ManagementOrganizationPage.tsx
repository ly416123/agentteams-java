import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  createManagementOrganization,
  createManagementTenant,
  listManagementOrganizations,
  listManagementTenants,
  updateManagementOrganizationStatus,
  updateManagementTenantStatus,
} from '../../api/management';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { StatusBadge } from '../../components/StatusBadge';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementOrganizationPage() {
  const queryClient = useQueryClient();
  const organizations = useQuery({
    queryKey: ['management-organizations'],
    queryFn: () => listManagementOrganizations(),
  });
  const [notice, setNotice] = useState<Notice>();
  const [organizationName, setOrganizationName] = useState('');
  const [tenantName, setTenantName] = useState('');
  const [organizationId, setOrganizationId] = useState('');
  const selectedOrganizationId = organizationId || organizations.data?.[0]?.id || '';
  const tenants = useQuery({
    queryKey: ['management-tenants', selectedOrganizationId],
    queryFn: () => listManagementTenants(selectedOrganizationId),
    enabled: Boolean(selectedOrganizationId),
  });
  const refreshOrganizations = () =>
    queryClient.invalidateQueries({ queryKey: ['management-organizations'] });
  const refreshTenants = () =>
    queryClient.invalidateQueries({ queryKey: ['management-tenants', selectedOrganizationId] });
  const createOrganization = useMutation({
    mutationFn: () => createManagementOrganization({ name: organizationName }),
    onSuccess: (value) => {
      setOrganizationId(value.id);
      setOrganizationName('');
      setNotice({ kind: 'success', text: '组织已创建' });
      void refreshOrganizations();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const createTenant = useMutation({
    mutationFn: () => createManagementTenant(selectedOrganizationId, { name: tenantName }),
    onSuccess: () => {
      setTenantName('');
      setNotice({ kind: 'success', text: '租户已创建' });
      void refreshTenants();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const updateOrganizationStatus = useMutation({
    mutationFn: ({
      id,
      expectedVersion,
      status,
    }: {
      id: string;
      expectedVersion: number;
      status: 'ACTIVE' | 'SUSPENDED';
    }) => updateManagementOrganizationStatus(id, { expectedVersion, status }),
    onSuccess: () => {
      setNotice({ kind: 'success', text: '组织状态已更新' });
      void refreshOrganizations();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const updateTenantStatus = useMutation({
    mutationFn: ({
      id,
      expectedVersion,
      status,
    }: {
      id: string;
      expectedVersion: number;
      status: 'ACTIVE' | 'SUSPENDED';
    }) => updateManagementTenantStatus(id, { expectedVersion, status }),
    onSuccess: () => {
      setNotice({ kind: 'success', text: '租户状态已更新' });
      void refreshTenants();
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  function submitOrganization(event: FormEvent) {
    event.preventDefault();
    createOrganization.mutate();
  }

  function submitTenant(event: FormEvent) {
    event.preventDefault();
    createTenant.mutate();
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">管理 / 组织</p>
          <h1>组织与租户</h1>
          <p className="page-subtitle">
            先建立组织和租户，再在项目/团队层配置工作节点资源；创建本身不会部署 Pod。
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
        <form className="form-panel" onSubmit={submitOrganization}>
          <h2>创建组织</h2>
          <label htmlFor="organization-name">组织名称</label>
          <input
            id="organization-name"
            value={organizationName}
            onChange={(event) => setOrganizationName(event.target.value)}
            required
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={createOrganization.isPending}
          >
            创建组织
          </button>
        </form>
        <form className="form-panel" onSubmit={submitTenant}>
          <h2>创建租户</h2>
          <label htmlFor="tenant-organization">组织</label>
          <select
            id="tenant-organization"
            value={selectedOrganizationId}
            onChange={(event) => setOrganizationId(event.target.value)}
            required
          >
            <option value="">选择组织</option>
            {(organizations.data || []).map((organization) => (
              <option value={organization.id} key={organization.id}>
                {organization.name}
              </option>
            ))}
          </select>
          <label htmlFor="tenant-name">租户名称</label>
          <input
            id="tenant-name"
            value={tenantName}
            onChange={(event) => setTenantName(event.target.value)}
            required
          />
          <button
            className="button button--primary"
            type="submit"
            disabled={!selectedOrganizationId || createTenant.isPending}
          >
            创建租户
          </button>
        </form>
      </div>

      {organizations.isLoading ? (
        <div className="panel loading-block">加载中…</div>
      ) : organizations.isError ? (
        <ErrorState error={organizations.error} onRetry={() => void organizations.refetch()} />
      ) : !organizations.data?.length ? (
        <EmptyState title="暂无组织" description="创建组织后可继续建立租户。" />
      ) : (
        <div className="content-grid">
          {organizations.data.map((organization) => (
            <article className="panel" key={organization.id}>
              <div className="panel-heading">
                <div>
                  <h2>{organization.name}</h2>
                  <p className="muted-text">{organization.id}</p>
                </div>
                <StatusBadge phase={organization.status} />
                {organization.status !== 'DELETED' && (
                  <button
                    className="button button--ghost"
                    type="button"
                    disabled={updateOrganizationStatus.isPending}
                    onClick={() =>
                      updateOrganizationStatus.mutate({
                        id: organization.id,
                        expectedVersion: organization.version,
                        status: organization.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                      })
                    }
                  >
                    {organization.status === 'ACTIVE' ? '暂停组织' : '恢复组织'}
                  </button>
                )}
              </div>
              {organization.id === selectedOrganizationId && (
                <div className="stack-list" aria-label={`${organization.name} 的租户`}>
                  {tenants.isLoading ? (
                    <span className="muted-text">租户加载中…</span>
                  ) : tenants.data?.length ? (
                    tenants.data.map((tenant) => (
                      <div className="stack-list__item" key={tenant.id}>
                        <span>{tenant.name}</span>
                        <StatusBadge phase={tenant.status} />
                        {tenant.status !== 'DELETED' && (
                          <button
                            className="button button--ghost"
                            type="button"
                            disabled={updateTenantStatus.isPending}
                            onClick={() =>
                              updateTenantStatus.mutate({
                                id: tenant.id,
                                expectedVersion: tenant.version,
                                status: tenant.status === 'ACTIVE' ? 'SUSPENDED' : 'ACTIVE',
                              })
                            }
                          >
                            {tenant.status === 'ACTIVE' ? '暂停租户' : '恢复租户'}
                          </button>
                        )}
                      </div>
                    ))
                  ) : (
                    <span className="muted-text">暂无租户</span>
                  )}
                </div>
              )}
            </article>
          ))}
        </div>
      )}
    </div>
  );
}
