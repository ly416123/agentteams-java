# 旧平台资产盘点汇总（2026-09-14）

- 数据源：corp-agent MySQL 台账 + corp-agent application yaml
- 口径：**台账镜像口径** —— 台账为「本地落库+远程同步」镜像，可能与平台侧真实状态漂移；核对需后续 OpenAPI 对账
- 敏感性：本报告不含 Prompt 正文、消息内容与凭据；明细（含正文）在 `output/`（gitignore）

## 资产总览

| 域 | 状态 | 数量统计 |
|---|---|---|
| platform-config | ok |  |
| workers | ok | at_worker_count=0; de_worker_count=75 |
| teams | ok | de_team_count=15; de_team_worker_rel_count=163; de_team_crew_rel_count=24; user_mapping_count=15; at_team_count=0 |
| mcps | table_missing |  |
| endpoints | table_missing |  |
| history | ok | de_task_total=326; de_task_rslt_total=158; de_chat_convo_total=164; de_chat_msg_total=1277 |
| legacy-to-new-mapping | ok | adopt=0; adapt=7; drop=1; total=8 |

## 降级说明（表缺失域）

- `mcps`: at_* 平台镜像表缺失：corp-agent 当前 agentteams.gateway.impl=remote（纯远程透传，从未落库）。资源清单以 de_* 业务台账为准，平台侧真实状态需 OpenAPI 对账（后续阶段）。
- `endpoints`: at_* 平台镜像表缺失：corp-agent 当前 agentteams.gateway.impl=remote（纯远程透传，从未落库）。资源清单以 de_* 业务台账为准，平台侧真实状态需 OpenAPI 对账（后续阶段）。

## 清单骨架（名单；完整字段在明细层）

- workers.de_worker (5 名单/75 行): corp-leader, 供应链主管, 战略专家, 销售顾问, 风控管理
- teams.de_team (15 名单/15 行): corp-map-team-01o-exr8za0cqr-542b799, corp-map-team-02048034615296-84cd1f6, corp-map-team-14894789144576-2833680, corp-map-team-1ebmw6kfkbduhc-bd368a9, corp-map-team-30482643423232-6ab079f, corp-map-team-45zq23qfby9pam-f9c7879, corp-map-team-47207311732736-e9397ff, corp-map-team-4oalxa9ba5p6ho-fca1ac8, corp-map-team-6g6787n2e1a63e-a8e3590, corp-map-team-9encf9h6ejrfv0-efc9c13, corp-map-team-flcqo7i3gcc8x3-98a816f, corp-map-team-klnbjilphdcaoj-081897f, corp-map-team-m9e2keojffiss1-dc47924, corp-map-team-pohtb569sj-g4g-1ea7925, corp-map-team-ta5hyaf8csdl0m-3490d0c

## 漂移线索（at_* vs de_* 名单差集）

-（无漂移线索）

## 历史数据画像

- `de_task`: total=326, child_task_count=134, by_status={'CC': 22, 'P': 5, 'CP': 281, 'R': 7, 'W': 11}
- `de_task_rslt`: total=158, multi_version_count=0, success_count=158
- `de_chat_convo`: total=164, time_span={'from': '2026-09-02 16:27:22', 'to': '2026-09-14 20:04:17'}
- `de_chat_msg`: total=1277, by_role={0: 291, 1: 986}, time_span={'from': '2026-09-02 16:25:06', 'to': '2026-09-14 20:04:17'}

## 旧→新映射评估（三态决策）

策略口径：adopt=概念对等直接迁移；adapt=语义等价按自研架构重建；drop=阿里缺陷或无迁移价值（须记录理由）。

| 旧概念 | 新平台对应 | 策略 | 理由 |
|---|---|---|---|
| at_worker.soul + agents（固定分段标记合成 instruction） | Worker/AgentSpec prompt 直接建模 | adapt | 分段标记协议是阿里实现细节，corp-agent 适配器自述需『厚翻译』 |
| legacy Team 无 Leader 参数（groups[0].role=leader 隐式认定） | Team 显式 Leader 一等概念 | adapt | 隐式格式语义易错 |
| AgentCore Team.agents 恰一 Leader 硬约束 | 自研 Team 状态机自行定义成员约束 | adapt | 平台硬约束不进入领域模型 |
| Agent 属于 Team 时禁止删除（AgentCore 409） | 解绑+删除显式编排（detachAndDeleteWorker 既有） | adapt | 约束属平台实现细节 |
| ServiceEndpoint {workerName}.worker.{host} 前缀格式匹配 | Endpoint 一等实体显式字段 | drop | 格式即协议的反模式 |
| modelProvider ↔ modelConnectionId 平台侧连接映射 | AgentSpec manifest + credentialRef 引用 | adapt | 模型配置不绑死平台侧连接 |
| mcpServers ↔ tools[{name,type=MCP}] | MCP 注册中心 + AgentSpec manifest 下发 | adapt | 自研 MCP 发现与运行时绑定已交付 |
| Worker（ManagedAgent）资源模型 | Worker + AgentSpec + Team Revision | adapt | 自研控制平面已按自身架构建模 |

## 映射统计

| 策略 | 数量 |
|---|---|
| adopt | 0 |
| adapt | 7 |
| drop | 1 |
