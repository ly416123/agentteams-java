import { useAuthorization } from '../../auth/AuthorizationProvider';
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  changeProjectMemberRole,
  getProjectRole,
  listProjectMembers,
  listProjectRolePermissions,
  listProjects,
  transferProjectOwner,
  type ProjectMember,
  type ProjectRolePermissions,
} from '../../api/projects';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { ActionConfirmModal } from '../../components/ActionConfirmModal';
import { labelRole, labelStatus } from '../../i18n/labels';
import {
  getEffectiveAuthorization,
  grantOrganizationAdministrator,
  grantPlatformAdministrator,
  listManagementRoleAssignments,
  revokeManagementAdministrator,
  type ManagementRoleAssignment,
} from '../../api/authorization';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

const EDITABLE_ROLES = ['ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER'];

export function ManagementRolePage() {
  const authorization = useAuthorization();
  const queryClient = useQueryClient();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => listProjects() });
  const [projectId, setProjectId] = useState('');
  const selectedProjectId = projectId || projects.data?.items[0]?.id || '';
  const selectedAuthorization = useQuery({
    queryKey: ['effective-authorization', authorization.permissions, selectedProjectId],
    queryFn: () => getEffectiveAuthorization(selectedProjectId),
    enabled: Boolean(selectedProjectId),
    retry: false,
  });
  const canReadMembers =
    selectedAuthorization.data?.permissions.includes('project:member:manage') === true;
  const currentRole = useQuery({
    queryKey: ['project-current-role', selectedProjectId],
    queryFn: () => getProjectRole(selectedProjectId),
    enabled: Boolean(selectedProjectId) && canReadMembers,
    retry: false,
  });
  const members = useQuery({
    queryKey: ['project-members', selectedProjectId],
    queryFn: () => listProjectMembers(selectedProjectId),
    enabled: Boolean(selectedProjectId) && canReadMembers,
  });
  const permissions = useQuery({
    queryKey: ['project-role-permissions', selectedProjectId],
    queryFn: () => listProjectRolePermissions(selectedProjectId),
    enabled: Boolean(selectedProjectId) && canReadMembers,
  });
  const [draftRoles, setDraftRoles] = useState<Record<string, string>>({});
  const [adminScope, setAdminScope] = useState<'PLATFORM' | 'ORGANIZATION'>('PLATFORM');
  const [adminOrganizationId, setAdminOrganizationId] = useState('');
  const [adminSubject, setAdminSubject] = useState('');
  const [notice, setNotice] = useState<Notice>();
  const roleMutation = useMutation({
    mutationFn: ({ member, role }: { member: ProjectMember; role: string }) =>
      changeProjectMemberRole(selectedProjectId, member.subject, {
        role,
        expectedMembershipVersion: member.version,
      }),
    onSuccess: () => {
      setNotice({ kind: 'success', text: '角色已更新' });
      void queryClient.invalidateQueries({ queryKey: ['project-members', selectedProjectId] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const [pendingOwnerTransfer, setPendingOwnerTransfer] = useState<ProjectMember>();
  const ownerTransfer = useMutation({
    mutationFn: (member: ProjectMember) => {
      const project = projects.data?.items.find((item) => item.id === selectedProjectId);
      if (!project) throw new Error('项目不存在，请刷新后重试。');
      return transferProjectOwner(selectedProjectId, {
        newOwner: member.subject,
        expectedProjectVersion: project.version,
      });
    },
    onSuccess: () => {
      setNotice({ kind: 'success', text: '项目所有者已转移' });
      void queryClient.invalidateQueries({ queryKey: ['project-members', selectedProjectId] });
      void queryClient.invalidateQueries({ queryKey: ['project-current-role', selectedProjectId] });
      void queryClient.invalidateQueries({ queryKey: ['effective-authorization'] });
      void queryClient.invalidateQueries({ queryKey: ['projects'] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  const canManagePlatformRoles = authorization.permissions.includes('management:role:manage');
  const adminAssignments = useQuery({
    queryKey: ['management-role-assignments', adminScope, adminOrganizationId],
    queryFn: () =>
      listManagementRoleAssignments(
        adminScope,
        adminScope === 'ORGANIZATION' ? adminOrganizationId : undefined,
      ),
    enabled: canManagePlatformRoles && (adminScope === 'PLATFORM' || Boolean(adminOrganizationId)),
    retry: false,
  });
  const grantAdministrator = useMutation({
    mutationFn: () =>
      adminScope === 'PLATFORM'
        ? grantPlatformAdministrator(adminSubject.trim())
        : grantOrganizationAdministrator(adminOrganizationId.trim(), adminSubject.trim()),
    onSuccess: () => {
      setAdminSubject('');
      setNotice({ kind: 'success', text: '管理员角色已授予' });
      void queryClient.invalidateQueries({ queryKey: ['management-role-assignments'] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });
  const revokeAdministrator = useMutation({
    mutationFn: (assignment: ManagementRoleAssignment) => revokeManagementAdministrator(assignment),
    onSuccess: () => {
      setNotice({ kind: 'success', text: '管理员角色已回收' });
      void queryClient.invalidateQueries({ queryKey: ['management-role-assignments'] });
      void queryClient.invalidateQueries({ queryKey: ['effective-authorization'] });
    },
    onError: (error) => setNotice({ kind: 'error', text: error.message }),
  });

  const roleFor = (member: ProjectMember) => draftRoles[member.subject] || member.role;

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">管理 / 授权</p>
          <h1>角色与权限</h1>
          <p className="page-subtitle">
            查看项目成员的有效角色和权限。角色变更使用成员版本保护；转移 OWNER 需单独确认。
          </p>
        </div>
        <button className="button button--ghost" onClick={() => void projects.refetch()}>
          刷新 Project
        </button>
      </div>
      {notice && (
        <div className={notice.kind === 'success' ? 'success-text' : 'error-text'} role="status">
          {notice.text}
        </div>
      )}
      {canManagePlatformRoles && (
        <section className="panel">
          <h2>平台与组织管理员</h2>
          <p className="muted-text">仅平台管理员可以授予或回收管理角色；回收操作按授权版本校验。</p>
          <div className="form-grid">
            <label>
              授权范围
              <select
                value={adminScope}
                onChange={(event) =>
                  setAdminScope(event.target.value as 'PLATFORM' | 'ORGANIZATION')
                }
              >
                <option value="PLATFORM">平台</option>
                <option value="ORGANIZATION">组织</option>
              </select>
            </label>
            {adminScope === 'ORGANIZATION' && (
              <label>
                组织 ID
                <input
                  value={adminOrganizationId}
                  onChange={(event) => setAdminOrganizationId(event.target.value)}
                  required
                />
              </label>
            )}
            <label>
              Subject
              <input
                value={adminSubject}
                onChange={(event) => setAdminSubject(event.target.value)}
                required
              />
            </label>
            <button
              className="button button--primary"
              disabled={
                !adminSubject.trim() ||
                (adminScope === 'ORGANIZATION' && !adminOrganizationId.trim()) ||
                grantAdministrator.isPending
              }
              onClick={() => grantAdministrator.mutate()}
            >
              授予管理员
            </button>
          </div>
          {adminAssignments.isLoading ? <p className="muted-text">加载管理员授权…</p> : null}
          {adminAssignments.isError ? <p role="alert">管理员授权加载失败，请重试。</p> : null}
          {adminAssignments.data?.length ? (
            <div className="stack-list" aria-label="管理员授权列表">
              {adminAssignments.data.map((assignment) => (
                <div className="stack-list__item" key={assignment.id}>
                  <span>
                    {assignment.subject} · {assignment.role} · v{assignment.version}
                  </span>
                  <button
                    className="button button--small button--ghost"
                    disabled={revokeAdministrator.isPending}
                    onClick={() => {
                      if (window.confirm(`确认回收 ${assignment.subject} 的管理员角色？`)) {
                        revokeAdministrator.mutate(assignment);
                      }
                    }}
                  >
                    回收角色
                  </button>
                </div>
              ))}
            </div>
          ) : adminAssignments.isFetched ? (
            <p className="muted-text">当前范围没有活动管理员。</p>
          ) : null}
        </section>
      )}
      <section className="panel">
        <label htmlFor="role-project">项目</label>
        <select
          id="role-project"
          value={selectedProjectId}
          onChange={(event) => {
            setProjectId(event.target.value);
            setDraftRoles({});
          }}
        >
          <option value="">选择项目</option>
          {(projects.data?.items || []).map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
            </option>
          ))}
        </select>
      </section>
      {projects.isError ? (
        <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />
      ) : selectedAuthorization.isLoading ? (
        <div className="panel loading-block">检查项目成员权限…</div>
      ) : !canReadMembers ? (
        <div className="panel muted-text" role="status">
          项目成员身份信息仅对项目管理员开放。
        </div>
      ) : members.isLoading ? (
        <div className="panel loading-block">加载成员中…</div>
      ) : currentRole.isError ? (
        <ErrorState error={currentRole.error} onRetry={() => void currentRole.refetch()} />
      ) : members.isError ? (
        <ErrorState error={members.error} onRetry={() => void members.refetch()} />
      ) : !members.data?.length ? (
        <EmptyState title="暂无项目成员" description="当前作用域没有可管理的成员。" />
      ) : (
        <section className="panel">
          <h2>成员授权</h2>
          <div className="stack-list" aria-label="项目成员授权列表">
            {members.data.map((member) => {
              const role = roleFor(member);
              const canManageMembers = canReadMembers;
              const editable = member.role !== 'OWNER' && canManageMembers;
              return (
                <div className="stack-list__item" key={member.subject}>
                  <div>
                    <strong>{member.subject}</strong>
                    <div className="muted-text">
                      {labelStatus(member.status)} · version {member.version}
                    </div>
                  </div>
                  {canManageMembers ? (
                    <>
                      <select
                        aria-label={`${member.subject} 的角色`}
                        disabled={!editable || roleMutation.isPending}
                        value={role}
                        onChange={(event) =>
                          setDraftRoles({ ...draftRoles, [member.subject]: event.target.value })
                        }
                      >
                        {member.role === 'OWNER' && (
                          <option value="OWNER">{labelRole('OWNER')}</option>
                        )}
                        {EDITABLE_ROLES.map((item) => (
                          <option key={item} value={item}>
                            {labelRole(item)}
                          </option>
                        ))}
                      </select>
                      <button
                        className="button button--small"
                        disabled={!editable || role === member.role || roleMutation.isPending}
                        onClick={() => roleMutation.mutate({ member, role })}
                      >
                        保存角色
                      </button>
                    </>
                  ) : (
                    <span>{labelRole(member.role)}</span>
                  )}
                  {currentRole.data?.role === 'OWNER' && member.role !== 'OWNER' ? (
                    <button
                      className="button button--small button--ghost"
                      disabled={ownerTransfer.isPending}
                      onClick={() => setPendingOwnerTransfer(member)}
                    >
                      转移所有者
                    </button>
                  ) : null}
                </div>
              );
            })}
          </div>
        </section>
      )}
      {canReadMembers ? (
        <section className="panel">
          <h2>有效权限矩阵</h2>
          {permissions.isLoading ? (
            <div className="loading-block">加载权限矩阵中…</div>
          ) : permissions.isError ? (
            <ErrorState error={permissions.error} onRetry={() => void permissions.refetch()} />
          ) : (
            <div className="content-grid">
              {(permissions.data as ProjectRolePermissions[] | undefined)?.map((item) => (
                <article className="panel" key={item.role}>
                  <h3>{labelRole(item.role)}</h3>
                  <div className="chip-row">
                    {item.permissions.map((permission) => (
                      <span className="chip" key={permission}>
                        {permission}
                      </span>
                    ))}
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      ) : null}
      <ActionConfirmModal
        open={Boolean(pendingOwnerTransfer)}
        actionLabel="转移项目所有者"
        impact={
          pendingOwnerTransfer
            ? `目标成员：${pendingOwnerTransfer.subject}。转移后对方成为项目 OWNER，你将失去 OWNER 权限；项目角色与权限会立即按新所有者更新。`
            : ''
        }
        onCancel={() => setPendingOwnerTransfer(undefined)}
        onConfirm={() => {
          if (pendingOwnerTransfer) ownerTransfer.mutate(pendingOwnerTransfer);
          setPendingOwnerTransfer(undefined);
        }}
      />
    </div>
  );
}
