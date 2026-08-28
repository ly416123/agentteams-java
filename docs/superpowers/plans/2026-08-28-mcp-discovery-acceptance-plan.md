# MCP 跨实例发现验收实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法跟踪进度。

**目标：** 暴露 revision-fenced MCP 发现聚合只读 API，并将多实例快照、旧 revision 隔离和过期 UNKNOWN 状态接入 Kind 验收。

**架构：** Control Plane 通过现有 `McpDiscoveryAggregationService` 从 PostgreSQL 快照计算安全聚合，HTTP 响应只返回状态、digest、实例计数、时间和固定失败分类，不返回 endpoint、credentialRef 或工具正文。Kind 脚本创建一个测试 MCP 资源后，通过 PostgreSQL 测试夹具写入两个受控实例快照，再经公开只读 API 验证聚合行为；真实 MCP 外部服务长期运行仍保持为独立 L6 边界。

**技术栈：** Java 17、Spring MVC、JDBC/PostgreSQL、JUnit 5、Python 3、kubectl、Helm、GitHub Actions。

---

## 文件清单与职责

- 修改：`control-plane/src/main/java/io/agentteams/controlplane/mcp/McpServerController.java` —— 增加发现聚合响应和只读路由。
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/mcp/McpServerControllerTest.java` —— 验证聚合字段、revision 和敏感字段边界。
- 创建：`scripts/run-kind-mcp-discovery.py` —— 创建测试 MCP 资源、写入受控快照并验证 API 聚合。
- 创建：`scripts/test_kind_mcp_discovery_contract.py` —— 锁定脚本安全输出和 CI 接线。
- 修改：`.github/workflows/ci.yml` —— 在资源绑定 ACK 后执行 MCP 跨实例聚合验收。
- 修改：`scripts/validate-kind-manifests.py` —— 校验 MCP 验收脚本及执行顺序。
- 修改：`docs/superpowers/plans/2026-08-28-resource-ack-fencing-plan.md` —— 记录资源 ACK/MCP 验收完成边界。
- 修改：`docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md` —— 记录 Kind 聚合验收结果和真实外部 MCP 边界。

## 任务 1：增加只读聚合 API

- [x] **步骤 1：编写失败测试。** 在 `McpServerControllerTest` 注入 `McpDiscoveryAggregationService` mock，增加 GET `/api/v1/mcp-servers/{id}/discovery` 测试，断言返回 `serverId`、`serverRevision`、`status`、`toolsDigest`、`healthyInstances`、`freshInstances`、`latestObservedAt`、`failureCategories`，且不返回 `endpoint`、`credentialRef` 或工具正文。

- [x] **步骤 2：运行红灯。**

```bash
mvn -q -pl control-plane -am -Dtest=McpServerControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：编译或测试失败，原因是 Controller 尚未接收聚合服务且路由不存在。

- [x] **步骤 3：实现最小 API。** Controller 构造器保留现有调用兼容性；新增 `McpDiscoveryAggregationService` 依赖和 `@GetMapping("/{id}/discovery")`，先调用 `service.get(id)` 获取当前 MCP server revision，再调用 `aggregate(server.id(), server.version())`。响应只复制 `McpDiscoveryAggregate` 的安全字段。

- [x] **步骤 4：运行绿灯。**

```bash
mvn -q -pl control-plane -am -Dtest=McpServerControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：Controller 测试全部通过，旧 CRUD 测试保持通过。

- [x] **步骤 5：Commit。** `933fd46` 提交了本计划；任务 1 的源代码提交在随后的实现提交中完成。

```bash
git add control-plane/src/main/java/io/agentteams/controlplane/mcp/McpServerController.java \
  control-plane/src/test/java/io/agentteams/controlplane/mcp/McpServerControllerTest.java
