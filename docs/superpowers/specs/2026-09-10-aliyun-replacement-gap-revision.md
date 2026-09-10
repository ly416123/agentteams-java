# 阿里云 AgentTeams/AgentCore 替换缺口基线修订

- 日期：2026-09-10
- 输入：《替换阿里云 AgentTeams/AgentCore 缺口分析》（基线 `f0a8420`，下称「原分析」）
- 本文档性质：对原分析的代码级核验结论与基线修订，替代原分析中的「当前判断」列
- 核验方法：生产代码静态抽查（端点清单、类存在性、依赖引用、git 历史），未执行全量测试，与原分析口径一致
- 修订后基线：`923c0c8`

## 1. 基线变更说明

原分析基于 `f0a8420`。此后 main 已合入：

| Commit | 内容 | 对缺口结论的影响 |
|---|---|---|
| `201dd71` | 会话文件交付（17 commits，含 L5 部署排障修复） | **直接影响 G05**：会话文件部分已交付 |
| `0855530` | console 设置页挂入 ConsoleLayout | 无业务语义影响 |
| `923c0c8` | console 字号 token 化与排版修复 | 无业务语义影响 |

原分析「本次未找到设计中的 `ConversationFileController` 和上传工具实现」的判断已过时，G05 需按第 3 节重写。

## 2. 逐项核验结论总表

| 编号 | 原判断 | 核验结论 | 修订 |
|---|---|---|---|
| G01 corp-agent 接入层 | 两边模型不同，尚未形成可替换接入层 | 成立。corp-agent 三个对接类核实存在且很薄：`AgentTeamsResourceGateway`（143 行）、`AgentTeamsTaskGateway`（131 行）、`AgentTeamsChatClient`（49 行） | **利好**：替换面窄，适配器成本低于预期；其余内容不变 |
| G02 任务业务生命周期 | 未发现补充要求/结果评审/多版本结果/归档的完整闭环 | 成立。`TaskController` 全部端点为 `GET /{id}`、`cancel`、`queue`、`retry`、`pause`、`approve`、`reject`；archive/评审/补充要求在 control-plane 无实现 | 不变；补充交集风险见第 5 节 |
| G03 多 Agent 协作 | 子任务路径是观测投影，不能认定已实现分布式调度 | 成立。`SubtaskService` 类注释自述「公理一：本服务是 best-effort 观测投影，绝不改变主任务终态」；L5 既有子任务端到端验收同为观测路径 | 不变；**方向决策前移**见第 4 节 |
| G04 对话动态路由 | 使用配置中的单一 Endpoint/Agent ID | 成立。L5 现状即单 qwenpaw endpoint + 单 Agent ID | 不变 |
| G05 会话/任务文件交付 | 未找到会话文件实现；任务结果提取以文本为主 | **部分过时** | **重写**，见第 3 节 |
| G06 取消/暂停/恢复语义 | 控制面状态与 Runtime 能力不完全对等 | 成立（方向性核验） | 不变 |
| G07 生产凭据接入 | 默认 `UnavailableCredentialSecretProvider` 返回空值 | 成立。属部署侧适配而非功能缺失，与原分析定性一致 | 不变 |
| G08 真实模型/MCP/Skill 一致性 | 需按选定 Runtime 完整验收 | 成立（验收型工作包） | 不变 |
| G09 SDK/Java 17 兼容 | Java 21 SDK 无法直接引入 corp-agent | 成立。`sdk/java/pom.xml` 无独立 `release` 配置（跟随父 pom Java 21） | **补充首选路线**：SDK 依赖薄，将独立 SDK 编译目标降到 Java 17 是三条路线中改动最小、风险最低的，建议 A 批次先做降版本验证 spike |
| G10 生产基础设施适配 | 多为集成与验证工作 | 成立 | 不变 |
| G11 硬预算与成本 | `HARD_LIMIT` 未与任务/模型 admission 连接 | 成立，有代码级证据：`HARD_LIMIT` 全部 7 处引用均在 `usage` 预算评估/通知/仓储链路，无 admission 路径引用 | 不变 |
| G12 数据迁移 | 未发现针对阿里云的迁移工具链 | 成立 | 不变；前置盘点工具应尽早启动 |

## 3. G05 重写：会话/任务文件交付

### 3.1 已交付（随 `201dd71` 合入，L5 端到端验收 PASS）

| 能力 | 实现 |
|---|---|
| 会话文件上传 | `ConversationFileController` + `ConversationFileService`：50MB multipart 上限、超限 413 由全局 advice 兜底（`FILE_TOO_LARGE`）、content_type 超长回退 |
| 上传工具 | MCP `upload_file`（`scripts/agentteams-task-mcp.py`）：qwenpaw→manager 集群内直传，filename 注入清洗 |
| 下载 | `GET /api/v1/conversations/{sessionId}/files/{fileId}` 匿名 302 → **15 分钟 presigned URL**（非永久链接）；高熵 fileId 为能力凭证 |
| 部署链 | helm storage 环境块、manager NetworkPolicy 双向放行（minio egress / qwenpaw ingress）、L5 确定性层 7/7 + 真模型生成 PDF→上传→回复内嵌链接→下载 全 PASS |

安全取舍沿用原分析结论：匿名入口无会话权限校验，生产化时改为登录校验后签发临时下载地址或可撤销共享令牌（收窄 `ManagerAuthenticationFilter` 放行路径即可，改动小）。

### 3.2 仍缺口（G05 剩余范围）

| 子项 | 开发内容 | 验收重点 |
|---|---|---|
| 任务文件 | 输入附件落入执行环境；输出支持 PDF/图片/Office 真实二进制；关联 Task/Attempt/子任务 | Worker 删除后仍可下载；重试不产生失控重复文件 |
| 交付可靠性 | 上传失败持久化重试队列、校验、去重、临时对象清理、数量对账 | 「执行成功」与「交付成功」分离；失败交付可重试且可观测 |

## 4. 批次顺序调整

原 A→E 骨架保留，两处调整：

1. **G03 方向决策前移到 A 批次**。「展示拆解过程」还是「多个 Worker 独立执行并汇总」是全部 P0 中唯一的方向性决策，决定 C 批次体量与最终验收形态；A 批次做替换契约设计时必须拿到业务答案，否则 B/C 边界无法冻结。
2. **B 批次减项与补位**。G05 会话文件已交付，B 批次剩余任务文件与交付可靠性，体量缩小；建议将 G07（生产凭据接入）或 G02 的查询兼容前移补位。

## 5. 交集风险（原分析未覆盖）

G02「结果评审」的对象与 G03「子任务产物汇总」存在交集：评审针对主任务最终结果，还是包含中间子任务产物，直接影响两者的表结构与状态机。应在 A 批次契约中一次定清，避免 G02/G03 各自实现后无法对接。

## 6. 立即行动清单

| # | 行动 | 对应 |
|---|---|---|
| 1 | 业务拍板：子任务是展示还是真调度 | G03 决策 |
| 2 | G02 任务评审闭环设计（B 批次起点） | G02 |
| 3 | 任务侧二进制交付与交付可靠性设计 | G05 剩余 |
| 4 | SDK 降版本到 Java 17 的编译验证 spike | G09 |
| 5 | 启动旧平台配置与历史数据盘点工具 | G12 前置 |
