# 使用量维度完整性审计实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 为项目当前调用者 scope 增加使用量维度完整性审计接口，识别历史兼容数据中的缺失维度，并提供可重复验证的覆盖率统计。

**架构：** Control Plane 在现有 `UsageQueryService` 中复用时间范围解析和 `ScopeFilter`，对 `model_call_audits` 执行一次聚合查询；HTTP Controller 只返回窗口、总调用数和每个固定维度的 present/missing/coverage，不暴露任何维度值或审计正文。缺失定义为 `NULL`、空字符串或全空白字符串，零调用窗口的覆盖率为 `null`。

**技术栈：** Java 21、Spring JDBC、Spring MVC、PostgreSQL、JUnit 5、Mockito、MockMvc、BigDecimal。

---

## 文件清单

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageQueryService.java` —— 增加当前 scope 的完整性聚合查询和值对象。
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/usage/UsageController.java` —— 增加 `GET /api/v1/usage/dimensions/completeness` 响应映射。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/usage/UsageQueryServiceTest.java` —— 验证固定维度、空值规则、时间范围和 scope 参数。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/usage/UsageControllerTest.java` —— 验证接口响应和参数透传。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/persistence/FoundationRepositoryIT.java` —— 在真实 PostgreSQL 中验证完整/缺失维度聚合。
- 修改：`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md` —— 标记完整维度审计纵切完成边界。

## 任务 1：Usage 完整性聚合领域查询

- [x] **步骤 1：编写失败测试。** 在 `UsageQueryServiceTest` 增加测试：Mock 聚合结果包含 12 次调用，并让每个维度返回不同 present 数；调用 `completeness(from, to)` 后断言返回 9 个固定名称、missing 等于 `totalCalls - present`、coverage 使用 6 位小数并且 SQL 使用 `NULLIF(BTRIM(...), '')`；同时验证当前 Principal 的 tenant/project/team 参数进入查询。
- [x] **步骤 2：运行测试验证失败。** 测试先因 `UsageCompleteness` 类型和 `completeness` 方法不存在而编译失败，确认红灯原因是功能缺失。

运行：

```bash
mvn -q -pl control-plane -am -Dtest=UsageQueryServiceTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，原因是 `UsageQueryService.completeness` 和完整性值对象尚不存在。

- [x] **步骤 3：实现最少查询代码。** 在 `UsageQueryService` 增加一个聚合 SQL，按 `occurred_at` 和 `ScopeFilter` 过滤，使用 `COUNT(*) FILTER (WHERE NULLIF(BTRIM(CAST(field AS text)), '') IS NOT NULL)` 统计 present；固定字段顺序为 `tenantId, projectId, teamId, workerId, taskId, provider, model, tool, quotaDimension`。新增 `UsageDimensionCompleteness` 和 `UsageCompleteness` record，使用 `BigDecimal` 按 6 位、`HALF_UP` 计算 coverage；总调用数为 0 时 coverage 为 `null`。
- [x] **步骤 4：运行测试验证通过。** `UsageQueryServiceTest` targeted Maven 测试退出码为 0。

运行同一条 Maven 命令，预期 `UsageQueryServiceTest` 全部通过。

## 任务 2：完整性 HTTP API

- [x] **步骤 1：编写失败测试。** 在 `UsageControllerTest` 增加 `GET /api/v1/usage/dimensions/completeness` 测试，验证 `from/to` 透传、响应含 `totalCalls`、9 个维度的 `present/missing/coverage`，并且响应不含 `tenantId`、`projectId`、`dimensionValue` 等明细字段。
- [x] **步骤 2：运行测试验证失败。** 测试先因端点不存在返回 404，确认红灯原因是接口缺失。

运行：

```bash
mvn -q -pl control-plane -am -Dtest=UsageControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译失败，原因是 Controller 没有完整性映射方法和值对象。

- [x] **步骤 3：实现最少 Controller 映射。** 在 `UsageController` 增加 `@GetMapping("/dimensions/completeness")`，调用 `service.completeness(from, to)`；响应 record 只包含 `from/to/totalCalls/dimensions`，维度只包含 `name/present/missing/coverage`。不增加请求体，不接受 tenant/project 参数，权限继续由现有 `/api/v1/usage` 资源链路和 `PrincipalContext` scope 负责。
- [x] **步骤 4：运行测试验证通过。** `UsageControllerTest` targeted Maven 测试退出码为 0。

运行同一条 Maven 命令，预期 `UsageControllerTest` 全部通过。

## 任务 3：回归、文档和本机验证

- [x] **步骤 1：运行 Control Plane 全量测试。** `mvn -q -pl control-plane -am test` 退出码为 0。

```bash
mvn -q -pl control-plane -am test
```

预期：退出码为 0，所有测试通过。

- [x] **步骤 2：运行脚本、Manifest 和 Helm 校验。** 107 个 Python 测试通过，Python 编译、Kind manifest、可观测性和 Helm lint 均通过。

```bash
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m py_compile scripts/*.py
python3 scripts/validate-kind-manifests.py
python3 scripts/validate-observability.py
helm lint deploy/helm/agentteams-java
```

预期：脚本测试无失败，输出 `KIND_MANIFESTS_OK`、`OBSERVABILITY_OK`，Helm lint 无错误。

- [x] **步骤 3：使用本机 Docker/Kind 做迁移和 API 相关验收。** 已执行 `source deploy/dev-env.sh`，确认 Docker context 为 `colima`、Docker Server 29.5.2、`kubectl config current-context` 为 `kind-agentteams`；FoundationRepositoryIT 已在真实 Testcontainers PostgreSQL 中验证 53 个迁移和完整/缺失维度聚合。
- [x] **步骤 4：更新路线图并做计划自检。** 路线图已将使用量维度完整性审计第一纵切标为进入 `main`，并保留历史回填、长期运行告警和 L6 压测边界；计划自检未发现占位内容、未解析类型或 scope 语义矛盾。
- [x] **步骤 5：提交并同步 main。** 已在 `main` 创建原子提交；推送前再次执行 fresh verification，并确认工作区与远程分支状态。

```bash
git diff --check
git status --short
git add control-plane/src/main/java/io/agentteams/controlplane/usage/UsageQueryService.java \
  control-plane/src/main/java/io/agentteams/controlplane/usage/UsageController.java \
  control-plane/src/test/java/io/agentteams/controlplane/usage/UsageQueryServiceTest.java \
  control-plane/src/test/java/io/agentteams/controlplane/usage/UsageControllerTest.java \
  docs/superpowers/plans/2026-08-28-usage-dimension-completeness-plan.md \
  docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md
git commit -m "feat(usage): 增加维度完整性审计接口"
git push origin main
```

预期：工作区干净，`main` 与 `origin/main` 指向同一提交；根据代码变更触发 GitHub Actions，提交后读取实际 CI 状态，不以本地测试替代 CI 结果。

## 范围边界

本计划只交付可查询的完整性统计，不实现价格同步、预算通知、历史数据修复、Prometheus 告警规则、真实外部 MCP 长期运行或 L6 压力测试。
