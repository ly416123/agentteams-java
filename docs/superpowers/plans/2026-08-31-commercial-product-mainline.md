# 商业产品主线第一批纵切实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 在不引入 L6 生产环境成本的前提下，补齐源码构建可信度、Team 级资源绑定和 Worker Template Registry 三个商业化基础能力。

**架构：** 继续以 Control Plane/PostgreSQL 为权威状态，Team Revision 负责不可变的团队配置，Template Revision 负责可复用的 AgentSpec 蓝图；发布和实例化都重新执行资源 scope、状态和 digest 校验。源码指纹独立于业务模块，在 CI 构建上下文、镜像元数据、SBOM/provenance 和 Release Manifest 之间建立同一份 canonical fingerprint。

**技术栈：** Java 17、Spring Boot、Spring JDBC、PostgreSQL/Flyway、JUnit 5、AssertJ、Python 3 标准库、Bash、GitHub Actions、Docker Buildx、Helm。

---

## 文件边界

第一批只修改以下职责明确的文件组：

- 构建可信度：`scripts/source-fingerprint.py`、`scripts/validate-release-manifest.py`、`scripts/test_source_fingerprint.py`、`scripts/test_batch_b_release_contract.py`、`.github/workflows/release.yml`、`.github/workflows/promote.yml`、`scripts/fixtures/release-manifest-valid.json`、`scripts/fixtures/release-manifest-invalid.json`。
- Team 资源绑定：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevision.java`、`TeamRevisionService.java`、`TeamRevisionRepository.java`、`TeamRevisionPublishValidator.java`、`control-plane/src/main/java/io/agentteams/controlplane/config/EffectiveConfigRequest.java`、`EffectiveConfigComposer.java`、对应 Team/Config 单元测试和当前最大 Flyway 版本之后的连续迁移。
- Template Registry：新增 `control-plane/src/main/java/io/agentteams/controlplane/template/` 下的领域记录、Repository、Service、Controller、异常和 JDBC 实现；新增对应迁移、单元测试、JDBC/Testcontainers 测试和 API 契约测试。
- 状态同步：仅在上述接口真正改变后更新 `docs/superpowers/specs/2026-08-23-alibaba-agentteams-commercial-gap-requirements.md`、`docs/superpowers/specs/2026-08-26-product-ecosystem-expansion-design.md`、`docs/superpowers/specs/2026-08-26-remaining-capabilities-roadmap-design.md` 和 `README.md`。

SDK、Console 页面和 Webhook Adapter 不在本批修改范围内；它们使用本批冻结的公共 API 作为下一批输入。

### 任务 1：源码指纹强制构建约束

**目标：** 防止发布流程只校验 Git SHA，却没有证明镜像、SBOM、provenance 和 Release Manifest 来自同一份实际构建源码。

**指纹规则：** `source-fingerprint.py` 接受仓库根目录和可选排除路径；默认只纳入 Git index 中已跟踪的普通文件，按 POSIX 相对路径排序，逐项计算文件内容 SHA-256，再计算 `path + NUL + contentDigest + LF` 的整体 SHA-256。排除 `.git/`、`target/`、`console/node_modules/`、`console/dist/`、`.local/` 和测试运行产物。未跟踪文件不进入发布源码指纹，因此 release workflow 必须先执行 `git diff --exit-code` 和 `git ls-files --others --exclude-standard` 检查，避免 Docker `COPY . .` 将未跟踪源码带入镜像。

**文件：**
- 创建：`scripts/source-fingerprint.py`
- 创建：`scripts/test_source_fingerprint.py`
- 修改：`scripts/validate-release-manifest.py`
- 修改：`scripts/test_batch_b_release_contract.py`
- 修改：`.github/workflows/release.yml`
- 修改：`.github/workflows/promote.yml`
- 修改：`scripts/fixtures/release-manifest-valid.json`
- 修改：`scripts/fixtures/release-manifest-invalid.json`

- [ ] **步骤 1：编写失败的指纹测试。** 测试排序稳定、内容变化导致指纹变化、排除目录不影响指纹、未跟踪文件不会被纳入计算、空仓库和缺失路径返回非零错误。

- [ ] **步骤 2：运行指纹测试确认正确失败。**

  运行：`python3 -m unittest scripts/test_source_fingerprint.py -v`

  预期：因 `scripts/source-fingerprint.py` 不存在而失败，不能出现 Python 语法错误。

- [ ] **步骤 3：实现最小 canonical fingerprint 工具。** 提供 `fingerprint` 子命令输出 64 位小写十六进制值，提供 `verify` 子命令比较期望值；所有文件路径使用 Git 的 POSIX 路径，拒绝仓库根目录之外的路径。

- [ ] **步骤 4：运行指纹测试确认通过。**

  运行：`python3 -m unittest scripts/test_source_fingerprint.py -v`

  预期：全部测试通过，输出包含 `OK`。

- [ ] **步骤 5：先写 Release Manifest 负向测试。** 增加 `source_fingerprint` 字段、组件级 `source_fingerprint` 字段和每个组件的 `build_context`；测试缺失、大小写错误、组件值不一致以及 Manifest fingerprint 与当前 checkout 不一致均被拒绝。

- [ ] **步骤 6：运行 Release 契约测试确认正确失败。**

  运行：`python3 -m unittest scripts/test_batch_b_release_contract.py -v`

  预期：fixture 尚未增加新字段时，失败原因是缺失源码指纹字段。

- [ ] **步骤 7：接入发布和晋级流程。** Release workflow 在 Buildx 前执行干净工作树检查并计算 fingerprint；每个镜像加入 `org.opencontainers.image.source`、`org.opencontainers.image.revision` 和 `io.agentteams.source-fingerprint` label；CycloneDX、SLSA predicate 和 Manifest 写入同一 fingerprint。Promote workflow 重新计算 checkout fingerprint，并校验 Manifest、Chart provenance 和组件 fingerprint 一致，失败时 fail-closed。

- [ ] **步骤 8：运行契约测试确认通过。**

  运行：`python3 -m unittest scripts/test_source_fingerprint.py scripts/test_batch_b_release_contract.py -v`

  预期：全部测试通过。

- [ ] **步骤 9：提交任务 1。**

  运行：`git diff --check && git add scripts .github/workflows/release.yml .github/workflows/promote.yml && git commit -m "ci(发布): 强制校验源码构建指纹"`

### 任务 2：Team 级 Model/Skill/MCP 绑定与 Effective Config 收口

**目标：** 在现有 Team Revision overlay 和 AgentSpec resourceBindings 基础上，增加不可变、可审计、可重新校验的 Team 资源绑定，并让 Effective Config provenance 同时记录 binding revision 和稳定 digest。

**数据契约：** Team Revision 增加 `List<TeamResourceBinding>`；绑定包含 `type`、`resourceId`、`resourceRevision`、`digest`，类型限定为 `MODEL`、`FILE`、`SKILL`、`MCP_SERVER`。Published revision 不可更新；修改绑定只能创建新 Draft。发布前通过现有 `TeamRevisionPublishValidator` 校验引用存在、已启用、scope 匹配和 digest 匹配。

**文件：**
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevision.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionService.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionRepository.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/team/TeamRevisionPublishValidator.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/config/EffectiveConfigRequest.java`
- 修改：`control-plane/src/main/java/io/agentteams/controlplane/config/EffectiveConfigComposer.java`
- 创建：`control-plane/src/main/resources/db/migration/` 下当前最大版本之后的 `team_resource_bindings` 连续迁移文件
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamRevisionServiceTest.java`
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/team/TeamRevisionRepositoryTest.java`
- 修改：`control-plane/src/test/java/io/agentteams/controlplane/config/EffectiveConfigComposerTest.java`

