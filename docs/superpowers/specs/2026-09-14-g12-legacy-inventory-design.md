# G12 前置：旧平台配置与历史数据盘点工具设计

- 日期：2026-09-14
- 状态：设计已获用户批准（范围/口径/敏感策略/三态映射决策/方案 A 均经逐项确认）
- 上游依据：《阿里云 AgentTeams/AgentCore 替换缺口基线修订》（`2026-09-10-aliyun-replacement-gap-revision.md`）立即行动清单 #5
- 定位：**调研工具，非迁移工具**。产出旧平台资产清单与旧→新映射评估，作为 G01（corp-agent 接入层替换契约设计）与 G12 主体（迁移工具链）的输入；与功能开发零耦合

## 1. 背景与目标

G12「数据迁移」在动手前必须先回答：旧平台上有什么资产、有多少、长什么样、与自研平台概念如何对应。本工具一次性盘点三类资产：

1. **平台配置**（切换新平台时需重建的配置参数）；
2. **平台资源**（旧平台业务对象：Worker/Team/MCP/Endpoint）；
3. **历史数据**（任务/结果/会话/消息，全量统计 + 抽样画像）。

## 2. 数据源事实（基于 corp-agent 代码分析，2026-09-14 核验）

corp-agent（`com.wanlianyida.corpAgent`，阿里云 Codeup 托管）是旧平台的真实消费者，其代码与配置给出盘点数据源的全部事实：

| 事实 | 内容 | 对盘点的影响 |
|---|---|---|
| 当前激活平台 | `agentcore.enabled=true`，AgentCore（SDK `agentcore20260804`），workspace `ws-f273387b...`，leader-agent `agent-143cc204b...` | 盘点以 AgentCore 为现状基准，legacy 并存 |
| legacy 平台残留 | AgentTeams（SDK `agentteams20260605`），instance `at-cn-4jg4w7zdk01`，endpoint `agentteams.cn-beijing.aliyuncs.com` | 配置域同时盘点两套参数 |
| 数据库 | 阿里云 RDS MySQL 8.0，库 `inner_imp_de`（corp-agent 本地配置直连；凭据运行时读取，不落盘） | 台账与历史数据均可离线盘点，无需阿里云 OpenAPI 凭据 |
| 平台资源镜像表 | `at_team` / `at_worker` / `at_mcp_server` / `at_service_endpoint`（`resources/sql/agentteams_ddl.sql`） | Worker 含 soul/agents MD 正文、模型、MCP/Skill 绑定 JSON |
| 业务台账与历史 | `de_user_mapp` / `de_worker` / `de_team` / `de_team_worker_rel` / `de_team_crew_rel` / `de_task` / `de_task_rslt` / `de_chat_convo` / `de_chat_msg` | 任务/会话历史在 corp-agent 自有库 |
| 台账口径风险 | `agentteams.gateway.impl` 当前为 `remote`（纯远程透传），`at_*` 表可能不存在或为空 | 预检逐表探测，缺失降级不失败 |
| 双侧台账漂移 | `at_*`（平台镜像）与 `de_*`（业务台账）是两套数据 | Worker/Team 域做两侧名单差集，漂移线索进报告 |

## 3. 总体架构

```
corp-agent MySQL（at_* / de_* 表）      corp-agent application*.yaml
                └──────────┬──────────────────────┘
                           ▼
        Collector 接口（本期实现 DbCollector + YamlConfigCollector；
                       OpenApiCollector 留骨架，配置开关不启用）
                           ▼  统一盘点模型（六大域）
        MappingEngine（旧→新映射评估：种子映射表 + 三态决策列）
                           ▼
        ReportWriter 两层产出
          ├─ output/legacy-inventory-<date>/detail/*.json   ← 明细（含正文），gitignore
          └─ docs/inventory/<date>-legacy-inventory.md      ← 汇总（脱敏），入库
```

- 工具形态：`scripts/inventory-legacy-platform.py`（Python CLI，沿用 scripts/ 惯例），配套 `scripts/test_inventory_legacy_platform.py`（pytest 契约测试）
- DB 访问：pymysql；配置解析：PyYAML
- 连接串从环境变量/CLI 参数传入，工具自身不保存任何凭据

## 4. 六大盘点域

| 域 | 数据源 | 盘点内容 | 产出层级 |
|---|---|---|---|
| ① 平台配置 | corp-agent yaml | AgentCore：enabled/workspace-id/leader-agent-id/runtime（compute-class、session-policy-type）；legacy AgentTeams：endpoint/instance-id/worker-url/Matrix homeserver/task+SSO 配置块。workspace-id/instance-id/leader-agent-id 属资源标识符而非凭据，可全文记录 | 汇总=参数清单；**凭据（api-key/DB 密码/SSO token）只记 SHA-256 指纹前 8 位，不落值** |
| ② Worker | `at_worker` + `de_worker` | 清单（name/agent_type/deploy_type/model_provider/model_name/status）+ MCP/Skill 绑定结构；soul+agents 正文进明细；`at_worker` 与 `de_worker` 名单差集 = 漂移线索（对齐键预期为两侧 name 类字段，实现计划阶段按 `0827_数字员工ddl.sql` 的 `de_worker` DDL 确认） | 明细含正文；汇总只含清单与差集统计 |
| ③ Team | `at_team` / `de_team` / `de_team_worker_rel` / `de_team_crew_rel` | leader、成员、worker 分组关系、双侧名单差集 | 明细 + 汇总统计 |
| ④ MCP | `at_mcp_server` + `at_worker.mcp_servers_json` | 协议/地址/部署状态/被引用关系 | 明细 + 汇总统计 |
| ⑤ Endpoint | `at_service_endpoint` | 组件/资源名/地址/鉴权方式 | 明细 + 汇总统计 |
| ⑥ 历史数据 | `de_task` / `de_task_rslt` / `de_chat_convo` / `de_chat_msg` | **全量统计**：数量、状态分布、时间跨度、按 worker/团队维度分布；每表抽样 ≤20 条做字段画像 | 汇总=统计画像；明细=仅抽样行 |

