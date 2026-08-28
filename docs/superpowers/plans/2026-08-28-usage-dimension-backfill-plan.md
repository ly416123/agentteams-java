# 使用量历史维度回填实施计划

> **面向 AI 代理的工作者：** 该计划已执行完成；保留逐项证据，便于后续审计和重复验证。

**目标：** 在不猜测租户归属、不覆盖已有事实的前提下，回填历史 `model_call_audits` 中能够从持久化任务事实确定的使用量维度。

**方案：** 通过 Flyway V55 执行幂等 SQL。tenant/project 仅从任务 `spec.scope` 回填；team 优先从任务 `teamId` 回填，其次仅在 `team_tasks` 唯一关联时回填；worker 仅在调用发生时间落入唯一 `task_assignments` 窗口时回填。已有非空值、作用域冲突和多候选关系不覆盖、不猜测。

## 验收清单

- [x] 先增加迁移契约测试，验证数据来源、空值识别和无默认租户/项目回填。
- [x] 增加 V55 `usage_dimension_backfill`，覆盖 scope、team 和唯一 worker 关联。
- [x] 使用真实 PostgreSQL 从 V54 升级到 V55，验证可恢复记录和无法关联记录。
- [x] 保留 `NULL`/空白和冲突记录，确保历史数据不会被错误归属。
- [x] 更新路线图与可观测规模化规格，明确历史回填第一纵切已完成，仍保留长期 MCP、价格同步和 L6 压测边界。
- [x] 使用本机 Colima/Docker 完成模块测试、脚本、Helm 和迁移验证。

## 验证命令

```bash
source deploy/dev-env.sh
mvn -q -pl control-plane -am test
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m py_compile scripts/*.py
python3 scripts/validate-kind-manifests.py
python3 scripts/validate-observability.py
helm lint deploy/helm/agentteams-java
helm template agentteams deploy/helm/agentteams-java
git diff --check
```
