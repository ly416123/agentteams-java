# 使用量预算与预测第一纵切实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 为当前项目作用域增加可恢复的预算策略、线性成本预测和评估查询，并明确 `UNPRICED` 与 `INSUFFICIENT_DATA` 的安全边界。

**架构：** Control Plane 使用显式 tenant/project scope 读取 `model_call_audits`，以 `BigDecimal` 计算当前预算窗口实际成本和可解释线性预测；预算策略、评估和去重事件持久化到 PostgreSQL，策略更新使用 expectedVersion 条件写入。HTTP API 只接受预算策略字段，不接受调用方伪造 tenant/project，所有读写复用现有 Usage 权限和 PrincipalContext。

**技术栈：** Java 17、Spring MVC、Spring JDBC、Flyway、PostgreSQL、JUnit 5、Mockito。

---

## 文件清单与职责

- 创建：`control-plane/src/main/resources/db/migration/V52__usage_budget_forecast.sql` —— 创建预算策略、评估和评估事件表及约束、索引、唯一键。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetPolicy.java` —— 预算策略、评估状态和值对象。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetRepository.java` —— 预算策略和评估持久化端口。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/usage/JdbcUsageBudgetRepository.java` —— PostgreSQL 映射、expectedVersion 更新和窗口事件去重。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetService.java` —— 项目作用域校验、窗口计算、BigDecimal 预测和评估持久化。
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetController.java` —— `PUT/GET` 策略与评估查询 API。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/usage/UsageBudgetServiceTest.java` —— 预测公式、阈值、无价格和数据不足边界。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/usage/JdbcUsageBudgetRepositoryTest.java` —— SQL 字段、唯一指纹、版本条件和脱敏字段约束。
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/usage/UsageBudgetControllerTest.java` —— scope API、expectedVersion、响应字段和评估分页参数。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/persistence/FoundationRepositoryIT.java` —— 验证最新 Flyway 迁移从空库升级后可写入预算表。
- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md` —— 记录预算第一纵切完成边界。
- 修改：`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md` —— 更新 W4 进度，保留完整维度审计、通知事件和 L6 边界。

## 任务 1：预算领域模型和预测规则

- [ ] **步骤 1：编写失败测试。** 在 `UsageBudgetServiceTest` 增加三个行为测试：有效观测达到 1 小时后按 `actual / elapsed * period` 预测；有效观测不足 1 小时返回 `INSUFFICIENT_DATA` 且预测为空；成本没有任何可计价观测时返回 `UNPRICED` 且预测为空。测试使用固定 Clock、显式 scope 和内存 Usage provider，不依赖系统时间。

- [ ] **步骤 2：运行红灯。**

```bash
mvn -q -pl control-plane -am -Dtest=UsageBudgetServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，原因是 `UsageBudgetService`、策略和值对象尚不存在。

- [ ] **步骤 3：实现最少领域代码。** 新增 `UsageBudgetPolicy`、`UsageBudgetEvaluation` 和 `UsageBudgetService`；服务只接受已解析的 `AuthorizationService.Scope`，窗口按 UTC `period` 对齐，成本使用 `BigDecimal`，有效观测小于 3600 秒时禁止生成预测，所有无价格输入保持 `null` 金额和 `UNPRICED` 状态。

- [ ] **步骤 4：运行绿灯。**

```bash
mvn -q -pl control-plane -am -Dtest=UsageBudgetServiceTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：3 个预算规则测试通过。

- [ ] **步骤 5：Commit。**

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetPolicy.java \
  control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetService.java \
  control-plane/src/test/java/io/agentteams/controlplane/usage/UsageBudgetServiceTest.java
git commit -m "feat(usage): 增加预算预测领域规则（任务 1/3）"
```

## 任务 2：预算 PostgreSQL 持久化

- [ ] **步骤 1：编写失败 SQL 测试。** 在 `JdbcUsageBudgetRepositoryTest` 锁定策略插入字段、`UPDATE ... WHERE version = ?`、评估事件 `(policy_id, window_start, threshold)` 唯一指纹和查询不得包含 prompt/response/token 等敏感正文；在迁移测试中断言 V52 创建三张表。

- [ ] **步骤 2：运行红灯。**

```bash
mvn -q -pl control-plane -am -Dtest=JdbcUsageBudgetRepositoryTest,FoundationRepositoryIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，原因是 JDBC Repository 和 V52 迁移尚不存在。

