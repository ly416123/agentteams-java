# 企业级 Agent 执行平面架构设计

**状态：** 已确认的架构基线

**适用范围：** 10 万用户、1 万企业的 SaaS、企业网络版和私有化部署。

## 1. 目标

项目采用统一控制面、可部署数据面和可插拔连接面的架构，满足以下要求：

- 企业、租户、项目、Team 的权限和资源边界可独立表达；
- 公共 MCP、企业私有 MCP 和客户内网 MCP 使用同一套业务抽象；
- 平台 Skill、企业 Skill 和项目/Team Skill 可以设置、审核、发布、绑定和撤销；
- 用户私人记忆、企业共享记忆、项目/Team 记忆和行为审计数据分层隔离；
- 用户生成代码、第三方插件、浏览器和高风险工具不运行在长驻 Worker 或 Control Plane 中；
- Sandbox Provider 可以在 Kubernetes、gVisor、Kata、MicroVM 和客户专属环境之间替换；
- 普通企业使用共享资源，高安全企业可以使用客户 VPC、专属节点池或完整私有化环境；
- 任务、Sandbox、MCP 调用和模型调用可以统一进行配额、Token、成本和审计归因。

## 2. 部署基线

平台默认提供 SaaS Control Plane。企业网络版在客户 VPC 或内网部署出站连接的 Connector；私有化版将 Control Plane、Execution Plane 和 MCP Connection 一并部署到客户环境。

```text
客户端 / Console / SDK
          |
      API Gateway
          |
    SaaS Control Plane
  身份、租户、权限、权益、任务、调度、审计
          |
     Outbox + NATS
          |
   Execution Scheduler
          |
    Sandbox Broker
       /       \
 Kubernetes    客户 VPC / 私有集群
 gVisor/Kata       Connector
          |
       MCP Gateway
      /     |      \
  公共 MCP  公网 API  企业内网 MCP
```

Control Plane 只保存连接元数据、策略和审计信息，不保存客户 Secret 明文，也不依赖客户内网的入站访问。

## 3. 资源层级

首期按「一个 Organization 可以拥有多个 Tenant」建模，即使控制台首期只默认创建一个 Tenant，也保留独立的标识和权限边界。

```text
Organization 企业
  └── Tenant 租户 / 环境
       └── Project 项目
            └── Team 执行团队
                 └── Task / Attempt / Sandbox
```

请求和任务上下文统一携带 `organizationId`、`tenantId`、`projectId`、`teamId` 和 `subjectId`。旧的字符串 `tenant_id` 作为兼容映射保留，不能继续作为新的安全边界。

## 4. Sandbox 分级

Sandbox 是策略结果，不直接暴露底层技术名称。

| 等级 | 任务类型 | 默认实现 | 说明 |
| --- | --- | --- | --- |
| `NONE` | 可信编排和普通对话 | 长驻 Worker | 不执行不可信代码 |
| `ISOLATED` | 用户代码、脚本、浏览器、普通插件 | Kubernetes + gVisor | 共享容量，限制网络和资源 |
| `HARDENED` | 高风险代码、跨租户数据、强合规任务 | Kata / MicroVM Provider | 更高隔离和更高成本 |
| `DEDICATED` | 专属企业和特殊合规 | 客户专属节点池或集群 | 通过部署档位提供 |

当前 `TaskSandbox`、`SandboxRuntimePort`、Operator 和 RuntimeClass 设计继续沿用。CubeSandbox、Firecracker、E2B 兼容 Provider 只能实现 Provider 接口，不能进入 Task 领域模型。

## 5. MCP 连接层

MCP 资源分为公共目录、企业连接和资源绑定：

```text
MCP Catalog
  └── 公共 MCP 定义

MCP Connection
  └── 企业或租户拥有的实际连接

MCP Binding
  └── 绑定到租户、项目、Team 或 Agent
```

连接模式包括：

- `PLATFORM_PUBLIC`：平台访问公网服务；
- `CUSTOMER_CONNECTOR`：客户 Connector 访问内网或客户 VPC 服务；
- `PRIVATE_DEPLOYMENT`：私有化环境内直接访问本地 MCP。

Sandbox 不直接持有 MCP 凭据。所有调用经过 MCP Gateway，由 Gateway 或客户 Connector 负责凭据、工具白名单、域名策略、超时、审计和结果脱敏。

## 6. Skill 设置管理

Skill 是可管理、可版本化、可审核的企业资源，不等同于一段任意可执行脚本。Skill 管理至少包含：

