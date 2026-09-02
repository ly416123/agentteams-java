# 管理端阶段验证报告

**日期：** 2026-09-03

**分支：** `main`

**验证范围：** 管理端身份、组织、角色、目录、Task、Artifact、Usage、预算、告警和审计入口，以及 Work Pod、记忆隔离、Secret 引用和 Java SDK 冻结约束。

L5 主机、`ly` 权限边界和 gVisor 故障复盘见：[L5 Linux/KVM 环境与 gVisor 故障基线](./l5-linux-kvm-environment-baseline.md)。

## 已通过

- Console 格式检查、Lint、单元测试和生产构建通过。
- Console 当前测试结果：29 个测试文件、123 个测试通过；生产构建通过。
- Conversation 页面本地回归已补齐：执行中追加消息进入有序队列，后续请求使用前一条响应返回的最新会话版本；发送失败时恢复当前及未发送草稿，不静默丢失补充信息。
- Agent Gateway 全量单元回归通过：85 个测试通过，0 failure、0 error、0 skipped；心跳刷新不再递增 Agent 生命周期版本，避免正常多副本心跳造成错误的乐观锁冲突。
- Console E2E：19 个用例通过；包括未登录入口、登录引导、Alice 真实 OIDC 登录进入 Project，以及 Alice/Reader/Tenant-B 独立浏览器会话的 Project 隔离。Alice 可在整页导航后访问 Memory/Sandbox 页面；Reader 访问同一项目资源页面返回“无权访问”；Alice 可在告警页面看到失败投递并执行“立即重试”；Quota Admin 可从 Skill 页面完成真实 MinIO 预签名直传和 package complete，并可在 MCP 页面完成 credentialRef 脱敏、不可达端点 fail-closed、Discovery 状态、编辑和删除；Organization/Tenant 页面已通过真实 OIDC 完成创建、幂等状态变更和版本保护操作；角色页面已通过真实 OIDC 展示当前 Project 的有效权限矩阵；Project 管理页面已通过真实 OIDC 完成当前 Tenant 内 Project 创建；Team 页面已通过真实 OIDC 完成当前 Project 内创建、详情加载和发布不自动部署 Worker 的边界展示，并验证伪造跨 Project UUID 返回“无权访问”；Template 页面已通过真实 OIDC 完成 Template → Revision → Publish → 显式实例化 Worker，并可进入 Worker 详情查看操作记录；AgentSpec 页面已通过真实 OIDC 独立完成创建、发布和停用生命周期；外部用户生命周期页面已通过真实 OIDC 完成初始化、更新、Membership 查询和停用；Integration Credential 页面已通过真实 OIDC 完成 Credential Ref 登记、轮换、撤销、版本保护和危险操作确认；Conversation 页面已通过真实 OIDC 完成 Team 选择、消息发送、页面 reload 后历史/事件恢复、取消确认和取消后发送禁用；Task 页面已通过真实 OIDC 使用 `team-a` scope 完成 NORMAL Task 创建、执行/恢复空态查看、取消确认和刷新后状态恢复。凭据轮换和撤销的成功提示均在列表刷新完成后展示，后续操作使用最新版本号，避免连续操作触发错误的乐观锁冲突。
- Worker 真实供给验收已通过：显式实例化写入逻辑 Worker 后，Control Plane 创建对应 `agentteams.io/v1alpha1 Worker` CR，Operator 创建 Deployment 和 Worker Pod，数据库 Worker 状态达到 `READY`；注册、激活、用户初始化、Team 创建或 Template 发布阶段均不会隐式创建 Pod。
- Worker 多副本运行时验收已通过：受控将 Worker 扩容到 2 副本、删除一个 Pod 后 Deployment 自动补齐，随后注入 lease 过期并确认稳定 WorkerSpec、2 个 Ready 副本和 `ROLLED_BACK`；同时修正 Gateway 心跳不递增 Agent 生命周期版本，避免正常心跳触发错误的 409 版本冲突。
- 受控 Ubuntu/K3s L5 Sandbox 验收已通过：gVisor 与 Kata 两个 TaskSandbox 均达到 `READY`，Job/Pod 的 `runtimeClassName` 分别为 `gvisor`、`kata-qemu`，并取得 guest kernel 与宿主机 kernel 证据。此前失败原因是 Operator 旧镜像生成的 runner Job 对预加载的 `:latest` 镜像使用默认 `Always` 拉取策略，匿名 GHCR 拉取返回 403；已将 Job 模板固定为 `imagePullPolicy: IfNotPresent`，重新部署 Operator 后复验通过。
- 真实 OIDC 浏览器验收使用 `api.agentteams.localhost:30080` 作为 Ingress 入口，已确认 Alice/Reader/Tenant-B 的登录、跨用户和跨租户 Project 可见性边界；测试凭据均来自开发 Realm。
- Kind OIDC API smoke：真实 Keycloak JWT 验签通过；无 Token/无效 Token 返回 401，Alice 有权限创建 Task 返回 201，Reader 缺少权限返回 403，Tenant-B 跨作用域返回 403。
- Control Plane 全量回归通过：809 个测试通过，0 failure、0 error、0 skipped；统一企业执行平面门禁已验证 Flyway 从空库升级到 v86，`FoundationRepositoryIT` 通过。
- Maven `integration-tests` profile 全量回归通过：10 个 Failsafe 测试通过，0 failure、0 error、0 skipped，包含真实 PostgreSQL/NATS/MinIO Task Push、Gateway replay、配置广播和 Team scheduling 链路。
- `git diff --check` 通过。
- `sdk/java` 无变更。
- 管理端外部用户 Provisioning 生命周期已补齐：初始化、更新、停用和 Membership 查询均有管理 API 与 `/settings/identity` 页面入口；写操作要求 `Idempotency-Key`，并按 `integrationId + externalOrganizationId + externalUserId` 精确定位，成功操作记录脱敏审计。Repository PostgreSQL 集成测试验证重复初始化不重复创建内部用户、Membership 只返回当前组织、更新和停用状态生效；Controller/Console 回归及真实 OIDC 浏览器用例通过。
- 管理端新增 Usage、预算、告警和审计页面均只调用管理 API，不直接访问数据库、Kubernetes、消息系统、对象存储或 Secret Manager。
- Memory 与 Sandbox 页面只展示治理/运维元数据，不展示记忆内容、workspace 内容或 Secret；Memory 治理操作要求原因并通过管理 API 使用幂等键。
- OIDC 用户状态仅保存于当前 tab 的 `sessionStorage`，不写入 `localStorage`；整页导航不会丢失已认证会话，同时不形成长期持久化凭据。
- Memory/Sandbox HTTP 路由已分别纳入 `memory:read`、`memory:govern`、`sandbox:read` 权限；执行上下文解析已要求组织、Tenant 和 active Project membership，Reader/Tenant-B 无项目成员关系时不能通过直达 API 绕过 Console 项目列表。
- Memory 管理列表已在数据库查询边界按当前 Project 收敛，并在接口层继续校验 Project/Team；跨 Project 治理被拒绝。Task 执行详情在任务越权时返回 FORBIDDEN，且不会读取 Attempt/Assignment/Lease。
- Artifact 页面已支持项目级保留策略查看和更新，使用现有 `artifact:write` 权限、`expectedVersion` 乐观锁和脱敏审计；策略只作用于后端清理，不暴露产物内容。
- 页面不接收或展示 Secret 明文；外部凭据只使用 `credentialRef` 和脱敏状态。
- Usage 后端已支持 Project 作用域内的 Task/Provider/Model 筛选，筛选条件在固定 Tenant/Project/Team 条件之后拼接；Console 已提供筛选和 CSV 导出，导出复用相同作用域并通过后端生成。
- Usage 分组查询已支持受限的 `offset/limit` 分页，后端通过稳定排序和多取一条返回 `nextOffset`，Console 已提供上一页/下一页并保留当前筛选条件。
- Usage 分组查询已支持 `Organization/Tenant/Project/Team` 维度；组织维度通过审计记录持久化的 `organization_id` 聚合，并对历史记录执行映射回填。当前请求仍受认证主体的 Tenant/Project/Team scope 限制，不提供无权限的跨租户或跨组织汇总。
- Usage 分组查询已支持 User 维度；调用审计通过 V78 持久化 `actor_subject`，由任务的 actor 直接写入或历史回填，不从 Worker、Task ID 或 Team ID 推断用户。
- 预算页面已接入现有预算写入 API，可修改软/硬阈值并携带当前 `expectedVersion`；前端回归覆盖写入参数。
- 审计页面已支持操作者、动作、资源类型、资源 ID 筛选，并通过 `before` 时间游标提供前后页导航；查询继续由后端按当前 Project 作用域收敛并脱敏属性。
- 告警规则已按 Tenant + Project 持久化，Console 可读取全局默认规则与项目覆盖规则并写入项目规则；写入携带 `expectedVersion`，并记录脱敏审计事件。告警事件已展示投递状态、尝试次数、失败原因和下一次重试时间，后台调度器负责持久化重试与指数退避。
- 失败告警已提供 Console“立即重试”操作；服务端通过 V76 持久化 `Idempotency-Key`，重复请求复用原结果，并记录 `DASHBOARD_ALERT_RETRY_REQUESTED` 审计事件。
- Kind 受控失败夹具已完成真实浏览器验收：接收器临时返回失败时，告警页面展示 `FAILED`、失败原因和重试入口；使用开发 OIDC Token 查询受保护事件 API，点击“立即重试”后恢复成功模式，事件最终恢复为 `SENT`。验收结束后已恢复接收器成功模式。
- Usage CSV 导出已具备独立的 `usage:export` 路由权限映射，未改变查询作用域或导出脱敏边界。
- Team 管理页已补齐成员添加/移除和调度策略编辑；成员写操作使用统一幂等请求，策略保存携带当前版本号，前端回归覆盖请求参数。
- Organization/Tenant 管理切片已完成：Organization 与 Tenant 创建通过持久化 `Idempotency-Key` 做请求重放保护；状态暂停/恢复通过 `expectedVersion` 乐观锁，后端校验组织作用域与管理权限，Console 已提供创建、状态操作和结果刷新。真实 Kind API 重放同一创建请求返回相同 Organization ID，真实 OIDC 浏览器已完成 Organization 与 Tenant 的创建、暂停和恢复。
- Project 角色/权限管理切片已完成：有效权限矩阵由后端 `ResourceAuthorizationMatrix` 提供，角色变更要求 Project membership 管理权限、`Idempotency-Key` 和 `expectedMembershipVersion`；V79 按 Tenant + Project 持久化幂等记录，重放同一请求不重复变更并保留脱敏角色变更审计。真实 OIDC 浏览器已完成角色页面的 Project 作用域矩阵展示。
- Project 管理切片已完成：管理端新增 `/settings/projects`，提供当前 Tenant 作用域的 Project 列表和创建入口；创建沿用后端 Project 创建幂等协议，浏览器通过真实 OIDC 创建后可在列表中看到 Project，并明确提示不会自动部署 Worker Pod。
- Team 管理页已补齐 Revision 草稿创建、审核、发布、显式 Deployment、失败重试和回滚草稿；发布与回滚使用 Revision 版本保护，Deployment 要求成员集合与已发布 Revision 一致。
- Team API 的所有 Console 请求现在显式携带路由 Project UUID；后端同时校验 OIDC scope 外部名和稳定 Project UUID，UUID 必须属于当前 Tenant、调用者 active membership，授权的 Project 路由会切换当前请求 scope，跨 Project 请求 fail-closed。真实 OIDC Team 浏览器验收已通过。
- Template、AgentSpec、Worker 页面和 API 请求现在显式携带路由 Project UUID；AgentSpec 默认状态修正为 `RUNNING`。显式实例化供给器默认保持 Noop，只有启用命名空间级 Worker CR RBAC 后才创建 CR，避免 API-only 部署隐式获得 Kubernetes 写权限。
- AgentSpec 独立页面已通过真实 OIDC 完成 `DRAFT → PUBLISHED → DISABLED` 验收；Worker 详情页已展示操作状态、失败分类、Operator/Gateway 观测和观测匹配结果，便于定位发布故障。
- Skill 管理页已补齐已审核且制品上传完成后的版本发布/停用入口；未完成制品或未通过审核时发布按钮保持禁用，发布前仍由后端复核制品状态。
- Skill 管理页已接入制品文件选择、浏览器端 SHA-256、后端预签名 URL 直传和 package complete；控制面不接收制品内容，前端回归已覆盖上传顺序与校验摘要。
- Skill 制品真实对象存储 API 验收已通过：Kind MinIO 完成预签名、直传、服务端大小/SHA-256 校验和 `packageUploadStatus=COMPLETED`；控制面支持内部存储 endpoint、浏览器预签名 endpoint 与显式 region 分离，Kind 验收通过 `127.0.0.1:19000` 受控端口转发访问。
- Skill 页面真实浏览器验收已通过：Quota Admin 使用开发 OIDC 登录，在页面创建 Skill/版本，选择 `.tar.gz` 文件，经浏览器 SHA-256、预签名 PUT 和 package complete 后看到 `package COMPLETED`；验收使用受控 MinIO 端口转发，未接触生产凭据。
- MCP 管理页已补齐删除确认；删除通过管理 API 执行，不展示或回填凭据明文，避免脱敏响应覆盖已有 `credentialRef`。
- MCP 管理页已补齐编辑、真实连接测试和 Discovery 摘要；编辑携带 `expectedVersion`，连接测试仅返回凭据安全的状态、延迟、分类和摘要，后端 API 回归已覆盖。
- MCP 管理页真实 OIDC 浏览器验收已通过：页面提交合法 `STREAMABLE_HTTP`，仅展示“已配置（仅引用）”，不可达开发端点返回 `UNHEALTHY/UNAVAILABLE`，未因无 Secret 回退放行；Discovery、编辑和删除均通过。
- 模型管理页已展示当前作用域价格目录的 Provider/Model、输入输出单价、生效时间、生命周期和价格版本；价格目录保持只读展示，不绕过同步权威链路写入。
- 模型管理页已补齐 Provider 的专用启用/停用和删除确认；请求只提交状态或资源 ID，不回传脱敏 Provider 对象，避免覆盖真实凭据引用。
- 模型管理页已展示 Provider 下属 Model，并补齐 Model 专用启用/停用和删除确认；Model 生命周期操作按所属 Provider 刷新列表，不改变凭据引用。
- 任务可靠性增量已通过本地与 L5 回归：租约恢复状态写入 `task_recovery_states`，默认最多自动恢复 3 次并按 1/2/4 秒退避，超限进入 `FAILED/RECOVERY_REQUIRED`；任务详情提供恢复状态查询和展示，未发生恢复的空响应也能正确显示空态。L5 最新 Control Plane/Console Deployment 均为 Ready，数据库已处于 v86。
- L5 Console 直连 NodePort 的对话入口已修复并复验：Console Nginx 将 `/api/v1/conversations`、`/api/v1/manager` 代理到 Manager，将其他 `/api/` 代理到 Control Plane；Manager NetworkPolicy 同时允许同命名空间的 Console Pod 访问 8080。修复前该路径因策略拒绝返回 502，修复后未认证请求正确返回 401，页面已能正常加载 Team 列表；未改变 OIDC 或业务授权边界。
- 对话稳定资源 ID 授权已打通并复验：当 Console 使用稳定 Project/Team UUID 而 OIDC scope 使用外部名称时，Manager 将原始 Bearer Token 透传给 Control Plane 的 Team 授权接口；Control Plane 2xx 放行、4xx fail-closed 为 403、依赖异常返回 503。L5 真实开发 OIDC Alice 已进入稳定 UUID 对话页并发送测试消息，页面收到 `CONVERSATION_MOCK_DELTA`；未使用生产凭据。
- 本轮真实开发 OIDC 浏览器验收补齐了内部用户创建、停用和按版本重新激活，以及 Artifact 当前 Project 元数据/保留策略页面访问；Artifact 页面不提供绕过授权的下载入口。
- 对话页面加载态已收敛：会话详情尚未返回时，发送和取消按钮保持禁用，避免用户点击后因缺少会话版本而无效返回；本地单元测试和 L5 真实 OIDC 回归均已覆盖。