- [ ] **步骤 3：实现最少持久化。** 创建 V52：策略保存 tenant/project、币种、周期秒数、软/硬阈值、forecast window、状态、version 和时间；评估保存窗口、实际/预测金额、状态和评估时间；事件表使用稳定 fingerprint 唯一约束。Repository 的更新必须返回新版本，expectedVersion 不匹配抛出现有 `OptimisticLockFailure`，金额列使用 `NUMERIC`，不保存供应商正文。

- [ ] **步骤 4：运行绿灯。**

```bash
mvn -q -pl control-plane -am -Dtest=JdbcUsageBudgetRepositoryTest,FoundationRepositoryIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：JDBC SQL 测试和 Flyway/Testcontainers 迁移测试通过。

- [ ] **步骤 5：Commit。**

```bash
git add control-plane/src/main/resources/db/migration/V52__usage_budget_forecast.sql \
  control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetRepository.java \
  control-plane/src/main/java/io/agentteams/controlplane/usage/JdbcUsageBudgetRepository.java \
  control-plane/src/test/java/io/agentteams/controlplane/usage/JdbcUsageBudgetRepositoryTest.java \
  control-plane/src/test/java/io/agentteams/controlplane/persistence/FoundationRepositoryIT.java
git commit -m "feat(usage): 持久化预算策略和评估（任务 2/3）"
```

## 任务 3：项目作用域 API 和交付

- [ ] **步骤 1：编写失败 API 测试。** 在 `UsageBudgetControllerTest` 增加 `PUT /api/v1/usage/budgets/{policyId}`、`GET /api/v1/usage/budgets` 和 `GET /api/v1/usage/budgets/{policyId}/evaluations?limit=20` 测试；断言请求不能覆盖 tenant/project，expectedVersion 会透传，响应包含状态/金额/窗口而不包含审计正文，limit 仅允许 1 到 100。

- [ ] **步骤 2：运行红灯。**

```bash
mvn -q -pl control-plane -am -Dtest=UsageBudgetControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，原因是预算 Controller 和 API 响应模型尚不存在。

- [ ] **步骤 3：实现最少 API 和装配。** 新增 `UsageBudgetController` 与 `UsageBudgetService` 的 Spring Bean 装配；`PUT` 只从 PrincipalContext 取得 tenant/project，`GET` 只返回当前项目策略，评估查询限制 page size 并按窗口倒序返回。为 `/api/v1/usage` 复用现有 `USAGE_READ`/`QUOTA_WRITE` 授权，不新增绕过 scope 的内部接口。

- [ ] **步骤 4：运行完整本地验证。**

```bash
mvn -q -pl control-plane -am test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m py_compile scripts/*.py
python3 scripts/validate-kind-manifests.py
python3 scripts/validate-observability.py
helm lint deploy/helm/agentteams-java
git diff --check
```

预期：Java、Python、Kind/可观测性校验和 Helm lint 全部退出码为 0。

- [ ] **步骤 5：本机 Docker/Colima 验收。** 使用 `source deploy/dev-env.sh && mvn -q -pl control-plane -am test` 验证迁移和 Testcontainers；若需要 Kind，只运行不依赖真实外部计费系统的预算 API smoke，不注入凭据。

- [ ] **步骤 6：同步文档并提交。** 文档只将策略、线性预测、`UNPRICED`、`INSUFFICIENT_DATA` 和项目作用域查询标记为完成；完整维度覆盖率、预算通知投递、最终账单和 L6 生产压力仍保持未完成。

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetService.java \
  control-plane/src/main/java/io/agentteams/controlplane/usage/UsageBudgetController.java \
  control-plane/src/test/java/io/agentteams/controlplane/usage/UsageBudgetControllerTest.java \
  docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md \
  docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md
git commit -m "feat(usage): 暴露项目预算评估 API（任务 3/3）"
```

- [ ] **步骤 7：推送并确认 CI。**

```bash
git push origin main
run_id="$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "${run_id}" --interval 15 --exit-status
```

## 完成边界

本计划完成表示预算策略、可解释预测、金额精度、无价格/数据不足状态、版本保护和项目作用域 API 已具备 L1-L4 验收；不表示最终财务账单、外部价格同步、集中预算通知、完整维度覆盖率或 L6 生产长期运行已完成。