历史域明确不做全量正文导出（体量与敏感双重考虑，经用户确认）。

## 5. 映射评估（三态决策列）

MappingEngine 对每个旧概念输出映射行：`旧概念 → 新平台对应 → 策略（采用/改造/放弃）+ 理由`。新平台侧基准：`openapi/agentteams-public.yaml` v1.0 + 本仓库领域模型，理念对齐上游 agentscope-ai/AgentTeams（Manager-Workers 协作、Matrix 房间消息流、Project workflow API）。

种子映射表（从 corp-agent 适配器注释提炼，2026-09-14 核验）：

| 旧概念（阿里 AgentTeams/AgentCore） | 新平台对应 | 策略 | 理由 |
|---|---|---|---|
| `at_worker.soul` + `at_worker.agents`（两个 MD 用固定分段标记合成 instruction） | Worker/AgentSpec prompt 直接建模 | 改造 | 分段标记协议是阿里实现细节，corp-agent 自述需「厚翻译」 |
| legacy Team 无 Leader 参数，leader 靠 `createWorker` 的 `groups[0].role=leader` 隐式认定 | Team 显式 Leader 一等概念 | 改造 | 隐式格式语义易错 |
| AgentCore `Team.agents` 必须恰好含一个 Leader 的非空列表（平台硬约束） | 自研 Team 状态机自行定义成员约束 | 改造 | 平台硬约束不进入领域模型 |
| Agent 属于 Team 时禁止删除（AgentCore 409） | 自研删除语义（解绑+删除显式编排，如 `detachAndDeleteWorker` 已有） | 改造 | 约束属平台实现细节 |
| ServiceEndpoint `{workerName}.worker.{host}` 前缀格式匹配 | Endpoint 一等实体显式字段 | 放弃 | 格式即协议的反模式 |
| `modelProvider ↔ modelConnectionId` 平台侧连接映射 | AgentSpec manifest + `credentialRef` 引用 | 改造 | 模型配置不绑死平台侧连接 |
| `mcpServers ↔ tools[{name,type=MCP}]` | MCP 注册中心 + manifest 下发 | 改造 | 自研 MCP 发现与运行时绑定已交付 |
| Worker（ManagedAgent）资源模型 | Worker + AgentSpec + Team Revision | 改造 | 自研控制平面已按自身架构建模 |

映射评估表是 G01 契约设计与 G12 迁移工具链的逐行依据；「放弃」行必须记录理由。

## 6. 敏感与入库策略（两层分离，经用户确认）

- `output/` 目录追加 `.gitignore` 条目；`detail/*.json` 头部带 `_meta`：数据源、生成时间、**「台账镜像口径」声明**、敏感级别标注
- 入库的汇总 Markdown 只含：统计、清单名、状态分布、脱敏样例；**Prompt 正文与消息内容不进 git**
- DB 凭据、api-key 全程内存使用，不进任何产出物（含错误信息与 `_meta`）
- 历史域抽样行中的消息正文在明细中保留（迁移映射评估需要素材），汇总中一律脱敏

## 7. 错误处理与预检

- `--check` 预检模式：DNS → 网络可达（RDS 白名单常见失败点）→ 账号权限 → 表存在性，逐层诊断并给修复指引
- 逐表探测，`at_*` 缺失输出「缺失说明」（含原因推断：未启用 `gateway.impl=db` 落库模式），降级继续其余域
- 退出码区分三种状态：连接失败 / 表缺失（降级成功）/ 正常完成
- 查询全部只读（显式 SELECT，无任何写语句）；大表统计用聚合 SQL 而非全表拉取

## 8. 测试策略

pytest 契约测试（`scripts/test_inventory_legacy_platform.py`），全部基于脱敏合成 fixture（固化六大域样本 JSON 模拟表数据），不打真库：

1. 解析器契约：TSV/JSON/参数结构的解析与字段画像；
2. 映射引擎契约：三态决策（含种子映射的预期标注）；
3. 报告两层分离断言：汇总不含正文与凭据、明细含正文且 `_meta` 完整、`.gitignore` 生效断言；
4. 降级路径：表缺失/连接失败/空数据的退出码与报告形态；
5. CLI 冒烟：假 DSN 的 `--check` 失败路径。

## 9. 验收标准

1. 对 corp-agent 库（可达时）一次运行产出两层报告，六大域齐全；不可达时 `--check` 诊断明确；
2. 汇总报告含：资产数量总表、各域清单骨架、双侧差集漂移线索、历史数据统计画像、完整映射评估表；
3. 明细 JSON 含正文与抽样画像，且被 gitignore；
4. 全部测试绿（fixture 驱动，CI 可跑）；
5. 仓库内任何提交内容不含凭据、完整 Prompt、消息正文（`git diff --check` + 人工核对）。

## 10. 非目标

- 不做数据迁移（G12 主体）、不做 OpenAPI 在线对账（Collector 接口留扩展点，凭据具备时接入）；
- 不改动 corp-agent 仓库（只读分析）；
- 不盘点 Matrix homeserver 内的消息（历史域以 corp-agent 自有库为准）；
- 不做跨平台 ID 映射的最终决策（映射表只给策略建议，决策归 G01/G12 主体）。
