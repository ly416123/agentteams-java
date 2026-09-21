# L5 身份与项目成员迁移

L5 的 Keycloak 会在重建或重新导入 realm 时生成新的 user subject。数据库中的
`project_memberships.subject` 和 `team_memberships.agent_id` 如果仍引用旧 subject，
登录成功后会得到 403。迁移以当前 Keycloak subject 为准，过程可重复执行，并且不把
token、密码或 API key 写入仓库。

## 1. 解析当前 subject

在有 L5 kubeconfig 的安全终端执行。密码通过环境变量传入，不要写进命令历史或日志：

```bash
export AGENTTEAMS_API_PASSWORD='从安全存储读取'
KC='http://192.168.122.55:30082'
TOKEN=$(curl -fsS -X POST "$KC/realms/agentteams/protocol/openid-connect/token" \
  -H 'content-type: application/x-www-form-urlencoded' \
  --data-urlencode client_id=agentteams-api \
  --data-urlencode grant_type=password \
  --data-urlencode username=quota-admin \
  --data-urlencode password="$AGENTTEAMS_API_PASSWORD" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
SUBJECT=$(TOKEN="$TOKEN" python3 -c 'import base64,json,os; p=os.environ["TOKEN"].split(".")[1]; p += "=" * (-len(p)%4); print(json.loads(base64.urlsafe_b64decode(p))["sub"])')
printf 'current subject: %s\n' "$SUBJECT"
```

## 2. 补齐当前项目成员关系

常规成员变更使用 Control Plane API。首次迁移如果项目成员关系完全缺失，必须由具备
数据库迁移权限的运维人员执行下面的幂等兼容写入，使当前 subject 获得项目 `ADMIN`；
这一步只新增或恢复当前 subject，不删除历史记录：

```bash
kubectl -n agentteams exec postgresql-0 -- psql -U agentteams -d agentteams \
  -v ON_ERROR_STOP=1 -c "
  INSERT INTO project_memberships
    (tenant_id, project_id, subject, role, status, created_at, updated_at, version)
  SELECT 'tenant-a', id, '$SUBJECT', 'ADMIN', 'ACTIVE', now(), now(), 0
    FROM projects
   WHERE tenant_id = 'tenant-a' AND name = 'project-a'
  ON CONFLICT (tenant_id, project_id, subject)
  DO UPDATE SET role = 'ADMIN', status = 'ACTIVE', updated_at = now();"
```

确认当前 subject 已可访问项目后，通过官方接口补齐 AgentScope Worker 的团队成员关系：

```bash
curl -fsS -X POST \
  "http://192.168.122.55:30080/api/v1/teams/<team-id>/members?projectId=<project-id>" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Idempotency-Key: l5-agentscope-team-member-v1' \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"<agent-id>","role":"EXECUTOR"}'
```

## 3. 盘点旧 subject

迁移完成后只读盘点历史记录。旧 subject 在确认不再使用前不要删除或禁用：

```bash
kubectl -n agentteams exec postgresql-0 -- psql -U agentteams -d agentteams -c "
  SELECT tenant_id, project_id, subject, role, status
    FROM project_memberships
   ORDER BY tenant_id, project_id, subject;"
```

删除历史成员必须先拿到 Keycloak 当前用户清单、业务负责人确认和回滚窗口。该操作不应
作为部署脚本的一部分自动执行。

## 验收

- 当前用户调用 `GET /api/v1/projects/<project-id>/members` 返回 200；
- 团队成员接口返回 AgentScope Worker 且状态为 `ACTIVE`；
- 新任务能进入 `QUEUED` 并被目标 Worker 接收；
- 数据库中的历史 subject 数量在迁移前后有记录，未发生未经确认的删除。