- [ ] **步骤 1：编写绑定值对象和 Effective Config 失败测试。** 覆盖空类型、空资源 ID、非正 revision、空 digest、重复绑定；覆盖相同 canonical 输入得到相同 digest、绑定顺序变化不改变 digest、旧绑定不能覆盖新 binding revision。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl control-plane -Dtest=TeamRevisionServiceTest,TeamRevisionRepositoryTest,EffectiveConfigComposerTest test`

  预期：因绑定字段和请求行为尚未存在而失败。

- [ ] **步骤 3：实现绑定值对象和 canonical 排序。** 增加严格校验的 `TeamResourceBinding`，在 Team Revision 和 Effective Config provenance 中使用不可变列表；canonicalizer 按类型、资源 ID、revision、digest 排序并去重，重复键但 digest 不同直接拒绝。

- [ ] **步骤 4：运行定向测试确认通过。**

  运行：`mvn -q -pl control-plane -Dtest=TeamRevisionServiceTest,EffectiveConfigComposerTest test`

  预期：新增单元测试和既有测试全部通过。

- [ ] **步骤 5：编写 JDBC 迁移和 Repository 失败测试。** 绑定表使用 `(team_id, team_revision, resource_type, resource_id)` 唯一键，保存 revision/digest；加载 Team Revision 必须按稳定顺序返回绑定；rollback 必须复制绑定。

- [ ] **步骤 6：运行 JDBC 测试确认迁移缺失。**

  运行：`mvn -q -pl control-plane -Dtest=TeamRevisionRepositoryTest test`

  预期：新增持久化测试因绑定表不存在而失败。

- [ ] **步骤 7：实现迁移和事务内绑定持久化。** `createDraft`、`createRollback`、publish CAS 和绑定读取保持同一事务；不修改已发布 revision；旧数据库升级后既有 revision 的绑定为空列表。

- [ ] **步骤 8：运行 JDBC 和模块测试确认通过。**

  运行：`mvn -q -pl control-plane -Dtest=TeamRevisionRepositoryTest,TeamRevisionServiceTest,EffectiveConfigComposerTest test`

  预期：全部通过；Docker 不可用时必须保留明确失败证据，不得把测试改成静默跳过。

- [ ] **步骤 9：补齐 Team Controller 请求/响应和 API 契约测试。** 创建 Draft、更新 Draft、review、publish、rollback 的请求体支持 bindings，响应返回稳定绑定摘要但不返回 Secret value、credentialRef 内容或完整外部响应。

- [ ] **步骤 10：运行 API 契约和全模块测试。**

  运行：`mvn -q -pl control-plane test && python3 scripts/validate-api-contract.py`

  预期：Java 测试和 API 契约均退出 0。

- [ ] **步骤 11：提交任务 2。**

  运行：`git diff --check && git add control-plane docs && git commit -m "feat(团队): 收口 Team 资源绑定与有效配置"`

### 任务 3：Worker Template Registry 最小可用闭环

**目标：** 提供模板 Draft/Review/Published/Deprecated 生命周期、不可变 revision、实例化幂等和实例升级入口；实例化复用 AgentSpec 创建/发布/部署边界，不直接操作 Kubernetes。

**数据契约：**

- `worker_templates` 保存模板 ID、scope、名称、当前发布 revision、版本和审计时间；scope 内名称唯一。
- `worker_template_revisions` 保存不可变 canonical AgentSpec JSON、digest、状态、审核信息、创建者和版本；`(template_id, revision)` 唯一。
- `worker_template_instances` 保存模板 revision、生成的 AgentSpec ID、Worker ID、幂等键、状态和当前升级 revision；实例化幂等键唯一。
- 生命周期为 `DRAFT -> REVIEWING -> PUBLISHED -> DEPRECATED`；发布 revision 不可修改。

**文件：**
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplate.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateRevision.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateInstance.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/TemplateStatus.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateRepository.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/JdbcWorkerTemplateRepository.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/template/WorkerTemplateService.java`
- 创建：`control-plane/src/main/java/io/agentteams/controlplane/api/WorkerTemplateController.java`
- 创建：`control-plane/src/main/resources/db/migration/` 下当前最大版本之后的 `worker_templates` 连续迁移文件
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/template/WorkerTemplateServiceTest.java`
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/template/JdbcWorkerTemplateRepositoryTest.java`
- 创建：`control-plane/src/test/java/io/agentteams/controlplane/api/WorkerTemplateControllerTest.java`