- 公共 Skill Catalog；
- 企业/租户私有 Skill；
- Skill 元数据、版本、Manifest 和不可变 digest；
- 上传、格式校验、安全扫描和人工审核；
- 发布、禁用、撤销和版本回滚；
- Organization、Tenant、Project、Team 和 Agent 的可见性及绑定；
- Skill 对 Model、MCP、Secret、网络和 Sandbox 的能力声明；
- 运行时资源限制、允许的工具和出站域名；Worker 在注册 MCP 工具时按所有激活 Skill 的交集执行 MCP、工具、域名和 Secret 拦截；
- Skill 使用审计、版本归因和成本归因。

Skill 的运行边界如下：

```text
Skill Catalog / Skill Version
          |
   Policy + Approval
          |
  AgentSpec / Team Binding
          |
   Sandbox Policy Resolution
          |
  Sandbox + MCP Gateway
```

当前 Worker 的 AgentScope 会话对多个 Skill 共用一个 Toolkit，因此运行时采用交集策略，避免“任一 Skill 放行”造成权限扩大。无能力声明的历史配置继续保持兼容；有能力声明但未明确允许的 MCP、工具、域名或 Secret 默认拒绝。通用 Sandbox 网络出口仍由最终 Sandbox Provider 的 NetworkPolicy 执行，Worker 侧拦截负责 MCP 客户端边界。

已发布 Skill Version 不可修改。AgentSpec、Team Revision 和 Worker Template 只能引用明确的 `skillId + version + digest`，运行时再次校验可见性、审核状态、资源能力和当前企业策略。Skill 包、Manifest、扫描结果和运行时日志不得包含 Secret 明文。

Skill 的设置管理与 MCP 管理保持同一套资源 scope 和权限模型，但两者不是同一资源：Skill 描述 Agent 能力和运行包，MCP 描述外部工具连接；Skill 只能通过 MCP Gateway 使用 MCP，不能自行携带 MCP 凭据。

## 7. 任务结果和执行过程

企业通过统一的任务执行协议获取任务结果和执行过程，不直接读取 Worker、NATS、Sandbox 或内部数据库。

### 7.1 结果获取

最终结果由 `ResultManifest` 表达，产物正文存储在对象存储，Control Plane 只保存元数据、Hash、版本和权限信息。对外提供任务快照、结果清单、产物列表和短期签名下载地址。大文件不得进入事件 Payload。

```text
Task Snapshot
  └── Result Manifest
       ├── summary
       ├── status
       ├── runId
       └── Artifact References
```

中间产物和最终产物使用同一套 Artifact 模型，并标记阶段、版本、来源 Task/Attempt 和血缘关系。

### 7.2 过程事件

任务过程通过可回放的事件流获取，支持 SSE、Webhook 和 SDK Callback。NATS 只负责内部投递，不能作为企业公共契约。事件至少包含：

```text
eventId, taskId, runId, sequence, eventType,
visibility, occurredAt, correlationId, payload / payloadRef
```

事件类型包括任务生命周期、任务拆分、计划阶段、进度、工具调用、Sandbox、人工审批、重试恢复、中间产物和最终结果。

多 Agent 任务使用 `Task Tree` 表达父子任务和依赖关系；过程事件只做事实记录，不替代 Task 状态机。

### 7.3 思考和决策信息

平台不向企业公共接口暴露原始模型 Chain of Thought。对外提供脱敏的 `Reasoning Summary` 和 `Decision Record`，内容包括当前目标、选择的方案、约束、依据、置信度、下一步和是否需要人工输入。系统提示词、Secret、完整 Prompt、其他用户数据和未脱敏 MCP 响应不得进入公共事件流。

### 7.4 可见性和回放

事件和产物都携带 `REQUESTER`、`PROJECT_MEMBER`、`TENANT_ADMIN`、`SECURITY_AUDITOR` 或 `INTERNAL_ONLY` 可见性。事件订阅支持 `Last-Event-ID`、sequence 回放、权限过滤、脱敏、保留和归档。企业管理员可以查看状态、资源、成本和审计信息，但不能默认查看用户私人内容或原始思维链。

## 8. 用户上下文、记忆和行为

用户相关数据分为身份上下文、执行上下文、个性化上下文和行为/审计数据。身份和执行上下文用于授权；个性化上下文只能影响模型行为，不能覆盖权限策略。

### 7.1 记忆作用域

```text
用户私人记忆
企业 / 租户共享记忆
项目 / Team 记忆
对话记忆
任务 / Attempt 记忆
审计账本
行为统计
```

