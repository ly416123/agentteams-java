import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  changeProjectMemberRole,
  listProjectMembers,
  listProjectRolePermissions,
  listProjects,
  type ProjectMember,
  type ProjectRolePermissions,
} from '../../api/projects';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';

type Notice = { kind: 'success' | 'error'; text: string } | undefined;

const EDITABLE_ROLES = ['ADMIN', 'OPERATOR', 'DEVELOPER', 'VIEWER'];

export function ManagementRolePage() {
  const queryClient = useQueryClient();
  const projects = useQuery({ queryKey: ['projects'], queryFn: () => listProjects() });
  const [projectId, setProjectId] = useState('');
  const selectedProjectId = projectId || projects.data?.items[0]?.id || '';
  const members = useQuery({
    queryKey: ['project-members', selectedProjectId],
    queryFn: () => listProjectMembers(selectedProjectId),
    enabled: Boolean(selectedProjectId),
  });
  const permissions = useQuery({
    queryKey: ['project-role-permissions', selectedProjectId],
    queryFn: () => listProjectRolePermissions(selectedProjectId),
    enabled: Boolean(selectedProjectId),
  });
  const [draftRoles, setDraftRoles] = useState<Record<string, string>>({});
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

  const roleFor = (member: ProjectMember) => draftRoles[member.subject] || member.role;

  return (
    <div className="page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">MANAGEMENT / AUTHORIZATION</p>
          <h1>角色与权限</h1>
          <p className="page-subtitle">
            查看 Project 成员的有效角色和权限。角色变更使用成员版本保护，OWNER 转移必须走独立流程。
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
      <section className="panel">
        <label htmlFor="role-project">Project</label>
        <select
          id="role-project"
          value={selectedProjectId}
          onChange={(event) => {
            setProjectId(event.target.value);
            setDraftRoles({});
          }}
        >
          <option value="">选择 Project</option>
          {(projects.data?.items || []).map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
            </option>
          ))}
        </select>
      </section>
      {projects.isError ? (
        <ErrorState error={projects.error} onRetry={() => void projects.refetch()} />
      ) : members.isLoading ? (
        <div className="panel loading-block">加载成员中…</div>
      ) : members.isError ? (
        <ErrorState error={members.error} onRetry={() => void members.refetch()} />
      ) : !members.data?.length ? (
        <EmptyState title="暂无 Project 成员" description="当前作用域没有可管理的成员。" />
      ) : (
        <section className="panel">
          <h2>成员授权</h2>
          <div className="stack-list" aria-label="Project 成员授权列表">
            {members.data.map((member) => {
              const role = roleFor(member);
              const editable = member.role !== 'OWNER';
              return (
                <div className="stack-list__item" key={member.subject}>
                  <div>
                    <strong>{member.subject}</strong>
                    <div className="muted-text">
                      {member.status} · version {member.version}
                    </div>
                  </div>
                  <select
                    aria-label={`${member.subject} 的角色`}
                    disabled={!editable || roleMutation.isPending}
                    value={role}
                    onChange={(event) =>
                      setDraftRoles({ ...draftRoles, [member.subject]: event.target.value })
                    }
                  >
                    {member.role === 'OWNER' && <option value="OWNER">OWNER</option>}
                    {EDITABLE_ROLES.map((item) => (
                      <option key={item} value={item}>
                        {item}
                      </option>
                    ))}
                  </select>
                  <button
                    className="button button--small"
                    disabled={!editable || role === member.role || roleMutation.isPending}
                    onClick={() => roleMutation.mutate({ member, role })}
                  >
                    保存 {member.subject} 的角色
                  </button>
                </div>
              );
            })}
          </div>
        </section>
      )}
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
                <h3>{item.role}</h3>
                <div className="stack-list">
                  {item.permissions.map((permission) => (
                    <span className="muted-text" key={permission}>
                      {permission}
                    </span>
                  ))}
                </div>
              </article>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
