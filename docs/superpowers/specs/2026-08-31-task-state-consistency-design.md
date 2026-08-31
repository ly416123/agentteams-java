# Task 状态一致性与终态单调保护设计

**日期：** 2026-08-31

**状态：** 已确认，进入实现

**范围：** Control Plane 的 Task 主状态、Task Run 运行投影、过程事件、子任务和结果 Manifest 的一致性保护与对账。

## 1. 背景与目标

`tasks.phase` 是任务生命周期的权威状态。Worker 事件进入 Control Plane 后，一条路径通过
`ExecutionEventService` 推进 Task/Attempt/Lease，另一条路径通过
`ControlPlaneTaskExecutionObservationAdapter` 维护 `task_runs`、过程事件、任务树和结果
Manifest。两条路径的职责不同，但当前 `task_runs` 的 upsert 会直接使用晚到观察事件的状态，
可能出现主 Task 已经 `SUCCEEDED` 而运行投影又被晚到的 `RUNNING` 覆盖的漂移。

本设计的目标是：

1. 保持 Task 主状态机为唯一权威，不引入第二套状态机；
2. 让 Task Run 状态只沿合法方向单调推进，终态不可被非终态或冲突终态覆盖；
3. 允许晚到过程事件继续进入审计/回放，但不改变已完成运行的状态；
4. 提供有租约保护、可审计、可重复执行的跨表一致性对账；
5. 发现漂移时先告警和记录证据，不在第一阶段自动修改 Task 权威状态。

## 2. 状态权威与一致性规则

### 2.1 权威关系

| 数据 | 权威级别 | 职责 |
|---|---|---|
| `tasks.phase` | 唯一权威 | 对外任务生命周期和合法状态转换 |
| `task_attempts.phase` | 执行事实 | 当前/历史 Attempt 的执行状态，由 Task 事件带 Attempt/Lease 校验后更新 |
| `agent_leases.status` | 执行租约事实 | 控制 Worker 是否仍拥有执行权 |
| `task_runs.status` | 运行投影 | 为过程、进度和结果查询提供按 run 的投影，不得反向覆盖 Task |
| `task_process_events` | 追加事实 | 记录可回放过程事件，不拥有 Task 状态 |
| `task_result_manifests` | 结果投影 | 记录某个 run 的成功/失败/取消结果，不拥有 Task 状态 |

### 2.2 Task Run 状态单调性

允许的运行投影顺序为：

```text
QUEUED → RUNNING → SUCCEEDED
                  → FAILED
                  → CANCELLED
```

具体规则：

- `QUEUED` 可以推进为 `RUNNING` 或终态；
- `RUNNING` 可以推进为终态，也可以重复写入 `RUNNING`；
- `SUCCEEDED`、`FAILED`、`CANCELLED` 是不可回退终态；
- 任何晚到的 `RUNNING`、`QUEUED` 或冲突终态不得覆盖已有终态；
- `started_at` 使用首次有效启动时间；
- `completed_at` 只在首次进入终态时写入，且终态后不得清空；
- `updated_at` 取数据库已有值和观察时间的较晚值，避免乱序事件倒退时间；
- `task_id`、租户作用域和 `run_id` 的关联必须保持不变，关联不匹配的观察事件拒绝写入。

如果两个不同终态事件竞争同一个 Run，Run 投影保留先落库的终态，并由一致性对账检查
与权威 `tasks.phase` 的差异。第一阶段不根据到达顺序自动修改 `tasks.phase`。

## 3. 事件处理设计

### 3.1 Task 主状态路径

`ExecutionEventService.apply` 继续负责：

- 校验 Task `expectedVersion`；
- 校验 Attempt、Lease 和 Worker 身份；
- 应用领域状态机；
- 更新 Task、Attempt、Lease；
- 终态时释放租约、释放 Team 调度占用和请求 Sandbox 回收；
- 在同一事务中登记 Artifact 和领域事件。

旧版本事件保持现有策略：已落后于当前版本的事件确认并丢弃，领先事件延迟重投，非法终态
转换确认并记录日志。

### 3.2 过程观察路径

`TaskExecutionObservationPort` 继续只负责过程投影。`ensureRun` 改为使用数据库内的
单调状态表达式，晚到观察事件可以追加到 `task_process_events`，但不能回退 `task_runs`。
过程事件仍通过 `(run_id, sequence)` 顺序读取，并用事件 ID/唯一约束避免重复产生副作用。

