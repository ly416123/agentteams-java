# 会话、统一渠道与 Agent 评测整体实施计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans。步骤使用复选框（- [ ]）语法跟踪进度。

**目标：** 按已批准规格分阶段落地 Conversation、统一渠道协议、Matrix Room 绑定和 Evaluation / Quality Plane，并保持现有 Task、Result、Artifact 和 Matrix 能力兼容。

**架构：** Control Plane 继续作为 Task 和领域事实源。application-contracts 定义统一 Command/Event/Timeline 数据结构；Manager、Console、SDK、Matrix 只负责适配；Evaluation Plane 只读消费 Evidence Bundle。

**技术栈：** Java 21、Spring Boot 3.4、JDBC、PostgreSQL/Flyway、NATS Outbox、TypeScript/React、Python 3、JUnit 5、Playwright、unittest。

---

## 子计划

- [ ] 执行计划 A：Conversation 与统一渠道协议
- [ ] 执行计划 B：Matrix Room 绑定与 Matrix 命令适配
- [ ] 执行计划 C：Evaluation / Quality Plane

文件：

- docs/superpowers/plans/2026-09-23-conversation-unified-channel-protocol.md
- docs/superpowers/plans/2026-09-23-matrix-room-binding.md
- docs/superpowers/plans/2026-09-23-evaluation-quality-plane.md

## 依赖顺序

1. 先执行计划 A，产出 CommandEnvelope、UnifiedEventEnvelope、TimelineItem、Conversation 查询接口和 sourceConversationId。
2. 再执行计划 B，把 Matrix Room 绑定切换到 Conversation / Task 主上下文，并将 Matrix 命令归一化为统一 CommandEnvelope。
3. 最后执行计划 C，基于计划 A 的 Task Event、Process Event、Task Tree、Result Manifest 和 Artifact 引用生成 Evidence Bundle。
4. 每个计划完成后运行自身模块测试；三个计划完成后运行项目级回归。

## 集成验收

- [ ] Console、SDK、Matrix 提交相同语义的 task.start 时，Control Plane 产生相同语义的命令审计和 Task Event。
- [ ] 一个 Conversation 可创建多个 Task；一个 Task 只能关联一个 source Conversation。
- [ ] Conversation Message 与 Task Event 分表保存，但统一时间线稳定排序、去重并按可见性过滤。
- [ ] Matrix Room 只有一个主绑定上下文，投影失败不影响 Control Plane 事实。
- [ ] Agent 结果、Artifact、任务拆分和过程证据可生成 Evidence Bundle。
- [ ] Evaluation Pass 与 Task success 分开保存；Quality Gate 未显式启用时不改变 Task 状态。
- [ ] 旧 Manager Conversation API、现有 Matrix 命令、现有 Task/Result API 的兼容测试通过。

## 全局验证命令

~~~bash
mvn -q -DskipTests=false test
python3 -m unittest discover -s scripts -p 'test_*.py' -v
npm --prefix console test -- --run
~~~

## 提交策略

每个子计划的每个任务形成独立 commit；commit 只包含对应任务文件。合并前运行 git diff --check、定向测试和项目级回归。