默认规则如下：

- 用户私人记忆由用户本人读写，企业管理员默认不能查看内容；
- 企业管理员可以配置保留、关闭、删除、导出和敏感信息策略，但治理权限不等于内容读取权限；
- 企业共享记忆由企业或租户授权角色维护；
- 项目/Team 记忆遵循成员和角色权限；
- 对话和任务记忆只对参与者及授权 Agent 可见；
- 审计数据与产品行为数据分离，原始行为数据不直接拼接进 Prompt；
- 模型推断出的偏好先进入候选记忆，敏感或长期记忆需要用户确认；
- 用户私人记忆默认不用于模型训练。

### 7.2 Context Assembly

Manager 不直接读取所有历史数据。新增逻辑上的 `Context Assembly` 层，按当前 `ExecutionContext`、记忆 scope、敏感等级、企业策略和 Token 预算组装最小上下文，并记录本次使用的记忆摘要和来源。

Sandbox 只接收任务所需的最小脱敏上下文，不接收用户完整历史、其他成员行为、原始审计日志或 Secret。Skill 只能读取被授权的记忆作用域，MCP 返回结果必须先经过 MCP Gateway 的权限和脱敏处理。

### 7.3 管理员访问

企业管理员可以查看记忆数量、容量、来源、更新时间和策略状态，可以执行冻结、删除、导出和保留操作；查看私人记忆内容必须经过指定原因、审批、最小范围、限时授权和完整审计。

## 9. 数据和成本边界

Sandbox 是临时执行环境，Durable Workspace 是持久化工作区，两者分离：

- Sandbox 按 Task Attempt 创建、续期和销毁；
- Workspace 使用对象存储或独立 Workspace 服务，按 Organization/Tenant 隔离；
- Task、模型调用、MCP 调用和 Sandbox 资源都关联统一的成本维度；
- Token 使用预占、释放、结算账本，不使用单一余额字段；
- 企业、租户、项目和用户可以分别设置并发、速率和预算边界。

## 10. 安全边界

- Control Plane 不访问 Docker Socket，不管理任意 Pod；
- Sandbox 默认关闭 Kubernetes Token、特权模式、Host Network、Host PID、Host Path 和宿主机设备；
- 任务网络默认拒绝，只允许 MCP Gateway、模型服务和 Artifact 服务；
- Provider 凭据只在 Provider 或 Connector 侧使用，不进入 Task、Outbox、事件和 Worker 消息；
- Sandbox、MCP 和事件日志只记录稳定 ID、状态、错误类别和脱敏摘要；
- Namespace 不是唯一租户隔离手段，必须结合数据库授权、Connector 身份和 Sandbox 运行时隔离。

## 11. 可靠性和扩展性

- Control Plane 继续以 PostgreSQL 作为权威状态，以 Outbox + NATS 投递异步命令；
- Sandbox Provider、Connector 和 MCP Gateway 均使用幂等命令、心跳、重连和版本协商；
- 调度按 Organization/Tenant 做公平队列，避免单一企业占满共享容量；
- 普通任务使用共享 gVisor 容量，高安全任务进入 Kata 或专属节点池；
- 只有在压测证明启动延迟、密度或成本成为瓶颈后，才引入 CubeSandbox 或 Firecracker；
- 任何涉及 Kubernetes、Operator、Worker、TaskSandbox、RuntimeClass、镜像或运行时路由的变更，都必须通过本地 Docker 和 Ubuntu/KVM L5 验收；L6 保持独立门禁。

## 12. 商业部署档位

| 档位 | Control Plane | Execution Plane | MCP 网络 | 主要价值 |
| --- | --- | --- | --- | --- |
| 基础版 | 平台 SaaS | 平台共享集群 | 公网 MCP | 低成本快速使用 |
| 企业版 | 平台 SaaS | 平台或客户 VPC | 客户 Connector | 内网访问、企业审计和私有 MCP |
| 专属版 | 平台专属或客户私有 | 专属节点池或私有集群 | 私有网络 | 强隔离、合规和数据驻留 |

## 13. 不在本架构基线中承诺的内容

- 不承诺所有客户默认独立 Kubernetes 集群；
- 不承诺直接暴露原始模型 Chain of Thought；
- 不以某个开源 Sandbox 项目的宣传性能作为容量承诺；
- 不在没有实测数据前把 CubeSandbox、Firecracker 或 E2B 设为唯一运行时；
- 不把私有化部署与 SaaS 版本做成两套业务模型。