- [ ] **步骤 1：编写模板领域失败测试。** 覆盖名称和 scope 校验、canonical JSON digest、已发布 revision 不可变、非法状态转换、重复实例化返回同一实例、不同请求复用同一幂等键被拒绝。

- [ ] **步骤 2：运行定向测试确认失败。**

  运行：`mvn -q -pl control-plane -Dtest=WorkerTemplateServiceTest,WorkerTemplateControllerTest test`

  预期：因模板类型和服务尚不存在而失败。

- [ ] **步骤 3：实现内存领域服务。** 服务只处理状态机、canonical JSON、digest、幂等请求哈希和当前主体 scope；所有模板资源引用交给已有 AgentSpecReferenceValidator 和 ResourceAuthorizationService 校验。

- [ ] **步骤 4：运行定向测试确认通过。**

  运行：`mvn -q -pl control-plane -Dtest=WorkerTemplateServiceTest,WorkerTemplateControllerTest test`

  预期：模板领域和 Controller 单元测试全部通过。

- [ ] **步骤 5：编写 JDBC 迁移和 Repository 失败测试。** 验证空库迁移、scope 内名称唯一、revision 不可修改、实例幂等唯一键、升级记录和事务回滚。

- [ ] **步骤 6：实现迁移和 JDBC Repository。** 所有写入使用数据库唯一约束和事务；实例化先持久化模板实例意图，再调用 AgentSpec Service，失败时记录稳定失败状态，不产生第二个 AgentSpec；升级只允许从已发布的新模板 revision 进入。

