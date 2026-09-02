import { useEffect, useState, type ChangeEvent, type FormEvent } from 'react';
import {
  createManagementUser,
  disableProvisionedUser,
  initializeProvisionedUser,
  listProvisionedUserMemberships,
  listManagementUsers,
  upsertExternalIdentity,
  upsertOrganizationMembership,
  updateProvisionedUser,
} from '../../api/management';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

export function ManagementIdentityPage() {
  const [notice, setNotice] = useState<Notice>();
  const [users, setUsers] = useState<
    Array<{ id: string; subject: string; displayName: string; status: string }>
  >([]);
  const [user, setUser] = useState({ subject: '', displayName: '' });
  const [membership, setMembership] = useState({ organizationId: '', subject: '', role: 'MEMBER' });
  const [identity, setIdentity] = useState({
    integrationId: '',
    organizationId: '',
    internalUserId: '',
    externalOrganizationId: '',
    externalUserId: '',
  });
  const [provisioned, setProvisioned] = useState({
    integrationId: '',
    externalOrganizationId: '',
    externalUserId: '',
    displayName: '',
  });
  const [provisionedMemberships, setProvisionedMemberships] = useState<
    Array<{ scopeType: string; scopeId: string; scopeName: string; role: string }>
  >([]);

  async function refreshUsers() {
    try {
      setUsers(await listManagementUsers());
    } catch {
      // The form remains usable when the current principal can write but not list users.
    }
  }

  useEffect(() => {
    void refreshUsers();
  }, []);

  async function submit(action: () => Promise<unknown>, success: string) {
    setNotice(undefined);
    try {
      await action();
      setNotice({ kind: 'success', text: success });
    } catch (error) {
      setNotice({ kind: 'error', text: error instanceof Error ? error.message : '请求失败' });
    }
  }

  function submitUser(event: FormEvent) {
    event.preventDefault();
    void submit(() => createManagementUser(user), '内部用户已创建');
  }

  function submitMembership(event: FormEvent) {
    event.preventDefault();
    void submit(
      () =>
        upsertOrganizationMembership(membership.organizationId, {
          subject: membership.subject,
          role: membership.role,
        }),
      '组织成员角色已保存',
    );
  }

  function submitIdentity(event: FormEvent) {
    event.preventDefault();
    void submit(
      () =>
        upsertExternalIdentity(identity.integrationId, {
          organizationId: identity.organizationId,
          internalUserId: identity.internalUserId,
          externalOrganizationId: identity.externalOrganizationId,
          externalUserId: identity.externalUserId,
        }),
      '外部用户映射已保存',
    );
  }

  function lifecycleInput(event: ChangeEvent<HTMLInputElement>) {
    setProvisioned({ ...provisioned, [event.target.name]: event.target.value });
  }

  async function initializeExternalUser() {
    await submit(async () => {
      const result = await initializeProvisionedUser(provisioned.integrationId, {
        externalOrganizationId: provisioned.externalOrganizationId,
        externalUserId: provisioned.externalUserId,
        displayName: provisioned.displayName,
      });
      setProvisioned((current) => ({
        ...current,
        displayName: result.displayName || current.displayName,
      }));
    }, '外部用户已初始化');
  }

  async function updateExternalUser() {
    await submit(
      () =>
        updateProvisionedUser(
          provisioned.integrationId,
          provisioned.externalOrganizationId,
          provisioned.externalUserId,
          {
            displayName: provisioned.displayName,
          },
        ),
      '外部用户已更新',
    );
  }

  async function disableExternalUser() {
    await submit(
      () =>
        disableProvisionedUser(
          provisioned.integrationId,
          provisioned.externalOrganizationId,
          provisioned.externalUserId,
        ),
      '外部用户已停用',
    );
  }

  async function loadExternalMemberships() {
    await submit(async () => {
      const result = await listProvisionedUserMemberships(
        provisioned.integrationId,
        provisioned.externalOrganizationId,
        provisioned.externalUserId,
      );
      setProvisionedMemberships(result);
    }, 'Membership 查询完成');
  }

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT</p>
          <h1>身份与权限管理</h1>
          <p className="page-subtitle">
            管理内部用户、组织角色，以及 SDK 外部用户到内部身份的映射。
          </p>
        </div>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      <div className="content-grid">
        <form className="form-panel" onSubmit={submitUser}>
          <h2>内部用户</h2>
          <label htmlFor="management-user-subject">内部用户 Subject</label>
          <input
            id="management-user-subject"
            value={user.subject}
            onChange={(event) => setUser({ ...user, subject: event.target.value })}
            required
          />
          <label htmlFor="management-user-name">内部用户名称</label>
          <input
            id="management-user-name"
            value={user.displayName}
            onChange={(event) => setUser({ ...user, displayName: event.target.value })}
            required
          />
          <button className="button button--primary" type="submit">
            创建内部用户
          </button>
          {users.length > 0 && (
            <div className="stack-list" aria-label="已登记内部用户">
              {users.map((item) => (
                <div className="stack-list__item" key={item.id}>
                  <span>
                    {item.displayName} · {item.subject}
                  </span>
                  <span className="muted-text">{item.status}</span>
                </div>
              ))}
            </div>
          )}
        </form>

        <form className="form-panel" onSubmit={submitMembership}>
          <h2>组织成员角色</h2>
          <label htmlFor="membership-organization">组织 ID</label>
          <input
            id="membership-organization"
            value={membership.organizationId}
            onChange={(event) =>
              setMembership({ ...membership, organizationId: event.target.value })
            }
            required
          />
          <label htmlFor="membership-subject">成员 Subject</label>
          <input
            id="membership-subject"
            value={membership.subject}
            onChange={(event) => setMembership({ ...membership, subject: event.target.value })}
            required
          />
          <label htmlFor="membership-role">角色</label>
          <select
            id="membership-role"
            value={membership.role}
            onChange={(event) => setMembership({ ...membership, role: event.target.value })}
          >
            {['OWNER', 'ADMIN', 'MEMBER', 'AUDITOR'].map((role) => (
              <option key={role}>{role}</option>
            ))}
          </select>
          <button className="button button--primary" type="submit">
            保存组织成员角色
          </button>
        </form>

        <form className="form-panel" onSubmit={submitIdentity}>
          <h2>外部用户映射</h2>
          <label htmlFor="identity-integration">Integration ID</label>
          <input
            id="identity-integration"
            value={identity.integrationId}
            onChange={(event) => setIdentity({ ...identity, integrationId: event.target.value })}
            required
          />
          <label htmlFor="identity-organization">组织 ID（身份映射）</label>
          <input
            id="identity-organization"
            value={identity.organizationId}
            onChange={(event) => setIdentity({ ...identity, organizationId: event.target.value })}
            required
          />
          <label htmlFor="identity-user">内部用户 UUID</label>
          <input
            id="identity-user"
            value={identity.internalUserId}
            onChange={(event) => setIdentity({ ...identity, internalUserId: event.target.value })}
            required
          />
          <label htmlFor="identity-external-organization">外部组织 ID</label>
          <input
            id="identity-external-organization"
            value={identity.externalOrganizationId}
            onChange={(event) =>
              setIdentity({ ...identity, externalOrganizationId: event.target.value })
            }
            required
          />
          <label htmlFor="identity-external-user">外部用户 ID</label>
          <input
            id="identity-external-user"
            value={identity.externalUserId}
            onChange={(event) => setIdentity({ ...identity, externalUserId: event.target.value })}
            required
          />
          <button className="button button--primary" type="submit">
            保存外部用户映射
          </button>
        </form>

        <section className="form-panel" aria-labelledby="provisioned-user-lifecycle-heading">
          <h2 id="provisioned-user-lifecycle-heading">外部用户生命周期</h2>
          <p className="muted-text">对应 SDK 的初始化、更新、停用和 Membership 查询能力。</p>
          <label htmlFor="provisioned-integration">生命周期 Integration ID</label>
          <input
            id="provisioned-integration"
            name="integrationId"
            value={provisioned.integrationId}
            onChange={lifecycleInput}
            required
          />
          <label htmlFor="provisioned-external-organization">生命周期外部组织 ID</label>
          <input
            id="provisioned-external-organization"
            name="externalOrganizationId"
            value={provisioned.externalOrganizationId}
            onChange={lifecycleInput}
            required
          />
          <label htmlFor="provisioned-external-user">生命周期外部用户 ID</label>
          <input
            id="provisioned-external-user"
            name="externalUserId"
            value={provisioned.externalUserId}
            onChange={lifecycleInput}
            required
          />
          <label htmlFor="provisioned-display-name">生命周期用户名称</label>
          <input
            id="provisioned-display-name"
            name="displayName"
            value={provisioned.displayName}
            onChange={lifecycleInput}
            required
          />
          <div className="form-actions">
            <button
              className="button button--primary"
              type="button"
              onClick={() => void initializeExternalUser()}
            >
              初始化外部用户
            </button>
            <button className="button" type="button" onClick={() => void updateExternalUser()}>
              更新外部用户
            </button>
            <button className="button" type="button" onClick={() => void loadExternalMemberships()}>
              查询 Membership
            </button>
            <button
              className="button button--ghost"
              type="button"
              onClick={() => void disableExternalUser()}
            >
              停用外部用户
            </button>
          </div>
          {provisionedMemberships.length > 0 && (
            <div className="stack-list" aria-label="外部用户有效 Membership">
              {provisionedMemberships.map((membership) => (
                <div
                  className="stack-list__item"
                  key={`${membership.scopeType}-${membership.scopeId}`}
                >
                  <span>{membership.scopeName}</span>
                  <span className="muted-text">
                    {membership.scopeType} · {membership.role}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
