# ChatGPT 计划额度单账户接入实现计划

> **面向 AI 代理的工作者：** 按项目约束在 `main` 上执行；不得创建分支或 worktree。实现过程保留工作区内所有无关改动。

**目标：** 为 L5 双 MCP 评测 Team 增加单个 ChatGPT Plus OAuth 账户的 Responses API 模型能力，且只向该 Team 的 Leader/Executor 注入短期 access token。

**架构：** Worker 继续通过 AgentScope Harness 发起 Responses API 请求，并将本地 MCP 工具作为 function tools 往返执行。OAuth 授权在开发者本机完成；L5 单实例刷新 Job 独占 refresh token 并轮换凭据，获准的评测 Worker 只读挂载 access token。Worker 凭据由 Provisioner 按规范化 Team/Project/Worker allowlist 判定，不走 Helm 全局 Worker 凭据注入。可选 HTTP CONNECT 代理只传给该 Team 的 ChatGPT Worker 与刷新 Job，代理地址不能带认证信息。

**技术栈：** Java 21、AgentScope `Model`/`ModelRegistry`、JDK HTTP Client、Fabric8 Kubernetes 客户端、Helm、Python OAuth 辅助脚本。

---

## 文件与职责

- 创建 `agent-worker/src/main/java/io/agentteams/worker/chatgpt/ChatGptPlanModel.java`：Responses API 请求、SSE 解析、函数工具转换和错误分类。
- 创建 `agent-worker/src/main/java/io/agentteams/worker/chatgpt/ChatGptPlanTokenSource.java`：按调用读取 access token 投影文件，支持 Kubernetes Secret 原子轮换。
- 修改 `agent-worker/src/main/java/io/agentteams/worker/WorkerRuntimeFactory.java`：注册并解析 `chatgpt-plan:<model>`，保持已有 Provider 解析方式。
- 修改 Worker CRD、`WorkerSpec`、`WorkerResourceFactory` 和 `WorkerCrdProvisioner`：为获准 Worker 单独挂载 token Secret；拒绝其他 Worker 引用该凭据。
- 修改 `AgentSpecWorkerTemplateProvisioner`：按规范化 Tenant/Project/Team UUID 与 Leader/Executor Worker 身份计算是否允许凭据引用。
- 修改 `deploy/helm/agentteams-java/values.yaml`、Schema 和模板：声明 OAuth Secret 名、Tenant/Project/Team UUID allowlist、可选 Team 专用代理以及受限刷新 Job 配置；不能添加全局 Worker env secret。
- 创建 OAuth 注册/PKCE 授权脚本：在本机获取并校验 token，不输出 token，通过安全临时文件创建一个完整 OAuth 会话 Secret。
- 创建单实例 Token Refresh CronJob 所需镜像/脚本、RBAC、NetworkPolicy 和固定版本部署配置；CronJob 更新 OAuth 会话 Secret，`concurrencyPolicy: Forbid`。
- 更新 `docs/development/project-constraints.md`：记录 ChatGPT 计划额度 Provider 与评测 Team 专属授权约束。
- 更新 L5 部署操作文档：OAuth 初始化、Secret 导入、刷新状态检查、撤销账户和故障处理。

## 任务 1：定义 Provider 注册与 TokenSource 接口

- [x] 阅读当前 AgentScope `Model`、`ChatResponse`、tool-call 编码和 ModelRegistry 注册 API 的依赖源码，固定适配器构造签名。
- [x] 创建 TokenSource 接口和文件投影实现；路径来自 `AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE`，每次模型请求读取最新文件内容，空文件或缺失时失败关闭。
- [x] 在 `WorkerRuntimeFactory` 注册 `chatgpt-plan:<model>`；DeepSeek/DashScope 解析行为保持原样。
- [x] 配置只允许 `chatgpt-plan:<model>` 使用 `AGENTTEAMS_CHATGPT_ACCESS_TOKEN_FILE`；缺配置时报出不含凭据的明确错误。

## 任务 2：实现 Responses API 与工具循环适配

- [x] 实现输入 `Msg`、系统/用户/assistant 内容和 ToolSchema 到 Responses API JSON 的映射，强制 `store:false` 和 `stream:true`。
- [x] 实现 SSE 消费，以 `response.completed` 作为成功边界；处理流结束、HTTP 非 2xx、畸形 JSON、重复/缺失 call id。
- [x] 把完成事件中的 function call 输出为 AgentScope `ChatResponse` 支持的标准工具调用结构，确保现有 MCP runtime 执行后续工具。
- [x] 对不支持的参数显式拒绝或忽略；不得把 prompt、请求/响应正文或 token 写入日志。
- [x] 为 401、403、429、5xx、超时和协议错误映射非敏感错误类别；禁止回退到其他 Provider。

