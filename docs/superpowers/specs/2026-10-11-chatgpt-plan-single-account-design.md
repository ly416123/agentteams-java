# ChatGPT 计划额度单账户接入设计

日期：2026-10-11
状态：设计已获用户确认，等待规格审阅

## 背景与目标

L5 双 MCP 评测 Team 当前通过 AgentScope Harness 调用 DeepSeek。评测账号额度不足，导致 10 个 PDF 评测用例在模型调用阶段失败。目标是增加 ChatGPT 计划额度的单账户接入方式，让指定的 L5 评测 Team 使用一个已授权的 ChatGPT Plus 账户完成模型调用，并继续执行 `ai-news/search_news`、`agentteams-web-search/web_search` 和 PDF 成果物流程。

本次只服务现有双 MCP 评测 Team。其他 Team、用户、Worker 和 L5 默认 Provider 继续使用现有配置。当前行为不得隐式切换 Provider 或回退 DeepSeek。

## 已确认的设计

### 模型运行时

- 保留 AgentScope Harness 和 Worker 发起模型请求的架构。
- 增加 `chatgpt-plan` Provider 的 AgentScope `Model` 实现，调用 OpenAI Responses API。
- 将 AgentScope 提供的本地工具 Schema 映射为 Responses API function tools；把模型返回的 function call 转回 AgentScope 的工具调用响应，继续复用现有 MCP 工具执行路径。
- 响应请求使用 OAuth Bearer Token，并满足 ChatGPT 计划额度预览接口要求：`store=false`、`stream=true`。适配器不发送不受支持的参数，也不把 MCP 服务器配置为 OpenAI 托管 MCP。
- `ModelRegistry` 通过 `chatgpt-plan:<model>` 解析新 Provider。模型名称以已授权账户可用的模型清单为准，不在代码中写死不可验证的账户能力。
- ChatGPT Provider 的认证失败、额度限制或网络错误应明确失败；不自动调用 DeepSeek，防止评测结果混用模型。

### 单账户 OAuth 与 Token 生命周期

- 用户在受信任的本机完成 Sign in with ChatGPT OAuth/PKCE 授权；不在聊天、命令参数、日志或 Git 文件中传递密码或 Token。
- OAuth 客户端使用稳定的 L5 主机标识，验证 `state`、`nonce`、Issuer、Audience、有效期和所需 Scope。
- 将 client ID、refresh token、access token 和过期时间存放在同一个 OAuth 会话 Kubernetes Secret，由刷新组件独占读写；获准的评测 Worker 通过 Secret volume 的 `items` 只映射 `access-token` 键，不把 refresh token 暴露给容器。
- 由单实例、串行的刷新作业定期轮换 refresh token，并以一次 Kubernetes Secret 更新同时保存新 refresh token 与 access token；刷新并发策略为 `Forbid`。失败时保留上一次有效凭据并记录脱敏状态，不能写出 Token。access token 在 Worker 侧按请求读取 Secret 投影文件，以适配轮换。
- 刷新组件只拥有读取/更新这一个指定 OAuth 会话 Secret 的 RBAC，不授予通用 Secret 枚举权限。刷新作业使用 L5 已有的资源 requests/limits 和固定镜像版本。
- ChatGPT Plus 的计划用量按账户共享。评测过程中该账户在其他 ChatGPT 产品或应用中的用量也可能占用同一额度；此集成不保证独占配额。

### 评测 Team 隔离

- 凭据注入由 Worker Provisioner 按规范化的 Tenant、Project UUID、Team UUID 和 Leader/Executor 类型判定，不能作为 Helm 全局环境变量注入所有动态 Worker。
- 只有现有双 MCP 评测 Team 的 Leader/Executor Worker 可以挂载 access-token Secret。其他 Team 即使选择 `chatgpt-plan` Provider，也必须因授权策略不匹配而失败关闭。
- 可选 HTTP CONNECT 代理地址通过 Control Plane 配置，只注入同一 Scope allowlist 命中的 ChatGPT Worker 和 Token Refresh Job；其他 Provider 与 Team 不获取该代理配置。代理 URL 不接受认证信息，默认不配置代理。
- 已有 `DEEPSEEK_API_KEY` Secret 保持原样；L5 默认模型和其他 Team 配置不变。
- 授权策略中的 Team 与 Project 标识必须使用 UUID，遵循 `docs/development/project-constraints.md` 的 Scope 规范，不使用显示名称。

### 观测与错误处理

- 日志允许记录请求阶段、HTTP 状态码、错误类型、模型 ID 和工具调用名称；不得记录请求/响应正文、Authorization Header、access/refresh token、OAuth callback 参数或带凭据 URL。
- 对 401/403、429、网络错误和无效响应分别给出可诊断的非敏感错误类别。
- Token 刷新状态只记录成功/失败时间、过期时间和非敏感错误分类。

## 备选方案与取舍

1. **AgentScope Responses Provider（选定）**：Worker 仍是模型请求发起者，保留单一 Harness 与本地 MCP 工具链；需实现 OAuth 生命周期管理、Responses API 的流式解析和工具调用映射。
2. **Codex app-server**：复用 Codex 的 OAuth 体验，但引入第二条模型运行时，并要求维护 Worker 到 app-server 的生命周期和协议，不符合当前 Worker 唯一执行路径约束。
3. **OpenAI API Key**：技术接入简单，但 ChatGPT Plus 与 API 计费分开，不能解决本次计划额度目标。

## 验收条件

- `chatgpt-plan` 模型能够通过真实 OAuth access token 完成 Responses API 流式请求，且请求符合 `store=false` 约束。
- 模型能完成至少一次本地函数工具往返，工具仍由 AgentScope/MCP 执行。
- refresh token 轮换后，新 access token 可被已挂载的评测 Worker 读取；并发刷新不会丢失新 refresh token。
- Kubernetes 身份与 Worker Provisioner 策略证明只有双 MCP 评测 Team 指定 Worker 能挂载 access token；其他 Team 拒绝。
- L5 单例 smoke 和完整 10 case 评测均走真实模型，并保留既有 PDF 程序检查、DeepEval 内容评审和脱敏报告。
- L5 若不能直连 OpenAI，可由桌面代理开启 LAN 监听后配置评测 Team 专用代理地址；代理可达性和真实 API 调用必须在启用 Team 前验证。
- 不发生 DeepSeek 自动回退；报告中记录实际 Provider/模型的非敏感标识。

## 外部接口参考

- Sign in with ChatGPT 快速开始与 OAuth：<https://developers.openai.com/siwc/quickstart>
- Open-source OAuth 注册与登录流程：<https://developers.openai.com/siwc/token-sharing-open-source/sign-in>
- Responses API 推理约束：<https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference>
- 令牌轮换与会话保护：<https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions>
- 自托管 VM 授权：<https://developers.openai.com/siwc/token-sharing-open-source/self-hosted-vms>
- 预览限制：<https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations>
