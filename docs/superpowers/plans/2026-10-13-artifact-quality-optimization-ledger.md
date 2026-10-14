# 成果物质量优化台账实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 `subagent-driven-development`（推荐）或 `executing-plans` 逐任务实现此计划。步骤使用复选框语法跟踪进度。本需求遵守仓库约束，在 `main` 上直接实施，不创建分支或 worktree。

**目标：** 实现带有数据库持久化、严格项目成员授权查询、独立 Console 页面和来源可追溯历史导入的成果物质量优化台账。

**架构：** Control Plane 保存不可变质量事件，以可信任务执行上下文写入，并在查询时用调用者 Tenant 和规范 Project UUID 过滤。实时质量事件从现有评测与任务执行事实产生；历史 JSON/计划由离线解析器生成安全导入记录，再由受控导入器补充数据库作用域后幂等写入。Console 通过独立 API 页面查看轨迹，不改变 PDF 内容和父任务结果 Manifest 规则。

**技术栈：** Java 21、Spring Boot、JdbcTemplate、PostgreSQL/Flyway、JUnit/Mockito/Testcontainers、React、TypeScript、Vitest、Python 3 标准库。

---

## 文件职责

- 新增 `control-plane/src/main/resources/db/migration/V119__artifact_quality_ledger.sql`：事件表、幂等索引和授权查询索引。
- 新增 `control-plane/src/main/java/io/agentteams/controlplane/quality/ArtifactQualityEvent.java`：事件类型、来源、可信度和不可变事件数据。
- 新增 `control-plane/src/main/java/io/agentteams/controlplane/quality/ArtifactQualityEventRepository.java`、`JdbcArtifactQualityEventRepository.java`：写入/分页查询及 DB 作用域绑定。
- 新增 `control-plane/src/main/java/io/agentteams/controlplane/quality/ArtifactQualityLedgerService.java`、`ArtifactQualityController.java`：脱敏、执行上下文写入、用户授权查询 API。
- 修改现有质量评测入口并新增对应测试：把真实检查结果追加到账本；记录失败不得改变原评测或任务结果。
- 新增 `scripts/import_artifact_quality_history.py` 及 `scripts/test_import_artifact_quality_history.py`：安全解析报告/计划、只输出证据可证实的 JSONL 记录、报告逐项跳过原因。
- 新增 `control-plane/src/main/java/io/agentteams/controlplane/quality/ArtifactQualityHistoryImportRunner.java`：可选受控 JSONL 导入，逐行容错、解析可信执行范围并使用同一幂等持久层。
- 新增 `control-plane/src/test/java/io/agentteams/controlplane/quality/*Test.java`：模型校验、脱敏、重复投递、乱序/迟到、跨 Scope 隔离、API 和导入测试；迁移测试使用既有 Testcontainers/Flyway 模式。
- 新增 `console/src/api/artifactQuality.ts`、`console/src/features/quality/ArtifactQualityLedgerPage.tsx` 及 Vitest 测试；修改 `console/src/app/router.tsx`、`AppShell.tsx` 加入独立导航与页面。

## 任务 1：事件契约与迁移

- [x] 先写事件模型测试：必需 `taskId/runId/eventType/occurredAt/idempotencyKey`，拒绝超长值和不合法摘要；没有文件 ID 仍可表示生成失败。
- [x] 运行模型测试并确认因类型/约束缺失失败。
- [x] 写最小不可变事件模型和安全文本处理器；限制 credential、bearer、URL、路径和自由文本字段，字段长度有界。
- [x] 先写迁移集成测试，验证幂等唯一键、事件追加、作用域 UUID 字段和按 scope/时间过滤索引。
- [x] 执行 Testcontainers/Flyway 测试确认迁移测试失败原因正确，再新增 V119 迁移并验证通过。

## 任务 2：持久化、实时事件与 API

- [x] 先写 Repository 测试：相同幂等键重复事件只存一条；不同幂等键按 `occurredAt, sequence, id` 有序追加；迟到事件不会覆盖旧事件。
- [x] 实现 JDBC 仓储，作用域只能来自已验证的 `ExecutionContext`，不得接受请求体 scope。
- [x] 先写 Service/API 测试：项目成员可查自身 Project，跨 Tenant/Project 或无成员关系请求被拒绝；过滤覆盖时间、task、artifact、status、issue type，分页有界。
- [x] 实现 GET 查询 API 和受控的内部写入服务；禁止普通请求者提交任意 scope 或完整敏感内容。
- [x] 先写评测集成测试，断言实际 PDF 缺失/质量门禁失败会产生 `QUALITY_CHECK` 与明确 `INCONCLUSIVE/FAILED` 状态；事件写入异常不改变评测原结果。
- [x] 接入当前确定性评测结果；不把成功重跑本身推断为改善，不改任务状态机、PDF 正文或 Manifest 选择行为。

## 任务 3：历史记录导入

- [x] 先写 Python 测试：报告输出只包含可验证事实、artifact 元数据和来源 SHA-256；不输出提示词、来源正文、凭证或签名地址。
- [x] 验证导入器测试先因解析器缺失而失败。
- [x] 实现 JSON 报告与计划事实解析；弱关联/未证实动作保持未知，不按文件名或时间推断因果。
- [x] 对损坏/未知格式文件逐项跳过并输出不含源内容的错误摘要；持久化来源只使用 SHA 引用。
- [x] 先写 Java 导入器测试：重复 JSONL 不重复插入；坏行只跳过该行；不接受跨项目任务 Run。
- [x] 实现可选路径触发的导入器，以数据库记录解析规范作用域；无安全可解析 scope 的记录不进入台账。
- [x] 用固定夹具连续导入两次验证幂等，并用损坏行混入有效行验证部分失败恢复。

## 任务 4：独立查询页面

- [x] 先写页面/API 客户端测试：请求携带时间、project、task、artifact、status、issue type 过滤；展示空数据、错误、历史可信度和未知证据提示。
- [x] 验证测试因页面/API 客户端缺失而失败。
- [x] 实现 typed API 客户端及带筛选器的独立时间线页，服务端限制分页。
- [x] 新增项目路由、主导航入口；行链接到任务路由，不向浏览器暴露本地历史来源路径。
- [x] 页面组件测试覆盖过滤、历史标签、来源可信度和任务链接。

## 任务 5：端到端针对性验证与收尾

- [x] 执行 V119 迁移集成测试、事件模型/仓储/权限/API 测试。
- [x] 执行历史导入 Python 套件与 Java 导入器幂等/部分损坏测试。
- [x] 执行 Console 台账页面/API 查询参数测试和 TypeScript 构建。
- [x] 对照设计逐项检查安全边界、无文件失败、乱序/迟到、最终状态和来源可信度；PDF/Manifest 行为未改动。
- [x] 检查需求文件格式；工作区已有其他预暂存/未提交改动，因此保留全部暂存区状态，不提交无关内容。