## 任务 3：为动态 Worker 建立 Team 级凭据授权

- [x] 从 L5 Control Plane 数据核实双 MCP 评测 Team 的 Tenant UUID、Project UUID 和 Team UUID：`429327d7-0250-600e-b51c-31d853528b8f`、`ef5916eb-14d9-4e75-9e9c-041c6b5fb447`、`b7aac93d-2e8c-4774-b04b-c80f839d4915`。
- [x] 将授权策略限制为上述 UUID 与 Leader/Executor 类型；Team 名称、项目名称和显示名称不得参与匹配。
- [x] 扩展 Worker CRD secret volume 引用契约，在 `WorkerResourceFactory` 中将 access token Secret 只挂载到 allowlist Worker。
- [x] 使 `AgentSpecWorkerTemplateProvisioner` 对越权 Team/Agent 拒绝 provision，不接受请求参数自行指定 Secret 名称。
- [x] OAuth 会话 Secret 只由刷新 Job ServiceAccount 通过 Kubernetes API 读写；Worker Pod 的 volume `items` 仅投影 `access-token` key，且不挂 Kubernetes API Token。

## 任务 4：OAuth 初始化与轮换

- [x] 创建本机 OAuth 工具：动态注册客户端、启动 loopback PKCE callback、验证 state/nonce/ID token/issuer/audience/scope，禁止在 stdout/stderr 输出 token。
- [x] 创建安全 Secret 初始化命令：Secret 数据通过 Kubernetes API stdin 发送，禁止 shell history 和命令行参数出现 token。
- [x] 实现单实例刷新作业：读取 OAuth 会话 Secret、串行 refresh、验证响应，并通过一次 Kubernetes Secret 更新保存新 refresh token 与 access token；更新失败时报告错误但不输出响应体。
- [ ] 加入 readiness/状态检查，暴露最近成功时间、access token 过期时间和脱敏错误类别；为 Job 配置资源 requests/limits、并发策略和退避。

## 任务 5：L5 部署与评测切换

- [x] 固定 Worker/refresh 镜像 tag，并将含适配器的 Control Plane、Operator、Worker 和刷新镜像部署到 L5；OAuth 保持关闭，L5 默认 DeepSeek 与其他 Team 保持现状。
- [ ] 取得 L5 可访问的 OpenAI 出口后，只为双 MCP 评测 Team 配置 `chatgpt-plan:<model>`。
- [ ] 通过本机 OAuth 初始化该单账户，向 L5 写入同一个 OAuth 会话 Secret；核对 Secret RBAC 与实际 Worker volume mount，证明 Worker 容器只收到 `access-token` 文件，refresh token 不投影到容器。
- [ ] 部署后先做单个评测 Team 的真实模型请求和本地 MCP function-call smoke；确认请求使用 `store:false`/`stream:true`，响应来自 Responses API。
- [ ] 成功后重跑双 MCP 10 个差异用例，保留 PDF 字体/布局/语义/相关性/准确性/引用验收和 DeepEval 报告；报告记录 Provider 与模型 ID，不记录凭据。
- [ ] 验证 refresh token 轮换后模型调用仍成功，并尝试从非评测 Worker 创建 `chatgpt-plan` 配置，确认部署授权失败关闭。

## 当前进度

2026-10-11：L5 Helm revision 88 已完成，Control Plane 与 Operator rollout 成功；OAuth 尚未完成，未创建 OAuth Secret 或刷新 CronJob。Responses 适配器回归测试 18 项通过，Python OAuth/刷新测试 10 项通过，Helm lint 与 L5 dry-run 通过。FlClash 与实际监听 `127.0.0.1:7892` 的 BoostNet 均已启用局域网访问，L5 访问 `192.168.125.227:7892` 成功并从 OpenAI API 收到预期 HTTP 401。OAuth 初始化脚本因 300 秒无浏览器回调而超时并已清理临时 kubeconfig；Mac 当前仍锁屏，需解锁后重新启动 OAuth 授权。

## 实施顺序与交接

按任务 1 至 5 顺序执行；每个任务完成后检查差异，只触碰本计划列出的文件以及为适配现有实现所必需的新文件。任何 L5 真实授权或部署步骤都必须在用户本机已有 ChatGPT 登录会话和 L5 kubeconfig 下运行；不要求用户将 Token 或密码发送到聊天。

完成后，在 L5 保持双 MCP 评测 Team 使用 ChatGPT 计划额度；只有 10 case 与 PDF/DeepEval 验收通过后，才报告本轮完成。
