# 当前开发阶段项目约束

更新时间：2026-10-12

本文件是当前开发阶段的工程约束。若历史文档与本文件冲突，以本文件和最新实现计划为准。

## 开发阶段的兼容策略

1. 当前处于开发阶段，**不以旧逻辑、旧 API、旧运行时或旧数据形态的兼容为约束**。当前仓库
   只保留 AgentScope 执行链路，不保留旧运行时的迁移适配器、兼容路由、隐式回退或旧部署脚本。
2. 新链路优先保证职责单一、边界清晰和状态可观测；需要破坏旧调用方式时，直接迁移代码、
   测试和部署配置，不新增兼容分支、隐式回退或“双轨执行”。
3. 生产组合根只能装配当前主链路。`FakeRuntime` 等测试替身仅用于单元测试隔离，不得被
   部署配置、管理后台或 L5 验收路径间接启用，也不得作为旧运行时的兼容入口。
4. 历史设计文档中的“兼容”“回退”“旧路径”描述不属于当前实现承诺；历史文档仅作背景留档，
   不得继续指导当前接口、部署或测试契约。

## 项目授权与配置 Scope

1. Project UUID 字符串是项目 Scope 的唯一规范值。持久化的资源 Scope、AgentSpec、Team 修订、
   Config Snapshot 清单、Worker 配置和授权比较必须使用同一个 Project UUID；不得把项目名称写入
   这些内部字段。
2. OIDC Token 或用户请求中的项目名称只能作为外部标识。进入资源查询、配置生成或授权比较前，必须
   通过 `ProjectScopeResolver` 在当前 Tenant 内解析到唯一且有效的项目，并验证主体具有该项目的
   有效成员关系。不得直接把 Token 中的项目名称复制到配置清单，也不得依赖字符串比较自动兼容名称和 UUID。
3. 配置 API 按规范 Scope 做严格相等比较。调用方必须先把 Token Scope、请求参数和配置清单中的
   `scope.project` 规范化为同一 UUID；只要一侧是项目名称、另一侧是 UUID，就会按越权拒绝处理。
   Console 路由可以继续展示项目名称，但发往 Control Plane 的授权与配置数据必须使用规范 UUID。
4. 新增或修改 Scope 入口时，必须覆盖“Token 使用项目名称、配置清单使用 UUID”和“请求使用 UUID、
   Token 使用项目名称”两种回归场景，确认同一项目解析到同一 UUID；同时验证跨 Tenant、跨项目、
   无有效成员关系仍然拒绝。授权失败不得通过放宽字符串比较或跳过成员校验来修复。
5. 项目 Scope 的历史规范化设计见
   [Project Scope 规范化计划](../superpowers/plans/2026-09-03-project-scope-canonicalization.md)。

## 开发分支约束

1. 本项目所有新需求、规格/计划文档、实现、测试和修复必须直接在 `main` 分支开展；禁止为需求创建功能分支、临时集成分支或 Git worktree，也不得把其他分支或工作树作为事实上的开发主线。
2. 开始需求前先确认当前分支为 `main`，并将其快进同步到 `origin/main`。如果当前不在 `main`，或本地 `main` 无法安全同步，先停止修改并处理分支状态；不得在其他分支继续开发后再择机回合。
3. 每项需求的规格、实现和验证结果都应在 `main` 上连续可见；按需求范围提交，验证通过后同步 `origin/main`。不得把未完成或未经验证的需求留在独立分支，亦不得用整支合并的方式补救长期分叉。
4. 已存在的历史分支仅用于只读审查和差异盘点。确需从历史分支取回代码时，先确认具体缺失功能及其与当前架构的兼容性，再将经过审查的最小变更直接移植到 `main` 并回归；不得整体合并历史分支。

## 运行时与模型

1. Worker 的唯一执行路径为 **AgentScope Harness**。模型 Provider 和模型标识必须通过
   Worker 的模型配置/注册表注入；L5 当前默认使用 `deepseek:deepseek-chat`，但不得把
   DeepSeek、DashScope 或任何单一模型 Provider 固化为架构边界。模型标识采用
   `provider:model` 注册表键，后续替换模型只需新增/选择 Provider 适配器并修改配置。
2. 模型凭据只允许通过 Kubernetes Secret 注入 Worker，代码、Helm values、日志和测试输出
   不得出现密钥明文。模型调用必须从 Worker 发起，Manager 不代理模型请求；替换模型只应
   改变模型配置和 Provider 适配，不得复制 Team、调度或任务状态机。
3. ChatGPT 计划额度 OAuth Provider (`chatgpt-plan:<model>`) 只用于显式授权的 L5 双 MCP 评测 Team。
   Control Plane 必须按 Tenant、Project UUID、Team UUID 拒绝其他 Team 的 Worker provisioning；
   不得将 OAuth 凭据配置为全局 Worker 环境变量。Worker Secret volume 只投影 access token 键，
   refresh token 仅由单实例串行刷新作业读取与轮换。Responses 请求必须使用 OAuth Bearer Token、
   `store=false` 与 `stream=true`，模型/MCP 失败不得回退到 DeepSeek。详细设计见
   [ChatGPT 单账户接入设计](../superpowers/specs/2026-10-11-chatgpt-plan-single-account-design.md)。
   可选 HTTP CONNECT 代理也必须只注入通过相同 Scope allowlist 的 ChatGPT Worker 与刷新作业；
   代理地址不得携带用户名、密码或 Token，默认保持直连。