## 当前受控边界

这里的“阻塞”是完整浏览器验收的证据阻塞，不是管理端功能开发阻塞。开发阶段可以、也应当使用本地 Kind 中的开发专用 Keycloak 用户和数据库夹具；不需要生产账号、生产 Token 或生产 Secret。

本次已使用仓库声明的开发专用 Keycloak 用户 `alice/alice-dev`、
`reader/reader-dev`、`tenant-b-user/tenant-b-dev` 和具备管理权限的 `quota-admin/quota-admin-dev` 设置验收环境，并使用修正后的
浏览器可访问 issuer 重新部署 Kind OIDC 配置。未使用生产账号、生产 Token 或生产
Secret。生产模型、MCP、Webhook 或 Secret Manager 的 L6 连通性仍需要相应的受控凭据；
本地 OIDC 验收本身不需要生产凭据。

其中，Keycloak realm 中的开发用户负责签发可验签的 OIDC JWT；Control Plane 数据库中的本地夹具负责组织、Tenant、Project、Team、成员和角色归属。两者不能互相替代：仅创建数据库用户无法完成 OIDC 登录，单独获取 OIDC Token 也不能凭空获得应用资源权限。现有 Kind 脚本已经按此边界使用 `alice/alice-dev`、`reader/reader-dev` 和 `tenant-b-user/tenant-b-dev`，并在取得真实 JWT subject 后建立 Project membership。