过程事件中的 Payload 继续执行既有安全策略：对外可见内容只允许脱敏摘要、状态、进度和
产物引用，不保存 Prompt、完整模型响应、凭据或内部链路细节。

## 4. 一致性对账

### 4.1 检查内容

新增只读对账服务和数据库 Lease 保护的定时 Job，按批次扫描最近活跃或最近进入终态的 Run，
检查：

1. `task_runs.task_id` 是否存在且租户作用域一致；
2. `task_runs.status` 是否与 `tasks.phase` 处于允许映射关系；
3. 终态 Task 是否仍有活动 Attempt/Lease；
4. 终态 Run 是否存在对应 Result Manifest，Manifest 状态是否与 Run 状态一致；
5. `task_process_events` 的序号是否从 0 开始且无重复；
6. `task_subtasks` 的成功/失败/取消状态是否能解释进度投影；
7. Run 是否存在多个互相冲突的终态事实。

### 4.2 允许映射

| Task 状态 | 允许的 Run 状态 |
|---|---|
| `DRAFT`、`QUEUED`、`PAUSED` | 无 Run 或 `QUEUED` |
| `ASSIGNED`、`ACCEPTED`、`RUNNING` | `QUEUED` 或 `RUNNING` |
| `SUCCEEDED` | `SUCCEEDED` |
| `FAILED` | `FAILED` |
| `CANCELLED`、`REJECTED` | `CANCELLED` 或无结果 Run |

对账发现问题时写入 `task_state_consistency_issues`：保存 Task/Run 标识、租户、问题类型、
观察到的状态、首次发现时间、最近发现时间、出现次数和解决时间。相同问题幂等更新，不重复
制造告警事件；下一次扫描恢复后标记 `resolved_at`。

第一阶段只做记录、日志和 Micrometer 计数，不自动修复 `tasks.phase`、不重建结果、不删除
过程事件。后续如需自动修复，必须以独立设计明确修复权限、审批和回滚。

### 4.3 调度与失败处理

- Job 使用现有 `SchedulerLeaseService`，多副本只允许一个实例扫描；
- 每批限制最大记录数，单条检查失败不阻塞后续记录；
- 数据库暂时不可用时保留租约失败日志并等待下一轮，不生成虚假的“已对账”；
- 对账问题本身不改变业务 Task 状态，避免诊断逻辑造成二次破坏；
- 提供内部查询服务用于运维排查，不在本批新增面向最终客户的管理 API。

## 5. 事务边界与并发

- Task 主状态、Attempt、Lease 和领域事件继续在原有事务中更新；
- Run 状态和过程事件在观察事务中更新；Run 行锁保证同一 Run 的序号分配和状态 upsert 顺序；
- 对账使用普通一致性读，不阻塞正常 Task 执行；
- 所有唯一约束和幂等检查保留在数据库层，不能只依赖 JVM 内存；
- 观察事件先落过程事实，再由单调规则更新 Run 投影时，重复或乱序事件不会破坏终态。

## 6. 测试验收

### 单元测试

- `RUNNING` 观察不能覆盖 `SUCCEEDED`、`FAILED` 或 `CANCELLED`；
- 终态时间不可被晚到事件清空或倒退；
- 重复观察不会重复创建 Run/过程事实；
- 不匹配的 Task/Run/租户关联被拒绝；
- 对账器能识别并归类 Task/Run/Manifest/Attempt/Lease 漂移；
- 已恢复问题会被幂等标记为 resolved。

### PostgreSQL/Testcontainers 测试

- 通过真实 PostgreSQL 验证终态单调 upsert；
- 验证问题表的唯一键、重复扫描合并和恢复标记；
- 验证 Flyway 空库迁移和现有迁移兼容。

### 集成门禁

- Control Plane 全量 Maven 测试；
- Docker-backed Testcontainers 测试，必须解析本机 Colima socket；
- Python 源码/批量发布/API 契约测试；
- Helm lint 和 `git diff --check`；
- 本批不修改 Worker、Operator、K3s、RuntimeClass、镜像或部署链路，因此不触发 L5 验收。

## 7. 不在本批范围

- 不修改 Task 主状态机的业务语义；
- 不引入事件溯源重放或全量投影重建；
- 不自动修复权威 Task 状态；
- 不实现新的客户侧状态 API；
- 不处理完整财务账单、L6 验收或企业审批流程。
