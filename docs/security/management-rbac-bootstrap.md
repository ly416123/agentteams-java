# 管理角色首次引导

`V121__management_role_assignments.sql` 默认不授予任何平台或组织管理角色。首次管理员可由部署时一次性配置 `agentteams.management.bootstrap-platform-admin-subject` 引导：应用启动时仅当数据库中尚无活动平台管理员才授予该主体，并记录 `PLATFORM_ADMIN_BOOTSTRAPPED` 审计事件。该配置应从部署密钥源注入，并在首次成功启动后移除；重复启动不会覆盖或追加管理员。普通用户 API 不开放首次自助升级。

如不能使用应用启动引导，可由受控数据库运维账号按工单操作：

在变更单批准后，以数据库运维身份执行以下语句，将 `:subject` 替换为已通过 OIDC 验证的稳定 subject：

```sql
INSERT INTO management_role_assignments
    (id, subject, scope_type, organization_id, role, status, version,
     created_by, updated_by, created_at, updated_at)
VALUES
    (gen_random_uuid(), :subject, 'PLATFORM', NULL, 'PLATFORM_ADMIN', 'ACTIVE', 0,
     'bootstrap', 'bootstrap', now(), now())
ON CONFLICT DO NOTHING;
```

查询确认：

```sql
SELECT subject, role, status, created_by, created_at
FROM management_role_assignments
WHERE scope_type = 'PLATFORM' AND status = 'ACTIVE';
```

## 管理员名单导入与撤权回滚

首次上线前由身份系统负责人提供 UTF-8 CSV 名单，至少包含 `subject,scope_type,organization_id,role`。上线负责人须逐条将 subject 与 IdP 中的稳定主体核对，并确认组织 ID；CSV 不保存姓名、邮箱、访问令牌或凭据。名单经双人复核后，通过启动配置引导一个平台管理员，再由平台管理员使用授权 API 导入组织管理员。不要把 CSV 或 subject 名单提交到代码仓库。

导入后核验有效授权：

```sql
SELECT subject, scope_type, organization_id, role, status, version
FROM management_role_assignments
WHERE status = 'ACTIVE'
ORDER BY scope_type, organization_id, subject;
```

发现误授时，优先用管理 API 携带当前 `expectedVersion` 撤权，以保留应用审计记录。紧急情况下可由数据库运维账号按工单停用单条授权；该操作须同步写入组织审计系统，记录工单号、操作者、受影响 subject 的哈希和时间：

```sql
UPDATE management_role_assignments
SET status = 'DISABLED', version = version + 1,
    updated_by = :operator, updated_at = now()
WHERE subject = :subject
  AND scope_type = :scope_type
  AND organization_id IS NOT DISTINCT FROM :organization_id
  AND role = :role
  AND status = 'ACTIVE';
```

回滚只撤销本次工单授予的角色，不回滚 `V121` 表结构，也不恢复已撤销的角色。变更前记录新建授权的 ID 和版本；回滚时逐项停用，并复查活动平台管理员数量和组织授权清单。至少保留一名经确认的平台管理员，避免锁死管理入口。若必须回退应用版本，先停用本次新增授权，再按发布回滚流程部署旧版本；旧版本不得继续对外开放未受控的管理接口。

授予组织管理员通过已认证的平台管理员调用 `POST /api/v1/management/authorizations/organizations/{organizationId}/admins`。组织/平台管理员回收分别调用 `/organizations/{organizationId}/admins/{subject}/revoke` 与 `/platform/admins/{subject}/revoke`，请求体需携带 `expectedVersion`，服务端记录主体哈希审计并拒绝平台管理员撤销自身；数据库运维仍可按受控工单更新状态、版本和 actor/time 字段。