- [ ] **步骤 7：运行 JDBC/Testcontainers 定向测试。**

  运行：`mvn -q -pl control-plane -Dtest=JdbcWorkerTemplateRepositoryTest test`

  预期：Docker 可用时所有测试通过；Docker 不可用时测试必须以非零退出并保留诊断。

- [ ] **步骤 8：实现公共 HTTP API。** 提供模板 CRUD、revision 创建、review、publish、instantiate、instance upgrade 和查询接口；写接口强制 `Idempotency-Key`，状态命令使用 `expectedVersion`；错误复用现有 `ApiErrorHandler`。

- [ ] **步骤 9：补充 API 正负向测试。** 覆盖跨项目访问、未发布 revision 实例化、AgentSpec 引用不存在、Skill/MCP digest 不匹配、重复实例化和版本冲突。

- [ ] **步骤 10：同步商业规格和 README。** 将 Template Registry 标记为仓库侧已实现，明确企业审批、真实外部 Skill 扫描和 L6 仍不属于本批完成条件；补充最小 curl/API 示例。

- [ ] **步骤 11：运行完整验证并提交任务 3。**

  运行：`mvn -q test && python3 -m unittest discover -s scripts -p 'test_*.py' && helm lint deploy/helm/agentteams-java && git diff --check`

  提交：`git add control-plane docs README.md && git commit -m "feat(模板): 添加 Worker Template Registry 闭环"`

## 集成检查点

- [ ] 任务 1 完成后先运行源码指纹和发布契约测试，再进入任务 2。
- [ ] 任务 2 完成后运行 Control Plane 全量测试，确认 Team Revision 和 Effective Config 的旧调用方兼容。
- [ ] 任务 3 完成后运行 Maven、Python、Helm、Console 构建/lint；任何失败都在当前分支修复，不把失败留给后续 SDK 或 Console 分支。
- [ ] 最终只报告实际运行过的命令和退出码；不把 L6、真实外部审批、真实企业 Secret Manager 或最终账单描述为本批已完成。