Kind OIDC 已统一为浏览器可访问的 `127.0.0.1:18082` issuer，JWK 读取仍走集群内部
Service；浏览器通过 `api.agentteams.localhost` 的 Ingress 访问 Console 和 Control Plane。
开发验收使用本地控制面镜像和幂等 Project/membership 夹具；不改变生产部署策略。

## 仍保留的受控边界

- Memory、Sandbox 页面已完成结构化展示和资源页面级浏览器隔离验收；Kind 已使用两名真实开发 OIDC subject 和临时 metadata-only fixtures 完成 USER_PRIVATE、PROJECT_SHARED、TEAM_SHARED、ORGANIZATION_SHARED、跨 Project、跨 Tenant 的逐项资源数据不串验收；受控 Ubuntu/K3s 的 gVisor/Kata L5 运行时验收也已通过。
- 当前 Kind L5 入口已执行到前置检查，并因集群不存在 `RuntimeClass gvisor`（同时未提供 `kata-qemu`）安全停止，未创建 Sandbox 夹具且清理成功；这证明当前环境不具备 L5 运行时，不将 Kind 默认容器结果冒充 gVisor/Kata 验收。
- MCP 管理端 Discovery、编辑、连接测试和完整管理页面验收已完成；外部 MCP L6 联通验收保留至项目最终阶段，当前不纳入执行计划，须用户明确说“启动 L6 验收”后再单独安排。
- Java SDK 解冻评审。只有能力矩阵全部完成或明确标记为不适用后，才允许单独创建 SDK 更新计划。

## 可复现命令

```text
cd console
npm run format:check
npm run lint
npm test
npm run build
npm run e2e

cd ..
mvn -q -pl control-plane -am test
AGENTTEAMS_E2E_QUOTA_ADMIN_USERNAME=... AGENTTEAMS_E2E_QUOTA_ADMIN_PASSWORD=... \\
  scripts/run-kind-worker-template-acceptance.sh
AGENTTEAMS_E2E_USERNAME=... AGENTTEAMS_E2E_PASSWORD=... \\
  scripts/run-kind-worker-operation-recovery-acceptance.sh
AGENTTEAMS_E2E_USERNAME=... AGENTTEAMS_E2E_PASSWORD=... AGENTTEAMS_WORKER_REPLICAS=2 \\
  scripts/run-kind-worker-operation-recovery-acceptance.sh
git diff --check
```

真实 OIDC 浏览器验收使用仓库声明的开发用户环境变量和
`AGENTTEAMS_E2E_BASE_URL=http://api.agentteams.localhost:30080`；密码不得写入仓库、命令历史或报告。