4. AgentScope 任务必须由 Control Plane 调度，Worker 只消费已分配的 Attempt，不在
   Manager 或 Console 内实现第二套执行状态机。
5. L5 真实验收当前以 `api.deepseek.com:443` 作为默认模型出口；更换模型时，验收环境只需
   为对应 Provider 配置受控 HTTPS 出口。不得通过 Manager 代理请求来掩盖 Worker 的模型
   出口不可用。

## Conversation、Team 与任务分配

1. 管理端 Conversation 的生产路径统一为：
   `Conversation -> Control Plane Task -> Team Scheduler -> Leader/Worker -> Attempt -> Artifact`。
2. Conversation 不得直接调用模型 Provider 或 Worker HTTP 接口。Conversation 只负责提交
   Task、展示任务事件，并通过 Control Plane 查询任务、Attempt 和成果物。
3. Team 必须明确一个 `LEADER` 和至少一个 `EXECUTOR`；意图识别、任务分解和分配由
   Leader 执行，具体工作由一个或多个 Executor 完成。Manager 不自行模拟分配过程。
5. Team 编排必须遵循 `PLAN -> EXECUTE -> AGGREGATE` 三阶段：主任务首轮只能分配给
   `LEADER`；Leader 必须返回恰好两个可独立执行的子任务计划；Control Plane 负责校验计划、
   创建子任务并将子任务只分配给 `EXECUTOR`。全部子任务成功后，主任务重新入队并回到
   `LEADER` 聚合结果，Leader 最终返回成果物。
6. Task 创建必须携带稳定的 UUID Team 标识和 `sourceConversationId`，并使用幂等键；创建后
   必须显式执行 `queue`，不能停留在 DRAFT。
7. 测试成功标准不是“Conversation 已入队”，而是能观察到 Task/Attempt 状态推进、真实模型调用、
   Leader/Worker 分配以及至少一个可下载且内容为 UTF-8 的 Artifact。

## SDK 与构建

1. 当前阶段只维护 Java SDK；TypeScript SDK 暂不维护，不要求同步接口、测试或发布产物。
2. L5 和发布验收镜像统一按 `linux/amd64` 构建、标记和导入；镜像 tag 必须显式固定，禁止
   用不可追溯的 `latest` 覆盖验收环境。
3. 新增模型、调度和 Conversation 行为必须先写失败测试，再实现，再执行模块测试和 L5
   真实流程回归。

## L5 资源边界

1. L5 是单节点约 7.2 GiB 内存的开发环境，不承担高可用拓扑；Control Plane、Gateway 和
   Operator 使用单副本，动态 Worker 只保留当前验收 Team 所需的 Leader/Executor 实例。
2. 每个动态 Worker 必须由 Operator 声明 CPU/内存 requests/limits，并设置受 cgroup 约束的
   JVM 堆比例；禁止创建没有资源边界的 Worker Deployment。
3. PostgreSQL、NATS、MinIO、Keycloak 和测试 Mock 同样必须有资源边界。Keycloak 的启动窗口
   必须覆盖首次 Quarkus augmentation，不能把慢启动误判为服务失效。
4. 发生节点级 OOM 时，先收敛副本和暂停非主链路工作负载，再滚动更新资源配置；禁止把整机重启
   作为默认修复手段。重启前必须确认数据库、NATS 和任务状态已具备恢复证据。

## MCP 研究来源证据

1. 用于事实评测或来源审计的搜索 MCP，必须将本次 Task、Attempt、Lease 和工具调用标识以传输上下文
   传给来源采集端；不得把这些标识放入模型可见参数或提示词。Worker 只可向代码明确允许的内部 MCP
   Endpoint 发送这些标识，禁止向任意注册的第三方 Endpoint 转发。
2. MCP 服务只能使用 Secret 中的 `AGENTTEAMS_TASK_MCP_TOKEN` 调用 Control Plane 的任务来源证据接口。
   Control Plane 必须校验活跃 Attempt/Lease，并保存有界的标题、规范 URL、URL SHA-256 和摘要；原始查询、
   Token、密码、签名 URL 和整段工具响应不得写入来源证据。
3. 搜索服务来源采集失败时不得丢弃搜索结果或伪报采集成功；必须留下不含来源正文和凭据的失败诊断。
   评测的引用匹配与事实判断必须基于本次运行采集到的来源证据，不能从最终模型回答或 PDF 的引用反向
   推导检索证据。
4. 新增或修改研究类 MCP 时，必须同时验证任务上下文只发往精确允许的内部 Endpoint、无上下文请求不会
   伪造采集、采集 API 拒绝非活跃 Lease，以及部署配置仅开放 MCP 到 Control Plane/所需数据存储的网络路径。

## 验收边界

1. L5 验收使用真实 DeepSeek API 调用；Fake runtime 只能用于单元测试，不能作为真实流程通过
   的依据。
2. 失败必须按实际阶段定位：Task 创建/入队、Team Deployment、配置应用、Worker 启动、模型
   调用、Artifact 上传分别记录证据，不能用“事件流已连接”替代任务成功。
3. 所有回归结果必须保留 Task、Attempt、Artifact 的非敏感标识和状态；禁止记录 Token、Secret
   和带签名的对象存储 URL。