git commit -m "feat(MCP): 暴露发现聚合只读接口（任务 1/3）"
```

## 任务 2：实现 Kind 跨实例聚合验收脚本和契约

- [x] **步骤 1：编写失败契约测试。** 测试 `run-kind-mcp-discovery.py` 存在，必须包含 MCP 创建请求的幂等键、PostgreSQL 快照写入、API 聚合读取、`AVAILABLE`/`UNKNOWN`/revision fencing 断言，并禁止打印 endpoint、credential、工具正文或完整响应。

- [x] **步骤 2：运行红灯。**

```bash
python3 -m unittest scripts/test_kind_mcp_discovery_contract.py
```

预期：因脚本不存在而失败。

- [x] **步骤 3：实现脚本。** 脚本接受 `--base-url`、`--postgres-pod`、`--namespace` 和 `--timeout`；通过 `POST /api/v1/mcp-servers` 创建 enabled=false 的测试资源；用固定安全 UUID、实例名和 digest 写入两个当前 revision 的快照；验证 API 返回 `AVAILABLE`、2 个 healthy/fresh 实例和共同 digest；再写入旧 revision 快照确认不影响当前结果；最后把当前快照过期并确认 `UNKNOWN`。所有 HTTP 写请求带唯一 `Idempotency-Key`，失败只输出固定字段摘要。

- [x] **步骤 4：运行绿灯。**

```bash
python3 -m unittest scripts/test_kind_mcp_discovery_contract.py
python3 scripts/validate-kind-manifests.py
```

预期：契约和 Kind 清单校验通过。

- [x] **步骤 5：Commit。** `scripts/run-kind-mcp-discovery.py` 与契约测试已加入待提交实现。

```bash
git add scripts/run-kind-mcp-discovery.py scripts/test_kind_mcp_discovery_contract.py
git commit -m "test(MCP): 增加跨实例发现聚合验收（任务 2/3）"
```

## 任务 3：接入 CI、同步文档和交付

- [x] **步骤 1：增加 CI 步骤和静态顺序契约。** 在现有资源绑定 ACK 验收之后调用脚本，复用已有 `18080` Control Plane port-forward 和 `postgresql-0`；不安装真实外部 MCP 服务，不注入凭据。

- [x] **步骤 2：本地 Docker/Colima 验证。**

```bash
source deploy/dev-env.sh
mvn -q -pl control-plane -am test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m py_compile scripts/*.py
python3 scripts/validate-kind-manifests.py
helm lint deploy/helm/agentteams-java
git diff --check
```

- [x] **步骤 3：本地 Kind 验收。** 使用现有 Colima Kind 集群执行 `run-kind-mcp-discovery.py`，输出 `KIND_MCP_DISCOVERY_OK`；Control Plane 两个副本滚动启动并 Ready，PostgreSQL 保持 Ready；其他组件沿用此前已通过的 Kind 验收。

- [x] **步骤 4：更新文档。** 只把“快照聚合 API、旧 revision 隔离、过期 UNKNOWN”记录为本批完成；保留真实外部 MCP 服务长期运行、凭证注入和 L6 作为后续受控环境边界。

- [x] **步骤 5：Commit、推送并确认 CI。** 代码提交 `b84982d` 已存在并已推送；当前分支与 `origin/main` 同步，契约测试、Kind 清单校验、Python 语法检查、Helm lint/template、Control Plane 定向回归和脚本全量回归均通过。

```bash
git add .github/workflows/ci.yml scripts/validate-kind-manifests.py \
  docs/superpowers/plans/2026-08-28-resource-ack-fencing-plan.md \
  docs/superpowers/specs/2026-08-26-observability-scale-closure-design.md \
  docs/superpowers/plans/2026-08-28-mcp-discovery-acceptance-plan.md
git commit -m "docs(MCP): 接入跨实例发现验收并同步边界（任务 3/3）"
git push origin main
run_id="$(gh run list --branch main --limit 1 --json databaseId --jq '.[0].databaseId')"
gh run watch "${run_id}" --interval 15 --exit-status
```

## 完成边界

本计划完成表示 MCP 发现快照的公开安全聚合和 Kind 跨实例状态验收已完成，不表示已连接真实外部 MCP 服务、完成凭证注入长期运行或完成 Linux/KVM L6 演练。
